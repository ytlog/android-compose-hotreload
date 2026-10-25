"""Lifecycle, framework caches, compilation and session contracts on a real device."""
from collections import Counter
import json
import html
from pathlib import Path
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'sample/src/main/java/dev/compose/hotreload/sample'
LOGIC = SOURCE / 'IntegrationLogic.kt'
WIRE = SOURCE / 'IntegrationWire.kt'
STATE = SOURCE / 'IntegrationState.kt'
SLOTS = SOURCE / 'IntegrationSlots.kt'
EFFECTS = SOURCE / 'IntegrationEffects.kt'
INIT = SOURCE / 'IntegrationInitializer.java'
LIB = ROOT / 'sample/lib/src/main/java/dev/compose/hotreload/sample/lib/IntegrationBuild.kt'
RES = ROOT / 'sample/src/main/res/values/hot_reload.xml'
GUARD = SOURCE / 'StateAdmissionGuard.java'
ADDED = SOURCE / 'IntegrationAdded.kt'
INTEGRATION_SOURCES = [SOURCE / name for name in (
    'IntegrationLogic.kt', 'IntegrationWire.kt', 'IntegrationSlots.kt', 'IntegrationEffects.kt',
    'IntegrationState.kt', 'IntegrationEditActivity.kt', 'IntegrationInitializer.java', 'IntegrationInitLedger.java')] + [LIB, RES]
INTEGRATION_GENERATED = [ADDED]


class IntegrationCases:
    def __init__(self, context):
        self.c, self.d = context, context.device
        self.model = self.root = None
        self.route, self.active = 'list', '0/0'
        self.events = []
        self.sequence = 0
        self.listener = self.effect = None
        self.scope = None
        self.saved = 0
        self.effect_version = self.listener_version = 'v1'
        self.latest = 'first'
        self.callback_prefix = ''

    def check(self, texts):
        for prefix, expected in [('Integration model: ', self.model), ('Integration root: ', self.root),
                                 ('Route: ', self.route), ('Active: ', self.active),
                                 ('Integration draft: ', 'integration42')]:
            if expected is not None:
                assert self.c.value(texts, prefix) == expected, prefix + ' changed'
        assert 'Saved: ' + str(self.saved) in texts
        raw = self.c.value(texts, 'Trace: ')
        actual = raw.split(';') if raw else []
        assert Counter(actual) == Counter(self.events), f'Unexpected/missing events: {Counter(actual)-Counter(self.events)} / {Counter(self.events)-Counter(actual)}'
        # Every individual task's events must remain ordered; disposal/start ordering across tasks may vary.
        for id in {e.split(':')[1] for e in self.events if ':' in e}:
            assert [e for e in actual if len(e.split(':')) > 1 and e.split(':')[1] == id] == [e for e in self.events if len(e.split(':')) > 1 and e.split(':')[1] == id]
        return actual

    def invariant(self):
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.sample').strip() == self.c.app_pid()
        texts = self.c.snapshot()
        actual = self.check(texts)
        (self.c.case_dir() / 'integration-observation.json').write_text(json.dumps(dict(
            model=self.model, root=self.root, route=self.route, active=self.active,
            expected=self.events, actual=actual), indent=2))
        return texts

    def settle(self):
        def matches(texts):
            try:
                self.check(texts)
                return True
            except (AssertionError, StopIteration):
                return False
        self.d.wait_for(matches, 'exact lifecycle and request ledger')
        return self.invariant()

    def tap(self, text):
        self.d.tap_text(text)
        return self.settle()

    def start(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field
        x1, y1, x2, y2 = map(int, field.groups())
        self.d.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        self.d.adb('shell', 'input', 'text', 'integration42')
        self.d.tap_text('Ready')
        texts = self.d.wait_for(lambda ts: 'Integration draft: integration42' in ts, 'integration input')
        self.model, self.root = [self.c.value(texts, p) for p in ['Integration model: ', 'Integration root: ']]
        self.invariant()

    def passed(self, name, action):
        self.c.new_case(name)
        action()
        self.c.common()
        self.c.results.append(dict(name=name, result='PASS'))
        print('PASS: ' + name, flush=True)

    def enter_expected(self):
        self.sequence += 1; self.listener = 'l' + str(self.sequence)
        self.sequence += 1; self.effect = 'effect' + str(self.sequence)
        self.events += ['register:' + self.listener, 'start:' + self.effect]
        self.active = '1/1'

    def leave_expected(self):
        if self.listener:
            self.events.append('dispose:' + self.listener); self.listener = None
        if self.effect:
            self.events.append('end:' + self.effect); self.effect = None
        if self.scope:
            self.events.append('end:' + self.scope); self.scope = None
        self.active = '0/0'

    def enter(self):
        self.route = 'detail:42'; self.enter_expected(); self.tap('Enter')

    def leave(self):
        self.leave_expected(); self.route = 'list'; self.tap('Leave')

    def fire(self):
        if self.listener:
            self.events.append(f'callback:{self.listener}:listener-{self.listener_version}:{self.callback_prefix}{self.latest}')
        self.tap('Fire')

    def reply(self):
        self.events += [f'output:{self.effect}:effect-{self.effect_version}:reply', 'end:' + self.effect]
        self.effect = None; self.active = '1/0'; self.tap('Reply')

    def effects(self):
        self.passed('effects-enter', self.enter)
        self.effect_version = self.listener_version = 'v2'
        self.c.accepted('effects-live-body', [(LOGIC, 'effect-v1:', 'effect-v2:'), (LOGIC, 'listener-v1:', 'listener-v2:')], action=self.fire)
        def latest():
            self.latest = 'latest'; self.tap('Latest'); self.fire()
        self.passed('effects-updated-state', latest)
        self.callback_prefix = 'direct:'
        self.c.accepted('effects-registered-lambda-body', [(EFFECTS, 'listenerResult(latest)', 'listenerResult("direct:" + latest)')], action=self.fire)
        self.passed('effects-pending-completion', self.reply)
        self.leave_expected(); self.enter_expected()
        self.c.accepted('effects-source-key-change', [(EFFECTS, 'DisposableEffect(state.effectKey)', 'DisposableEffect(state.effectKey + 100)'), (EFFECTS, 'LaunchedEffect(state.effectKey)', 'LaunchedEffect(state.effectKey + 100)')], action=self.settle)
        def key():
            self.leave_expected(); self.enter_expected(); self.tap('Next key')
        self.passed('effects-key-replacement', key)
        def scope():
            self.sequence += 1; self.scope = 'scope' + str(self.sequence)
            self.events.append('start:' + self.scope); self.active = '1/2'; self.tap('Scope task')
        self.passed('effects-remembered-scope', scope)
        def remove():
            self.leave_expected(); self.tap('Toggle'); self.fire()
        self.passed('effects-component-removal', remove)
        def restore():
            self.enter_expected(); self.tap('Toggle'); self.fire()
        self.passed('effects-component-return', restore)
        self.passed('effects-navigation-disposal', self.leave)

    def scopes(self):
        self.passed('scope-enter', self.enter)
        self.tap('Slot count: 10')
        assert 'Slot count: 11' in self.invariant()
        self.c.accepted('scope-initializer-reset', [(SLOTS, 'mutableIntStateOf(10)', 'mutableIntStateOf(20)')], visible=['Slot count: 20'])
        self.tap('Slot count: 20')
        self.c.accepted('scope-slot-addition', [(SLOTS, '// EXTRA_SLOT', 'val extra = remember { "added" }; Text("Extra: $extra")')], visible=['Slot count: 20', 'Extra: added'])
        self.tap('Slot count: 20')
        self.c.accepted('scope-slot-removal', [(SLOTS, 'val extra = remember { "added" }; Text("Extra: $extra")', '// EXTRA_SLOT')], visible=['Slot count: 20'])
        self.passed('scope-leave', self.leave)

    def lifecycle(self):
        self.passed('lifecycle-enter-with-parameter', self.enter)
        self.saved += 1; self.tap('Saved: ')
        def recreate():
            self.leave_expected(); self.enter_expected()
            old = self.root; self.root = None
            self.d.tap_text('Recreate')
            texts = self.d.wait_for(lambda ts: any(t.startswith('Integration root: ') and t != 'Integration root: ' + old for t in ts), 'new Activity composition')
            self.root = self.c.value(texts, 'Integration root: ')
            self.settle()
        self.passed('lifecycle-recreate-saved-state', recreate)
        def configuration():
            for _ in range(2):
                self.leave_expected(); self.enter_expected()
                old = self.root; self.root = None
                self.d.tap_text('Theme')
                texts = self.d.wait_for(lambda ts: any(t.startswith('Integration root: ') and t != 'Integration root: ' + old for t in ts), 'configuration recreated Activity')
                self.root = self.c.value(texts, 'Integration root: ')
                self.settle()
        self.passed('lifecycle-night-configuration-roundtrip', configuration)
        def back():
            self.leave_expected(); self.route = 'list'
            self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); self.settle(); self.fire()
        self.passed('lifecycle-system-back', back)
        self.passed('lifecycle-reenter', self.enter)
        self.passed('lifecycle-final-disposal', self.leave)

    def probe(self, label, expected):
        texts = self.tap(label)
        assert 'Probe: ' + expected in [html.unescape(t) for t in texts], f'{label}: expected {expected}; got {self.c.value(texts, "Probe: ")}'

    def network(self):
        requests, lock = [], threading.Lock()
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def do_GET(self):
                entry = dict(path=self.path, released=threading.Event(), closed=False)
                with lock: requests.append(entry)
                if not entry['released'].wait(180): return
                try:
                    body = b'wire'
                    self.send_response(200); self.send_header('Content-Length', str(len(body))); self.send_header('Connection', 'close'); self.end_headers(); self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError): entry['closed'] = True
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.d.adb('reverse', 'tcp:18765', 'tcp:' + str(server.server_port))
        def request(fresh=False, path='/v1'):
            n = len(requests); self.sequence += 1; id = 'http' + str(self.sequence)
            self.events.append('request:' + id); self.tap('HTTP new' if fresh else 'HTTP old')
            deadline = time.monotonic() + 20
            while len(requests) == n and time.monotonic() < deadline: time.sleep(.05)
            assert len(requests) == n + 1 and requests[-1]['path'] == path, 'Missing/duplicate request or stale endpoint'
            return id, requests[-1]
        def release(id, entry, version='v1'):
            self.events += [f'response:{id}:network-{version}:wire', 'finish:' + id]
            entry['released'].set(); self.settle()
        try:
            id, entry = request()
            self.c.accepted('http-pending-business-update', [(LOGIC, 'network-v1:', 'network-v2:')], action=lambda: release(id, entry, 'v2'))
            def cancel():
                id, entry = request(); self.events += ['cancel:' + id, 'finish:' + id]
                self.tap('Cancel HTTP'); entry['released'].set(); time.sleep(.3); self.invariant()
            self.passed('http-cancel-late-response', cancel)
            def both():
                id, entry = request(); release(id, entry, 'v2')
                id, entry = request(True, '/v2'); release(id, entry, 'v2')
            self.c.accepted('http-retrofit-annotation-cache', [(WIRE, '@GET("v1")', '@GET("v2")')], action=both)
            self.passed('json-cached-adapter-baseline', lambda: self.probe('JSON old', 'json:42'))
            def added():
                self.probe('JSON old', 'json:42:0'); self.probe('JSON new', 'json:42:7')
            self.c.accepted('json-added-field-cache-boundary', [(WIRE, '// DTO_FIELDS', 'var extra: Int = 0'), (WIRE, 'fun display(): String = value', 'fun display(): String = "$value:$extra"')], action=added)
            def retyped():
                texts = self.tap('JSON old')
                observed = self.c.value(texts, 'Probe: ')
                assert observed in ['json-error:NoSuchFieldError', 'json-error:IllegalArgumentException'], 'Retired cached Field was not safely rejected: ' + observed
                (self.c.case_dir() / 'cached-adapter-boundary.json').write_text(json.dumps({'oldAdapter': observed}))
                self.probe('JSON new', 'json:42:7')
            self.c.accepted('json-retyped-field-cache-boundary', [(WIRE, 'var value: String = "initial"', 'var value: Int = -1')], action=retyped)
            assert [e['path'] for e in requests] == ['/v1', '/v1', '/v1', '/v2'], 'Unsolicited HTTP request'
        finally:
            for entry in requests: entry['released'].set()
            server.shutdown(); server.server_close(); thread.join(5)
            self.d.adb('reverse', '--remove', 'tcp:18765')
            (self.c.evidence / 'http-requests.json').write_text(json.dumps([dict(path=e['path'], closed=e['closed']) for e in requests], indent=2))

    def build(self):
        self.passed('build-inline-baseline', lambda: self.probe('Build', 'constant-v1/inline-v1/default-v1/type-v1:String'))
        self.c.accepted('build-cross-module-inline-constant-default-reified', [(LIB, 'constant-v1', 'constant-v2'), (LIB, 'inline-v1', 'inline-v2'), (LIB, 'default-v1', 'default-v2'), (LIB, 'type-v1', 'type-v2')], action=lambda: self.probe('Build', 'constant-v2/inline-v2/default-v2/type-v2:String'))
        self.passed('generated-cached-serializer-baseline', lambda: self.probe('Generated old', 'generated:{"value":"wire"}'))
        self.c.accepted('generated-missing-field-default', [(STATE, r'Json.decodeFromString(s, "{\"value\":\"wire\"}")', 'Json.decodeFromString(s, "{}")')], action=lambda: self.probe('Generated old', 'generated:{"value":"generated-v1"}'))
        self.c.accepted('generated-serializer-default-update', [(WIRE, 'generated-v1', 'generated-v2')], action=lambda: self.probe('Generated old', 'generated:{"value":"generated-v2"}'))

        def generated_added():
            self.probe('Generated old', 'generated:{"value":"generated-v2","extra":7}')
            self.probe('Generated new', 'generated:{"value":"generated-v2","extra":7}')
        self.c.accepted('generated-serializer-added-field', [(WIRE, 'data class IntegrationGenerated(val value: String = "generated-v2")', 'data class IntegrationGenerated(val value: String = "generated-v2", val extra: Int = 7)')], action=generated_added)

    def transaction(self):
        self.passed('batch-baseline', lambda: self.probe('Batch', 'batch-v1:Resource text'))
        original_logic = LOGIC.read_bytes()
        self.c.edit(LOGIC, 'fun integrationBatchResult(): String = "batch-v1"', 'fun integrationBatchResult(): String = missingIntegrationSymbol')
        _, seconds = self.c.trigger('batch-compiler-rejection', compile_failure=True)
        self.probe('Batch', 'batch-v1:Resource text'); self.c.common()
        self.c.results.append(dict(name='batch-compiler-rejection', result='PASS', reloadSent=False, seconds=seconds))
        LOGIC.write_bytes(original_logic)
        self.c.accepted('batch-compiler-recovery', [], action=lambda: self.probe('Batch', 'batch-v1:Resource text'))
        self.c.edit(ADDED, None, 'package dev.compose.hotreload.sample\nclass IntegrationAdded { fun text() = "batch-v2" }\n')
        self.c.edit(LOGIC, 'fun integrationBatchResult(): String = "batch-v1"', 'fun integrationBatchResult(): String = IntegrationAdded().text()')
        self.c.edit(RES, '>Resource text<', '>Resource batch<')
        original = GUARD.read_bytes()
        self.c.edit(GUARD, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        host, seconds = self.c.trigger('batch-appended-class-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host
        self.probe('Batch', 'batch-v1:Resource text'); self.c.common()
        self.c.results.append(dict(name='batch-appended-class-rejection', result='PASS', reloadAccepted=False, reloadSeconds=seconds))
        GUARD.write_bytes(original)
        self.c.accepted('batch-appended-class-recovery', [], action=lambda: self.probe('Batch', 'batch-v2:Resource batch'))

        self.passed('initializer-baseline', lambda: self.probe('Init', 'init-v1:1:1'))
        self.c.edit(INIT, 'call < 0', 'call > 0')
        self.c.edit(INIT, 'init-v1:', 'init-v2:')
        self.c.edit(ADDED, 'batch-v2', 'batch-v3')
        self.c.edit(RES, '>Resource batch<', '>Resource init<')
        host, seconds = self.c.trigger('initializer-post-commit-failure')
        assert 'success=false' in host and 'integration-init-failure' in host
        self.probe('Batch', 'batch-v3:Resource init')
        self.probe('Init', 'init-v1:1:2')
        self.c.common()
        self.c.results.append(dict(name='initializer-post-commit-failure', result='PASS', reloadAccepted=False, definitionsApplied=True, userSideEffectsRolledBack=False, reloadSeconds=seconds))
        self.c.accepted('initializer-explicit-recovery', [(INIT, 'call > 0', 'call < 0')], action=lambda: self.probe('Init', 'init-v2:3:3'))

    def session(self):
        self.passed('session-worker-start', lambda: self.tap('Worker'))
        self.tap('Gap')
        (self.c.case_dir() / 'memory-before.txt').write_text(self.d.adb('shell', 'dumpsys', 'meminfo', 'dev.compose.hotreload.sample'))
        for n in range(2, 22):
            def action(n=n):
                self.events.append('session-v' + str(n)); self.probe('Version', 'session-v' + str(n))
                texts = self.tap('Gap'); gap = int(self.c.value(texts, 'Probe: ').removeprefix('gap:'))
                (self.c.case_dir() / 'heartbeat.json').write_text(json.dumps(dict(maximumForegroundGapMs=gap, limitMs=5000)))
                assert gap <= 5000, 'Foreground heartbeat exceeded predeclared 5 second limit'
            self.c.accepted('session-edit-' + str(n), [(LOGIC, 'session-v' + str(n-1) + '"', 'session-v' + str(n) + '"')], action=action)
            if n in [11, 21]:
                (self.c.case_dir() / 'memory-after.txt').write_text(self.d.adb('shell', 'dumpsys', 'meminfo', 'dev.compose.hotreload.sample'))
            assert self.c.results[-1]['reloadSeconds'] <= 120, 'Reload exceeded predeclared 120 second limit'
        def join():
            texts = self.tap('Join'); raw = self.c.value(texts, 'Probe: ').removeprefix('worker:')
            values = dict(item.split('=') for item in raw.split(','))
            assert set(values) == {'session-v' + str(n) for n in range(1, 22)}
            assert all(int(count) > 0 for count in values.values())
            (self.c.case_dir() / 'concurrent-calls.json').write_text(json.dumps(values, indent=2))
        self.passed('session-worker-all-versions', join)

    def run(self, group):
        for name in ['effects', 'scopes', 'lifecycle', 'network', 'build', 'transaction', 'session']:
            if group in [name, 'integration', 'full'] and (group != 'integration' or not self.c.integration_groups or name in self.c.integration_groups):
                getattr(self, name)()
