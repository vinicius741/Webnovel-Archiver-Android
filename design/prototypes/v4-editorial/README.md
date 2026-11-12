# v4 · Editorial

**Concept.** Webnovel Archiver as a private library and literary quarterly. Ink-black stock, bone-white type, and one
burnished-copper accent, used the way a printer uses a rubric: on drop caps, ribbons, active rules and the primary action.
Covers stand on shelves like books, the story page reads like a magazine feature, and the reader sets chapters like a novel.

**Palette.** Ink `#0D0B09` (page) → paper `#14110E / #1B1713 / #241F19` (raised surfaces); bone `#EDE3CF` / `#BCB09A` /
`#877C69` (text tiers); copper `#D08A5C` (accent; `#E9AC80` for copper text); crimson `#E5636F` only for errors and content
warnings. Separation comes from hairline rules (`rgba(236,224,202,.12/.22)`), not shadows or tinted cards.

**Type.** Fraunces (display: titles, numerals, pull quotes, italics as the "voice"), Newsreader (body and the reader:
justified, hyphenated, old-style figures, drop cap, small-caps first line), and Instrument Sans for tracked uppercase
labels and metadata. Every root screen opens with a kicker, a display title and an italic dek.

**Navigation.** A typographic running foot (Library · Listen · Downloads · Settings) on root screens, plus a **Contents**
sheet (book icon, top-left) that lists every section and settings page as a table of contents with roman numerals and
dotted leaders. Pushed screens use a minimal bar with Back. The mini player floats above the foot; the Reader docks it.

**Key ideas**
- Library: shelves (tabs) as serif words with an underline rule; swipe the list to change shelf. **Shelf view** (covers with
  spine shading on a plank) or **Index view** (catalog rows; alphabet letters appear when sorted by Title). Search, Refine
  (source + subject chips) and Sort live on one "catalog card". Long-press a book for selection, Move and Delete.
- Story: centred jacket, display title, byline link, forecast dek, a three-column stat strip (Score · Chapters ·
  Followers → Trends), coverage bar with a copper bookmark ribbon, pull quote + drop-capped description, small-caps
  Subjects (tap = filter the library), Patronage, a **colophon** of leader rows, and **Contents** as a table of contents
  with dotted leaders and ribbon bookmarks. At ≥820px it becomes a two-page spread with a sticky left page.
- Reader: chapter opener (story kicker, "Chapter Three", italic title, fleuron), ink-wash TTS highlight with a copper
  underline, already-read sentences dimmed, auto-centring, double-tap any sentence to read from there, bars hide on scroll.
- Now Playing: an audiobook "Currently reading" passage (previous / current / next sentence) is the hero, not the cover.
- Listen, Downloads, Settings: ruled ledgers with roman-numbered section heads; settings rows use leaders to their values.

**QA hooks.** `?view=index|shelf`, `?tab=all|<name>`, `?select=1`, `?sheet=sort`, `?filters=1`, `?contents=1`,
`?listen=1` (any route: start the saved session), `?menu=1` / `?toc=1` (story), `?play=1` + `&shot=1` (reader; shift
instead of scroll for headless captures), `?play=1` / `?sheet=sleep` (player), `?demo=1` (select), `?group=patreon`.

**Porting notes (Compose).** Tokens map to a single dark `ColorScheme` (no light scheme). Fonts ship as downloadable
fonts; use `fontFeatureSettings = "onum"` for old-style numerals and Fraunces `opsz`. Leaders = `Row` with a dotted
`drawBehind` spacer. Shelf plank = `drawBehind` under each `LazyVerticalGrid` row; spine shading = horizontal gradient
overlay. Reader: `Text` with `hyphens = Hyphens.Auto`, `TextAlign.Justify`, and `SpanStyle` background for the active
sentence. Sheets = `ModalBottomSheet`; the Contents sheet replaces the current app-bar icon row.
