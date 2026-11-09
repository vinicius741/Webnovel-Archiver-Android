# Building and publishing releases

Release signing, local APK builds, and the manual GitHub Actions publishing workflow. For installation and development basics, see the [project README](../../README.md).

## Build a release APK

Configure [release signing](#release-signing-setup) first, then run from the repository root:

```bash
android/gradlew -p android :app:assembleRelease
```

Install the signed release APK from `android/app/build/outputs/apk/release/app-release.apk`. To skip the optional convenience copy, add `-x copyReleaseApkToProjectRoot` to the build command.

Its package ID is `com.vinicius741.webnovelarchiver.nativeapp`, and its launcher name is `Webnovel Archiver Native`.

If Android shows `App not installed`, check the signing certificate and version code. An update must use the original signing key and must not have a lower version code. Do not uninstall to work around this: uninstalling deletes the app's local data. Use the fresh Gradle output above rather than an older copy in `apk-output/`.

Use the release variant for phone installs. `android/gradlew -p android :app:assembleDebug` produces `android/app/build/outputs/apk/debug/app-debug.apk`, which is a development build signed with the Android debug key and labeled `Webnovel Archiver Native Test`. When sideloaded, Play Protect is expected to warn that it has not seen the developer before.

Sideloaded release APKs can still receive a Play Protect "unknown developer" warning until the signing certificate/app gains reputation or the app is distributed through Google Play testing/production channels, but `assembleRelease` is the correct command for a normal signed APK.

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

The manual workflow in [android-release.yml](../../.github/workflows/android-release.yml) builds and publishes a signed APK without needing the Mac online. It runs only on `main`, runs the full quality gate, verifies the APK signature and package/version, and publishes a GitHub Release. It does not run automatically on pushes or pull requests.

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
3. After it succeeds, open [Releases](https://github.com/vinicius741/Webnovel-Archiver-Android/releases) on the phone, download the **WebnovelArchiver-v<version>-<run-id>-<attempt>.apk** asset, and open it. Allow installation from the browser if Android asks. Keep the existing app installed to preserve its library.

For each subsequent release, increase both `versionCode` and `versionName` in `android/app/build.gradle`, commit and push the intended changes, then run the workflow again. Each version gets a `v<versionName>` tag; existing tags are never overwritten. The first run can publish the current version if it has no tag yet. A failed run that already created a release must be checked before retrying.

Each published APK filename includes the app version, GitHub Actions run ID, and run attempt so different runs and retries use different filenames. The workflow summary links directly to that run's APK. The latest release page is available without a GitHub login:

https://github.com/vinicius741/Webnovel-Archiver-Android/releases/latest

After the first install, the release app updates itself: it finds new releases through **Settings → App Updates** (and an automatic startup check) and installs them in place. The workflow adds a hidden `<!-- app-update versionCode=N -->` marker to the release notes for this; do not remove it when editing a release.
