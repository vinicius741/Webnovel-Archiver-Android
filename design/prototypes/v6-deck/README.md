# v6 · Deck & command

**Concept.** Listening comes first, and you navigate by gesture and by typing a command. Home is a **Stage**, a big "now listening" block
with the time left, a scrubber and a huge play button, above a **Deck** of novel cards that you swipe one at a time. Swipe right to play, left
to queue the remaining-chapter download, and up to open the novel. Long-press opens the action sheet. A **command bar** stays at the
bottom of every screen. It searches novels, chapters, authors, tags and settings, and runs actions such as "sync gun", "speed 1.8",
"sleep 30", "gen 5" and "ch 12". There are no bottom tabs. The ⊞ button opens a poster-style index of places, and **+** adds a story.

**Palette.** Near-black `#0A0A0B`, off-white ink `#F2F0E8`, one electric accent (acid lime `#C8FF2E`, used for play, progress and focus),
and a signal orange `#FF6A2B` for errors, the bookmark pin, and swipe-to-queue and swipe-to-remove. There is no light theme.

**Type.** Archivo for display (900 weight, condensed `wdth 75` for big numerals, expanded `wdth 112–125` for poster headings), Space Grotesk
for the UI, and JetBrains Mono for small uppercase labels. The Reader is calmer: Inter 18 px at 1.65 line height, with Literata as an optional serif.

**Language.** Thick 2 px rules and outlines, small radii (4 px), hard offset shadows only on primary actions, numbered section tags
(`01 QUEUE`), and stats shown as big numerals (score, offline count, time left, %). Motion is quick and springy: card flings with
stamps, scrub thumbs that grow, an equaliser in the Stage, and palette rows that slide in.

**Key interactions**
- Deck: momentum fling (from velocity), stacked cards behind, and PLAY / QUEUE / OPEN stamps. There is a button fallback row (Back, Queue, Play, Open, More), and Grid mode covers the whole library (tabs, search, filters, sort, cover/list, long-press selection with Move and Delete).
- Command palette: thumb-first. The best hit sits right above the input, results are ranked with highlighted fuzzy matches, sections (Do, Novels, Chapters, Authors, Tags, Settings) depend on the context story, verb chips are available, and arrow keys plus Enter work on a keyboard.
- Reader: live sentence highlight with auto-scroll, double-tap a paragraph to read from there, swipe between chapters, the command bar hides while you scroll, and a docked player appears while this chapter is playing.
- Player: pull down to dismiss, current-sentence quote, 92 px time-left numeral, tap speed to cycle (long-press for the list), sleep timer, history, and Read along.
- Downloads: swipe right to pause or resume, swipe left to remove (with Undo), drag handles to reorder Up next, and live progress patched in place.
- Story: poster header, a 4-up stats strip, coverage bar with bookmark pin, and a dense chapter list with a **scrub rail** for long lists. On the Fold's inner screen it is two-pane.
- Settings: browsable and searchable. Every setting is also a command (`?focus=` deep links flash the row).

**Deep links for QA:** `#/library?view=grid|cover|list|deck`, `#/library?play=1`, `?cmd=<text>` opens the palette prefilled on any route,
`?places=1`, `#/reader/rr_141704/2800285?play=1`, `#/player?play=1`, `#/story/{id}?more=1`, `#/settings/tts?focus=rate`.

**Porting to Android (Compose)**
- The command bar becomes a persistent bottom `Surface`, and the palette a full-screen `ModalBottomSheet` (or a Dialog with the input pinned above the IME, using `imePadding`). Index novels, chapter titles, tags and settings in Room FTS. Parse verbs with a small grammar.
- Deck: `Box` with three stacked cards, `Modifier.pointerInput { detectDragGestures }`, `Animatable` offsets and a velocity tracker for the fling. Use AnimatedContent for stamps. Always keep the accessibility fallback buttons.
- Stage, mini player and docked player all collect the existing TTS `StateFlow`. Scrubbing uses `Slider` with a custom thumb and track.
- Big numerals: Archivo variable font (`FontVariation.width(75f)`). Thick rules map to `BorderStroke(2.dp)`, and hard shadows to an offset `drawBehind`.
- Swipe rows map to `SwipeToDismissBox`. Reordering uses `LazyColumn` with a drag-reorder modifier. The chapter scrub rail is a custom `draggable` that calls `listState.scrollToItem`.
