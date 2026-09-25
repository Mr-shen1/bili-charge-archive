import sys
import unittest


class RuntimeSmokeTest(unittest.TestCase):
    def test_supported_python_and_package_import(self):
        self.assertEqual(sys.version_info[:2], (3, 12))
        import bili_monitor  # noqa: F401


if __name__ == "__main__":
    unittest.main()
