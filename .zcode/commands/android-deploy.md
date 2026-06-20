---
argument-hint: "[clean]"
description: Build the signed release APK and deploy/re-launch it on the user's physical phone
---

You are the **Android Deployer** for this repo. Build the latest native **release** app and get it running on the user's **physical phone**. Invoke this command only when the user wants to deploy to their device — running it is the explicit per-message consent to operate on the phone.

This command always builds **release** and always targets the **physical phone**. It never deploys to an emulator and never builds debug.

## Hard rules (device safety)

- Target ONLY the owner's physical phone. Resolve a healthy serial that does NOT start with `emulator-` and pass it explicitly as `adb -s "$PHONE_SERIAL" ...`. Never run an unqualified device-targeting `adb` command.
- `adb devices -l` is the ONLY allowed unqualified `adb` invocation — it is discovery only.
- Fail closed: if no physical device is connected, or more than one is connected and none was explicitly selected, STOP and ask. Do not fall back to an emulator.
- Do not expose, print, or log signing credentials or the contents of `android/local.properties`.

## Step 1 — Resolve repo root and confirm signing

- The repo root is the nearest ancestor containing `.git` (the `android/` project lives there).
- Release builds must be signed. Signing is read automatically from `android/local.properties` (`WEBNOVEL_RELEASE_*` keys) — do not print that file. If `android/gradlew -p android :app:assembleRelease` later fails with the release-signing error, STOP and tell the user signing is not configured; do not attempt to fix or expose credentials.

## Step 2 — Resolve the physical device

Run `adb devices -l`.

- A physical phone appears as a row whose serial does NOT start with `emulator-` and whose state is `device`. Select it.
- If exactly one healthy phone is listed, reuse it. Note its serial as `PHONE_SERIAL`.
- If none is listed, STOP and tell the user to connect their phone (and authorize USB debugging on the prompt), then ask them to re-run.
- If more than one non-emulator device is listed, STOP and list them; ask the user which serial to target.

## Step 3 — Build the release APK

From the repo root:

```
android/gradlew -p android :app:assembleRelease
```

- If the user passed `clean`, run `android/gradlew -p android :app:clean` first.
- If the build fails, surface the failing output (last ~30 lines) and STOP — do not attempt a partial install.
- Output APK: `android/app/build/outputs/apk/release/app-release.apk` (the `assembleRelease` task also copies it to `apk-output/WebnovelArchiver-Native-release.apk`).

## Step 4 — Install on the phone

```
adb -s "$PHONE_SERIAL" install -r android/app/build/outputs/apk/release/app-release.apk
```

If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (release vs. previously-installed debug build), tell the user and offer to uninstall the debug package (`com.vinicius741.webnovelarchiver.nativeapp.debug`) first — do not uninstall anything without confirmation.

## Step 5 — Launch and verify

Release app identity (note: NO `.debug` suffix):

- Package: `com.vinicius741.webnovelarchiver.nativeapp`
- Launcher activity: `com.vinicius741.webnovelarchiver.app.MainActivity` (the `.app` segment is required — a plain `MainActivity` will not resolve)

Cold-start it so stale state can't survive:

```
adb -s "$PHONE_SERIAL" shell am force-stop com.vinicius741.webnovelarchiver.nativeapp
adb -s "$PHONE_SERIAL" shell am start -n com.vinicius741.webnovelarchiver.nativeapp/com.vinicius741.webnovelarchiver.app.MainActivity
```

A successful build+install is not proof the app runs. Confirm:

- `adb -s "$PHONE_SERIAL" shell dumpsys activity activities | grep ResumedActivity` shows the app's `MainActivity` as the top resumed activity, OR
- capture `adb -s "$PHONE_SERIAL" exec-out screencap -p` and check the app is on screen.

If the foreground is still the launcher or another app, explicitly `am start` the activity above and re-check.

## Step 6 — Report

Tell the user, concisely:
- The phone serial used.
- That a release build was produced and signed (with the APK path), then installed and launched.
- The verification method used.
- Anything not run, and any errors encountered.

## Optional instructions from the user

$ARGUMENTS

The only recognized argument is `clean` (run a clean build first). Ignore any other text.
