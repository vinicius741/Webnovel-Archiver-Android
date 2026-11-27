# Podcast-style TTS playback

How TTS behaves as a persistent, podcast-like player: playback that survives navigation and
app exits, per-story resume memory, and the in-app player surfaces. Shipped 2026-09-02;
media buttons + in-app close hardened 2026-09-04.

Playback itself changed in November 2026: see [`tts-audiobook-playback.md`](tts-audiobook-playback.md),
which takes precedence where the two differ (rendered audio, time-based controls, short skips, pause
rules, caught-up positions). The session model and lifecycle below still apply.

## Model

Two persisted concepts, deliberately separate:

- **Active session** (`tts_session.json`, `TtsSession`) — the "now playing". Exists while the
  player is loaded (playing or paused). Cleared on explicit stop and natural completion. Drives
  the notification, MediaSession, reader transport, and mini-player visibility.
- **Per-story positions** (`tts_positions.json`, map `storyId → TtsStoryPosition`) — each story's
  last heard chapter + sentence chunk. Mirrored on every session write (see "Write cadence"); written on
  explicit stop; kept as `caughtUp` by natural completion; cleared only by a content-variant
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
- After a system kill the service restarts (`START_STICKY`, null intent). A session that was
  playing continues on its own; one the listener paused comes back loaded and silent.
- **Permanent audio-focus loss pauses** (session + paused notification survive) instead of
  stopping and wiping position. Transient loss pauses and auto-resumes, except after a phone call, which stays paused.
- A session ending (explicit stop or natural completion) stops the foreground service and
  removes the notification — no zombie service with a dismissable notification.
- A service instance started right after a previous stop must ignore the replayed
  authoritative-null from *before* it existed (`replayAtCreate` reference guard in
  `TtsForegroundService`); honoring it stopped the service before its first command ran,
  leaving the process-wide engine speaking with no session or notification. Playback updates
  carry an event id so a later no-op or failed resume emits a distinct authoritative-null and
  still tears the new service down.
- Cold start with a resume-eligible session reopens the reader at that chapter (pre-existing
  behavior, unchanged).

## Media buttons (headset & Bluetooth)

- **Routing** (live-speech fallback only; rendered audio plays in this app's own process): TTS audio
  plays inside the engine's process, so the system attributes playback
  to the engine's uid — and media-button routing picks the target session by "which uid is
  actually playing audio". `TtsMediaButtonClaim` holds a silent looping in-app `AudioTrack`
  while speaking so our uid becomes the active player and the session receives media buttons
  like any music player; released on pause/stop (the "lastly played" ordering keeps the
  paused session first in line, so resume taps still arrive).
- **Headset semantics** (`TtsHeadsetTapPlanning`): 1 tap toggle, 2 taps forward 30 s, 3 taps
  back 15 s, or whole chapters with the setting on (300 ms burst window). Repeated key-down events from a held button are
  ignored. Dedicated PLAY/PAUSE/NEXT/PREVIOUS/STOP keycodes act immediately.
- **AUDIO_BECOMING_NOISY** (headphones/Bluetooth disconnected) pauses via
  `TtsNoisyAudioReceiver`, registered only while playing.
- The manifest declares `androidx.media.session.MediaButtonReceiver` plus a
  `MEDIA_BUTTON` intent-filter on the service, so post-death media-button restarts route to
  the service (which enters foreground first, then dispatches the key to the session).

## Player surfaces

- **Mini-player** (`feature/player/TtsMiniPlayer.kt`, card in `TtsMiniPlayerBar.kt`): docked in the
  app shell's bottom shelf, above the navigation bar, while a session is loaded: cover, chapter
  title, time left and story, gold play/pause, ✕ to close the session (stop; per-story position
  kept), and a thin progress line. Tapping it opens the player. The screen frame ends above the
  shelf, so the bar never covers content. Hidden on the Reader (which docks its own player),
  Now Playing, and Listen (which leads with a Now playing card).
- **Now Playing screen** (`feature/player/PlayerScreen.kt`, `AppRoute.Player`): blurred cover
  background, a "Now reading" card with the sentence being spoken, cover, story +
  chapter titles, "Chapter n / N", interactive `SeekBar` scrubber with touch isolation and live
  seeking, prev/next chapter, prev/next sentence, play/pause, speed chip (0.75–2.0 presets, applied
  live and persisted), History chip, sleep timer chip (countdown or chapter-end pause), Stop chip (ends the
  session and navigates back), and a Read along button that opens the playing chapter in the
  Reader. It uses `AppNavigator.bringToTop`, dropping older readers of that story from the
  history, so Back from the reader always returns to Now Playing. Closes itself when playback ends. Rebuilds on chapter
  change; patches transport state in place otherwise.
- **Reader read-along** (`feature/reader/ReaderReadAlong.kt`, rules in `ReadAlongPlanning`): one
  controller per reader page owns the highlight, following, the pill, and opening the chapter
  playback moves to. It listens to `TtsEngine.playbackState` while its WebView is attached and stops
  when the page is removed, so no other screen or activity can cancel it. The page script
  (`ReaderReadAlongScript`) only renders what it is told (`WnaReader.render(sentence, scroll)`) and
  also pulls `ReaderBridge.readAlongState()` on load, on becoming visible, and once a second while
  visible. A pull that disagrees with what the page was last sent makes the controller catch up
  and restart its listener if it had stopped; each repair is logged as `read_along_healed` in the
  activity log (READER), alongside `read_along_listening` / `read_along_stopped` /
  `read_along_following`.
- **Reader TTS bar**: while the visible chapter is being read aloud (playing or paused), the
  Reader replaces its bottom chrome with a docked `ReaderPlayerDock` (play/pause, sentence n of N,
  time left, back 15, speed, open player, stop). Chapter prev/next navigation is hidden so stray
  taps cannot jump chapters or break playback; it returns when the session stops. Dragging the
  page past Android's touch slop stops following and, while audio plays, shows a "Back to current
  sentence" pill. Play/pause
  resumes the persisted session when the engine has no live session (cold-start phantom-pause fix).
- **Notification + media buttons**: short skips by default, or **chapter** skips with the setting on;
  sentence skipping stays available in Now Playing.

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

## Write cadence and crash loss

Both files are fsync'd atomic writes under the shared storage lock, so playback writes them
sparingly:

- A listener change (story, chapter, pause/play, speed, voice, a jump that lands in the history,
  a move backward) is written 250 ms after it settles.
- Plain forward progress through a playing chapter (`TtsSessionPlanning.isProgressOnly`) is
  written at most once every 10 s (`TtsSessionStore.PROGRESS_INTERVAL_MS`), ending with the
  latest sentence. That is 6–7 session writes and as many position writes per minute of
  listening, instead of two per sentence.
- Pause, stop, and natural completion write at once.
- When playback moves to another story before its progress was written, that story's position
  is still written first.

So if the process dies while playing (no pause or stop ran), resume loses at most about 10.25 s
of listening, rounded back to the start of the sentence as before. The repository's in-memory
session (Reader seed, `TtsPlaybackPreparer.resume`) can likewise lag the live place by that much
while playing; the live `TtsEngine.playbackState` is what screens show.

`tts_positions.json` is cached in memory (`AppStorage.ttsPositionsDoc`), loaded once and
reloaded with the other cached documents after a restore, import, or clear, so saving a position
no longer re-reads and re-parses the whole map. Its on-disk format is unchanged.

The `history` list (up to 10 jump-back places, `TtsPositionPlanning.MAX_HISTORY`) is most of each
file: in a November 2026 sample it was 2.5 KB of a 3.4 KB `tts_session.json` (10 entries) and
13 KB of a 23 KB `tts_positions.json` (49 entries over 20 stories). It is written along with each
position; trimming it would change the backup format, so it was left as is.

## Architecture separation

- `TtsNotificationManager`: encapsulates notification channel creation, MediaStyle layouts, and intent builder logic.
- `TtsMediaSessionManager`: encapsulates `MediaSessionCompat`, hardware media button handling, and playback state sync.
- `TtsWatchdog`: isolates utterance stall timeout tracking and automatic retries.
- `TtsSettingsApplier`: encapsulates voice and language resolution and engine property application.

## Reader auto-follow guard

Chapter auto-follow only fires while that reader chapter is the current route **and** was itself
reading along (`ReadAlongState.showsPlayer`), so opening another chapter of the same story while
playback is active or paused stays on the chosen chapter instead of bouncing back to the playing
chapter, and a chapter transition anywhere else (player skip, background auto-advance) does not
hijack the screen the user is looking at. It checks the story's current chapter list, not the one
the page opened with.

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

## Emulator QA (2026-09-04, webnovel_api36, debug build)

Verified live via `cmd media_session dispatch` + `input keyevent 85` (same MediaSessionService
dispatch Bluetooth uses): media button session resolves to our session (`dumpsys media_session`
was `null` before the claim), single-tap pause/resume, double-tap next chapter, triple-tap
previous chapter, STOP key teardown, becoming-noisy pause, paused session still owns media
buttons, mini-player ✕ close (bar hides, service stops, `tts_positions.json` retained), Now
Playing Stop chip (teardown + auto-navigate-back), chevron-up + ✕ confirmed by screenshot,
and stop→restart in one process no longer zombies the engine.
