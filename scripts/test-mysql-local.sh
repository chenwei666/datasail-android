#!/usr/bin/env bash
# Isolated, loopback-only synthetic fixture. Never point this at a production database.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
fixture="$(mktemp -d "$DATASAIL_ROOT/.local-mysql.XXXXXX")"
container="datasail-mysql-$RANDOM-$$"
cleanup() { docker rm -fv "$container" >/dev/null 2>&1 || true; rm -rf "$fixture"; }
trap cleanup EXIT
chmod 700 "$fixture"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca-key.pem" -out "$fixture/ca.pem" -days 2 -subj '/CN=DataSail temporary test CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/server-key.pem" -out "$fixture/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n' > "$fixture/server.ext"
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/ca.pem" -CAkey "$fixture/ca-key.pem" -CAcreateserial -out "$fixture/server-cert.pem" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
# Only this temporary, synthetic server key is made readable to the container's mysql UID.
chmod 644 "$fixture/server-key.pem"
mkdir -m 755 "$fixture/tls"
cp "$fixture/ca.pem" "$fixture/server-cert.pem" "$fixture/server-key.pem" "$fixture/tls/"
chmod 644 "$fixture/tls/"*.pem
python3 - "$fixture" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1]); password=secrets.token_hex(24)
(p/'container.env').write_text('MYSQL_ROOT_PASSWORD='+secrets.token_hex(24)+'\nMYSQL_DATABASE=synthetic\nMYSQL_USER=datasail\nMYSQL_PASSWORD='+password+'\n')
(p/'database.properties').write_text('kind=MYSQL\nhost=localhost\ndatabase=synthetic\nusername=datasail\npassword='+password+'\n')
for f in ['container.env','database.properties']: (p/f).chmod(0o600)
PY
docker run -d --name "$container" --env-file "$fixture/container.env" \
    -p 127.0.0.1::3306 --mount "type=bind,src=$fixture/tls,dst=/tls,readonly" \
    mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9 \
    --require-secure-transport=ON --ssl-ca=/tls/ca.pem --ssl-cert=/tls/server-cert.pem --ssl-key=/tls/server-key.pem \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null
port="$(docker port "$container" 3306/tcp | sed 's/.*://')"
printf 'port=%s\n' "$port" >> "$fixture/database.properties"
ready=false
for attempt in $(seq 1 60); do
    if docker exec "$container" mysqladmin --protocol=TCP --host=127.0.0.1 ping --silent >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == true ]] || { echo 'MySQL fixture did not become ready; no credentials logged.' >&2; exit 1; }
docker exec "$container" mysqld --version
keytool -importcert -noprompt -alias datasail-test-ca -file "$fixture/ca.pem" -keystore "$fixture/trust.p12" -storepass changeit >/dev/null 2>&1
export DATASAIL_TEST_CONFIG="$fixture/database.properties"
export DATASAIL_TEST_TRUSTSTORE="$fixture/trust.p12"
./gradlew :core:integrationTest :core:untrustedTlsTest --console=plain
