# v0 · Current app (baseline)

**Concept.** A copy of the Kotlin app as it ships today (1.0.21-native), used as the baseline for comparing redesigns.
Nothing is redesigned. The prototype follows `reference/*.jpg` and `SCREENS.md`, quirks included.

**Palette ("Obsidian").** bg `#12110F`, app bar `#201E1B` (elevation2), cards `#201E1B` and `#252320`, gold primary
`#C9A84C` with dark `#3D2E00` text. Brown `secondaryContainer #663C21` is used for tonal buttons ("Generate EPUB",
"Read along", "Allow"), selected chips, and the narration banner. Olive `#574800` marks status and "Updated" pills,
red `#93000A` marks content warnings, and salmon `#FFB4AB` marks destructive actions and failures.

**Type and shape.** Roboto. App-bar titles are 24/700 with 14 px muted subtitles. Section labels are gold 17/700
with 1 px tracking. Reader text is 18 px on phones and 20 px at 600 px and wider, with line height 1.7. Radii: cards
12, buttons 8, dialogs 16, chips 8. Icons are Material Symbols Rounded, filled by default; outline variants are used
for the bookmark, the EPUB book, and the grid toggle.

**Navigation.** One stack rooted at Library, with no bottom nav. The Library app bar has the cover/list toggle,
Narration, Downloads and Settings. The FAB "+" opens Add Story. Menus are centered "More options" dialogs with gold
section labels, a red "Delete Novel" and "Cancel". Confirmations are alert dialogs and feedback uses Android-style
toasts.

**Layouts.**
- Phone (412): 1-column library list, 2-column cover grid, and Details as one scroll with a "Hide details" toggle.
- 600 px and wider: 2-column list (3 at 1000 px and wider), 4-column cover grid, and two-pane Details with a
  ~456 px info pane and the chapter list on the right.

**Player.** The saved session (Gunwitch, ch. 3, sentence 17) loads paused at boot, so the floating mini player shows
on every screen, as in the screenshots. Closing it (X) brings back the Library's "Continue listening" card. The Reader
docks the player in place of the chapter nav while its chapter is the one loaded. The current sentence is
highlighted and auto-scrolled, and double-tapping a paragraph reads from there. The AI job chip floats above the mini
player.

**Deep-link hooks for QA.**
- Library: `#/library?view=cover`, `?filters=1`, `?select=1`, `?tab=all`, and `?idle=1` (nothing loaded, so the
  Continue card shows).
- Details: `#/story/{id}?menu=1`.
- Playback: `#/reader/{id}/{ch}?play=1`, `#/player?play=1`.
- Other: `#/narration/{id}?select=1`, `?ch={chapterId}` (chapter focus card), `#/story/{id}/ai?tab=description`.

**Porting notes.** This is the existing app, so there is nothing to port. Use it to diff behavior and density
against v1–v6.
- The Fold screenshots show a 2-column list at 884 dp and a ~450 dp info pane, not the 3-column ≥840 dp grid and
  360 dp pane described in `SCREENS.md`. The prototype follows the screenshots.
- `story.bookmark` is treated as a 1-based chapter number, so "From Bookmark (209)" for Gunwitch matches the
  screenshot.
- When the USD amount is unknown, the Patreon estimate is ~$9 per paid member, matching "~$2,889/mo · 321 paid" and
  "$290–$828".

**Files.**
- `app.js`: shell, dialogs, toasts, mini player.
- `screens/*.js`: one file per area.
- `styles.css`: tokens and components.
- `screens.css`: per-screen styles.
