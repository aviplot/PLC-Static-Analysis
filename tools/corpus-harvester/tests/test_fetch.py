import unittest
from pathlib import PurePosixPath

from harvester import fetch


class WindowsPathTest(unittest.TestCase):
    def test_ordinary_paths_ok(self):
        for p in ["src/FB_Motor.TcPOU", "a b/c.st", "lib/console.st", "x/COM10.scl"]:
            self.assertTrue(fetch._windows_safe(PurePosixPath(p)), p)

    def test_ntfs_unsafe_paths_rejected(self):
        for p in ["a/CON.st", "a/nul", "a/Lpt1.txt", "a/x:y.st", "a/q?.st", "a/b\\c.st",
                  "dir./f.st", "dir /f.st", "a/t\tb.st"]:
            self.assertFalse(fetch._windows_safe(PurePosixPath(p)), p)

    def test_git_never_converts_line_endings(self):
        self.assertIn("core.autocrlf=false", fetch.GIT_CFG)


if __name__ == "__main__":
    unittest.main()
