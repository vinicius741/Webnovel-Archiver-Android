# Prototype brief (read fully before building)

> **Context budget warning.** `shared/library/data.js` is a 1 MB single-line file. Never open, cat, head, or grep it,
> and never grep recursively over `design/prototypes/` or `shared/` without `--exclude-dir=library`. Read
> `shared/DATA_SHAPE.md` for the data shape instead. Keep tool output small: no dumping of whole large files, and
> view at most ~4 reference/QA images per turn at their default size. Write code in several moderate files
> (≤ ~600 lines each) rather than one giant file, and edit with targeted Edit calls.

We are redesigning the UI of **Webnovel Archiver**, a native Android app for archiving web novels and reading them
offline or listening to them with text-to-speech. Before touching the Kotlin app we are building
**clickable HTML prototypes of the whole app**, in 7 versions (v0–v6). The owner will walk through them on their
phone over the LAN, compare them with the in-page version switcher, leave feedback, and pick a direction. The chosen
design will later be ported to Kotlin, so the designs must stay buildable as a native Android UI.

## Hard constraints

- **Dark theme only.** The owner dislikes light themes. No light mode, no toggle.
- **Real data.** The owner's real library (282 novels, real covers, tabs, chapters, metrics, TTS settings) is exposed
  through `window.WNA` (see `shared/kit.js`, its header documents the API). Never invent placeholder novels.
- **Phone first.** The owner likely uses a Galaxy Z Fold. Design for a **412×915 CSS px** phone/cover screen, and
  make it adapt well to the **~884×1000 inner screen** (multi-column library, two-pane details, and so on where it
  helps). Nothing may scroll horizontally except deliberate carousels. No hover-only affordances.
- **Same functionality in every version.** Versions differ in design and information architecture, not in features.
  Every feature in `SCREENS.md` must be reachable and simulated: tapping buttons does something believable (state
  changes, progress animates, toasts or snackbars confirm, sheets open). Back navigation works everywhere.
- **Only write inside your own version folder** `design/prototypes/<version-id>/`. Do not edit `shared/`,
  `SCREENS.md`, `BRIEF.md`, the gallery, or other versions. If the kit is missing something, work around it inside
  your folder (wrap or extend `WNA` locally) and mention it in your final report.

## Files and tech

- Entry: `design/prototypes/<version-id>/index.html`. Load, in order:
  `<script src="../shared/library/data.js"></script>`, `<script src="../shared/kit.js"></script>`, then your own
  scripts. Use relative paths only (the site is served from `design/prototypes/` by `server.py`).
- No build step. Plain HTML, CSS and modern JS (ES2020, template literals). Split into several files as you like
  (`app.js`, `screens/*.js`, `styles.css`). Hand-written vanilla JS is preferred. If you really want a library, load it
  from cdnjs/jsdelivr/unpkg pinned to an exact version.
- Fonts: Google Fonts allowed. Icons: Material Symbols (Google Fonts) or inline SVG.
- Images: `WNA.coverUrl(story)` gives the cover URL (null for 8 stories; then use `WNA.coverFallback(story)`, a CSS
  gradient, with the title over it). Use `loading="lazy"` and `decoding="async"`. The library has 282 novels, so render
  efficiently (lazy images, `content-visibility:auto` or incremental rendering).
- Mobile web polish: `<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">`, a
  `theme-color` meta, `100dvh`, safe-area insets, ≥44px touch targets, `-webkit-tap-highlight-color:transparent`,
  `overscroll-behavior` where sensible, pointer-based long-press (~450 ms) for selection modes, no 300 ms delays.
- The kit injects a prototype overlay (a small handle on the **left edge, mid-height**, plus a bottom sheet with the
  version switcher and feedback form) in a Shadow DOM at z-index 2147483000. Do not build your own version switcher,
  and keep your z-indexes below that.

## Kit essentials (`shared/kit.js`)

- Data: `WNA.stories()`, `WNA.story(id)`, `WNA.tabs()`, `WNA.filterStories({tab, query, sources, tags, sort, asc})`,
  `WNA.SORTS`, `WNA.sourceCounts(list)`, `WNA.tagCounts(list)`, `WNA.chapters(id)` (each chapter has `downloaded`,
  `audio`, `index`), `WNA.chapterText(id, chapterId)` (paragraphs), `WNA.sentences(id, chapterId)`,
  `WNA.authorNovels(id)`, `WNA.session` (saved "continue listening" position), `WNA.sources`, `WNA.stats`.
- Derived: `WNA.forecast(story)` (next-chapter estimate, state due/expected/late/unknown), `WNA.scoreTrend(id)`,
  `WNA.patreonLine(story)`, `WNA.coverage(story)` (downloaded segments and bookmark position, as fractions),
  `WNA.trendSeries(id)` (chart series), `WNA.fmt.*` formatters.
- Router: `WNA.router(fn)` calls `fn({name, params, query, path})` on every hash change. Use `WNA.go('/story/' + id)`
  and `WNA.back()`. You **must** support every route in the route table at the end of `SCREENS.md`, so the version
  switcher can land on the same screen in your version. If your IA merges screens, map the route to the closest view
  (for example, `#/narration` opens your "Listen" tab).
- Simulations, each with `.on(fn)` and an unsubscribe:
  - `WNA.player`: sentence-level TTS. `load(storyId, chapterId, {index, play})`, `toggle`, `skip(±sec)`,
    `next/prevChapter`, `cycleRate`, `setSleep`, `stop`, `state()` (with sentences, index, progress, remainingSec,
    history). Use it for the mini player, Now Playing, and **live sentence highlighting in the Reader**.
  - `WNA.downloads`: queue with live progress and statuses, a blocked-site card, a failed item, and actions.
  - `WNA.narration`: audio generation queue with a live generating job, failed job, computers, keep-ahead,
    generated audio, and `chapterStatus(storyId, chapterId)`.
  - `WNA.ai`: cover versions, cover and description generation jobs, models, spend.
  - `WNA.settings`: mutable settings objects (TTS, reader, downloads, app, cleanup, backups).
- Prefer the story `rr_141704` (Gunwitch, which has real chapter text and a saved session) for demo flows. Chapter
  text exists for `rr_141704`, `rr_164525`, `rr_100678`; other chapters reuse real text from that pool.

## What to build (priority order; all required)

1. **Library**: tabs, search, source and tag filter chips, sort, list and cover views, selection mode (long-press)
   with Move and Delete, and the "Continue listening" entry.
2. **Story Details**: header (cover, title, author link, badges, forecast, score and trend, coverage bar with
   bookmark), Sync (simulated), Download remaining (feeds `WNA.downloads`), EPUB, Patreon card, description,
   tags, chapter list with search, filters and bookmark toggles, and the overflow actions.
3. **Reader** with live TTS highlighting and auto-scroll, reader settings (font size), bookmark, chapter navigation,
   and the docked player. **Mini player** on all other screens. **Now Playing** with full transport, speed, sleep
   timer, history, and "Read along".
4. **Audio generation** hub and **Chapter audio** per story (statuses, filters, selection, menus).
5. **Downloads** queue (sections, live progress, site-check card, failed card, expand chapters, menus).
6. **Settings** hub and **Voice & Speech** (fully interactive controls).
7. **Add Story** (simulated fetch that ends on a Details page), **Select Chapters**, **Trends** (real SVG or canvas line
   charts from `WNA.trendSeries`), **AI Controls** (cover versions, generate, description draft).
8. Secondary, one good representative each: AI Settings, Data & Backup, Manage Tabs, Text Cleanup, Notifications,
   Download Settings, App Updates, Author Novels.

## Quality bar

This is a design exploration, so craft matters most: typography, spacing rhythm, hierarchy, motion, empty and
loading states, and how screens feel in the hand. Make it feel like a real, shipped, premium app, not a wireframe.
Keep the copy concise and consistent with `SCREENS.md` (you may rewrite copy where your design calls for it).

## Self-QA (do this, repeatedly)

The server runs at `http://127.0.0.1:8737/`. Take headless screenshots with:

```
design/prototypes/tools/shot.sh '<version-id>/index.html#/library' design/prototypes/<version-id>/_shots/library.png 412 915
design/prototypes/tools/shot.sh '<version-id>/index.html#/story/rr_141704' design/prototypes/<version-id>/_shots/story-wide.png 884 1000
```

It prints JS console errors. View the PNGs with the Read tool and fix what looks off. Cover at least: library,
library in cover view (if it's a route or state you can deep-link, for example `?view=cover`), story, reader,
player, narration, narration story, queue, settings, settings/tts, add, select, trends, ai, at phone width, plus
library and story at inner-screen width. Headless screenshots cannot tap, so add query-param hooks if you need to
capture states, for example `#/reader/rr_141704/2800285?play=1` starts playback so the highlight shows. Also run
`node --check` on your JS files.

## Deliverables

- The working prototype in your folder.
- `README.md` in your folder (≤ 40 lines): concept, palette, type, key interaction ideas, and notes for porting to
  native Android.
- Final message: what you built, the design decisions that matter, any deviations, and known gaps.
