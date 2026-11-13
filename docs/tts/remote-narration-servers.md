# Generating narration on your computers

Kokoro chapters can generate on one of the owner's computers instead of the phone. The computer runs
the same model, generates several sentences at once, and the app writes the audio into the same chapter
packs the phone makes. Playback does not change: a chapter plays from its pack on the phone either way.

## Why

Measured on 2026-11-11 with the phone's model (`kokoro-en-v0_19`, Bella), in seconds of audio
generated per second:

| Where | Speed | A 20-minute chapter |
|---|---|---|
| Galaxy Z Fold7, warm (the app's speed test) | 1.56x | about 13 min |
| MacBook Pro M1 Pro, 4 model copies x 2 threads | 6.6x (6.9x over HTTP) | about 3 min |
| Bazzite (Ryzen Z1 Extreme), 4 copies x 2 threads | 7.1x | about 3 min |
| Emulator → Mac server, in the app (network and emulator included) | 5.4x | about 4 min |
| The same with the Rust server (2026-11-12, below) | 6.0x | about 3.5 min |

The owner listens at 1.8x. A warm phone generates 1.56x, so it falls behind even when it generates
continuously. A computer stays about 3.5x ahead, and the phone stays cool. GPUs do not help with this
model: CoreML rejects its dynamic shapes, MLX ships only Kokoro v1.0 (a different voice), and the
Bazzite iGPU runs only a PyTorch v1.0 build. So the server runs on CPU.

## The server (`scripts/narration-server/`)

A Rust program (since 2026-11-12; it replaced a Python server with one model copy per worker process).
It calls the sherpa-onnx C API at **1.12.21, the version the app ships**, so clips come from the same
library as the phone's. The Python server ran the PyPI build 1.12.26.

| File | Role |
|---|---|
| `src/sherpa.rs` | FFI to the sherpa-onnx C API (struct layouts from its `c-api.h` at v1.12.21). PCM conversion matches `TtsWavWriter` exactly (clamp, ×32767, round half up). |
| `src/engine.rs` | **One shared model** and its generation slots: load on first use, recycle, unload when idle or short of memory. |
| `src/memory.rs` | Memory probes (Linux: `/proc/meminfo`, PSI, cgroup v2 limits; macOS: `host_statistics64`, the kernel's memory-pressure level, own footprint) and the pure `decide` policy. |
| `src/http.rs` | HTTP/1.1 with keep-alive, one thread per connection, bounded reads. |
| `setup.sh` | Installs into one folder per user: Rust (rustup) when missing, the sherpa-onnx 1.12.21 shared libraries (`lib/`), the built server (`bin/narration-server`), the model (downloaded once) and a token. `--service` adds a launchd agent (macOS) or a systemd user unit (Linux). `--uninstall` removes the service. Re-running it upgrades a Python install in place and keeps the token; without `--service` it leaves the Python files alone while a service still runs them. |

Data folders: `~/Library/Application Support/WebnovelNarration` on macOS,
`~/.local/share/webnovel-narration` on Linux. Logs: `server.log` there on macOS,
`journalctl --user -u webnovel-narration.service` on Linux. Port 8790 (`NARRATION_PORT` overrides it).
On a Mac, the server holds an idle-sleep assertion (`caffeinate -i`) for two minutes after each request,
so a batch does not stop halfway. Closing the lid still sleeps the Mac. On Linux, the service starts at
login. `loginctl enable-linger` keeps it running while logged out.

Options (`narration-server --help`): `--threads` (default: one per performance core), `--max-slots`
(sentences at once; default 4 on 8+ cores), `--reserve-mb`, `--critical-mb`, `--idle-unload-s` (default
300), `--verbose`. `narration-server --bench` measures speed and memory at each slot count on the
machine; use it to tune a new computer.

### One shared model

ONNX Runtime's `Session::Run` is thread-safe, and sherpa-onnx serializes the espeak-ng phonemizer behind
a mutex. So every request runs on **one** model copy, and up to `--max-slots` sentences generate on it at
once, all sharing one intra-op thread pool sized to the performance cores. Measured on Bazzite (Ryzen Z1
Extreme, 8 cores), 32 sentences, same machine and day:

| Layout | Speed | Peak memory |
|---|---|---|
| 4 model copies × 2 threads (the Python layout, via the same C API) | 6.40x | 2.4 GB |
| 1 model, 1 slot × 8 threads | 4.64x | 0.84 GB |
| 1 model, 2 slots × 8 threads | 5.55x | 1.04 GB |
| 1 model, 4 slots × 8 threads (default) | **6.88x** | **1.3 GB** |
| 1 model, 6 slots × 8 threads | 7.06x | 1.7 GB |

Over HTTP with five sentences in flight (as the app sends them) and `Nice=10`: 6.67-6.82x, peak 1.4-1.6 GB.
The Python server held about 3 GB from start to stop. The Rust server holds ~10 MB before the model
loads, and ~60 MB after an idle unload.

Measured on the MacBook Pro (M1 Pro, 8 performance + 2 efficiency cores, 32 GB, macOS 26) with
`--bench` (16 sentences), 2026-11-12. Speeds are from runs on a quiet machine:

| Layout | Speed | Peak memory |
|---|---|---|
| 4 model copies × 2 threads (the Python server, measured the day before) | 6.6x | 3.9 GB, held from start to stop |
| 1 model, 1 slot × 8 threads | 5.1x | 0.58 GB |
| 1 model, 2 slots × 8 threads | 6.4x | 0.66 GB |
| 1 model, 4 slots × 8 threads (default) | **7.3x** | **0.78 GB** |
| 1 model, 6 slots × 8 threads | 7.5x | 0.91 GB |
| 1 model, 5 slots × 10 threads (efficiency cores too) | 6.7x | 0.83 GB |
| 1 model, 8 slots × 2 threads | 6.4x | 1.16 GB |

So the defaults hold on Apple silicon: one thread per **performance** core (the efficiency cores slow
the rest down) and four slots; more slots add 2-3%. Over HTTP with five in flight: 7.2-7.4x from a
terminal and 6.9x as the launchd service at `Nice` 10, with other work on the machine. Real chapters have
longer sentences than the benchmark, so the server holds 1.3-1.7 GB while it generates them, 2 MB
before the model loads and 16-18 MB after an idle unload. As a background-priority process macOS holds
it to the efficiency cores (0.22x against 5.1x on one slot), so the agent runs as `ProcessType`
`Interactive`.

### Memory

The server checks memory every second and adapts. Thresholds come from the machine's RAM:
- **Reserve**: memory kept free for everything else. 20% of RAM, 1.5-4 GB (3.1 GB on a 16 GB machine).
- **Critical**: a third of the reserve, at least 600 MB.

| Level | When | What the server does |
|---|---|---|
| Normal | Available above the reserve | Slots = those already paid for + as many more as fit above the reserve (learned cost per slot, 256 MB to start), up to `--max-slots`. |
| Low | Available below the reserve, Linux PSI `some` ≥ 20%, swap (zram) three-quarters used, or macOS pressure "warning" | One slot. After 10 s at Low, it reloads the model once to give back what extra slots made ONNX Runtime keep. Its arena never shrinks on its own; measured 932 → 489 MB on Linux, 1030 → 520 MB on the Mac. It skips that reload when memory is so close to the critical line that the model could not load again, and keeps the one slot. |
| Critical | Available below critical, PSI `full` ≥ 10%, swap almost gone while low, or macOS pressure "critical" | **Stops generating.** Requests get `503` + `Retry-After: 20`. The model is unloaded as soon as running sentences finish (→ ~40-60 MB), and `malloc_trim` hands the heap back. |

Hysteresis: a level is left only when memory clears its line by 384 MB, so zram holding memory right at a
line does not make it flap. The model reloads only with room for it plus that 384 MB above the critical
line (learned load cost, 900 MB to start), and not within 15 s of leaving Critical. That cooldown
doubles, up to 5 minutes, each time a reload is followed by another stop within a minute. A refused
request is told when to ask again. The learned costs drift back toward the defaults at each load, so
one unusual chapter does not lower the slot count for good. A model that fails to load twice in a row
(missing or corrupt files) is reported as an error (`500`), not as busy. A process cgroup limit
(systemd `MemoryMax`, a container) caps slots to what fits under it, separately from the machine's
reserve. An idle model is unloaded after 5 minutes. It reloads in about a second (0.75-1.0 s measured) on
the next request, and `/v1/info` starts loading it ahead of the first sentence. A sentence whose client
hung up while it waited for a slot (the app paused or switched) is dropped instead of generated.

Kernel backstops in the systemd unit: `OOMScoreAdjust=500` (if memory still runs out, the OOM killer takes
this server before the desktop or a game, and systemd restarts it in 5 s), `MemoryMax=35%`, and `Nice=10`.

#### macOS

- **Available** is the kernel's own figure (`kern.memorystatus_level`): the share of memory that is
  neither wired nor compressed. It counts memory apps hold, the server's own included, so loading or
  unloading the model does not move it, and it stays high until the Mac really is short. The kernel's
  pressure level (`kern.memorystatus_vm_pressure_level`) is what usually drives Low ("warning") and
  Critical ("critical") there. Swap does not count: macOS grows swap on demand, so nearly full
  swapfiles are normal.
- **Freed memory goes back at once.** macOS's allocator caches freed large blocks, and they stay charged
  to the process: after unloading the model the server still held 1560 MB, and no call releases them.
  The allocator reads `MallocLargeCache` only as a process starts, so the server starts itself again
  with `MallocLargeCache=0` (same pid, so launchd is unaffected). Measured: 16 MB after an unload,
  about 250 MB less while generating, and the same speed.
- **No kernel backstop.** launchd offers no memory limit or kill-first setting for an agent, so the
  governor is the only protection. The agent sets `Nice` 10 (apps in use go first when the processor
  is contended; no cost otherwise) and `ProcessType` `Interactive` (see the table above).
- **Addresses.** Tailscale's command lives inside the app (`/Applications/Tailscale.app`), on no
  `PATH`. `setup.sh` finds it and prints the Tailscale address. Started by launchd, that command
  never returns, so the server gives it 3 seconds and looks addresses up beside serving, never
  before it; the service's log lists only the LAN address.

### Protocol 1

Unchanged for existing apps; the additions are optional. Every request carries
`Authorization: Bearer <token>`. Tokens are 12 characters from `23456789abcdefghjkmnpqrstuvwxyz`,
grouped `xxxx-xxxx-xxxx`, and compared case-insensitively.

- `GET /v1/info`: `{protocol, model, sampleRate, workers, threadsPerWorker, name, server, version, memory}`.
  `workers` is the most sentences at once (the app keeps one more in flight; the rest wait in the
  server for a slot, up to 60 s).
- `POST /v1/synthesize` with `{"text", "speakerId"}`: `audio/wav`, mono 16-bit PCM, at 1x. Headers
  `X-Generate-Ms`, `X-Queue-Ms`, `X-Slots`.
- `GET /v1/status`: memory level and thresholds, slots, model loaded or not, counters.
- Errors are JSON `{"error"}`: 400 bad request, 401 wrong token, 500 generation failed, and **503** while
  memory is short: `Retry-After` plus `{"retryAfterMs", "memory", "stopped"}`.
- Connections are kept alive (HTTP/1.1), closed after 75 s idle. `GET /health` needs no token.

## The app

| Piece | Responsibility |
|---|---|
| `NarrationQueueDocument.servers` / `generateOnIds` / `phoneFallback` | The computers (name, local-network `address`, `tailscaleAddress`, token), the chosen ones, and whether this phone stands in while none can generate. Either address may be blank. Stored in `filesDir/narration/queue.json`, which backups leave out, so the token never leaves the phone. Files from before 2026-11-13 hold one `generateOn` id; `NarrationQueue` moves it into `generateOnIds` on load. |
| `reachServer` (in `NarrationServerClient.kt`) | Connects on the first address that answers: the local network first, then Tailscale (`NarrationServerPlanning.routes`). The local address gets a 2.5 s connect timeout while Tailscale is left to try, since away from home it usually does not answer at all. When neither answers, one message covers both; a server that answered (wrong token, another model) is reported over one that could not be reached. Used to open generation, by the connection test, and by the online check. |
| `NarrationServerMonitor` | The online check. While Audio generation is visible, the screen asks every 5 s for the computers not checked in the last 30 s; each check calls `GET /v1/status` on each address in turn (10 s read timeout), which checks the token but neither loads the model nor keeps a Mac awake. Rows read "Online · local network", "Online · Tailscale", "Offline", "Online · … · wrong token" or "stopped, short of memory", with a coloured dot and both addresses. A connection test records its result too. |
| `NarrationServerClient` | HTTP/1.1 over a plain socket, with kept-alive connections reused across sentences (one per sentence in flight, dropped after 45 s idle), so a chapter costs a handful of TCP and Tailscale handshakes instead of one per sentence. A reused connection the server closed is retried once on a fresh one. Cancelling closes the socket. A `503` becomes `NarrationServerBusy` (`retryAfterMs`, `stopped`). It deliberately bypasses Android's cleartext policy, which would otherwise have to allow plain HTTP for the whole app. Tailscale encrypts the traffic. On a home LAN it is plain HTTP with a token. |
| `NarrationServerPlanning` | Compatibility (protocol 1, model `kokoro-en-v0_19`), sentences in flight (one per model copy plus one, at most 9), form checks, and the test summary. |
| `AndroidNarrationSynthesizers` | A Kokoro job goes to the chosen computers when there are any. `location(voice)` (`NarrationPlaces.locationKey`) changes when the choice, the fallback, or a chosen server's addresses or token change. Android-engine voices always generate on the phone. |
| `NarrationPlacePool` | The chosen computers as one synthesizer (see [Several computers](#several-computers)). Its `parallelism` is the sum of their slots, so the generator keeps that many sentences in flight; each sentence goes to the computer with the most free slots. |
| `RemoteKokoroPlace` (in `NarrationSynthesizers.kt`) | One computer in the pool. Prepares the text with `KokoroTextPlanning.spokenText`, as the phone does, and sends it. It uses the phone's voice key (`KokoroVoices.voiceKey`), so clips from the phone and the computers are interchangeable and mix within a chapter. One retry on a dropped connection, after finding the computer again through `reachServer` (so leaving home mid-chapter moves to Tailscale instead of failing over). Sentences that failed together reconnect once, and when that reconnection fails the others fail at once instead of each trying again. A `503` goes up to the pool. |
| `NarrationGenerator` | Keeps `parallelism` sentences in flight and writes clips in order. At each sentence it checks the job and the place: choosing another place switches at the next sentence and keeps the clips. A `NarrationServerProblem` (unreachable, wrong token, wrong model) **pauses the queue** with the reason and `pausedForServer`, instead of failing every job. Choosing a place resumes it, and so does Resume. A 400 or 500 for one sentence is retried once, then fails only that chapter. Generation speed is recorded per place (`engine@serverId`). |
| `NarrationProgress.waitingFor` | While every chosen computer has stopped for memory, the banner reads "Waiting for <name> · 40%" with the server's reason, and so do the queue row and the notification. While another computer still generates, nothing is shown. Fewer slots are not shown either: sentences just take turns. A computer stopped for 15 minutes counts as out, so with no other place left generation pauses with the reason (`pausedForServer`) and the phone does not stay awake polling for hours. Each computer's slots follow its `X-Slots` (slots + 1). |
| `feature/narration/NarrationServersUi.kt` | The **Generate on** section of Audio generation: chips for "This phone" and each computer, any number of computers chosen at once (`NarrationServerPlanning.tapGenerateOn`), a row per computer with its online status (`ServerRows`, redrawn in place as checks return), Add / Edit / Test / Remove. The test sends one sentence per model copy at once and reports the total speed. The banner and queue rows read "Generating on <name>", or "Mac + Bazzite" while several share a chapter. Speeds are kept per computer and per combination (`kokoro@mac+bazzite`); a combination not measured yet adds up its computers' speeds for the estimate. |

## Several computers

Choose several computers and they share each chapter: `NarrationPlacePool` sends each sentence to the
computer with a free slot, and the generator still writes clips in sentence order. Two computers at
about 6x each generate about 11-12x in the app: a 20-minute chapter in about 1.8 minutes instead of
3.5, a 30-chapter batch in under an hour.

| What happens | What the pool does |
|---|---|
| A computer cannot be reached, rejects the token or runs another model | It is set aside and its sentences go to the others at once. A background probe (`reachServer` + `/v1/status`) checks it after 15 s, then twice as long each time up to 5 minutes, and it rejoins when it answers and is not stopped for memory. |
| A computer answers `503`, stopped for memory | It gets no sentences until its `Retry-After`; the others continue. "Waiting for…" shows only when no computer can generate. A computer stopped for 15 minutes counts as out. |
| A computer answers `503` short of a stop (a slot wait timed out, too many connections) | Only that sentence waits its `Retry-After`; the computer's other slots keep working. |
| Every chosen computer is out | Generation pauses (`pausedForServer`) with the most useful reason: a problem the user must fix (a wrong token) over computers that could not be reached ("Can't reach Mac or Bazzite…"). |
| Every chosen computer is out and **This phone** is also chosen | This phone generates, one sentence at a time, until a computer is back. Sentences still waiting for the phone then go to that computer, and the phone frees its model. Without the Kokoro model installed it pauses as above. |
| A sentence returns 400/500 | The generator's one retry may land on another computer. |
| Measured speed | Recorded for the computers that generated the chapter (`kokoro@mac+bazzite`, or `kokoro@bazzite` when the Mac was out throughout). A chapter that mixed places (the phone stood in, or a computer dropped or rejoined) records no speed. |

**This phone** never generates beside the computers. It is about 4x slower, and clips are written in
order, so the computers would sit idle behind its sentence. With computers chosen, its chip only
means "stand in while none can generate". Leaving no computer chosen generates on this phone, as
before.

## Setting it up

1. On the computer: `scripts/narration-server/setup.sh --service`. It prints the token and the LAN and
   Tailscale addresses.
2. In the app: Audio generation → Generate on → **Add a computer**. Enter a name, the local network
   address and the Tailscale address (`ip` or `ip:port`; either may be left blank) and the token.
   Saving runs a connection test. One entry per computer covers home and away: the app tries the local
   network first and falls back to Tailscale.
3. Pick the computer's chip, or several. Kokoro chapters queued from then on, and the one in progress, generate there. Also pick **This phone** to keep generating on the phone while no computer can be reached.

The Tailscale address is the computer's `100.x` address (or its MagicDNS name), which works from anywhere. A Linux firewall may need
port 8790 open for LAN access.

## Verified

- Several computers (2026-11-13): `NarrationPlacePoolTest` (sentences spread over two computers and their slots, one dropping mid-chapter, one coming back through the probe, every computer out with one message, the phone standing in and stepping back, a stop waited out and shown, a stop that lasts 15 minutes, one computer holding back while the other generates) and `NarrationGeneratorTest.computersSharingAChapterWriteItInOrderEvenWhenOneDrops`.

- Unit tests: `NarrationServerClientTest` (token, WAV parsing, 401 vs 500, closed port),
  `NarrationServerPlanningTest`, and `NarrationGeneratorTest` (four sentences in flight written in
  order, an unreachable server pausing with clips kept, switching place mid-chapter).
- Emulator, end to end, against the Mac's launchd service at `10.0.2.2:8790`: the connection test, and a
  real chapter (431 sentences) generated at 5.4x real time with the banner showing the computer.
- Rust server (2026-11-12, Bazzite): unit tests for the memory policy, the PCM conversion and the token
  check; output identical across runs. Squeezed with a memory hog during a five-in-flight load test, it
  dropped to one slot below the reserve, reloaded to shrink (932 → 489 MB), and returned to four slots
  when memory came back. With the reserve forced above what was free, it unloaded (36 MB), answered
  `503` with `Retry-After` instead of loading, and reloaded and finished the run once memory returned. No
  request failed in any run. Forced into Critical, it stopped within a second (0 slots, `503`), unloaded to 37 MB once its two running sentences finished, and resumed by itself. `NarrationServerClientTest` covers kept-alive reuse, a server-closed
  connection and `503` parsing.
- Rust server (2026-11-12, MacBook Pro M1 Pro, macOS 26): built and installed with `setup.sh --service`
  over the Python install (token kept), 14 unit tests, clippy clean (also for Linux x64 and arm64).
  The launchd service generated 48 sentences with five in flight at 6.9x with no failure, and unloaded
  its idle model after 5 minutes (1713 → 18 MB). The levels
  were walked for real during load tests, by wiring memory (`mlock`) with the thresholds set just
  under what was available: below the reserve it dropped to one slot and reloaded to shrink
  (1030 → 520 MB); close to the critical line it kept its one slot instead of unloading; below critical
  it stopped within a second, answered `503` + `Retry-After: 20`, unloaded to 16 MB, and resumed by
  itself when the memory came back. 96 of 96 sentences arrived; none failed. The idle-sleep assertion
  (`caffeinate`) showed in `pmset -g assertions` during requests.
- Emulator on the Mac, end to end, with the app built from the same change (2026-11-12): a queued
  chapter (370 sentences) generated on the launchd service at 6.0x real time in the app and was
  published. With a test instance forced into Critical mid-chapter, the banner read "Waiting for Mac
  Pro · 56%" with the server's reason, the notification "waiting for Mac Pro", and generation resumed by
  itself when the memory came back.
