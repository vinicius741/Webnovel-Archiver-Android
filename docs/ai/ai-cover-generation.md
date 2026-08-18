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
  (badge + thumbnail) or the current state (source cover / no cover), the generation-mode checkbox,
  `Generate` (label follows the mode: `Generate Cover with AI` / `Generate Prompt with AI` /
  `Regenerate …`), and `Delete AI cover` when a generated image exists.
- **Generation modes** — the `Generate prompt + image in one step` checkbox (persisted in
  `AiSettings.coverOneStep`, default on) picks between:
  - **One step**: the button runs both billable stages in a single uninterrupted flow, exactly as
    before. A pending staged prompt is discarded by the run.
  - **Staged**: the button runs only the prompt-writing call. The prompt appears in an editable
    draft card (`Image prompt · draft`, multiline field, `Generate Image` / `Discard`); the user
    can rewrite it before the billable image call, and re-painting after an edit re-bills only the
    image stage. Regenerating the prompt discards a preview painted from the old one.
- **Two billable stages** (both on the user's key):
  1. The **description model** (the same model chosen for synopses) writes an image-generation
     prompt from the novel's material: title, author, tags, the currently displayed description
     (AI synopsis when active, else source), and the same capped first-5-downloaded-chapters
     context the description flow uses.
  2. The **image model** paints the prompt via `POST /api/v1/images` with `aspect_ratio 2:3`
     (matches the app's 80×120dp / 150×225dp cover cards), `resolution 1K`, `quality medium`.
     Optional parameters are sent only when the selected model lists them in its catalog
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
- **Background generation**: cover jobs do not belong to the screen that started them. They run on
  the process-wide application scope (`AiCoverJobCoordinator`) and keep running while the user
  navigates between screens, minimizes, or leaves the app; `AiCoverForegroundService` (a
  `dataSync` foreground service, channel `webnovel_ai` in Settings → Notifications) holds the
  process alive for the duration and shows live progress, then posts a tappable **AI cover
  ready / failed** notification. Each stage's result is persisted to
  `ai_cover_drafts/<safeName(id)>.{json,<ext>}` (prompt first, image bytes before the JSON meta
  that marks completeness) the moment it arrives, so even a process death after the API replied
  cannot lose a billed image. Reopening AI Controls rehydrates the persisted draft (prompt-only
  or full preview) into its card; `Apply`/`Discard`/`Delete AI cover` clear the files. Starting
  the image stage persists the edited prompt with it, so a mid-paint death recovers the prompt
  for a retry.
- **Show AI cover toggle**: when both a source cover and an applied AI cover exist, a `Show AI
  cover` checkbox (mirroring the synopsis toggle) switches which one the app displays — nothing is
  deleted either way. Applying a new AI cover always switches the display to it; a story whose
  source has no cover keeps showing the AI cover regardless of the toggle
  (`AiCoverPlanning.isAiCoverActive`).
- **Delete**: `Delete AI cover` removes the generated file and record — for giving up the
  generated image entirely. The source `coverUrl` is never modified anywhere in the flow, so the
  original always survives.

## Where the AI cover applies

While `Show AI cover` is on (the default after an Apply), the local cover file is what every cover
surface displays: library cards, the Details header, the Follow Updates list, and the full-screen
zoom viewer (which also becomes available for stories that never had a source cover). Generated
EPUBs embed the AI cover bytes in place of the source URL's image. Toggling it off switches all of
those surfaces — EPUBs included — back to the source cover without deleting anything. Syncs carry
`aiCoverPath` + `showAiCover` forward like `aiDescription`, and the sync fold protects a cover
applied or toggled during a sync's network window.

## Storage, cost, and failure handling

- One file per story under `files/webnovel_archiver/covers/`, written atomically; a regenerate
  overwrites it (removing any earlier file saved under a different extension). Deleting the story
  removes its cover.
- Cost controls: the text call reuses the description budgets (5 chapters, 12k chars each, 60k
  total, `max_tokens 1000`); the image call requests one 1K image. Generating over an applied cover
  or pending drafts asks for confirmation (one call in staged mode, two in one-step), and so does
  repainting an edited prompt over a pending preview; the shared `storyOperation` guard
  (`AI_COVER` kind) blocks concurrent story operations and drives the progress UI. A hand-edited
  prompt is re-cleaned (trimmed, whitespace-collapsed, capped at 1,500 chars) exactly like a model
  reply.
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
| `ai/AiCoverPlanning.kt` | Pure logic: prompt-writing messages (metadata + description + chapters), prompt cleanup, image-request parameter gating on the catalog, the `isAiCoverActive` display rule, and media-type → file-extension mapping. |
| `ai/AiCoverArtEngine.kt` | Stage orchestration: `draft` (one-shot), `draftPrompt` (stage 1), `draftImage` (stage 2, cleans the possibly user-edited prompt); caches the image-model parameter catalog per process. |
| `ai/AiCoverJobCoordinator.kt` | Background job runner on the application scope: one cover job at a time, running state (`jobs` StateFlow) for progress surfaces, terminal `events` (result persisted before the success event fires). |
| `ai/AiCoverForegroundService.kt` | `dataSync` foreground service mirroring job progress in a notification and posting ready/failed outcome notifications; stops itself when the coordinator goes idle. |
| `ai/AiContextChapters.kt` | Shared capped chapter reading used by both the description and cover engines. |
| `app/AiCoverJobUiBridge.kt` | Activity-side bridge: mirrors coordinator state into the shared `storyOperation` slot (Details progress, AI Controls gating) and surfaces terminal events as toasts/draft cards. |
| `data/storage/AiCoverDraftStore.kt` | Pending-draft persistence under `ai_cover_drafts/` (prompt JSON + image bytes, image-first completeness marker); excluded from backups by design. |
| `feature/ai/AiCoverControls.kt` | Cover Art section UI: state card (thumbnail, show-AI toggle, mode checkbox), draft preview, apply/discard/delete actions. |
| `feature/ai/AiCoverGeneration.kt` | Generation flows: mode dispatch, job launches through the coordinator + service, the staged prompt card, draft rehydration from disk. |
| `feature/settings/SettingsAiImageModel.kt` | Image-model picker dialog + catalog cache for the AI Settings row. |

Supporting pieces: `Story.aiCoverPath` + `Story.showAiCover`; `AiSettings.imageModel` +
`AiSettings.coverOneStep` + `DEFAULT_IMAGE_MODEL`; `AppStorage.saveCover`/`findCoverFile`/
`deleteCover` and `deleteStory` cleanup; `AppRepository.setAiCover`/`clearAiCover`/`setShowAiCover`/
`coverFile`; `StoryMutations.setAiCoverPath`/`setShowAiCover`/`clearAiCover`; sync carry-forward +
fold in `StorySyncEngine`/`StorySyncMergePlanning`; `coverImage()`/`activeCoverSource()` honoring
the preference (`ui/Scaffold.kt`, `feature/story/StoryDialogs.kt`); EPUB embedding via
`EpubEngine.localCoverAsset`; `StoryOperationKind.AI_COVER`; backup changes in `BackupExporter`,
`BackupInputLimits`, `FullBackupManifestValidation`, and `FullBackupRestorer`.

## Extending to more generators

The description → cover progression generalizes: a new generator adds an `AiSettings` model field +
settings row, a pure planning object, an engine method returning a draft, a card on AI Controls
with the preview-then-apply pattern, and (when it produces files) a per-story storage root plus
backup/restore handling like `covers/`.
