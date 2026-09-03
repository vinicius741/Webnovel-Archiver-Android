# Podcast-style TTS playback

How TTS behaves as a persistent, podcast-like player: playback that survives navigation and
app exits, per-story resume memory, and the in-app player surfaces. Shipped 2026-09-02.

## Model

Two persisted concepts, deliberately separate:

- **Active session** (`tts_session.json`, `TtsSession`) — the "now playing". Exists while the
  player is loaded (playing or paused). Cleared on explicit stop and natural completion. Drives
  the notification, MediaSession, reader transport, and mini-player visibility.
- **Per-story positions** (`tts_positions.json`, map `storyId → TtsStoryPosition`) — each story's
  last heard chapter + sentence chunk. Mirrored on every debounced session write; written on
  explicit stop; cleared only by natural completion (nothing left to resume) or a content-variant
  switch (chunk indices remap between source and polished text). Description narration
  (sentinel chapter `wna:description`) never touches this store.

"Read aloud" with no explicit chunk = **resume the story's saved position**, even from a
different chapter (podcast behavior). Double-tapping a paragraph still starts exactly there.
A saved position is only honored while its chapter is still playable (downloaded, or legacy
inline content); otherwise the viewed chapter plays from the top.

## Lifecycle rules

- Playback lives in the process-wide `TtsEngine` + `TtsForegroundService` (`mediaPlayback` FGS
  type, MediaSession + MediaStyle notification). Leaving the reader, the activity, or the app
  does not stop it; only explicit stop, natural completion, or process death do.
- After a system kill the service restarts (`START_STICKY`, null intent → resume persisted
  session), so playback continues on its own.
- **Permanent audio-focus loss pauses** (session + paused notification survive) instead of
  stopping and wiping position. Transient loss already paused + auto-resumed.
- A session ending (explicit stop or natural completion) stops the foreground service and
  removes the notification — no zombie service with a dismissable notification.
- Cold start with a resume-eligible session reopens the reader at that chapter (pre-existing
  behavior, unchanged).

## Player surfaces

- **Mini-player** (`feature/player/TtsMiniPlayer.kt`): floating bar pinned above every screen
  while a session is loaded — play/pause, chapter + story title, sentence progress; tap opens
  the player. Smooth enter/exit animations (`translationY` and `alpha`). Hidden on the Reader route
  (which has its own transport + highlight). The current screen's FAB is lifted above the bar while visible.
- **Now Playing screen** (`feature/player/PlayerScreen.kt`, `AppRoute.Player`): cover, story +
  chapter titles, "Chapter n / N", interactive `SeekBar` scrubber with touch isolation and live
  seeking, prev/next chapter, prev/next sentence, play/pause, speed chip (0.75–2.0 presets, applied
  live and persisted), sleep timer chip (countdown or chapter-end pause), closes itself when
  playback ends. Rebuilds on chapter change; patches transport state in place otherwise.
- **Reader transport** (unchanged location): sentence-level prev/next + play/pause + stop for the
  visible chapter. Its play/pause resumes the persisted session when the engine has no live
  session (cold-start phantom-pause fix).
- **Notification + media buttons**: Prev/Next are **chapter** skips (podcast episode semantics);
  sentence skipping stays available in the reader transport and the Now Playing screen.

## Engine additions

- `TtsEngine.play(storyId, chapterId, chunkIndex = null)` — null chunk = story-position resume
  (resolution in `TtsSessionPlanning.resolveStartPosition`, pure + unit-tested).
- `TtsEngine.seekChunk(targetIndex)` — interactive scrubber seeking; clamps chunk index, interrupts
  ongoing speech, reschedules session, and re-speaks immediately if active.
- `TtsEngine.skipChapter(delta)` — prepares the adjacent chapter; stays paused if playback was
  paused (`startPaused` path in `startPreparedPlaybackLocked`); forward skips mark the current
  chapter read, mirroring auto-advance.
- `TtsEngine.setRate(rate)` — persists `TtsSettings`, applies to the live session, and re-speaks
  the current chunk so the change is audible immediately.
- `TtsEngine.stop(forgetPosition)` — stop persists the final story position;
  `forgetPosition = true` (variant switch) drops it.
- `TtsSleepTimer` — handles countdown timers (15m, 30m, 45m, 60m) and end-of-chapter pause triggers.
- `TtsSessionStore` mirrors every session write into `tts_positions.json` and owns
  stop/finish/clear semantics under one write mutex.

## Architecture separation

- `TtsNotificationManager`: encapsulates notification channel creation, MediaStyle layouts, and intent builder logic.
- `TtsMediaSessionManager`: encapsulates `MediaSessionCompat`, hardware media button handling, and playback state sync.
- `TtsWatchdog`: isolates utterance stall timeout tracking and automatic retries.
- `TtsSettingsApplier`: encapsulates voice and language resolution and engine property application.

## Reader auto-follow guard

The reader's TTS collector (highlight + chapter auto-follow) is now detached whenever a
non-Reader screen opens (`ui/Scaffold.kt`), and auto-follow only fires while that reader chapter
is the current route. Before, a chapter transition anywhere (player skip, background
auto-advance) would hijack the screen the user was looking at.

## Storage layout

```
files/webnovel_archiver/
  tts_session.json     # active session (single)
  tts_positions.json   # { storyId: { chapterId, chapterTitle, currentChunkIndex, updatedAt } }
  tts_settings.json    # voice/rate/pitch (unchanged)
```

`TtsSession` gained `storyTitle` (constructor-default Gson migration; legacy JSON regression test
in `TtsSessionLegacyJsonTest`). Both models live in `domain/model/TtsModels.kt`.

## Emulator QA (2026-09-02, webnovel_api36, debug build)

Verified live: navigate-away and leave-app continuity (chunks keep advancing; FGS foreground),
mini-player visibility + progress, Now Playing rendering/transport, speed chip (1.8x → 2x live),
chapter skip while playing and while paused (silent position move), process-kill resume
(startIndex=89), stop-then-resume (startIndex=104, re-speaks the interrupted sentence), per-story
memory across two stories with a cross-chapter jump (Prologue tap → chapter 2 chunk 147), and
the auto-follow hijack fix (player skip stays on the player).
