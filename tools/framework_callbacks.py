"""Retained Kotlin callbacks and real runtime-permission launcher acceptance."""
import json
import re
import time
import xml.etree.ElementTree as ET

from framework_ui import UiFeatureCases, APP


class PermissionFixture:
    """Mutate permission setup only outside the PID/input-monitored session."""
    permission = 'android.permission.CAMERA'
    flag_names = {'USER_SET': 'user-set', 'USER_FIXED': 'user-fixed'}

    def __init__(self, runner):
        self.r = runner
        self.original = None

    @staticmethod
    def parse_state(dump, user):
        # Some Android builds omit the flags suffix when no flags are set.
        # Restrict the lookup to the active user, including on multi-user OEMs.
        section = re.search(r'^\s*User ' + re.escape(user) + r':.*?(?=^\s*User \d+:|\Z)',
                            dump, re.MULTILINE | re.DOTALL)
        assert section, 'Active user missing from installed fixture'
        match = re.search(r'^\s*android\.permission\.CAMERA: granted=(true|false)'
                          r'(?:, flags=\[([^]\n]*)\])?[ \t]*$', section[0], re.MULTILINE)
        assert match, 'Camera runtime permission missing from installed fixture'
        flags = set(re.findall(r'[A-Z_]+', match[2] or ''))
        return {'granted': match[1] == 'true', 'flags': sorted(flags)}

    def state(self):
        user = self.r.device.adb('shell', 'am', 'get-current-user').strip()
        assert user.isdecimal(), 'Cannot identify active permission user'
        dump = self.r.device.adb('shell', 'dumpsys', 'package', APP)
        return self.parse_state(dump, user)

    def command(self, command, *args):
        return self.r.device.adb('shell', 'pm', command, APP, self.permission, *args)

    def prepare(self):
        original = self.state()
        assert not set(original['flags']) & {'POLICY_FIXED', 'SYSTEM_FIXED', 'ONE_TIME'}, 'Permission cannot be restored with supported shell flags'
        self.original = original
        (self.r.evidence / 'permission-original.json').write_text(json.dumps(self.original))
        self.dismiss_pending()
        if self.original['granted']:
            self.command('revoke')
        self.command('clear-permission-flags', *self.flag_names.values())
        actual = self.state()
        assert not actual['granted'] and not set(actual['flags']) & self.flag_names.keys()
        self.r.metadata['permissionBaseline'] = actual

    def dismiss_pending(self):
        # A failed test can leave an OEM permission Activity after its parent dies.
        # Close only this fixture's identified prompt, outside acceptance monitoring.
        if self.r.ui.process is None or self.r.ui.process.poll() is not None:
            return
        def own_prompt(xml):
            return any(n.get('package') == 'com.android.permissioncontroller'
                       or n.get('package') == 'com.google.android.permissioncontroller'
                       for n in ET.fromstring(xml).iter('node')
                       if 'Framework edit verification' in n.get('text', ''))
        xml = self.r.ui.dump()
        if not own_prompt(xml):
            return
        (self.r.evidence / 'permission-pending-cleanup.xml').write_text(xml)
        self.r.device.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        deadline = time.monotonic() + 10
        while own_prompt(self.r.ui.dump()):
            assert time.monotonic() < deadline, 'Fixture permission dialog not dismissed'
            time.sleep(.1)

    def close(self):
        if self.original is None:
            return
        if self.state()['granted'] != self.original['granted']:
            self.command('grant' if self.original['granted'] else 'revoke')
        self.command('clear-permission-flags', *self.flag_names.values())
        restore = [value for flag, value in self.flag_names.items() if flag in self.original['flags']]
        if restore:
            self.command('set-permission-flags', *restore)
        actual = self.state()
        assert actual['granted'] == self.original['granted'], 'Permission grant not restored'
        assert set(actual['flags']) & self.flag_names.keys() == set(self.original['flags']) & self.flag_names.keys(), 'Permission flags not restored'
        self.r.metadata['permissionRestored'] = actual


class CallbackCases(UiFeatureCases):
    def resume_original(self):
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        self.r.wait(lambda: 'mStopped=true' in self.d.adb('shell', 'dumpsys', 'activity', APP + '/.FrameworkActivity'), 'parent stopped after HOME', timeout=30)
        self.d.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n', APP + '/.FrameworkActivity')
        self.r.ui.wait_for_foreground(APP)

    def method_references(self):
        self.r.new_case('references-retain-registered-objects')
        self.enter('References'); self.d.tap_text('Register refs'); self.d.tap_text('Edit receiver')
        anchor = 'Reference anchor: ' + self.get(self.r.snapshot(), 'Reference anchor: ')
        self.d.tap_text('Invoke bound'); self.d.tap_text('Invoke anonymous')
        self.wait(anchor, 'Reference state: edited-receiver:1:1', 'Bound result: bound-v1:edited-receiver:1',
                  'Anonymous result: anonymous-v1:captured:receiver-seed:edited-receiver')
        self.r.passed('references-retain-registered-objects')
        self.r.edit('ReferenceReceiver.kt', 'calls += 1', 'calls += 3')
        self.edit('references-bound-receiver-new-method', 'ReferenceReceiver.kt', 'bound-v1:', 'bound-v2:')
        self.wait(anchor, 'Reference state: edited-receiver:1:1')
        self.d.tap_text('Invoke bound')
        self.wait(anchor, 'Reference state: edited-receiver:4:1', 'Bound result: bound-v2:edited-receiver:4')
        self.r.passed('references-bound-receiver-new-method', reload=True)
        self.edit('references-anonymous-old-captures-new-body', 'ReferenceFactory.kt', 'anonymous-v1:', 'anonymous-v2:')
        self.d.tap_text('Invoke anonymous')
        self.wait(anchor, 'Reference state: edited-receiver:4:2', 'Anonymous result: anonymous-v2:captured:receiver-seed:edited-receiver')
        self.r.passed('references-anonymous-old-captures-new-body', reload=True)
        self.r.edit('ReferenceFactory.kt', '    return object', '    val suffix: String? = "new-capture"\n    return object')
        self.edit('references-added-capture-old-default-new-constructor', 'ReferenceFactory.kt', '${receiver.draft}"\n    }', '${receiver.draft}:$suffix"\n    }')
        self.d.tap_text('Invoke anonymous'); self.d.tap_text('Fresh anonymous')
        self.wait(anchor, 'Reference state: edited-receiver:4:3',
                  'Anonymous result: anonymous-v2:captured:receiver-seed:edited-receiver:null',
                  'Fresh reference: anonymous-v2:captured:edited-receiver:edited-receiver:new-capture')
        self.r.passed('references-added-capture-old-default-new-constructor', reload=True)
        self.r.new_case('references-home-resume-retains-registrations')
        self.d.tap_text('GC: '); self.wait(anchor)
        self.resume_original(); self.d.tap_text('Invoke bound'); self.d.tap_text('Invoke anonymous')
        self.wait(anchor, 'Reference state: edited-receiver:7:4', 'Bound result: bound-v2:edited-receiver:7',
                  'Anonymous result: anonymous-v2:captured:receiver-seed:edited-receiver:null')
        self.leave(); self.wait('Reference lifetime: 1:1')
        self.r.passed('references-home-resume-retains-registrations')
        self.r.new_case('references-fresh-page-after-disposal')
        self.enter('References'); self.d.tap_text('Register refs')
        assert 'Reference anchor: ' + self.get(self.r.snapshot(), 'Reference anchor: ') != anchor
        self.d.tap_text('Invoke bound'); self.d.tap_text('Invoke anonymous')
        self.wait('Reference state: receiver-seed:3:1', 'Bound result: bound-v2:receiver-seed:3',
                  'Anonymous result: anonymous-v2:captured:receiver-seed:receiver-seed:new-capture')
        self.leave(); self.wait('Reference lifetime: 2:2')
        self.r.passed('references-fresh-page-after-disposal')

    def permission_nodes(self):
        self.r.session.check()
        assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid, 'Application process replaced'
        xml = self.r.ui.dump()
        nodes = list(ET.fromstring(xml).iter('node'))
        (self.r.case / 'system-permission.xml').write_text(xml)
        packages = {n.get('package') for n in nodes} - {None, '', APP, 'com.android.systemui'}
        assert packages and packages <= {'com.android.permissioncontroller', 'com.google.android.permissioncontroller', 'com.lbe.security.miui'}, 'Unexpected foreground during permission request'
        self.r.session.check()
        return nodes

    def request_permission(self):
        self.d.tap_text('Request camera permission')
        self.r.wait(lambda: 'GrantPermissionsActivity' in self.d.adb('shell', 'dumpsys', 'activity', 'activities'), 'actual permission dialog', timeout=30)
        self.permission_nodes()

    def permission_answer(self, button):
        nodes = self.permission_nodes()
        # The read-only UI helper exposes text/bounds/package, not resource IDs.
        # Match an exact observed system action; never use an app-window text fallback.
        labels = {
            'permission_deny_button': {'拒绝', 'Don’t allow', "Don't allow", 'Deny'},
            'permission_allow_foreground_only_button': {'仅在使用中允许', 'While using the app'},
        }
        matches = [n for n in nodes if n.get('package') in {
            'com.android.permissioncontroller', 'com.google.android.permissioncontroller', 'com.lbe.security.miui'
        } and (n.get('resource-id', '').endswith(':id/' + button) or n.get('text') in labels[button])]
        assert len(matches) == 1, 'Expected actual permission action: ' + button
        bounds = list(map(int, re.findall(r'\d+', matches[0].get('bounds'))))
        self.d.adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
        self.r.ui.wait_for_foreground(APP)

    def permission_launcher(self):
        self.r.new_case('permission-real-system-request')
        self.enter('Permissions'); self.d.tap_text('Edit permission draft')
        self.wait('Permission state: edited-permission-draft:0:0', 'Permission granted: false')
        anchor = 'Permission anchor: ' + self.get(self.r.snapshot(), 'Permission anchor: ')
        self.request_permission()
        self.r.passed('permission-real-system-request')
        self.edit('permission-denied-after-pending-business-edit', 'PermissionLogic.kt', 'permission-v1:', 'permission-v2:')
        self.permission_answer('permission_deny_button')
        self.wait(anchor, 'Permission state: edited-permission-draft:1:1',
                  'Permission result: permission-v2:false:edited-permission-draft', 'Permission granted: false')
        self.r.passed('permission-denied-after-pending-business-edit', reload=True)
        self.r.new_case('permission-repeat-system-request')
        self.request_permission()
        self.edit('permission-original-callback-after-pending-edit', 'PermissionScreen.kt', 'calls += 1', 'calls += 3')
        self.permission_answer('permission_allow_foreground_only_button')
        self.wait(anchor, 'Permission state: edited-permission-draft:4:2',
                  'Permission result: permission-v2:true:edited-permission-draft', 'Permission granted: true')
        self.r.passed('permission-original-callback-after-pending-edit', reload=True)
        self.r.new_case('permission-already-granted-delivers-once')
        self.d.tap_text('Request camera permission')
        self.wait(anchor, 'Permission state: edited-permission-draft:7:3', 'Permission granted: true')
        self.resume_original()
        self.wait(anchor, 'Permission state: edited-permission-draft:7:3')
        self.leave(); self.wait('Permission lifetime: 1:1:3')
        self.r.passed('permission-already-granted-delivers-once')
        self.r.new_case('permission-fresh-registration-after-disposal')
        self.enter('Permissions')
        self.wait('Permission state: permission-draft:0:0')
        assert 'Permission anchor: ' + self.get(self.r.snapshot(), 'Permission anchor: ') != anchor
        self.d.tap_text('Request camera permission')
        self.wait('Permission state: permission-draft:3:1', 'Permission result: permission-v2:true:permission-draft')
        self.leave(); self.wait('Permission lifetime: 2:2:4')
        self.r.passed('permission-fresh-registration-after-disposal')
