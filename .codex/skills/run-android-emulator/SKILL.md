---
name: run-android-emulator
description: Use when building, installing, launching, or QA-checking the native Webnovel Archiver Android app in an Android emulator from this repository. Covers SDK package checks, AVD creation, tmux startup, Gradle install, activity launch, screenshots, UI tree dumps, and common recovery commands.
---

# Run Android Emulator

Use this workflow for simulator-based development and QA of the native Kotlin app in `android/`.

## Known Good Configuration

- AVD name: `webnovel_api36`
- System image: `system-images;android-36;google_apis;arm64-v8a`
- Hardware profile: `pixel_8`
- Debug package: `com.vinicius741.webnovelarchiver.nativeapp.debug`
- Main activity: `com.vinicius741.webnovelarchiver.MainActivity`
- Emulator tmux session: `webnovel-emulator`
- Expected SDK root on this machine: `/opt/homebrew/share/android-commandlinetools`

Use `$ANDROID_HOME` when it is set. If emulator tools are missing from `PATH`, call them by full path under `$ANDROID_HOME`.

## Quick Reuse Path

Start by checking whether an emulator already exists and is running:

```bash
adb devices -l
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager list avd
```

If `emulator-5554` or another device is already listed as `device`, reuse it.

Build and install the native debug app:

```bash
cd android
./gradlew :app:installDebug --console=plain
```

Launch the app:

```bash
adb shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.MainActivity
```

Verify it is foregrounded:

```bash
adb shell dumpsys window | rg -i 'mCurrentFocus|mFocusedApp|webnovel'
adb exec-out uiautomator dump /dev/tty
```

Capture a screenshot when visual confirmation matters:

```bash
adb exec-out screencap -p > /tmp/webnovel-emulator.png
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
  -d pixel_8
```

## Starting the Emulator

Prefer tmux so the emulator survives the agent command session:

```bash
tmux new-session -d -s webnovel-emulator \
  "$ANDROID_HOME/emulator/emulator @webnovel_api36 -netdelay none -netspeed full"
```

Wait for Android boot completion:

```bash
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; input keyevent 82; getprop sys.boot_completed'
```

Attach to the emulator session only when interactive logs are needed:

```bash
tmux attach -t webnovel-emulator
```

Do not leave foreground emulator `exec_command` sessions running in the conversation. Use tmux for long-lived runs.

## Activity and Package Checks

Resolve the launch activity instead of guessing if package details may have changed:

```bash
adb shell cmd package resolve-activity --brief com.vinicius741.webnovelarchiver.nativeapp.debug
```

List matching installed packages:

```bash
adb shell pm list packages | rg webnovel
```

Check whether the app process is running:

```bash
adb shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug
```

## Useful QA Commands

Use UI-tree-derived coordinates for taps:

```bash
adb exec-out uiautomator dump /dev/tty > /tmp/webnovel-ui.xml
```

Clear and inspect logs:

```bash
adb logcat -c
adb shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug
adb logcat --pid "$(adb shell pidof -s com.vinicius741.webnovelarchiver.nativeapp.debug)"
adb logcat -b crash -d
```

Reinstall after code changes:

```bash
cd android
./gradlew :app:installDebug --console=plain
adb shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.MainActivity
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
  "$ANDROID_HOME/emulator/emulator @webnovel_api36 -netdelay none -netspeed full"
```

If install fails because no device is connected, run the boot wait command again before retrying `:app:installDebug`.
