# Native app emulator QA — 2026-11-07

Full-screen QA pass of the native Kotlin app (debug variant `1.0.6-native`, versionCode 7)
on the `webnovel_api36` emulator (Galaxy Z Fold7 approximation, serial `emulator-5554`),
driven with the `dev_start_screen` cold-start tokens and the T3 device panel. Screenshots
referenced below live in `assets/qa-2026-11-07/`.

## Validation actually run

- APK: debug variant already installed on the emulator (user's in-progress library: 234
  novels, 5 custom tabs). No rebuild was requested; all checks ran against the installed
  `com.vinicius741.webnovelarchiver.nativeapp.debug`.
- Story under test for details/trends/reader/TTS: **rr_92924 "Vampire and the Dayspring
  Star" by Matoi** (RoyalRoad, 122/122 chapters, score 4.69, Patreon 28 members).
- Fold states: Library and Reader captured **folded** (cover 1080×2520) and **unfolded**
  (inner 1968×2184), with a live fold → unlock → cold-start transition. Library left
  unfolded at the end.
- Crash sweep: full-session `logcat -d` filtered for `FATAL EXCEPTION` / `AndroidRuntime`.
  Every hit is the known `uiautomator dump` ↔ agent-device accessibility conflict
  (`UiAutomationShellWrapper.connect` "already registered!", shell PID, exit 137) — a
  tooling artifact of this QA setup, **not** an app crash. No `Process: com.vinicius…`
  crash entries exist; the app never crashed during the pass.

## Screen coverage

| Area | Exercised | Result |
|------|-----------|--------|
| Library | List + cover views, filters, search, sort, select mode, tab switches, header count, continue-listening banner; folded + unfolded | Count behavior confirmed (F3); banner observation not reproduced (F6) |
| Details | rr_92924: header, stats, Patreon card, chapter index, overflow menu, open chapter into reader | Pass, 1 issue (F2, shared with Trends) |
| Trends | 7d/30d/90d ranges, metric filters, panning, Patreon card with mini-player active | Pass, 2 issues (F1, F2) |
| Reader | Chapter 1 load + settle, scroll, bottom padding vs chapter nav, TTS start/highlight/stop, Reader Settings, Voice settings dialog, chapter-index open, Next chapter; folded | Pass (regressions 1 & 4 hold) |
| Player (Now Playing) | Reached via mini-player → Open player; cover/title/chapter, seek bar, transport, rate 1.8x, timer, stop, seek-back, Read along | Pass |
| Queue (Downloads) | Collapsed + expanded Finished group, per-row state, remove/overflow controls, header totals (5 novels · 16/16) | Pass |
| Download Settings | Defaults fields, per-source delay overrides (checked/unchecked) | Pass |
| Settings | All entries; Large Screen Layout Auto/Cover/Inner; regression 2 check | Pass; grouping observation (F5) |
| Voice & Speech | Pitch/rate/voice/pauses, sample playback + stop, Save TTS | Pass |
| Text Cleanup | Full sentences + Regex tabs, saved sentences, export | Pass |
| Manage Tabs | 5 tabs (149+26+21+36+2 = 234), reorder/rename/delete controls, edge arrows disabled at list ends | Pass |
| Data & Backup | Backup actions, backup files area, Sources & Storage, Danger Zone (view only) | Pass |
| Notifications | Permission state, 3 categories, explanation text | Pass |
| AI Settings | Empty key field, 3 model pickers, chapter scan limit, AI spend section | Pass; F4 retracted |
| App Updates | Installed version card, auto-check toggle, dev-build notice (no live check triggered) | Pass |
| Updates tracker | Empty state, 10 following · 0 new (no live sync) | Pass |
| Following Review | Tab filter, search, "Within 6 chapters" toggle, covers toggle, 234-novel list | Pass (regression 3 vacuous — no archived novels on this device) |
| Add Story | Empty URL → Fetch Story: toast "Enter a URL", form stayed; source browser list | Pass |
| Patreon Account | 5-step setup render only (sign-out / paste deliberately untouched) | Pass |
| Fold transitions | `emu fold`/`emu unfold` on Library and Reader, keyguard dismiss, layout re-flow | Pass |

## Findings

No P1 or P2 findings in the original pass. An independent retest confirmed two P3
issues (F1 and F2), verified two behaviors subject to product judgment (F3 and F5),
retracted F4, and could not reproduce F6.

### Independent retest — 2026-11-07

The retest used the installed debug app on the unfolded `webnovel_api36` emulator,
without rebuilding or performing live network checks. It used the same story as the
original pass, started TTS from Continue listening, opened its Trends screen, and
checked the Library search, view toggle, Settings, and AI Settings. The retest did not
repeat the full screen walkthrough or the folded-display checks. The emulator was left
unfolded on Library with the player closed, and the device session was released.

### F1 (P3) — Trends: last card occluded by the TTS mini-player and the content cannot scroll it clear

With a TTS session loaded (mini-player pinned), the Trends screen's final card
("Patreon monthly earnings …") is clipped mid-line behind the mini-player, and the
chart column cannot be scrolled far enough to reveal it: two flings **and** a slow
~1200 px pan produced zero scroll. The scroll area's bounds end ~52 px above the
mini-player's top edge, so the last ~52 px are permanently hidden while the mini-player
is up.

- Repro: start TTS on any story → cold-start `trends` (or open Trends while audio is
  active) → scroll to the bottom of the Trends column.
- Evidence: `assets/qa-2026-11-07/13-trends-bottom.png`. The original report also
  cited `13c-trends-panned.png`, but that file is absent from the evidence folder.
- Independent retest: confirmed the overlap with TTS active; two upward swipes did not
  move the last card clear of the player.
- Root cause (code): `TtsMiniPlayer.liftBottomViews` (`feature/player/TtsMiniPlayer.kt`)
  lifts only views tagged `FAB_VIEW_TAG` and `BOTTOM_BAR_VIEW_TAG`; a screen whose only
  scrollable column is neither (Trends) gets no bottom inset, so its content is laid out
  underneath the mini-player.

### F2 (P3) — Patreon monthly earnings rendered inconsistently across screens

For the same story and member count (28 paid members), the Details Patreon card and the
Library card show **$139/mo**, while the Trends Patreon card shows **Current
$138/mo**. Details and Library round to whole dollars; Trends truncates the cents.
One of the two should be canonical.

- Evidence: `09-details-top.png` and `06-library-coverview.png` ($139) vs
  `11-trends-patreon.png` / `12-trends-7d.png` ($138).
- Independent retest: confirmed $139 in Library and $138 in Trends for the same story.

### F3 (product judgment) — Library header count does not reflect the active search filter

Searching "tanya" narrows the list, but the header still reads "234 novels". The
header is built from the total library size, so it is not a stale filtered count.
Whether it should show the number of search results is a product choice.

- Evidence: `03-library-search.png` vs `05-library-cleared.png`.
- Independent retest: four matching stories were visible while the header remained at
  234 novels. The original pass reported two results.

### F4 (retracted) — AI Settings key usage message

The original pass mistook the `sk-or-v1-...` placeholder for a masked, configured
key. The independent retest confirmed that the field's value was empty and its hint was
showing. "Add a key to see usage" therefore matched the observed state. This does not
test usage with a configured key.

- Evidence: `36-aisettings.png` (empty field with placeholder) and
  `37-aisettings-spend.png`.

### F5 (product judgment) — Settings grouping: AI Settings listed under "Reading & Audio"

On the main Settings screen, "AI Settings" (OpenRouter key + description models) sits
inside the "Reading & Audio" section, although it is not a reading or audio feature.
Grouping it under a dedicated "AI" heading (or beside Text Cleanup Rules as its own
group) would match the section titles better.

- Evidence: `27-settings.png`.
- Independent retest: confirmed the grouping; whether it should change is a UX decision.

### F6 (unconfirmed) — Continue-listening banner vanishing after a cover-view toggle

An earlier observation this session saw the continue-listening banner disappear after
toggling Library to cover view and **not return** when toggling back to list view (the
underlying TTS session was still active, and the banner had not been dismissed). On a
clean retest the banner persisted in both views, so this could not be reproduced. Filed
as an observation only; worth watching for, not fixing blind.

The independent retest also found the banner present after switching to cover view and
back to list view.

## Previously fixed — regression checks

1. **Reader Settings → Voice settings dismisses; Back returns to the reader** — **Pass.**
   Voice dialog opened from reader settings dismissed with Back, returning to the reader
   content (not main Settings). `21-reader-voice-dialog.png`.
2. **No dead "EPUB Volume Folding" control in Settings** — **Pass.** No "volume folding"
   node exists anywhere in the Settings hierarchy; the Auto/Cover/Inner segmented control
   that exists belongs to "Large Screen Layout" (legit). `27-settings.png`.
3. **Archives excluded/labelled in selection lists** — **Partially verified (vacuous).**
   The emulator library currently contains **zero** archived novels
   (`dev_library.sh list --archived` is empty), so the Following Review exclusion and the
   "· Archived" label could not be exercised against real data this pass.
4. **Reader bottom padding clears the chapter nav bar** — **Pass.** Scrolled to the
   chapter end; the last line renders fully above the nav bar in the unfolded reader
   (`18-reader-bottom.png`).

## Not run (out of scope for this pass)

- Live network flows: Royal Road / Scribble Hub / SpaceBattles imports and syncs, live
  followed-novel sync ("Sync Followed Novels" left untapped), live AI generation, live
  GitHub release check from App Updates. Queue inspection used historical completed
  tasks only; no new downloads were started.
- Patreon sign-in / cookie paste flows (screen render only, per QA rules).
- Physical phone / release variant.
- Audible TTS output verification (emulator audio path; playback state + highlighting
  verified instead).
- The optional short perf suite (`perf_runner.py`) — not part of this functional pass.

## End state

Emulator left unfolded on the Library screen (two-column, All tab), mini-player
dismissed, no TTS session active. `device_close` called without shutdown, so the
emulator keeps running.
