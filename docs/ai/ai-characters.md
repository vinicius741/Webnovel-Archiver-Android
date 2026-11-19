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
   the list; portraits stay with characters whose name is unchanged (ids are name slugs).
3. **A character's page.** Tapping a row opens the character inside the tab: portrait, role,
   chapters, aliases and bio, every portrait (tap for full size, with its prompt and **Delete**),
   and **Generate portrait**. System Back and **‹ All characters** return to the list.

Jobs run on the application scope (`CharacterJobCoordinator`), so leaving the screen never
cancels a billed call; `AiJobForegroundService` keeps the process alive and posts a
"Characters found" / "Portrait ready" notification. One character job runs at a time; cover
jobs are independent.

## How the list is built

- `CharacterMining.candidates` finds capitalized words and word pairs that appear mid-sentence,
  are rarely written in lowercase ("Mana" vs "mana") and are not common capitalized words. Names
  next to speech verbs rank higher. It keeps the top 50 with up to three short snippets each.
- `CharacterRosterPlanning` sends the title, tags, description and the candidates as
  `SOURCE_DATA` to the text model, which drops places, skills and titles, merges aliases, picks
  exactly one protagonist, and writes a one-sentence bio. The reply is JSON; up to 24 characters.
- The app then counts each character's names and aliases per chapter (`CharacterMining.mentions`)
  for the chapter span, the mention count, and `keyChapters` (the entrance, then the chapters that
  name them most), which the portrait scan reads first.

## How a portrait is painted

1. **Evidence.** For the lead, the cover's automatic scan (`coverSelection`) supplies the
   appearance passages, scoped to the cover's story phase (Cover tab › Story phase), with earlier
   passages for lasting traits. It is cached after the first cover. Everyone else, and the lead
   when the cover scan found nothing, gets `CharacterAppearanceRubric`: up to 16 key chapters,
   each sent from one passage before the character's first mention, with a per-passage Noul
   "does this describe how the character in `character` looks?". The name travels in the state,
   never in a question. The scan stops after 5 chapters with a flagged passage.
2. **No evidence, no face.** If no passage describes the character, the run stops with a plain
   message before any text or image call.
3. **Prompt.** `CharacterPortraitPlanning` gives the text model the character, the flagged
   passages (12k characters, strongest first), a random framing (head-and-shoulders, waist-up,
   three-quarter or full figure), and the prompt of the cover in use as `cover_style_reference`,
   so the portrait matches the cover's medium and palette. The portrait has no lettering.
4. **Image.** The image model paints it at 2:3, like covers.

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
| Portrait passage scan (16 Jev requests) | about $0.003 |
| Portrait prompt | about $0.02 |
| Portrait image (Grok Imagine) | $0.06 |

Usage features in the ledger: `characters`, `portrait_selection`, `portrait_prompt`,
`portrait_image`.
