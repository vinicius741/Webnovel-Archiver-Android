# Offline agent testing

The debug app can run AI, source imports, downloads, and Cloudflare recovery entirely
offline. Production clients, parsers, engines, services, and repository transactions
consume scripted responses. Simulation writes into a disposable library and uses a
dummy API key. It never falls back to a paid API or a public server.

## Start a session

Build and install the debug APK on the single selected `webnovel_api36` emulator using
the existing build/install workflow. Then run from the repository root:

```bash
python3 scripts/simulate.py list
python3 scripts/simulate.py run ai-cover --until completed
python3 scripts/simulate.py run ai-description --until completed
python3 scripts/simulate.py run downloads-verification --until completed --auto-verify
python3 scripts/simulate.py run sources-all --until completed
python3 scripts/simulate.py suite
```

`run` normally returns after the session is ready so an agent can interact with the
screen while work continues. `--until completed` waits for terminal assertions. An
expected error scenario passes when the intended fault produces a failure. Unexpected
requests fail the run. `suite` runs scenarios sequentially with fast timing and local
verification, and returns a failure exit code if any scenario fails or times out.

The runner resolves a healthy `emulator-*` serial, checks the AVD name, and uses only
the debug application ID. Use `--serial emulator-5554` before the subcommand to select
an emulator explicitly when several are running. It cannot target a physical phone.
No valid OpenRouter key, login, or internet connection is needed.

## Timing and manual interaction

```bash
# Visible loading states, progress, notifications, navigation, and fold transitions
python3 scripts/simulate.py run ai-cover-slow

# Keep the real image request pending until explicitly released
python3 scripts/simulate.py run ai-cover --hold image --until held
python3 scripts/simulate.py release SESSION_ID image
python3 scripts/simulate.py wait SESSION_ID --until completed

# Seed data and perform every action through the normal UI
python3 scripts/simulate.py run ai-cover --manual --screen aicontrols

# Restart the active session onto a chosen screen without reseeding
python3 scripts/simulate.py continue SESSION_ID --screen queue --until ready
python3 scripts/simulate.py report SESSION_ID
```

`fast` uses short response delays and a 100x logical clock. `visible` uses ordinary
time, about four seconds for text generation and ten seconds for an image. `controlled`
requires `--hold` and uses ordinary time unless `--speed` is supplied. Valid hold stages
are `decisions`, `description`, `prompt`, `image`, and `source`. `release` accepts a
stage name or one operation ID from the report. Stage release also permits later
requests at that stage. Holds expire after two real minutes and respect cancellation.

`--speed` accepts 1 through 1000. Scheduler clocks, pacing waits, cooldowns, decision
retry delays, queue timeouts, and countdown reads use the same time mapping. Billing
timestamps remain real. Coroutine virtual time is reserved for unit tests. Socket
deadlines and Android event delivery remain real time.

New sessions receive unique IDs. `--session NAME` supplies one explicitly; an existing
session cannot be silently overwritten. `--seed` is recorded in the report and fake
receipt identities. Request matching and per-request cursors determine response order;
the seed does not make concurrent Android thread scheduling deterministic.

The synthetic library has stable story IDs `rr_990001`, `rr_990002`, and `ffn_990003`.
The primary story's six chapter files are present in AI/manual-screen scenarios.
Download scenarios start with undownloaded chapters. `sources-all` starts empty and
imports the six supported provider fixtures. Existing `dev_start_screen` and story
arguments still work while simulation is enabled.

## Scenarios

The shared catalog is `android/app/src/debug/assets/simulation/catalog.json`.
It includes automatic/manual AI evidence, missing credentials, archived stories,
empty/truncated replies, auth/credit/rate-limit failures, invalid images and judgments,
missing billing metadata, cache reuse, cover versions, slow jobs, downloads across
independent sources, bulk preflight, rate limiting, timeout/offline recovery, removed
chapters, parse failures, exhausted retries, and Cloudflare render outcomes.

`composite` generates a slow cover while downloads run and one source waits for
verification. `cloudflare-protocols` exercises the real interceptor's same-origin form
POST and JSON GET handling with scripted browser results. `patreon-public` reads local
public JSON without a session cookie. Unknown Patreon/authentication requests are denied.

For manual verification, tap the normal queue card to open the local verification
screen. Its successful check calls the production source-clearance and queue-resume
path. `--auto-verify` or `python3 scripts/simulate.py verify SESSION_ID` drives that same
screen through a debug-only control activity. The `still-blocked` scenario retains its
block. Cancel closes the screen without verifying.

Automatic cover jobs still use the shared coordinator and foreground service. Surviving
navigation does not imply process-death resumption. After interruption, the report marks
AI work incomplete; start a new scenario or interact manually to retry. Durable download
jobs resume through the existing recovery path when `continue` restarts a session.

## Reports and cleanup

App-side configuration and state live under:

```text
files/dev_simulation/enabled
files/dev_simulation/session.json
files/dev_simulation/sessions/SESSION_ID/report.json
files/dev_simulation/sessions/SESSION_ID/files/webnovel_archiver/
files/dev_simulation/sessions/SESSION_ID/cache/
```

The runner copies reports into gitignored `simulation-runs/SESSION_ID/report.json`
and `report.md`. Reports contain scenario/fixture versions, timing, seed, bounded events,
request fingerprints and attempts, verification state, queue results, receipt counts,
and named assertions. Request bodies, credentials, cookies, and chapter text are excluded.
An interrupted or nonterminal report is never a pass. Switching scenarios archives the
previous run and marks unfinished work interrupted.

```bash
# Explicitly return to the normal debug library, preserving all simulation evidence
python3 scripts/simulate.py stop

# Delete one inactive disposable session
python3 scripts/simulate.py reset SESSION_ID
```

Never use `pm clear`, uninstall, or restore a backup to switch modes. A session is
selected before container construction and stays fixed for the entire process, including
service-only startup. The enable marker survives process death. Malformed configuration
produces a startup error and leaves external access disabled. Only `stop` removes the
enable marker. Do not manually delete only `session.json` to leave simulation.

Simulation overrides the storage context's files, cache, preferences, backup, and restore
locations. It skips normal cookie seeding, user-agent WebView initialization, and normal
theme-hint reads/writes. Coil uses the guarded responder with disk caching disabled.
Reader WebViews block network loads, browser handoffs are disabled, and the verification
screen is native local UI. On Android 28+, WebViews also use a separate simulation profile.
The supported runner target is the API 36 emulator.

## Extending fixtures and validation

`SimulationReplay` reads exact method/URL/form matches from
`android/app/src/debug/assets/simulation/replay.json`. Its files reuse sanitized parser
fixtures and synthetic text/images. Query order and fragments are normalized; unexpected
form fields, paths, or hosts are denied. Add a route and its local payload deliberately.
There is no automatic record-on-miss or live capture mode. Never add API keys, cookies,
private chapters, or unreviewed recordings.

Debug implementations and assets are shared with the instrumentation build type through
Gradle source sets. Release has an inert factory and contains no scenario catalog or
control activity. No runtime mocking library or new dependency is required.

```bash
python3 -m unittest discover -s scripts/tests -p 'test_simulate.py'
android/gradlew -p android :app:testInstrumentationUnitTest --tests '*.Simulation*Test' --tests '*.AppTimeTest'
android/gradlew -p android :app:lintKotlin :app:ci -x copyDebugApkToProjectRoot
```

`OfflineBrowserDeviceTest` runs in the separate instrumentation sandbox and exercises
real Chromium form/JSON requests against a server bound to `127.0.0.1`, plus storage
isolation and reader network blocking. Install the raw instrumentation APK/test APK and
run that class with explicitly targeted ADB, following repository device rules. Only the
instrumentation manifest allows the local cleartext HTTP test server.

Simulation checks application behavior under controlled inputs. It does not evaluate AI
writing/art quality, current site markup, actual provider latency, or Cloudflare's live
TLS/challenge decisions. Keep those occasional live compatibility checks separate.
