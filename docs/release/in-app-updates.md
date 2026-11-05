# In-app updates

The release app checks GitHub Releases for a newer signed APK and installs it in place, so the
phone never needs a browser download after the first install.

## User flow

- **Settings → App Updates** (`AppRoute.AppUpdate`, `feature/settings/SettingsAppUpdate.kt`) shows
  the installed version, the last check, and one action for the current state: *Check for
  updates*, *Download and install*, a progress bar with *Cancel download*, *Install update*, or
  *Continue install*. A **Check automatically** toggle (on by default) controls startup checks.
- With automatic checks on, the app checks when it opens or resumes, at most every 12 hours
  (`AppUpdatePlanning.AUTO_CHECK_INTERVAL_MILLIS`). A found release is offered once in an
  *Update available* dialog with **Update**, **Later**, and **Skip this version**. A skipped
  release is not offered again at startup but stays installable from the screen.
- The Settings row shows `App Updates •` when an update is waiting.
- The first time, Android asks the user to allow Webnovel Archiver to *install unknown apps*.
  The app explains this, opens that setting, and continues into the installer when the user
  comes back. Android then shows its own update confirmation. Once Android records this app as
  the installer, later updates may skip that confirmation (`USER_ACTION_NOT_REQUIRED`).
- Android replaces the running process when the update lands. Library data is untouched: the
  update installs over the same application id and signing key.

## How it works

| Piece | Responsibility |
|-------|----------------|
| `appupdate/GitHubReleaseClient` | Plain OkHttp client (not the Cloudflare `NetworkClient`): `GET /repos/{owner}/{repo}/releases/latest`, the checksum, and the APK download through a `.part` file with streaming SHA-256. |
| `appupdate/GitHubReleaseParsing` | Gson tree parsing of release JSON (safe under R8), and the trimmed copy cached in preferences. |
| `appupdate/AppUpdatePlanning` | Pure rules: version ordering, APK/checksum selection, auto-check timing, prompt-once, and archive verification. Tested in `AppUpdatePlanningTest`. |
| `appupdate/AppUpdateCoordinator` | Process-wide `StateFlow` state machine in `AppContainer`, running on the application scope so downloads survive navigation. |
| `appupdate/AppUpdateInstaller` | Verifies the APK, then commits a `PackageInstaller` session. `AppUpdateInstallReceiver` receives the session status and opens Android's confirmation. |
| `appupdate/AppUpdateState` | UI states and `AppUpdatePreferences` (device-local SharedPreferences `app_update`; not in backups). |

### Which release is newer

The release workflow writes a hidden marker into each release's notes:

```
<!-- app-update versionCode=5 -->
```

When the marker is present, its `versionCode` is compared with the installed one. Releases
published before the marker existed fall back to comparing the tag's dotted version number
(`v1.0.4-native` > `1.0.3-native`). Drafts and prereleases are never offered.

### Integrity

1. The APK must match a SHA-256: GitHub's asset `digest`, or else the release's `SHA256SUMS.txt`.
   A release with neither is refused.
2. Before installing, the APK must have this app's package name, a higher `versionCode`, and the
   same signing certificate as the installed app. Otherwise the file is deleted and the screen
   says why.
3. Android's package installer checks the signature again.

## Build variants and QA

- Only the **release** build installs updates (`BuildConfig.BUILD_TYPE == "release"`). Debug and
  instrumentation builds use other application ids, so a release APK would install beside them.
  They can still run a manual check, never prompt at startup, and disable the download button.
- The offline simulation passes no client, so no update request is made there.
- `BuildConfig.UPDATE_REPOSITORY` (in `android/app/build.gradle`) names the GitHub repository.
- Debug dev-launch token: `appupdate`.
- An end-to-end install test needs a newer signed release on GitHub and the release app on a
  device. It cannot run on the debug emulator build.
