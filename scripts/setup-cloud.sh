#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
[[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || { echo 'This installer supports Linux x86_64 only.' >&2; exit 1; }
mkdir -p "$DATASAIL_TOOLS_DIR/downloads" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
# adb still requires this directory even with ANDROID_USER_HOME configured.
mkdir -p "$HOME/.android"
download() {
    local url="$1" file="$2" checksum="$3"
    if [[ ! -f "$file" ]] || ! echo "$checksum  $file" | sha256sum --check --status; then
        curl --fail --location --retry 2 "$url" -o "$file.partial"
        echo "$checksum  $file.partial" | sha256sum --check --status
        mv "$file.partial" "$file"
    fi
}
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    download 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz' \
        "$DATASAIL_TOOLS_DIR/downloads/jdk.tar.gz" f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219
    tar -xzf "$DATASAIL_TOOLS_DIR/downloads/jdk.tar.gz" -C "$DATASAIL_TOOLS_DIR"
fi
if [[ -n "${CODEX_PROXY_CERT:-}" && -f "$CODEX_PROXY_CERT" ]]; then
    mkdir -p "$DATASAIL_TOOLS_DIR/trust"
    cp "$JAVA_HOME/lib/security/cacerts" "$DATASAIL_TOOLS_DIR/trust/cacerts"
    "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias datasail-cloud-proxy \
        -file "$CODEX_PROXY_CERT" -keystore "$DATASAIL_TOOLS_DIR/trust/cacerts" -storepass changeit
    source "$(dirname "$0")/env.sh"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
    download 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' \
        "$DATASAIL_TOOLS_DIR/downloads/cmdline.zip" 7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
    staging="$(mktemp -d "$DATASAIL_TOOLS_DIR/sdk-unpack.XXXXXX")"
    unzip -q "$DATASAIL_TOOLS_DIR/downloads/cmdline.zip" -d "$staging"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv "$staging/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
    rmdir "$staging"
fi
# Installing the Android SDK requires accepting its standard development licenses.
# Use process substitution so yes receiving SIGPIPE does not mask sdkmanager's status.
sdkmanager --sdk_root="$ANDROID_HOME" --licenses < <(yes)
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0'
if [[ ! -f "$ANDROID_HOME/platform-tools/source.properties" ]] || ! grep -q '^Pkg.Revision=37.0.1$' "$ANDROID_HOME/platform-tools/source.properties"; then
    download 'https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip' \
        "$DATASAIL_TOOLS_DIR/downloads/platform-tools.zip" d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1
    unzip -qo "$DATASAIL_TOOLS_DIR/downloads/platform-tools.zip" -d "$ANDROID_HOME"
fi
cd "$DATASAIL_ROOT"
java -version
javac -version
./gradlew --version
test -f "$ANDROID_HOME/platforms/android-36/android.jar"
echo 'Toolchain installed. Run: source scripts/env.sh; ./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest'
echo 'Release/R8 is a separate known-blocked gate: ./gradlew :app:assembleRelease'
