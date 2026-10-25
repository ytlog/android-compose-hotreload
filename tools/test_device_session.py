"""Host checks for evidence-backed device-session interruption handling."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from device_session import DeviceSession, DeviceSessionInterrupted

APP = 'test.sample'
UI = ('exec-out', 'uiautomator', 'dump', '/dev/tty')
TOUCH = '[ 123.1] /dev/input/event5: EV_KEY BTN_TOUCH DOWN\n'


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.evidence = Path(self.directory.name)
        self.device = Mock(serial='test-selector')
        self.device.adb.return_value = ''
        self.session = DeviceSession(self.device, self.evidence, APP)
        self.session.reader = io.StringIO('')
        self.session.events = Mock()
        self.session.events.poll.return_value = None

    def test_physical_contact_stops_before_next_injected_action_and_latches(self):
        self.session.reader = io.StringIO(TOUCH)
        for _ in range(2):
            with self.assertRaisesRegex(DeviceSessionInterrupted, 'external-touch'):
                self.session.adb('shell', 'input', 'tap', '418', '541')
        self.device.adb.assert_not_called()
        self.assertEqual(json.loads((self.evidence / 'session-interruption.json').read_text()),
                         {'reason': 'external-touch'})

    def test_tracking_contact_without_btn_touch_is_detected(self):
        self.session.reader = io.StringIO('[ 123.1] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID 00000001\n')
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'external-touch'):
            self.session.check()

    def test_release_and_device_headers_do_not_trigger_interruption(self):
        self.session.reader = io.StringIO('add device 1: /dev/input/event5\n'
                                         '[ 123.1] /dev/input/event5: EV_ABS ABS_MT_TRACKING_ID ffffffff\n')
        self.session.adb('shell', 'input', 'tap', '418', '541')
        self.device.adb.assert_called_once()

    def test_partial_event_line_is_retained_until_complete(self):
        self.session.reader = io.StringIO(TOUCH.rstrip('\n'))
        self.session.check()
        self.session.reader = io.StringIO('\n')
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'external-touch'):
            self.session.check()

    def test_touch_during_adb_action_stops_after_one_action_without_retry(self):
        def inject(*args, **kwargs):
            self.session.reader = io.StringIO(TOUCH)
            return ''
        self.device.adb.side_effect = inject
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'external-touch'):
            self.session.adb('shell', 'input', 'tap', '418', '541')
        self.device.adb.assert_called_once()
        self.assertTrue((self.evidence / 'adb-trace.jsonl').exists())

    def test_process_replacement_before_ui_read_stops_without_touch(self):
        self.session.bind_process('123')
        self.device.adb.return_value = '456\n'
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'application-process-changed'):
            self.session.adb(*UI)
        self.device.adb.assert_called_once_with('shell', 'pidof', APP, check=False)

    def test_process_replacement_during_ui_read_is_detected(self):
        self.session.bind_process('123')
        self.device.adb.side_effect = ['123', '<node package="test.sample"/>', '456']
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'application-process-changed'):
            self.session.adb(*UI)

    def test_foreign_window_stops_even_if_application_process_survives(self):
        self.session.bind_process('123')
        self.device.adb.side_effect = ['123', '<node package="test.overlay"/>', '123']
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'application-window-covered'):
            self.session.adb(*UI)

    def test_ime_alongside_application_is_allowed(self):
        self.session.bind_process('123')
        xml = '<node package="test.sample"/><node package="test.ime"/>'
        self.device.adb.side_effect = ['123', xml, '123']
        self.assertEqual(self.session.adb(*UI), xml)

    def test_dead_input_monitor_cannot_produce_a_pass(self):
        self.session.events.poll.return_value = 1
        with self.assertRaisesRegex(DeviceSessionInterrupted, 'input-monitor-stopped'):
            self.session.check()

    def test_cleanup_collects_original_pid_and_restores_adb(self):
        self.session.bind_process('123')
        self.device.adb = self.session.adb
        self.session.close()
        self.device.adb.assert_called_once_with('logcat', '-d', '--pid=123', check=False)
        self.assertTrue((self.evidence / 'original-process.logcat').exists())


if __name__ == '__main__':
    unittest.main()
