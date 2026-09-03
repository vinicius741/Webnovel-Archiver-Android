# Webnovel Archiver (Android)

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with **native Kotlin**.

> **Note:** This app was originally developed with React Native / Expo and has since been fully rewritten in native Kotlin. The legacy React Native source has been removed; active development targets the native app under `android/`.

## Key Features

- **Multi-Source Support** — Extensible `SourceProvider` architecture with stable source descriptors for Royal Road, Scribble Hub, SpaceBattles, and FanFiction.net. Providers own URL matching, fetching, parsing, network/browser policy, capabilities, and featured metadata. SpaceBattles imports use main Threadmarks and Reader pages, excluding forum replies and optional threadmark categories. FanFiction.net imports accept any chapter URL and discover the complete work from its chapter selector.
- **In-App Source Browser** — Source picker plus Chrome Custom Tabs for browsing supported sites and importing novels. Cloudflare challenges use a persistent shared-session WebView, with sticky Chromium transport when the native HTTP fingerprint is rejected.
- **Offline Library** — Download chapters for reading without an internet connection.
- **Built-in Reader** — WebView-based reader with sentence-level TTS highlighting, image support, last-read position tracking, and a floating TTS transport.
- **Text-to-Speech** — Podcast-style playback: a foreground service with MediaSession, notification/media-button controls, audio focus, and stall recovery keeps narration running while you navigate the app or leave it entirely. Every story remembers where you stopped, so Read aloud resumes at the exact sentence (even from a different chapter); a floating mini-player and a full Now Playing screen (cover art, chapter/sentence skip, speed presets) control playback anywhere in the app. Story descriptions can also be read aloud from the detail page (Listen button), using the same voice settings.
- **Updates Tracker** — Novels are followed automatically while your bookmark is within an adjustable threshold (default 5 chapters) of their latest chapter, and batch-synced concurrently for new chapters with nested chapter rows under each novel; a Following Review screen shows each novel's distance from the end and why it is or isn't followed.
- **EPUB Export** — Generate EPUB 2.0 files with volume splitting, configurable chapter ranges, timestamped outputs, and an EPUB Files screen for leftovers.
- **Background Downloads** — Foreground service with a persistent queue, two parallel source lanes, sequential per-source pacing, isolated bulk preflight and Cloudflare circuit breaking, `Retry-After` cooldowns, and automatic recovery.
- **Text Cleanup** — Sentence removal and regex rules with scoped targets (download, TTS, or both), live sample previews, and circuit-breaking for pathological user regex.
- **AI Features** — Per-novel AI-generated synopses and cover art through the user's own OpenRouter key: per-task model pickers, preview-then-apply drafts, one-step or editable staged covers, background generation that survives navigation/app exit, exact per-request cost receipts and local spend totals, live current-key usage/limits, and a show-AI/show-source switch for both descriptions and covers.
- **Library Organization** — Custom tabs with swipe-between-tabs navigation, search, tag filtering, persisted sort controls (including Patreon earnings/members), and archive snapshots.
- **Trends** — Per-novel metric history captured on every sync (score, Patreon members, monthly earnings, plus per-source engagement metrics — Watchers/Replies/Views/Likes on SpaceBattles, Favorites/Follows/Reviews on FanFiction.net, Followers/Readers on Royal Road/ScribbleHub) and graphed over time on a Trends sub-screen reached from the detail page's score row, Patreon card, tappable engagement chips, and overflow menu, with current-value/delta/range summaries and same-day coalescing + bounded retention.
- **Publication Status** — Colored status badges derived from source metadata and chapter publish dates (including outdated/hiatus lifecycle).
- **Smart Updates** — New-chapter detection with intelligent merge, stale-EPUB marking, and source-availability tracking that preserves local chapters when a fiction disappears upstream.
- **Backup & Restore** — JSON metadata export/import with atomic merge-on-import and rollback, plus full ZIP backup/restore.
- **Notifications Settings** — Dedicated screen for Android 13+ notification permission and channel guidance (downloads vs TTS media controls).
- **Pluggable Theme System** — Obsidian, Midnight, Forest, and Classic Light themes with custom typography.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.1.20 |
| UI | Programmatic Android Views (no XML layouts) |
| Build | Gradle 9.0 + AGP 8.13.2 |
| HTTP Client | OkHttp 4.12 + shared source coordinator + sticky Chromium transport for challenged hosts |
| HTML Parsing | Jsoup 1.21 |
| JSON | Gson 2.13 |
| Async | Kotlin Coroutines 1.10 |
| Images | Coil 2.7 |
| Charts | MPAndroidChart 3.1 (trend graphs; JitPack) |
| Logging | Timber 5 (debug-gated in release) |
| Storage | File-based JSON via `AppStorage` / `AppRepository` (no Room/SQLite) |
| TTS | Android TextToSpeech API + MediaSession |
| Services | Foreground Services (dataSync, mediaPlayback) |
| Browser | AndroidX Browser Custom Tabs |
| Testing | JUnit 4, MockWebServer, coroutines-test; instrumentation build type for device tests |
| Quality | kotlinter, detekt, Android lint, Kotlin file-size budget, R8 minify/shrink on release |

## Getting Started

### Prerequisites

- Android Studio (latest)
- JDK 17
- Android SDK with compileSdk 36

### Build an APK to Install on a Phone

```bash
cd android
./gradlew :app:assembleRelease
```

Install the signed release APK from `apk-output/WebnovelArchiver-Native-release.apk`.

Its package ID is `com.vinicius741.webnovelarchiver.nativeapp`, and its launcher name is `Webnovel Archiver Native`.

If Android shows only `App not installed`, first remove any previous install of `Webnovel Archiver Native` and try the generated APK again. Android will reject an update when an already-installed app has the same package ID but was signed with a different certificate, or when the installed app has a newer version code. With USB debugging enabled, the equivalent cleanup command is:

```bash
adb uninstall com.vinicius741.webnovelarchiver.nativeapp
```

Make sure the file copied to the phone is the current Gradle output, `apk-output/WebnovelArchiver-Native-release.apk`. The release APK produced by this project is only a few MB; a large `build-*.apk` file is usually an older or unrelated artifact.

Do not use `./gradlew :app:assembleDebug` for the APK you send to your phone. That command creates `apk-output/WebnovelArchiver-Test-debug.apk`, which is a development build signed with the Android debug key and labeled `Webnovel Archiver Native Test`. When sideloaded, Play Protect is expected to warn that it has not seen the developer before.

Sideloaded release APKs can still receive a Play Protect "unknown developer" warning until the signing certificate/app gains reputation or the app is distributed through Google Play testing/production channels, but `assembleRelease` is the correct command for a normal signed APK.

### First-Time Release Signing Setup

Release signing is already configured on this machine through `android/local.properties`. If you move to a new computer or delete the local keystore, recreate the release keystore and keep it outside the repo.

Create the keystore:

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

### Development

Open the `android/` directory in Android Studio. The project uses:
- `compileSdk` 36, `minSdk` 26, `targetSdk` 36
- Kotlin 2.1.20, AGP 8.13.2, Gradle wrapper 9.0

For a side-by-side development build:

```bash
cd android
./gradlew :app:assembleDebug
```

This produces `apk-output/WebnovelArchiver-Test-debug.apk` with package ID `com.vinicius741.webnovelarchiver.nativeapp.debug`.

There is also an `instrumentation` build type (`…nativeapp.instrumentation`) used for connected tests so they do not share the debug app’s library sandbox.

For emulator redeploy scripts and the debug-only “cold-start into a screen” helper, see `android/AGENTS.md`.

### Testing

```bash
cd android
./gradlew :app:testDebugUnitTest              # All unit tests
./gradlew :app:testDebugUnitTest --tests "*.TextCleanupTest"  # Single test class
./gradlew :app:lintKotlin                     # Kotlin formatting check
./gradlew :app:detekt                         # Static analysis
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:lintKotlin :app:ci             # Full local quality gate
```

See `AGENTS.md` (repo-wide rules) and `android/AGENTS.md` (architecture, coding standards, emulator workflow) before contributing.

## Project Structure

```
android/
  app/
    src/
      main/
        java/com/vinicius741/webnovelarchiver/
          app/                 # Application, MainActivity, AppContainer, startup recovery
          navigation/          # AppRoute, AppNavigator, ScreenHost
          feature/             # UI by flow: library, details, reader, player (TTS mini-player
                               # + Now Playing), browser, downloads, updates, settings, cleanup,
                               # ai, story actions
          domain/              # Models + pure domain rules (story, archive)
          data/
            repository/        # AppRepository — single owner of library/queue/settings
            storage/           # AppStorage + atomic writes, recovery, backup orchestration
            backup/            # Backup/restore planning and validation
            diagnostics/       # Local diagnostics export + shareable Cloudflare bypass event log
          source/              # Provider descriptors, registry, parsers, and source-owned loading
            network/           # OkHttp client, Cloudflare cookie jar + Chromium fallback
          sync/                # Story sync engine + merge planning
          download/            # Download engine, queue, foreground service
          epub/                # EPUB generation
          cleanup/             # Text cleanup for download + TTS
          tts/                 # TTS engine + foreground service
          ai/                  # OpenRouter client + AI description/cover/chapter-rewrite engines + background jobs
          notification/        # Notification channels + permission helpers
          ui/                  # Programmatic View DSL, themes, widgets
        AndroidManifest.xml
      test/                    # Unit tests mirroring production packages
      androidTest/             # Instrumentation tests
    build.gradle
  build.gradle
  gradle/quality.gradle        # detekt, file-size budget, :app:ci gate
  settings.gradle
docs/                          # Long-form docs by subject (see docs/README.md)
scripts/                       # redeploy.sh, watch-redeploy.sh (emulator only)
.agents/skills/                # Agent skills (build/install, dev launch, emulator QA)
```

## Contributing

Contributions are welcome! Please read `AGENTS.md` and `android/AGENTS.md` before submitting a PR. All changes should target the native Kotlin app under `android/`.
