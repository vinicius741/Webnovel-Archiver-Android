# v5 · Home feed / immersive

**Concept.** Stop treating the app as a list manager. It opens on a personal **Home** that answers "what should I read or listen to next, and what's new?". The Library becomes a fast, search-first browsing space. Story pages, the reader and Now Playing are immersive and take their colour from the cover.

**Navigation.** A floating pill nav: **Home · Library · Listen · Downloads**. The active item expands into a labelled capsule. The mini player floats above it. Settings and Add are round glass buttons on Home (Add is also in Library). Switching tabs replaces history, so Back from any tab lands on Home. Sheets and dialogs push a history entry, so the Android back gesture closes them first. `#/` and `#/home` open Home; every route in SCREENS.md works (`#/narration` is the Listen tab).

**Home.** A hero "Continue" card with a blurred-cover backdrop, a progress ring on the play button and a live two-line transcript, plus one-tap resume. Below it: live strips for Downloading now, Generating audio and site checks; library stats; **Likely out now** (forecast `due`, one-tap Sync, then "Get N" to download); a 7-day **Release radar** built from the forecast cadence; and **Up next** carousels per tab.

**Palette.** Deep, slightly cool dark base `#09090c`, surfaces `#121217 / #18181f / #20202a`, and hairlines at 7% white. The accent is sampled from each cover: a canvas downsample, saturation-weighted hue buckets, and `WNA.hue` as the fallback. It yields `--acc` (L72%), `--acc-soft`, `--amb` and `--amb-deep` for backdrops. The default accent is amber (the old gold). Status colours: ok `#7be3ae`, warn `#ffc56e`, bad `#ff8a86`.

**Type.** Inter Tight (800, tight tracking) for display and numbers. Manrope for body and reading text. Material Symbols Rounded for icons.

**Shape and motion.** Radii are 30, 22, 16 and 12, and pills. Glass (blur 22 to 30, white border at 9%) is used only for floating chrome: dock, sheets, top bars over art, action bars. Screens slide or fade with a spring curve, and Now Playing rises from the bottom.

**Key interactions.**
- Story: full-bleed cover header with parallax. The cover tint bleeds into the page gradient, and the title condenses into a glass top bar. A sticky action bar holds Continue (bookmark) and Listen. Action tiles: Sync, Get N, Select, EPUB, Audio, AI. On the inner screen the page splits into two panes (info on the left, chapters on the right).
- Reader: in karaoke-style highlighting, spoken sentences dim, the current one glows in the accent tint, and the page auto-scrolls. If you scroll away, a "Back to voice" pill appears. Double-tap a paragraph to read from there. The docked player has chapter skip.
- Now Playing: a drifting blurred-cover and blob background. The current sentence fills left to right in sync with `WNA.player` timing, and the next ones are previewed. "Transcript" mode turns it into a scrollable, tappable transcript.
- Long-press (450 ms) selection in Library (Move/Delete) and Chapter audio. Long-press and drag selects a range in Select Chapters. Drag the handle to reorder Up next in Downloads.

**Debug hooks.** `?view=cover|list`, `?select=1`, `?focus=1`, `?smart=due` and `?tab=<id>` on library; `?play=1` on home, reader and player; `?lyrics=1` on player; `?menu=1` on story; `?demo=1` on select.

**Porting notes (Compose).** Dock = `Box` with a pill `NavigationBar` substitute and a mini player above it. Palette = `androidx.palette` (vibrant or dominant swatch) mapped to the same 4 tokens per story, crossfaded. Blur = `Modifier.blur` / `RenderEffect` on API 31+, with a pre-blurred bitmap fallback. Karaoke = `AnnotatedString` with a `Brush` sweep driven by the player's sentence duration. Release radar = forecast cadence grouped by day. Sheets = `ModalBottomSheet`. Everything uses standard components, so no custom rendering is required.
