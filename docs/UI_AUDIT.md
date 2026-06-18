# UI Audit — Webnovel Archiver (Native Android)

> Compiled after a full manual walk-through of every screen in the `webnovel_api36` emulator (debug build `com.vinicius741.webnovelarchiver.nativeapp.debug`, branch `codex/native-android-support`).
> Coordinates are in emulator px (1080×2400) and are approximate — used only to point at the region in question.
> Each finding is tagged **[Severity]**: 🔴 High (broken/confusing UX), 🟡 Medium (rough/unpolished), 🔵 Low (polish/nit).

---

## 0. Cross-cutting / global issues

### G1. 🔴 Everything is hand-rolled programmatic Views — no shared spacing system
Every screen manually threads `dp(8)`, `dp(12)`, `dp(16)` through dozens of `setPadding(...)` / margin calls. There is no spacing scale (4/8/12/16/24), so:
- paddings are inconsistent between screens that should look identical (e.g. card padding is `16`, app-bar icon padding is `11`, chip padding is `14/7`, search field padding is `14/12`).
- the same logical gap appears as `dp(2)`, `dp(4)`, `dp(6)`, `dp(8)`, `dp(10)`, `dp(12)` in different files.

### G2. 🟡 App bar action icons are cramped against the right edge
`Scaffold.kt:74` pads the app bar with `dp(4)` on the right only. Four 46dp-wide touch targets (Browser, Queue, Select, Settings) sit shoulder-to-shoulder from x=590 to x=1070 with **zero gap to the screen edge** and no separators. On the Library they read as a single cluttered icon strip rather than distinct actions.

### G3. 🟡 `WrapLayout` flow rows leave large ragged gaps
`flow {}` (used for almost every button row) left-aligns and wraps. Because buttons have unequal widths, rows frequently end with a big empty stretch on the right:
- Settings TTS row: `Save | Voice | Manage Tabs` fit, but `Cleanup Rules` wraps alone to the next line — leaving ~600px of dead space on row 2.
- Settings Backup row: `Export JSON | Import JSON` then `Full Backup | Restore Full` — right ~250px of each row is empty.
- This looks unintentional; a grid or right-aligned secondary actions would be cleaner.

### G4. 🔵 Button visual weight is inconsistent
`fullButton` (MATCH_PARENT) is used for primary actions on Details, but everywhere else primary actions live inside `flow {}` as WRAP_CONTENT buttons of varying widths. The same "Save" concept is a giant bar on one screen and a tiny wrap button on another.

### G5. 🔵 No loading/affordance feedback on async actions
`syncStory`, `generateConfiguredEpub`, `applyCleanup`, backup export etc. all run with **no spinner, no disabled-state on the trigger button, and no optimistic UI**. Tapping "Fetch Story" or "Sync Chapters" appears to do nothing for several seconds.

---

## 1. Library

### L1. 🔴 Filter bar takes ~400px before the first story is visible
Vertical stack above the list: tab bar (`All | Unassigned (1)`) → search + sort icon → tag chips (`RoyalRoad (1) | Adventure (1) | Attractive Lead (1)`). Measured from the UI tree: the scroll area for the actual story cards doesn't begin until y≈683, and the single card sits at y≈725–1080. On a list with a few stories the chrome-to-content ratio is ~30%, which is heavy for the home screen.

### L2. 🟡 "Sort" icon has no label and no current-state hint
The sort affordance is a single 40×40 icon (`wna_sort_descending`) inline with the search field. There is no indication of the active sort option or direction until you open the dialog. A chip-style "Sort: Last Updated ↓" would communicate state.

### L3. 🟡 Collapsible-filter chevron is orphaned when tabs exist
`makeLibraryFilters` builds a `wrapper` whose only child on the header row is a right-aligned chevron — the left side is an invisible spacer (`View(context)`, weight 1). The result is a lone down-arrow floating at top-right with no label, which is hard to discover as "toggle filters."

### L4. 🔵 Tag chips show raw source/category names with no grouping
`RoyalRoad (1)`, `Adventure (1)`, `Attractive Lead (1)` are all flattened into one horizontal scroll. Source name and genre tags are visually identical, so the "source" filter is indistinguishable from genre filters.

### L5. 🔵 Story card has no download-count text, only a thin progress bar
`renderLibraryList` shows a 4dp progress bar but no "12 / 140 chapters" label. You have to open the story to see the numbers.

---

## 2. Add Story

### A1. 🔴 "Save to tab" section header always renders, but the spinner is conditional
`showAddStory` always calls `section("Save to tab")`, then only `addView(tabSpinner)` if `tabs.isNotEmpty()`. With no tabs you get a bold header "Save to tab" followed by nothing — a dangling label with no control under it.

### A2. 🟡 Primary CTA ("Fetch Story") is a WRAP_CONTENT button in a flow
"Fetch Story" is the main action of this screen, yet it's a small wrap button (~360px wide) sitting left-aligned above "Or browse." It should be a `fullButton` (MATCH_PARENT) like the Details primary actions, for consistency and tap target size.

### A3. 🔵 "Royal Road" / "Scribble Hub" browse buttons duplicate the app-bar Browser entry
The "Or browse" row opens the same Browser screen the app-bar globe opens, just with a preset URL. Two paths to the same place with no explanation.

---

## 3. Details

### D1. 🔴 The whole screen is one giant ScrollView including the chapter list
`scrollable = true` wraps the header + actions + description + tags + search + chips + **the entire chapter list** in a single scroll surface. For a story with hundreds of chapters this means:
- No sticky header / actions — once you scroll, Sync/Download disappear.
- The chapter search field scrolls away, so filtering requires scrolling back to the top.
- There's no virtualization (it's a vertical `LinearLayout` building one row per chapter), so long lists inflate every view up front.

### D2. 🟡 Two full-width primary buttons stacked, then a 2-col grid — inconsistent action hierarchy
Layout order: `fullButton("Sync Chapters")` → `fullButton("Download Remaining (N)")` → `grid(2){ Generate EPUB | Read EPUB }`. The two grid buttons look subordinate but "Generate EPUB" is arguably the most-used action. Visual weight doesn't map to usage.

### D3. 🟡 "Copy" button under the description is odd
Below "Read more" there's a standalone text button "Copy" that copies the description to clipboard. Copying a synopsis is a rare action; surfacing it as a peer of "Read more" inflates the description block and looks like a leftover.

### D4. 🟡 Description block is center-aligned but the rest of the screen is left-aligned
`descCol` uses `Gravity.CENTER_HORIZONTAL` and the text view itself `Gravity.CENTER`, while chapter rows, search field, and chips are left-aligned. The centered description between left-aligned sections reads as misaligned.

### D5. 🔵 Stat pills duplicate info already in the header
The header shows `RoyalRoad` badge + `Score` / `Chapters` / `Saved` pills, but `Score` and `Chapters` are often already implied by the tags/score row that Royal Road provides. Three pills + a badge + tags is a lot of meta before any description.

### D6. 🔵 "EPUB out of date" is a bare centered text line
When `epubStale == true`, a single centered `BODY_SMALL` line appears between the grid and the description with no action button ("Regenerate?"). It informs but doesn't let you act.

---

## 4. Reader

### R1. 🔴 Seven navigation/TTS buttons crammed in one wrap row
Top of reader: `Prev | TTS | Pause | Stop | TTS Prev | TTS Next | Next` in a single `flow {}`. That's mixing chapter navigation (Prev/Next) with TTS transport (TTS/Pause/Stop/TTS Prev/TTS Next) into one undifferentiated row of seven tiny text buttons. There's no grouping, no icons-on-primary, and "Pause"/"Stop" do nothing unless TTS is already running — but they're always enabled.

### R2. 🔴 TTS transport controls are always visible even when TTS is off
Pause, Stop, TTS Prev, TTS Next are shown permanently. For a reading screen these are noise; they should appear only when a TTS session is active (or live in a bottom sheet / mini-player).

### R3. 🟡 Second action row (Copy / Mark Read / Details) is redundant
"Details" just navigates back to the screen you came from (the back button already does this). "Mark Read" is also available in the per-chapter overflow (⋮). Three text buttons mostly duplicate existing affordances.

### R4. 🟡 WebView has no reader chrome (font size, theme, margins)
The WebView loads `ReaderContentRenderer.document(...)` with a fixed style. There's no font-size control, no margin adjustment, no dark-background-for-reader toggle — common features for a novel reader.

### R5. 🔵 Chapter title in the app bar isn't sanitized
The app-bar `title = chapter.title` uses the raw title, while the chapter list uses `sanitizeTitle(...)` to strip trailing dots. The reader header can show "Chapter 12..." while the list shows "Chapter 12".

---

## 5. Browser

### B1. 🔴 ~60% of the screen is controls; the WebView gets the leftover sliver
Above the WebView: URL EditText, "Ready" status text, "Save imported novel to" section + spinner, then `Go | Back | Forward | Refresh`, then `Import | External | Home`. That's two flow rows + a field + a status + a section header **before** any web content. On a 2400px-tall screen the WebView ends up with very little vertical room, defeating the purpose of a browser.

### B2. 🟡 "Home" button in the browser navigates to Library
The second flow row has `Home` which calls `showLibrary()` — but the system/app-bar Back already returns to Library. Redundant, and "Home" is ambiguous (browser home page vs app home).

### B3. 🟡 "Back" exists both as an app-bar icon and as an in-content button
The app-bar back arrow (webview history → Library) duplicates the in-flow "Back" button (webview history only). Two back affordances with slightly different behavior is confusing.

### B4. 🔵 The address EditText is plain-styled, not a browser URL bar
It uses the generic `makeField` (surface-variant fill, 1px outline). It doesn't look or behave like an address bar (no reload-on-Enter, no security indicator, no lock icon for https).

### B5. 🔵 "Ready" / "Loading..." status is a raw label floating between the URL and the save section
A one-line status TextView between inputs reads as a layout accident rather than a progress indicator.

---

## 6. Settings

### S1. 🔴 Single Save button at the bottom saves Downloads, Source Overrides, AND TTS together
One `flow { Save }` button persists `settings`, `sourceSettings`, and `ttsSettings` in one shot. There's no indication that this one button covers three visually separate sections (Downloads, Source Overrides, Text To Speech). A user editing only TTS may not realize they must scroll to the bottom and hit the global Save.

### S2. 🟡 "Active: obsidian" debug-style label under theme chips
Right below the theme picker, `text("Active: ${displayPreferences.activeThemeId}")` prints the raw theme id (`obsidian`, `classic-light`). This looks like developer diagnostics left in the UI; the selected chip already communicates the choice.

### S3. 🟡 Source Override cards repeat the provider name four times each
For RoyalRoad: card title "RoyalRoad", checkbox "Override RoyalRoad", field label "RoyalRoad concurrency", field label "RoyalRoad delay (ms)". The name is repeated in every label inside the card that's already titled RoyalRoad.

### S4. 🟡 TTS "Voice: System default" is a static label, not a control
The current voice is shown as plain text; to change it you tap the separate "Voice" button in the flow below. The label should be the control (tap-to-open picker).

### S5. 🟡 "Clear Local Storage" (destructive) lives in the same visual zone as Backup export buttons
The error-styled "Clear Local Storage" button sits directly under `Export/Import/Full Backup/Restore Full` with only a flow-wrap separation. A destructive irreversible action should be visually separated and ideally behind a second confirmation (it does have a confirm dialog, but placement still reads as risky).

### S6. 🔵 "Fold Layout" (Auto/Cover/Inner) has no explanation
Three chips with no description of what fold layout means or when it matters. Unusable without docs.

### S7. 🔵 Backup buttons are not grouped by direction
Export/Import and Full Backup/Restore Full are four similar-looking buttons in a wrap. Grouping "Export | Full Backup" (outbound) and "Import | Restore" (inbound), or using labeled sub-headers, would clarify the two backup formats.

---

## 7. Manage Tabs (Settings sub-screen)

### T1. 🟡 Empty state is just a bare input field
With no tabs, the entire screen is a single `hint = "New tab name"` EditText + "Add" button. No empty-state illustration or helper text explaining what tabs are.

### T2. 🔵 Tab card action row has four text buttons: Up / Down / Rename / Delete
Reordering via "Up"/"Down" buttons is functional but clunky; drag-to-reorder or a single move affordance would be expected. Four text buttons of equal weight also make "Delete" feel as routine as "Rename."

### T3. 🔵 Tab count is a bare number with no label
`"${storage.getLibrary().count { ... }}"` renders just a number (e.g. "0") at the right of the tab row, with no "novels" suffix or icon.

---

## 8. Text Cleanup (Settings sub-screen)

### C1. 🔴 Every sentence rule is a full card with its own Edit/Delete button row
There are **10+ default sentence-removal rules**, each rendered as a card containing the full sentence text + an `Edit | Delete` button row. This produces an enormous, repetitive wall of near-identical cards. A compact list (single-line truncated, tap to expand/edit, swipe to delete) would be far more scannable.

### C2. 🟡 "Add" button for sentences is mismatched with the field height
The sentence input is a `setSingleLine()` EditText (~118px tall), but the inline "Add" button is WRAP_CONTENT and right-aligned via a row layout, so it doesn't fill the row height cleanly.

### C3. 🟡 Regex rule card shows raw `/pattern/flags • appliesTo` as a subtitle
The regex summary line `"/${rule.pattern}/${rule.flags} • ${rule.appliesTo}"` is raw and can overflow / wrap uglily for long patterns. No truncation or monospace styling.

### C4. 🔵 "Export JSON" button sits alone above "Sentences"
A lone export button at the very top, before any section header, reads as orphaned. It's not clear it exports cleanup rules (not the whole app).

### C5. 🔵 Add-Regex dialog has a "Quick Separator" button but no preview of the generated pattern
Tapping "Quick Separator" opens a sub-dialog, fills the fields, and returns — but the user doesn't see what the pattern will match until it's saved and applied.

---

## 9. Download Manager (Queue)

### Q1. 🔴 Six stat pills in one wrap row, including always-zero states
`Total | Active | Queued | Done | Failed | Paused` — six pills, many of which are "0" at any given time. The row is dense and dominated by zeroes; collapsing zero-states or using a single "X of Y done" summary would communicate better.

### Q2. 🟡 Five global action buttons in one wrap row
`Resume | Pause | Retry Failed | Clear Done | Cancel All` — five buttons of mixed variants (TONAL/TEXT/ERROR) wrapping across two lines with no grouping. "Cancel All" (error/destructive) sits next to "Clear Done" with little visual separation.

### Q3. 🟡 Per-story group summary is a long run-on text line
The group card subtitle is one `BODY_SMALL` line:
`"3/9 chapters • 0 queued • 0 active • 0 paused • 6 failed • 0 cancelled"`
— six counts in one sentence, including the always-present zero buckets. Hard to scan.

### Q4. 🟡 Per-job cards repeat per-story actions
Each job card has its own `Pause/Resume | Cancel | Retry | Remove` flow. For a queue of 9 jobs under one story, that's 9 × ~3 buttons = 27 buttons, many duplicated by the per-story controls above. Very noisy.

### Q5. 🔵 "next retry 5m" uses `formatRelativeTime` which shows a countdown from *now*
`formatRelativeTime(timestamp)` computes `timestamp - now`. It reads "5m" with no unit context, and because it's computed at render time it goes stale until the screen is reopened.

---

## 10. Selection screens (Select Novels / Select Chapters)

### X1. 🔴 Selection lists use bare CheckBoxes, discarding the library's card design
`showLibrarySelection` and `showChapterSelection` render each item as a plain `CheckBox` with text ("Title - Author" / "1. Chapter Title"). No covers, no metadata, no download status — a dramatic visual downgrade from the Library/Details lists they mimic. Two screens that should feel like "multi-select mode" of the parent list instead look like a 2010 form.

### X2. 🟡 Action bar (Move/Delete or Download Selected) sits at the very top
The bulk actions are in a `flow {}` at the top of the screen, above the list. "Download Selected" is a WRAP_CONTENT button, not a full-width primary CTA, and it's far from the items you're selecting (which scroll below).

### X3. 🔵 No "Select All" / "Deselect All" affordance
For selecting many chapters, there's no select-all toggle — you must tap each checkbox individually.

---

## 11. Dialogs

### DL1. ✅ Download Range dialog replaced by on-list range selection
The separate mode-and-number dialog was removed. Select Chapters now supports tap-first/tap-last
ranges and long-press drag selection with edge auto-scroll, keeping the interaction next to the
chapters it affects.

### DL2. 🟡 EPUB Settings "Start after bookmark" checkbox can be visible-but-disabled
When there's no bookmark, the checkbox renders disabled with no explanation of why.

### DL3. 🔵 Confirm dialogs use generic "Confirm" / "Cancel" buttons
`ScreenHost.confirm` hardcodes `setPositiveButton("Confirm")`. For delete actions "Delete" / "Remove" would be clearer and less ambiguous than a generic "Confirm."

### DL4. 🔵 Cover dialog uses a fixed `dp(520)` height ImageView
`showCoverDialog` sets a fixed 520dp-tall image area regardless of the cover's aspect ratio, which can letterbox or crop awkwardly.

---

## Summary of themes

1. **Too many buttons.** Almost every screen stuffs 4–7 actions into wrap rows (Reader: 7, Queue: 5 global + per-job, Cleanup: per-card). Actions need grouping, progressive disclosure (overflow menus, bottom sheets), or contextual show/hide.
2. **Inconsistent primary-action treatment.** `fullButton` (full-width) is used only on Details; the same conceptual primary action elsewhere is a small wrap button. There's no shared "primary CTA" convention.
3. **Controls before content.** Library, Browser, and Reader all spend their top ~30–60% on chrome before the actual content (cards / web page / chapter text).
4. **Duplicated/redundant affordances.** Browser Back vs app-bar Back, Reader "Details" vs back, "Home" vs back, per-job buttons duplicating per-story buttons.
5. **Raw/developer-facing text in the UI.** "Active: obsidian", "Voice: System default", run-on status summaries, raw regex `/pattern/flags`.
6. **No async feedback.** Sync/Download/Generate/Backup fire with no spinner, no button-disable, no progress — the app looks frozen until the toast appears.
7. **Selection screens are a visual downgrade.** Bare checkboxes instead of the parent list's card design.

### Recommended priority order
1. Reader TTS row (R1/R2) — biggest "weird interface" win per user report.
2. Browser controls-to-content ratio (B1).
3. Details single-scroll + chapter list virtualization (D1).
4. Settings global Save / section grouping (S1).
5. Cleanup sentence-card wall (C1).
6. Selection screens reuse card design (X1).
7. Pass to unify spacing scale + primary CTA component (G1/G4).
