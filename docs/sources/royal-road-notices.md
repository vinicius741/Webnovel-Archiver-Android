# Royal Road Inserted Notices

Royal Road injects one anti-piracy notice into each chapter, such as "Stolen story; please
report." The wording varies too much for an exact-match list to keep up, so the app finds the
notice by its markup and uses the Text Cleanup sentence list only as a fallback.

## The marker

The notice sits in a bare `<span>` with a single `class` attribute. The class is 44 characters of
Base64 that decode to `r` followed by 32 lowercase hex digits. Paragraph classes look similar but
decode to `rs` plus hex, so they never match. `source/RoyalRoadNoticeMarker.kt` owns this rule.

Measured on 13,875 stored Royal Road chapters from 160 novels (2026-11-03):

- 13,802 chapters had exactly one marker, and none had more than one.
- All 498 markers still holding text held a notice or leftover punctuation, never story text.
- The 73 chapters without a marker were 28 Patreon copies and 45 source chapters, 38 of them in one
  novel. The longest unbroken run of source chapters without one was 30.
- The marker sat a median 41% of the way through the chapter text, and 98% were between 28% and 54%.

These counts come from stored chapters, which had already been through Jsoup and cleanup. One live
chapter opened in a browser on 2026-11-03 had the same shape: a single bare marker span, 42% of the
way through, holding a notice. The page's CSS hides that class with `display: none`, and nothing
else in the chapter was hidden. A plain HTTP request got Cloudflare's challenge page, so this check
used the browser's rendered page, which is also what the app's Cloudflare render path reads.

## Behavior

- **Downloads:** `RoyalRoadProvider.parseChapterContent` empties the marker. The sentence list and
  regex rules still run afterward, so custom sentences keep working on new chapters.
- **Apply Text Cleanup:** a chapter with a marker skips the sentence list and is only parsed when a
  download-scoped regex rule is active. Blank markers are left alone, so clean files are not rewritten.
  Chapters without a marker, including Patreon copies, use the full sentence list.
- **The marker is kept, emptied.** Removing it would make later cleanup treat the chapter as unmarked
  and fall back to the slow sentence pass.

A sentence added to the list for non-notice text is therefore applied to new Royal Road downloads,
but Apply Text Cleanup does not remove it from existing chapters that have a marker.

## If Royal Road changes the markup

`InsertedNoticeMarkerMonitor` counts consecutive Royal Road downloads without a marker. At 50 the
download service posts one notification that opens Text Cleanup. The count is in memory, so it resets
when the process restarts. Until the rule in `RoyalRoadNoticeMarker` is updated, chapters are cleaned
by the sentence list alone, and nothing is removed by mistake.

Another source can opt in by overriding `SourceProvider.insertedNoticeMarker`.
