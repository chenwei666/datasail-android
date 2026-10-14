#!/usr/bin/env bash
# Isolated, synthetic MongoDB TLS fixture; test-only administrator and failpoints never enter the APK.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
image='mongo@sha256:d0d926f94df099bff534b7ee5b5986458131a22489dfff8664509af0c1e2ca9c'
mkdir -p .local
fixture="$(mktemp -d "$DATASAIL_ROOT/.local/mongo.XXXXXX")"
container="datasail-mongo-$RANDOM-$$"
cleanup() {
 if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
  adb shell run-as com.example.datasail rm -f files/mongo-fixture.properties files/mongo-ca.pem >/dev/null 2>&1 || true
  if [[ -n "${port:-}" ]]; then adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; fi
 fi
 docker rm -fv "$container" >/dev/null 2>&1 || true
 rm -rf "$fixture"
}
trap cleanup EXIT
mkdir -m 755 "$fixture/server"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/server/ca.pem" -days 2 -subj '/CN=DataSail temporary Mongo CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/server/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
cat "$fixture/key.pem" "$fixture/cert.pem" > "$fixture/server/server.pem"
chmod 644 "$fixture/server/"*.pem
python3 - "$fixture" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1]); password=secrets.token_hex(24)
(p/'server/password').write_text(password); (p/'server/password').chmod(0o600)
(p/'database.properties').write_text(f'kind=MONGODB\npassword={password}\n'); (p/'database.properties').chmod(0o600)
PY
cat > "$fixture/server/seed.js" <<'JS'
const password = require('fs').readFileSync('/fixture/password','utf8');
const admin = db.getSiblingDB('admin');
admin.createUser({user:'controller',pwd:password,roles:['root'],mechanisms:['SCRAM-SHA-256']});
admin.auth('controller',password);
admin.createUser({user:'writer',pwd:password,roles:[{role:'readWrite',db:'synthetic'}],mechanisms:['SCRAM-SHA-256']});
admin.createUser({user:'reader',pwd:password,roles:[{role:'read',db:'synthetic'}],mechanisms:['SCRAM-SHA-256']});
const target = db.getSiblingDB('synthetic');
for (let i=0; i<205; i++) target.documents.insertOne({_id: i===7 ? ObjectId('0123456789abcdef01234567') : new ObjectId(), name:String(i).padStart(4,'0'), long:NumberLong('9007199254740993'), decimal:NumberDecimal('1.2300'), date:new Date('2025-01-01T00:00:00Z'), binary:BinData(0,'AP8='), empty:'', nullable:null, nested:{value:[true,1,'text']}});
target.oversized.insertOne({value:'x'.repeat(40000)});
print('Synthetic MongoDB fixture ready: '+db.version());
JS
chmod 644 "$fixture/server/seed.js"
docker run -d --name "$container" -p '127.0.0.1::27017' --mount "type=bind,src=$fixture/server,dst=/fixture,readonly" "$image" mongod --auth --bind_ip_all --tlsMode requireTLS --tlsCertificateKeyFile /fixture/server.pem --tlsCAFile /fixture/ca.pem --tlsAllowConnectionsWithoutCertificates --setParameter enableTestCommands=1 --wiredTigerCacheSizeGB 0.25 >/dev/null
port="$(docker port "$container" 27017/tcp | sed 's/.*://')"
printf 'port=%s\n' "$port" >> "$fixture/database.properties"
ready=0
for attempt in {1..30}; do
 if docker exec "$container" mongosh --quiet --host localhost --tls --tlsCAFile /fixture/ca.pem --eval 'quit(db.runCommand({ping:1}).ok ? 0 : 1)' >/dev/null 2>&1; then ready=1; break; fi
 sleep 1
done
[[ "$ready" == 1 ]] || { echo 'Mongo fixture failed readiness'; exit 1; }
docker exec --user 0 "$container" mongosh --quiet --host localhost --tls --tlsCAFile /fixture/ca.pem --file /fixture/seed.js
keytool -importcert -noprompt -alias fixture-ca -file "$fixture/server/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_TEST_CONFIG="$fixture/database.properties"
export DATASAIL_TEST_TRUSTSTORE="$fixture/trust.p12"
evidence="docs/evidence/v0130/MONGODB"
mkdir -p "$evidence"
if [[ "${DATASAIL_SKIP_JVM:-0}" != 1 ]]; then
 ./gradlew :core:mongoTest --console=plain
 cp core/build/test-results/mongoTest/TEST-*.xml "$evidence/"
fi
if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
 install_verified() {
  local package="$1" apk="$2" remote installed expected
  remote="$(adb shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  expected="$(sha256sum "$apk" | cut -d ' ' -f1)"
  installed=""
  if [[ "$remote" =~ ^/data/app/[A-Za-z0-9_./+=~-]+$ ]]; then installed="$(adb shell sha256sum "$remote" | cut -d ' ' -f1 | tr -d '\r')"; fi
  if [[ "$installed" == "$expected" ]]; then printf 'Verified existing APK: %s\n' "$package"; else adb install -r "$apk"; fi
 }
 install_verified com.example.datasail app/build/outputs/apk/debug/app-debug.apk
 install_verified com.example.datasail.test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 adb shell run-as com.example.datasail mkdir -p files
 adb shell run-as com.example.datasail sh -c "'cat > files/mongo-fixture.properties'" < "$fixture/database.properties"
 adb shell run-as com.example.datasail sh -c "'cat > files/mongo-ca.pem'" < "$fixture/server/ca.pem"
 adb reverse "tcp:$port" "tcp:$port"
 classes=com.example.datasail.MongoDeviceTest,com.example.datasail.MongoUiTest; expected=5
 if [[ "${DATASAIL_MONGO_UI_ONLY:-0}" == 1 ]]; then classes=com.example.datasail.MongoUiTest; expected=1; fi
 adb shell am instrument -w -e class "$classes" com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture/android-result.txt"
 cp "$fixture/android-result.txt" "$evidence/android-${ANDROID_SERIAL}.txt"
 rg -q "OK \($expected tests\)" "$fixture/android-result.txt"
fi
