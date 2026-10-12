#!/usr/bin/env python3
"""Immutable local delivery from a clean commit. Never signs, pushes, or publishes."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
build_config = (ROOT / "app/build.gradle.kts").read_text()
VERSION = "V" + re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', build_config).group(1)
VERSION_CODE = int(re.search(r'versionCode = ([0-9]+)', build_config).group(1))
os.chdir(ROOT)

def command(*args):
    return subprocess.check_output(args, text=True).strip()

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

if command("git", "status", "--porcelain", "--untracked-files=all"):
    raise SystemExit("Refusing archive: commit all intended source/docs first; preserve other work.")
commit = command("git", "rev-parse", "HEAD")
base = ROOT / "versions"
target = base / VERSION
bundle = base / (VERSION + ".zip")
if target.exists() or bundle.exists():
    raise SystemExit("Existing version archive is immutable; choose a new version instead.")
apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if not apk.is_file():
    raise SystemExit("No real Debug APK: build before archiving.")
sdk = Path(os.environ["ANDROID_HOME"])
badging = command(str(sdk / "build-tools/36.0.0/aapt"), "dump", "badging", str(apk))
if f"versionCode='{VERSION_CODE}'" not in badging or f"versionName='{VERSION[1:]}'" not in badging:
    raise SystemExit("APK version differs from committed source.")
cert = command(str(sdk / "build-tools/36.0.0/apksigner"), "verify", "--print-certs", str(apk))
fingerprint = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", cert)
if not fingerprint:
    raise SystemExit("No verified signing fingerprint.")
base.mkdir(exist_ok=True)
staging = base / (VERSION + ".partial")
if staging.exists():
    raise SystemExit("Inspect previous partial archive before retrying.")
source = staging / "source"
source.mkdir(parents=True)
payload = subprocess.check_output(["git", "archive", "--format=tar", commit])
with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
    for item in archive.getmembers():
        path = Path(item.name)
        if any(p in {".git", "versions", "build", ".gradle", ".toolchains", ".local"} for p in path.parts):
            raise SystemExit("Forbidden archive member: " + item.name)
        if item.issym() or item.islnk() or path.is_absolute() or ".." in path.parts:
            raise SystemExit("Unsafe archive member: " + item.name)
        if path.suffix.lower() in {".keystore", ".jks", ".p12", ".pem", ".key"} or path.name.startswith(".env"):
            raise SystemExit("Possible secret material in source: " + item.name)
    archive.extractall(source, filter="data")
shutil.copytree(source / "docs", staging / "docs")
(staging / "apk").mkdir()
shutil.copy2(apk, staging / f"apk/DataSail-{VERSION}-debug.apk")
if test_apk.is_file():
    shutil.copy2(test_apk, staging / f"apk/DataSail-{VERSION}-androidTest.apk")
for src, dst in [("CHANGELOG.md", "更新说明.md"), ("docs/TEST_REPORT.md", "测试报告.md"), ("docs/KNOWN_ISSUES.md", "已知问题.md")]:
    shutil.copy2(source / src, staging / dst)
reports = staging / "docs/verification"
reports.mkdir()
suites = [(name, ROOT / "core/build/test-results" / name) for name in ["test", "integrationTest", "untrustedTlsTest", "sqlServerTest"]]
suites.append(("app-unit", ROOT / "app/build/test-results/testDebugUnitTest"))
for name, folder in suites:
    if not list(folder.glob("TEST-*.xml")):
        raise SystemExit("Missing actual test results: " + name)
    destination = reports / name
    destination.mkdir()
    for report in folder.glob("TEST-*.xml"):
        result = ET.parse(report).getroot()
        if any(int(result.get(key, "0")) for key in ("failures", "errors", "skipped")):
            raise SystemExit("Refusing failed/skipped test report: " + str(report))
        shutil.copy2(report, destination / report.name)
shutil.copy2(ROOT / "app/build/reports/lint-results-debug.xml", reports / "lint-results-debug.xml")
manifest = {
    "version": VERSION, "versionCode": VERSION_CODE, "applicationId": "com.example.datasail",
    "gitSha": commit, "sourceStatus": "clean committed snapshot", "published": False,
    "signing": {"type": "local debug only", "certificateSha256": fingerprint.group(1)},
    "toolchain": {"jdk": "Temurin 21.0.8+9", "agp": "8.13.0", "gradle": "8.13", "kotlin": "2.2.10", "composeBom": "2025.08.01", "compileSdk": 36, "minSdk": 26, "buildTools": "36.0.0"},
    "androidTlsProvider": "Conscrypt 2.7.0, engine sockets, platform trust manager",
    "drivers": {"mssql-jdbc": "13.6.0.jre8", "mariadb-java-client": "3.5.10"},
    "limitations": ["Physical devices not tested; consult TEST_REPORT for exact emulator/API evidence", "MySQL Android database runtime remains unverified", "Release/R8 fails on missing APIs; no release APK"],
    "artifacts": {p.name: {"bytes": p.stat().st_size, "sha256": sha(p)} for p in sorted((staging / "apk").glob("*.apk"))},
}
(staging / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
files = sorted(p for p in staging.rglob("*") if p.is_file())
(staging / "SHA256SUMS").write_text("".join(f"{sha(p)}  {p.relative_to(staging).as_posix()}\n" for p in files))
staging.rename(target)
with zipfile.ZipFile(bundle, "x", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(target.rglob("*")):
        if path.is_file():
            archive.write(path, f"{VERSION}/{path.relative_to(target).as_posix()}")
(base / (VERSION + ".zip.sha256")).write_text(f"{sha(bundle)}  {bundle.name}\n")
print(target)
print(bundle)
