#!/usr/bin/env python3
"""Record the actual installed application bytes; does not install or run tests."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit('Usage: record-device-evidence.py OUTPUT_JSON')
variant = os.environ.get('DATASAIL_TEST_VARIANT', 'debug')
if variant not in ('debug', 'release'):
    raise SystemExit('Invalid test variant')
root = Path(__file__).resolve().parents[1]
expected = root / f'app/build/outputs/apk/{variant}/app-{variant}.apk'

def adb(*args):
    return subprocess.check_output(['adb', *args], stdin=subprocess.DEVNULL)

def installed(package):
    paths = adb('shell', 'pm', 'path', package).decode().strip().splitlines()
    if len(paths) != 1 or not paths[0].startswith('package:/data/app/'):
        raise RuntimeError('Expected one installed base APK: ' + package)
    return hashlib.sha256(adb('exec-out', 'cat', paths[0].removeprefix('package:'))).hexdigest()

actual = installed('com.example.datasail')
if actual != hashlib.sha256(expected.read_bytes()).hexdigest():
    raise SystemExit('Installed APK differs from the current build')
test_hash = installed('com.example.datasail.test')
expected_test = root / f'app/build/outputs/apk/androidTest/{variant}/app-{variant}-androidTest.apk'
if test_hash != hashlib.sha256(expected_test.read_bytes()).hexdigest():
    raise SystemExit('Installed test APK differs from the current build')
record = {'serial': adb('get-serialno').decode().strip(), 'variant': variant,
          'installedApkSha256': actual, 'installedTestApkSha256': test_hash}
for key, prop in [('api', 'ro.build.version.sdk'), ('androidVersion', 'ro.build.version.release'),
                  ('abi', 'ro.product.cpu.abi'), ('buildFingerprint', 'ro.build.fingerprint'),
                  ('emulator', 'ro.kernel.qemu')]:
    record[key] = adb('shell', 'getprop', prop).decode().strip()
Path(sys.argv[1]).write_text(json.dumps(record, indent=2) + '\n')
print('Recorded installed APK identity for', record['serial'])
