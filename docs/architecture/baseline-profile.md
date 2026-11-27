# Baseline Profile

The release and beta APKs ship a [Baseline Profile](https://developer.android.com/topic/performance/baselineprofiles/overview):
a list of the classes and methods the app runs at startup and on its main journey, which ART
compiles ahead of time instead of interpreting and JIT-compiling them on every cold start.

## Why the app needs one

The app is sideloaded from GitHub releases, so it never receives Play's cloud profiles. ART installs
it with the `verify` filter: everything runs interpreted/JIT until a background dexopt compiles what
local use has recorded, and that starts over after every update.

Before this profile, `androidx.profileinstaller` was already on the runtime classpath (pulled in by
AndroidX), so release builds already installed the small profiles that AndroidX libraries ship
(1,531 rules after R8). None of the app's own code was in them.

With the app profile:

- AGP merges `app/src/release/generated/baselineProfiles/baseline-prof.txt` with the library
  profiles, R8 rewrites it to the obfuscated names (about 6,700 rules), and the APK carries it as
  `assets/dexopt/baseline.prof` and `baseline.profm`.
- `androidx.profileinstaller` (now a direct dependency) writes it to ART's reference profile a few
  seconds after the first launch of a new version. The next background dexopt compiles those
  methods (`speed-profile`). Until then the app runs as before.
- `startup-prof.txt` (the startup subset) lets R8 put startup classes first in the primary dex
  (`dexLayoutOptimization`), which applies from the very first launch.

## Build setup

| Piece | Where | What it does |
|---|---|---|
| `androidx.baselineprofile` consumer | `android/app/build.gradle` | Merges the checked-in profile into `release` and `beta` (beta reads the release folder). `automaticGenerationDuringBuild = false`: normal builds and the release workflow never need a device. |
| `nonMinifiedRelease`, `benchmarkRelease` | `android/app/build.gradle` | Profiling build types the plugin needs. Declared by hand so they get application id `…nativeapp.profiling` (own, initially empty library), debug signing (no release keystore) and the `x86_64` emulator ABI. The plugin makes them non-debuggable and profileable; `nonMinifiedRelease` skips R8. Never published. |
| `src/profiling/` | `android/app/src/profiling` | Only in the two profiling build types: `FixtureLibraryReceiver`, which seeds 24 Royal Road-shaped novels (80 chapters each, 60 downloaded as real chapter files) through the app's own repository. |
| `:baselineprofile` | `android/baselineprofile` | `com.android.test` module with the generator (`BaselineProfileGenerator`): a cold start onto the Library (also the startup profile), then Library scroll → open a story → Chapters tab → Reader → scroll. |

The debug, instrumentation, release and beta variants are unchanged apart from the profile itself.

## Regenerate the profile

Regenerate after large changes to startup, the Library, the story page or the Reader (renamed or
moved classes simply stop matching; the profile never breaks a build).

1. Start the `webnovel_api36` emulator (API 33+ is required to collect a profile without root).
   It must be the only connected device.
2. From the repo root:

   ```bash
   android/gradlew -p android :app:generateReleaseBaselineProfile
   ```

   This builds `nonMinifiedRelease`, installs it with the generator, seeds the fixture library,
   runs the journeys until the profile is stable, and copies the result to
   `android/app/src/release/generated/baselineProfiles/` (`baseline-prof.txt`, `startup-prof.txt`).
   AGP's connected test uninstalls both APKs at the end. It never touches the debug app.
3. Commit the two files. Review the diff: it should be mostly additions and removals of app,
   AndroidX and Kotlin classes, with no `profiling` or `baselineprofile` entries.

Use the release task: the beta has no profiling variants (they are disabled) and ships the release profile.
One run takes about 4 minutes on the emulator (both journeys repeat until the profile is stable).

## Measure it

`scripts/perf/baseline_profile_startup.sh [runs]` installs the `benchmarkRelease` APK (minified like
the real release, profile included), seeds the fixture library and compares cold starts
(`am start -W`, `TotalTime`) in two states:

- **none**: `cmd package compile --reset` before every run, the state right after a sideload.
- **profile**: the app's own `ProfileInstaller` broadcast, then
  `cmd package compile -f -m speed-profile`, the state after the first background dexopt.

Results on `webnovel_api36` (2026-11-27, version 1.2.12, 24 fixture novels):

| APK | none (median) | profile compiled (median) |
|---|---|---|
| Library profiles only (what releases shipped before) | 362 ms | 387 ms |
| With the app profile | 363 ms | 356 ms |

An interleaved A/B of the two compiled states (alternating APKs every round, 24 starts each) gave
381 ms vs 360 ms medians (means 384 vs 362 ms, about 6 % faster, Welch t = 3.7). The gain on the
emulator is small: `TotalTime` ends at the first frame, the library loads off the main thread, and
the emulator runs on a fast desktop CPU where the JIT warms quickly. A phone's slower cores spend
more time interpreting, so the gain there is expected to be larger, but it has not been measured.
