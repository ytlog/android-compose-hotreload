"""Real AndroidView callbacks and Android Activity result delivery; no synthetic registry dispatch."""
import json

INTEROP_GROUPS = ['androidview', 'native-listener', 'activity-result']


class InteropCases:
    def __init__(self, runner):
        self.r = runner
        self.d = runner.device
        self.root = self.identity = self.tag = None
        self.counts = [0, 0, 0]
        self.audit = [0, 0, 0, 0, 0, 0]
        self.text = 'native-draft'
        self.selection = '2:5'
        self.label = 'v1'
        self.update_note = 'v1'
        self.business = self.click_version = self.watch_version = 'v1'
        self.result_version = self.result_business = 'v1'
        self.probes = {'Native probe: ': 'none', 'Watch probe: ': 'none', 'Result probe: ': 'none'}

    @staticmethod
    def get(texts, prefix):
        return next(t[len(prefix):] for t in texts if t.startswith(prefix))

    def audit_text(self):
        return 'Interop audit: ' + ':'.join(map(str, self.audit)) + ':true'

    def check(self, texts=None):
        texts = texts or self.r.snapshot()
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.framework').strip() == self.r.pid
        expected = ['Root: ' + self.root, 'Interop: ' + self.identity, 'Native anchors: true',
                    self.audit_text(),
                    'Interop counts: ' + ':'.join(map(str, self.counts))]
        assert any(t.casefold() == ('Native label ' + self.label).casefold() for t in texts), 'Native label not updated'
        expected += [p + v for p, v in self.probes.items()]
        for value in expected:
            assert value in texts, 'Missing ' + value
        (self.r.case / 'interop-observation.json').write_text(json.dumps({
            'root': self.root, 'identity': self.identity, 'viewTag': self.tag,
            'counts': self.counts, 'audit': self.audit, 'probes': self.probes,
            'nativeText': self.text, 'nativeSelection': self.selection, 'updateNote': self.update_note}, indent=2))
        return texts

    def settle(self):
        def ready(texts):
            try:
                self.check(texts)
                return True
            except (AssertionError, StopIteration):
                return False
        self.d.wait_for(ready, 'original native objects, exact callbacks and registry lifetime')
        self.check()

    def inspect(self):
        self.d.tap_text('Inspect native')
        value = self.get(self.r.snapshot(), 'Native snapshot: ')
        tag, actual = value.split(':', 1)
        if self.tag is None:
            self.tag = tag
        assert tag == self.tag, 'Native View replaced'
        assert actual == self.text + ':' + self.selection + ':update-' + self.update_note, 'Native editor state changed: ' + actual
        self.settle()

    def start(self):
        self.r.new_case('interop-startup')
        texts = self.d.wait_for(lambda t: 'Draft list' in t, 'fixture list')
        self.root = self.get(texts, 'Root: ')
        self.enter()
        self.r.passed('interop-startup')

    def enter(self):
        self.d.tap_text('Open interop')
        texts = self.d.wait_for(lambda t: 'Native anchors: true' in t, 'native View factory')
        identity = self.get(texts, 'Interop: ')
        assert identity != self.identity, 'Disposed state reused'
        self.identity = identity
        self.tag = None
        self.counts = [0, 0, 0]
        self.probes = dict.fromkeys(self.probes, 'none')
        self.text = 'native-draft'; self.selection = '2:5'
        self.audit[0] += 1; self.audit[2] += 1
        self.inspect()

    def leave(self):
        self.d.tap_text('Back')
        self.audit[1] += 1; self.audit[3] += 1
        texts = self.d.wait_for(lambda t: 'Draft list' in t and self.audit_text() in t,
                               'one release/unregister and detached native callbacks suppressed')
        assert 'Root: ' + self.root in texts
        self.r.snapshot()

    def common(self):
        self.inspect()
        gc = int(self.get(self.r.snapshot(), 'GC: '))
        self.d.tap_text('GC: ')
        self.d.wait_for(lambda t: 'GC: ' + str(gc + 1) in t, 'GC request')
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        # Require positive evidence that the Activity has lost its resumed state.
        self.r.wait(lambda: 'mResumed=false' in self.d.adb('shell', 'dumpsys', 'activity',
                    'dev.compose.hotreload.framework/.FrameworkActivity'), 'parent background', timeout=30)
        self.d.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n',
                   'dev.compose.hotreload.framework/.FrameworkActivity')
        self.inspect()

    def click(self):
        self.tap_native('Native label ' + self.label)
        self.counts[0] += 1
        self.probes['Native probe: '] = f'click-{self.click_version}:native-business-{self.business}:{self.text}'
        self.settle()

    def tap_native(self, label):
        # The platform Button style may expose textAllCaps in accessibility.
        actual = next(t for t in self.r.snapshot() if t.casefold() == label.casefold())
        self.d.tap_text(actual)

    def write(self):
        self.d.tap_text('Write native')
        self.counts[1] += 1
        self.text = 'edited-draft'; self.selection = '1:4'
        self.probes['Watch probe: '] = f'watch-{self.watch_version}:{self.text}'
        self.inspect()

    def edit(self, name, old, new, action):
        self.r.edit('InteropLogic.kt', old, new)
        self.r.reload(name)
        self.inspect()  # No new registration or event is allowed just because code reloaded.
        action()
        self.common()
        self.r.passed(name, reload=True)

    def androidview(self):
        self.r.new_case('androidview-original-native-state')
        self.click(); self.common()
        self.r.passed('androidview-original-native-state')
        self.r.edit('InteropLogic.kt', 'update-v1', 'update-v2')
        self.r.reload('androidview-stable-update-not-replayed')
        self.common()
        self.r.passed('androidview-stable-update-not-replayed', reload=True,
                      boundary='A stable AndroidView update callback is not automatically replayed')
        self.label = 'v2'
        self.update_note = 'v2'
        self.edit('androidview-update-retains-editor', 'Native label v1', 'Native label v2', self.click)
        self.business = 'v2'
        self.edit('androidview-retained-listener-business', 'native-business-v1:', 'native-business-v2:', self.click)

    def native_listener(self):
        self.r.new_case('native-listener-original-watcher')
        self.write(); self.common()
        self.r.passed('native-listener-original-watcher')
        self.click_version = 'v2'
        self.edit('native-listener-original-click-lambda', 'click-v1:', 'click-v2:', self.click)
        self.watch_version = 'v2'
        self.edit('native-listener-original-textwatcher', 'watch-v1:', 'watch-v2:', self.write)

    def launch(self):
        self.d.tap_text('Launch result')
        texts = self.d.wait_for(lambda t: any(x.startswith('Result pending: ') for x in t), 'real child Activity')
        self.audit[4] += 1
        return self.get(texts, 'Result pending: ')

    def return_result(self, cancel=False):
        self.tap_native('Return cancel' if cancel else 'Return OK')
        self.counts[2] += 1; self.audit[5] += 1
        payload = 'cancel' if cancel else 'payload'
        self.probes['Result probe: '] = f'result-{self.result_version}:result-business-{self.result_business}:{payload}'
        self.settle()
        self.common()  # Resume twice/GC must not redeliver a completed result.

    def activity_result(self):
        self.r.new_case('activity-result-original-callback')
        self.launch(); self.return_result()
        self.r.passed('activity-result-original-callback')
        for name, old, new, cancel in [
            ('activity-result-pending-lambda', 'result-v1:', 'result-v2:', False),
            ('activity-result-pending-business-cancel', 'result-business-v1:', 'result-business-v2:', True),
        ]:
            pending = self.launch()
            self.r.edit('InteropLogic.kt', old, new)
            self.r.reload(name)
            assert self.get(self.r.snapshot(), 'Result pending: ') == pending, 'Child Activity replaced'
            if cancel: self.result_business = 'v2'
            else: self.result_version = 'v2'
            self.return_result(cancel)
            self.r.passed(name, reload=True)

    def fresh_and_cleanup(self, groups):
        self.r.new_case('interop-release-and-fresh-registration')
        old_tag = self.tag
        self.leave(); self.enter()
        assert self.tag != old_tag, 'Released native View reused by a fresh entry'
        self.click(); self.write()
        if 'activity-result' in groups:
            self.launch(); self.return_result()
        self.common()
        self.leave()
        self.r.passed('interop-release-and-fresh-registration')
