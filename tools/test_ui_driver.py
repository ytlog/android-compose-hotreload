"""Readiness waits must not replay UI actions or accept process replacement."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from ui_driver import UiDriver


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.device = Mock()
        self.device.adb.return_value = '123'
        self.driver = UiDriver(self.device, Path(self.temp.name))

    def test_transition_waits_for_actual_window_without_replaying_inputs(self):
        self.driver.dump = Mock(side_effect=['<node package="launcher"/>', '<node package="sample"/>'])
        with patch('ui_driver.time.sleep'):
            self.driver.wait_for_foreground('sample')
        self.assertEqual(self.driver.dump.call_count, 2)
        self.assertTrue(all(call.args == ('shell', 'pidof', 'sample') for call in self.device.adb.call_args_list))

    def test_process_replacement_is_rejected_before_reading_ui(self):
        self.device.adb.side_effect = ['123', '456']
        self.driver.dump = Mock()
        with self.assertRaisesRegex(AssertionError, 'Process changed'):
            self.driver.wait_for_foreground('sample')
        self.driver.dump.assert_not_called()

    def test_persistent_foreign_window_is_rejected(self):
        self.driver.dump = Mock(return_value='<node package="launcher"/>')
        with patch('ui_driver.time.monotonic', side_effect=[0, 11]):
            with self.assertRaisesRegex(AssertionError, 'did not become foreground'):
                self.driver.wait_for_foreground('sample')

    def test_external_session_interruption_propagates(self):
        self.device.adb.side_effect = AssertionError('external-touch')
        self.driver.dump = Mock()
        with self.assertRaisesRegex(AssertionError, 'external-touch'):
            self.driver.wait_for_foreground('sample')
        self.driver.dump.assert_not_called()


if __name__ == '__main__':
    unittest.main()
