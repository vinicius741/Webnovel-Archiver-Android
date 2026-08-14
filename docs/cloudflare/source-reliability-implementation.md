# Source reliability implementation

This document describes the current native Android behavior for long source downloads. The earlier
HTML documents in this folder remain the research and decision record; this file is the implemented
architecture.

## Request path

All source consumers share one `NetworkClient` and one `SourceReliabilityCoordinator` from
`AppContainer`. The coordinator canonicalizes `www` and bare hosts, then owns:

- the configured per-source delay range;
- a conservative source-policy floor (Scribble Hub: 3 seconds);
- a rolling Scribble Hub budget of at most 12 request starts per minute;
- an adaptive minimum gap that increases after rate limits and recovers after sustained success;
- timed `Retry-After` cooldowns up to 30 minutes;
- manual-verification circuit state; and
- a 30-minute sticky browser-transport passage refreshed by each browser-rendered success.

Waiting happens outside the host lock. Downloads, sync, cover fetches, and retries therefore share
one request stream instead of enforcing independent limits.

## Cloudflare transport

OkHttp remains the inexpensive first transport. `cf-mitigated: challenge` is authoritative at any
HTTP status; Cloudflare headers plus strong challenge DOM markers are the conservative fallback.

After a detected challenge:

1. The host enters sticky browser mode.
2. The original GET or form POST is loaded through a serialized persistent WebView session.
3. The solver polls the document state on a main-Looper timer started directly when navigation is
   issued. It does not use `onPageStarted`/`onPageFinished`, which some physical-device WebView
   builds can omit for the detached renderer, or `View.post`, which can defer work until a View is
   attached. A page is accepted only after `document.readyState` reaches
   `interactive`/`complete` — so a mid-stream DOM cannot be serialized as a truncated chapter —
   and only when its stable story/chapter identity (or exact non-story path) matches the request, so
   the persistent session's previous document is never mistaken for the new one. Identity comparison
   prefers stable provider chapter ids over URL kind, because chapter URLs can classify as a story
   (FanFiction) or canonicalize to a thread URL (SpaceBattles post → `#post-` fragment). Before each
   navigation the outgoing document is marked stale in its own window (`window.__wnaRenderStale`), so
   a retry of the same URL cannot decide on the previous request's page before the fresh navigation
   commits. A settled previous chapter is treated as navigation still pending and polled again; it
   is not rejected as a new Cloudflare block while the next sequential chapter is committing.
   Wrong-origin/error pages and pages missing the source's expected content marker are rejected.
   Poll payloads arrive JSON string-encoded from the
   `evaluateJavascript` bridge and are unwrapped by the unit-tested `CloudflarePageStateDecoder`;
   parsing them as objects directly once collapsed every poll into an eternally-loading document,
   making every render time out as blocked while the interactive verifier (which decodes
   correctly) showed the page as fine. Render give-ups log the last observed document state so the
   branch that stalled is identifiable from logcat.
4. The validated rendered DOM is returned to the existing Jsoup parser.
5. Later pages go directly through the same Chromium session without first sending the already
   rejected OkHttp TLS/HTTP fingerprint.

If the browser cannot produce a valid page within the timeout, the manual circuit opens. The
download engine marks all active jobs for that source as `source_blocked` in one transaction, while
other sources may continue. One successful interactive verification clears the circuit and retries
the affected queue.

The WebView-derived User-Agent is frozen on first use so it cannot change mid-session. A network
change drops prepared page content while retaining server cooldowns, the rolling request budget, and
Chromium mode for a fresh browser load.

## Bulk downloads and retries

Batches of 20 or more pending chapters preflight their first chapter. `NetworkClient` caches the
validated HTML for five minutes, so the worker consumes it without issuing a duplicate request.

Download jobs own the durable retry budget. Each job execution makes one network attempt, eliminating
the former multiplication of three network attempts by four queue executions. An exhausted 403/429
produces a typed rate-limit error; the queue never schedules that job or another pending job for the
same source earlier than the larger of its exponential backoff and the server/coordinator cooldown.

Story sync retains its bounded in-call retry behavior because it has no durable job scheduler.

## User controls and diagnostics

Settings shows the active WebView version and aggregate, process-local source counters: request,
challenge, rate-limit, and browser-render counts. It also reports whether Chromium transport,
cooldown, or manual verification is active.

“Reset Source Web Session” destroys persistent solver WebViews, clears WebView storage and source
cookies, closes the circuit, and disables sticky mode. The next request begins a fresh source session.

No URLs, story names, chapter titles, cookies, or response bodies are retained in these diagnostics.
