#!/usr/bin/env bash
# Incremental rebuild + reinstall + relaunch of the debug app on the emulator.
# Safe to re-run; skips install/launch on build failure.
set -euo pipefail

cd "$(dirname "$0")/.."

PKG="com.vinicius741.webnovelarchiver.nativeapp.debug"
ACTIVITY="com.vinicius741.webnovelarchiver.MainActivity"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-$(command -v adb || echo adb)}"
APK_TIME="${APK}.mtime"

# Require a connected device; if none, the emulator probably isn't running.
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "✗ No device/emulator found. Start one (adb devices) and re-run." >&2
  exit 1
fi

stamp_before=$( [ -f "$APK" ] && stat -f%m "$APK" 2>/dev/null || echo 0 )

# The gradle wrapper lives in android/, not the repo root.
GRADLE="android/gradlew -p android"

echo "▸ Building debug APK (incremental)…"
# assembleDebug is idempotent and fast when nothing changed.
$GRADLE :app:assembleDebug --console=plain >/tmp/webnovel-redeploy.log 2>&1 || {
  echo "✗ Build failed — last 25 lines:" >&2
  tail -25 /tmp/webnovel-redeploy.log >&2
  exit 1
}

stamp_after=$(stat -f%m "$APK" 2>/dev/null || echo 0)

if [ "$stamp_before" != "0" ] && [ "$stamp_before" = "$stamp_after" ] && [ -f "$APK_TIME" ] && [ "$(cat "$APK_TIME")" = "$stamp_after" ]; then
  echo "• APK unchanged since last successful deploy; relaunching only."
else
  echo "▸ Installing APK…"
  "$ADB" install -r -d "$APK" >/dev/null
  echo "$stamp_after" > "$APK_TIME"
fi

echo "▸ Launching $PKG"
# force-stop first so the app cold-starts with the new code (no stale process)
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/$ACTIVITY" >/dev/null

echo "✓ Deployed at $(date +%H:%M:%S)"
