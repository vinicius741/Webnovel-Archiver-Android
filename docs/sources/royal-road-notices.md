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
  regex rules still run afterward.
- **Apply Text Cleanup:** the marker empties the notice, then the sentence list and regex rules run
  on every chapter, marked or not. The list is not only notices: it also holds entries such as
  separator lines, and skipping it on marked chapters (as the first version did) silently stopped
  those from being removed. Patreon copies and other chapters without a marker get the same pass.
- **The marker is kept, emptied.** Blank markers are left alone, and text nodes nothing matched are
  not rewritten, so an already clean chapter serializes back to the same HTML and is not rewritten.

### Why the sentence list is cheap enough to run everywhere

The slow part of the old cleanup was not parsing the chapter. It was compiling each entry into its
own case-insensitive regex and running every one over every text node. `cleanup/SentenceMatching`
replaces that in two steps:

1. `SentenceList.candidatesIn` makes one pass over the chapter's HTML and looks for every entry's
   anchors, its runs of printable ASCII other than `& < > " '`. An entry whose anchors are not all
   present cannot be in the chapter. Requiring all of them matters: an entry's longest word alone
   ("support", "official") is in nearly every chapter. The scan skips the characters Jsoup's
   `TextNode.text()` drops (zero-width space, soft hyphen, NUL), and any entity Jsoup never writes
   (such as `&#112;`) makes every entry a candidate, since it could spell an anchor.
2. Only when an entry might be present is the chapter parsed. Each text node that holds a candidate
   then gets the whole list removed in order, by case-folded substring search.

This removes the same text as the old regexes, which a randomized test against the old pass checks.
The one deliberate difference: whitespace inside an entry matches a single space. The chapter text
is already whitespace-collapsed by Jsoup, so an entry with a double space or a line break used to
match nothing.

A marked chapter with no candidate and no active download-scoped regex rule is not parsed at all,
and a chapter nothing was removed from is never re-serialized.

Apply Text Cleanup on 107 chapters of one Royal Road novel with a 96-entry list, debug build on the
`webnovel_api36` emulator (2026-11-06). Debug builds run app code much slower than release, so
compare rows, not absolute times:

| Path | Time |
| --- | --- |
| Marker only, sentence list skipped (first marker version) | ~0.25 s |
| Substring search over every chapter, no candidate pass | ~2.8 s |
| Candidate pass, then substring search (current) | ~0.48 s |
| Current, plus the "Remove separator lines" preset | ~1.1 s |

On a desktop JVM the old one-regex-per-entry pass took ~1,850 ms for the same chapters, against
~60 ms now.

Separators drawn with a run of dashes vary in length from chapter to chapter, and a sentence entry
is an exact match. One dash entry left a dash run behind in 103 of the novel's 107 chapters. The
"Remove separator lines" regex preset removed all 461 separator lines.

## If Royal Road changes the markup

`InsertedNoticeMarkerMonitor` counts consecutive Royal Road downloads without a marker. At 50 the
download service posts one notification that opens Text Cleanup. The count is in memory, so it resets
when the process restarts. Until the rule in `RoyalRoadNoticeMarker` is updated, chapters are cleaned
by the sentence list alone, and nothing is removed by mistake.

Another source can opt in by overriding `SourceProvider.insertedNoticeMarker`.
