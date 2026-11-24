# Narration server: lower idle memory and a gentler memory ladder (plan, 2026-11-24)

Status: v2, after review by Claude Fable 5.2 and Gemini 4 Neon. Implemented in server 3.1.0; the
measurements are in `remote-narration-servers.md` ("Memory").

## What the owner asked for

1. **Idle**: when nothing is generating, the server should hold as little RAM as possible. Measured
   on Bazzite before this change: 320-360 MB with no model loaded.
2. **Under pressure** (chapters generating while the emulator runs), in this order:
   1. free what can be freed without real cost (including the server's own swap);
   2. keep generating at lower capacity;
   3. stop only when there is truly no memory left.

## What the code and the logs said

- **Idle 320 MB was native memory the server never got back.** After 27 loads and unloads of Kokoro
  v1.0 (fast path, OpenVINO + ONNX Runtime) the process held 185 MB of glibc heap and 107 MB of
  anonymous maps with no model loaded, although `malloc_trim` ran at each unload. After one unload
  on 2026-11-21 it was 105 MB, so it grew by roughly 8 MB per load.
- **The server mostly slowed and stopped because of swap, not RAM.** Journal, 5 days, Kokoro v1.0:
  18.5 h at Low (one slot), 0.8 h stopped, 87 stops. The median *available* memory at a stop was
  **3066 MB** (min 795 MB) while the stop line was 1044 MB: nearly every stop came from "zram < 5% free
  and available < reserve (3134 MB)". Low came from "swap ≥ 75% used" or "available < 3134 MB".
  zram use only grows until reboot (4.3 of 7.7 GB used, 3.0 GB of real RAM), so the server stayed
  slowed for the rest of the session.
- Low jumped from 4 slots straight to 1.

## A. Idle: each model runs in a child "worker" process

The parent (HTTP, governor, counters) never loads a model. Loading a model starts
`narration-server --worker ...` (the same binary, `/proc/self/exe` on Linux so an upgraded binary on
disk is not picked up mid-run); unloading ends that process, so all of its memory, heap fragments,
native globals and swapped-out pages go back at once. Target idle: parent ~10-15 MB, no worker.

`src/worker.rs`:
- **Parent side**: `Worker`, which the engine holds in place of the in-process model. Spawns the
  child with stdin/stdout pipes (stderr inherited, so native logs reach the journal) and waits up to
  90 s for a `ready` frame or an error frame; past that it kills the child (a thrashing load cannot
  wedge `loading`). Requests are multiplexed by id; one reader thread routes replies. Drop kills the
  child at once (it has no state to save) and a reaper thread waits for it.
- **Child side**: `--worker --model NAME --model-dir DIR --threads N [--fast-library DIR
  --fast-precision P --fast-streams N]`. First it moves fd 1 aside (`F_DUPFD_CLOEXEC`) for the protocol and points
  stdout at stderr, so a native library printing to stdout cannot corrupt frames. It raises its own
  `oom_score_adj` to 1000 on Linux (the kernel's OOM killer takes it before anything else). No
  `PR_SET_PDEATHSIG` (it follows the spawning thread, which is a short-lived HTTP or preload thread):
  instead the stdin reader calls `_exit` on EOF, which also covers a parent `kill -9` and macOS. Each
  request runs on its own thread (8 MB stack, as the HTTP threads have); replies are written under a
  lock, never from the reader. A panic in a sentence answers it as an error. The last sentence out
  hands freed heap back before replying (the fast path frees OpenVINO's buffers the same way), so no
  separate "release" message is needed: the engine's pause (B) is what makes the worker go idle.
  Phonon's thread pool is set up only in the worker.
- **Frames**: `u64 id, u8 kind, u32 len, bytes`, length capped at 64 MB both ways. Kinds: generate
  (JSON text + voice), ready (JSON: protocol version, whether the fast path loaded), audio (u32 rate
  + PCM), error.
- **No in-process fallback** in server mode (spawn fails mostly under memory pressure, and a fallback
  would load 1.6 GB into the parent): a failed spawn answers 503. `--bench` stays in-process.

Accounting:
- Each engine measures **its own worker** (Linux `/proc/PID/status` RssAnon + RssShmem and VmSwap,
  as its file-backed pages already count as available; macOS
  `proc_pid_rusage(pid)` phys_footprint). `before_load` becomes 0, the "don't learn while another
  model is loaded" rule goes away, and `/v1/status` shows `serverMb` (parent) and per-model
  `workerMb`/`workerSwapMb`.
- `reload_would_fit` counts the worker's resident memory as returned on Linux (not its swap).

Worker death (OOM kill, crash):
- `Synth::generate` returns a typed error: `Lost` (the worker died) or `Failed`. Lost answers
  **503 + Retry-After** and is not counted as a failure.
- The engine clears the dead handle at once (`report_lost`, matched by handle generation, even with
  sentences still active), counts an unload, and starts a **lost cooldown** (15 s, doubling to 5 min
  when the next worker also dies within a minute), separate from the critical-stop cooldown.
- A worker that dies during load counts as memory (503), not as a broken model; only an error frame
  from the child counts toward `LOAD_FAILURES_BEFORE_ERROR` (500).
- systemd: `OOMPolicy=continue` in the unit, so a kernel OOM kill of the worker does not stop the
  service (the default `stop` would). `systemd-oomd` kills whole units; that case remains a 5 s
  restart.

## B. A gentler ladder: free first, then slow down, stop last

Thresholds (16 GB machine in brackets):

| Line | Before | Now |
|---|---|---|
| Reserve (slots grow above it) | 20% RAM, 1.5-4 GB [3134 MB] | 12% RAM, 1-3 GB [1880 MB] |
| Stop line | a third of the reserve, ≥ 600 MB [1044 MB] | 4% RAM, 384-768 MB [640 MB] |
| Emergency (kill the worker now) | none | half the stop line [320 MB] |

One helper, `effective_stop(snapshot, policy)`, is the stop line plus 384 MB while swap is nearly full,
and `decide`, `load_refusal` and `reload_would_fit` all use it.

As available memory falls:

1. **Above the reserve: Normal.** Slots = paid-for slots + what fits above the reserve (as before).
2. **Under the reserve: free first, then step down (Low; still `low` on the wire).**
   - Slots = `paid - ceil((reserve - available) / slot_cost)`, at least 1: one slot fewer for each
     slot's worth of memory under the reserve, instead of the cliff to 1. `paid` is the slots grown
     since load, or the target carried across a recycle, so a recycle does not reset to 1.
   - Free first, cheaply: when slots first drop below what was grown (at most every 30 s), the engine
     quiesces (admits nothing for a moment, up to 3 s), asks the worker to release its
     between-sentence buffers and heap, then resumes. Other idle models are unloaded. Idle unload
     drops from 5 min to 60 s while under the reserve (not when it is disabled).
   - If slots still sit at least two below what was grown, or at 1 with more grown, for 10 s:
     **recycle** the worker at the allowed slot count (fresh process, the fast path's streams sized
     to it), carrying that count as the target, and only if the reload fits. Afterwards the steps
     count from the kept size with the memory the recycle gave back. Recycles are at least
     60 s apart, doubling to 10 min when the next comes within 2 minutes, so a hog sitting at the line
     cannot cause a reload loop.
   - **Lower-cost model variant**: when Kokoro v1.0's fast path (1.6 GB to load) does not fit but
     the normal path (0.9 GB) does, load the normal path (same voice and audio quality; 7.7x instead
     of 11x on Bazzite) rather than refuse. Each variant learns its own load cost.
3. **Below the stop line: stop.** 503 + Retry-After; the worker ends as soon as running sentences
   finish. Loads need the variant's learned load cost above the effective stop line (no extra margin:
   the cost is already a measured peak, and the emergency line backs it up).
4. **Below the emergency line, or PSI `full` ≥ 40%: kill the worker now.** In-flight sentences get
   503 and the app retries them later.

Swap and pressure signals:

- **Swap use no longer slows or stops the server.** Only nearly full swap counts (enter < 5% free,
  leave ≥ 10%), and it raises the stop line by 384 MB.
- Linux PSI: `some` ≥ 20% → Low with one slot fewer than paid. `full` ≥ 10% → one slot; `full` ≥ 10%
  with available under the reserve → stop. Leaving needs `some` < 10% / `full` < 5%.
- macOS: kernel pressure warning → Low (one slot fewer than paid), critical → stop, as before.
- Every level change logs the rule that fired.

## C. Swap

- The server's own swap goes to zero whenever its worker exits (idle unload, recycle, stop).
- It no longer reacts to other programs' swap.
- It cannot shrink other programs' swap: pages come back from zram only when their owner touches
  them, or with `sudo swapoff /dev/zram0 && sudo swapon /dev/zram0` (root, enough free RAM).
  Documented, not automated.

## Validation

- Unit tests: the ladder (each step, swap not slowing, PSI hysteresis, emergency), frame round trip
  and length cap, engine: lost handle cleared with sentences active, lost cooldown doubling, slots
  after a recycle above 1, death during load is 503 not 500, variant choice by room.
- Bazzite, a test instance on another port beside the service:
  - idle RSS after start and after each model loads and unloads (target < 20 MB);
  - speed over HTTP with 5 in flight against the in-process figures;
  - a memory hog walk: release → fewer slots → recycle → normal path → stop → resume;
  - `kill -9` the worker mid-chapter and during load: 503s, cooldown, then it continues;
  - the preload thread and connections closing do not kill the worker; `kill -9` of the parent leaves
    no orphan;
  - under `systemd-run --user -p MemoryMax=... -p OOMPolicy=continue`, a worker OOM kill leaves the
    parent answering.
- Then reinstall with `setup.sh --service` and check `/v1/status`.
- `cargo test`, `cargo clippy`; macOS needs a real run on the MacBook (launchd `ProcessType`
  inheritance by the worker, `phys_footprint` of a child).

## Leads not taken

- The unit's own `memory.pressure` would separate the server's stalls from the emulator's; the
  system-wide PSI is kept for now.
- Splitting very long sentences under pressure would cap per-sentence peaks but changes prosody;
  quality changes need the owner's ears first.
