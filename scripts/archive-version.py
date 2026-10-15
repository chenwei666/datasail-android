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
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--variant", choices=("debug", "release"), default="debug")
variant = parser.parse_args().variant

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
apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
test_apk = ROOT / f"app/build/outputs/apk/androidTest/{variant}/app-{variant}-androidTest.apk"
if not apk.is_file():
    raise SystemExit(f"No real {variant} APK: build before archiving.")
sdk = Path(os.environ["ANDROID_HOME"])
badging = command(str(sdk / "build-tools/36.0.0/aapt"), "dump", "badging", str(apk))
if f"versionCode='{VERSION_CODE}'" not in badging or f"versionName='{VERSION[1:]}'" not in badging:
    raise SystemExit("APK version differs from committed source.")
cert = command(str(sdk / "build-tools/36.0.0/apksigner"), "verify", "--print-certs", str(apk))
fingerprint = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", cert)
if not fingerprint:
    raise SystemExit("No verified signing fingerprint.")
if variant == "release":
    identity = json.loads((ROOT / "docs/releases/signing-certificate.json").read_text())
    if fingerprint.group(1) != identity["certificateSha256"] or "application-debuggable" in badging:
        raise SystemExit("Release identity or debuggability does not match the production configuration.")
    dex_result = json.loads(command("python3", "scripts/verify-release-dex.py", str(apk)))
    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    if not mapping.is_file() or not mapping.stat().st_size:
        raise SystemExit("Missing actual R8 mapping")
# Bind the delivery to the APK that actually completed device verification.
evidence = ROOT / "docs/evidence" / ("v" + VERSION[1:].replace(".", ""))
verification = json.loads((evidence / "summary.json").read_text())
devices = json.loads((evidence / "devices.json").read_text())
if verification.get("version") != VERSION[1:] or verification.get("versionCode") != VERSION_CODE:
    raise SystemExit("Evidence version differs from the APK/source.")
if variant == "release" and verification.get("variant") != "release":
    raise SystemExit("Release requires Release device evidence; Debug results cannot substitute.")
if verification.get("finalApkSha256") != sha(apk) or not devices:
    raise SystemExit("Final APK does not match completed verification evidence.")
if any(device.get("installedApkSha256") != sha(apk) for device in devices.values()):
    raise SystemExit("Device installation evidence does not match the final APK.")
if variant == "release":
    android_reports = verification.get("androidReports", [])
    if not android_reports:
        raise SystemExit("Release requires individually bound Android test reports")
    for item in android_reports:
        report_path = (evidence / item["report"]).resolve()
        device_path = (evidence / item["device"]).resolve()
        if evidence.resolve() not in report_path.parents or evidence.resolve() not in device_path.parents:
            raise SystemExit("Test evidence paths must remain in the current version")
        report = report_path.read_text()
        receipt = json.loads(device_path.read_text())
        if (receipt.get("variant") != "release" or receipt.get("installedApkSha256") != sha(apk) or
                receipt.get("installedTestApkSha256") != item.get("testApkSha256") or
                item.get("testApkSha256") not in {sha(test_apk), *[runner["sha256"] for runner in verification["testRunnerHistory"]["priorRunners"]]} or
                not re.search(r"OK \(" + str(int(item["tests"])) + r" tests?\)", report) or
                "FAILURES!!!" in report or "INSTRUMENTATION_FAILED" in report):
            raise SystemExit("Failed or mismatched Release Android report: " + item["report"])
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
shutil.copy2(apk, staging / f"apk/DataSail-{VERSION}-{variant}.apk")
if test_apk.is_file():
    shutil.copy2(test_apk, staging / f"apk/DataSail-{VERSION}-androidTest.apk")
if variant == "release":
    for index, runner in enumerate(verification["testRunnerHistory"]["priorRunners"], 1):
        previous_runner = (ROOT / runner["localPath"]).resolve()
        if (ROOT / ".local/release-test-runners").resolve() not in previous_runner.parents or sha(previous_runner) != runner["sha256"]:
            raise SystemExit("Previous test runner differs from the recorded test-only change")
        shutil.copy2(previous_runner, staging / f"apk/DataSail-{VERSION}-androidTest-prior-{index}.apk")
for src, dst in [("CHANGELOG.md", "更新说明.md"), ("docs/TEST_REPORT.md", "测试报告.md"), ("docs/KNOWN_ISSUES.md", "已知问题.md")]:
    shutil.copy2(source / src, staging / dst)
reports = staging / "docs/verification"
reports.mkdir()
suites = [(name, ROOT / "core/build/test-results" / name) for name in verification["requiredJvmReports"]]
suites.append(("app-unit", ROOT / "app/build/test-results/testDebugUnitTest"))
suites.append(("driver-profile", ROOT / "sqlserver-android/build/test-results/test"))
for module in ("postgresql-android", "mariadb-android", "mongodb-core-android"):
    suites.append((module, ROOT / module / "build/test-results/test"))
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
profile_lock = json.loads((ROOT / "sqlserver-android/upstream.lock.json").read_text())
profile_jar = ROOT / f"sqlserver-android/build/libs/mssql-jdbc-android-{profile_lock['profile']}.jar"
(staging / "drivers").mkdir()
shutil.copy2(profile_jar, staging / "drivers" / profile_jar.name)
profiles = {}
for module in ("mariadb-android", "postgresql-android", "mongodb-core-android", "mongodb-sync-android"):
    lock = json.loads((ROOT / module / "upstream.lock.json").read_text())
    jars = list((ROOT / module / "build/libs").glob("*-" + lock["profile"] + ".jar"))
    if len(jars) != 1:
        raise SystemExit("Missing exact derived driver artifact: " + module)
    shutil.copy2(jars[0], staging / "drivers" / jars[0].name)
    profiles[module] = {"version": lock["profile"], "jarSha256": sha(jars[0]),
                        "upstreamSourceSha256": lock["sourceSha256"],
                        "patchSha256": sha(ROOT / module / "patches/android.patch")}
if variant == "release":
    shutil.copytree(ROOT / "app/build/outputs/mapping/release", reports / "r8")
manifest = {
    "version": VERSION, "versionCode": VERSION_CODE, "applicationId": "com.example.datasail",
    "gitSha": commit, "sourceStatus": "clean committed snapshot", "published": False,
    "variant": variant,
    "signing": {"type": "production" if variant == "release" else "local debug only", "certificateSha256": fingerprint.group(1)},
    "toolchain": {"jdk": "Temurin 21.0.8+9", "agp": "8.13.0", "gradle": "8.13", "kotlin": "2.2.10", "composeBom": "2025.08.01", "compileSdk": 36, "minSdk": 26, "buildTools": "36.0.0"},
    "androidTlsProvider": "Conscrypt 2.7.0, engine sockets, platform trust manager",
    "drivers": {"mssql-jdbc": "13.6.0.jre8", "mariadb-java-client": "3.5.10", "postgresql": "42.7.14", "mongodb-driver-sync": "5.6.5", "android-sasl-api": "Apache Harmony 02970cb7227a335edd2c8457ebdde0195a735733 (two unmodified API types)", "sqlite": "Android platform", "redis-valkey": "DataSail bounded RESP2/TLS implementation", "elasticsearch-opensearch": "DataSail bounded platform HTTPS/JSON implementation"},
    "sqlServerAndroidProfile": {"version": profile_lock["profile"], "jarSha256": sha(profile_jar), "upstreamSourceSha256": profile_lock["sourceSha256"], "patchSha256": sha(ROOT / "sqlserver-android/patches/android-platform.patch")},
    "verification": verification, "devices": devices,
    "androidDriverProfiles": profiles,
    "limitations": ["Physical devices not tested; consult TEST_REPORT for exact emulator/API evidence", "Writes are restricted to documented types and single objects; planned engines cannot connect", "Production certificate cannot overwrite historical Debug-signed installs"],
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
