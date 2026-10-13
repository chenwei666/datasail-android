#!/usr/bin/env bash
# Separate real TLS servers, synthetic accounts, plus explicitly separate adversarial HTTP fixture.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
kind="${1:?ELASTICSEARCH or OPENSEARCH}"
case "$kind" in
 ELASTICSEARCH) image='elasticsearch@sha256:7a159fec8fc8a7b15ae74dcc01f418b39147a93ae097236774fec0b23e40364a'; server_home=/usr/share/elasticsearch;;
 OPENSEARCH) image='opensearchproject/opensearch@sha256:16effbdfb018599a0f5cf5102e15b7ecce1aacbc9959a2203ff4769ff23d375f'; server_home=/usr/share/opensearch;;
 *) exit 2;;
esac
mkdir -p .local
fixture="$(mktemp -d "$DATASAIL_ROOT/.local/search.XXXXXX")"
container="datasail-search-$RANDOM-$$"
cleanup() {
 local result=$?
 docker logs "$container" > "/tmp/datasail-search-server-$kind.log" 2>&1 || true
 if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
  adb shell run-as com.example.datasail rm -f files/search-fixture.properties files/search-ca.pem >/dev/null 2>&1 || true
  for target in "${port:-}" "${fault_port:-}"; do [[ -z "$target" ]] || adb reverse --remove "tcp:$target" >/dev/null 2>&1 || true; done
 fi
 [[ -z "${fault_pid:-}" ]] || kill "$fault_pid" 2>/dev/null || true
 docker rm -fv "$container" >/dev/null 2>&1 || true
 rm -rf "$fixture"
}
trap cleanup EXIT
mkdir -m 755 "$fixture/server"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/server/ca.pem" -days 2 -subj '/CN=DataSail temporary search CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/server/key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth,clientAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/server/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/server/cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
chmod 644 "$fixture/server/"*.pem
python3 - "$fixture" "$kind" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1]); password=secrets.token_hex(24)
(p/'password').write_text(password); (p/'password').chmod(0o600)
(p/'database.properties').write_text(f'kind={sys.argv[2]}\npassword={password}\n'); (p/'database.properties').chmod(0o600)
(p/'env').write_text('ELASTIC_PASSWORD='+password+'\n'); (p/'env').chmod(0o600)
PY
if [[ "$kind" == ELASTICSEARCH ]]; then
 docker run -d --name "$container" -p '127.0.0.1::9200' --env-file "$fixture/env" -e 'discovery.type=single-node' -e 'ES_JAVA_OPTS=-Xms512m -Xmx512m' -e 'node.store.allow_mmap=false' -e 'xpack.security.enabled=true' -e 'xpack.security.http.ssl.enabled=true' -e 'xpack.security.http.ssl.key=fixture/key.pem' -e 'xpack.security.http.ssl.certificate=fixture/cert.pem' -e 'xpack.security.http.ssl.certificate_authorities=fixture/ca.pem' --mount "type=bind,src=$fixture/server,dst=$server_home/config/fixture,readonly" "$image" >/dev/null
else
 # Use the image's security configuration schema, replacing all built-in users and roles.
 docker create --name "$container" "$image" >/dev/null
 docker cp "$container:$server_home/config/opensearch-security" "$fixture/security"
 docker rm "$container" >/dev/null
 python3 - "$fixture" <<'PY'
from pathlib import Path
import crypt,sys
p=Path(sys.argv[1]); hashed=crypt.crypt((p/'password').read_text(),crypt.mksalt(crypt.METHOD_BLOWFISH))
(p/'security/internal_users.yml').write_text('_meta:\n  type: internalusers\n  config_version: 2\n'+''.join(f'{u}:\n  hash: "{hashed}"\n  reserved: false\n' for u in ['admin','reader','denied']))
(p/'security/roles.yml').write_text('''_meta:
  type: roles
  config_version: 2
fixture_admin:
  cluster_permissions: ['*']
  index_permissions:
    - index_patterns: ['*']
      allowed_actions: ['*']
fixture_denied:
  cluster_permissions: ['cluster_monitor']
fixture_reader:
  cluster_permissions: ['cluster_monitor']
  index_permissions:
    - index_patterns: ['synthetic']
      allowed_actions: ['read', 'indices_monitor', 'indices:admin/mappings/get']
''')
(p/'security/roles_mapping.yml').write_text('''_meta:
  type: rolesmapping
  config_version: 2
fixture_admin:
  users: ['admin']
fixture_reader:
  users: ['reader']
fixture_denied:
  users: ['denied']
''')
PY
 cat > "$fixture/opensearch.yml" <<'YAML'
cluster.name: datasail-fixture
network.host: 0.0.0.0
discovery.type: single-node
node.store.allow_mmap: false
plugins.security.ssl.transport.pemcert_filepath: fixture/cert.pem
plugins.security.ssl.transport.pemkey_filepath: fixture/key.pem
plugins.security.ssl.transport.pemtrustedcas_filepath: fixture/ca.pem
plugins.security.ssl.http.enabled: true
plugins.security.ssl.http.pemcert_filepath: fixture/cert.pem
plugins.security.ssl.http.pemkey_filepath: fixture/key.pem
plugins.security.ssl.http.pemtrustedcas_filepath: fixture/ca.pem
plugins.security.nodes_dn: ['CN=localhost']
plugins.security.allow_default_init_securityindex: true
YAML
 chmod -R a+rX "$fixture/security"
 docker run -d --name "$container" -p '127.0.0.1::9200' -e DISABLE_INSTALL_DEMO_CONFIG=true -e 'OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m' --mount "type=bind,src=$fixture/server,dst=$server_home/config/fixture,readonly" --mount "type=bind,src=$fixture/security,dst=$server_home/config/opensearch-security,readonly" --mount "type=bind,src=$fixture/opensearch.yml,dst=$server_home/config/opensearch.yml,readonly" "$image" >/dev/null
fi
port="$(docker port "$container" 9200/tcp | sed 's/.*://')"
printf '%s' "$port" > "$fixture/port"
if ! python3 scripts/fixtures/search-control.py seed "$fixture" "$kind"; then docker logs --tail 70 "$container"; exit 1; fi
python3 scripts/fixtures/search-control.py fault "$fixture" "$kind" > "$fixture/fault.log" 2>&1 & fault_pid=$!
for attempt in {1..20}; do [[ ! -f "$fixture/fault-port" ]] || break; sleep 1; done
fault_port="$(cat "$fixture/fault-port")"
printf 'port=%s\nfaultPort=%s\n' "$port" "$fault_port" >> "$fixture/database.properties"
keytool -importcert -noprompt -alias fixture-ca -file "$fixture/server/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_TEST_CONFIG="$fixture/database.properties" DATASAIL_TEST_TRUSTSTORE="$fixture/trust.p12"
evidence="docs/evidence/v071/$kind"; mkdir -p "$evidence"
cp "$fixture/server-version.json" "$evidence/"
if [[ "${DATASAIL_SKIP_JVM:-0}" != 1 ]]; then
 ./gradlew :core:searchTest --console=plain
 cp core/build/test-results/searchTest/TEST-*.xml "$evidence/"
fi
if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
 for device in ${DATASAIL_ANDROID_SERIALS:-${ANDROID_SERIAL:-emulator-5554}}; do
 export ANDROID_SERIAL="$device"
 install_verified() {
  local package="$1" apk="$2" remote installed expected
  remote="$(adb shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  expected="$(sha256sum "$apk" | cut -d ' ' -f1)"; installed=""
  if [[ "$remote" =~ ^/data/app/[A-Za-z0-9_./+=~-]+$ ]]; then installed="$(adb shell sha256sum "$remote" | cut -d ' ' -f1 | tr -d '\r')"; fi
  if [[ "$installed" == "$expected" ]]; then printf 'Verified existing APK: %s\n' "$package"; else adb install -r "$apk"; fi
 }
 install_verified com.example.datasail app/build/outputs/apk/debug/app-debug.apk
 install_verified com.example.datasail.test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 adb shell run-as com.example.datasail mkdir -p files
 adb shell run-as com.example.datasail sh -c "'cat > files/search-fixture.properties'" < "$fixture/database.properties"
 adb shell run-as com.example.datasail sh -c "'cat > files/search-ca.pem'" < "$fixture/server/ca.pem"
 adb reverse "tcp:$port" "tcp:$port"; adb reverse "tcp:$fault_port" "tcp:$fault_port"
 adb shell am instrument -w -e class com.example.datasail.SearchDeviceTest,com.example.datasail.SearchUiTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture/android-result.txt"
 cp "$fixture/android-result.txt" "$evidence/android-${ANDROID_SERIAL}.txt"
 rg -q 'OK \(5 tests\)' "$fixture/android-result.txt"
 adb shell run-as com.example.datasail rm -f files/search-fixture.properties files/search-ca.pem
 adb reverse --remove "tcp:$port"; adb reverse --remove "tcp:$fault_port"
 done
fi
[[ ! -f "$fixture/redirect-leaked" ]] || { echo 'Redirect isolation failed'; exit 1; }
