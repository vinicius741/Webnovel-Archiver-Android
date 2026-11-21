# Sources, network and Cloudflare

Applies to `source/`, `source/network/`, `feature/browser/`, and Patreon early access (`sync/PatreonChapterSync`, `domain/story/PatreonCopyPlanning`). These rules add to `android/AGENTS.md`.

- `feature/browser/` launches third-party sites in a browser-powered Custom Tab and receives import URLs through `MainActivity`; do not move third-party OAuth back into a WebView. It also hosts `CloudflareSolveActivity`, a narrow in-app WebView that solves interactive Cloudflare challenges in the shared `android.webkit.CookieManager` session. The visible solver preserves existing clearance and verifies loaded source content before automatically retrying. `source/network/CloudflareBypassInterceptor.kt` normally uses OkHttp, but after a challenge it switches that host to a persistent, serialized Chromium-rendered transport for the active passage window instead of replaying a browser token through a different TLS fingerprint. A source that declares `browserTransportOnly` (Scribble Hub) never uses OkHttp for pages, and with `SourceUserAgentMode.WEBVIEW` it keeps the WebView's own User-Agent and navigation headers. A render that stalls with no challenge showing is retried once in a fresh WebView and never opens the manual circuit; solver scripts run through `GuardedJavascript` so an unanswered one cannot stop the polling. Form POSTs and JSON GETs (an `Accept: application/json` request, e.g. the Patreon API) use same-origin browser `fetch` to preserve AJAX headers and return the raw response text. `SourceReliabilityCoordinator` owns the process-wide request budget, adaptive pacing, cooldown, and manual-verification circuit shared by sync and downloads. That CF path is distinct from OAuth — third-party login/credential flows still go through Custom Tabs, never the in-app WebView.

- New novel sites implement `SourceProvider`, declare stable identity/capabilities/network/UI policy
  in `SourceDescriptor`, and must be registered in `SourceRegistry`. SpaceBattles and Sufficient
  Velocity share `XenForoProvider` for main threadmarks, RSS, and reader pages; keep their stable
  ID prefixes and descriptors separate. SpaceBattles alone owns Story Library enrichment.
  Do not add source-name or host
  switches outside the provider descriptor; follow `docs/sources/adding-a-source.md`. Source
  metadata parsing also discovers Patreon links; `StorySyncEngine` refreshes Patreon statistics by
  default without creator-specific mappings. Only measured data is persisted (`PatreonRawStats`,
  USD-converted at capture, tier ladder delta-encoded on metric snapshots); every displayed dollar
  figure is derived at render by `PatreonEarningsPlanning`, so formula changes apply retroactively.
  Callers can pass `refreshPatreonStats = false` to skip the Patreon refresh while preserving
  existing saved stats (Updates' "Sync all that may be out" and the offline simulator do). Patreon data belongs to the creator: live stories whose
  `patreonUrl` resolves to the same `PatreonCreatorPlanning.creatorKey` share stats (a fresh
  refresh is copied to siblings in `commitSyncedStory`, startup reconciles to the freshest), and
  Trends charts their merged Patreon history. Per-story metric files are not rewritten.
- Patreon early-access chapters (`docs/sources/patreon-early-access.md`) supplement a source story;
  Patreon is not a `SourceProvider`. A link targets one collection or, with a blank `collectionId`,
  every post on the campaign (creators without collections), which selects posts more strictly. Copies carry `Chapter.patreonPostId`, always sit after every
  source chapter, are split off before the source merge (so they never read as removed source
  chapters), are stored by sync from the post list (`sync/PatreonChapterSync`), and are never
  queued for download. `domain/story/PatreonCopyPlanning` owns title matching and retiring a copy
  once its public chapter has downloaded (sync and download commits both reconcile). The user's
  Patreon `session_id` lives only in `CookieManager` (`source/PatreonSession`): never persist,
  back up, log, or display it, and never enter it on the user's behalf during QA. Sign-in is the
  `AppRoute.PatreonAccount` guide (`feature/settings/PatreonAccountScreen.kt`, Firefox handoff via
  `feature/browser/FirefoxLauncher`); in QA never tap its Sign out or paste into it.
- Royal Road marks its injected anti-piracy notices with a recognizable `<span>` (`source/RoyalRoadNoticeMarker`, see `docs/sources/royal-road-notices.md`). Providers declare this through `SourceProvider.insertedNoticeMarker`. Stripping empties the marker instead of removing it, so later cleanups still recognize the chapter. The marker only removes the notice: the sentence list still runs on every chapter because it also holds non-notice entries. `cleanup/SentenceMatching` keeps that cheap: `SentenceList.candidatesIn` checks every entry's ASCII anchors in one pass over the raw HTML, and only chapters with a candidate are parsed. Do not go back to one regex (or one substring search) per entry per chapter, which made cleanup 6–30x slower on a real novel.
