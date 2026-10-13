#!/usr/bin/env bash
# Real, isolated TLS servers. No credentials in command arguments, logs, or evidence.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
kind="${1:-REDIS}"
case "$kind" in
 REDIS) image='redis@sha256:08ad0b1d280850169a790dba1393ff7a90aef951fc19632cf4d3ce4f78e679ba'; server=redis-server ;;
 VALKEY) image='valkey/valkey@sha256:918228e4ff7da6b3a4213cb18067f6e09d9f0503d0a08868699ba227cff71861'; server=valkey-server ;;
 *) echo 'Use REDIS or VALKEY'; exit 2 ;;
esac
mkdir -p .local
fixture="$(mktemp -d "$DATASAIL_ROOT/.local/kv.XXXXXX")"
container="datasail-kv-$RANDOM-$$"
cleanup() {
 if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
  adb shell run-as com.example.datasail rm -f files/kv-fixture.properties files/kv-ca.pem >/dev/null 2>&1 || true
  if [[ -n "${port:-}" ]]; then adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; fi
 fi
 docker rm -fv "$container" >/dev/null 2>&1 || true
 rm -rf "$fixture"
}
trap cleanup EXIT
mkdir -m 755 "$fixture/server"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/server/ca.pem" -days 2 -subj '/CN=DataSail temporary KV CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/server/key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/server/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/server/cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
chmod 644 "$fixture/server/"*.pem
python3 - "$fixture" "$kind" <<'PY'
from pathlib import Path
import secrets,sys,hashlib
p=Path(sys.argv[1]); password=secrets.token_hex(24); hashed=hashlib.sha256(password.encode()).hexdigest()
reads='ping select scan type pttl strlen getrange llen lrange hlen hscan scard sscan zcard zrange xlen xrange'.split()
(p/'server/users.acl').write_text('user default off\n'+f'user seed on #{hashed} ~* +@all\n'+f'user reader on #{hashed} ~* -@all '+ ' '.join('+'+r for r in reads)+' +info|keyspace\n'+f'user noinfo on #{hashed} ~* -@all '+ ' '.join('+'+r for r in reads)+'\n'+f'user pauser on #{hashed} -@all +client|pause\n')
(p/'server/server.conf').write_text('port 0\ntls-port 6379\nbind 0.0.0.0\nprotected-mode yes\ntls-cert-file /fixture/cert.pem\ntls-key-file /fixture/key.pem\ntls-ca-cert-file /fixture/ca.pem\ntls-auth-clients no\naclfile /fixture/users.acl\nsave ""\nappendonly no\n')
(p/'database.properties').write_text(f'kind={sys.argv[2]}\npassword={password}\n')
(p/'database.properties').chmod(0o600)
PY
chmod 644 "$fixture/server/server.conf" "$fixture/server/users.acl"
docker run -d --name "$container" -p '127.0.0.1::6379' --mount "type=bind,src=$fixture/server,dst=/fixture,readonly" "$image" "$server" /fixture/server.conf >/dev/null
port="$(docker port "$container" 6379/tcp | sed 's/.*://')"
printf 'port=%s\n' "$port" >> "$fixture/database.properties"
python3 - "$fixture" "$port" <<'PY'
from pathlib import Path
import sys,ssl,socket,time
p=Path(sys.argv[1]); port=int(sys.argv[2]); password=dict(line.split('=',1) for line in (p/'database.properties').read_text().splitlines())['password']
context=ssl.create_default_context(cafile=str(p/'server/ca.pem'))
for attempt in range(30):
 try:
  conn=context.wrap_socket(socket.create_connection(('localhost',port),timeout=5),server_hostname='localhost'); break
 except (ConnectionError,OSError): time.sleep(1)
else: raise SystemExit('Fixture did not start')
with conn:
 f=conn.makefile('rb')
 def command(*args):
  args=[arg if isinstance(arg,bytes) else str(arg).encode() for arg in args]
  conn.sendall(b'*'+str(len(args)).encode()+b'\r\n'+b''.join(b'$'+str(len(arg)).encode()+b'\r\n'+arg+b'\r\n' for arg in args))
  line=f.readline()
  if line[:1]==b'$': return f.read(int(line[1:])+2)
  assert line[:1] in (b'+',b':',b'-'); return line
 assert command('AUTH','seed',password)==b'+OK\r\n'
 for i in range(200): command('SET',f'item:{i:04}',str(i))
 command('SET','string','0007 数舟'); command('RPUSH','list','a','b'); command('HSET','hash','name','0007')
 command('SADD','set','a','b'); command('ZADD','zset','1.5','0007'); command('XADD','stream','1-0','name','0007')
 command('SET','expiring','value','EX',3600); command('SET','long','a'*4096)
 command('SET',bytes([0,255,13,10]),bytes([0,255])); command('SET','odd\r\n*"','special')
 for i in range(600): command('HSET','largehash',str(i),str(i))
 command('LPUSH','oversized','x'*9000)
 command('SELECT','5'); command('SET','discovery-marker','synthetic'); command('SELECT','0')
 assert command('AUTH','reader',password)==b'+OK\r\n'
 assert command('SET','forbidden','write').startswith(b'-NOPERM')
 assert command('DEL','string').startswith(b'-NOPERM')
print('Synthetic fixture seeded; reader SET and DEL rejected by ACL.')
PY
keytool -importcert -noprompt -alias fixture-ca -file "$fixture/server/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_TEST_CONFIG="$fixture/database.properties"
export DATASAIL_TEST_TRUSTSTORE="$fixture/trust.p12"
./gradlew :core:keyValueTest --console=plain
mkdir -p "docs/evidence/v071/$kind"
cp core/build/test-results/keyValueTest/TEST-*.xml "docs/evidence/v071/$kind/"
if [[ "${DATASAIL_ANDROID_TESTS:-0}" == 1 ]]; then
 adb install -r app/build/outputs/apk/debug/app-debug.apk
 adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 adb shell run-as com.example.datasail mkdir -p files
 adb shell run-as com.example.datasail sh -c "'cat > files/kv-fixture.properties'" < "$fixture/database.properties"
 adb shell run-as com.example.datasail sh -c "'cat > files/kv-ca.pem'" < "$fixture/server/ca.pem"
 adb reverse "tcp:$port" "tcp:$port"
 adb shell am instrument -w -e class com.example.datasail.KeyValueDeviceTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture/android-result.txt"
 rg -q 'OK \(3 tests\)' "$fixture/android-result.txt"
 cp "$fixture/android-result.txt" "docs/evidence/v071/$kind/android-${ANDROID_SERIAL}.txt"
fi
