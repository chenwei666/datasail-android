#!/usr/bin/env bash
# Deliberately destructive only to this app's synthetic data on a selected emulator.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
[[ "${1:-}" == --reset-synthetic-emulator ]] || { echo 'Requires --reset-synthetic-emulator; use a disposable emulator only.'; exit 2; }
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Refusing to reset a physical device.'; exit 2; }
baseline=versions/V0.2.1/apk/DataSail-V0.2.1-debug.apk
[[ -f "$baseline" ]] || { echo 'Provide the immutable V0.2.1 archive first.'; exit 2; }
adb uninstall com.example.datasail >/dev/null 2>&1 || true
adb install "$baseline"
adb shell dumpsys package com.example.datasail | rg 'versionCode|versionName'
adb shell run-as com.example.datasail mkdir -p files
printf 'synthetic upgrade marker\n' | adb shell run-as com.example.datasail sh -c "'cat > files/upgrade-marker.txt'"
# Seed V0.2.1's documented schema with only synthetic, non-secret connection metadata.
printf '%s' '{"schema":1,"connections":[{"id":"upgrade-fixture","name":"升级保留连接","host":"localhost","port":"1433","database":"synthetic","username":"fixture","production":true,"ca":""}]}' | adb shell run-as com.example.datasail sh -c "'cat > files/connections-v1.json'"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.example.datasail | rg 'versionCode|versionName'
adb shell run-as com.example.datasail cat files/upgrade-marker.txt | rg '^synthetic upgrade marker'
adb shell run-as com.example.datasail rm files/upgrade-marker.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p "$DATASAIL_ROOT/.local"
result="$(mktemp "$DATASAIL_ROOT/.local/android-smoke.XXXXXX")"
trap 'rm -f "$result"' EXIT
adb shell am instrument -w -e class com.example.datasail.SmokeTest,com.example.datasail.UpgradeTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
rg -q 'OK \(4 tests\)' "$result"
