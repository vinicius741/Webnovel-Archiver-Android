# AI-Generated Novel Descriptions (OpenRouter)

Per-novel AI synopsis generation through [OpenRouter](https://openrouter.ai/), for novels whose
source description is bad or missing. Shipped 2026-08-14; descriptions are the first AI feature —
the layer is built to grow tag and cover-image generation later.

## User flow

- **Settings → AI Settings**: the user enters their own OpenRouter API key (masked input) and picks
  the model used for description generation. The model picker fetches OpenRouter's live catalog
  (`GET /api/v1/models`, public endpoint) into a searchable dialog with All/Free filters and
  per-million-token pricing labels; a manual-id entry row covers new/offline models. Defaults to
  `deepseek/deepseek-v4-flash-0731` (cheap) so a fresh install works immediately.
- **Details → More options → AI Controls**: all per-novel AI features live on the AI Controls screen
  (route `ai_controls`), not on the Details body, so new generators (tags, cover art) join a hub
  instead of scattering more buttons across Details. Its description card shows the applied AI
  synopsis (or an explanatory line), the "Show AI description in Details" preference, and the
  generate action.
- **Generate → preview → apply**: `Generate Description with AI` sends the first **5 downloaded
  chapters** (plain text, each capped at ~12k chars, ~60k chars total) plus title/author/tags to the
  selected model, which writes a 120–200-word book-blurb synopsis. The result is a **draft**: the
  screen shows a preview card with the draft text and `Apply` / `Discard` actions — nothing is
  persisted until `Apply` (which stores it and switches the displayed synopsis to the AI text; an
  "AI-generated" badge marks it on Details). A pending draft survives navigating away and back
  within the same process. Generating over an applied description or a pending preview asks for
  confirmation first because every generation is a billable call.
- The source `description` field is never modified. The `Show original` / `Show AI description`
  choice (a checkbox on the AI Controls screen) is remembered per novel. Description TTS (`Listen`)
  reads whichever synopsis Details displays. Syncing the novel carries `aiDescription` /
  `showAiDescription` forward (the sync engine rebuilds the `Story`, so these local-only fields are
  explicitly retained, like `coverUrl`).

## Cost controls

- Context: first 5 downloaded chapters, 12k chars/chapter cap, 60k chars total.
- Output: `max_tokens = 700`.
- Regenerating (over an applied description or a pending preview) requires a confirm dialog; every
  other in-flight story operation blocks generation
  (and vice versa) through the shared `storyOperation` guard. While a draft is generating, Details
  shows a slim progress block (so backing out of AI Controls mid-generation still explains why its
  buttons are disabled) and the AI Controls screen shows the live progress message.
- HTTP failures map to friendly messages: 401 invalid key, 402 insufficient credits, 404 unknown
  model, 429 rate limit.

## Code map (package `ai/`)

| File | Role |
|------|------|
| `OpenRouterClient.kt` | Plain OkHttp client for `openrouter.ai` (deliberately NOT the app's `NetworkClient`, whose Cloudflare WebView interceptor is for novel sites). `chatCompletion` + `fetchModels`; `baseUrl` injectable for MockWebServer tests. |
| `AiDescriptionPlanning.kt` | Pure logic: chapter selection, char caps, prompt assembly, response cleanup, and `activeDescription(story)` (the displayed-synopsis rule shared with the Details UI and description TTS). |
| `AiDescriptionEngine.kt` | Orchestration: reads chapters via the repository, extracts text (`HtmlCleanup`), calls the client, and returns the draft — persisting is the caller's job (`AppRepository.setAiDescription` on Apply). |
| `AiModelPresentation.kt` | Pure picker helpers: pricing labels, search/free-only filtering. |

Supporting pieces: `AiSettings` (in `domain/model/Models.kt`) persisted to
`files/webnovel_archiver/ai_settings.json`; `Story.aiDescription` + `Story.showAiDescription`;
`StoryMutations.setAiDescription`/`setShowAiDescription`; UI in
`feature/settings/SettingsAi.kt` (key + model) and `feature/ai/AiControlsScreen.kt` (per-novel
generate/preview/apply hub); wired in `AppContainer` (`openRouter`, `aiDescriptionEngine`).

## Secrets and backups

The API key lives only in `ai_settings.json` on the device. It is **not** part of full backups
(`BackupExporter.fullConfig` does not include the document) — restoring a backup on a new device
requires re-entering the key. Restoring a backup on the same device preserves its existing local AI
settings across the storage-root swap.

## Extending to other generators

Cover art has since shipped on this template — see `ai-cover-generation.md` for the concrete
two-stage (text + image) variant. Future generators (e.g. tags) reuse the same building blocks:
add a model field to `AiSettings` plus a row on the AI Settings screen (the model-picker dialog is
generic); add a sibling pure planning object (prompt + response cleanup) beside
`AiDescriptionPlanning`; call `OpenRouterClient` from a new engine method that returns a draft;
surface it as a new card on the AI Controls screen with the same preview-then-apply pattern; store
results as new local-only `Story` fields carried across sync like `aiDescription`.
