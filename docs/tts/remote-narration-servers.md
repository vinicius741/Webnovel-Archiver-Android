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

| File | Role |
|---|---|
| `narration_server.py` | Standard-library HTTP server. It loads N model copies in worker processes (sherpa-onnx, `provider=cpu`). Defaults to 4 x 2 threads on 8+ cores. |
| `setup.sh` | Installs into one folder per user: a venv, the model (downloaded once), a token. `--service` adds a launchd agent (macOS) or a systemd user unit (Linux). `--uninstall` removes the service. |
| `requirements.txt` | `sherpa-onnx==1.12.26`, the closest PyPI build to the app's 1.12.21. 2.x cannot phonemize `kokoro-en-v0_19` without a lexicon. |

Data folders: `~/Library/Application Support/WebnovelNarration` on macOS,
`~/.local/share/webnovel-narration` on Linux. Logs: `server.log` there on macOS,
`journalctl --user -u webnovel-narration.service` on Linux. Port 8790 (`NARRATION_PORT` overrides it).
On a Mac, the server holds an idle-sleep assertion (`caffeinate -i`) for two minutes after each request,
so a batch does not stop halfway. Closing the lid still sleeps the Mac. On Linux, the service starts at
login. `loginctl enable-linger` keeps it running while logged out.

### Protocol 1

Every request carries `Authorization: Bearer <token>`. Tokens are 12 characters from
`23456789abcdefghjkmnpqrstuvwxyz`, grouped `xxxx-xxxx-xxxx`, and compared case-insensitively.

- `GET /v1/info`: `{protocol, model, sampleRate, workers, threadsPerWorker, name}`.
- `POST /v1/synthesize` with `{"text", "speakerId"}`: `audio/wav`, mono 16-bit PCM, at 1x. Samples are
  converted like `TtsWavWriter` (clamp, x32767, round half up).
- Errors are JSON `{"error"}`: 400 bad request, 401 wrong token, 500 generation failed.
- `GET /health` needs no token.

## The app

| Piece | Responsibility |
|---|---|
| `NarrationQueueDocument.servers` / `generateOn` | The computers (name, address, token) and the chosen one. Stored in `filesDir/narration/queue.json`, which backups leave out, so the token never leaves the phone. |
| `NarrationServerClient` | HTTP/1.1 over a plain socket. Each request uses its own connection, and cancelling closes the socket. It deliberately bypasses Android's cleartext policy, which would otherwise have to allow plain HTTP for the whole app. Tailscale encrypts the traffic. On a home LAN it is plain HTTP with a token. |
| `NarrationServerPlanning` | Compatibility (protocol 1, model `kokoro-en-v0_19`), sentences in flight (one per model copy plus one, at most 9), form checks, and the test summary. |
| `AndroidNarrationSynthesizers` | A Kokoro job goes to the chosen computer when there is one. `location(voice)` changes when the choice or the server's address or token changes. Android-engine voices always generate on the phone. |
| `RemoteKokoroNarrationSynthesizer` | Prepares the text with `KokoroTextPlanning.spokenText`, as the phone does, and sends it. It uses the phone's voice key (`KokoroVoices.voiceKey`), so clips from the phone and a computer are interchangeable and mix within a chapter. One retry on a dropped connection. |
| `NarrationGenerator` | Keeps `parallelism` sentences in flight and writes clips in order. At each sentence it checks the job and the place: choosing another place switches at the next sentence and keeps the clips. A `NarrationServerProblem` (unreachable, wrong token, wrong model) **pauses the queue** with the reason and `pausedForServer`, instead of failing every job. Choosing a place resumes it, and so does Resume. A 400 or 500 for one sentence is retried once, then fails only that chapter. Generation speed is recorded per place (`engine@serverId`). |
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
