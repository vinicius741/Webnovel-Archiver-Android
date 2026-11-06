# Pre-release review, 2026-11-06

Status: code review and host validation completed. Emulator QA is blocked, so this
is not a release sign-off.

## Baseline and scope

GitHub's latest published release is `v1.0.4-native`, published on 2026-11-05 at
22:59 UTC from `1814717fb69db1e1c284dadc14df40449b40d40d`, with version code 5.
The initial local checkout was `5de155e`. The latest release tag was fetched before
reviewing the five subsequent commits.

The production changes since that release affect TTS sample play/stop controls,
Patreon creator URL extraction and invalid-stat cleanup at startup, and per-novel
text-cleanup results and EPUB freshness. Those changes and their tests received
the focused code review. Additional reads covered updater installation/download
handling, navigation, and removed-chapter/archive preservation. This was not a
line-by-line review of the entire application.

## Findings and fixes

### P2: a non-creator Patreon link can hide the creator's link

Before the fix, `PatreonCreatorPlanning.sourceLink` rejected bare `/user` and
`/profile` links, but accepted the Patreon homepage, individual posts, collections,
and login pages. `findPatreonUrl` could rank one of these above a valid creator
link, saving a URL for which the new stats-fetch guard refuses to fetch anything.
It also accepted a different site's URL containing a Patreon URL in its query.

The fix validates the actual host, HTTP scheme, and creator identity before link
ranking. Numeric `?u=` identities and creator sub-pages retain their existing
behavior. One planning regression test and one Royal Road parser regression test
failed before the fix and passed afterwards. The latter exercises a page with
high-priority invalid links before a valid lower-priority creator link.

### P3: cleanup device assertions still expect the old result messages

`CleanupCountDeviceTest` still searched for messages removed by `5de155e`. Its
success and partial-failure cases would time out against the current UI. The
assertions now check the current summary, successful removal counts, missing-file
count, and unchanged-run message. The second cleanup run also checks that a
previously current EPUB stays current and receives no regeneration suggestion.
Fixture setup now waits for repository startup before reading settings.

The updated instrumentation APK compiles. Execution of these tests remains pending
emulator access; compilation is not a device-test pass.

## Validation

Executed successfully after the production fix:

```bash
android/gradlew -p android :app:testInstrumentationUnitTest \
  --tests '*.PatreonCreatorPlanningTest' --tests '*.SourceProviderTest'
android/gradlew -p android :app:lintKotlin :app:ci -x copyDebugApkToProjectRoot
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 -m unittest discover -s scripts/perf/tests
```

The full gate passed 1,373 JVM tests with no failures, errors, or skips, Kotlin
formatting, detekt, Kotlin file-size checks, Android debug lint, and debug assembly.
The Python checks passed 8 simulation-runner tests and 57 performance-runner tests.
These Python checks do not execute emulator scenarios or measure app performance.

After updating the instrumentation assertions:

```bash
android/gradlew -p android :app:lintKotlin :app:detekt :app:assembleInstrumentationAndroidTest
```

## Device coverage and blocker

The healthy `webnovel_api36` emulator was discovered as `emulator-5554` and the
initial debug APK installed successfully with `adb -s emulator-5554 install -r`.
No physical device was targeted.

The Device panel opened, but the first app launch failed with `DEVICE_IN_USE` for
session `t3-e6e32ac56b81992b89d8dff0`. The advised retry with `--wait 10000` failed
with the same error. Read-only session and thread inspection traced it to the
completed in-app updater conversation. Its last recorded device action was
2026-11-05 at 22:47:52 UTC. No other session was reused or closed.

| Area | Execution coverage |
| --- | --- |
| Library, filters, tabs, selection | Not run; device-control lock |
| Details, chapters, Trends | Not run; device-control lock |
| Reader, TTS, player, read along | Not run; device-control lock |
| Settings, TTS sample, cleanup, notifications | Not run; device-control lock |
| Updates, Follow Updates, Add Story | Not run; device-control lock |
| Queue ordering, recovery, Cloudflare verification | Not run; device-control lock |
| AI controls/settings and offline scenarios | Not run; device-control lock |
| App Updates and Patreon guide | Not run; device-control lock |
| Folded/unfolded layouts and live transitions | Not run; device-control lock |
| Instrumentation tests | APK compiled; tests not executed |

No settled-screen captures or interactive verification were obtained. The normal
library was not replaced, cleared, or switched into simulation for this review.

## Remaining release work

- Release the old device connection from its owning conversation, then complete
  the emulator walkthrough and offline scenario suite, including the updated
  cleanup instrumentation tests in the isolated instrumentation sandbox.
- Check reader/TTS and sample-stop behavior, cleanup with changes/no changes/missing
  files, startup migration, and folded/unfolded transitions before release sign-off.
- Choose an unused release version and increase version code above 5 before
  publishing. The current `1.0.4-native` / code 5 is already published.
- Release signing, release APK assembly, installation upgrades, live source/API
  compatibility, paid AI calls, phone testing, and publication were not performed.

No crash or data-loss blocker was confirmed by the completed review and host
checks. Missing device coverage prevents a conclusion about overall release readiness.
