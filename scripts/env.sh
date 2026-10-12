#!/usr/bin/env bash
# Source this file from any directory. No secrets are written or printed.
DATASAIL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DATASAIL_TOOLS_DIR="${DATASAIL_TOOLS_DIR:-$DATASAIL_ROOT/.toolchains}"
export JAVA_HOME="$DATASAIL_TOOLS_DIR/jdk-21.0.8+9"
export ANDROID_HOME="$DATASAIL_TOOLS_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$DATASAIL_TOOLS_DIR/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$DATASAIL_TOOLS_DIR/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
# Java does not automatically honor HTTPS_PROXY. Preserve platform TLS trust.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    datasail_proxy_flags="$(python3 - <<'PY'
import os, urllib.parse
p = urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
if p.username or p.password:
    raise SystemExit('Authenticated proxies require platform Java configuration; do not embed credentials.')
if not p.hostname or not p.port:
    raise SystemExit('HTTPS_PROXY must have a host and port.')
print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port} -Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttp.nonProxyHosts=localhost|127.*|[::1]')
PY
)" || return 1
    case "${JAVA_TOOL_OPTIONS:-}" in
        *-Dhttps.proxyHost=*) ;;
        *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $datasail_proxy_flags" ;;
    esac
    if [[ -r "$DATASAIL_TOOLS_DIR/trust/cacerts" && "${JAVA_TOOL_OPTIONS:-}" != *-Djavax.net.ssl.trustStore=* ]]; then
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=$DATASAIL_TOOLS_DIR/trust/cacerts"
    fi
    unset datasail_proxy_flags
fi
