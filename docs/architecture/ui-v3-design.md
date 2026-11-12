# UI v3 "Restructured" design

The app's UI follows the v3 "Restructured" redesign chosen in November 2026 from seven HTML
prototypes (`design/prototypes/`, served on the LAN by `design/prototypes/server.py`). The v3
prototype (`design/prototypes/v3-restructured/`, with its `README.md`) is the visual reference
for new screens. This page records the rules the native app follows.

## Structure

- **Five destinations** (`ShellDestination`): Library, Updates (`AppRoute.Updates`), Listen
  (`AppRoute.Narration`), Downloads (`AppRoute.Queue`) and Settings. A destination tap starts the
  history over the Library root (`ShellNavigationPlanning.historyFor`), and destination app bars
  show no back arrow. The highlighted destination is the newest destination root in the history,
  so a novel opened from Updates stays under Updates.
- **App shell** (`ui/AppShell.kt`): every screen renders into `frame`, which ends above a solid
  bottom shelf holding the docked TTS mini player and the bottom navigation bar. On screens at
  least 600dp wide (the Fold's inner display) the destinations move to a rail on the right edge,
  under the thumb that holds the device, with an Add story button on top. The shelf owns the
  gesture-bar inset, so screens never pad for it and no content can sit under the player or the
  bar. A 20dp fade at the frame's bottom shows rows sliding under the shelf.
- **Hidden chrome**: Reader, ChapterSelection, Player, AddStory and Working hide the navigation;
  Reader, Player and Listen hide the mini player (Listen leads with its own Now playing card).
  Selection modes call `shell.setNavigationSuppressed(true)` while their action bar shows.
- **Story page**: Overview (`Details`), Chapters (`Details(chapters = true)`), Audio
  (`NarrationStory`), Trends and AI (`AiControls`) are tabs of one page. Switching tabs uses
  `navigator.replace`, so Back leaves the story. On the inner screen the Overview hero sits in a
  left pane next to the tab content.
- **Badges**: Updates shows how many novels may have a new chapter
  (`UpdatesPlanning.badgeCount`), Downloads how many novels have chapters waiting
  (`ShellBadgePlanning`), and Listen a dot while chapter audio generates.

## Components

- **Palette** (`ThemeManager.colors`): warm obsidian surfaces (`elevation1..5`), gold primary,
  copper secondary for Patreon, generation and lateness, sage tertiary for done/completed, a
  hairline `line` color for borders, and the ivory `bookmark` color. The reading bookmark is
  never red.
- **Cards** are flat with a hairline border (radius 18). Buttons are 48dp pills (`Btn.FILLED`,
  `TONAL`, `SURFACE`, `OUTLINED`, `TEXT`, `ERROR`). Section headers are small uppercase gold labels.
- **Bottom sheets** (`showBottomSheet`, `makeSheetRow`, `makeSheetSection`) replace centered menus
  and pickers; `showStyledOptionsDialog` already renders as one. Confirmations stay dialogs.
- **Steppers** (`makeStepper`, `stepperRow`) replace sliders for numeric settings: tap ± to nudge,
  hold to repeat (it speeds up), tap the number to type an exact value. Only playback seek bars
  stay sliders.
- **Covers** (`CoverImageView`, used by `makeCover`/`coverImage`) always show the whole image:
  odd-shaped art is letterboxed over a blur of itself. Tapping a cover opens the full-screen
  viewer (`showCoverViewer` / `showCoverDialog`), which swipes between versions and offers actions.
- **Segmented controls**: `makeSegmentedControl` (story tabs and AI) and `SegmentedSelector`
  (settings and cleanup).

## Owner feedback that shaped v3

These came from reviewing the prototypes on a phone and are binding for new UI:

- Prefer −/+ steppers with exact entry over sliders and over preset-only choices (for example a
  per-site download delay must be settable without presets).
- Covers are shown whole, never cropped, and the AI cover comparison must be large enough to
  judge (side by side, plus a full-screen viewer).
- The foldable's navigation rail belongs on the right edge.
- Floating buttons and bars must never cover scrolling content.
- Keep Now Playing's "Now reading" sentence card.
