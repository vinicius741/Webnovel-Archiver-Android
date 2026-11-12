#!/usr/bin/env bash
# Starts the webnovel_api36 QA emulator headless and waits until Android has booted.
#
# Uses the host GPU (-gpu host). On the Linux dev host (Bazzite / Fedora 44, emulator 36.6.11),
# `-gpu auto` (lavapipe/SwANGLE) and `-gpu swiftshader_indirect` both segfault inside the bundled
# libGLESv2 during boot, so do not boot the AVD with them. Tools that boot the AVD themselves
# with `-gpu auto` (e.g. a device panel) attach to an emulator this script already started.
#
# The emulator runs detached from the calling shell (a systemd user unit when available), so it
# survives agent sessions. Re-running while it is up is a no-op. Stop it with:
#   adb -s emulator-5554 emu kill
#
# Usage: scripts/emulator/start_emulator.sh [--window]
#   --window  show the emulator window instead of running headless
set -euo pipefail

AVD="${AVD:-webnovel_api36}"
PORT="${EMULATOR_PORT:-5554}"
SERIAL="emulator-${PORT}"
# The SDK: the environment's, else the one Gradle builds with on this machine (android/local.properties,
# e.g. Homebrew's on the Mac), else the Linux dev host's ~/Android.
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GRADLE_SDK="$(sed -n 's/^sdk\.dir=//p' "$REPO/android/local.properties" 2>/dev/null | head -n 1)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${GRADLE_SDK:-$HOME/Android}}}"
EMULATOR="${EMULATOR:-$SDK/emulator/emulator}"
ADB="${ADB:-$(command -v adb || echo "$SDK/platform-tools/adb")}"
LOG="${EMULATOR_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/webnovel-emulator/emulator.log}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-300}"

window_args=(-no-window)
if [ "${1:-}" = "--window" ]; then
  window_args=()
fi

# Matches the emulator launcher and its QEMU child, so the check holds while QEMU starts up.
running() {
  pgrep -f -- "-avd $AVD( |$)" >/dev/null
}

booted() {
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

if booted; then
  echo "✓ $SERIAL is already running"
  exit 0
fi
if running; then
  echo "• $AVD is starting; waiting for boot"
else
  if [ ! -x "$EMULATOR" ]; then
    # On the dev host the SDK lives on a loop-mounted ext4 image; an unmounted image looks like
    # an empty ~/Android. See the emulator-recovery skill.
    echo "✗ Emulator binary not found at $EMULATOR" >&2
    echo "  Is the SDK image mounted? findmnt /var/mnt/android-avd" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$LOG")"
  args=(-avd "$AVD" -port "$PORT" -gpu host -no-audio -no-boot-anim -dns-server 8.8.8.8,1.1.1.1 ${window_args[@]+"${window_args[@]}"})
  if command -v systemd-run >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
    unit="webnovel-emulator-$PORT"
    systemctl --user reset-failed "$unit" >/dev/null 2>&1 || true
    # Pass through only what the emulator reads; the user manager does not inherit this shell.
    env_args=(--setenv=ANDROID_HOME="$SDK" --setenv=ANDROID_SDK_ROOT="$SDK")
    for name in ANDROID_AVD_HOME DISPLAY WAYLAND_DISPLAY XDG_RUNTIME_DIR; do
      if [ -n "${!name:-}" ]; then env_args+=(--setenv="$name=${!name}"); fi
    done
    systemd-run --user --quiet --collect --unit "$unit" "${env_args[@]}" \
      /bin/sh -c 'exec "$0" "$@" >>"'"$LOG"'" 2>&1' "$EMULATOR" "${args[@]}"
    echo "• Started $AVD as user unit $unit (log: $LOG)"
  else
    # macOS has no setsid; nohup alone keeps the emulator running after this shell.
    if command -v setsid >/dev/null; then
      setsid nohup "$EMULATOR" "${args[@]}" >>"$LOG" 2>&1 < /dev/null &
    else
      nohup "$EMULATOR" "${args[@]}" >>"$LOG" 2>&1 < /dev/null &
    fi
    echo "• Started $AVD (pid $!, log: $LOG)"
  fi
fi

for _ in $(seq 1 "$BOOT_TIMEOUT_SECONDS"); do
  if booted; then
    echo "✓ $SERIAL booted"
    exit 0
  fi
  if ! running; then
    echo "✗ The emulator exited before booting. See $LOG" >&2
    exit 1
  fi
  sleep 1
done
echo "✗ $SERIAL did not finish booting within ${BOOT_TIMEOUT_SECONDS}s. See $LOG" >&2
exit 1
