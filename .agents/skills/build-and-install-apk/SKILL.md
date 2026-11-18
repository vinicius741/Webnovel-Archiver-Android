# Skill: build-and-install-apk

---
name: build-and-install-apk
description: Builds the native Android debug and release APKs for the Webnovel-Archiver-Android project and optionally installs the debug APK onto the webnovel_api36 emulator. Use whenever the user asks to build, compile, assemble, package, or make the APK (debug or release), sign the release build, install/push/replace/deploy the app onto the emulator, or rebuild and reinstall. Trigger on phrases like "build the APK", "build release APK", "rebuild and replace the app", "deploy to the emulator". The owner's phone updates from GitHub releases, not adb. Do NOT trigger for legacy React Native (app/src/modules) work unless the user explicitly asks.
---

## What this skill does

Produce fresh `app-debug.apk` and `app-release.apk` for the native Kotlin app under `android/`, and optionally push one onto a device. The build is a normal Gradle invocation, but this repo has three traps that cost a full session to debug the first time — the core value of this skill is not re-discovering those traps.

## The build

Build only the requested variant. A local rebuild does not require a version bump or a GitHub release. For GitHub publication or a failed publication retry, use the release preflight below before building or dispatching.

From the repo root:

```bash
cd android
./gradlew assembleDebug assembleRelease -x copyDebugApkToProjectRoot -x copyReleaseApkToProjectRoot
```

The two `-x` flags are load-bearing — see "Trap 1" below. If you omit them, the build will still produce the APKs in `build/outputs/` but then fail at the post-build copy task, which looks like a build failure.

Output APKs (these are the source of truth — always install from here, not from `apk-output/`):

- Debug:   `android/app/build/outputs/apk/debug/app-debug.apk`
- Release: `android/app/build/outputs/apk/release/app-release.apk`
- Beta:    `android/app/build/outputs/apk/beta/app-beta.apk` (`./gradlew assembleBeta`; only when the user asks for the beta)

The beta (`com.vinicius741.webnovelarchiver.nativeapp.beta`, "Webnovel Archiver Beta") is a separate app with its own library. Never install it in place of the stable release. Beta publishing uses `.github/workflows/android-beta.yml` from any branch that contains it (GitHub only lists it once it is on `main`); it numbers betas by run number, so it needs no version bump and skips the version steps of the preflight below (still check recent runs and that the branch is pushed). See `docs/release/build-and-publish.md#beta-track`.

Release is signed automatically — signing config lives in `android/local.properties` under the `WEBNOVEL_RELEASE_*` keys (store file, store password, key alias, key password). Never print, cat, or expose those values; `build.gradle` reads them via `findProperty`/`System.getenv`/`localProperties` and you never need to handle them directly.

Build alone takes ~15–30s incremental, longer from clean.

## GitHub release preflight

The manual workflow is `.github/workflows/android-release.yml`; signing setup is in the root `README.md`. Publishing is separate from a local build or device installation. Do not commit, push, or dispatch a workflow without user authorization for that action.

1. Read `versionName` and `versionCode` from `android/app/build.gradle`. Do not rely on version examples in this skill or an earlier conversation.
2. Inspect remote state before choosing a version or retrying. From the repo root:

   ```bash
   gh run list --workflow android-release.yml --limit 10
   gh release list --limit 10
   git ls-remote --tags origin
   ```

   Inspect the relevant failed run with `gh run view <run-id> --log-failed`. Check an in-progress run with `gh run view <run-id>` rather than launching a duplicate. If remote inspection fails because of authentication or connectivity, report the failure rather than treating the release/tag as absent.
3. For a new published release, choose an unused `versionName` and increase `versionCode` above previous published releases. Each tag is exactly `v<versionName>`. Check the proposed tag explicitly with `git ls-remote --tags origin "refs/tags/$RELEASE_TAG"`, setting `RELEASE_TAG` from the chosen version. Any matching tag blocks publication, even if no release appears in `gh release list`. Do not delete or move an existing tag, overwrite published assets, or generate a replacement signing key to bypass this check.
4. Before an authorized dispatch, verify remote `main` contains the intended changes and version bump. Use a fresh workflow dispatch on `main` for a new commit. GitHub's re-run action uses the old run's commit, so re-running a failed old version will not pick up a later bump. If the same version already has a release, inspect its assets and the previous run before deciding whether anything remains to do. Do not blindly retry a run that may already have published.
5. After an authorized dispatch, report the run ID and status. Claim publication succeeded only after the workflow succeeds and the expected release has `WebnovelArchiver.apk` and `SHA256SUMS.txt`. If it is still running, say so; passing preflight is not proof that the APK was published.

Known failure: on November 5, 2026, a second attempt to publish `v1.0.2-native` failed in seconds with `Release tag already exists`, because that version had already been released. The correct response for new changes is a fresh version and a dispatch on the updated `main`, not a retry of the old commit or disabling GitHub failure emails.

## Trap 1 — the copy tasks fail; skip them

`build.gradle` registers `copyDebugApkToProjectRoot` and `copyReleaseApkToProjectRoot`, finalized by `assembleDebug`/`assembleRelease`. They copy the APK into `apk-output/` at the repo root. On this shared machine the `apk-output/` directory is owned by a different user than the active developer, so Gradle's copy fails with:

```
Could not set file mode 644 on '.../apk-output/WebnovelArchiver-Test-debug.apk'
```

This fires *after* the APK is already built and valid — the "BUILD FAILED" line is misleading. Two correct responses:

1. **Preferred:** skip the copy tasks with `-x copyDebugApkToProjectRoot -x copyReleaseApkToProjectRoot` (as in the command above). Always do this.
2. The APKs in `build/outputs/` are perfectly usable; `apk-output/` is just a convenience copy. Never block on the copy failing.

## Trap 2 — mixed-ownership build cache blocks release packaging

If you see the failure move *inside* the build (not at the copy task), e.g.:

```
Could not set file mode 755 on '.../app/build/intermediates/java_res/release/processReleaseJavaRes/out/META-INF'
```

then `android/app/build/` and/or `android/build/` contain files owned by another user (check with `find app/build -user <other-user>`). Gradle can't overwrite/chmod them. Fix:

```bash
cd android
rm -rf app/build build
./gradlew assembleDebug assembleRelease -x copyDebugApkToProjectRoot -x copyReleaseApkToProjectRoot
```

These are disposable build intermediates — safe to delete. Confirm staleness by timestamp/owner before nuking, but don't hesitate once confirmed; a clean rebuild is fast.

## Trap 3 — stale release APK in the build dir

Always check the output APK timestamp and owner before trusting it:

```bash
ls -l android/app/build/outputs/apk/release/app-release.apk
```

If the file is older than your latest `assembleRelease` run or owned by another user, it's stale from a previous build — a failed downstream task (like the copy in Trap 1) can leave an old APK in place. A successful `assembleRelease` always rewrites it.

## Installing onto the emulator

The target is the `webnovel_api36` emulator; no phone is connected (the owner's phone updates from GitHub releases through the in-app updater). Installing on the emulator needs no permission. Resolve the serial and pass it to every device-targeting command:

```bash
adb devices -l   # discovery only — the one unqualified adb call allowed
EMULATOR_SERIAL=emulator-5554   # the row starting with emulator- in state "device"
```

If `webnovel_api36` isn't listed or won't start, use the `emulator-recovery` skill rather than substituting another device.

### Replace install (preserve app data)

Application IDs:
- Debug variant:   `com.vinicius741.webnovelarchiver.nativeapp.debug` (has `.debug` suffix) — the one to install on the emulator; the owner's test library lives in it.
- Release variant: `com.vinicius741.webnovelarchiver.nativeapp` — published through GitHub releases; starts empty on the emulator.
- Legacy RN app:   `com.vinicius741.webnovelarchiver` (reference-only; don't install)

```bash
adb -s "$EMULATOR_SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` = reinstall, keep data. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch, do not uninstall without telling the user: uninstalling deletes the debug library.

### Verify the install

```bash
adb -s "$EMULATOR_SERIAL" shell dumpsys package com.vinicius741.webnovelarchiver.nativeapp.debug | grep -E 'versionName|versionCode|lastUpdateTime'
```

Confirm `lastUpdateTime` is the current session's time.

## Quick reference — the whole flow

```bash
cd android
./gradlew assembleDebug -x copyDebugApkToProjectRoot
adb devices -l
adb -s "$EMULATOR_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

### After install — verification / QA

- **One feature / one screen:** use the `dev-launch-screen` skill (`am start --es dev_start_screen <token>`). Force-stop first, then **settle** before screenshotting — the reader especially needs a poll until `Preparing chapter…` is gone (do not capture on a fixed 1–2s sleep).
- **Full walkthrough / product QA:** use the `emulator-qa` skill (build → install → all major screens → interactions → severity report).
