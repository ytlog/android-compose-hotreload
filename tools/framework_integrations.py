"""Public-artifact additions: saved navigation stacks, Coil HTTP and Compose launchers."""
import json
import re
import time
import xml.etree.ElementTree as ET

from framework_ui import UiFeatureCases, APP

INTEGRATION_GROUPS = ['navigation-stacks', 'async-images', 'compose-launcher']


class IntegrationCases(UiFeatureCases):
    def navigation_stacks(self):
        self.r.new_case('stacks-two-nested-destinations')
        self.enter('Stacks')
        self.wait('Stack route: feed/home')
        owner = 'Stack owner: ' + self.get(self.r.snapshot(), 'Stack owner: ')
        pages = {}

        def seed(route):
            self.wait('Stack route: ' + route)
            self.d.tap_text('Seed stack'); self.d.tap_text('Stack count')
            self.wait(f'Stack state: {route}-draft:1:12:7')
            pages[route] = self.get(self.r.snapshot(), 'Stack page: ')

        def retained(route, count=1):
            self.wait(owner, 'Stack route: ' + route, 'Stack page: ' + pages[route],
                      f'Stack state: {route}-draft:{count}:12:7')

        seed('feed/home'); self.d.tap_text('Stack next'); seed('feed/detail')
        self.d.tap_text('Tab saved'); seed('saved/home')
        self.d.tap_text('Stack next'); seed('saved/detail')
        self.d.tap_text('Tab feed'); retained('feed/detail')
        self.d.tap_text('Tab saved'); retained('saved/detail')
        live = 'Stack live: ' + self.get(self.r.snapshot(), 'Stack live: ')
        self.r.passed('stacks-two-nested-destinations')
        self.edit('stacks-active-business-preserves-state', 'IntegrationLogic.kt', 'stack-v1:', 'stack-v2:')
        retained('saved/detail'); self.check(live, 'stack-v2:saved/detail')
        self.r.passed('stacks-active-business-preserves-state', reload=True)
        self.edit('stacks-active-original-lambda', 'StackScreen.kt', 'count += 1', 'count += 3')
        retained('saved/detail'); self.check(live)
        self.d.tap_text('Stack count'); retained('saved/detail', 4); self.check(live)
        self.r.passed('stacks-active-original-lambda', reload=True)
        self.r.new_case('stacks-restore-inactive-state-and-new-code')
        self.d.tap_text('Tab feed'); retained('feed/detail'); self.check('stack-v2:feed/detail')
        self.d.tap_text('Stack count'); retained('feed/detail', 4)
        self.d.tap_text('Tab saved'); retained('saved/detail', 4)
        self.r.passed('stacks-restore-inactive-state-and-new-code')
        self.r.new_case('stacks-independent-pop-and-home-state')
        self.d.tap_text('Stack pop'); retained('saved/home')
        self.d.tap_text('Tab feed'); retained('feed/detail', 4)
        self.d.tap_text('Stack pop'); retained('feed/home')
        self.d.tap_text('Tab saved'); retained('saved/home')
        self.leave(); self.r.passed('stacks-independent-pop-and-home-state')

    def async_images(self):
        if getattr(self.r.args, 'image_phase', 'all') == 'baseline':
            return self.image_baseline()
        self.r.new_case('images-real-http-pending')
        self.enter('Images'); self.d.tap_text('Edit image draft')
        self.wait('Image counts: 0:0:1:0:true', 'Image draft: edited-image-draft')
        texts = self.r.snapshot()
        anchor = 'Image anchor: ' + self.get(texts, 'Image anchor: ')
        request = 'Image request: ' + self.get(texts, 'Image request: ')
        self.r.passed('images-real-http-pending')
        self.edit('images-edit-business-during-request', 'IntegrationLogic.kt', 'image-v1:', 'image-v2:')
        self.wait(anchor, request, 'Image draft: edited-image-draft', 'Image counts: 0:0:1:0:true')
        self.r.passed('images-edit-business-during-request', reload=True)
        self.edit('images-original-success-lambda', 'ImageScreen.kt', 'successes += 1', 'successes += 3')
        self.wait(anchor, request, 'Image counts: 0:0:1:0:true')
        self.d.tap_text('Release image')
        self.wait(anchor, request, 'Image counts: 3:0:1:1:false',
                  'Image result: image-v2:NETWORK:-16711936:edited-image-draft')
        self.r.passed('images-original-success-lambda', reload=True)
        self.r.new_case('images-memory-cache-without-network')
        self.d.tap_text('Hide image'); self.wait('Show image', 'Image counts: 3:0:1:1:false')
        self.d.tap_text('Show image')
        self.wait(anchor, request, 'Image counts: 6:0:1:1:false',
                  'Image result: image-v2:MEMORY_CACHE:-16711936:edited-image-draft')
        self.r.passed('images-memory-cache-without-network')
        self.r.new_case('images-real-http-error')
        self.d.tap_text('Fail image'); self.wait(anchor, 'Image counts: 6:0:2:1:true')
        self.d.tap_text('Release image')
        self.wait(anchor, 'Image counts: 6:1:2:2:false', 'Image result: image-v2:error:edited-image-draft')
        self.r.passed('images-real-http-error')
        self.edit('images-edit-error-and-retry', 'IntegrationLogic.kt', 'image-v2:', 'image-v3:')
        self.wait(anchor, 'Image counts: 6:1:2:2:false')
        self.d.tap_text('Retry image'); self.wait(anchor, 'Image counts: 6:1:3:2:true')
        self.d.tap_text('Release image')
        self.wait(anchor, 'Image counts: 9:1:3:3:false',
                  'Image result: image-v3:NETWORK:-16711936:edited-image-draft')
        self.r.passed('images-edit-error-and-retry', reload=True)
        self.r.new_case('images-dispose-loader-and-server-once')
        self.leave(); self.wait('Image lifetime: 1:1')
        self.r.passed('images-dispose-loader-and-server-once')

    def image_baseline(self):
        """Isolate real image I/O from the known public-version cache-invalidation failure."""
        self.r.new_case('images-baseline-http-and-memory-cache')
        self.enter('Images'); self.d.tap_text('Edit image draft')
        self.wait('Image counts: 0:0:1:0:true')
        anchor = 'Image anchor: ' + self.get(self.r.snapshot(), 'Image anchor: ')
        request = 'Image request: ' + self.get(self.r.snapshot(), 'Image request: ')
        self.d.tap_text('Release image')
        self.wait(anchor, request, 'Image counts: 1:0:1:1:false',
                  'Image result: image-v1:NETWORK:-16711936:edited-image-draft')
        self.d.tap_text('Hide image'); self.wait('Show image')
        self.d.tap_text('Show image')
        self.wait(anchor, request, 'Image counts: 2:0:1:1:false',
                  'Image result: image-v1:MEMORY_CACHE:-16711936:edited-image-draft')
        self.r.passed('images-baseline-http-and-memory-cache')
        self.r.new_case('images-baseline-503-and-retry')
        self.d.tap_text('Fail image'); self.wait(anchor, 'Image counts: 2:0:2:1:true')
        self.d.tap_text('Release image')
        self.wait(anchor, 'Image counts: 2:1:2:2:false', 'Image result: image-v1:error:edited-image-draft')
        self.d.tap_text('Retry image'); self.wait(anchor, 'Image counts: 2:1:3:2:true')
        self.d.tap_text('Release image')
        self.wait(anchor, 'Image counts: 3:1:3:3:false',
                  'Image result: image-v1:NETWORK:-16711936:edited-image-draft')
        self.r.passed('images-baseline-503-and-retry')
        self.r.new_case('images-baseline-disposal')
        self.leave(); self.wait('Image lifetime: 1:1')
        self.r.passed('images-baseline-disposal')

    def picker_nodes(self):
        # This explicit system transition is the only foreign window accepted here.
        # Keep the common app-window guard unchanged, and retain process/input checks.
        self.r.session.check()
        assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid
        xml = self.r.ui.dump()
        nodes = list(ET.fromstring(xml).iter('node'))
        packages = {n.get('package') for n in nodes} - {None, '', 'com.android.systemui'}
        assert packages and packages <= {'com.android.documentsui', 'com.google.android.documentsui'}, 'Unexpected foreground during document selection'
        (self.r.case / 'system-picker.xml').write_text(xml)
        self.r.session.check()
        return nodes

    def open_picker(self):
        self.d.tap_text('Pick document')
        # Observe the system Activity, not a simulated registry delivery.
        self.r.wait(lambda: bool(re.search(r'(?:mResumedActivity|topResumedActivity|ResumedActivity):.*(?:com.android.documentsui|com.google.android.documentsui)/',
                    self.d.adb('shell', 'dumpsys', 'activity', 'activities'))), 'system document picker foreground', timeout=30)
        self.picker_nodes()
        self.r.wait(lambda: 'mResumed=false' in self.d.adb('shell', 'dumpsys', 'activity', APP + '/.FrameworkActivity'),
                    'launcher parent stopped', timeout=30)

    def select_document(self):
        deadline = time.monotonic() + 30
        root_selected = False
        drawer_opened = False
        while True:
            nodes = self.picker_nodes()
            matches = [n for n in nodes if n.get('text') == 'hot-reload.png'
                       or re.match(r'^hot-reload\.png(?:,|$)', n.get('content-desc', ''))]
            if matches:
                target = matches[0]
                break
            # EXTRA_INITIAL_URI is advisory; some DocumentsUI versions still open Recents.
            roots = [n for n in nodes if n.get('text') == 'Hot reload fixtures']
            if roots and not root_selected:
                self.tap_picker_node(roots[0]); root_selected = True
            elif not roots and not drawer_opened and not root_selected:
                controls = [n for n in nodes if n.get('content-desc') in ('Show roots', '显示根目录')]
                if controls:
                    self.tap_picker_node(controls[0]); drawer_opened = True
            assert time.monotonic() < deadline, 'Fixture document absent from actual picker'
            time.sleep(.2)
        self.tap_picker_node(target)
        self.r.ui.wait_for_foreground(APP)

    def tap_picker_node(self, node):
        bounds = list(map(int, re.findall(r'\d+', node.get('bounds'))))
        self.d.adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))

    def compose_launcher(self):
        self.r.new_case('launcher-system-document-pending')
        self.enter('Launcher'); self.d.tap_text('Edit launcher draft')
        self.wait('Launcher state: edited-launcher-draft:0:0')
        anchor = 'Launcher anchor: ' + self.get(self.r.snapshot(), 'Launcher anchor: ')
        self.open_picker()
        self.r.passed('launcher-system-document-pending')
        self.edit('launcher-business-while-parent-stopped', 'IntegrationLogic.kt', 'launcher-v1:', 'launcher-v2:')
        self.picker_nodes()
        self.r.passed('launcher-business-while-parent-stopped', reload=True)
        self.edit('launcher-original-result-callback', 'LauncherScreen.kt', 'calls += 1', 'calls += 3')
        self.picker_nodes(); self.select_document()
        self.wait(anchor, 'Launcher state: edited-launcher-draft:3:1',
                  'Launcher result: launcher-v2:16x16:-16711936:edited-launcher-draft')
        self.r.passed('launcher-original-result-callback', reload=True)
        self.r.new_case('launcher-system-cancel-delivers-once')
        self.open_picker(); self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        self.r.ui.wait_for_foreground(APP)
        self.wait(anchor, 'Launcher state: edited-launcher-draft:6:2', 'Launcher result: launcher-v2:cancel:edited-launcher-draft')
        self.r.passed('launcher-system-cancel-delivers-once')
        self.r.new_case('launcher-resume-without-redelivery')
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        # Wait for onStop, not merely onPause: resuming during the HOME animation
        # can leave an OEM launcher transition covering the app window.
        self.r.wait(lambda: 'mStopped=true' in self.d.adb('shell', 'dumpsys', 'activity', APP + '/.FrameworkActivity'), 'parent stopped after HOME', timeout=30)
        self.d.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n', APP + '/.FrameworkActivity')
        self.wait(anchor, 'Launcher state: edited-launcher-draft:6:2')
        self.leave(); self.wait('Launcher lifetime: 1:1:2')
        self.r.passed('launcher-resume-without-redelivery')
        self.r.new_case('launcher-fresh-composition-after-disposal')
        self.enter('Launcher')
        self.wait('Launcher state: launcher-draft:0:0')
        assert 'Launcher anchor: ' + self.get(self.r.snapshot(), 'Launcher anchor: ') != anchor
        self.open_picker(); self.select_document()
        self.wait('Launcher state: launcher-draft:3:1', 'Launcher result: launcher-v2:16x16:-16711936:launcher-draft')
        self.leave(); self.wait('Launcher lifetime: 2:2:3')
        self.r.passed('launcher-fresh-composition-after-disposal')
