#!/usr/bin/env bash
# Deliberately destructive only to this app's synthetic data on a selected emulator.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
[[ "${1:-}" == --reset-synthetic-emulator ]] || { echo 'Requires --reset-synthetic-emulator; use a disposable emulator only.'; exit 2; }
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Refusing to reset a physical device.'; exit 2; }
baseline=versions/V0.12.2/apk/DataSail-V0.12.2-debug.apk
[[ -f "$baseline" ]] || { echo 'Provide the immutable V0.12.2 archive first.'; exit 2; }
adb uninstall com.example.datasail >/dev/null 2>&1 || true
adb install --no-streaming "$baseline"
adb shell dumpsys package com.example.datasail | rg 'versionCode|versionName'
adb shell run-as com.example.datasail mkdir -p files
printf 'synthetic upgrade marker\n' | adb shell run-as com.example.datasail sh -c "'cat > files/upgrade-marker.txt'"
# Seed V0.12.2's documented schema with only synthetic, non-secret connection metadata.
printf '%s' '{"schema":4,"connections":[{"kind":"SQL_SERVER","id":"upgrade-fixture","name":"升级保留连接","host":"localhost","port":"1433","database":"synthetic","username":"fixture","production":true,"ca":"","authDatabase":"admin","certificateTrust":{"connectionId":"upgrade-fixture","kind":"SQL_SERVER","host":"localhost","port":1433,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","confirmedAt":123}},{"kind":"MONGODB","id":"upgrade-mongo","name":"原有文档连接","host":"localhost","port":"27017","database":"synthetic","username":"fixture","production":true,"ca":"","authDatabase":"custom_auth"},{"kind":"OPENSEARCH","id":"upgrade-search","name":"原有搜索连接","host":"localhost","port":"9200","database":"logs-*","username":"fixture","production":true,"ca":"","authDatabase":"admin"}]}' | adb shell run-as com.example.datasail sh -c "'cat > files/connections-v1.json'"
printf '%s' '{"schema":1,"queries":[{"id":"upgrade-query","name":"升级保留收藏","kind":"POSTGRESQL","sql":"SELECT 1"}]}' | adb shell run-as com.example.datasail sh -c "'cat > files/queries-v1.json'"
bash scripts/install-android-test-apks.sh test
seed_result="$(mktemp "$DATASAIL_ROOT/.local/upgrade-seed.XXXXXX")"
adb shell am instrument -w -e class com.example.datasail.UpgradeStateSeedTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$seed_result"
rg -q 'OK \(1 test\)' "$seed_result"
rm "$seed_result"
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.example.datasail | rg 'versionCode|versionName'
adb shell run-as com.example.datasail cat files/upgrade-marker.txt | rg '^synthetic upgrade marker'
adb shell run-as com.example.datasail rm files/upgrade-marker.txt
bash scripts/install-android-test-apks.sh test
mkdir -p "$DATASAIL_ROOT/.local"
result="$(mktemp "$DATASAIL_ROOT/.local/android-smoke.XXXXXX")"
trap 'rm -f "$result"' EXIT
adb shell am instrument -w -e class com.example.datasail.SmokeTest,com.example.datasail.UpgradeTest,com.example.datasail.UpgradeStateVerifyTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
rg -q 'OK \(5 tests\)' "$result"
