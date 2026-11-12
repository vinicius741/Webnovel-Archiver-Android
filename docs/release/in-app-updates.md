# In-app updates

The release app checks GitHub Releases for a newer signed APK. When one exists, the app opens
the APK in the browser. The browser downloads it, and Android's own package installer installs it
over the existing app.

## Why the app does not install updates itself

Until 1.0.9 the app downloaded the APK and installed it through a `PackageInstaller` session.
That needed `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and later
updates could install without a confirmation. Google Play Protect then flagged the sideloaded
release app as harmful ("This app may be harmful"): a sideloaded app from an unregistered
developer that downloads APKs at runtime and installs them silently matches the behavior Play
Protect looks for.

The app now holds neither permission and has no installer code. Keep it that way: do not re-add
those permissions or a self-update path. Play Protect's verdict is made on Google's servers, so a
removed flag can only be confirmed after a new release is installed and rescanned. If the warning
stays, submit the release APK through Google's Play Protect appeal form.

## User flow

- **Settings → App Updates** (`AppRoute.AppUpdate`, `feature/settings/SettingsAppUpdate.kt`) shows
  the installed version, the last check, and the actions for the current state: *Check for
  updates*, or *Download update* and *View release* when a newer release exists. A **Check
  automatically** toggle (on by default) controls startup checks.
- With automatic checks on, the app checks when it opens or resumes, at most every 12 hours
  (`AppUpdatePlanning.AUTO_CHECK_INTERVAL_MILLIS`). A found release is offered once in an
  *Update available* dialog with **Update**, **Later**, and **Skip this version**. **Update** opens
  the download in the browser. A skipped release is not offered again at startup but stays
  available from the screen.
- The Settings row shows `App Updates •` when an update is waiting.
- The first time, Android asks the user to let the browser *install unknown apps*. Android then
  shows its own update confirmation. Library data is untouched: the update installs over the same
  application id and signing key, and Android refuses an APK signed with a different key.

## How it works

| Piece | Responsibility |
|-------|----------------|
| `appupdate/GitHubReleaseClient` | Plain OkHttp client (not the Cloudflare `NetworkClient`): `releases/latest` for stable, the recent release listing for beta. |
| `appupdate/GitHubReleaseParsing` | Gson tree parsing of release JSON (safe under R8), and the trimmed copy cached in preferences. |
| `appupdate/AppUpdatePlanning` | Pure rules: version ordering, APK selection, auto-check timing, and prompt-once; `UpdateChannel` decides which releases belong to each track. Tested in `AppUpdatePlanningTest`. |
| `appupdate/AppUpdateCoordinator` | Process-wide `StateFlow` (`Idle`, `Checking`, `UpToDate`, `Available`, `Failed`) in `AppContainer`, running checks on the application scope. |
| `appupdate/AppUpdateState` | UI states and `AppUpdatePreferences` (device-local SharedPreferences `app_update`; not in backups). |
| `feature/settings/AppUpdateActions` | Settings row summary, the startup prompt, and `openUpdateDownload`, which opens the APK URL with `ACTION_VIEW`. |

### Update channels

Stable and beta are separate apps (see [Beta track](build-and-publish.md#beta-track)), so each
follows only its own track. `BuildConfig.UPDATE_CHANNEL` (`stable`, or `beta` for the `beta` build
type) selects the `appupdate/UpdateChannel`:

- **Stable** reads `GET /repos/{owner}/{repo}/releases/latest`. GitHub never points *latest* at a
  prerelease, so betas are invisible to the stable app, including stable apps installed before
  channels existed. Stable builds also refuse a release whose marker says `channel=beta`, in case a
  beta loses its prerelease flag; do not clear that flag or mark a beta *latest*.
- **Beta** reads every page of `GET /repos/{owner}/{repo}/releases` (100 per page, at most 5 pages)
  and takes the prerelease marked `channel=beta` with the highest version code
  (`UpdateChannel.newest`). It reads every page because GitHub orders the listing by commit date,
  so a beta built later from an older commit can come after an earlier beta. With no beta release
  the check reports the app as up to date rather than failing. Stable releases and
  unmarked prereleases are ignored, because their APKs would install beside the beta app.

### Which release is newer

The release workflow writes a hidden marker into each release's notes:

```
<!-- app-update versionCode=5 -->
```

Beta releases add the channel: `<!-- app-update channel=beta versionCode=1000007 -->`. The stable
form must stay exactly as it is, because installed stable apps read it.

When the marker is present, its `versionCode` is compared with the installed one. Releases
published before the marker existed fall back to comparing the tag's dotted version number
(`v1.0.4-native` > `1.0.3-native`). Drafts are never offered, and a release from the other channel
is never offered.

### Integrity

The app no longer verifies the download itself. Android's package installer only accepts an
update with the same package name and signing certificate as the installed app. The release keeps
publishing `SHA256SUMS.txt` for manual checks.

## Build variants and QA

- Only the published builds offer the download: **release** and **beta**
  (`AppContainer.PUBLISHED_BUILD_TYPES`). Debug and instrumentation builds use other application
  ids, so a published APK would install beside them. They can still run a manual (stable) check,
  never prompt at startup, and disable the download button.
- The offline simulation passes no client, so no update request is made there.
- `BuildConfig.UPDATE_REPOSITORY` (in `android/app/build.gradle`) names the GitHub repository.
- Debug dev-launch token: `appupdate`.
