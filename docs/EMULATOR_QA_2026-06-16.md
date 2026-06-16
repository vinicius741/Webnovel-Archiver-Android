# Emulator QA - 2026-06-16

Device: `webnovel_api36` AVD, Android 16 / API 36  
Package: `com.vinicius741.webnovelarchiver.nativeapp.debug`  
Build command: `cd android && ./gradlew :app:installDebug --console=plain`  
Launch command: `adb shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.MainActivity`

## Result

The debug build installed and launched successfully in the simulator. No app crash was seen in `adb logcat -b crash -d` during navigation.

Covered screens:

- Library
- Browser / import WebView
- Add Story
- Downloads / queue
- Settings
- Download Settings
- Source Overrides
- Voice & Speech
- TTS voice picker
- Manage Tabs
- Text Cleanup
- Select Novels
- Story Details
- Story overflow menu
- Select Chapters
- Reader
- Reader settings
- Read Aloud panel

## Issues Found

### 1. Select Novels does not preserve or apply selection state

Severity: High

Repro:

1. Open Library.
2. Tap the Select action in the app bar.
3. Tap the story row or its checkbox.
4. Observe that the checkbox can visually toggle, but the bottom action still says `Move 0 Selected`.
5. Tap `Select All`.
6. Observe that the story is not selected and the count still says `Move 0 Selected`.

Impact:

Bulk move/delete cannot be used reliably from the Select Novels screen.

Likely cause:

`showLibrarySelection()` creates `selectedIds` as a local `mutableSetOf<String>()`. `Select All` mutates it and then calls `showLibrarySelection()`, which recreates an empty set. Row checkbox changes also do not rerender the bottom action count.

Related file:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/LibraryScreen.kt`

### 2. Select Chapters opens with an empty list when all chapters are already downloaded

Severity: Medium

Repro:

1. Open `Emie Ascended`.
2. Open the story overflow menu.
3. Tap `Select Chapters`.
4. Observe an empty content area with `Download 0 Selected`, despite the story having 27 chapters.
5. Tap `Select All`; nothing changes.

Impact:

The screen looks broken because there is no empty-state explanation. The regular story detail chapter list still shows the chapters, so the data is present.

Likely cause:

`showChapterSelection()` filters to `story.chapters.filter { !it.downloaded }`. When all chapters are downloaded, the list is empty and no empty-state message is rendered.

Related file:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/DetailsScreen.kt`

### 3. Text Cleanup top controls are clipped/collapsed

Severity: Medium

Repro:

1. Open Settings.
2. Scroll to Data.
3. Tap `Text Cleanup Rules`.
4. Observe that the expected top controls are not fully visible; the first action row appears clipped, and the screen starts directly into the sentence list.

Impact:

The user cannot clearly access the intended top controls such as cleanup export/add controls from the initial Text Cleanup view.

Expected:

The `Cleanup Rules`, `Export JSON`, `Sentences`, and add-sentence controls should render normally above the sentence list.

Related file:

- `android/app/src/main/java/com/vinicius741/webnovelarchiver/CleanupScreen.kt`

## Notes

- Browser loaded `https://www.royalroad.com/home` successfully in the WebView.
- Story detail, normal chapter list, reader content, reader settings, read-aloud panel, and next-chapter navigation worked.
- Settings nested pages rendered without crashes.
- The Settings `Data` header is partially visible at the first scroll position, but the section is reachable after scrolling; this was not counted as a bug.
