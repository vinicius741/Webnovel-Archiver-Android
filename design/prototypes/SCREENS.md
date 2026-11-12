# Webnovel Archiver — screen & feature inventory (for UI prototypes)

Source of truth: the native Kotlin app under `android/app/src/main/java/com/vinicius741/webnovelarchiver/`.
Reference screenshots of the current app (Galaxy Fold-style inner screen, dark "Obsidian" theme) are in
`design/prototypes/reference/`. `{x}` marks runtime values.

## Current visual language (v0 baseline)

- Single dark theme "Obsidian". Tokens: primary `#C9A84C` (gold), onPrimary `#3D2E00`, primaryContainer `#574400`,
  onPrimaryContainer `#E8D597`, secondary `#D4A88C`, secondaryContainer `#663C21`, onSecondaryContainer `#F5DED3`,
  tertiary `#D4B85C`, tertiaryContainer `#574800`, error `#FFB4AB`, errorContainer `#93000A`, background `#12110F`,
  surface `#1C1A17`, surfaceVariant `#252320`, onSurface `#E8E2DA`, onSurfaceVariant `#B0A99A`, outline `#3D3A36`,
  outlineVariant `#4D4944`, elevation1..5 `#1C1A17 #201E1B #252320 #2A2725 #302D2A`.
- Shapes: card radius 12, button 8, dialog 16, chip 8, search 8. Spacing 4/8/12/16/24. Roboto, MD3 type scale
  (headline 24, title-large 22, title-medium 16, body 14, label 12/11).
- Top app bar on `elevation2`: back arrow, title (2 lines max) + muted subtitle, trailing icon actions. No bottom nav,
  no drawer: one navigation stack rooted at Library; Library app-bar icons go to Narration, Downloads, Settings.
- Material-ish icons, filled gold buttons, tonal (brown secondaryContainer) buttons, text buttons.
- Options menus are centered dialogs ("More options" titled list with section labels), not bottom sheets.
- Large screens: Library list is 2 columns ≥600dp, 3 ≥840dp; Details goes two-pane (360dp info panel left, chapter
  list right); Settings content capped at 720–840dp.

## Global: TTS mini player

Floating rounded card (radius 16, elevation) at the bottom of every screen except Reader (docked instead of chapter
nav) and Now Playing. Row: play/pause (gold), chapter title / "{5 min left} · {story title}", chevron-up "Open
player", X "Close player". Thin gold progress line under the row (sentence progress). Tap → Now Playing. When nothing
is loaded, the Library shows a "Continue listening: {story} · {chapter}" tonal card instead (dismissible).

## 1. Library (home)

- App bar: "Library", subtitle "282 novels". Actions: view toggle (Cover view / List view), headphones "Narration",
  download "Downloads", gear "Settings". FAB "+" (Add) bottom-right.
- Tab bar (horizontal scroll, underline style): user tabs (Starter, Plan, Reading, Following, Paused) then "All"
  (plus "Unassigned (N)" first if any). Swipe between tab pages.
- Filters block (collapsible header "Filters" with chevron; "•" + "Clear filters" when active):
  search field "Search stories" (title/author) + sort pill "Updated ↓"; chip row: source chips with globe icon
  "RoyalRoad (30)", then tag chips "Female Lead (30)", multi-select AND, ordered by count.
- Sort dialog "Sort by": Default (Smart), Title, Last Updated, Date Added, Chapter Count, Score, Patreon Earnings,
  Patreon Paid Members; direction row Ascending/Descending.
- List card: 80×120 cover; title (2 lines); "by {author}"; badge row: source name (gold text), publication pill
  (Completed/Ongoing/Outdated/Hiatus), "Source unavailable" pill, refresh glyph = "Next chapter may be out, sync to
  check"; ★ score; Patreon line "Patreon ~$1,234/mo · 56 paid"; tags (5) joined by " • "; footer "{dl} / {total}"
  + chapter coverage capsule bar with red bookmark pin.
- Cover grid: 2:3 covers, title, author, progress footer; overlays for new-chapter hint, AI sparkle, selection check.
- Selection mode (long-press): title "{N} selected", close X, "Select all"; checkbox circles on covers; bottom bar
  "Move" (→ dialog "Move N Novels": Unassigned + tabs) and "Delete" (confirm).
- Empty states: "Your library is empty" + "Add a story"; "No matches" — "Try clearing your search or filters.";
  "Nothing here yet".

## 2. Add Story

App bar "Add Story" / "Paste a story URL to import". URL field "Story URL" + paste button; "Save to tab" dropdown;
filled "Fetch Story" (→ "Fetching..." + spinner + status line "Starting..." → opens the new Details). Section "Or
browse a source": rows (globe disc, name, host, "Open →"): RoyalRoad royalroad.com, Scribble Hub scribblehub.com,
SpaceBattles forums.spacebattles.com, Sufficient Velocity forums.sufficientvelocity.com, FanFiction.net
fanfiction.net, Archive of Our Own archiveofourown.org.

## 3. Story Details

- App bar: story title, "by {author}", "More options" (⋮) → dialog: Full Sync, Edit Patreon Link, [EPUB] EPUB Settings,
  EPUB Files, Apply Text Cleanup, [Explore] Trends, AI Controls, Narration audio, — Delete Novel (red, confirm).
- Info panel: cover 150×225 (tap = zoom); title; author (underlined → Author Novels); badges: source + external link,
  publication status, "Original"/type; release forecast line ("Next chapter expected in 2 days · usually every 2 days" /
  "Next chapter may be out · sync to check" / "Later than usual · …"); ★ 4.44 with trend arrow (tap → Trends);
  coverage "{dl} / {total}" + capsule bar + bookmark pin; metric chips ("85.2K Followers" → Trends), content-warning
  chips (red), "Updated Nov 9, 2026".
- Actions: filled "Sync Chapters" (→ "Checking Source..." + spinner + message); "Download Remaining (N)" / "Download
  All" + text "Select Chapters"; live download banner ("Downloading: {chapter} (3/12)", progress bar, "Go to
  Downloads"); tonal "Generate EPUB" (→ "Generating..."), outlined "Read EPUB".
- Patreon card: title "Patreon", edit pencil, open-external; "92 Paid members", "$290–$828 Estimated amount";
  "Updated Nov 9, 2026 · per month · tap for trends"; row "Early-access chapters" — "Not linked · tap to choose a
  Patreon collection".
- Description (truncated, "Read more"/"Read less", "Listen"); tags chips.
- Chapter list: search "Search chapters"; chips All / Downloaded / From Bookmark (209) / Removed from source (N);
  rows: status dot, title, "Index 3 • Audio • Available offline" (or Downloading…/Queued/Download failed), trailing
  bookmark toggle. Tap → Reader.
- Compact: one scroll with "Hide details" toggle. Wide: two-pane.

## 4. Select Chapters

"Select Chapters" / story title. Hint "Tap chapters individually, or long-press one and drag to select or clear a
range." Buttons "Select Range" (→ "Tap the first chapter in your range." / "Chapter 5 is the start. Tap the last
chapter."), "All", "Clear". Rows: checkbox, index, title (not-downloaded chapters only). Sticky "Download N Selected".
Empty: "All caught up".

## 5. Trends

"Trends" / story title. Header title, by author, "{n} snapshots · since {date} · updated {date}". Range chips 7D 30D
90D 1Y All time; group chips All metrics / Score / Patreon / Engagement. Series cards: title, "Current 4.47K (+155
since last sync)", "+230 in the last 30 days · range 4.24K–4.47K", 180dp line chart with area fill. Empty: "No trend
data yet".

## 6. AI Controls

"AI Controls" / story. Tab chips Cover | Description (• when unseen result).
Cover: "New cover ready" card (Current vs New thumbs, "OpenRouter: $0.04 · 2 calls", "Use new cover" / "Keep
current"); "Covers" strip (Source, AI 3, AI 2, AI 1; in-use outlined + check; "New" badge; hint "Tap a cover to use
it. Long-press to see its prompt."); "Make a new cover": "Cover context chapters · Automatic", multiline "Image
prompt (optional)", "Write prompt with AI", "Generate cover" (progress: "Writing image prompt..." → "Painting
cover..."); footer "Image: {model} · Prompt: {model} · Change in AI Settings".
Description: context chapters row, AI-generated badge + text, "Show AI description in Details" checkbox, "Generate
Description with AI"; draft preview card with Apply / Discard. Global floating "AI job chip" (spinner + "Generating
cover..." + story, dismiss).

## 7. Reader

App bar: chapter title, "3 / 210"; Bookmark, "Read aloud" (speaker; dialog "Continue listening: …" / "Listen from
this chapter"), ⋮ Reader settings. Body: chapter text (sans 18px, line-height 1.6); while TTS plays, the current
sentence is highlighted (gold ~20% tint) and auto-scrolls to center; double-tap a paragraph to read from there.
Bottom: chapter nav bar (Previous chapter, "3 / 210", Next chapter) or, while reading aloud, the docked mini player.
Reader Settings panel: font "A−  100%  A+" (80–160%), "Dark reader On", "Voice settings", "Copy chapter text".

## 8. Now Playing (Player)

"Now Playing" / "Voice: Bella · Kokoro", action "Chapter audio". Cover, story title, chapter title, "Chapter 3 /
210"; seek bar; elapsed / "-4:22" remaining; transport: Previous chapter, Back 15s, big Play/Pause, Forward 30s, Next
chapter; chips: speed "1.8x" (cycles 0.75 1 1.25 1.5 1.75 1.8 2.0), "Timer: Off" (Sleep Timer dialog: Off, 15, 30,
45, 60 minutes, End of chapter), "History" ("Go back to" list), "Stop"; tonal "Read along".

## 9. Audio generation (Narration hub)

"Audio generation", ⋮ Queue options (Pause/Resume generating, Retry failed, Clear the queue). Live banner (secondary
card): "Generating · 40%" / "Generating on {computer} · 40%", "chapter · story", progress bar, "12 of 80 sentences ·
3.2× real time · ~4 min left · 6 chapters after this", Pause/Resume. Idle: "Nothing is generating".
Sections: "Queue · N chapters" (hint about order; per-story cards: title, "N queued · M failed", up to 5 job rows
"Queued · Bella" / "Generating · 40% · 12 of 80 sentences" / "Failed · {error}", "and N more", "Generate this story
first"); battery card "Generating with the screen off" + Allow; "Generate on" (text; chips "This phone" + computers;
computer rows name/address; "Add a computer" dialog Name/Address/Token); "Keep ahead while listening" chips Off / 1 /
3 / 5 / 10 chapters; "Audio uses 125 MB · 2.2 GB free"; "Generated audio" rows (story, "6 chapters · 45 min · 125
MB · Bella · Kokoro") → per-story.

## 10. Chapter audio (per story)

"Chapter audio" / story; actions Select chapters, Generation queue, ⋮ More (Generate everything not generated (N),
Generate this story first, Regenerate N in {voice}, Voice & Speech, Generation queue, Remove from queue, Delete all
audio). Banner (if generating). Filter chips "From bookmark (N)", "All (N)", "Not generated (N)", "Ready (N)".
Optional focus card for a chapter (status + Play / Generate now / Generate it next). Line "New audio uses Bella · 6
ready · 125 MB". Rows: title + colored status: "Not generated" (muted), "Queued · #3", "Generating · 42%" + bar,
"Failed · {reason}" (red), "Ready · 7:45 · Bella" (gold), "Text changed · regenerate to play" (red). Tap → chapter
menu (Play, Generate next, Retry, Generate now, Regenerate in {voice}, Open in reader, Delete audio). Long-press →
selection; bottom bar Generate / Regenerate / Delete.

## 11. Downloads (queue)

"Downloads"; actions Pause all / Resume all, gear "Download Settings", ⋮ (Retry failed chapters, Clear finished,
Cancel all downloads). Summary "4 novels · 128 of 420 chapters". Site-check card ("Verification needed" — "{Site}
needs a quick check" — "Verify now" / "Having trouble?"). Failed card ("3 chapters couldn't download" — "Retry all").
Sections "Downloading now", "Up next" (Drag to reorder), "Paused", "Finished · N novels" (collapsible, Clear). Novel
card: drag handle, title, "{Site} · X of Y chapters", colored status ("Downloading chapter 14", "Next chapter in 12
seconds", "Site asked to slow down · resuming in 30 seconds", "After {novel}", "Paused", "2 failed", "Done"), 4dp
progress, inline pause/resume/retry, ⋮. Expand → chapter rows (dot, "14. Title", status, retry). Empty: "Nothing
downloading" — "Chapters you download will appear here while they download."

## 12. Settings hub

"Settings". Appearance: "Large Screen Layout" chips Auto / Cover / Inner. Reading & Audio: Voice & Speech; Text
Cleanup Rules; AI Settings. Then Notifications; Manage Tabs; Data & Backup; App Updates ("Version 1.0.21-native").

## 13. Voice & Speech

"Saved TTS session" card ("Chapter 3: Regional Rudeness Trials (sentence 17)", Resume TTS / Clear Session); "Profile"
card ("Based on "Google", with changes not saved to it.", Switch / Save as profile / Delete); Rate field 1.8 (Pitch
hidden for Kokoro); Engine "Kokoro (built in)"; Voice "Bella (American, female)"; "Kokoro voices" card ("Installed.
Uses 369 MB on this phone.", Test speed / Last results / Remove); switches "Replay a little after long breaks", "Say
chapter titles", "Headphone and lock-screen skips move whole chapters"; "Pauses" sliders (Extra at commas and phrases
50 ms, Between sentences 300 ms, Between paragraphs 450 ms, At scene breaks 500 ms); "Try it" sample text + Play
sample / Reset text; "Save TTS".

## Secondary screens (one representative each)

- **AI Settings**: OpenRouter API key; Models (Description model, Cover image model, Chapter selection model,
  each with Reasoning pill); "Chapter scan limit"; "AI spend" (Today / This month / All time, Recent requests,
  Current OpenRouter key usage + Refresh); Save.
- **Data & Backup**: Backup (Export Backup, Import Backup, Create Full Backup, Restore Full Backup, Recover Chapters
  from Backup); Backup Files (cards with Share/Delete); Sources & Storage (Source Access Status "Ready • WebView
  …", Share Activity Logs, Share Source Access Logs, Patreon Account, Reset Source Web Session); Danger Zone (Clear
  Local Storage).
- **Manage Tabs**: intro; "New tab name" + Add; per-tab cards with count, up/down/rename/delete.
- **Text Cleanup**: mode tabs Full sentences | Regex rules; sentence input + Paste/Add; Saved sentences list;
  regex rule cards (name, Enabled · Read aloud and downloaded text, /pattern/flags, Edit/Disable/Delete); Add preset.
- **Notifications**: App Permission card (Allowed); Categories rows (Downloads, Text to Speech, Narration audio, AI
  Generation · Enabled).
- **Download Settings**: Parallel sources 2, Delay min/max (ms), Max chapters per EPUB 150; per-source overrides.
- **App Updates**: Installed version 1.0.21-native, "You have the latest version · Checked 5 minutes ago", Check for
  updates; "Check automatically" toggle.
- **Author Novels**: author name / "2 other novels in your library" list of story cards.

## Prototype route contract (all versions)

Hash routes so the version switcher can jump to the same screen in another version. Versions that merge screens map
the route to the closest equivalent.

| Route | Screen |
|---|---|
| `#/library` | Library (home) |
| `#/add` | Add Story |
| `#/story/{id}` | Story Details |
| `#/story/{id}/select` | Select Chapters |
| `#/story/{id}/trends` | Trends |
| `#/story/{id}/ai` | AI Controls |
| `#/author/{id}` | Author Novels (other novels by the author of story {id}) |
| `#/reader/{id}/{chapterId}` | Reader |
| `#/player` | Now Playing |
| `#/narration` | Audio generation hub |
| `#/narration/{id}` | Chapter audio for a story |
| `#/queue` | Downloads |
| `#/settings` | Settings hub |
| `#/settings/tts` `#/settings/ai` `#/settings/data` `#/settings/tabs` `#/settings/cleanup` `#/settings/notifications` `#/settings/downloads` `#/settings/update` | Settings sub-screens |
