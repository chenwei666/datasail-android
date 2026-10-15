#!/usr/bin/env python3
"""Transfer synthetic fixture files to a nondebuggable APK on a disposable emulator.

This never changes device root configuration. Release runs require an existing SDK
emulator with su; physical devices are rejected. Secrets travel only through stdin.
"""
import os
import re
import shlex
import subprocess
import sys

args = sys.argv[1:]
if not args:
    raise SystemExit("Missing app-private file command")
if len(args) == 3 and args[:2] == ["sh", "-c"]:
    args[2] = args[2].removeprefix("'").removesuffix("'")
if os.environ.get("DATASAIL_TEST_VARIANT", "debug") == "debug":
    raise SystemExit(subprocess.call(["adb", "shell", "-T", shlex.join(["run-as", "com.example.datasail", *args])]))
if subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True, stdin=subprocess.DEVNULL).strip() != "1":
    raise SystemExit("Release fixture transfer is restricted to disposable SDK emulators")
package = subprocess.check_output(["adb", "shell", "cmd", "package", "list", "packages", "-U", "com.example.datasail"], text=True, stdin=subprocess.DEVNULL)
match = re.search(r"^package:com\.example\.datasail uid:(\d+)$", package, re.M)
if not match:
    raise SystemExit("Target app UID unavailable")
command = "umask 077; cd /data/user/0/com.example.datasail && " + shlex.join(["su", match[1], *args])
# Shell v2 without a PTY preserves binary bytes and propagates remote exit status;
# exec-out does not reliably propagate failed fixture transfers.
result = subprocess.call(["adb", "shell", "-T", shlex.join(["su", "0", "sh", "-c", command])])
if result:
    raise SystemExit(result)
# su changes the UID but API26 does not inherit the app's SELinux categories for
# newly created files. Restore package-derived labels, never disable enforcement.
# Keep relabel diagnostics separate from binary fixture reads and disconnect stdin.
relabel = "if [ -d /data/user/0/com.example.datasail/files ]; then restorecon -RF /data/user/0/com.example.datasail/files; fi"
raise SystemExit(subprocess.call(["adb", "shell", "-T", shlex.join(["su", "0", "sh", "-c", relabel])],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL))
