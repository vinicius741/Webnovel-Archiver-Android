# v1 · Refined

**Concept.** The most conservative option. It is the same app with the same screens, one navigation stack from Library, app-bar icons for Narration, Downloads and Settings, the + FAB, and the obsidian-and-gold look, rebuilt with a strict system. Nothing moves; everything is quieter, aligned and consistent.

**Palette (dark only).** Surfaces step up in tone: `bg #12110F`, `s1 #191815` (cards), `s2 #1F1D1A` (raised app bar, sheets), `s3–s5` for inputs, mini player and tracks. Ink is `#ECE6DD`, then `#B4AD9F` for secondary text, `#857E72` for tertiary and `#5F5950` for disabled. Gold `#C9A84C` is the primary, with `#E2C46E` for highlights. The brown secondaryContainer is replaced by a **gold-tinted tonal container** (`#342C17` on `#ECDBA3`), so every tonal button and selected chip belongs to the gold family. Semantic colours are warm and muted: completed `#A9C48F`, hiatus `#E1AD74`, error `#FFB4AB` on `#3D1814`, and rose `#E5867B` for the bookmark pin.

**Type.** Inter (Roboto Flex as fallback) with tabular numbers wherever counts appear. Scale: 22 (Library title), 21/18 (page and app-bar titles), 15.5 (card titles), 14 (body), 13 (secondary), 12 (meta) and 11 (caps labels). The Reader uses 18 px at 1.7 line-height, scaled 80–160%.

**Shape and rhythm.** 4/8 pt spacing with a 16 px gutter. Radii are 6 for covers, 10 for chips, inputs and buttons, 14 for cards, and 20 for sheets and the mini player. Elevation comes from tone, plus three shadows (e1–e3) used only on floating elements.

**Key decisions**
- **Library card hierarchy:** title, then author · source, then one meta line (status pill, ★ score with trend arrow, "New chapter?", Patreon $/mo). Then come faint tags on a single line, and a footer with `dl / total`, a thin coverage bar with a rose bookmark tick, and a "Ch. N" bookmark label.
- **Sticky header:** app bar, tabs (with counts), and an always-visible search pill plus a sort pill. Source and tag chips scroll horizontally, selected chips come first, and "All tags" opens a searchable sheet. Long-press starts a contextual gold app bar with Move/Delete in a bottom bar (Undo on both). You can swipe between tabs.
- **Details:** a quiet header (cover, title, author link, badges, score → Trends), then the forecast, coverage, metric chips and one action group. The group is a filled **Continue reading · Ch. N** button plus a Listen button, then three equal tonal buttons: Sync / Get N / EPUB. Inline status rows show sync, EPUB progress and a live download banner. The Patreon card is calm. Chapter rows are dense, with index, title, offline/audio/date meta and a bookmark toggle; the bookmarked row gets a rose accent.
- **Menus are bottom sheets** with section labels (familiar list order, drag-to-dismiss). Confirmations stay as centred dialogs.
- **Settings** use grouped cards with icon list rows, switches, sliders with value chips, and a sticky "Unsaved changes · Save TTS" bar.
- **Motion:** 220–320 ms emphasized-decelerate push and pop, sheets that rise, chips and tabs that settle, a live sentence highlight that eases between sentences, and auto-scroll that pauses for 3.5 s after you scroll by hand. Everything respects reduced-motion.
- **Wide (≥760 px):** 2-column library list (3 at ≥1100), a 5–6 column cover grid, two-pane Details (a 380 px info pane and a chapter pane with its own scroll), and a side-by-side Now Playing.

**Deep links for QA:** `#/library?view=cover`, `#/reader/rr_141704/2800285?play=1`, `#/player?play=1`, `#/story/{id}/ai?tab=description`, `#/narration/{id}?chapter={cid}` (focus card), `#/story/{id}?focus=chapters`.

**Porting to Compose**
- Map tokens to a custom `ColorScheme` with `secondaryContainer = #342C17` and `onSecondaryContainer = #ECDBA3`. Keep `surfaceContainer*` tones as listed. Bundle Inter as a downloadable or resource font.
- Library card is a `Row(cover 72×108) + Column`. Use `FlowRow(maxLines = 1)` for the meta line. Draw the coverage bar on a `Canvas` with the bookmark tick.
- Sheets map to `ModalBottomSheet`, the contextual bar to a `TopAppBar` with `containerColor = secondaryContainer`, and the sticky search to the `LazyColumn` `stickyHeader`.
- For the Reader highlight, use an `AnnotatedString` with a background `SpanStyle` per sentence and `animateScrollTo` to centre it.
