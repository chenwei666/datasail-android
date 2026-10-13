#!/usr/bin/env bash
# Developer edition, isolated synthetic data only. ACCEPT_EULA applies to this test container.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
fixture="$(mktemp -d "$DATASAIL_ROOT/.local-sqlserver.XXXXXX")"
container="datasail-sqlserver-$RANDOM-$$"
cleanup() {
 if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
  adb shell run-as com.example.datasail rm -f files/fixture.properties files/fixture-ca.pem >/dev/null 2>&1 || true
  if [[ -n "${port:-}" ]]; then adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; fi
 fi
 docker rm -fv "$container" >/dev/null 2>&1 || true; rm -rf "$fixture"; }
trap cleanup EXIT
chmod 700 "$fixture"
mkdir -m 755 "$fixture/tls"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/tls/ca.pem" -days 2 -subj '/CN=DataSail temporary SQL test CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/tls/server-key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost,IP:10.0.2.2\nextendedKeyUsage=serverAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/tls/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/tls/server-cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
chmod 644 "$fixture/tls/"*.pem
cat > "$fixture/mssql.conf" <<'CONF'
[network]
forceencryption = 1
tlscert = /tls/server-cert.pem
tlskey = /tls/server-key.pem
tlsprotocols = 1.2
CONF
chmod 644 "$fixture/mssql.conf"
python3 - "$fixture" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1]); password='Ds!'+secrets.token_hex(24)
(p/'container.env').write_text('ACCEPT_EULA=Y\nMSSQL_PID=Developer\nMSSQL_SA_PASSWORD='+password+'\n')
(p/'database.properties').write_text('kind=SQL_SERVER\nhost=localhost\ndatabase=master\nusername=sa\npassword='+password+'\n')
for f in ['container.env','database.properties']: (p/f).chmod(0o600)
PY
tls_mounts=(--mount "type=bind,src=$fixture/tls,dst=/tls,readonly" --mount "type=bind,src=$fixture/mssql.conf,dst=/var/opt/mssql/mssql.conf,readonly")
if [[ "${DATASAIL_SQLSERVER_AUTOCERT:-0}" == 1 ]]; then tls_mounts=(); fi
docker run -d --name "$container" --env-file "$fixture/container.env" --memory=3g \
 -p 127.0.0.1::1433 "${tls_mounts[@]}" \
 mcr.microsoft.com/mssql/server@sha256:45a1a9d13ca5574cf8e0fe4ae73ab77248b66d9c3132ac9658fb6c16dd72a8af >/dev/null
port="$(docker port "$container" 1433/tcp | sed 's/.*://')"
printf 'port=%s\n' "$port" >> "$fixture/database.properties"
ready=false
for attempt in $(seq 1 90); do
 if docker logs "$container" 2>&1 | rg -q 'SQL Server is now ready for client connections'; then ready=true; break; fi
 sleep 1
done
[[ "$ready" == true ]] || { echo 'SQL Server fixture did not become ready; no credentials logged.' >&2; docker logs --tail 30 "$container"; exit 1; }
keytool -importcert -noprompt -alias datasail-test-ca -file "$fixture/tls/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_SQLSERVER_CONFIG="$fixture/database.properties"
export DATASAIL_SQLSERVER_TRUSTSTORE="$fixture/trust.p12"
task=sqlServerTest
suite=SQL_SERVER
android_class=com.example.datasail.SqlServerDeviceTest
if [[ "${DATASAIL_SQLSERVER_AUTOCERT:-0}" == 1 ]]; then
 task=sqlServerAutoCertificateTest; suite=SQL_SERVER_AUTOCERT; android_class=com.example.datasail.CertificateTrustUiTest
fi
./gradlew ":core:$task" --console=plain
mkdir -p "docs/evidence/v071/$suite"
cp core/build/test-results/"$task"/TEST-*.xml "docs/evidence/v071/$suite/"

if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
 adb install -r app/build/outputs/apk/debug/app-debug.apk
 adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 # Credentials travel via stdin to app-private storage, never CLI arguments, logs or archive.
 adb shell run-as com.example.datasail mkdir -p files
 adb shell run-as com.example.datasail sh -c "'cat > files/fixture.properties'" < "$fixture/database.properties"
 adb shell run-as com.example.datasail sh -c "'cat > files/fixture-ca.pem'" < "$fixture/tls/ca.pem"
 adb reverse "tcp:$port" "tcp:$port"
 adb shell am instrument -w -e class "$android_class" com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture/android-result.txt"
 adb shell run-as com.example.datasail rm -f files/fixture.properties files/fixture-ca.pem
 rg -q 'OK \(2 tests\)' "$fixture/android-result.txt"
 cp "$fixture/android-result.txt" "docs/evidence/v071/$suite/android-${ANDROID_SERIAL}.txt"
fi
