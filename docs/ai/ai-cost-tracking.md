# AI Usage and Cost Tracking

The native app records the billing receipt OpenRouter returns with each successful AI response.
This gives users a device-local, per-request history without storing prompts or exposing the API
key. Tracking covers description generation, cover-prompt generation (including billed empty
replies and retries), and cover-image generation.

## User surfaces

- **Settings → AI Settings → AI spend** shows local totals for today, the current month, and all
  time, plus the eight newest requests with feature, model, timestamp, token count, outcome, and
  reported cost. Local tracking starts with requests made after this feature is installed; it does
  not attempt to reconstruct older per-request history.
- The same card can fetch live usage and limit counters for the API key entered on the screen via
  `GET /api/v1/key`. This uses the ordinary inference key, not an OpenRouter management key.
- Description, cover-prompt, and cover-image preview cards show the latest operation cost. A
  one-step cover groups its prompt and image calls under one operation, so the cover preview shows
  their combined cost while Recent requests keeps the individual receipts visible.

OpenRouter can omit billing metadata. The app labels those rows **Cost unavailable** and counts
them separately instead of treating them as free. Failed requests that never return a provider
receipt are not assumed to be billable. Live key counters provide the account-level cross-check,
including usage outside this app.

## Storage and precision

`OpenRouterClient` keeps provider monetary values as decimal strings. `AiUsagePlanning` validates
and aggregates them with `BigDecimal`, avoiding binary floating-point drift for sub-cent charges.
The ledger lives at `files/webnovel_archiver/ai_usage.json`, keeps exact daily/monthly/all-time
aggregates, and retains the newest 500 request rows. It stores model and token metadata, but never
the prompt, generated text, image, or API key.

`AppRepository.recordAiUsage` serializes updates through the repository storage transaction and
writes atomically before updating its cache. A same-device full restore preserves the ledger, but
the ledger is excluded from exported backups, matching the device-local treatment of AI settings.
If writing the receipt fails, generation still succeeds; the app logs the local tracking failure
and the live OpenRouter key totals remain available. Repository refresh reloads this cache, so
clearing all data also clears the in-memory history. I/O and unsupported-schema reads block ledger
writes until a later refresh succeeds instead of overwriting the unreadable source file.

## Code map

| File | Role |
|------|------|
| `ai/OpenRouterClient.kt` | Parses response receipts and live key usage without converting money to `Double`. |
| `ai/AiUsagePlanning.kt` | Normalization, exact aggregation, retention, summaries, and cost formatting. |
| `domain/model/AiUsageModels.kt` | `AiUsageRecord`, period summaries, and `AiUsageLedger`. |
| `data/storage/AiUsageFileStore.kt` | Device-local durable reads, guarded failures, and atomic writes. |
| `data/repository/AiUsageStore.kt` | Reloadable cache and serialized receipt recording. |
| `feature/settings/SettingsAiUsage.kt` | Local totals, recent requests, and live current-key counters. |
| `feature/ai/AiUsageUi.kt` | Groups receipts into the per-operation cost shown beside previews. |
