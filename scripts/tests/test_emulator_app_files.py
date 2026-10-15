"""Regression coverage for fixture stdin loss and false-success adb transfers."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / "emulator-app-files.py"
FAKE_ADB = '''#!/usr/bin/env python3
import os,sys
from pathlib import Path
# Read-only adb commands also consume stdin unless the caller disconnects it.
if "getprop" in sys.argv:
    sys.stdin.buffer.read()
    print(os.environ.get("TEST_EMULATOR", "1"))
elif "packages" in sys.argv:
    sys.stdin.buffer.read()
    print("package:com.example.datasail uid:10101")
elif "restorecon" in " ".join(sys.argv):
    Path(os.environ["TEST_LABEL_CAPTURE"]).write_text("restored")
    sys.exit(int(os.environ.get("TEST_RELABEL_EXIT", "0")))
else:
    Path(os.environ["TEST_CAPTURE"]).write_bytes(sys.stdin.buffer.read())
    sys.exit(int(os.environ.get("TEST_REMOTE_EXIT", "0")))
'''

class EmulatorFixtureTransferTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        adb = root / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        self.capture = root / "captured.bin"
        self.env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                        DATASAIL_TEST_VARIANT="release", TEST_CAPTURE=str(self.capture), TEST_LABEL_CAPTURE=str(root / "labels.txt"))

    def transfer(self, payload=b"synthetic\x00\xff\r\n"):
        return subprocess.run([sys.executable, str(HELPER), "sh", "-c", "cat > files/fixture.properties"],
                              input=payload, env=self.env, capture_output=True)

    def test_metadata_queries_cannot_consume_fixture_input(self):
        payload = b"synthetic\x00\xff\r\n"
        result = self.transfer(payload)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(payload, self.capture.read_bytes())

    def test_remote_write_failure_is_not_reported_as_success(self):
        self.env["TEST_REMOTE_EXIT"] = "7"
        self.assertEqual(7, self.transfer().returncode)

    def test_relabel_failure_is_not_reported_as_success(self):
        self.env["TEST_RELABEL_EXIT"] = "9"
        self.assertEqual(9, self.transfer().returncode)

    def test_app_file_labels_are_restored_after_transfer(self):
        self.assertEqual(0, self.transfer().returncode)
        self.assertEqual("restored", Path(self.env["TEST_LABEL_CAPTURE"]).read_text())

    def test_physical_device_rejects_before_transfer(self):
        self.env["TEST_EMULATOR"] = "0"
        self.assertNotEqual(0, self.transfer().returncode)
        self.assertFalse(self.capture.exists())

if __name__ == "__main__":
    unittest.main()
