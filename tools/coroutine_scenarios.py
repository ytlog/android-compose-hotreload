"""Deterministic UI-driven coroutine/Flow completions across real Kotlin/D8 reloads."""
from collections import Counter
import json
import re
import subprocess
from pathlib import Path

SOURCE = Path(__file__).resolve().parent.parent / 'sample/src/main/java/dev/compose/hotreload/sample'
COROUTINE_SOURCES = [SOURCE / name for name in (
    'CoroutineLedger.kt', 'CoroutineWork.kt', 'CoroutineFlows.kt', 'CoroutineState.kt', 'CoroutineEditActivity.kt')]


class CoroutineCases:
    def __init__(self, context):
        self.c, self.d = context, context.device
        self.events, self.pending, self.collectors = [], [], {}
        self.requests = self.deliveries = self.cancellations = self.ignored = 0
        self.tasks = self.collector_count = self.emissions = 0
        self.identities = {}
        self.map = self.collect = 'v1'
        self.guard_version = 'v1'
        self.extra = False
        self.finally_version = 'v1'

    def event(self, *values):
        self.events.extend(values)

    def request(self, id):
        self.requests += 1
        self.pending.append(id)
        self.event('request:' + id)

    def output(self, id, value):
        self.event('output:' + id + ':' + value)

    def check(self, texts):
        for prefix, value in self.identities.items():
            assert self.c.value(texts, prefix) == value, prefix + ' changed'
        for value in ['Objects retained: true', 'Coroutine draft: coroutine42',
                      f'Counts: {self.requests}/{self.deliveries}/{self.cancellations}/{self.ignored}',
                      'Pending: ' + (','.join(self.pending) or 'none'),
                      f'Starts: {self.tasks}/{self.collector_count}/{self.emissions}']:
            assert value in texts, 'Missing ' + value
        actual = self.c.value(texts, 'Trace: ').split(';') if self.events else []
        assert Counter(actual) == Counter(self.events), f'Lost/duplicate/unexpected event: {Counter(actual)-Counter(self.events)} / {Counter(self.events)-Counter(actual)}'
        # Global scheduling may interleave collectors; each collector's delivery order must be exact.
        for id in set(e.split(':')[1] for e in self.events if e.startswith('output:')):
            prefix = 'output:' + id + ':'
            assert [e for e in actual if e.startswith(prefix)] == [e for e in self.events if e.startswith(prefix)], 'Output order changed: ' + id
        return actual

    def invariant(self):
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.sample').strip() == self.c.app_pid(), 'Process changed'
        texts = self.c.snapshot()
        actual = self.check(texts)
        (self.c.case_dir() / 'coroutine-observation.json').write_text(json.dumps(dict(
            identities=self.identities, expected=self.events, actual=actual, counts=[self.requests, self.deliveries, self.cancellations, self.ignored],
            pending=self.pending, starts=[self.tasks, self.collector_count, self.emissions]), indent=2))
        return texts

    def settle(self):
        def matches(texts):
            try:
                self.check(texts)
                return True
            except (AssertionError, StopIteration):
                return False
        self.d.wait_for(matches, 'exact coroutine requests, callbacks and ordered Flow values', timeout=30)
        self.invariant()

    def tap(self, label):
        self.d.tap_text(label)
        self.settle()

    def start(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field
        x1, y1, x2, y2 = map(int, field.groups())
        self.d.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        self.d.adb('shell', 'input', 'text', 'coroutine42')
        self.d.tap_text('Ready')
        texts = self.d.wait_for(lambda ts: 'Coroutine draft: coroutine42' in ts, 'coroutine input')
        self.identities = {p: self.c.value(texts, p) for p in ['Coroutine state: ', 'Coroutine remember: ']}
        self.invariant()

    def task(self, kind):
        if kind == 'Cold old':
            id = 'cold-old'
        else:
            self.tasks += 1
            id = 't' + str(self.tasks)
        self.event('start:' + id)
        if kind.startswith('Cold'):
            self.event('cold-start:' + id)
        self.request(id)
        self.tap(kind)
        return id

    def reply(self, id, value=None, named=False, terminal=True, guarded=False):
        pending = self.pending.pop(0)
        assert pending == id
        self.deliveries += 1
        self.event('deliver:' + id)
        if named:
            self.event('resume:' + id.removesuffix('-next'))
        if guarded:
            self.event(f'finally-{self.finally_version}:{id}')
        if value is not None:
            self.output(id.removesuffix('-next') if named else id, value)
        if terminal:
            self.event('end:' + (id.removesuffix('-next') if named else id) + ':ok')
        self.tap('Reply')

    def named_reply(self, id, value):
        if self.extra:
            assert self.pending.pop(0) == id
            self.deliveries += 1
            self.event('deliver:' + id)
            self.request(id + '-next')
            self.tap('Reply')
            id += '-next'
        self.reply(id, value, named=True)

    def subscribe(self, kind):
        self.collector_count += 1
        id = 'c' + str(self.collector_count)
        self.collectors[id] = kind
        self.event('subscribe:' + id)
        if kind == 'State':
            self.output(id, f'collect-{self.collect}/map-{self.map}/s0')
        self.tap(kind)
        return id

    def emit(self):
        self.emissions += 1
        value = 'e' + str(self.emissions)
        self.event('emit:' + value)
        for id, kind in self.collectors.items():
            if kind == 'Collect':
                self.output(id, f'collect-{self.collect}/map-{self.map}/{value}')
            elif kind == 'Gated':
                self.request(id + '-' + value)
        self.tap('Emit')
        return value

    def stop_flows(self):
        for id in self.collectors:
            for pending in self.pending:
                if pending.startswith(id + '-'):
                    self.cancellations += 1
                    self.event('cancel-request:' + pending)
            self.event('unsubscribe:' + id, 'end:' + id + ':cancel')
        self.collectors.clear()
        self.tap('Stop flows')

    def late(self):
        id = self.pending.pop(0)
        self.ignored += 1
        self.event('late:' + id)
        self.tap('Reply')

    def abi(self, stem):
        paths = sorted((self.c.case_dir() / 'classes/dev/compose/hotreload/sample').glob(stem + '*.class'))
        assert paths
        output = ''.join(subprocess.check_output(['javap', '-p', '-c', '-v', str(p)], text=True) for p in paths)
        assert 'kotlin.coroutines.jvm.internal.DebugMetadata' in output
        assert 'label' in output and 'invokeSuspend' in output
        (self.c.case_dir() / (stem + '-abi.txt')).write_text(output)

    def run(self):
        work, flows = SOURCE/'CoroutineWork.kt', SOURCE/'CoroutineFlows.kt'
        def accept(name, edits, action=None):
            self.c.accepted(name, edits, action=action or self.invariant)
        old = self.task('Named')
        accept('suspend-pending-body', [(work, 'before-v1', 'before-v2'), (work, 'after-v1', 'after-v2')])
        self.abi('CoroutineWork')
        def named_resume():
            self.named_reply(old, 'before-v1/reply/after-v3')
            self.named_reply(self.task('Named'), 'before-v2/reply/after-v3')
        accept('suspend-resume-and-new', [(work, 'after-v2', 'after-v3')], named_resume)
        old = self.task('Lambda')
        accept('suspend-lambda-resume', [(work, 'lambda-before-v1', 'lambda-before-v2'), (work, 'lambda-after-v1', 'lambda-after-v2')],
               lambda: (self.reply(old, 'lambda-before-v1/reply/lambda-after-v2'),
                        self.reply(self.task('Lambda'), 'lambda-before-v2/reply/lambda-after-v2')))
        old = self.task('Nested')
        accept('suspend-nested-resume', [(work, 'outer-v1', 'outer-v2'), (work, 'child-v1', 'child-v2')],
               lambda: (self.reply(old, 'outer-v2/child-v2/reply'), self.reply(self.task('Nested'), 'outer-v2/child-v2/reply')))
        old = self.task('Named')
        self.extra = True
        accept('suspend-append-point', [(work, '// NEXT_SUSPENSION', 'ledger.await("$id-next")')],
               lambda: (self.named_reply(old, 'before-v2/reply/after-v3'),
                        self.named_reply(self.task('Named'), 'before-v2/reply/after-v3')))
        self.abi('CoroutineWork')
        old = self.task('Guarded')
        self.finally_version = 'v2'
        def cancel():
            self.cancellations += 1
            self.event('cancel-request:' + old, 'finally-v2:' + old, 'end:' + old + ':cancel')
            self.tap('Cancel task')
            self.late()
            self.reply(self.task('Guarded'), 'guard-v2/reply', guarded=True)
        accept('suspend-cancel-late-callback', [(work, 'finally-v1', 'finally-v2'), (work, 'guard-v1', 'guard-v2')], cancel)
        old = self.task('Guarded')
        self.finally_version = self.guard_version = 'v3'
        def fail():
            assert self.pending.pop(0) == old
            self.deliveries += 1
            self.event('deliver:' + old, 'finally-v3:' + old, 'uncaught:IllegalStateException', 'end:' + old + ':IllegalStateException')
            self.tap('Fail')
            self.reply(self.task('Guarded'), 'guard-v3/reply', guarded=True)
        accept('suspend-exception-recovery', [(work, 'finally-v2', 'finally-v3'), (work, 'guard-v2', 'guard-v3')], fail)
        accept('flow-created-before-update', [(flows, 'factory-v1', 'factory-v2'), (flows, 'cold-v1', 'cold-v2')],
               lambda: (self.reply(self.task('Cold old'), 'factory-v1/reply/cold-v2'),
                        self.reply(self.task('Cold new'), 'factory-v2/reply/cold-v2')))
        old = self.task('Cold new')
        accept('flow-suspended-builder', [(flows, 'cold-v2', 'cold-v3')],
               lambda: (self.reply(old, 'factory-v2/reply/cold-v3'),
                        self.reply(self.task('Cold new'), 'factory-v2/reply/cold-v3')))
        self.subscribe('Collect'); self.emit()
        self.map = self.collect = 'v2'
        def active_flow():
            self.emit(); self.subscribe('Collect'); self.emit(); self.stop_flows()
        accept('flow-active-and-new-collectors', [(flows, 'map-v1', 'map-v2'), (flows, 'collect-v1', 'collect-v2')], active_flow)
        id = self.subscribe('Gated'); event = self.emit()
        # A resumed map emits to its original collector, without a second subscription/request.
        def complete_gated(id, event):
            request = id + '-' + event
            assert self.pending.pop(0) == request
            self.deliveries += 1
            self.event('deliver:' + request)
            self.output(id, f'gated-v2/{event}/reply')
            self.tap('Reply')
        def gated_resume():
            complete_gated(id, event)
            self.emit(); self.stop_flows(); self.late()
            fresh = self.subscribe('Gated'); value = self.emit(); complete_gated(fresh, value); self.stop_flows()
        accept('flow-suspended-map-cancel-restart', [(flows, 'gated-v1', 'gated-v2')], gated_resume)
        self.abi('CoroutineFlows')
        id = self.subscribe('State')
        self.map = 'v3'
        def state_flow():
            self.event('state:s1'); self.output(id, 'collect-v2/map-v3/s1'); self.tap('State value')
            self.event('state:s1'); self.tap('State value')
            self.stop_flows()
        accept('flow-state-conflation', [(flows, 'map-v2', 'map-v3')], state_flow)
        self.run_recovery()

    def run_recovery(self):
        work = SOURCE/'CoroutineWork.kt'
        old = self.task('Guarded'); self.subscribe('Collect')
        guard = SOURCE/'StateAdmissionGuard.java'
        self.c.edit(guard, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        self.c.edit(work, 'guard-' + self.guard_version, 'guard-v4')
        host, elapsed = self.c.trigger('coroutine-atomic-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host
        self.invariant(); self.c.common(); self.emit()
        self.reply(old, 'guard-' + self.guard_version + '/reply', guarded=True)
        self.reply(self.task('Guarded'), 'guard-' + self.guard_version + '/reply', guarded=True)
        self.c.common()
        self.c.results.append(dict(name='coroutine-atomic-rejection', result='PASS', reloadSeconds=round(elapsed,3), nativeRejection=True))
        self.c.accepted('coroutine-recovery', [(guard, 'public abstract class StateAdmissionGuard', 'public class StateAdmissionGuard')],
               action=lambda: (self.emit(), self.reply(self.task('Guarded'), 'guard-v4/reply', guarded=True), self.stop_flows()))
        assert not self.pending and not self.collectors
