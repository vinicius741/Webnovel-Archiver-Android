# Pre-generated narration: plan

Status: implemented 2026-11-11 (see "Implementation notes" at the end). Chapter playback no longer
renders in real time.

## Problem

Chapter narration used to render sentence by sentence while it played (`RenderedTtsOutput` →
`TtsAudioRenderer` → `TtsAudioCache`). With Kokoro the margin is thin: on the owner's Galaxy Z
Fold7, Kokoro renders at about 2.5x real time while the owner listens at 1.8x. With the screen on and
the app in front the experience is good. Once the phone is locked or another app is in front,
playback stops between sentences: generation no longer keeps ahead, the ~0.7–1.2 GB model is loaded
during playback (a background-kill target), and the clip cache lives in `cacheDir` (500 MB LRU, the
OS may clear it).

## Decisions (from the owner, 2026-11-11)

1. A chapter can be listened to **only after its audio has been generated in full**. There is no
   real-time chapter playback any more.
2. Bulk generation: queue many chapters, generate in the background at full speed (screen off,
   other apps in front). Heat and battery are the user's responsibility: no throttling, no
   charging requirement.
3. **Keep ahead**: while listening to a story, keep the next N chapters (default 5) generated or
   queued.
4. Generated audio is **never deleted automatically**. A management screen shows sizes and deletes.
5. A chapter keeps the voice it was generated with. The user can **regenerate** chapters in another
   voice.
6. Android TTS engines get the same flow, not only Kokoro.
7. Generation has its **own screen**, separate from Downloads.
8. Storage format: left to us (see §1).

## Overview

```
Reader / Details / Narration screen
        │ enqueue(story, chapters, resolved voice)        keep-ahead (while playing)
        ▼                                                         │
NarrationQueue (filesDir/narration/queue.json) ◄──────────────────┘
        ▼
NarrationGenerator (process-wide, kept alive by NarrationForegroundService)
  chapter text → TtsTextPreparation → recipe (texts + voice, immutable)
  → per sentence: reuse an identical clip, or synthesize → trim → v-<id>/NNNN.pcm
  → index.json → switch `current` pointer (atomic)
        ▼
NarrationStore  filesDir/narration/packs/<hash story>/<hash chapter>/
        ▼
TtsEngine (chapter) → loaded chapter's pack ready? ─ no → paused, AudioNotGenerated
                                                   └ yes → PackTtsOutput: TtsPcmPlayer reads clips
```

The listening experience is unchanged: `TtsTimeline`, `TtsPcmPlayer`, pauses inserted at playback,
speed by time stretch, reader highlighting, anchored positions, history and the media session all
stay. Only the source of each sentence's audio changes. A chapter plays from its pack: no Kokoro,
no `TextToSpeech`, no rendering, no cache.

Description narration (Details) and the Voice & Speech "Try it" sample keep real-time rendering.
They are a paragraph long and are not chapters.

## 1. Storage: the chapter pack

### Format: uncompressed per-sentence clips (v1)

Each sentence is stored as its own file, in the same format `TtsAudioCache` uses today (a 4-byte
sample-rate header plus 16-bit mono PCM, already trimmed with the 15 ms pad and 5 ms fade).

Why not AAC, which was the first draft: both reviewers showed that cutting sentences back out of one
lossy stream depends on encoder priming that `MediaMuxer`/`MediaExtractor` do not report reliably,
that low-bitrate noise can lift encoded silence above the trim threshold (-50 dBFS), and that the
owner's phone has a different encoder from the emulator. A bug there would make hours of generated
audio sound wrong, and it cannot be verified here. Per-sentence PCM is exact by construction, and
playback reads a clip with one file read: no decoder, no decode cache, exact durations from the start.

Cost: 2.9 MB per minute at 24 kHz, about 55 MB for a 20-minute chapter. The Narration screen shows
the space used and free, per story and per chapter, and deletes on request. Compression stays
possible later: `index.json` carries `"codec": "pcm"`, so a future lossless (FLAC) or validated
lossy codec can sit next to it without breaking existing packs.

### Location and names

`filesDir/narration/`, next to the library root, the way the Kokoro model lives in
`filesDir/tts_models/`. Full backups do not include it, and the OS does not clear it.

```
narration/
  queue.json                    jobs, pause flag, measured speed per engine
  packs/<h(storyId)>/<h(chapterId)>/
    current                     name of the published version (written by rename)
    v-<id>/
      recipe.json               immutable: what this version is generated from
      0000.pcm … NNNN.pcm       one clip per narration sentence (chunk index)
      title.pcm                 the spoken chapter title, when there is one
      index.json                written last: durations, bytes, time. Its presence means complete.
```

`h()` is the first 32 hex characters of SHA-256 of the full id. `AppStorage.safeName` is not used
here because it truncates and replaces characters, so distinct ids could share a folder. The recipe
keeps the original ids and readers check them.

### Recipe (immutable per version)

```jsonc
{
  "storyId": "…", "chapterId": "…",
  "chunkingVersion": 2,
  "textFingerprint": "sha256 of chunkingVersion + the sentence texts",
  "title": "Chapter 12, The Gate",           // null when the text already opens with the title
  "sentences": ["…", "…"],
  "voice": { "engine": "builtin:kokoro-en-v0_19", "voiceId": "kokoro-en-v0_19:af_bella",
             "pitch": 1.0, "label": "Bella · Kokoro" },
  "voiceKey": "builtin:kokoro-en-v0_19|speaker=3|text=2"
}
```

A clip file `NNNN.pcm` is valid only inside its version folder, whose recipe fixes its text and
voice. A resumed job reuses its folder only when the recomputed recipe is identical. Otherwise it
starts a new folder.

### Publication (atomic)

1. Every clip is written to a temporary name and renamed into place.
2. `index.json` is written the same way. The version is now complete.
3. The chapter's `current` file is replaced by rename to name the new version.
4. Superseded versions are deleted, unless playback holds them (`retain`/`release`). In that case
   they are deleted when playback releases them, or by the next startup sweep.

A process death at any step leaves either the old pack current and complete, or the new one. Never a
mix.

### What is deleted, and when

- Never automatically: a complete, current pack. Packs whose story or chapter is no longer in the
  library (after a restore of an older backup, for example) are kept and listed as **Detached
  audio** in the Narration screen, where they can be deleted.
- On an explicit user action: Delete audio (chapter, selection, story, detached), Regenerate (old
  version after the new one is published), Delete story from the library.
- By the startup sweep (only after the repository is ready): incomplete version folders that no job
  owns, superseded versions, and stray temporary files.

## 2. Generation

### Jobs (`NarrationJob`, persisted)

```kotlin
data class NarrationJob(
    val id: String,
    val storyId: String,
    val chapterId: String,
    val voice: NarrationVoice,       // resolved at enqueue: concrete engine, voice id, pitch, label
    val origin: NarrationOrigin,     // MANUAL, KEEP_AHEAD, REGENERATE
    val state: NarrationJobState,    // QUEUED, FAILED (running is derived; done jobs are removed)
    val error: String? = null,
    val version: String? = null,     // the version folder being built, for resume
    val enqueuedAt: Long,
)
```

- One job per chapter. A chapter with a ready pack in the same voice is not queued again, unless the
  user chose Regenerate. Re-enqueueing a chapter that already has a job replaces that job only for
  MANUAL and REGENERATE requests. Keep-ahead only adds jobs and never changes existing ones.
- Order: queue order. "Generate" and "Regenerate" requests go to the end. "Generate next" (and the
  auto-advance case below) puts a job first. Keep-ahead jobs go to the end.
- **Voice resolved at enqueue.** "Follow the phone's engine" (`enginePackage = null`) becomes the
  actual default engine package (`Settings.Secure.TTS_DEFAULT_SYNTH`). Kokoro's default voice becomes
  its concrete id. An Android engine's unspecified voice stays unspecified, and the voice the engine
  actually used is recorded in the recipe. Changing settings later never changes queued jobs.
- **Strict engines.** Generation never substitutes another engine. If the job's engine is not usable
  (Kokoro not installed, TTS app uninstalled), the job fails with that reason and the queue moves on.
  Playback's existing fallback to the phone's engine stays for description narration only.

### Worker

`NarrationGenerator`, one job at a time, on its own single thread:

1. Load the chapter's HTML and the current cleanup rules, then run
   `TtsTextPreparation.prepareTtsNarration` and `TtsNarrationPlanning.spokenChapterTitle`. This
   builds the recipe.
2. If the job's `version` folder holds the same recipe, resume it. Otherwise create `v-<new id>/`,
   write `recipe.json`, and save the version in the job.
3. Start the job's synthesizer:
   - Kokoro through the shared `KokoroRuntime`, which is released when the queue is idle or paused.
   - An Android engine through its own `TextToSpeech` instance, with the voice applied by
     `TtsSettingsApplier` and no fallback.

   The synthesizer is reused across jobs with the same voice.
4. For each sentence (and the title) without a clip:
   - If the current published pack has a clip with the same voice key and text, copy it. A small
     cleanup-rule edit then regenerates only the changed sentences.
   - Otherwise synthesize (one retry), then `parseWav` and `trimSilence`, and write the clip.

   Progress (done / total, audio ms produced, real ms spent) is published as a `StateFlow`.
5. Before publishing, check:
   - the job is still in the queue (it was not cancelled);
   - the chapter's current text still has the recipe's fingerprint;
   - the repository's library generation has not changed (no restore or clear meanwhile).

   If a check fails, the version is discarded and the job either restarts or is dropped.
6. Publish (see §1), remove the job, update speed statistics, and run keep-ahead for that story.

Failure handling:

- A sentence failing twice fails the job, with the reason. The queue continues with the next job.
- Empty output 5 times in a row means the voice is broken, as in today's renderer.
- Out of space (`IOException` while free space is below the margin) **pauses the queue** with "Not
  enough storage". The job's clips are kept, so Resume continues where it stopped.
- Free space is checked before each chapter: its estimated PCM size, from text length and the
  measured ms per character, plus a 200 MB margin.

### Service, Doze and background starts

- `NarrationForegroundService` keeps the process in the foreground while the queue has runnable
  jobs and is not paused. It holds a partial wake lock and shows a notification, for example
  "Generating audio · Story · Chapter 12 · 3 of 10 · ~25 min left", with Pause and a tap that
  opens the Narration screen.
- **Type: `specialUse`** (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), requiring
  `FOREGROUND_SERVICE_SPECIAL_USE`. `dataSync` and `mediaProcessing` are capped at 6 h per 24 h on
  Android 15+, and an overnight batch can exceed that. No listed type fits on-device speech
  synthesis for later playback, which is the case `specialUse` covers. The app is sideloaded, so no
  store review applies. Below API 34 the type attribute is ignored.
- **Doze.** Deep Doze ignores wake locks, so a compute-only service with the screen off may only
  advance in maintenance windows. The fix is the battery-optimization exemption (permission
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, fine for a sideloaded app):
  - The Narration screen shows a banner, "Allow generating with the screen off", while the app is
    not exempt.
  - The first time a batch is queued, a one-time dialog offers the same.
  - The exemption also allows the service to start from the background (Android 12+ restrictions).
- **Background starts that are refused** (`ForegroundServiceStartNotAllowedException`) are caught.
  The jobs stay queued. The service starts again the next time the app is opened, or when anything
  is enqueued while the app is visible.
- `START_STICKY` lets the system restart the service after the process dies; it is not a guarantee
  of immediate recovery. The service calls `startForeground` first, then waits for the repository.
- Generation and playback share the process. Playback no longer needs the model, so the risk drops
  from "the model must keep ahead of the listener" to "the process must survive". The model is
  released as soon as the queue is idle or paused.
- **Other Kokoro users:**
  - The speed test (`KokoroBenchmark`) pauses the queue before it loads its own copies, and resumes
    it after.
  - Removing the model pauses the queue first, and Kokoro jobs then fail with "Kokoro model not
    installed".
  - The settings sample shares the runtime's lock, so it waits for the sentence in progress.

### Keep ahead

- Setting in the Narration screen: **Keep ahead while listening**: Off / 1 / 3 / 5 / 10, default 5.
- Runs when a chapter's audio starts playing (not when a paused session is restored) and when a
  chapter of the playing story finishes generating.
- `NarrationKeepAheadPlanning.chaptersToQueue(chapters, currentChapterId, n, ready, queued)`
  - The window is the N chapters immediately after the current one, in the same order auto-advance
    uses (`story.chapters`, including chapters kept after removal from the source).
  - Within the window, it returns the downloaded chapters with no ready pack in that voice and no job.
  - The window is fixed before filtering, so refills never reach further than N chapters ahead.
- Keep-ahead jobs use the voice of the chapter being played. A story changes voice only through
  Regenerate.

## 3. Playback

### The gate

`TtsEngine.loadOutputLocked` is the single place where chapter audio starts. Play, resume, restore
after a restart, chapter skip, history jump and auto-advance all go through it.

- `TtsPlaybackPreparer` computes the chapter's fingerprint along with the narration. It also waits
  for `NarrationStore` to finish loading its in-memory index, so the gate is a memory lookup on the
  state path, with no I/O.
- Description sessions (`TtsDescriptionPlanning.isDescriptionSession`) skip the gate and play
  through the existing rendered pipeline.
- A chapter with a ready pack (matching ids and fingerprint) plays through `PackTtsOutput`.
- **No ready pack:**
  - The session stays loaded, paused, at that place (the mini-player and Now Playing still show it).
  - The engine reports `TtsPlaybackErrorKind.AudioNotGenerated`. The notification says "Chapter
    audio not generated yet".
  - The chapter is queued at the front, in the voice of the story's latest pack, or the current
    settings if the story has none.
- UI entry points check readiness first and open the story's narration screen instead of sending
  play (Reader, Library card, mini-player, Now Playing). Media buttons and the notification still
  reach the engine gate.

### Auto-advance into a chapter that is not ready

The session moves to the next chapter, paused at its start (the existing paused-load path), with
`AudioNotGenerated`. The next chapter is queued first. Play starts it once ready. Restarts, history
and positions behave as for any paused chapter, with no special state. Playback never starts by
itself when generation finishes: a pause is the listener's decision.

### `PackTtsOutput`

- It implements `TtsOutput` with a `TtsPcmPlayer` and a `TtsPcmFeed` backed by the pack. Speech
  item `k` maps to clip `k`, and the title item maps to `title.pcm` (or plays as empty if the pack
  has none).
- The fingerprint guarantees the sentence texts are the ones generated. A chapter title changed
  since generation does not invalidate the pack: the pack's own title is spoken.
- On load, every item's duration is set from `index.json`, so time and length are exact at once.
- Clip reads are small (one sentence) and happen on the player thread. A missing or unreadable clip
  pauses with `AudioNotGenerated`. It never falls back to synthesis. The pack stays on disk for
  "Regenerate".
- The output holds a store `retain` on its version until it stops, so a regeneration that publishes
  meanwhile does not delete files under it.
- Settings while a pack chapter plays:
  - Pauses and speed apply in place, unchanged.
  - Engine, voice or pitch changes no longer restart the output. The chapter keeps its voice, and
    Now Playing shows it.
  - Render-ahead (`prepareAhead`) does not exist for pack chapters.

### Text drift

If the current text differs from the pack's fingerprint (cleanup rules edited, chapter re-downloaded,
`CHUNKING_VERSION` bumped), the pack is **outdated**. It does not play, and it shows as "Text changed ·
Regenerate". Regeneration reuses identical sentences (§2 step 4).

## 4. UI

### Narration screen (`AppRoute.Narration`, `feature/narration/`)

Reached from a Library app-bar action ("Narration", headphones icon), Now Playing, Details and the
generation notification.

- **Status card:**
  - Generating now: story, chapter, progress bar, sentence count, ETA and voice. Or "Paused"
    with the reason, or "Idle".
  - Pause / Resume.
  - Storage used by narration, and free space.
- **Screen-off banner** while the app is not exempt from battery optimization.
- **Queue:** jobs in order with their state. Per job: move to top, remove, retry (failed). Clear
  failed.
- **Keep ahead** setting.
- **Generated:** stories with packs, showing chapters ready, size and voices. A tap opens the story
  screen. **Detached audio** (packs of stories no longer in the library) can be deleted.

### Story narration screen (`AppRoute.NarrationStory(storyId, chapterId?)`)

"Read aloud" opens it when the chapter is not ready.

- Focused chapter card: status (Not generated / Queued / Generating 41% / Ready / Text changed /
  Failed: reason). When ready it shows a **Play** button, and the user decides when to start.
- "New audio uses: Bella · Kokoro (current voice)", with a button to open Voice & Speech.
- Quick actions with estimates (time and size):
  - **Generate this chapter**
  - **This + next 5**
  - **All remaining**
  - **Select chapters…**
- Chapter list (downloaded chapters) with a status badge per row and multi-select. Selection
  actions:
  - Generate
  - Regenerate with current voice
  - Delete audio
- Story actions: Delete all audio, Regenerate all with current voice.

### Entry points

- **Reader "Read aloud"** resolves the chapter it would play (saved place or this chapter) first.
  - Ready: it plays as before. "Continue listening / Listen from this chapter" is asked only when
    both chapters differ, and each choice is gated.
  - Not ready: it opens the story screen focused on that chapter.
  - A paragraph double-tap on a ready chapter plays from there.
- **Library "Continue listening"**, **mini-player play** on a cold or paused session, and **Now
  Playing play**: the same gate.
- **Details:** "Narration audio" in "More options", and a ready badge on chapter rows.
- **Now Playing:** shows the voice the chapter was generated with.

## 5. Lifecycle and integrity

- Every narration file operation runs off the main thread. The store's index loads on IO after
  `awaitRepositoryReady`, and the sweep runs after that, never during a restore.
- Generation captures `AppRepository.libraryGeneration` at job start and checks it before
  publishing.
- Story deleted by the user → its packs and jobs are deleted.
- Restore of an older backup → packs not in the restored library become detached and are kept.
  Packs whose text no longer matches show as outdated.
- Kokoro model removed → Kokoro jobs fail, and existing Kokoro packs still play.
- The activity log records enqueue, start, finish, failure, pause and delete.

## 6. Code

New package `narration/`:

| File | Role |
|---|---|
| `NarrationModels.kt` | Jobs, voices, recipe, index, statuses. |
| `NarrationPlanning.kt` | Pure rules: id hashing, fingerprint, queue edits and order, keep-ahead window, estimates. |
| `NarrationStore.kt` | Pack folders, versions, publication, retain/release, in-memory index, sweep, sizes, delete. |
| `NarrationQueue.kt` | Persisted jobs and pause state under one lock; `StateFlow`. |
| `NarrationSynthesizers.kt` | Strict synthesizer per voice: Kokoro runtime or a dedicated `TextToSpeech`. |
| `NarrationGenerator.kt` | Worker: recipe, resume, reuse, synthesize, publish, progress. |
| `NarrationCoordinator.kt` | Process-wide facade in `AppContainer`: enqueue, keep-ahead, readiness, service start. |
| `NarrationForegroundService.kt` | Foreground service, wake lock, notification. |
| `tts/PackTtsOutput.kt` | Pack-backed `TtsOutput`. |

UI in `feature/narration/`: `NarrationScreen.kt`, `NarrationStoryScreen.kt`, shared rows.

## 7. Tests and verification

- **JUnit (pure and file-based, temporary directories):**
  - id hashing (punctuation, long shared prefixes)
  - fingerprint
  - queue edits (dedupe, manual vs keep-ahead, move to top, voice snapshot unaffected by settings)
  - keep-ahead window (removed-from-source and undownloaded chapters, story end, refill bound)
  - store publication (interruption at each step leaves a consistent pack, retain/release, sweep
    keeps detached packs)
  - generator with a fake synthesizer (resume after a kill, recipe change discards, reuse copies,
    cancel before publish, text change before publish, library generation change, out-of-space
    pauses)
  - gate decisions
- **Emulator QA** (debug build, `webnovel_api36`):
  - Queue 3 chapters with Kokoro and 1 with the Google engine.
  - Lock the screen and open another app during generation.
  - Force Doze (`dumpsys deviceidle force-idle`) with and without the exemption.
  - Kill the process mid-chapter and confirm it resumes at the next sentence.
  - Play a ready chapter with the screen off (the model must not be loaded: check memory).
  - Read aloud on a chapter that is not ready.
  - Auto-advance into a chapter that is not ready.
  - Delete audio. Regenerate in another voice.
  - Edit a cleanup rule, then regenerate (reuse).
  - Folded and unfolded layouts.
- **Not verifiable here:** One UI's Doze and background behavior, thermal behavior over hours, and
  memory pressure on the owner's phone.

## 8. Out of scope

- Compression (FLAC or a validated lossy codec), with `codec` in `index.json` reserved for it.
- Exporting an audiobook file (M4B with chapter marks).
- Rendering at the listening speed with Kokoro's own speed control.
- Loudness normalization.
- Running generation in a separate process.

## Review (2026-11-11)

The first draft was reviewed by GLM-5.3 (max) and GPT-6.1 Sol (high). Accepted:

| Finding | Raised by | Change |
|---|---|---|
| AAC sentence boundaries depend on unreliable encoder delay; silence noise vs trim threshold; untestable on the owner's encoder | both | Per-sentence PCM in v1; codec field kept for later |
| Replacing audio then index leaves a mixed pack | both | Immutable version folders + atomic `current` pointer; retain while playing |
| Doze ignores wake locks; background FGS starts restricted | both | Battery-optimization exemption prompt; refused starts keep jobs queued |
| Startup sweep would delete audio after restoring an older backup | both | Complete packs are never swept; detached audio listed for manual delete |
| Staged clips not tied to text/voice; publish after cancel or restore | Sol, GLM | Immutable recipe per version; pre-publish checks incl. library generation |
| `safeName` truncates and collides | both | SHA-256 folder names; ids kept in recipe |
| Snapshot of null engine/voice is not a fixed voice; host silently falls back | both | Resolve at enqueue; strict synthesizers |
| Waiting at the end of a chapter cannot resume into the next | Sol | Move to the next chapter paused, `AudioNotGenerated` |
| Gate must cover every start path, incl. already-loaded resume and sticky restart | Sol, GLM | Gate in `loadOutputLocked`, the single start point |
| Description narration would be gated | GLM | Explicit exemption |
| Keep-ahead could rewrite manual jobs, skip the real next chapter, or refill forever | both | Add-only, playback order, fixed window, only while playing |
| Decode cache made playback depend on spare storage | Sol | Feed reads pack clips directly |
| Free-space check ignored staged PCM | Sol | Estimate PCM size; ENOSPC pauses and keeps work |
| Title not covered by the fingerprint | both | Pack's own title is spoken |
| Benchmark and model removal vs generation | Sol | Pause queue first |

Not adopted: moving generation to a separate process (`AppContainer` and the JSON stores are
single-process by design; the model is released whenever the queue is idle).

## Implementation notes (2026-11-11)

Differences from the plan above and where things live:

- **Now Playing** shows the chapter's generated voice as the screen subtitle, and has a headphones
  action that opens the playing chapter's audio screen.
- **Details** shows "Audio" in a chapter row's metadata when a pack exists. The row checks only
  that a pack exists, not whether the text still matches. "Narration audio" is in "More options".
- **In-app "not generated" handling**: `MainActivity` listens for `AudioNotGenerated`. It shows a
  toast and opens the chapter's audio screen, except in the Reader (so an auto-advance does not pull
  the reader away) and when that screen is already open.
- **Readiness for screens** (`NarrationCoordinator.statuses`): a chapter with a pack reads its text
  once, to compare fingerprints. The result is cached in memory by chapter download time, cleanup
  rules and `CHUNKING_VERSION`.
- **Keep-ahead refill**: also runs when a chapter of the story being listened to finishes
  generating, using the window after the playing chapter.
- **Estimates** use a 20-minute chapter until the story has packs, then that story's average. ETAs
  use the running chapter's measured rate, then the stored per-engine rate.

Verified on `webnovel_api36` (debug build, Kokoro Bella, 2026-11-11):

- **Gate.**
  - The Library "Continue listening" card and Reader "Read aloud" ("Listen from this chapter") on
    an ungenerated chapter opened its audio screen instead of playing.
  - Mini-player play on an ungenerated chapter stayed paused with `AudioNotGenerated`.
  - Skipping into a chapter still generating moved the session there paused and opened that
    chapter's screen.
  - Once the chapter finished generating, its Play button appeared in place, and play then worked.
- **Generation.**
  - A 221-clip chapter generated in about 3.5 min, at 2.1x real time on the emulator.
  - The `specialUse` service and its notification were shown, and generation continued with the
    app in the background. The service stopped when the queue drained.
- **Recovery.**
  - After `kill -9` mid-chapter, the sticky service restarted and resumed in the same version folder
    at the next clip.
  - After a reinstall (force-stop), opening the app resumed the job's version.
- **Playback** from a pack:
  - The 1.8x rate was applied.
  - Sentence highlighting in the Reader worked.
  - Times were exact (no `~`).
  - Now Playing showed the voice.
- **Keep-ahead.**
  - Playing chapter 2 queued chapters 3–7.
  - When chapter 3 finished, the queue refilled with chapter 8.
- **Memory.**
  - Pausing generation released the model: native heap went from 904 MB to 79 MB.
  - Playing a pack with generation paused held 71 MB: the model is not loaded for listening.
- **Delete audio** removed the chapter's folder. Details shows "Audio" on chapters that have a
  pack.

**Not verifiable here:**
- Real Doze. A forced deep idle on the emulator did not stop generation even without the
  exemption, because an emulator never suspends its CPU.
- The owner's phone, One UI's background limits, hours-long thermal behavior, and regenerating in
  another voice on device (that is covered by unit tests).
