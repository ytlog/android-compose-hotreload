"""Targeted modal, Paging/Pager and in-flight motion edits on ordinary remember state."""
import json
import time
import re
import xml.etree.ElementTree as ET

UI_GROUPS = ['overlays', 'paging-pager', 'animation-gestures']
APP = 'dev.compose.hotreload.framework'


class UiFeatureCases:
    def __init__(self, runner):
        self.r, self.d = runner, runner.device
        self.root = None

    @staticmethod
    def get(texts, prefix):
        return next(t[len(prefix):] for t in texts if t.startswith(prefix))

    def wait(self, *expected):
        self.d.wait_for(lambda ts: all(x in ts for x in expected), 'exact feature state: ' + repr(expected))
        return self.check(*expected)

    def check(self, *expected):
        texts = self.r.snapshot()
        assert all(x in texts for x in expected), 'Missing expected feature state: ' + repr(expected)
        assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid, 'Application process replaced'
        (self.r.case / 'feature-observation.json').write_text(json.dumps({'expected': expected, 'texts': texts}, indent=2))
        return texts

    def scroll_home(self, direction):
        """Expose fixture controls/audits on phones without changing display settings."""
        previous = None
        for _ in range(4):
            xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
            nodes = list(ET.fromstring(xml).iter('node'))
            texts = tuple(n.get('text', '') for n in nodes)
            assert 'Route: list' in texts, 'Home scroll outside fixture list'
            if texts == previous:
                return
            previous = texts
            scrolls = [n for n in nodes if n.get('class') == 'android.widget.ScrollView'
                       and n.get('package') == APP]
            assert len(scrolls) == 1, 'Fixture home scroll container is ambiguous'
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', scrolls[0].get('bounds', '')))
            top, bottom = y1 + 40, y2 - 40
            assert bottom > top + 100, 'Fixture list has no usable scroll area'
            x = (x1 + x2) // 2
            start, end = (top, bottom) if direction == 'top' else (bottom, top)
            self.d.adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '350')
            time.sleep(.4)

    def enter(self, label):
        self.d.wait_for(lambda ts: 'Route: list' in ts, 'fixture list')
        self.scroll_home('top')
        self.root = self.get(self.r.snapshot(), 'Root: ')
        if label not in self.d.texts():
            self.scroll_home('bottom')
        matches = [bounds for text, bounds in self.d.nodes() if text == label]
        assert len(matches) == 1, 'Fixture entry not uniquely visible: ' + label
        x1, y1, x2, y2 = matches[0]
        self.d.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.5)

    def leave(self):
        self.d.tap_text('Back')
        self.wait('Route: list', 'Root: ' + self.root)
        self.scroll_home('bottom')

    def edit(self, name, filename, old, new):
        self.r.edit(filename, old, new)
        self.r.reload(name)

    def overlays(self):
        self.r.new_case('overlays-open-dialog-state')
        self.enter('Overlays')
        self.d.tap_text('Edit overlay draft')
        page = self.get(self.r.snapshot(), 'Overlay anchor: ').split('|')[0]
        anchor = 'Overlay anchor: ' + page + '|edited-draft:2:7|'
        self.d.tap_text('Show dialog'); self.d.tap_text('Dialog local')
        dialog = self.get(self.r.snapshot(), 'Dialog state: ')
        assert dialog.endswith(':1')
        self.check(anchor + '0:0', 'Dialog state: ' + dialog)
        self.r.passed('overlays-open-dialog-state')
        self.edit('overlays-live-dialog-business', 'FeatureLogic.kt', 'overlay-v1:', 'overlay-v2:')
        self.wait(anchor + '0:0', 'Dialog state: ' + dialog, 'overlay-v2:edited-draft')
        self.d.tap_text('Dialog apply')
        self.wait(anchor + '1:0', 'Overlay result: overlay-v2:edited-draft', 'Dialog state: ' + dialog)
        self.r.passed('overlays-live-dialog-business', reload=True)
        self.edit('overlays-retained-confirm-lambda', 'OverlayScreen.kt', 'calls += 1', 'calls += 3')
        self.wait(anchor + '1:0', 'Dialog state: ' + dialog)
        self.d.tap_text('Dialog apply')
        self.wait(anchor + '4:0', 'Dialog state: ' + dialog)
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        self.wait(anchor + '4:1', 'Overlay result: dismiss-v1:dialog', 'Show dialog')
        self.r.passed('overlays-retained-confirm-lambda', reload=True)
        self.r.new_case('overlays-open-sheet-state')
        self.d.tap_text('Show sheet'); self.d.tap_text('Sheet local')
        self.d.wait_for(lambda ts: any(t.startswith('Sheet state: ') and t.endswith(':Expanded') for t in ts), 'expanded bottom sheet')
        sheet = self.get(self.r.snapshot(), 'Sheet state: ')
        assert ':1:' in sheet
        self.check(anchor + '4:1', 'Sheet state: ' + sheet)
        self.r.passed('overlays-open-sheet-state')
        self.edit('overlays-live-sheet-business', 'FeatureLogic.kt', 'overlay-v2:', 'overlay-v3:')
        self.wait(anchor + '4:1', 'Sheet state: ' + sheet, 'overlay-v3:edited-draft')
        self.d.tap_text('Sheet apply')
        self.wait(anchor + '7:1', 'Overlay result: overlay-v3:edited-draft', 'Sheet state: ' + sheet)
        self.r.passed('overlays-live-sheet-business', reload=True)
        self.edit('overlays-retained-dismiss-callback', 'FeatureLogic.kt', 'dismiss-v1:', 'dismiss-v2:')
        self.wait(anchor + '7:1', 'Sheet state: ' + sheet)
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        self.wait(anchor + '7:2', 'Overlay result: dismiss-v2:sheet', 'Show sheet')
        # Reopening intentionally creates modal-local state, without replacing the parent page.
        self.d.tap_text('Show dialog')
        fresh = self.get(self.r.snapshot(), 'Dialog state: ')
        assert fresh.endswith(':0') and fresh != dialog
        self.d.tap_text('Dialog close'); self.wait(anchor + '7:3')
        self.leave(); self.r.passed('overlays-retained-dismiss-callback', reload=True)

    def paging_pager(self):
        self.r.new_case('paging-pending-append-and-pager-state')
        self.enter('Paging')
        self.wait('Paging audit: 1:1:-1:20', 'Pager position: 2:0.0')
        self.d.tap_text('Pager count 2'); self.d.tap_text('Load more')
        self.wait('Paging audit: 1:2:20:20')
        texts = self.r.snapshot()
        anchor = 'Paging anchor: ' + self.get(texts, 'Paging anchor: ')
        position = 'Paging position: ' + self.get(texts, 'Paging position: ')
        page = self.get(texts, 'Pager state: ')
        assert page.startswith('2:') and page.endswith(':1')
        self.r.passed('paging-pending-append-and-pager-state')
        self.edit('paging-edit-during-pending-load', 'FeatureLogic.kt', 'row-v1:', 'row-v2:')
        self.wait(anchor, position, 'Pager state: ' + page, 'Paging audit: 1:2:20:20')
        self.d.tap_text('Release page')
        self.wait(anchor, position, 'Pager state: ' + page, 'Paging audit: 1:2:-1:30')
        self.wait('Paging tail: row-v2:29')
        self.r.passed('paging-edit-during-pending-load', reload=True)
        self.edit('pager-current-page-render', 'FeatureLogic.kt', 'page-v1:', 'page-v2:')
        self.wait(anchor, position, 'Pager state: ' + page, 'page-v2:2')
        self.r.passed('pager-current-page-render', reload=True)
        self.edit('pager-retained-click-lambda', 'PagingScreen.kt', 'count += 1', 'count += 3')
        self.wait(anchor, position, 'Pager state: ' + page)
        self.d.tap_text('Pager count 2')
        page = page.rsplit(':', 1)[0] + ':4'
        self.wait(anchor, position, 'Pager state: ' + page)
        self.r.passed('pager-retained-click-lambda', reload=True)
        self.r.new_case('paging-append-error')
        self.d.tap_text('Fail append'); self.wait('Paging audit: 1:3:30:30')
        position = 'Paging position: ' + self.get(self.r.snapshot(), 'Paging position: ')
        self.d.tap_text('Release page')
        self.wait(anchor, position, 'Paging audit: 1:3:-1:30', 'Paging status: Error:NotLoading')
        self.r.passed('paging-append-error')
        self.edit('paging-edit-error-and-retry', 'FeatureLogic.kt', 'row-v2:', 'row-v3:')
        self.wait(anchor, position, 'Paging status: Error:NotLoading', 'Pager state: ' + page)
        self.d.tap_text('Retry page'); self.wait('Paging audit: 1:4:30:30')
        self.d.tap_text('Release page')
        self.wait(anchor, position, 'Paging audit: 1:4:-1:40', 'Pager state: ' + page)
        self.wait('Paging tail: row-v3:39')
        self.r.passed('paging-edit-error-and-retry', reload=True)
        self.r.new_case('paging-refresh-and-pager-navigation')
        visible = next(t.split(':')[2] for t in self.r.snapshot() if t.startswith('Item: '))
        self.d.tap_text('Refresh page')
        self.wait(anchor, 'Paging audit: 2:5:20:40', 'Pager state: ' + page)
        self.d.tap_text('Release page')
        self.wait(anchor, 'Paging audit: 2:5:-1:20', 'Pager state: ' + page)
        assert next(t.split(':')[2] for t in self.r.snapshot() if t.startswith('Item: ')) == visible, 'Refresh lost the keyed visible row'
        self.d.tap_text('Pager next'); self.wait('Pager position: 3:0.0', 'page-v2:3')
        self.d.tap_text('Pager count 3')
        fresh = self.get(self.r.snapshot(), 'Pager state: ')
        assert fresh.startswith('3:') and fresh.endswith(':3') and fresh.split(':')[1] != page.split(':')[1]
        self.leave(); self.r.passed('paging-refresh-and-pager-navigation')

    def animation_gestures(self):
        phase = getattr(self.r.args, 'motion_phase', 'all')
        if phase == 'gestures':
            return self.gestures()
        self.r.new_case('motion-running-animation-state')
        self.enter('Motion'); self.d.tap_text('Start animation')
        self.wait('Animation audit: 1:0:0:true')
        anchor = 'Motion anchor: ' + self.get(self.r.snapshot(), 'Motion anchor: ')
        previous = int(self.get(self.r.snapshot(), 'Animation value: '))
        self.r.passed('motion-running-animation-state')
        self.edit('motion-live-animation-business', 'FeatureLogic.kt', 'frame-v1:', 'frame-v2:')
        self.wait(anchor, 'Animation audit: 1:0:0:true')
        texts = self.r.snapshot()
        assert self.get(texts, 'Animation frame: ').startswith('frame-v2:')
        assert int(self.get(texts, 'Animation value: ')) > previous, 'Animation reset or stopped'
        self.r.passed('motion-live-animation-business', reload=True)
        previous = int(self.get(texts, 'Animation value: '))
        self.edit('motion-inflight-frame-lambda', 'MotionScreen.kt', 'animationFrame(value)', 'animationFrame(value + 100f)')
        self.wait(anchor, 'Animation audit: 1:0:0:true')
        texts = self.r.snapshot()
        value = int(self.get(texts, 'Animation value: '))
        frame = int(self.get(texts, 'Animation frame: ').split(':')[1])
        assert value > previous and 98 <= frame - value <= 102, 'Active frame callback stale or animation reset'
        self.r.passed('motion-inflight-frame-lambda', reload=True)
        self.r.new_case('motion-cancel-animation-once')
        self.d.tap_text('Stop animation'); self.wait(anchor, 'Animation audit: 1:0:1:false')
        self.r.passed('motion-cancel-animation-once')
        if phase != 'animation':
            self.gesture_updates(anchor, 'Animation audit: 1:0:1:false')
        self.leave()

    def gestures(self):
        """Independent entry for retrying only the pointer portion after animation passed."""
        self.r.new_case('gesture-page-startup')
        self.enter('Motion')
        anchor = 'Motion anchor: ' + self.get(self.r.snapshot(), 'Motion anchor: ')
        self.gesture_updates(anchor, 'Animation audit: 0:0:0:false')
        self.leave()

    def gesture_updates(self, anchor, animation_audit):
        self.r.new_case('gesture-held-pointer-baseline')
        # Inject inside the labelled target, clear of system edge-navigation regions.
        # Keep a real pointer pressed across both edits. No UI taps until UP.
        bounds = next(bounds for text, bounds in self.d.nodes() if text == 'Drag surface')
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        self.d.adb('shell', 'input', 'touchscreen', 'motionevent', 'DOWN', str(x), str(y))
        try:
            self.d.adb('shell', 'input', 'touchscreen', 'motionevent', 'MOVE', str(x + 40), str(y))
            self.wait('Gesture audit: 1:0:1:0:1', 'Gesture result: drag-v1:1')
            self.r.passed('gesture-held-pointer-baseline')
            self.edit('gesture-live-held-pointer-business', 'FeatureLogic.kt', 'drag-v1:', 'drag-v2:')
            self.wait(anchor, 'Gesture audit: 1:0:1:0:1')
            self.d.adb('shell', 'input', 'touchscreen', 'motionevent', 'MOVE', str(x + 80), str(y))
            self.wait('Gesture audit: 1:0:1:0:2', 'Gesture result: drag-v2:2')
            self.r.passed('gesture-live-held-pointer-business', reload=True)
            self.edit('gesture-retained-pointer-block', 'MotionScreen.kt', 'gestureLabel(state.moves)', 'gestureLabel(state.moves + 10)')
            self.wait(anchor, 'Gesture audit: 1:0:1:0:2')
            self.d.adb('shell', 'input', 'touchscreen', 'motionevent', 'MOVE', str(x + 120), str(y))
            self.wait('Gesture audit: 1:0:1:0:3', 'Gesture result: drag-v2:13')
        finally:
            self.d.adb('shell', 'input', 'touchscreen', 'motionevent', 'UP', str(x + 120), str(y))
        self.wait(anchor, 'Gesture audit: 1:0:1:1:3', animation_audit)
        self.r.passed('gesture-retained-pointer-block', reload=True)
