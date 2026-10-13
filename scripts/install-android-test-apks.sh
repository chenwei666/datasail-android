#!/usr/bin/env bash
# Synthetic emulators only. Skip reinstall only after comparing the installed APK bytes.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DATASAIL_ROOT"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Use a disposable emulator.'; exit 2; }
install_if_changed() {
  local package="$1" artifact="$2" installed expected actual
  installed="$(adb shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p')"
  expected="$(sha256sum "$artifact" | cut -d ' ' -f 1)"
  actual=''
  if [[ -n "$installed" && "$installed" != *$'\n'* ]]; then actual="$(adb exec-out cat "$installed" | sha256sum | cut -d ' ' -f 1)"; fi
  if [[ "$actual" != "$expected" ]]; then adb install --no-streaming -r "$artifact"; else echo "Exact $package APK already installed."; fi
}
case "${1:-both}" in
  both) install_if_changed com.example.datasail app/build/outputs/apk/debug/app-debug.apk ;;
  test) ;;
  *) echo "Use no argument or test"; exit 2 ;;
esac
install_if_changed com.example.datasail.test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
