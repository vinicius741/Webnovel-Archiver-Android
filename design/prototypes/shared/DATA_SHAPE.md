# Data shape (do NOT open shared/library/data.js — it is a 1 MB single line that will flood your context)

Never `cat`, `grep`, `head` or Read `shared/library/data.js`, and never grep recursively over `shared/` or
`design/prototypes/` without `--exclude-dir=library`. Use the `WNA` API from `shared/kit.js` instead; this file shows
what the raw objects look like.

## Library summary
- 282 stories, 29949 chapters, 21978 downloaded.
- Tabs: Starter, Plan, Reading, Following, Paused (ids like `tab_1780763308358_i9pdfhg`).
- Full chapter lists exist for 26 featured stories; the kit synthesizes "Chapter N" lists for the rest.
- Sources: RoyalRoad, SpaceBattles, Scribble Hub, FanFiction.net, Sufficient Velocity.
- Demo story: `rr_141704` (Gunwitch [LitRPG]); chapter 3 id `2800285` is the saved TTS session (sentence 16).

## `WNA.story(id)` example
```json
{
 "id": "rr_141704",
 "title": "Gunwitch [LitRPG]",
 "author": "Goroella",
 "source": "RoyalRoad",
 "pub": "ongoing",
 "status": "completed",
 "score": "4.44",
 "tags": [
  "LitRPG",
  "Progression",
  "Comedy",
  "Female Lead",
  "Slice of Life",
  "Action",
  "Adventure",
  "Fantasy",
  "GameLit",
  "High Fantasy"
 ],
 "warnings": [
  "Graphic Violence",
  "Profanity",
  "Sensitive Content"
 ],
 "type": "Original",
 "total": 210,
 "downloaded": 210,
 "tab": "tab_1780763322696_0j2qjwn",
 "added": 1781981040739,
 "updated": 1794265057032,
 "lastSync": 1794264919554,
 "desc": "Burn the witch? Good luck.\n\nHecate has never done anything wrong in her entire life.\n\nSure, she was supposed to pick the same class as her mother. Obey the rule…",
 "cover": true,
 "bookmark": 2,
 "patreon": {
  "members": 92,
  "usd": 0
 },
 "recentPubs": [
  1793120765000,
  1793293573000,
  1793379967000,
  1793639171000,
  1793725572000,
  1793898364000,
  1793984766000,
  1794243961000
 ]
}
```
`pub`: ongoing | completed | hiatus | outdated | unknown. `bookmark`: 0-based chapter index or null.
`patreon`: {members, usd} or null. `cover`: whether a cover image exists (use `WNA.coverUrl`).

## `WNA.chapters(id)` items
```json
[
 {
  "id": "2799917",
  "title": "Chapter 1: A Well-Placed Bullet Is Worth a Hundred Prayers",
  "downloaded": true,
  "publishedAt": 1764327285000,
  "index": 1,
  "audio": false
 },
 {
  "id": "2800142",
  "title": "Chapter 2: Goat Herder or Dung Witch",
  "downloaded": true,
  "publishedAt": 1764331567000,
  "index": 2,
  "audio": false
 }
]
```

## `WNA.session`
```json
{
 "storyId": "rr_141704",
 "chapterId": "2800285",
 "chapterTitle": "Chapter 3: Regional Rudeness Trials",
 "index": 16,
 "storyTitle": "Gunwitch [LitRPG]"
}
```

## `WNA.settings.tts` keys
announceChapters, contextReplay, enginePackage, paragraphPauseMs, phrasePauseMs, pitch, profiles, rate, scenePauseMs, sentencePauseMs, voiceIdentifier, engine, voiceLabel, profileStatus, replayAfterBreaks, sayTitles, skipWholeChapters
