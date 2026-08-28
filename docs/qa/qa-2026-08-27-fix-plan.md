# QA 2026-08-27 — Findings Analysis & Fix Plan

Analysis of the findings in [native-app-emulator-qa-2026-08-27.html](native-app-emulator-qa-2026-08-27.html)
(4 P2, 8 P3, zero crashes) with concrete fix proposals per finding, code sites, and an execution order.
Each P2 proposal below names the files involved so implementation sessions can start from this document.

## Priorities at a glance

| Finding | Severity | Stakes | Fix cost | Order |
|---------|----------|--------|----------|-------|
| F2 — Full backup feedback opaque, `.tmp` leak, dead taps | P2 | Data safety: backups run *before* disasters; success invisible, failure silent | Medium | **1 — in progress** |
| F1 — Cold-start spinner 5–7s (+ 24–84s episode) | P2 | Every session, every user | Split: cheap UX fix + larger storage work | 2 |
| F3 — AI Controls expert-knob overload | P2 | Core "way too complicated" complaint | Medium (screen restructure) | 3 |
| F4 — Voice & Speech free-text decimals | P2 | Daily-reader usability | Small-medium | 3 (same pass as F3) |
| F5–F12 — polish batch | P3 | Death by a thousand cuts | Small each | 4 |

## F2 — Full backup feedback loop (P2) — implemented 2026-08-27

The highest-stakes finding. A backup flow whose success is a share sheet that can flash by in under
two seconds, whose failures are silent, and whose interrupted runs leak 37 MB orphan files is the
worst kind of bug: it is discovered exactly when the user needed the backup to work.

What the code showed:

- `feature/settings/SettingsScreen.kt` (`showDataBackup`) gated the row on
  `backupExportState.activeKind`; the only completion signal was `share(file)` in
  `feature/story/StoryFileActions.kt` firing a chooser that self-destructs when nothing handles
  the zip.
- `AtomicFileWrites.writeAtomically` cleans its `.tmp.N` sibling in a `finally` — which cannot run
  when the process dies mid-write (exactly what memory pressure does).
- "Dead taps" were the stuck `activeKind` flag with only a dimmed row as feedback.

Fixes shipped:

1. **Visible progress.** `BackupExporter.exportFull()` takes an `onProgress` callback
   ("Zipping files 400/1200"), throttled by `BackupProgressPlanning`. The Data & Backup screen
   shows a progress card with the live message while a full backup runs.
2. **Persistent result surface.** A "Backup Files" section on Data & Backup lists the zips and
   JSON backups already in app storage (date + size, newest first), each with Share and Delete.
   Completion is a new row appearing plus a "Backup saved" dialog offering Share — the chooser
   becomes an offer, not the only signal.
3. **Orphan sweep.** `BackupFilePlanning.sweepOrphanTempFiles` runs at storage construction and
   deletes every `*.tmp.N` under the backups directory (atomic-rename semantics make any survivor
   garbage; no export can be running at process start).
4. **State hardening.** `BackupExportState` tracks the export `Job` and `reconcile()`s on screen
   entry: a flag without a live job is cleared, so a stale run can no longer eat taps.

## F1 — Cold-start spinner (P2)

`MainActivity.onCreate` → `showStartupLoading()` (`app/StartupViews.kt`) renders a bare centered
`ProgressBar` until `awaitRepositoryReady()` finishes hydrating the whole library JSON — no label,
no branding, no skeleton. The 24–84s episode (process idle-blocked under ~900 MB swap) correlates
with backup I/O but is unproven; the StrictMode disk-read spam is the whole-file-JSON + global-lock
architecture already documented in the 2026-08-26 performance audit.

Proposed fix, two tiers:

1. **Now (cheap, big perceived win):** branded startup state — app name, theme background,
   "Loading your library…" — plus skeleton rows on Library so the first paint shows shape; hold the
   platform splash screen until the first real frame.
2. **Real fix (audit tier-1):** render the grid from a fast-path snapshot. Persist a compact index
   (story id, title, tabId, cover path) beside the full library JSON; parse the small file first,
   paint the grid, swap in the full library when hydration lands. Turns 5–7 s of nothing into
   sub-second first content without touching the write path.
3. Profile the 24–84 s episode separately *after* F2 lands — it may literally be backup I/O.

## F3 — AI Controls complexity overload (P2)

The per-novel screen (`feature/ai/AiControlsScreen.kt` + `AiChapterRewriteControls.kt`) embeds
three global model pickers rendering raw provider ids (`AiModelControls.kt` shows `currentModel()`
verbatim), repeats the same caution per picker, and leaks research vocabulary into product copy
("Verified in the spike: gpt-5.6-terra/sol…", "appliable").

Proposed fix:

1. **Move model configuration out of the per-novel screen.** Models are global (`AiSettings`);
   they belong on Settings → AI Settings (`feature/settings/SettingsAi.kt`). The per-novel screen
   keeps only actions: Generate Cover, Polish a Chapter (with strength), Generate Description.
2. **Friendly model names everywhere.** The picker already shows `model.name`; add an id→name
   resolver in `AiModelPresentation` (fallback: prettified id) so no row reads `openai/gpt-5.6-terra`.
3. **Copy pass.** Delete "spike", "appliable". The verifier rule becomes one plain sentence where
   the pickers live: "The checker must be a different model than the rewriter."
4. **Curated shortlist.** The known-good list becomes a "Recommended" section atop the picker;
   full searchable catalog stays below.

## F4 — Voice & Speech free-text decimals (P2)

`feature/settings/SettingsTts.kt` uses `labeledField` text fields for pitch/rate (silently clamped
to 0.5–2.0 by `SettingsValidation`), labels the voice button with the raw engine identifier
(`en-us-x-sfg-local`), and says "Save TTS".

Proposed fix: sliders (`SeekBar`) spanning exactly 0.5–2.0 with live values, save on release
(matching chips elsewhere), a Reset button, and a "Play sample" button speaking one sentence at
current settings through the existing `TtsEngine`. Resolve the voice identifier against
`ttsEngine.availableVoices()` for a friendly label ("English (US)"), falling back to the identifier.
Rename the button "Save".

## P3 batch

- **F5 (All tab sliver):** `feature/library/LibraryTabBar.kt` appends "All" last — move it first
  (matches Android convention); custom tabs scroll behind it.
- **F6 (Index N):** `feature/details/ChapterRowPlanning` — stop emitting "Index N" for
  un-downloaded rows; show published date (`Chapter.publishedAt` exists) or nothing.
- **F7 (delete styling):** add a `destructive` style to options-dialog items; apply in the library
  long-press dialog (`LibraryStoryViews.kt`), matching Details' overflow.
- **F8 (naming):** rename the story dialog's "Select Multiple" to "Organize Novels" so both entry
  points use one name.
- **F9 (EPUB egress):** add per-file Share in `LegacyEpubsScreen.kt` (the `share()` helper already
  exists); preserve title case in generated filenames.
- **F10 (Cover/Inner):** label-only rename in `SettingsScreen.kt` to outcome language
  ("Two-pane on large screens: Auto / Cover + list / Detail only"); no storage change.
- **F11 (Trends):** drop the duplicated in-screen novel header (use the screen subtitle); for
  novels with <3 snapshots show stat cards only.
- **F12 (keyboard / Manage Tabs):** clear focus + hide the IME when library search empties;
  compact Manage Tabs cards to one row with trailing actions.

## Execution order

1. F2 (this change)
2. F1a branded startup + skeleton (F1b snapshot fast-path is a separate, larger change)
3. F3 + F4 as one "make the expert surfaces humane" pass
4. F5/F7/F8 in one small PR, then the remaining P3s
