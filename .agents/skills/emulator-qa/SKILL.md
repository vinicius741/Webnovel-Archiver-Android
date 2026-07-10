---
name: emulator-qa
description: >
  Full emulator QA pass for the native Webnovel Archiver Android debug app on
  webnovel_api36: build/install, cold-start every major screen via dev_start_screen,
  wait until each screen is actually settled (not a spinner/loading title), capture
  screenshot + UI hierarchy, exercise key interactions, and report severity-ranked
  findings. USE THIS when the user asks to QA, do a QA pass, test the app on the
  emulator, walk through screens, find bugs, smoke-test, regression-test, or
  "go through the pages and see if anything is wrong". Also trigger after a broad
  rebuild when the user wants visual/manual verification across the product, not
  just one screen. Use when the user runs /emulator-qa. Do NOT use for a pure
  build with no device check, phone/release installs (unless phone use is
  explicitly authorized), or a single-screen feature check (use dev-launch-screen
  instead). Companion skills: build-and-install-apk, dev-launch-screen.
---

# Skill: emulator-qa

End-to-end **emulator** QA for the native Kotlin app. This is the skill that
prevents the common agent failure mode: screenshotting too early (spinner /
"Preparing chapter…") and filing false findings.

## Hard rules (same as AGENTS.md)

- Target **only** an `emulator-*` serial (default AVD `webnovel_api36`). Never
  touch the physical phone unless the current message explicitly authorizes it.
- Debug package only: `com.vinicius741.webnovelarchiver.nativeapp.debug`
- Main activity: `com.vinicius741.webnovelarchiver.app.MainActivity`
  (note the `.app.` segment — wrong path launches nothing useful)
- Unqualified `adb devices -l` is discovery-only. Every device op uses
  `adb -s "$SERIAL" …`.
- Prefer **local** flows. Do **not** run live Royal Road / Scribble Hub import,
  full followed-novel sync, or mass downloads unless the user asked — those
  mutate the emulator library and depend on third-party sites.

## Companion skills (invoke, don't re-invent)

| Need | Skill |
|------|--------|
| Build + install debug APK | `build-and-install-apk` (skip copy tasks; install from `android/app/build/outputs/apk/debug/app-debug.apk`) |
| Land on a specific screen | `dev-launch-screen` (`--es dev_start_screen <token>`) |
| Emulator missing / cold boot | start AVD with `-dns-server 8.8.8.8,1.1.1.1` |

## Constants

```bash
PKG=com.vinicius741.webnovelarchiver.nativeapp.debug
ACT=com.vinicius741.webnovelarchiver.app.MainActivity
# SERIAL=emulator-5554   # resolve from adb devices -l; must start with emulator-
QA_DIR=/tmp/webnovel-qa-$(date +%Y-%m-%d)
mkdir -p "$QA_DIR"
```

## Pipeline

### 1. Emulator ready

```bash
adb devices -l
# if no emulator-*: start webnovel_api36 with DNS, then wait:
#   adb -s "$SERIAL" wait-for-device
#   until boot_completed == 1
```

### 2. Fresh debug build + install

Use `build-and-install-apk` traps:

```bash
cd android && ./gradlew :app:assembleDebug -x copyDebugApkToProjectRoot --console=plain
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

Confirm install time is this session (`dumpsys package … | grep lastUpdateTime`).

### 3. Cold-start screens (do not tap-navigate to arrive)

Tokens: `library` · `queue` · `settings` · `updates` · `addstory` · `details` · `reader`

Always:

```bash
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$PKG/$ACT" --es dev_start_screen <token>
```

Then **settle** (next section) before any screenshot.

### 4. Settle before screenshot — the load-bearing rule

**Never** use a fixed short sleep as "ready". First-pass captures with
`sleep 1.5` routinely catch loading states and waste the whole QA loop.

| Screen | Min wait after launch | Ready when UI / dump shows… | Still loading if… |
|--------|----------------------|-----------------------------|-------------------|
| `library` | ~2s | Title `Library`, novel count or cards | blank body, no title |
| `queue` | ~1.5s | `Downloads` + empty state or queue rows | — |
| `settings` | ~1.5s | `Settings` + `Appearance` / theme chips | — |
| `updates` | ~2s | `Updates` + followed count or empty state | — |
| `addstory` | ~1.5s | `Add Story` + `Fetch Story` | — |
| `details` | ~2–3s | Story title in app bar + cover or action buttons | generic "Details" only, blank |
| **`reader`** | **~4–5s, then poll** | App bar `Chapter …` **and** body text (or WebView text nodes); bottom `N / M` nav | Title `Reader` + `Preparing chapter…`, spinner only, tiny PNG (~40KB) |

**Reader settle loop (required):**

```bash
# after am start … reader
for i in 1 2 3 4 5 6 7 8; do
  sleep 1
  adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb -s "$SERIAL" exec-out cat /sdcard/ui.xml > "$QA_DIR/_reader_probe.xml"
  if ! grep -q 'Preparing chapter' "$QA_DIR/_reader_probe.xml" \
     && grep -qE 'Chapter [0-9]|Read aloud|Next chapter' "$QA_DIR/_reader_probe.xml"; then
    break
  fi
done
# then screenshot — if still Preparing, report "reader never left loading" as a finding
```

After **every** screenshot, **read the image** (or at least re-check the dump).
If it is a spinner / wrong screen / loading title, wait and recapture once before
continuing. Do not QA from a loading frame.

### 5. Capture pair (screenshot + hierarchy)

```bash
capture() {
  local name="$1"
  adb -s "$SERIAL" exec-out screencap -p > "$QA_DIR/${name}.png"
  adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb -s "$SERIAL" exec-out cat /sdcard/ui.xml > "$QA_DIR/${name}.xml"
}
```

- Prefer **dump-derived** tap centers over guessed coordinates.
- PNG size is a cheap signal: pure black spinner frames are often ≪ settled UI.

### 6. Interactive checks (after settle)

Do these on top of static screenshots. Use dump bounds for taps.

| Area | Exercise |
|------|----------|
| Library | Open filters; Select mode; switch All / custom tabs; note archive cards |
| Details | Collapse → chapter list; overflow menu; open a chapter into reader |
| Reader | Next chapter; TTS play → transport visible → Stop; open Reader Settings |
| Reader Settings | Tap **Voice settings** — dialog must dismiss (known P2 if it stays) |
| Settings | Download Settings; Voice & Speech; Manage Tabs; Text Cleanup; scroll for Backup |
| Add Story | Empty URL → Fetch Story stays on form (toast may die fast; form stay = pass) |
| Updates | Choose novels (check archive duplicates) |
| Queue | Empty state only unless user allowed downloads |

After sticky bottom bars (Select Novels Move/Delete), **scroll the list** before
concluding an item is missing.

### 7. Data on device (when needed)

Library is **not** `files/library_index.json`. Story files live at:

```bash
adb -s "$SERIAL" shell run-as "$PKG" ls files/webnovel_archiver/stories/
# each file: schema wrapper { appVersion, payload, schemaVersion }
# payload keys: id, title, author, isArchived, archiveOfStoryId, …
```

Archives: `isArchived: true` — library cards may show an icon; selection lists often
only show title/author (duplicate-looking rows = known UX issue).

### 8. Logs

```bash
adb -s "$SERIAL" logcat -c   # at start of interactive section
# at end:
adb -s "$SERIAL" logcat -d | rg -i 'FATAL EXCEPTION|AndroidRuntime' | rg -i webnovel || true
```

Ignore force-stop noise (`Shutting down VM` during intentional restarts).
`Toast already killed` on empty-URL validation is usually OK if the form stayed put.

### 9. Report format

- Validation actually run (build, install serial, screens visited)
- Table: Area / exercised / Pass · Issue · Not run
- Findings ranked **P1** (crash/data loss) · **P2** (broken primary path) · **P3** (polish/UX)
- Repro + evidence (screenshot path or dump text) for each issue
- Explicitly list what was **not** run (live network, phone, landscape, …)
- Leave the app on `library` when finished

## Previously fixed (regression-check only)

These were fixed after the 2026-07-10 QA pass. Confirm they stay fixed; re-open if regressed.

1. Reader Settings → Voice settings must dismiss the panel; Back from TTS opened via
   reader must return to the reader (not main Settings).
2. Settings must **not** show a dead “EPUB Volume Folding” Cover/Inner control
   (`foldLayoutMode` is legacy storage only).
3. Select Novels labels archives (`· Archived`); Follow Updates excludes archives.
4. Reader HTML has extra bottom padding so the last line clears the chapter nav bar.

## Anti-patterns (from past failed QA)

- `sleep 1.5` then screenshot the reader → "Preparing chapter…" false negative.
- Launching without `force-stop` → old screen stays; `dev_start_screen` ignored.
- Wrong activity (`…webnovelarchiver.MainActivity` without `.app.`) .
- Tap-navigating from library to reach TTS / queue (slow, error-prone) instead of
  cold-start tokens.
- Running `installDebug` without `-s` serial / targeting a phone by accident.
- Treating `apk-output/` as the install source (use `app/build/outputs/apk/…`).
- Declaring "missing novel" on Select Novels without scrolling past sticky actions.
- Filing a full network import as QA without user consent to mutate the library.
