#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Use a disposable emulator.'; exit 2; }
fixture=$(mktemp -d "$DATASAIL_ROOT/.local/ai-fixture.XXXXXX")
server_pid=''
ai_host_port="${DATASAIL_AI_HOST_PORT:-18743}"
[[ "$ai_host_port" =~ ^[0-9]+$ && "$ai_host_port" -ge 1024 && "$ai_host_port" -le 65535 ]]
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  adb reverse --remove tcp:18743 >/dev/null 2>&1 || true
  adb shell run-as com.example.datasail rm -f files/ai-fixture-ca.crt >/dev/null 2>&1 || true
  rm -rf "$fixture"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/key.pem" -out "$fixture/cert.pem" -days 2 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' >"$fixture/openssl.log" 2>&1
python3 scripts/fixtures/ai-service.py --port "$ai_host_port" --cert "$fixture/cert.pem" --key "$fixture/key.pem" >"$fixture/server.log" 2>&1 &
server_pid=$!
for attempt in $(seq 1 30); do if curl --silent --cacert "$fixture/cert.pem" "https://localhost:$ai_host_port/openai/models" >/dev/null; then break; fi; sleep 0.2; done
bash scripts/install-android-test-apks.sh
adb shell run-as com.example.datasail mkdir -p files
adb shell run-as com.example.datasail sh -c "'cat > files/ai-fixture-ca.crt'" < "$fixture/cert.pem"
adb reverse tcp:18743 "tcp:$ai_host_port"
mkdir -p docs/evidence/v0100
adb shell am instrument -w -e class com.example.datasail.AiServiceUiTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "docs/evidence/v0100/ai-$ANDROID_SERIAL.txt"
rg -q 'OK \(3 tests\)' "docs/evidence/v0100/ai-$ANDROID_SERIAL.txt"
