# Skill: dev-launch-screen

---
name: dev-launch-screen
description: Cold-starts the Webnovel-Archiver-Android debug app directly onto a chosen screen (reader, queue/download-manager, settings, notifications, updates, details, add-story, library) on the webnovel_api36 emulator via the debug-only `dev_start_screen` intent extra — skipping all manual UI navigation. THIS IS THE CANONICAL WAY TO REACH ANY SCREEN ON THE EMULATOR FOR AGENT QA. It also lists and filters the novels installed on the emulator and opens the app directly onto a SPECIFIC novel (its details) or a SPECIFIC chapter inside that novel (the reader), instead of always landing on the first library entry. USE THIS instead of `android_launch_app` or a bare `am start -n …/MainActivity` whenever you are about to (re)launch the debug app on the emulator to verify, check, QA, screenshot, or exercise a feature — i.e. the post-build/post-install launch step is the exact trigger point. If your plan is "install the APK, launch the app, then tap through to <screen>", invoke this skill and pass the screen token instead; do not tap through the UI to reach a screen you could have cold-started onto. Trigger phrases include "build and check the reader", "install and verify settings", "screenshot the queue after rebuilding", "launch into reader", "open on settings", "QA the reader", "verify on the emulator/device", "exercise the flow", any "test <feature> on the emulator" where the feature lives on a reachable screen, AND the novel-specific ones: "list the novels on the emulator", "which novels are installed", "open novel <X>", "open on a specific story/novel", "open chapter <N> of <novel>", "open the reader on <novel> chapter <N>". Do NOT trigger for legacy React Native work, the release/phone variant (debug-only, no-op in release), a pure build with no emulator QA, or a launch with no specific destination.
---

## What this skill does

Lets you land the debug app on a target screen on cold start so you can test a feature in place instead of tapping through the UI every iteration. The value is speed and reliability: TTS lives only on the reader, the download manager is several taps deep, and reaching them by hand each rebuild wastes a session and is error-prone.

It also lets you target a **specific novel or chapter**. By default `reader`/`details` open the first story in the library (and the first chapter for `reader`); the companion script `scripts/dev_library.sh` lists and filters the installed novels and resolves "open novel X chapter Y" to the right ids, then launches straight there.

**This is the canonical way to reach any screen on the emulator for agent QA.** The trigger point is the moment you would otherwise launch the app after building/installing. If your plan is "install the APK, then `am start -n …/MainActivity`, then tap to <screen>," STOP and run this skill with the screen token instead — do not do a generic launch and tap through the UI to reach a screen you could cold-start onto. Reach for manual UI automation (taps/swipes) only for interactions *after* you've landed on the target screen, not for getting there.

It works by passing an `am start --es dev_start_screen <token>` intent extra that `MainActivity` reads at launch (gated on `BuildConfig.DEBUG`, so it is dead in release). The screen selection logic is pure and unit-tested in `app/src/main/java/com/vinicius741/webnovelarchiver/app/DevLaunchPlanning.kt`.

## Discover and open a specific novel (or chapter)

Use `scripts/dev_library.sh` to list/filter the novels installed on the emulator, then open directly onto one (details) or onto a specific chapter (reader). It reads the app's on-disk library over `adb exec-out run-as` — no app change needed — and resolves human-friendly refs (row number, exact id, or title substring) to the ids `MainActivity` already understands.

```bash
# 1. See what's installed (sorted by title; columns: #, id, title, author, status, pub, progress, source)
scripts/dev_library.sh list

# 2. Filter by any field — combine freely; flags may go before or after the positional ref
scripts/dev_library.sh list --status partial                # download status
scripts/dev_library.sh list --pub-status ongoing            # publication status
scripts/dev_library.sh list --source royalroad              # sourceUrl host substring
scripts/dev_library.sh list --tag litrpg                    # has tag (case-insensitive)
scripts/dev_library.sh list --author "Riley C Lyle"
scripts/dev_library.sh list --incomplete                    # downloadedChapters < totalChapters
scripts/dev_library.sh list --archived                      # / --not-archived
scripts/dev_library.sh list --jq '.score == "4.59 / 5"'     # escape hatch: filter on ANY Story field
scripts/dev_library.sh list --ids                           # just the ids (pipe-friendly)
scripts/dev_library.sh list --json                          # raw filtered JSON array
```

Other filters: `--title <substr>`, `--tab <tabId>`. Status values: `idle|downloading|completed|failed|paused|partial`. Pub values: `unknown|ongoing|completed|outdated|hiatus`.

### Story/chapter refs

`chapters` and `open` accept a `<story-ref>`, resolved in this order:
1. **positive integer** → 1-based row in the *current filtered list* (pass the same filter flags so row numbers line up, e.g. `open --source scribblehub 2`),
2. **exact id** (e.g. `rr_158518`),
3. **title substring** (case-insensitive; errors and lists candidates if it matches more than one).

`--chapter <chapter-ref>` uses the same rules but against that story's chapter list.

```bash
# Chapters of a novel (row 5 in the full list); --downloaded shows only downloaded ones
scripts/dev_library.sh chapters 5
scripts/dev_library.sh chapters 5 --downloaded

# Open a novel's DETAILS screen (cold start)
scripts/dev_library.sh open 5                          # row 5
scripts/dev_library.sh open rr_158518                  # exact id
scripts/dev_library.sh open "System Lost"              # title substring (unique)

# Open the READER on a specific chapter of a specific novel
scripts/dev_library.sh open 5 --chapter 1                       # row 5, chapter row 1
scripts/dev_library.sh open rr_158518 --chapter "Berserkers"    # chapter title substring
scripts/dev_library.sh open "Black and Red" --chapter 3

# Preview the resolved ids + am start line without launching
scripts/dev_library.sh open 5 --chapter 1 --dry-run

# Rebuild + install + cold-start straight onto that novel/chapter in one shot
scripts/dev_library.sh open 5 --chapter 1 --rebuild
```

`open` warns if you target a chapter that isn't downloaded (the reader may show a spinner/empty body). If a ref is ambiguous (e.g. an archived copy shares a title with its source), the script lists the candidates and exits — pass the exact id to disambiguate.

`open` is just a wrapper around the same `force-stop` + `am start --es …` discipline documented below, so the **settle-before-screenshot** rules still apply (especially the reader poll).

## Available screens

| Token          | Lands on            | Needs library data? |
|----------------|---------------------|---------------------|
| `library`      | Library (home)      | no                  |
| `queue`        | Download manager    | no                  |
| `settings`     | Settings            | no                  |
| `notifications`| Notifications       | no                  |
| `updates`      | Updates tracker     | no                  |
| `addstory`     | Add Story           | no                  |
| `reader`       | Chapter reader      | yes (story+chapter) |
| `details`      | Story details       | yes (story)         |

`reader` and `details` auto-pick the first story in the persisted library (and the first chapter for `reader`) unless you pass `dev_start_story` / `dev_start_chapter` — which `dev_library.sh open` does for you. If the emulator's library is empty (or the ids don't resolve), they fall back to the normal library start rather than rendering a blank screen — so add a story first if you need to test the reader/details.

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
| Notifications settings                                            | `notifications` |
| Updates tracker                                                   | `updates`  |
| Add-story flow                                                    | `addstory` |

Not sure which screen a change affects? Open the changed file's `feature/<x>/` directory — the directory name maps to the token.

## Device safety (per AGENTS.md)

Default target is the `webnovel_api36` **emulator**. This feature is debug-only; **never** use it on the owner's physical phone (it is a no-op there anyway because release ignores the extra). Resolve an `emulator-` serial explicitly and pass it with `adb -s`. The only unqualified adb call allowed is `adb devices -l` (discovery). `scripts/dev_library.sh` and `scripts/redeploy.sh` both resolve an `emulator-` serial themselves and fail closed if you try to point them at a phone serial — so they're emulator-safe. Set `EMULATOR_SERIAL=<emulator-serial>` to pin one when several are running.

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

   To land on a specific novel/chapter instead of the first one, use `dev_library.sh open …` (see above), or add the override extras by hand:

   ```bash
   adb -s "$SERIAL" shell am start -n "$PKG/$ACT" \
     --es dev_start_screen reader \
     --es dev_start_story <storyId> \
     --es dev_start_chapter <chapterId>
   ```

4. **Settle, then screenshot.** Do not capture on a fixed short sleep alone — that is how agents keep screenshotting spinners and filing false bugs.

   | Token | Typical settle | Ready signal | Still loading if |
   |-------|----------------|--------------|------------------|
   | Most (`library`, `queue`, `settings`, `notifications`, `updates`, `addstory`) | 1.5–2s | Correct app-bar title + primary content | blank / wrong title |
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
# other tokens: queue, settings, notifications, updates, details, addstory, library
# then settle (see table above) before screenshot / interaction
```

For a specific novel/chapter without a rebuild, prefer `dev_library.sh open <ref> [--chapter <cref>]` (it wraps this same force-stop + `am start`).

Switch screens instantly by re-running the force-stop + start lines with a different token — no rebuild needed. Always force-stop first; a bare `am start` while the app is foreground keeps the old screen and ignores the extra.

## Rebuild + relaunch straight into a screen

`scripts/redeploy.sh` accepts the token as an optional first argument (it handles build, install, force-stop, and the `--es` extra):

```bash
scripts/redeploy.sh reader   # rebuild + cold-start in the reader
scripts/redeploy.sh queue    # rebuild + cold-start in the download manager
scripts/redeploy.sh          # no arg = normal library start (unchanged behavior)
```

To rebuild and land on a **specific** novel/chapter in one shot, let `dev_library.sh open … --rebuild` drive `redeploy.sh` (it forwards the resolved ids via `REDEPLOY_STORY_ID` / `REDEPLOY_CHAPTER_ID`):

```bash
scripts/dev_library.sh open 5 --chapter 1 --rebuild     # rebuild + open novel #5, chapter #1
```

## How it works / safety

- The branch lives at the top of `MainActivity.onCreate`'s launch-decision chain and is wrapped in `if (BuildConfig.DEBUG)`. Release builds compile it out, so phone releases are unaffected.
- The dev target takes precedence over browser-import and TTS-resume, so you reliably land where you asked.
- The library is read lazily — only for `reader`/`details`. The no-arg screens never touch storage.
- Unknown/blank tokens and empty libraries are ignored (fall through to the normal library start), so a typo never leaves you on a blank screen.
- `dev_library.sh` is read-only against the app's data (it only `cat`s files via `run-as`); the only state change it makes is the same force-stop + launch every cold start does. It targets the emulator only and fails closed on a phone serial.
- This is a testing convenience only; it does not change any screen's behavior once you're there.
