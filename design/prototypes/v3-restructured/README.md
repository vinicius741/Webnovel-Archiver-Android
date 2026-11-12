# v3 Restructured — new navigation

**Concept.** Same warm gold-on-obsidian identity, refreshed. The single stack becomes a five-destination app with a
persistent player. Each job gets a home: browse (Library), check what's new (Updates), listen and generate audio
(Listen), watch downloads (Downloads), and configure (Settings). Story pages split into tabs.

**Sitemap**
```
Library ─ search in app bar · Sort & filter sheet · list/cover · long-press select (Move/Download/Delete) · + Add story
  └ Story ─ tabs: Overview · Chapters · Audio · Trends · AI     (⋮ More, EPUB sheet, cover zoom, Select chapters)
      ├ Author novels        └ Reader (docked player, settings sheet)
Updates ─ release forecast + 7-day calendar · May be out · To download · Expected · Running late · Recently synced
Listen ─ Now playing / Continue listening · Queue · Generated · Setup (Generate on, computers, keep ahead)
Downloads ─ summary · site check · failures · Downloading / Up next / Paused / Finished
Settings ─ Listening & reading · Library · Intelligence · System  →  tts ai data tabs cleanup notifications downloads update
Mini player (docked above the bar, everywhere) ─ tap or swipe up → Now Playing sheet (#/player)
```
**Route map.** `#/narration` → Listen · `#/narration/{id}` → Story › Audio tab · `#/story/{id}/trends` → Trends tab ·
`#/story/{id}/ai` → AI tab · `#/story/{id}?tab=chapters` → Chapters tab · `#/updates` (new) · `#/player` opens the
Now Playing sheet over the current screen (Listen if opened directly). All other routes match the contract.
QA hooks: `#/library?view=cover|select=1|sheet=filters|q=…`, `#/reader/…?play=1`, `#/player?play=1`,
`#/story/{id}/select?demo=1`, `#/narration?seg=gen|setup`, `#/updates?f=due|missing|soon|late|synced`.

**Palette.** bg `#110F0C`, surfaces `#191712 → #3A342B`, text `#EDE6DA` / muted `#AAA190`, gold `#D8B65A`
(container `#47380C`), copper `#DCA582` for Patreon, generation, and late, red `#FF9A8F`, sage `#B3C98D` for done/completed.
**Type.** Roboto Flex (variable): 26/720 large titles, 20/650 app bars, 15.5/650 card titles, 14.5 body, 12.5 meta.
Material Symbols Rounded. Shapes: cards 18, pills 24, sheets 28.

**Key interactions.** Contextual primary action on Overview ("Continue reading · Ch. 3", or "Download 21 remaining"
when you've caught up), with Sync / EPUB / Select / Audio quick actions. Bottom sheets replace dialog menus. Reader
follows the TTS sentence, stops following when you scroll ("Back to current sentence" pill), double-tap a paragraph
to read from it, and moves to the next chapter with the player. Updates batch-syncs overdue novels with live
progress. Selection modes (Library, Audio) swap the nav bar for an action bar.

**Porting notes (Compose).** `NavigationBar` ↔ `NavigationRail` at ≥ 600 dp (`NavigationSuiteScaffold`), rail
destinations bottom-aligned for thumb reach. Mini player = a bottom slot in the scaffold. Now Playing = a full-screen
route with shared-element cover. Story = `TabRow` + `HorizontalPager`. On wide screens it uses `ListDetailPaneScaffold`
with the Overview hero in the list pane. Sheets = `ModalBottomSheet`. Updates are computed from existing forecast data,
so no new backend is needed.
**Gaps.** Up-next drag reorder only supports moving a novel to the top (the kit has only `moveFirst`). "Removed from source" chapter chip omitted (no data).

## Round 2: changes from feedback (2026-11-12)
| Feedback | Change |
|---|---|
| Bookmark colour (v1, the same red here) | Bookmark is now ivory (`--bm`) everywhere: the pin on progress bars, the chapter-row highlight and the reader button. |
| Tablet sidebar should be on the other side | The navigation rail is on the **right** edge; the content, mini player, toasts and selection bar shift left. |
| Plus/minus instead of sliders | Shared stepper (`A.stepper` / `A.stepRow` / `A.bindSteppers` in core.js): tap ±, hold ± to repeat (speeds up), **tap the number to type an exact value**. Used for TTS rate/pitch/pauses, the AI scan limit, Listen "chapters ahead" (was presets), and Download settings. Only the player's seek bar is still a slider. |
| Custom per-source delay without presets | A source opens a sheet: "Custom pace for this site" switch, then steppers for chapters at a time / delay min / delay max. The presets are just "Quick fill" chips. Min and max stay ordered. |
| Whole cover image, never partial | Covers are never cropped: odd-shaped art is letterboxed over a blurred copy of itself. The cover-grid progress bar moved below the art. Tapping a cover (story header, Downloads rows) opens a full-screen viewer. |
| AI cover too small to judge | "New cover ready" shows Current and New side by side at half the card width each, plus "View full size": a full-screen viewer to swipe between them, with Use/Keep buttons. Tapping any version in the Covers strip opens all versions full size (swipe, prompt, "Use this cover"). |
| Scroll area vs floating buttons overlapping | Root cause: `--bottom-space` was computed on `:root`, so the nav/player heights never counted and the last rows stayed hidden. It is now computed on `body`. A solid bottom shelf with a 20px fade sits behind the nav and mini player (no content peeking around the floating player). Save bars are docked right above the shelf, and the view adds padding while one is visible. Toasts and the AI job chip stack above both, so the job chip no longer covers the tabs. |
| Now Playing "Now reading" card (liked in v1) | Already in v3's Now Playing. Kept. |

Porting notes: steppers map to a `Row { IconButton(−) ; TextButton(value → number dialog) ; IconButton(+) }` with
`repeatOnLongPress`. The shelf maps to the `Scaffold` bottomBar holding mini player + `NavigationBar`, with content
using `innerPadding`. Save bars go in a bottomBar slot above it, not overlaid. Rail goes in `NavigationSuiteScaffold`
with the rail placed at the end (custom layout, since M3 puts it at the start).
