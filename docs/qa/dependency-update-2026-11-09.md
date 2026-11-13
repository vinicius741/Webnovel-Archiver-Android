# Stable dependency update, 2026-11-09

All directly declared Android runtime, test, and build dependencies were checked against their
publishers' repositories on November 9, 2026. Preview, alpha, beta, RC, snapshot, and milestone
versions were excluded. Transitive versions remain selected by the upstream dependency graph,
with the complete resolved configurations recorded in `android/app/gradle.lockfile`.

## Updates

| Dependency | Before | After |
|---|---|---|
| Android Gradle plugin | 9.4.1 | 9.5.1 |
| jsoup | 1.23.2 | 1.24.1 |
| Gradle wrapper | 9.8.2 | 9.9.0 |

AGP 9.5 supports `compileSdk` 37 and requires Gradle ≥ 9.6.0, so the wrapper move to 9.9.0 keeps
the same relation to the minimum as before (9.8.2 over the 9.4 minimum). AGP 9.5.1 still bundles
a 2.2.x Kotlin compiler, so the standalone Kotlin 2.4.21 plugin with `android.builtInKotlin=false`
remains required (comment in `android/gradle.properties` updated). jsoup 1.24.1 aligns HTML
parsing more closely with the current HTML standard (tables, formatting elements, templates) and
changes `:root` matching plus `Response.bodyStream()` limits; the app uses neither `:root`
selectors nor `bodyStream()`, and its HTML-standard-alignment changes are exercised by the
jsoup-fixture unit tests (source providers, cleanup, evidence scanning), which all pass.

The wrapper JAR and scripts were regenerated with Gradle's `wrapper` task; the 9.9.0
distribution checksum is pinned in `gradle-wrapper.properties`. The lockfile deltas are the jsoup
pin (1.23.2 → 1.24.1) and AGP's own lint tooling artifacts (9.4.1 → 9.5.1, `androidLintTool`
configuration).

## Pre-existing androidTest breakage fixed

`assembleInstrumentationAndroidTest` does not compile on `main` before this change: commit
9065731 (download-loop tests) retyped `DownloadProcessLoop.storage` from `AppStorage` to
`DownloadQueueStore` and updated the JVM unit tests but missed
`OfflineBrowserDeviceTest.downloadRetryCrossingItsDeadlineDuringProgressCannotStrandTheQueue`,
which still passed the raw `AppStorage`. The `:app:ci` gate does not build the androidTest APK,
so the break went unnoticed. Fixed by passing `storage.downloadQueueStore()` there.

Two `AiSimplificationDeviceTest` tests were also stale against the October 31 Cover/Description
tab split (`f4ae97d`): they drove the removed "Generation options" expander, "Write your own
prompt" heading, and "Source" radio, so they NPE'd deterministically (verified by running them
on a pre-change build). Rewritten against the current UI with the same intents —
`coverPromptDraftsAndChoicesRetainTheirStoryScope` types into the Cover tab's prompt box, checks
the draft survives leaving/re-entering, taps the strip's "Source" frame, and verifies only the
display preference flips (`showAiCover` false, "Source · In use", `aiCoverPath` untouched);
`progressPatchesTheAttachedTreeAndIgnoresLateMessages` runs description progress on the
Description tab and cover progress on the Cover tab, asserting in-place patching (same root and
binding across 100 ticks), the rebuild on the kind/tab switch, gating while busy, and that late
messages cannot patch the detached tree after leaving.

`AiJobNotificationDeviceTest.ongoingAndResultNotificationsCoexistWithDistinctIntents` asserted
`activeNotifications` immediately after four `notify()` calls; posting is asynchronous in
NotificationManagerService, so the assertion raced (it failed 2 of 9 full-suite runs on the
updated build and passed 5 of 5 on the pre-change one). It now polls up to 5 s for all four ids
to surface, matching the wait pattern the same file already uses for notification removal.

## Dependencies already current

| Dependency | Stable version |
|---|---|
| Kotlin Android plugin | 2.4.21 |
| Kotlinter plugin | 5.7.0 |
| detekt plugin | 1.23.8 |
| AndroidX Core KTX | 1.19.1 |
| AndroidX Activity KTX | 1.13.0 |
| AndroidX Lifecycle Runtime KTX | 2.11.0 |
| AndroidX AppCompat | 1.8.0 |
| AndroidX Browser | 1.10.0 |
| AndroidX Media | 1.8.0 |
| AndroidX RecyclerView | 1.4.0 |
| AndroidX ViewPager2 | 1.1.0 |
| AndroidX Window | 1.5.1 |
| Kotlin coroutines Android and test | 1.11.0 |
| Timber | 5.0.1 |
| OkHttp and MockWebServer | 5.5.1 |
| Gson | 2.15.0 |
| Coil and Coil OkHttp networking | 3.6.3 |
| MPAndroidChart | v4.0.1 (latest tag) |
| JUnit | 4.13.2 |
| JSON-java test library | 20260814 |
| AndroidX Test Core KTX, Runner, Rules | 1.7.0 |
| AndroidX Test Ext JUnit KTX | 1.3.0 |

Version checks used [Google Maven](https://dl.google.com/dl/android/maven2/index.html),
[Maven Central](https://repo.maven.apache.org/maven2/),
[the Kotlinter plugin page](https://plugins.gradle.org/plugin/org.jmailen.kotlinter),
[MPAndroidChart tags](https://github.com/PhilJay/MPAndroidChart/tags), and
[Gradle's current stable release metadata](https://services.gradle.org/versions/current).
Compatibility was checked against the
[AGP 9.5 release notes](https://developer.android.com/build/releases/gradle-plugin) and the
[jsoup 1.24.1 release notes](https://github.com/jhy/jsoup/releases/tag/jsoup-1.24.1).

## Local validation

Commands ran from the repository root with JDK 17 and the repository wrapper.

```sh
android/gradlew -p android wrapper --gradle-version 9.9.0 --distribution-type bin \
  --gradle-distribution-sha256-sum 0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a \
  :app:dependencies --write-locks
android/gradlew -p android :app:ci :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest \
  -x copyDebugApkToProjectRoot
```

The full gate passed under Gradle 9.9.0 + AGP 9.5.1: **1,453 unit tests across 197 classes, zero
failures and zero skipped**. Kotlin formatting, file-size checks, detekt (existing advisory
findings unchanged), Android lint, and debug/instrumentation APK builds passed.

## Emulator coverage

The debug APK was installed on `webnovel_api36` (Fold7 approximation, unfolded) from the raw
Gradle output. All device commands selected `emulator-5554` explicitly.

- **Library hydration fingerprint matched exactly, pre- vs post-update**: same
  `storyIdsSha256`, 234 stories, 17,972 downloaded / 25,465 total chapter entries, all five tab
  counts identical, and an empty `storageIssues` list both before (1.0.7-native old build) and
  after (updated build) installation.
- **Screens verified via dev cold-starts**: Library (tabs + Coil covers), Reader (jsoup-parsed
  chapter content, dark theme, progress), Downloads queue (finished summary), Settings, and
  Trends (MPAndroidChart line chart with axis labels). Screenshots were taken in the session
  Device panel.
- **Crash buffer empty** after all screen passes and test runs.
- App left unfolded on Library.

## Device tests

The AndroidJUnitRunner suite under the isolated `.instrumentation` sandbox runs 37 tests (grown
from 25 in October). Before the fixes above it was unbuildable on `main`; after them the suite
passes **37/37, twice in a row**, with the previously flaky notification test green in five
consecutive runs (its class repeated 3× plus two full-suite passes). The emulator crash buffer
was empty afterwards.

Pre-fix triage (both verified against a rebuilt pre-change `ad9bb03` with only the compile fix):
the two `AiSimplificationDeviceTest` failures reproduced identically on the pre-change build —
stale tests against the October 31 UI split, not a dependency regression; the notification
failure was the racy assertion described above, not a behavior change (the two builds'
instrumentation APKs dump identical permissions and SDK levels).

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/instrumentation/app-instrumentation.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/instrumentation/app-instrumentation-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.vinicius741.webnovelarchiver.nativeapp.instrumentation.test/androidx.test.runner.AndroidJUnitRunner
```

No paid AI generation, live source import/sync, mass downloads, destructive library operations,
phone access, release build, or performance benchmark was run. The user's debug library was
never cleared or written by the instrumentation runs (separate sandbox).

## Rebased onto the v3 UI (2026-11-13)

The update was recovered from a stash and rebased onto `main` after the v3 redesign. In v3, tapping a
cover-strip choice opens the full-screen cover viewer (a dialog window) and the choice is committed
with its "Use this cover" action, and the AI screen no longer shows an "AI Controls" title.
`AiSimplificationDeviceTest` now drives the viewer through `WindowInspector` and checks the
`AppRoute.AiControls` route after recreation. `AiSimplificationDeviceTest` and
`AiJobNotificationDeviceTest` pass on `webnovel_api36` (8/8). The minified beta build was
smoke-tested on the emulator: launch, every destination, a live Royal Road import, chapter
download, EPUB generation, and the reader.
