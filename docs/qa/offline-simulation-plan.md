# Offline simulation plan

Designed on 2026-11-02. The implementation and supported commands are documented in
[Offline agent testing](offline-simulation.md). This document preserves the original
options and rollout plan; use the implementation guide for current behavior.

The goal is to let an agent exercise the app without OpenRouter charges or requests
to novel sites. The app should still perform its normal work: select chapter evidence,
parse pages, schedule downloads, publish progress, save chapters and cover drafts,
update screens, and recover after interruption. External responses become scripted
local inputs. Local writes happen in a disposable simulation library.

## Options

| Approach | What it exercises | Main limitation | Suggested use |
|---|---|---|---|
| Fake engine results | Screens receive prepared successes, progress, and errors. Smallest initial change. | Bypasses request construction, parsing, selection, retries, and much of persistence. Can hide bugs in those paths. | Isolated UI previews and small screen tests. |
| Script external responses inside the app | Real engines consume local HTTP-shaped responses and simulated browser outcomes. No server process needed. | Socket behavior and Chromium networking need separate tests. Requires common startup, storage, and timing controls. | Recommended default for agent QA. |
| Run a local fixture server | Real HTTP clients and selected WebView flows talk to a local server with delays, redirects, and broken connections. | More setup; provider host matching and URL identity need deliberate routing. Does not reproduce Cloudflare's actual service. | Focused transport and browser integration tests. |
| Replay sanitized recorded responses | Reuses representative provider responses as fixtures for either of the previous approaches. | Recordings age, can contain secrets, and provide poor failure coverage without editing. Initial capture may incur charges. | Optional fixture source, with explicit recording only. |

I recommend scripted external responses for routine emulator work, plus local-server
tests for transport behavior. Keep the existing fast unit tests. Build one scenario
catalog that these layers can share where their input formats allow it.

## Existing code we can reuse

- `app/AppContainer.kt` already constructs shared dependencies for the activity and
  foreground services. It is the right place to select live versus simulated wiring.
- `ai/OpenRouterClient.kt` accepts a base URL and OkHttp client. Tests already use
  MockWebServer. Its separate Decisions client is derived from the injected client.
  Cover images currently arrive as base64 in the image response.
- `source/network/NetworkClient.kt` accepts an OkHttp client, dispatcher, clock,
  sleep function, and jitter function. Both its HTML and binary paths need coverage.
- `CloudflareBypassInterceptor` already accepts a `CloudflarePageRenderer`. Its unit
  tests return typed render outcomes without starting a real browser.
- `SourceReliabilityCoordinator` and `DownloadRequestPacer` already accept time and
  sleep functions. `DownloadProcessLoop` accepts a clock but also has coroutine timeout
  waits. `DecisionClient` still calls `delay` directly.
- Source parser fixtures already exist under `app/src/test/resources/fixtures/`.
  MockWebServer and kotlinx-coroutines-test are existing test dependencies.
- Dev screen launches and the performance runner provide patterns for an emulator-only
  runner, settled-state checks, and machine-readable reports.

These are useful starting points, but they are not a complete offline emulator mode.

## Proposed session design

Use a debug-only simulation session with a scenario ID, fixture version, seed, timing
profile, and session ID. A session selects external behavior and a disposable storage
root before the process-wide container starts. The selection remains fixed for that
process and applies to foreground services as well as screens.

`WebnovelArchiverApp` currently starts constructing the container in `Application.onCreate`,
before `MainActivity` can read launch extras. Therefore, a new activity extra alone is
insufficient. The runner should force-stop the debug package, atomically stage an
app-private session configuration, then launch the requested screen through the existing
dev-launch flow. Startup must validate configuration before constructing dependencies.
Switching back to live mode requires an explicit runner command and a process restart.
Invalid configuration fails startup into a diagnostic state, never live networking.

Use the normal debug application ID on `webnovel_api36`. Connected instrumentation
tests continue to use their existing separate instrumentation application ID.

### External response replacement

Preserve `OpenRouterClient`, source providers, sync/download engines, and repository
transactions. Replace the HTTP response boundary, rather than returning final story or
AI results directly from fake engines.

For the first version, inject an OkHttp application interceptor that returns scripted
responses or transport errors without calling the real network. For source requests,
place it downstream of the real Cloudflare interceptor so challenge responses still
exercise detection and escalation. Inject the scripted Cloudflare renderer separately.
Use an equivalent guarded client for every OpenRouter endpoint, including catalogs,
key usage, chat, Decisions, and images.

Every unexpected request is a scenario failure. Match requests by method, canonical
host/path/query, operation, chapter identity, and per-operation attempt where needed.
Validate meaningful request bodies. Avoid a single global response queue, since parallel
downloads can consume it in an unpredictable order. Consume scripted steps atomically
and keep request histories bounded. Seeded randomness alone does not control thread
ordering; scenarios must either tolerate permitted interleavings or use explicit gates.

Use complete provider-compatible HTML and JSON fixtures, plus small valid raster images.
Share fixtures through a deliberate test/debug resource arrangement; main app code cannot
read files that only exist in `src/test/resources`. Release builds must not include the
scenario catalog or expose its controls.

### Offline guarantee and data isolation

- In simulation, external HTTP requests must terminate in the fixture responder or a
  denial. Do not silently fall through when a fixture is absent or exhausted.
- WebViews use a separate Chromium network stack. Both the hidden renderer and visible
  verification activity need simulation wiring. Disable external browser handoffs,
  authentication, and unhandled external links for the session. Intercepting OkHttp alone
  cannot guarantee offline behavior.
- For routine QA, use scripted renderer outcomes and locally supplied verification
  pages with network loading disabled. For browser integration tests, use a separate
  loopback-only server profile and deny remote navigation and subresources. Check
  redirects and form/JSON requests as well as top-level page loads.
- Keep one repository/storage owner. Extend its root configuration so the simulation
  library, queue, settings, AI usage ledger, reliability state, covers, and backup/recovery
  paths all resolve inside the selected session. Isolate evidence caches too. Audit
  startup migrations and restore recovery before enabling alternate roots.
- Do not read the normal library's API key, cookies, or Patreon session. Seed a dummy
  nonempty API key inside simulation settings so normal key-presence validation still
  runs. Include separate missing-key and rejected-key scenarios.
- Routine simulation should avoid CookieManager entirely. Browser integration tests
  need an isolated WebView data directory chosen before any WebView/CookieManager
  initialization, including the current user-agent and cookie-seeding startup paths.
- Show a persistent simulation indicator. Simulated billing receipts can contain
  nonzero values to exercise cost displays, but remain clearly simulated and confined
  to the disposable ledger.
- Run the basic catalog with emulator connectivity disabled as an independent check.
  Record denied/unmatched requests and require none for a passing ordinary scenario.

This mode simulates server responses while preserving real local app behavior. It is
not a preview that suppresses all writes, so disposable storage is part of the design.

## Timing controls

| Profile | Behavior | What it is for |
|---|---|---|
| Fast | Very short response delays; configured retry and cooldown waits are accelerated consistently. | Routine agent smoke checks. |
| Visible | Scripted seconds-long delays for each stage, such as evidence selection, prompt writing, and image generation. | Observe loading states, navigate away, fold, cancel, and check notifications. |
| Controlled | Hold a selected operation until the runner releases it; optionally release operations in a chosen order. | Reproduce cancellation and overlapping jobs without racing a fixed sleep. |
| Virtual time in tests | Advance a shared test scheduler and clock through long waits without waiting in real time. | Exact retry, cooldown, cache expiry, and scheduling assertions. |

Add a small production time abstraction with normal defaults, extending existing injected
functions where practical. It must keep elapsed deadlines, sleeps, scheduler timeouts,
and countdown displays consistent. Keep persisted wall-clock timestamps distinct from
monotonic elapsed time. Route the remaining engine timestamps, decision retry delays,
queue waits, and relevant UI countdowns through the appropriate injected dependency.

Do not implement acceleration by shortening sleeps while leaving their deadline clock
unchanged; that can create busy loops or incorrect countdowns. Start emulator QA with
fast/visible responses on ordinary time, then add coherent acceleration where needed.
Use virtual time for exhaustive timing tests. It does not automatically accelerate
OkHttp socket deadlines, WebView events, or work on uninjected dispatchers. Keep real
socket timeout tests separate, with short explicit test deadlines.

The coroutine test library provides a shared virtual scheduler, but its documentation
explicitly keeps it in test code. Do not bundle it into the debug app's runtime.
See the [Kotlin coroutine testing documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/).

## Initial scenario catalog

| Area | First scenarios and expected behavior |
|---|---|
| AI descriptions | Automatic evidence selection, manual selection, usable draft, empty/truncated/invalid reply, missing key, 401, 402, 429, and timeout. Applying the draft uses the normal repository path. |
| AI covers | Evidence selection, prompt generation, valid local image bytes, several drafts, selecting/reverting a cover, unsupported image output, and failure at each stage. Cover drafts and job-chip state follow normal behavior. |
| AI timing and lifecycle | Slow selection/text/image stages; retryable Decisions errors; leaving and reopening controls; folding; activity recreation; cancellation where supported; process interruption under the existing recovery policy. Do not assume background survival means process-death resumption. |
| AI settings and costs | Text/decision/image model lists, supported parameters, cached catalogs, fake key counters, exact decimal costs, missing receipt data, failed calls, and cached evidence adding no new receipt. |
| Downloads | Story import and chapter parsing, slow chapters, two stories on one source, independent sources, large-batch preflight, reorder, pause/resume/cancel, chapter 404, malformed HTML, connection loss, retry exhaustion, and process restart. Assert actual saved chapter files and queue states. |
| Rate limits | Script 429 plus Retry-After, then success. Verify all jobs on that source respect the shared cooldown and another source can continue. |
| Cloudflare automatic recovery | Native response is a challenge, scripted browser render succeeds, later chapters use sticky browser transport. Assert the real reliability coordinator transitions. |
| Cloudflare manual recovery | Challenge remains unsolved, source pauses, verification screen opens, local verification succeeds, blocked chapters resume without spending retries. Also cover cancel, still blocked, renderer death, wrong page identity, and a source chapter 404 that must not block the whole source. |
| Other remote requests | Source cover images, Patreon metadata/posts, and any update checks reached by a scenario use fixtures or explicit denial. No real authentication. |

One useful composite scenario is a two-source download with a slow cover-generation job.
One source requires verification, the other continues downloading, and the AI job finishes
while the user is viewing the queue. This exercises shared services and UI updates under
conditions that are expensive and difficult to reproduce live.

Test archive read-only rules and evidence cache hit/miss behavior using explicit fixture
variants. Never depend on whatever happens to be in the developer's library.

## Agent workflow and reports

Proposed interface:

```text
scripts/simulate.py list
scripts/simulate.py run ai-cover-slow --screen aicontrols --timing visible
scripts/simulate.py run downloads-verification --screen queue
scripts/simulate.py release <session-id> <operation-id>
scripts/simulate.py report <session-id>
scripts/simulate.py stop
```

The runner resolves a single healthy emulator serial and validates the AVD, stages
configuration, launches directly onto the selected screen, and waits for explicit state
conditions. It never targets a physical phone. It should seed through repository-owned
setup and expose stable fixture story/chapter IDs for existing dev launch arguments.

Write a versioned JSON report containing session/scenario/fixture IDs, seed, timing mode,
logical and actual durations, completed stages, request attempts, retry/cooldown events,
assertion results, unmatched requests, and final queue/job/storage summaries. Sanitize
request summaries so keys, cookies, and chapter text never enter the report. Reuse existing
performance instrumentation for measured app timings and attach its session ID when used.
Simulated service latency is a test input, not a measurement of real provider performance.

Separate commands to reset a disposable session and continue one after process death.
Persist the script cursor and consumed operations when a recovery scenario needs them;
an ordinary restart must not accidentally replay earlier successes. Do not clear the
normal debug app's data. Mark interrupted reports as incomplete rather than passing.

## Implementation order and acceptance gates

1. Establish session startup, isolated storage, offline denial, and reporting. This is
   the largest shared prerequisite. Prove an unknown request fails and the normal debug
   library, settings, cookies, and ledger remain untouched. Verify service-only startup
   uses the same session configuration.
2. Ship a useful AI slice. Add fake catalogs, Decisions responses, text, image bytes,
   and receipts with fast/visible delays. Complete description and cover flows without
   a valid key and with emulator internet disabled. Include a held job for lifecycle QA.
3. Add source fixtures and downloads. Begin with Royal Road's existing fixture shape,
   then cover the other provider request patterns, including forms, JSON, and reusable
   multi-chapter pages. Validate saved files, ordering, retries, and restart recovery.
4. Add Cloudflare scenarios. Reuse the real interceptor and reliability coordinator,
   script typed renderer outcomes, and wire the visible solver to local verification.
   Validate per-source pauses, other-source progress, and automatic resume.
5. Complete timing injection and local transport tests. Cover virtual-time boundaries,
   cancellation, real socket timeouts, broken connections, redirect behavior, and selected
   real WebView flows against a loopback server. MockWebServer is already a unit-test
   dependency; any instrumentation dependency change requires the normal lockfile update.
6. Make simulation the default agent QA path. Update `android/AGENTS.md`, emulator QA
   skills, and the relevant developer documentation. Keep live checks explicitly separate.
   A small developer scenario picker can follow after the runner is useful.

Each phase should land as a reviewable change with narrow tests first. Production Kotlin
changes require relevant tests, Kotlin lint, and detekt. Cross-cutting startup/storage/time
changes require the full local gate. UI/service changes also require affected-flow QA on
the debug emulator, including both displays and a live fold transition. Connected tests
must keep using the separate instrumentation sandbox. No release artifact is needed.

## What simulation proves

It can prove that a chosen response/error sequence produces the correct app behavior,
including progress, retries, persistence, cancellation, and recovery. Local-server tests
extend that to selected real HTTP and browser interactions.

It cannot prove model output quality, current site markup, real provider latency, real
credentials, or Cloudflare's live challenge/TLS/fingerprint decisions. Keep occasional
small, explicitly authorized live compatibility checks separate from routine offline QA.
Recorded fixtures should be sanitized and reviewed; automatic record-on-miss would defeat
the zero-cost, zero-external-request guarantee.
