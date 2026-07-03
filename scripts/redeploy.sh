#!/usr/bin/env bash
# Incremental rebuild + reinstall + relaunch of the debug app on the emulator.
# Safe to re-run; skips install/launch on build failure.
#
# Usage: scripts/redeploy.sh [dev_start_screen]
#   The optional arg is a debug-only intent extra (see android/AGENTS.md "Dev launch screen")
#   that cold-starts the app directly into a screen — e.g. `reader`, `queue`, `settings`,
#   `updates`, `details`, `addstory`, `library`. Omit it for the normal library start.
set -euo pipefail

cd "$(dirname "$0")/.."

PKG="com.vinicius741.webnovelarchiver.nativeapp.debug"
ACTIVITY="com.vinicius741.webnovelarchiver.app.MainActivity"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-$(command -v adb || echo adb)}"
APK_TIME="${APK}.mtime"

# Resolve an emulator explicitly. Never let adb fall back to a connected physical device.
emulator_serials=()
while read -r serial state _rest; do
  if [[ "$serial" == emulator-* && "$state" == "device" ]]; then
    emulator_serials+=("$serial")
  fi
done < <("$ADB" devices -l)

if [ -n "${EMULATOR_SERIAL:-}" ]; then
  selected_state=$("$ADB" -s "$EMULATOR_SERIAL" get-state 2>/dev/null || true)
  if [[ "$EMULATOR_SERIAL" != emulator-* ]] || [ "$selected_state" != "device" ]; then
    echo "✗ EMULATOR_SERIAL must name a healthy emulator-* target." >&2
    exit 1
  fi
elif [ "${#emulator_serials[@]}" -eq 1 ]; then
  EMULATOR_SERIAL="${emulator_serials[0]}"
elif [ "${#emulator_serials[@]}" -eq 0 ]; then
  echo "✗ No running emulator found. Start webnovel_api36 and re-run." >&2
  exit 1
else
  echo "✗ Multiple emulators are running. Set EMULATOR_SERIAL to the intended emulator-* serial." >&2
  printf '  %s\n' "${emulator_serials[@]}" >&2
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
  "$ADB" -s "$EMULATOR_SERIAL" install -r -d "$APK" >/dev/null
  echo "$stamp_after" > "$APK_TIME"
fi

# Optional debug-only "dev launch screen" (android/AGENTS.md "Dev launch screen"): cold-start the
# app directly into a target screen via an intent extra. No-arg invocation is unchanged.
DEV_START_ARGS=()
if [ $# -ge 1 ] && [ -n "$1" ]; then
  DEV_START_ARGS+=(--es dev_start_screen "$1")
fi

echo "▸ Launching $PKG on $EMULATOR_SERIAL"
# force-stop first so the app cold-starts with the new code (no stale process)
"$ADB" -s "$EMULATOR_SERIAL" shell am force-stop "$PKG"
"$ADB" -s "$EMULATOR_SERIAL" shell am start -n "$PKG/$ACTIVITY" "${DEV_START_ARGS[@]}" >/dev/null

echo "✓ Deployed at $(date +%H:%M:%S)"
