"""Permission dumps from Android variants must preserve exact restore state."""
import unittest

from framework_callbacks import PermissionFixture


class PermissionDumpTests(unittest.TestCase):
    def test_absent_and_empty_flags(self):
        for suffix in ('', ', flags=[]'):
            with self.subTest(suffix=suffix):
                self.assertEqual(PermissionFixture.parse_state(
                    '  User 0: installed=true\n    runtime permissions:\n'
                    '      android.permission.CAMERA: granted=false' + suffix + '\n', '0'),
                    {'granted': False, 'flags': []})

    def test_active_user_and_protected_flags(self):
        dump = ('  User 0: installed=true\n'
                '    android.permission.CAMERA: granted=false\n'
                '  User 10: installed=true\n'
                '    android.permission.CAMERA: granted=true, flags=[ USER_SET|POLICY_FIXED ]\n')
        self.assertEqual(PermissionFixture.parse_state(dump, '10'),
                         {'granted': True, 'flags': ['POLICY_FIXED', 'USER_SET']})

    def test_missing_user_permission_and_unrecognized_format_fail_closed(self):
        for dump in ('  User 10: installed=true\n    android.permission.CAMERA: granted=true\n',
                     '  User 0: installed=true\n  User 10: installed=true\n'
                     '    android.permission.CAMERA: granted=true\n',
                     '  User 0: installed=true\n'
                     '    android.permission.CAMERA: granted=true, unexpected=value\n'):
            with self.subTest(dump=dump), self.assertRaises(AssertionError):
                PermissionFixture.parse_state(dump, '0')


if __name__ == '__main__':
    unittest.main()
