# Building and publishing releases

Release signing, local APK builds, and the manual GitHub Actions publishing workflow. For installation and development basics, see the [project README](../../README.md).

## Build a release APK

Configure [release signing](#release-signing-setup) first, then run from the repository root:

```bash
android/gradlew -p android :app:assembleRelease
```

Install the signed release APK from `android/app/build/outputs/apk/release/app-release.apk`. To skip the optional convenience copy, add `-x copyReleaseApkToProjectRoot` to the build command.

Its package ID is `com.vinicius741.webnovelarchiver.nativeapp`, and its launcher name is `Webnovel Archiver Native`. For the separate beta app, see [Beta track](#beta-track).

If Android shows `App not installed`, check the signing certificate and version code. An update must use the original signing key and must not have a lower version code. Do not uninstall to work around this: uninstalling deletes the app's local data. Use the fresh Gradle output above rather than an older copy in `apk-output/`.

Use the release variant for phone installs. `android/gradlew -p android :app:assembleDebug` produces `android/app/build/outputs/apk/debug/app-debug.apk`, which is a development build signed with the Android debug key and labeled `Webnovel Archiver Native Test`. When sideloaded, Play Protect is expected to warn that it has not seen the developer before.

Sideloaded release APKs can still receive a Play Protect "unknown developer" warning until the signing certificate/app gains reputation or the app is distributed through Google Play testing/production channels, but `assembleRelease` is the correct command for a normal signed APK.

## Beta track

The beta app lets large changes reach a phone without touching the stable app. It is a separate app, so the two install side by side:

| | Stable | Beta |
|---|---|---|
| Package ID | `com.vinicius741.webnovelarchiver.nativeapp` | `com.vinicius741.webnovelarchiver.nativeapp.beta` |
| Launcher name | Webnovel Archiver Native | Webnovel Archiver Beta |
| Launcher icon | Navy background | Purple background with an orange β badge (themed icons get a ringed β) |
| Gradle build type | `release` | `beta` (`initWith release`: same minification and signing key) |
| Library and settings | Its own | Its own, empty at first |
| Published as | GitHub release marked *latest* | GitHub *prerelease*, never *latest* |
| Workflow | `android-release.yml`, `main` only | `android-beta.yml`, any branch |
| Updates offered | Stable releases only (never one marked `channel=beta`) | Beta prereleases only |

Installing, updating, or uninstalling one never affects the other's data. To try your real library in the beta, use **Create Full Backup** in the stable app (**Settings → Data & Backup**), then **Restore Full Backup** in the beta. Nothing flows back to the stable app automatically.

### Beta versions

A beta is numbered by the beta workflow's run number `n` (`WEBNOVEL_BETA_NUMBER`; local builds use 0):

- `versionName` is the branch's stable `versionName` plus `-beta.<n>`, for example `1.0.21-native-beta.7`. The tag is `v1.0.21-native-beta.7`.
- `versionCode` is `1000000 + n`, for example `1000007`. It ignores the branch's stable `versionCode`, so a beta from a branch cut before a stable version bump still updates over the previous beta. The run number only resets if the workflow file is renamed, so keep its name.

Publishing a beta needs no version bump: every run gets a new number. Bumping the stable version in `android/app/build.gradle` stays a stable-release step.

### Build a beta locally

```bash
android/gradlew -p android :app:assembleBeta
```

The APK is at `android/app/build/outputs/apk/beta/app-beta.apk`. It uses the same [release signing](#release-signing-setup) as the stable build. Install a beta on a phone only from the workflow or from a build signed with the original key: a beta signed with a different key blocks later beta updates until it is uninstalled, which deletes the beta's library.

### Publish a beta

GitHub lists a manually dispatched workflow only once its file is on the default branch, so `android-beta.yml` must be on `main` before the first beta. After that, any branch that contains the workflow and the `beta` build type can be published; a branch cut before they reached `main` must merge `main` first.

1. Push the branch with the changes to GitHub. It does not have to be `main`.
2. Open **Actions → Publish Android Beta APK → Run workflow**, choose that branch, and run it.
3. On the phone, open the new prerelease on the [Releases](https://github.com/vinicius741/Webnovel-Archiver-Android/releases) page and install the `WebnovelArchiver-v<version>-beta.<n>.apk` asset, for example `WebnovelArchiver-v1.0.22-beta.2.apk`.

The beta workflow uses the same signing secrets as the stable one and verifies the APK's package ID, version, signature, and that it is not debuggable. Like the stable workflow, it only warns about the Quality Gate: that gate runs on pushes to `main`, so a beta from another branch has no gate run unless you dispatch **Quality Gate** on that branch first. Its release notes carry `<!-- app-update channel=beta versionCode=N -->`, which the beta app's update check reads; do not remove it.

## Release signing setup

Release signing is configured on the owner's Mac through `android/local.properties`. Other machines need their own signing setup. To update an existing installation, transfer the original keystore securely and reuse its alias and passwords. Keep a backup outside the repository; generating a replacement key will not let you update an app signed with the original key.

For a new app that has never been distributed, create the keystore:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/.android/webnovel-archiver-release.jks" \
  -alias webnovel-archiver \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Then add these values to `android/local.properties`, or export them before running Gradle:

```bash
WEBNOVEL_RELEASE_STORE_FILE=$HOME/.android/webnovel-archiver-release.jks
WEBNOVEL_RELEASE_STORE_PASSWORD=your-keystore-password
WEBNOVEL_RELEASE_KEY_ALIAS=webnovel-archiver
WEBNOVEL_RELEASE_KEY_PASSWORD=your-key-password
```

## Publish with GitHub Actions

The manual workflow in [android-release.yml](../../.github/workflows/android-release.yml) builds and publishes a signed APK without needing the Mac online. It runs only on `main`, builds the signed APK, verifies its signature and package/version, and publishes a GitHub Release. It does not run automatically on pushes or pull requests.

The release workflow does not run the quality gate, so publishing takes about half as long. The [Quality Gate workflow](../../.github/workflows/quality-gate.yml) runs `:app:lintKotlin :app:releaseCheck` on every push to `main`. When it fails, it opens one `quality-gate` issue, or comments on the open one, with the run link and reports. The next passing run closes the issue. The release workflow logs a warning when the gate for the released commit failed, is still running, or has no run, but it still publishes. `assembleRelease` also skips Android's `lintVital` pass (`lint { checkReleaseBuilds false }`), because `lintDebug` in the gate already covers it.

One-time setup on the Mac that holds the original signing key:

1. In the repository's **Settings → Secrets and variables → Actions**, add these repository secrets using the existing release signing values:

   | Secret | Value |
   |--------|-------|
   | `WEBNOVEL_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the original `.jks` file |
   | `WEBNOVEL_RELEASE_STORE_PASSWORD` | Existing keystore password |
   | `WEBNOVEL_RELEASE_KEY_ALIAS` | Existing signing alias |
   | `WEBNOVEL_RELEASE_KEY_PASSWORD` | Existing key password |

   On macOS, copy the keystore encoding directly to the clipboard with `base64 -i /path/to/original-release.jks | tr -d '\n' | pbcopy`, then paste it into the secret field. Base64 is an encoding, not encryption. Never commit it, the keystore, or passwords to the repository or paste them into chat.

2. Once the workflow is committed and pushed to `main`, open **Actions → Publish Android APK → Run workflow**, select `main`, and run it.
3. After it succeeds, open [Releases](https://github.com/vinicius741/Webnovel-Archiver-Android/releases) on the phone, download the `WebnovelArchiver-v<version>.apk` asset, for example `WebnovelArchiver-v1.0.22.apk`, and open it. Allow installation from the browser if Android asks. Keep the existing app installed to preserve its library.

For each subsequent release, increase both `versionCode` and `versionName` in `android/app/build.gradle`, commit and push the intended changes, then run the workflow again. Each version gets a `v<versionName>` tag; existing tags are never overwritten. The first run can publish the current version if it has no tag yet. A failed run that already created a release must be checked before retrying.

Published APK filenames omit the legacy `-native` suffix and GitHub Actions run IDs and retry numbers. Stable APKs use `WebnovelArchiver-v<version>.apk`; beta APKs use `WebnovelArchiver-v<version>-beta.<n>.apk`. The app's internal version names, version codes, and release tags keep their existing format. The workflow summary links directly to the APK. The latest release page is available without a GitHub login:

https://github.com/vinicius741/Webnovel-Archiver-Android/releases/latest

After the first install, the release app finds new releases through **Settings → App Updates** (and an automatic startup check) and opens the new APK in the browser, so Android's installer installs it over the existing app. The app does not install APKs itself (see [in-app-updates.md](in-app-updates.md)). The workflow adds a hidden `<!-- app-update versionCode=N -->` marker to the release notes for this; do not remove it when editing a release.
