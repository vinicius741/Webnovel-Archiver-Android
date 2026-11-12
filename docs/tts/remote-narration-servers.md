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
| `setup.sh` | Installs into one folder per user: Rust (rustup) when missing, the sherpa-onnx 1.12.21 shared libraries (`lib/`), the built server (`bin/narration-server`), the model (downloaded once) and a token. `--service` adds a launchd agent (macOS) or a systemd user unit (Linux). `--uninstall` removes the service. Re-running it upgrades a Python install in place and keeps the token. |

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

### Memory

The server checks memory every second and adapts. Thresholds come from the machine's RAM:
- **Reserve**: memory kept free for everything else. 20% of RAM, 1.5-4 GB (3.1 GB on a 16 GB machine).
- **Critical**: a third of the reserve, at least 600 MB.

| Level | When | What the server does |
|---|---|---|
| Normal | Available above the reserve | Slots = those already paid for + as many more as fit above the reserve (learned cost per slot, 256 MB to start), up to `--max-slots`. |
| Low | Available below the reserve, Linux PSI `some` ≥ 20%, swap (zram) three-quarters used, or macOS pressure "warning" | One slot. After 10 s at Low, it reloads the model once to give back what extra slots made ONNX Runtime keep. Its arena never shrinks on its own; measured 932 → 489 MB. |
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

On macOS, "available" is the kernel's own estimate (`kern.memorystatus_level`), and the kernel's pressure
level drives Low and Critical. Swap does not count there: macOS grows swap on demand, so nearly full
swapfiles are normal.

Kernel backstops in the systemd unit: `OOMScoreAdjust=500` (if memory still runs out, the OOM killer takes
this server before the desktop or a game, and systemd restarts it in 5 s), `MemoryMax=35%`, and `Nice=10`.

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
| `NarrationQueueDocument.servers` / `generateOn` | The computers (name, address, token) and the chosen one. Stored in `filesDir/narration/queue.json`, which backups leave out, so the token never leaves the phone. |
| `NarrationServerClient` | HTTP/1.1 over a plain socket, with kept-alive connections reused across sentences (one per sentence in flight, dropped after 45 s idle), so a chapter costs a handful of TCP and Tailscale handshakes instead of one per sentence. A reused connection the server closed is retried once on a fresh one. Cancelling closes the socket. A `503` becomes `NarrationServerBusy` (`retryAfterMs`, `stopped`). It deliberately bypasses Android's cleartext policy, which would otherwise have to allow plain HTTP for the whole app. Tailscale encrypts the traffic. On a home LAN it is plain HTTP with a token. |
| `NarrationServerPlanning` | Compatibility (protocol 1, model `kokoro-en-v0_19`), sentences in flight (one per model copy plus one, at most 9), form checks, and the test summary. |
| `AndroidNarrationSynthesizers` | A Kokoro job goes to the chosen computer when there is one. `location(voice)` changes when the choice or the server's address or token changes. Android-engine voices always generate on the phone. |
| `RemoteKokoroNarrationSynthesizer` | Prepares the text with `KokoroTextPlanning.spokenText`, as the phone does, and sends it. It uses the phone's voice key (`KokoroVoices.voiceKey`), so clips from the phone and a computer are interchangeable and mix within a chapter. One retry on a dropped connection. While the server answers `503` (short of memory), it waits `retryAfterMs` and asks again, and reports a stop through `waitingFor`. The chapter does not fail. A pause, a reorder or a place change interrupts the wait at once (the generator races each sentence against them). |
| `NarrationGenerator` | Keeps `parallelism` sentences in flight and writes clips in order. At each sentence it checks the job and the place: choosing another place switches at the next sentence and keeps the clips. A `NarrationServerProblem` (unreachable, wrong token, wrong model) **pauses the queue** with the reason and `pausedForServer`, instead of failing every job. Choosing a place resumes it, and so does Resume. A 400 or 500 for one sentence is retried once, then fails only that chapter. Generation speed is recorded per place (`engine@serverId`). |
| `NarrationProgress.waitingFor` | While a computer has stopped for memory, the banner reads "Waiting for <name> · 40%" with the server's reason, and so do the queue row and the notification. Fewer slots are not shown: sentences just take turns. After 15 minutes of a stop, generation pauses with the reason (`pausedForServer`), so the phone does not stay awake polling for hours. The in-flight count follows the server's `X-Slots` (slots + 1). |
| `feature/narration/NarrationServersUi.kt` | The **Generate on** section of Audio generation: chips for "This phone" and each computer, Add / Edit / Test / Remove. The test sends one sentence per model copy at once and reports the total speed. The banner and queue rows read "Generating on <name>". |

## Setting it up

1. On the computer: `scripts/narration-server/setup.sh --service`. It prints the token and the LAN and
   Tailscale addresses.
2. In the app: Audio generation → Generate on → **Add a computer**. Enter a name, the address (`ip` or
   `ip:port`) and the token. Saving runs a connection test.
3. Pick the computer's chip. Kokoro chapters queued from then on, and the one in progress, generate there.

Over Tailscale, use the computer's `100.x` address, which works from anywhere. A Linux firewall may need
port 8790 open for LAN access.

## Verified

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
