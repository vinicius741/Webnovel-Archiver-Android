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
- a 30-minute sticky browser-transport passage refreshed by each browser-rendered success, which is
  permanent for a source that declares `browserTransportOnly` (Scribble Hub).

Waiting happens outside the host lock. Downloads, sync, cover fetches, and retries therefore share
one request stream instead of enforcing independent limits.

## Cloudflare transport

OkHttp remains the inexpensive first transport, except for a source whose descriptor sets
`SourceNetworkPolicy.browserTransportOnly`. Scribble Hub challenges OkHttp at the start of every
session, so its pages never go through OkHttp: the probe spent a request that was always rejected
and showed the site two different clients on one address.

`cf-mitigated: challenge` is authoritative at any
HTTP status; Cloudflare headers plus strong challenge DOM markers are the conservative fallback.
Header-confirmed challenges do not wait for a body preview. The fallback checks script configuration,
challenge orchestration paths, and the interstitial title. It ignores prose, escaped script examples,
embedded Turnstile, and passive JavaScript Detection scripts on readable pages. A generic
`challenge-platform` path is not a block signal: Cloudflare injects these paths into normal HTML too.
See [Cloudflare JavaScript Detections](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/javascript-detections/).

After a detected challenge:

1. The host enters sticky browser mode.
2. GET pages load through a serialized persistent WebView session. Form POSTs reuse a settled
   same-origin page when available, or open one before running `fetch` with the original form body
   and the source's AJAX headers. The returned response text, not a WebView-generated HTML wrapper,
   reaches the existing parser. Android `WebView.postUrl()` cannot carry those custom headers.
3. The solver polls the document state on a main-Looper timer started directly when navigation is
   issued. It does not use `onPageStarted`/`onPageFinished`, which some physical-device WebView
   builds can omit for the detached renderer, or `View.post`, which can defer work until a View is
   attached. A page is accepted only after `document.readyState` reaches
   `interactive`/`complete` — so a mid-stream DOM cannot be serialized as a truncated chapter —
   and a requested page missing its expected selector receives a short settling period before it
   is treated as unexpected content.
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
4. The validated rendered DOM or original form response text is returned to the existing parser.
5. Later pages go directly through the same Chromium session without first sending the already
   rejected OkHttp TLS/HTTP fingerprint.

Render failures escalate by kind, not uniformly. Only a challenge still showing when the time
runs out, or a dead renderer, opens the manual circuit. A settled page that fails the source's
content rules is returned to the parser (one job fails with a typed parse error), an origin HTTP
status such as a removed chapter's 404 flows through the normal per-source retry policy, and a
transport-level render failure is a retryable error. Previously any of these opened the circuit,
so a single dead chapter URL could block the whole source.

A render that ends without a page while no challenge was showing is a *stall*
(`CloudflareRenderOutcome.Stalled`: timed out, stale document persisted, navigation never
committed, never settled). The solver destroys that WebView, the interceptor renders once more in
a new one, and a second stall fails only that request as a retryable transport error. A stall
never opens the circuit. Whether a challenge was showing comes from the last poll that read a
current, non-empty document; a challenge reloading itself does not count as gone. Through 1.2.1 a
stall did open the circuit: in the 2026-11-21 phone log a render stopped polling
after its second poll, timed out, and paused Scribble Hub for 30 minutes, after which the
verification screen opened the same page in 1.5 seconds with no challenge.

That render stopped polling because each poll was scheduled from the previous poll's
`evaluateJavascript` answer, and one answer never arrived. Every page-state script now goes through
`GuardedJavascript`: an answer missing after two seconds is recorded as `render_poll_lost` and
polling continues. The emulator's WebView 133 did not reproduce the missing answer (about 7,200
scripts issued across 12 navigations, all answered), so its cause on the phone is not established; the guard
does not depend on it.

## What the hidden WebView presents

Measured on the emulator (WebView 133) with a loopback server, 2026-11-21:

| | Before | Now, for a `WEBVIEW`-mode source |
|---|---|---|
| Viewport | `innerWidth`/`innerHeight` 0×0; an `IntersectionObserver` never reports the page's content visible | the display's size; content visible |
| `User-Agent` | rewritten to plain Chrome (`Android 10; K`, no `wv`) | the system WebView's own string |
| `sec-ch-ua` | `"Android WebView"`, contradicting the User-Agent | `"Android WebView"`, matching it |
| Model and platform-version hints | sent blank | sent with the device's values |
| `X-Requested-With` | this app's package, contradicting the User-Agent | this app's package, consistent with a WebView |
| `Accept-Language` on navigation | passed as an extra header, written ahead of `sec-fetch-*` | written by Chromium, last |

The solver lays its detached WebView out at the display's size on creation; this applies to every
source. The identity rows apply to sources whose descriptor sets
`userAgentMode = SourceUserAgentMode.WEBVIEW` (Scribble Hub): their WebViews are never assigned a
User-Agent (`SourceUserAgent.keepsWebViewDefault`), their OkHttp requests send the same unmodified
string (`SourceUserAgent.forUrl`), and their page navigations carry no `Accept` or
`Accept-Language` extra headers. Other sources keep the reduced Chrome string. The app does not
alter client hints or remove `X-Requested-With`; the aim is a client whose signals agree, not one
that hides what it is.

Whether this lets the hidden WebView pass Scribble Hub's challenge without the verification screen
has not been tested against the live site. In the phone log the hidden WebView showed the challenge
for 20 seconds without passing, and the visible screen passed in 8 seconds; the log does not say
whether that needed a tap. An interactive check still needs the verification screen.

While the circuit is open, the download loop pauses the source's lane instead of draining it:
in-flight jobs fail as `source_blocked` (the retry flow keys off that category), pending jobs are
deferred to a short recheck, and the scheduler skips the source entirely. The foreground service
posts a "Source needs verification" notification that opens the in-app solver; one successful
verification clears the circuit and the whole remaining queue resumes on its own.

Per-host reliability state (open circuit, sticky-transport window, cooldowns, counters) is
persisted to `source_reliability.json` on every transition and restored at process start, so an
overnight OS kill resumes with the same access state instead of re-probing the source with the
already-rejected OkHttp fingerprint.

The reduced WebView-derived User-Agent is frozen on first use so it cannot change mid-session. A network
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

The Downloads screen turns a blocked site into one "Needs attention" card per site ("Royal Road
needs a quick check", with the novels and chapters it holds). A site counts as blocked when any
chapter failed as `source_blocked` or pending chapters sit behind its open manual circuit
(`DownloadQueueSectionPlanning.blockedSites`). Verify now opens `CloudflareSolveActivity` directly.
When the solver passes, `resumeDownloadsAfterSourceCheck` returns that site's blocked chapters to
pending without spending a retry, clears the recheck delay on its deferred chapters, and starts
the download service. This runs whatever opened the solver: the card, the novel page's banner, the
notification, or a sync retry. The card then shows "Verified · resuming N chapters" briefly. If the
user comes back without passing, it shows "Still blocked" with Try again. Fallbacks (open in
browser, reset the site's web session, pause that site's downloads) sit behind "Having trouble?".
These states live in memory only (`SourceCheckOutcomes`). The blocked-site notification names the
site.

Settings shows the active WebView version and aggregate, process-local source counters: request,
challenge, rate-limit, and browser-render counts. It also reports whether Chromium transport,
cooldown, or manual verification is active.

“Share Source Access Logs” exports the bounded, process-local bypass event log
(`data/diagnostics/BypassEventLog`) as a JSON Lines file into the app's backups folder and shares
it through the system sheet. The first line is a self-describing header with app info, the
aggregate reliability snapshots, a queue summary, and embedded instructions so an AI agent can
interpret the file cold; each following line is one structured event (request lifecycle, challenge
detection, sticky-transport window, WebView render polls and outcomes, circuit and cooldown
transitions, download-queue failures, interactive-solve flow) correlated by attempt, render, and
job ids. The ring is trimmed to the newest 1500 events and the export to 256 KiB.

“Reset Source Web Session” destroys persistent solver WebViews, clears WebView storage and source
cookies, closes the circuit, and disables sticky mode. The next request begins a fresh source session.

The visible verification screen preserves existing clearance and waits for the requested source
page's expected content instead of treating cookie presence as proof of access. For source endpoints
that are not navigable pages, it opens the provider's browse page and waits for an explicit Done
before retrying the original request. A user can still confirm an unverified page explicitly.

Aggregates and events carry host names, timings, decisions, and fixed-vocabulary failure notes.
No URLs, story names, chapter titles, cookies, or response bodies are retained in these
diagnostics.
