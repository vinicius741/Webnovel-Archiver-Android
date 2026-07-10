# Skill: dev-launch-screen

---
name: dev-launch-screen
description: Cold-starts the Webnovel-Archiver-Android debug app directly onto a specific screen (chapter reader, download manager/queue, settings, updates, story details, add-story, library) on the webnovel_api36 emulator via the debug-only `dev_start_screen` intent extra, skipping all manual UI navigation. USE THIS whenever you need to reach a screen on the emulator to test, verify, QA, screenshot, or inspect a feature after a build/rebuild — i.e. BEFORE you tap through the app by hand to get somewhere. Trigger when the task is to test/exercise/verify/check/see/inspect a screen-scoped feature AND the app must be (re)launched on the emulator, including the verification step after a code change: "test TTS / playback", "verify the reader / the transport bar / the new control", "check the settings screen", "exercise the download manager", "screenshot the queue", "go to the reader after rebuilding", "build, install, and check the reader", "launch into reader", "start in the download manager", "open on settings", "cold start in queue", "QA the reader", "verify on the emulator". Also trigger on the generic post-build emulator-verification step ("verify on device", "check on the emulator", "exercise the flow", "screenshot to confirm") whenever the feature being verified lives on a reachable screen. Do NOT trigger for legacy React Native work, for the release/phone variant (debug-only, no-op in release), for a pure build with no emulator QA needed, or for a normal launch with no specific destination.
---

## What this skill does

Lets you land the debug app on a target screen on cold start so you can test a feature in place instead of tapping through the UI every iteration. The value is speed and reliability: TTS lives only on the reader, the download manager is several taps deep, and reaching them by hand each rebuild wastes a session and is error-prone.

**This is the canonical way to reach any screen on the emulator for agent QA.** When you have just built/reinstalled the debug app and need to verify a change that lives on a specific screen, USE THIS instead of `android_launch_app` + manual taps. Reach for manual UI automation (taps/swipes) only for interactions *after* you've landed on the target screen, not for getting there.

It works by passing an `am start --es dev_start_screen <token>` intent extra that `MainActivity` reads at launch (gated on `BuildConfig.DEBUG`, so it is dead in release). The screen selection logic is pure and unit-tested in `app/src/main/java/com/vinicius741/webnovelarchiver/app/DevLaunchPlanning.kt`.

## Available screens

| Token     | Lands on            | Needs library data? |
|-----------|---------------------|---------------------|
| `library` | Library (home)      | no                  |
| `queue`   | Download manager    | no                  |
| `settings`| Settings            | no                  |
| `updates` | Updates tracker     | no                  |
| `addstory`| Add Story           | no                  |
| `reader`  | Chapter reader      | yes (story+chapter) |
| `details` | Story details       | yes (story)         |

`reader` and `details` auto-pick the first story in the persisted library (and the first chapter for `reader`). If the emulator's library is empty, they fall back to the normal library start rather than rendering a blank screen — so add a story first if you need to test the reader/details.

## Which token for the feature I'm verifying?

A quick lookup so you don't have to reason about where a change surfaces:

| Feature / area you changed                                        | Token      |
|-------------------------------------------------------------------|------------|
| TTS playback, reader transport bar, in-reader controls, highlight | `reader`   |
| Reading/typography (font, theme, cleanup)                         | `reader`   |
| Library list, story cards, filters/sort, progress                 | `library`  |
| Story details (download banner, EPUB actions, per-story)          | `details`  |
| Download manager / queue screen                                   | `queue`    |
| Settings (TTS voice, chunk size, theme, text-cleanup rules)       | `settings` |
| Updates tracker                                                   | `updates`  |
| Add-story flow                                                    | `addstory` |

Not sure which screen a change affects? Open the changed file's `feature/<x>/` directory — the directory name maps to the token.

## Device safety (per AGENTS.md)

Default target is the `webnovel_api36` **emulator**. This feature is debug-only; **never** use it on the owner's physical phone (it is a no-op there anyway because release ignores the extra). Resolve an `emulator-` serial explicitly and pass it with `adb -s`. The only unqualified adb call allowed is `adb devices -l` (discovery).

## Verify after a rebuild (the common case)

You just built/installed the debug APK (`assembleDebug` / `build-and-install-apk` skill) and need to QA a change that lives on a screen. Do NOT launch the app and tap through to the feature — cold-start directly onto it.

1. Resolve the emulator serial (only allowed unqualified adb call):

   ```bash
   adb devices -l          # pick the emulator-* serial
   ```

2. Pick the token for the feature you changed (see the table above). Example: reader transport bar → `reader`.

3. Cold-start onto the screen (force-stop first so `onCreate` reads the extra):

   ```bash
   SERIAL=emulator-5554      # replace with the actual emulator-* serial
   PKG=com.vinicius741.webnovelarchiver.nativeapp.debug
   ACT=com.vinicius741.webnovelarchiver.app.MainActivity
   adb -s "$SERIAL" shell am force-stop "$PKG"
   adb -s "$SERIAL" shell am start -n "$PKG/$ACT" --es dev_start_screen reader
   ```

4. **Settle, then screenshot.** Do not capture on a fixed short sleep alone — that is how agents keep screenshotting spinners and filing false bugs.

   | Token | Typical settle | Ready signal | Still loading if |
   |-------|----------------|--------------|------------------|
   | Most (`library`, `queue`, `settings`, `updates`, `addstory`) | 1.5–2s | Correct app-bar title + primary content | blank / wrong title |
   | `details` | 2–3s | Story title + cover or action buttons | empty body |
   | **`reader`** | **4–5s minimum, poll** | App bar `Chapter …` **and** body text / TTS controls | Title is `Reader` + `Preparing chapter…`, spinner only |

   Reader poll (required before any reader screenshot):

   ```bash
   for i in 1 2 3 4 5 6 7 8; do
     sleep 1
     adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null
     adb -s "$SERIAL" exec-out cat /sdcard/ui.xml > /tmp/reader_probe.xml
     if ! grep -q 'Preparing chapter' /tmp/reader_probe.xml \
        && grep -qE 'Chapter [0-9]|Read aloud|Next chapter' /tmp/reader_probe.xml; then
       break
     fi
   done
   adb -s "$SERIAL" exec-out screencap -p > /tmp/reader.png
   ```

   After every screenshot, **look at the image** (or re-check the dump). If it is still loading, wait and recapture once. Tiny all-black PNGs (~40KB) are almost always a loading frame.

5. Drive further interaction by dump-derived taps only after the settled capture.

If you're rebuilding *and* relaunching in one shot, `scripts/redeploy.sh <token>` does build → install → force-stop → `am start --es dev_start_screen <token>` for you. That script fails closed on a physical device, so it's emulator-safe. You still must **settle** before screenshotting after redeploy.

For a multi-screen product QA pass (not one feature), use the `emulator-qa` skill instead.

## Launch into a screen (cold start, no rebuild)

Use this when the app is already built and installed and you just want to (re)start it on a screen. Always `force-stop` first so `onCreate` runs and reads the extra — if the app is already foreground, a bare `am start` keeps the current screen.

```bash
adb devices -l                       # discover; pick the emulator-* serial
SERIAL=emulator-5554                 # replace with the actual emulator-* serial
PKG=com.vinicius741.webnovelarchiver.nativeapp.debug
ACTIVITY=com.vinicius741.webnovelarchiver.app.MainActivity

adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" --es dev_start_screen reader
# other tokens: queue, settings, updates, details, addstory, library
# then settle (see table above) before screenshot / interaction
```

Switch screens instantly by re-running the force-stop + start lines with a different token — no rebuild needed. Always force-stop first; a bare `am start` while the app is foreground keeps the old screen and ignores the extra.

## Reader with a specific story/chapter

By default `reader` opens the first story's first chapter. Override with the story/chapter ids (both must exist in the persisted library, or the app ignores the dev target and falls back to its normal launch flow):

```bash
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" \
  --es dev_start_screen reader \
  --es dev_start_story <storyId> \
  --es dev_start_chapter <chapterId>
# settle with the reader poll above before screenshotting
```

To discover ids, list story JSON on the emulator (there is **no** `files/library_index.json`):

```bash
adb -s "$SERIAL" shell run-as "$PKG" ls files/webnovel_archiver/stories/
# each file is { appVersion, payload, schemaVersion }; ids/titles live under payload
adb -s "$SERIAL" shell run-as "$PKG" cat files/webnovel_archiver/stories/<id>.json
```

## Rebuild + relaunch straight into a screen

`scripts/redeploy.sh` accepts the token as an optional first argument (it handles build, install, force-stop, and the `--es` extra):

```bash
scripts/redeploy.sh reader   # rebuild + cold-start in the reader
scripts/redeploy.sh queue    # rebuild + cold-start in the download manager
scripts/redeploy.sh          # no arg = normal library start (unchanged behavior)
```

For a reader launch with a specific story/chapter plus a rebuild, build with the `build-and-install-apk` skill, then use the cold-start command above (redeploy.sh only threads the screen token, not story/chapter).

## How it works / safety

- The branch lives at the top of `MainActivity.onCreate`'s launch-decision chain and is wrapped in `if (BuildConfig.DEBUG)`. Release builds compile it out, so phone releases are unaffected.
- The dev target takes precedence over browser-import and TTS-resume, so you reliably land where you asked.
- The library is read lazily — only for `reader`/`details`. The no-arg screens never touch storage.
- Unknown/blank tokens and empty libraries are ignored (fall through to the normal library start), so a typo never leaves you on a blank screen.
- This is a testing convenience only; it does not change any screen's behavior once you're there.
