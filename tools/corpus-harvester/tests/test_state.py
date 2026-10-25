import tempfile
import unittest
from pathlib import Path

from harvester.state import State


class StateTest(unittest.TestCase):
    def test_damaged_checkpoint_falls_back_to_backup(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / ".harvest-state.json"
            s = State(path)
            s.add_repo("github:a/b", {"host": "github"}, "q1")
            s.save()
            s.add_repo("github:c/d", {"host": "github"}, "q2")
            s.save()  # previous save is now the .bak
            path.write_bytes(b"\0" * 64)  # what a power loss can leave on NTFS
            with self.assertLogs("harvester.state", "WARNING"):
                self.assertEqual(list(State(path).repos), ["github:a/b"])

    def test_both_damaged_refuses_to_start_over_silently(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / ".harvest-state.json"
            path.write_bytes(b"\0" * 64)
            path.with_suffix(".bak").write_bytes(b"\0" * 64)
            with self.assertLogs("harvester.state", "WARNING"), self.assertRaises(RuntimeError):
                State(path)


if __name__ == "__main__":
    unittest.main()
