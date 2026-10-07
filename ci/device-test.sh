#!/usr/bin/env bash
# Runs inside the emulator job:
#   1. the instrumented tests (real Keystore, real file system);
#   2. demo accounts + a screenshot of the home screen;
#   3. an update in place: a newer build installed over the old one must keep every account.
set -euo pipefail
PKG=com.iknalos.twostep
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p shots

instrument() {   # am instrument exits 0 even when a test fails, so read its report
  local out
  out=$(adb shell am instrument -w "$@" "$RUNNER")
  echo "$out"
  echo "$out" | grep -q "OK (1 test)"
}

shot() {
  adb shell am start -W -n "$PKG/.MainActivity"
  sleep 6
  adb exec-out screencap -p > "shots/$1.png"
  adb shell am force-stop "$PKG"
}

echo "== 1. instrumented tests"
gradle connectedDebugAndroidTest --no-daemon

echo "== 2. build 1 with demo accounts"
VERSION_CODE=1 gradle installDebug installDebugAndroidTest --no-daemon
instrument -e seed 1 -e class "$PKG.ScreenshotSeed#seed"
shot home

echo "== 3. update in place to build 99999"
VERSION_CODE=99999 gradle assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
instrument -e verify 1 -e build 99999 -e class "$PKG.ScreenshotSeed#verify"
shot after-update
echo "accounts survived the update"
