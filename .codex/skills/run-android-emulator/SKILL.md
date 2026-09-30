---
name: run-android-emulator
description: Use when building, installing, launching, or QA-checking the native Webnovel Archiver Android app in an Android emulator from this repository. Covers SDK package checks, AVD creation, tmux startup, Gradle install, activity launch, screenshots, UI tree dumps, and common recovery commands.
---

# Run Android Emulator

Use this workflow for simulator-based development and QA of the native Kotlin app in `android/`.

## Known Good Configuration

- AVD name: `webnovel_api36`
- System image: `system-images;android-36;google_apis;arm64-v8a`
- Hardware profile: Galaxy Z Fold7 approximation using `pixel_9_pro_fold` as the supported base; inner 1968×2184, cover 1080×2520, logical density 420 dpi. This runs Google's Android image, not One UI. Scaling is approximate until measured from the owner's device.
- Debug package: `com.vinicius741.webnovelarchiver.nativeapp.debug`
- Main activity: `com.vinicius741.webnovelarchiver.app.MainActivity`
- Emulator tmux session: `webnovel-emulator`
- Emulator DNS override: `8.8.8.8,1.1.1.1`
- Expected SDK root on this machine: `/opt/homebrew/share/android-commandlinetools`

Use `$ANDROID_HOME` when it is set. If emulator tools are missing from `PATH`, call them by full path under `$ANDROID_HOME`.

## Quick Reuse Path

Start by checking whether an emulator already exists and is running. Use unqualified
`adb devices -l` only for discovery; resolve exactly one healthy emulator and pass its serial to
every device-targeting call.

```bash
adb devices -l
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager list avd

emulator_serials=()
while read -r serial state _rest; do
  [[ "$serial" == emulator-* && "$state" == device ]] && emulator_serials+=("$serial")
done < <(adb devices -l)
((${#emulator_serials[@]} == 1)) || { printf 'Expected exactly one running emulator, found %s\n' "${#emulator_serials[@]}" >&2; return 1 2>/dev/null || exit 1; }
EMULATOR_SERIAL="${emulator_serials[0]}"
```

If an `emulator-*` target is already listed as `device`, reuse it. Ignore physical-device serials.
When multiple emulators are running, explicitly select the intended `emulator-*` serial instead of
silently choosing one.

Build and install the native debug app. Do not use Gradle's `installDebug` task because it does not
carry the explicitly selected emulator serial.

```bash
android/gradlew -p android :app:assembleDebug --console=plain
adb -s "$EMULATOR_SERIAL" install -r -d android/app/build/outputs/apk/debug/app-debug.apk
```

Launch the app (prefer debug cold-start tokens from the `dev-launch-screen` skill when targeting a screen):

```bash
PKG=com.vinicius741.webnovelarchiver.nativeapp.debug
ACT=com.vinicius741.webnovelarchiver.app.MainActivity
adb -s "$EMULATOR_SERIAL" shell am force-stop "$PKG"
adb -s "$EMULATOR_SERIAL" shell am start -n "$PKG/$ACT"
# or: am start -n "$PKG/$ACT" --es dev_start_screen reader
```

Verify it is foregrounded:

```bash
adb -s "$EMULATOR_SERIAL" shell dumpsys window | rg -i 'mCurrentFocus|mFocusedApp|webnovel'
adb -s "$EMULATOR_SERIAL" shell uiautomator dump /sdcard/ui.xml
adb -s "$EMULATOR_SERIAL" exec-out cat /sdcard/ui.xml
```

Capture a screenshot when visual confirmation matters — **only after the UI has settled**.
A fixed short sleep is not enough for the reader (`Preparing chapter…` still shows for several seconds). Prefer dump-based readiness (no `Preparing chapter`, app bar shows `Chapter …`) before `screencap`. For a full multi-screen QA pass, use the `emulator-qa` skill.

```bash
# after settle:
adb -s "$EMULATOR_SERIAL" exec-out screencap -p > /tmp/webnovel-emulator.png
```

## First-Time Setup

Check local tool availability:

```bash
which adb || true
which emulator || true
which sdkmanager || true
which avdmanager || true
echo "$ANDROID_HOME"
```

Install the emulator package and API 36 ARM64 image if needed:

```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "emulator" \
  "system-images;android-36;google_apis;arm64-v8a"
```

Create the AVD if `webnovel_api36` is missing:

```bash
printf 'no\n' | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n webnovel_api36 \
  -k "system-images;android-36;google_apis;arm64-v8a" \
  -d pixel_9_pro_fold
python3 scripts/emulator/configure_fold7.py
```

For an existing AVD, stop it before running `configure_fold7.py`. The script preserves data images
and saves `config.ini.before-fold7`; never recreate or wipe the existing AVD. Cold boot after a
hardware change.

After the first boot of a converted phone AVD, run `python3 scripts/emulator/initialize_fold7.py`.
It supplies the SDK's fold/display system configuration without wiping the library and reboots
only when needed. Verify `adb -s "$EMULATOR_SERIAL" shell dumpsys device_state` reports
`CLOSED` or `OPENED`, not `DEFAULT`; `wm size` must change with the selected display.
Use the same AVD/library for both configurations:

```bash
adb -s "$EMULATOR_SERIAL" emu fold     # smaller cover display
adb -s "$EMULATOR_SERIAL" emu unfold   # larger inner display
```

Check both sizes and a live fold/unfold transition for UI changes. Leave it unfolded on Library.
For foldable screenshots, select the active physical display explicitly using
`dumpsys SurfaceFlinger --display-id` and `dumpsys display`, then `screencap -d <id> -p`.
Otherwise a capture can show the inactive screen, and stderr warnings can corrupt an `exec-out` PNG.

## Starting the Emulator

Prefer tmux so the emulator survives the agent command session:

```bash
tmux new-session -d -s webnovel-emulator \
  "$ANDROID_HOME/emulator/emulator @webnovel_api36 -netdelay none -netspeed full -dns-server 8.8.8.8,1.1.1.1"
```

Wait for Android boot completion:

First use `adb devices -l` to discover the newly created `emulator-*` serial and assign it to
`EMULATOR_SERIAL`; do not select a physical-device serial.

```bash
adb -s "$EMULATOR_SERIAL" wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; input keyevent 82; getprop sys.boot_completed'
```

Attach to the emulator session only when interactive logs are needed:

```bash
tmux attach -t webnovel-emulator
```

Do not leave foreground emulator `exec_command` sessions running in the conversation. Use tmux for long-lived runs.

## Activity and Package Checks

Resolve the launch activity instead of guessing if package details may have changed:

```bash
adb -s "$EMULATOR_SERIAL" shell cmd package resolve-activity --brief com.vinicius741.webnovelarchiver.nativeapp.debug
```

List matching installed packages:

```bash
adb -s "$EMULATOR_SERIAL" shell pm list packages | rg webnovel
```

Check whether the app process is running:

```bash
adb -s "$EMULATOR_SERIAL" shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug
```

## Useful QA Commands

Use UI-tree-derived coordinates for taps:

```bash
adb -s "$EMULATOR_SERIAL" exec-out uiautomator dump /dev/tty > /tmp/webnovel-ui.xml
```

Clear and inspect logs:

```bash
adb -s "$EMULATOR_SERIAL" logcat -c
adb -s "$EMULATOR_SERIAL" shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug
adb -s "$EMULATOR_SERIAL" logcat --pid "$(adb -s "$EMULATOR_SERIAL" shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug)"
adb -s "$EMULATOR_SERIAL" logcat -b crash -d
```

Reinstall after code changes:

```bash
android/gradlew -p android :app:assembleDebug --console=plain
adb -s "$EMULATOR_SERIAL" install -r -d android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$EMULATOR_SERIAL" shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.app.MainActivity
```

## Recovery

If `adb devices` is empty after starting tmux, inspect the session:

```bash
tmux capture-pane -pt webnovel-emulator -S -120
tmux ls
```

If the emulator is wedged, stop the tmux session and restart:

```bash
tmux kill-session -t webnovel-emulator
tmux new-session -d -s webnovel-emulator \
  "$ANDROID_HOME/emulator/emulator @webnovel_api36 -netdelay none -netspeed full -dns-server 8.8.8.8,1.1.1.1"
```

If install fails because no device is connected, run the boot wait command again before retrying `:app:installDebug`.
