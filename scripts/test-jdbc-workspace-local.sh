#!/usr/bin/env bash
# Isolated loopback fixture; kind must name the actual server under test.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
kind="${1:-MYSQL}"
case "$kind" in
 MYSQL) image='mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9'; dbport=3306 ;;
 MARIADB) image='mariadb@sha256:6422478cb8e159f080fb1d8ccf65101e26fe51385787fde7d16c3b165a331f15'; dbport=3306 ;;
 POSTGRESQL) image='postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db'; dbport=5432 ;;
 *) echo 'Use MYSQL, MARIADB or POSTGRESQL'; exit 2 ;;
esac
mkdir -p .local
fixture="$(mktemp -d "$DATASAIL_ROOT/.local/jdbc.XXXXXX")"
container="datasail-jdbc-$RANDOM-$$"
cleanup() {
 if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
  adb shell run-as com.example.datasail rm -f files/jdbc-fixture.properties files/jdbc-ca.pem >/dev/null 2>&1 || true
  if [[ -n "${port:-}" ]]; then adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; fi
 fi
 docker rm -fv "$container" >/dev/null 2>&1 || true
 rm -rf "$fixture"
}
trap cleanup EXIT
mkdir -m 755 "$fixture/tls"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/tls/ca.pem" -days 2 -subj '/CN=DataSail temporary JDBC CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/tls/server-key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/tls/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/tls/server-cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
# Synthetic two-day server key only, copied to private container storage below for PostgreSQL.
chmod 644 "$fixture/tls/"*.pem
python3 - "$fixture" "$kind" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1]); kind=sys.argv[2]; password=secrets.token_hex(24)
if kind=='POSTGRESQL':
 env='POSTGRES_DB=synthetic\nPOSTGRES_USER=datasail\nPOSTGRES_PASSWORD='+password+'\n'
else:
 prefix='MYSQL' if kind=='MYSQL' else 'MARIADB'
 env=f'{prefix}_DATABASE=synthetic\n{prefix}_USER=datasail\n{prefix}_PASSWORD={password}\n{prefix}_ROOT_PASSWORD='+secrets.token_hex(24)+'\n'
(p/'container.env').write_text(env)
(p/'database.properties').write_text(f'kind={kind}\nhost=localhost\ndatabase=synthetic\nusername=datasail\npassword={password}\n')
for f in ['container.env','database.properties']: (p/f).chmod(0o600)
PY
if [[ "$kind" == POSTGRESQL ]]; then
 docker run -d --name "$container" --env-file "$fixture/container.env" -p "127.0.0.1::$dbport" \
  --mount "type=bind,src=$fixture/tls,dst=/tls,readonly" "$image" \
  bash -c 'mkdir /tmp/datasail-tls; cp /tls/* /tmp/datasail-tls/; chown -R postgres:postgres /tmp/datasail-tls; chmod 600 /tmp/datasail-tls/server-key.pem; exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/datasail-tls/server-cert.pem -c ssl_key_file=/tmp/datasail-tls/server-key.pem' >/dev/null
else
 docker run -d --name "$container" --env-file "$fixture/container.env" -p "127.0.0.1::$dbport" \
  --mount "type=bind,src=$fixture/tls,dst=/tls,readonly" "$image" \
  --require-secure-transport=ON --ssl-ca=/tls/ca.pem --ssl-cert=/tls/server-cert.pem --ssl-key=/tls/server-key.pem \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null
fi
port="$(docker port "$container" "$dbport/tcp" | sed 's/.*://')"
printf 'port=%s\n' "$port" >> "$fixture/database.properties"
ready=false
for attempt in $(seq 1 90); do
 case "$kind" in
  POSTGRESQL) if docker exec "$container" pg_isready -h 127.0.0.1 -U datasail -d synthetic >/dev/null 2>&1; then ready=true; break; fi ;;
  MYSQL) if docker exec "$container" mysqladmin --protocol=TCP --host=127.0.0.1 ping --silent >/dev/null 2>&1; then ready=true; break; fi ;;
  MARIADB) if docker exec "$container" mariadb-admin --protocol=TCP --host=127.0.0.1 ping --silent >/dev/null 2>&1; then ready=true; break; fi ;;
 esac
 sleep 1
done
[[ "$ready" == true ]] || { echo 'Fixture not ready; no credentials logged.' >&2; exit 1; }
keytool -importcert -noprompt -alias fixture-ca -file "$fixture/tls/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_TEST_CONFIG="$fixture/database.properties"
export DATASAIL_TEST_TRUSTSTORE="$fixture/trust.p12"
./gradlew :core:jdbcWorkspaceTest --console=plain
mkdir -p "docs/evidence/v050/$kind"
cp core/build/test-results/jdbcWorkspaceTest/TEST-*.xml "docs/evidence/v050/$kind/"
if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
 adb install -r app/build/outputs/apk/debug/app-debug.apk
 adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 adb shell run-as com.example.datasail mkdir -p files
 adb shell run-as com.example.datasail sh -c "'cat > files/jdbc-fixture.properties'" < "$fixture/database.properties"
 adb shell run-as com.example.datasail sh -c "'cat > files/jdbc-ca.pem'" < "$fixture/tls/ca.pem"
 adb reverse "tcp:$port" "tcp:$port"
 adb shell am instrument -w -e class com.example.datasail.JdbcWorkspaceDeviceTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture/android-result.txt"
 rg -q 'OK \(2 tests\)' "$fixture/android-result.txt"
 cp "$fixture/android-result.txt" "docs/evidence/v050/$kind/android-${ANDROID_SERIAL}.txt"
fi
