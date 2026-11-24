# Dev launch, dev library report and dev restore

Debug-only cold-start hooks for agent QA (moved from `android/AGENTS.md`). The `dev-launch-screen` skill wraps these commands.

For remote-dependent flows, first start a session with `scripts/simulate.py`. The debug-only
enable marker selects the session before `Application` constructs `AppContainer`, including
service-only startup. Session storage, evidence caches, settings, usage receipts, and recovery
files are isolated. The same dev-launch extras below then address the simulation library.
Unknown requests fail closed, including Coil images; reader WebViews block network loads,
and source verification uses local UI. `scripts/simulate.py stop` explicitly returns to the
normal library without clearing it. Never remove just the configuration or use `pm clear`.
The scenario implementation/assets live in `src/debug/`, shared with `instrumentation` by
source sets. Release has an inert factory and no scenario catalog/control activity. Add new
external transports to the simulation guard when introducing them. Scheduling time belongs
to `AppContainer.time`; persisted billing timestamps remain real wall time.

The debug variant cold-starts directly into a chosen screen via an `am start --es dev_start_screen <token>` intent extra, so you can test a feature in place (e.g. TTS in the reader, the download manager) without navigating there by hand. The feature is gated on `BuildConfig.DEBUG` and is a complete no-op in the release variant — it never affects phone releases.

**This is the required way to reach a screen for agent QA.** When you have built/installed the debug app and need to verify a change that lives on a screen (reader transport, settings, queue, etc.), cold-start onto that screen via the `dev-launch-screen` skill or the commands below — do NOT launch the app and tap through the UI by hand to get there. Tap/swipe only for interactions *after* you've landed on the target screen. Manual navigation wastes a session and is error-prone (you can end up on the wrong chapter, the wrong story, or fumbling the overflow menu). Skill triggering is heuristic; if the skill isn't auto-loaded for a post-build verification step, invoke it explicitly or run the cold-start command here directly.

Tokens: `library`, `updates`, `trending` (the Trending screen), `queue` (download manager), `settings`, `aisettings`, `aicontrols` (first story's AI Controls), `cleanup`, `patreon` (Patreon Account sign-in guide), `appupdate` (App Updates), `reader`, `details`, `trends` (a story's Trends screen), `addstory`, `local` (Local folder).

- No-arg screens (`library`, `updates`, `trending`, `queue`, `settings`, `aisettings`, `cleanup`, `patreon`, `appupdate`, `addstory`, `local`) need nothing else.
- `reader`, `details`, `trends`, and `aicontrols` auto-pick the first story in the persisted library (and the first chapter for `reader`); supply `--es dev_start_story <id>` and (reader only) `--es dev_start_chapter <id>` to target a specific one. If the library is empty or the ids don't resolve, the app falls back to the normal library start rather than rendering a blank screen.
- To discover ids or open a specific novel/chapter without hand-editing the `am start` line, use `scripts/dev_library.sh`: `list`/filter (`--status`, `--source`, `--tag`, `--jq '<expr>'`, …) the installed novels; `open <row|id|title> [--chapter <row|id|title>]` cold-starts onto that novel's details or that chapter's reader (add `--rebuild` to build+install+open in one shot, `--dry-run` to preview the resolved ids). It is emulator-only and fails closed on a phone serial.
- The dev target takes precedence over browser-import and TTS-resume, so it reliably lands where asked.

Shorthand — `scripts/redeploy.sh <token>` rebuilds and relaunches straight into the screen:

```bash
scripts/redeploy.sh reader   # rebuild + cold-start in the reader
scripts/redeploy.sh queue    # rebuild + cold-start in the download manager
```

Cold-start without rebuild (app already installed):

```bash
adb -s <emulator-serial> shell am force-stop com.vinicius741.webnovelarchiver.nativeapp.debug
adb -s <emulator-serial> shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.app.MainActivity --es dev_start_screen reader
```

For the full launch command set (reader with explicit story/chapter, library-id discovery), use the `dev-launch-screen` skill. Planning logic lives in `app/DevLaunchPlanning.kt` with unit tests under `app/src/test/.../app/`.

**Dev library report (restore/import verification).** Adding `--es dev_library_report 1` to a cold start makes the debug app write what it actually hydrated from storage to `cache/dev_library_report.json` (readable via `run-as <pkg> cat`): library size, a sha256 over the loaded story-id list, per-tab counts, downloaded-chapter total, and any documents the storage layer quarantined as corrupt. This is the deterministic way to verify a backup restore/library seed (used by the `restore-library-backup` skill) — a matching `storyIdsSha256` proves the app loaded exactly the intended stories in order, and a non-empty `storageIssues` exposes a malformed seeded file that would otherwise vanish silently. Planning lives in `app/DevLibraryReportPlanning.kt`; same `BuildConfig.DEBUG` gating, no-op in release. Poll for the file rather than fixed-sleeping — hydrating a large library can take several seconds.

**Dev full-backup restore.** Adding `--es dev_restore_full_backup <path-relative-to-cacheDir>` to a cold start restores a `webnovel_full_backup_*.zip` staged inside the app's own cache (adb push + `run-as cp` to `cache/dev_restore_source.zip`) through the production `FullBackupRestorer` pipeline — extraction, manifest/ZIP-index validation, staging, verification, atomic root swap, and repository refresh, identical to the in-app Settings picker but without the SAF file picker. The path is resolved by `app/DevRestorePlanning.kt`, which rejects absolute and `..` paths; the staged zip is deleted after the attempt so relaunching never re-restores, and the dev library report is written automatically afterwards. Debug-only, no-op in release. Expect minutes, not seconds: a 113 MB / 10,808-chapter ZIP measured ~7-8 minutes on the emulator. Expectations for verification come from `scripts/full_backup_expectations.py <zip>`.

## Emulator hardware profile and fold QA

(Moved from `android/AGENTS.md`.)

- Default hardware is a **Galaxy Z Fold7 approximation** on the existing `webnovel_api36` AVD: inner 1968×2184, cover 1080×2520, logical density 420 dpi. It uses the supported `pixel_9_pro_fold` base and Google's API 36 image, not Samsung One UI. Density/font scaling are approximations, not measurements from the owner's phone.
- Apply the checked-in profile to a **stopped** AVD with `python3 scripts/emulator/configure_fold7.py`. It backs up the original config and preserves all data images. Never use `-wipe-data` when changing profiles. Cold boot after hardware changes.
- After converting an existing phone AVD, run `python3 scripts/emulator/initialize_fold7.py` once it has booted. It installs the SDK's fold/display XMLs into the emulator's system configuration and reboots if needed, preserving the app/library. Hardware config alone leaves Android in `DEFAULT` state and stretches the inner framebuffer into the cover window. Verify `dumpsys device_state` reports `CLOSED`/`OPENED`, and `wm size` changes to the corresponding dimensions.
- For UI QA, check both `adb -s "$EMULATOR_SERIAL" emu fold` (cover) and `adb -s "$EMULATOR_SERIAL" emu unfold` (inner), plus transitions while a screen is open. Leave the emulator unfolded on Library when finished. Screenshots must follow settled UI, not a fixed sleep alone.
- Foldables expose multiple physical displays. For `screencap`, use an explicit active physical display ID from `dumpsys SurfaceFlinger --display-id`, cross-checked against `dumpsys display`. Pass `screencap -d <id> -p`; an unqualified display capture can select the inactive screen, and stderr warnings can corrupt a redirected `exec-out` PNG.
- Reuse a running `webnovel_api36` emulator; do not start another unnecessarily.
- Start `webnovel_api36` with `scripts/emulator/start_emulator.sh` (detached: a systemd user unit on Linux, `nohup` on the Mac; headless, waits for boot; `--window` shows it). It works on both dev machines: the SDK comes from `ANDROID_HOME`/`ANDROID_SDK_ROOT`, else `sdk.dir` in `android/local.properties` (Homebrew's on the Mac), else `~/Android`. It passes `-dns-server 8.8.8.8,1.1.1.1` so the emulator does not inherit stale host DNS, and `-gpu host`: on the Linux dev host, `-gpu auto` and `-gpu swiftshader_indirect` segfault in the bundled `libGLESv2` during boot. A device panel that boots the AVD itself uses `-gpu auto` and crashes, so start the emulator with the script first; the panel attaches to the running `emulator-5554`. Stop it with `adb -s emulator-5554 emu kill`. If the emulator will not boot or crashes, use the `emulator-recovery` skill.
- Use `scripts/redeploy.sh` for a one-shot debug rebuild/install/relaunch and `scripts/watch-redeploy.sh` for iterative UI work.
- Both scripts must target an `emulator-` serial explicitly and fail closed rather than selecting a physical device.
