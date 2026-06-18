# Emulator QA - 2026-06-18

## Test environment

- Revision: `aaf858b`
- Device: `webnovel_api36` AVD (Pixel 8, Android 16 / API 36)
- Package: `com.vinicius741.webnovelarchiver.nativeapp.debug`
- Test state: clean app data, notification permission allowed
- Story: `https://www.royalroad.com/fiction/172380/unsealed-words-sounds-in-silence-narutooc`

The physical phone was not used.

## Result

The current debug build installed and launched successfully. The supplied Royal Road story imported into a newly created `QA Reading` tab, remained in that tab after a force-stop/relaunch, downloaded all six chapters, generated an EPUB, and opened downloaded chapters in the reader. No app crash appeared in Android's crash buffer.

No high- or medium-severity functional defect was reproduced in the tested path. Three low-severity UI issues were found.

## Issues found

### 1. Singular novel counts use plural grammar

Severity: Low

Reproduction:

1. Create a custom tab.
2. Add exactly one novel to it.
3. Open Settings > Manage Tabs.
4. Observe `1 novels` beside the tab.
5. Open Library > Select, select the one novel, and tap `Move 1 Selected`.
6. Observe the dialog title `Move 1 Novels`.

Expected: `1 novel` and `Move 1 Novel`.

Related code:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/SettingsScreen.kt:280`
- `android/app/src/main/java/com/vinicius741/webnovelarchiver/LibraryScreen.kt:797`

### 2. Configured tabs are hidden while the library is empty

Severity: Low

Reproduction:

1. Start with an empty library.
2. Create `QA Reading` in Settings > Manage Tabs.
3. Return to Library.
4. Observe only the global empty state; the `All` and `QA Reading` tab controls are absent.
5. Add the first novel and return to Library; the tab controls now appear.

Impact: tab creation appears not to have affected the Library until a novel exists. The add-story form still allows assignment to the hidden tab, so this does not block the workflow.

Likely cause: `showLibrary()` returns immediately after rendering the empty state, before loading or rendering tabs.

Related code:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/LibraryScreen.kt:56`

### 3. Empty Select Chapters screen retains active-looking no-op controls

Severity: Low

Reproduction:

1. Download every chapter of a novel.
2. Open the story menu and select `Select Chapters`.
3. Observe the correct `All chapters are already downloaded` empty state.
4. Observe that `Select All` and `Deselect All` remain visually active even though there are no selectable chapters.

Expected: hide or disable selection controls when the downloadable chapter list is empty. The bottom download action is already disabled correctly.

Related code:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/DetailsScreen.kt:414`

## Supplied-story and tab test

1. Cleared only the debug package's simulator data.
2. Created custom tab `QA Reading`.
3. Entered the supplied URL on Add Story.
4. Selected `QA Reading` in `Save to tab` before fetching.
5. Import succeeded with the expected title, author `Lessgently`, cover, description, tags, rating, and six chapters.
6. Library showed the story under both `All` and `QA Reading`.
7. Force-stopped and relaunched the app; the story, custom tab, and selected tab persisted.
8. Downloaded all six chapters. Each chapter showed `Available Offline`, and Downloads showed `6 / 6`.
9. Generated `unsealed_words_sounds_in_silence_naruto_oc_Ch1-6.epub`; the file was present in app storage.
10. Opened Chapter 1 offline and navigated to Chapter 2 successfully.

## Additional coverage

Passed manual checks:

- Fresh launch and Android 13+ notification permission prompt
- Empty Library and Add Story screens
- Tab creation and tab picker
- Royal Road metadata fetch
- Story details, description, tags, cover, and chapter list
- Foreground download completion for six chapters
- Downloads completed-state screen
- EPUB generation and file persistence
- Offline reader, next-chapter navigation, and reader settings dialog
- Select Novels checkbox, count updates, Select All, and move-target dialog
- Select Chapters downloaded-only empty state
- Settings navigation and Text Cleanup controls

Not covered in this pass:

- Scribble Hub import
- TTS playback/notification controls
- Backup import, export, and restore document-picker flows
- Folded/large-screen layouts and rotation
- Failure injection for offline, HTTP error, retry, pause/resume, or interrupted downloads
- Archive snapshot creation and restore

## Automated checks

- `./gradlew :app:assembleDebug --console=plain`: passed
- `./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain`: 244 tests passed, 0 skipped, 0 failed
- `./gradlew :app:lintDebug --console=plain`: passed with 95 existing warnings and no lint errors

The largest lint groups are `UnusedResources` (23), `UseKtx` (17), `IconExtension` (15), and `SetTextI18n` (14). Correctness warnings also include two unsupported internal inset-resource accesses, six private AppCompat resource accesses, and one locale-sensitive format call.

## Log observations

- Android crash buffer: empty for the app during the run.
- No uncaught app exception was observed.
- App-scoped logcat contained emulator/WebView graphics warnings and one Chromium renderer termination when leaving the reader. It caused no visible failure and was not counted as a confirmed defect, but it is worth monitoring if reader exits become unstable.

## Regression checks from 2026-06-16

The following previously documented issues are fixed in this build:

- Select Novels now preserves checkbox state and updates `Move/Delete` selection counts.
- Select Chapters now explains that all chapters are downloaded instead of showing an unexplained blank area.
- Text Cleanup top controls render fully and are usable.
