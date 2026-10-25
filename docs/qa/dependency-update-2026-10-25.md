# Stable dependency update, 2026-10-25

All directly declared Android runtime, test, and build dependencies were checked against their
publishers' repositories on October 25, 2026. Preview, alpha, beta, RC, snapshot, and milestone
versions were excluded. Transitive versions remain selected by the upstream dependency graph,
with the complete resolved configurations recorded in `android/app/gradle.lockfile`.

## Updates

| Dependency | Before | After |
|---|---|---|
| Kotlin Android plugin and app standard library | 2.4.20 | 2.4.21 |
| OkHttp and MockWebServer | 5.5.0 | 5.5.1 |
| Gson | 2.14.0 | 2.15.0 |
| MPAndroidChart | `com.github.PhilJay:MPAndroidChart:v3.1.0` | `com.github.PhilJay.MPAndroidChart:MPChartLib:v4.0.1` |
| Gradle wrapper | 9.8.0 | 9.8.2 |

MPAndroidChart 4 rewrites its API in Kotlin. `TrendChart` now uses Kotlin properties and
`IAxisValueFormatter`, preserving fitted axes, metric formatting, and scroll/zoom behavior.
The `MPChartLib` artifact supplies the app's Android Views charts. Device regression tests
render all four metric kinds at cover and inner widths, inspect axis labels and ranges,
and exercise empty and single-point data.

Live fold QA also found that Trends kept Details' redraw callback. Folding returned to Details.
Trends now installs its own redraw callback and preserves its story and focused metric. A device
test exercises repeated redraws after entering from Details.

The wrapper JAR and Windows script were regenerated using Gradle's `wrapper` task. The
9.8.2 distribution checksum is pinned, and the wrapper JAR matches Gradle's published SHA-256.
Existing device tests were corrected to expect the current "Following Review" title and the
solver's renamed verification dialog. The solver test now waits for its asynchronous WebView
verification before confirming the dialog.

## Dependencies already current

| Dependency | Stable version |
|---|---|
| Android Gradle plugin | 9.4.1 |
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
| jsoup | 1.23.2 |
| Coil and Coil OkHttp networking | 3.6.3 |
| JUnit | 4.13.2 |
| JSON-java test library | 20260814 |
| AndroidX Test Core KTX, Runner, Rules | 1.7.0 |
| AndroidX Test Ext JUnit KTX | 1.3.0 |

Version checks used [Google Maven](https://dl.google.com/dl/android/maven2/index.html),
[Maven Central](https://repo.maven.apache.org/maven2/),
[the Kotlinter plugin page](https://plugins.gradle.org/plugin/org.jmailen.kotlinter),
[MPAndroidChart releases](https://github.com/PhilJay/MPAndroidChart/releases), and
[Gradle's current stable release metadata](https://services.gradle.org/versions/current).
Migration behavior was checked against the
[chart migration guide](https://github.com/PhilJay/MPAndroidChart/blob/v4.0.1/MIGRATION.md),
[Gson release notes](https://github.com/google/gson/releases/tag/gson-parent-2.15.0), and
[Kotlin release history](https://kotlinlang.org/docs/releases.html).

## Local validation

Commands ran from the repository root with JDK 17. Gradle commands below use the repository
wrapper and skip the convenience APK copy when assembling debug.

```sh
android/gradlew -p android wrapper --gradle-version 9.8.2 --distribution-type bin \
  --gradle-distribution-sha256-sum 21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607 \
  :app:dependencies --write-locks
android/gradlew -p android :app:formatKotlin :app:ci \
  :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest \
  -x copyDebugApkToProjectRoot
android/gradlew -p android :app:lintKotlin :app:assembleInstrumentationAndroidTest
```

The full gate passed with 1,180 unit tests across 169 classes, zero failures and zero errors.
Kotlin formatting, file-size checks, detekt, Android lint, and debug/instrumentation APK builds
passed. Android lint reported zero errors and 152 warnings. Existing advisory detekt and
AGP deprecation warnings remain.

The debug APK was installed with `adb -s emulator-5554 install -r` from the raw Gradle output.
The AVD is `webnovel_api36`, API 36, with the repository's Fold7 approximation. All device
commands explicitly selected that emulator.

The app's pre-update and post-update library hydration reports matched on story IDs/order,
all tab counts, total chapter entries, and downloaded chapter entries. Both loaded 241 stories
and 18,420 downloaded chapters, with no quarantined storage documents.

## Emulator coverage

| Area | Checks | Result |
|---|---|---|
| Main routes | Library, Downloads, Settings, Notifications, AI Settings, AI Controls, Updates, Following Review, Text Cleanup, Patreon Account, Add Story, Details, Reader on both displays | Passed |
| Library | Expand/collapse filters, switch Plan/All, enter/exit selection, fold/unfold while open | Passed |
| Trends | Enter through Details overflow, render score and Patreon charts, fold/unfold while open | Passed after fixing redraw callback |
| Reader | Open offline text, next chapter, start/stop TTS transport, fold/unfold while open | Passed |
| Reader settings | Open Voice settings, dismiss the reader panel, Back returns to Reader | Passed |
| Settings | Manage Tabs, Data & Backup, Download Settings | Passed |
| Add Story | Empty Fetch Story leaves the form open | Passed |

Every capture includes a UI hierarchy. Readiness probes wait for the requested title/content;
reader captures also require the reading controls and rendered chapter content. Foldable PNGs
select the active physical display explicitly. Screenshots and logs are local session artifacts
under `/tmp/webnovel-deps-qa-2026-10-25/`.

### Findings

- **Fixed P2:** Folding on Trends returned to Details. The previous screen's redraw callback was
  still installed. Trends now reinstalls itself with the same story ID and metric focus.
- **Existing P2 symptom observed:** During one episode, library hydration startup passes took
  32.4, 47.1, and 50.3 seconds. All requested screens eventually reached content, and later passes
  returned to roughly 1.5 to 2.3 seconds. The [August QA report](native-app-emulator-qa-2026-08-27.html#F1)
  already records similar transient 24 to 84 second cold-start degradation. This session does
  not establish a causal performance change from the dependency update.

No paid AI generation, live source import/sync, mass downloads, destructive library operations,
phone access, release build, or performance benchmark was run. Existing completed queue entries
were inspected without clearing them. Reading and TTS may update their normal resume state.

## Device tests

The complete AndroidJUnitRunner suite passed: **25 tests, zero failures**. It runs under
`com.vinicius741.webnovelarchiver.nativeapp.instrumentation`, which has an isolated data sandbox.
Coverage includes launch/routes, saved-state restoration, offline reader fixtures, JSON/ZIP
restore compatibility, AI controls, cleanup counts, service notifications, manifest boundaries,
source-verification confirmation/insets, WebView disposal, and the new trend rendering/redraw tests.
The emulator crash buffer was empty. A final debug-app hydration report still matched the
pre-update library fingerprint. The app was left unfolded on Library.

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/instrumentation/app-instrumentation.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/instrumentation/app-instrumentation-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.vinicius741.webnovelarchiver.nativeapp.instrumentation.test/androidx.test.runner.AndroidJUnitRunner
```
