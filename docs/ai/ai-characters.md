# Characters and portraits

The story page's **AI** tab has three tabs: **Cover**, **Characters** and **Description**. The
Characters tab lists the novel's cast and paints a portrait of any character the owner picks. It
uses the same OpenRouter key, text model, decision model and image model as covers (AI Settings).

## Using it

1. **Find characters.** Before the first run the tab shows one card with **Find characters**.
   The app reads every downloaded chapter, counts name-like words on the phone (free), and makes
   one text-model call that turns them into a list. The run takes seconds plus one model call.
2. **The list.** The lead first, then **Main characters**, then **Supporting (N)**, collapsed.
   Each row shows the newest portrait (or initials), the role, the chapters the character
   appears in, how often they are named, and a one-line bio. **Find characters again** rebuilds
   the list; a character whose name or an alias matches one in the old list keeps its id, so its
   portraits stay attached even when the model words the name differently.
3. **A character's page.** Tapping a row opens the character inside the tab: portrait, role,
   chapters, aliases and bio, every portrait (tap for full size, with its prompt and **Delete**),
   and **Generate portrait**. System Back and **‹ All characters** return to the list.

Jobs run on the application scope (`CharacterJobCoordinator`), so leaving the screen never
cancels a billed call; one character job runs at a time across all novels (buttons say so when
another novel's job runs), and a novel deleted mid-run ends its job silently before the next paid
step. Progress ticks patch one line; only a job starting or ending re-renders the tab, which renders
its last loaded view at once (keeping the scroll position) and re-renders only if the files changed. `AiJobForegroundService` keeps the process alive and posts a
"Characters found" / "Portrait ready" notification. One character job runs at a time; cover
jobs are independent.

## How the list is built

- Chapters are read one at a time, never all at once. `CharacterMining.Tallies` finds capitalized
  words and word pairs that appear mid-sentence ("Mr. Darcy" counts), are rarely written in
  lowercase ("Mana" vs "mana"; for a pair, its first word: "Did Jason" is dropped) and are not
  common capitalized words; possessive pairs ("Jake's Perception") are skipped and curly
  apostrophes count as straight ones. Names next to speech verbs rank higher. It keeps the top
  120: three snippets each for the top 40, one for the rest, read back from just those chapters.
- `CharacterRosterPlanning` sends the title, tags, description and the candidates as
  `SOURCE_DATA` to the text model, which drops places, skills and titles, merges aliases, picks
  exactly one protagonist (a second one is demoted), and writes a one-sentence bio. The reply is
  JSON; up to 24 characters. `CharacterRosterPlanning.reconcile` drops aliases two characters share
  and bare titles ("Captain", "Senior Brother"), and keeps old ids as described above. Ids are
  ASCII slugs; a name with letters outside ASCII ("Renée", "林凡") gets a short hash of the name.
- A second pass counts every character's names and aliases with one pattern for the whole list
  (`CharacterMining.MentionCounter`; the longest name wins, so "Li Mei" never counts for a "Li")
  for the chapter span, the mention count, and `keyChapters` (the entrance, then the chapters that
  name them most), which the portrait scan reads first. Characters the text never names are
  dropped, except the lead.

## How a portrait is painted

1. **Where they are described (free).** `CharacterAppearanceScout` reads every downloaded chapter on
   the device and scores each mention of the character by the appearance words around it (hair,
   blonde, tall, freckles, wore...). The chapters with the best-described mention come first, then
   the chapters that name the character most, up to 16. A character who enters late or is described
   once is still found: the sister in the test novel enters around chapter 80 and is described only
   at chapter 149 ("golden blonde strands and bright blue orbs"), which ranking by mentions missed.
2. **Evidence.** For the lead of a novel on automatic cover chapters, the cover's scan
   (`coverSelection`) supplies the appearance passages, scoped to the cover's story phase (Cover
   tab › Story phase), with earlier passages for lasting traits. It is cached after the first
   cover; otherwise it scans about 60 chapters first (around 2 cents), and a lead portrait cannot
   start while that novel's cover job runs. Everyone else, the lead of a novel with hand-picked cover
   chapters, and a lead the cover scan found nothing for, gets `CharacterAppearanceRubric`: every chapter the scout chose (no early stop), each sent from
   one passage before its best-described mention, with a per-passage Noul "does this say how the
   character in `character` looks?". Details that belong to someone else in the passage do not
   count; a comparison ("a shade farther from gold than her own") does. The name travels in the state,
   never in a question, and the novel description gets only the bytes the rest of the request
   leaves, so a request stays under 30 KB even with non-Latin text. The scan stops after 5 chapters with a flagged passage.
3. **No evidence, no face.** If no passage describes the character, the run stops with a plain
   message before any text or image call.
4. **Prompt.** `CharacterPortraitPlanning` gives the text model the character, the flagged
   passages (12k characters, strongest first), a random framing (head-and-shoulders, waist-up,
   three-quarter or full figure), and, when the novel shows an AI cover, the art direction of that
   cover's prompt as `cover_style_reference` (`styleOnly` keeps only sentences that name no person,
   physical trait or lettering: the cover usually shows the lead, and passing the whole prompt once
   gave a blonde sister the lead's black hair). The writer takes a trait only when a passage
   attributes it to this character. The portrait has no lettering.
5. **Image.** The image model paints it at 2:3, like covers.

## Storage

`CharacterStore` keeps `ai_characters/<story>/roster.json` and
`ai_characters/<story>/portraits/<character id>/<sha256>.{png,jpg,webp}` with a JSON sidecar holding
the prompt. Like saved cover versions, this is device-local: it is not in backups and is deleted
with the novel.

## Cost

Measured on a 277-chapter novel with Claude Sonnet 5.5 as the text model:

| Action | Cost |
|---|---|
| Find characters (one text call, ~9k tokens in) | about $0.03 |
| Portrait passage scan (up to 16 Jev requests) | about $0.005 |
| Portrait prompt | about $0.02 |
| Portrait image, Meta Muse Image (the default) | $0.01 |
| Portrait image, Grok Imagine | $0.06 |

Usage features in the ledger: `characters`, `portrait_selection`, `portrait_prompt`,
`portrait_image`.
