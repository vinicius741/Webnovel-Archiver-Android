# AI-Generated Cover Art (OpenRouter Images API)

Per-novel AI cover generation through [OpenRouter](https://openrouter.ai/), for novels whose
source cover is bad or missing entirely. Shipped 2026-08-16; the second AI feature, built on the
same key/model/preview-apply layer as descriptions (see `ai-description-generation.md`).

## User flow

- **Settings → AI Settings**: beside the description model, a **Cover image model** row picks the
  image generator. Its picker rides the dedicated image-model catalog (`GET /api/v1/images/models`,
  public, no pricing) — same searchable dialog shape as the text-model picker, minus price labels
  and the Free filter, with a pinned manual-id row. Defaults to `x-ai/grok-imagine-image-2.0`
  (from ~$0.04/image; supports 2:3 portrait).
- **Details → More options → AI Controls → Cover Art**: the cover card shows the applied AI cover
  (badge + thumbnail) or the current state (source cover / no cover), plus `Generate Cover with AI`
  (or `Regenerate Cover`) and `Use source cover` when reverting is possible.
- **Two-stage generation** (both billable on the user's key):
  1. The **description model** (the same model chosen for synopses) writes an image-generation
     prompt from the novel's material: title, author, tags, the currently displayed description
     (AI synopsis when active, else source), and the same capped first-5-downloaded-chapters
     context the description flow uses.
  2. The **image model** paints it via `POST /api/v1/images` with `aspect_ratio 2:3` (matches the
     app's 80×120dp / 150×225dp cover cards), `resolution 1K`, `quality medium`. Optional
     parameters are sent only when the selected model lists them in its catalog
     `supported_parameters` **and** the value is inside that parameter's allowed `values` enum —
     when the default is out of enum (recraft offers `3:4` but not `2:3`; seedream-lite starts at
     `2K`) the nearest supported stand-in is sent instead, and a parameter with no acceptable
     value is omitted (the model's own default applies). An unknown catalog falls back to a
     minimal model+prompt request.
- **Preview → apply**: the draft card shows the decoded image (tap to zoom), the exact prompt sent
  to the image model (for transparency), and `Apply` / `Discard`. Nothing is persisted until
  `Apply`, which writes the image to `covers/<safeName(id)>.<ext>` and points the story at it.
  The prompt-writing system prompt follows image-model best practices: layered description
  (subject → setting → art style/medium → portrait composition → lighting → color/mood), concrete
  nouns, no "book cover" mockup, no watermark/border — and the **text model itself decides**
  whether the title fits on the cover: short titles may be typeset, long ones are shortened or
  dropped, and no other text is ever invented.
- **Revert**: `Use source cover` deletes the generated file and clears the record — the source
  `coverUrl` is never modified anywhere in the flow, so the original is always one tap away.

## Where the AI cover applies

Once applied, the local cover file wins everywhere a cover is shown: library cards, the Details
header, the Follow Updates list, and the full-screen zoom viewer (which also becomes available for
stories that never had a source cover). Generated EPUBs embed the AI cover bytes in place of the
source URL's image. Syncs carry `aiCoverPath` forward like `aiDescription`.

## Storage, cost, and failure handling

- One file per story under `files/webnovel_archiver/covers/`, written atomically; a regenerate
  overwrites it (removing any earlier file saved under a different extension). Deleting the story
  removes its cover.
- Cost controls: the text call reuses the description budgets (5 chapters, 12k chars each, 60k
  total, `max_tokens 500`); the image call requests one 1K image. Generating over an applied cover
  or a pending preview asks for confirmation (two billable calls); the shared `storyOperation`
  guard (`AI_COVER` kind) blocks concurrent story operations and drives the progress UI.
- HTTP failures reuse the friendly mapping (401 key / 402 credits / 404 model / 429 rate limit);
  a missing, empty, or undecodable image yields a "try again or pick a different model" message.

## Backups

- **JSON backup**: `aiCoverPath` is stripped like other device-local file paths; covers are not
  included. Stories fall back to their source cover URL after a JSON-only restore.
- **Full backup**: generated covers ship in the zip under each story's own relative
  `aiCoverPath` (`covers/<name>.<ext>`), listed in a new optional `coverFiles` manifest index
  (validated like `metricFiles`; older backups without the key restore fine — stories fall back
  to the source cover). Restore copies the `covers/` tree verbatim and keeps a recorded path only
  when it is exactly the story's `coverFiles` entry — the path comes from untrusted backup JSON
  and is re-resolved against the live root, so anything else (dangling, traversal, or pointing at
  another file) is cleared rather than probed on the filesystem.

## Code map

| File | Role |
|------|------|
| `ai/OpenRouterClient.kt` | `generateImage` (`POST /api/v1/images`, hand-built JSON per the R8 rule, base64-decoded result) and `fetchImageModels` (image catalog with `supported_parameters`). |
| `ai/AiCoverPlanning.kt` | Pure logic: prompt-writing messages (metadata + description + chapters), prompt cleanup, image-request parameter gating on the catalog, and media-type → file-extension mapping. |
| `ai/AiCoverArtEngine.kt` | Two-stage orchestration returning an `AiCoverDraft` (prompt + bytes + media type); caches the image-model parameter catalog per process. |
| `ai/AiContextChapters.kt` | Shared capped chapter reading used by both the description and cover engines. |
| `feature/ai/AiCoverControls.kt` | Cover Art section UI: state card, draft preview (image + prompt), apply/discard/revert actions, progress patching. |
| `feature/settings/SettingsAiImageModel.kt` | Image-model picker dialog + catalog cache for the AI Settings row. |

Supporting pieces: `Story.aiCoverPath` (relative, like `epubPath`); `AiSettings.imageModel` +
`DEFAULT_IMAGE_MODEL`; `AppStorage.saveCover`/`findCoverFile`/`deleteCover` and `deleteStory`
cleanup; `AppRepository.setAiCover`/`clearAiCover`/`coverFile`; `StoryMutations.setAiCoverPath`/
`clearAiCover`; sync carry-forward in `StorySyncEngine`; `coverImage()` preferring the local file
(`ui/Scaffold.kt`); EPUB embedding via `EpubEngine.localCoverAsset`; `StoryOperationKind.AI_COVER`;
backup changes in `BackupExporter`, `BackupInputLimits`, `FullBackupManifestValidation`, and
`FullBackupRestorer`.

## Extending to more generators

The description → cover progression generalizes: a new generator adds an `AiSettings` model field +
settings row, a pure planning object, an engine method returning a draft, a card on AI Controls
with the preview-then-apply pattern, and (when it produces files) a per-story storage root plus
backup/restore handling like `covers/`.
