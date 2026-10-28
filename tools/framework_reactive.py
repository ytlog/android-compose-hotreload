"""Snapshot and lifecycle behavior on a retained ordinary remember page."""
import json
from framework_ui import UiFeatureCases
import re

REACTIVE_GROUPS = ['snapshot-flow', 'derived-state', 'lifecycle-flow']
APP = 'dev.compose.hotreload.framework'


class ReactiveCases:
    def __init__(self, runner):
        self.r, self.d = runner, runner.device
        self.root = self.identity = None
        self.snapshot_version = self.collector_version = self.derived_version = self.lifecycle_version = 'v1'
        self.disposed = 0
        self.snapshot_offset = self.derived_offset = self.lifecycle_offset = 0

    @staticmethod
    def get(texts, prefix):
        return next(t[len(prefix):] for t in texts if t.startswith(prefix))

    def projection(self):
        value = self.secondary if self.alternate else self.input
        return f'snapshot-{self.snapshot_version}:{(value + self.snapshot_offset) // 2}'

    def expected(self):
        return [f'Root: {self.root}', f'Reactive: {self.identity}',
                'Reactive draft: reactive-draft:2:5',
                f'Reactive input: {self.input}:{self.secondary}:{str(self.alternate).lower()}',
                f'Derived: derived-{self.derived_version}:{(self.input + self.derived_offset) // 2}',
                'Derived identity: ' + self.derived_identity,
                'Snapshots: ' + ';'.join(self.snapshots),
                'Lifecycle value: ' + self.deliveries[-1],
                'Deliveries: ' + ';'.join(self.deliveries),
                f'Reactive audit: 1:0:{self.starts}:{self.stops}:{len(self.snapshots)}:{len(self.deliveries)}']

    def check(self):
        expected = self.expected()
        self.d.wait_for(lambda ts: all(t in ts for t in expected), 'retained reactive page and exact deliveries')
        texts = self.r.snapshot()
        assert all(t in texts for t in expected), 'Reactive observation changed after settling'
        assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid, 'Process replaced'
        (self.r.case / 'reactive-observation.json').write_text(json.dumps({
            'expected': expected, 'derivedIdentity': self.get(texts, 'Derived identity: ')}, indent=2))

    def start(self):
        self.r.new_case('reactive-startup')
        UiFeatureCases(self.r).scroll_home('top')
        texts = self.d.wait_for(lambda ts: 'Draft list' in ts, 'fixture list')
        self.root = self.get(texts, 'Root: ')
        self.enter()
        self.r.passed('reactive-startup')

    def enter(self):
        UiFeatureCases(self.r).scroll_home('top')
        self.d.tap_text('Open reactive')
        texts = self.d.wait_for(lambda ts: any(t.startswith('Reactive: ') for t in ts), 'reactive page')
        identity = self.get(texts, 'Reactive: ')
        assert identity != self.identity, 'Disposed state reused'
        self.identity = identity
        self.derived_identity = self.get(texts, 'Derived identity: ')
        self.input = self.secondary = self.source = 0
        self.alternate = False
        self.starts, self.stops = 1, 0
        self.last_projection = self.projection()
        self.snapshots = [f'collector-{self.collector_version}:' + self.last_projection]
        self.deliveries = [f'lifecycle-{self.lifecycle_version}:{self.lifecycle_offset}']
        self.check()

    def mutate(self, label):
        if label == 'Input plus': self.input += 1
        elif label == 'Alt plus': self.secondary += 1
        elif label == 'Switch': self.alternate = not self.alternate
        else: raise AssertionError(label)
        new = self.projection()
        if new != self.last_projection:
            self.snapshots.append(f'collector-{self.collector_version}:' + new)
            self.last_projection = new
        self.d.tap_text(label)
        self.check()

    def gc(self):
        count = int(self.get(self.r.snapshot(), 'GC: '))
        self.d.tap_text('GC: ')
        self.d.wait_for(lambda ts: f'GC: {count + 1}' in ts, 'GC request')
        self.check()

    def edit(self, name, old, new, filename='ReactiveLogic.kt'):
        self.r.edit(filename, old, new)
        self.r.reload(name)

    def snapshot_flow(self):
        self.r.new_case('snapshot-distinct-and-dynamic-dependencies')
        self.mutate('Input plus')  # 0 -> 1 maps to the same bucket, no emission.
        self.mutate('Input plus')
        self.mutate('Switch')
        self.mutate('Input plus')  # Unobserved branch must not emit.
        self.mutate('Alt plus')
        self.mutate('Alt plus')
        self.mutate('Switch')
        self.gc(); self.r.passed('snapshot-distinct-and-dynamic-dependencies')
        self.edit('snapshot-retained-calculation', 'snapshot-v1:', 'snapshot-v2:')
        self.snapshot_version = 'v2'
        self.check()  # Code reload alone must not restart the active collection.
        self.mutate('Input plus'); self.gc()
        self.r.passed('snapshot-retained-calculation', reload=True)
        self.edit('snapshot-retained-collector', 'collector-v1:', 'collector-v2:')
        self.collector_version = 'v2'
        self.check()
        self.mutate('Input plus'); self.mutate('Input plus'); self.gc()
        self.r.passed('snapshot-retained-collector', reload=True)
        self.edit('snapshot-original-block-body',
                  'snapshotProjection(if (state.alternate) state.secondary else state.input)',
                  'snapshotProjection((if (state.alternate) state.secondary else state.input) + 2)',
                  'ReactiveScreen.kt')
        self.snapshot_offset = 2
        self.check(); self.mutate('Input plus'); self.gc()
        self.r.passed('snapshot-original-block-body', reload=True)


    def derived_state(self):
        self.r.new_case('derived-state-original-and-equal-input')
        self.mutate('Input plus'); self.mutate('Input plus'); self.gc()
        self.r.passed('derived-state-original-and-equal-input')
        self.edit('derived-state-calculation-body', 'derived-v1:', 'derived-v2:')
        self.derived_version = 'v2'
        # Cached derived values are read again after an actual dependency write.
        self.mutate('Input plus'); self.mutate('Input plus'); self.gc()
        self.r.passed('derived-state-calculation-body', reload=True)
        self.edit('derived-original-calculation-lambda', 'derivedProjection(state.input)',
                  'derivedProjection(state.input + 2)', 'ReactiveScreen.kt')
        self.derived_offset = 2
        self.mutate('Input plus'); self.gc()
        self.r.passed('derived-original-calculation-lambda', reload=True)


    def emit(self):
        self.source += 1
        self.deliveries.append(f'lifecycle-{self.lifecycle_version}:{self.source + self.lifecycle_offset}')
        self.d.tap_text('Emit'); self.check()

    def tap_native(self, text):
        actual = next(t for t in self.r.snapshot() if t.casefold() == text.casefold())
        self.d.tap_text(actual)

    def stopped(self):
        pattern = re.compile(r'^\S+\s+\S+\s+' + re.escape(self.r.pid) +
            r'\s+\d+\s+I\s+ReactiveLedger\s*:\s+' + re.escape(self.identity) +
            ':stop:' + str(self.stops) + r'\s*$', re.MULTILINE)
        self.r.wait(lambda: bool(pattern.search((self.r.evidence / 'system.logcat').read_text(errors='replace'))),
                    'real lifecycle collection cancellation', timeout=30)

    def pause(self):
        self.d.tap_text('Pause collector')
        self.d.wait_for(lambda ts: any(t.startswith('Paused: ') for t in ts), 'opaque child Activity')
        self.stops += 1
        self.stopped()
        self.tap_native('Inspect paused')
        self.child = self.get(self.r.snapshot(), 'Paused: ').split('|')[0]
        self.check_paused()

    def check_paused(self):
        self.tap_native('Inspect paused')
        expected = f'{self.child}|{self.identity}|{self.source}|1:0:{self.starts}:{self.stops}:{len(self.snapshots)}:{len(self.deliveries)}|' + ';'.join(self.deliveries)
        assert self.get(self.r.snapshot(), 'Paused: ') == expected, 'Stopped collector or retained parent changed'

    def resume(self):
        self.starts += 1
        self.deliveries.append(f'lifecycle-{self.lifecycle_version}:{self.source + self.lifecycle_offset}')
        self.tap_native('Resume collector'); self.check(); self.gc()

    def lifecycle_flow(self):
        self.r.new_case('lifecycle-original-collection')
        self.emit(); self.gc(); self.r.passed('lifecycle-original-collection')
        self.edit('lifecycle-active-transform', 'lifecycle-v1:', 'lifecycle-v2:')
        self.lifecycle_version = 'v2'
        self.check(); self.emit(); self.gc()
        self.r.passed('lifecycle-active-transform', reload=True)
        self.edit('lifecycle-original-map-block', 'lifecycleProjection(it)',
                  'lifecycleProjection(it + 10)', 'ReactiveScreen.kt')
        self.lifecycle_offset = 10
        self.check(); self.emit(); self.gc()
        self.r.passed('lifecycle-original-map-block', reload=True)

        self.r.new_case('lifecycle-stopped-latest-replay')
        self.pause()
        for _ in range(2):
            self.source += 1; self.tap_native('Emit paused'); self.check_paused()
        self.resume()
        self.r.passed('lifecycle-stopped-latest-replay')
        self.pause()
        self.edit('lifecycle-edit-while-stopped', 'lifecycle-v2:', 'lifecycle-v3:')
        self.lifecycle_version = 'v3'
        self.check_paused()
        self.source += 1; self.tap_native('Emit paused'); self.check_paused()
        self.resume(); self.emit()
        self.r.passed('lifecycle-edit-while-stopped', reload=True)

    def leave(self):
        self.d.tap_text('Back'); self.disposed += 1
        self.d.wait_for(lambda ts: 'Route: list' in ts, 'fixture list')
        UiFeatureCases(self.r).scroll_home('bottom')
        self.d.wait_for(lambda ts: f'Reactive disposed: {self.disposed}' in ts,
                        'reactive page disposal')
        self.stops += 1; self.stopped()
        self.r.wait(lambda: f'{self.identity}:snapshot-stop:1' in
                    (self.r.evidence / 'system.logcat').read_text(errors='replace'), 'snapshotFlow cancellation')
        assert 'Root: ' + self.root in self.r.snapshot()

    def fresh_and_cleanup(self):
        self.r.new_case('reactive-dispose-and-fresh-page')
        self.leave(); self.enter(); self.mutate('Input plus'); self.mutate('Input plus'); self.emit()
        self.leave(); self.r.passed('reactive-dispose-and-fresh-page')
