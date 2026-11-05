# Audiobook-style TTS playback

How narration plays since November 2026. It builds on the session and position model in
[`tts-podcast-player.md`](tts-podcast-player.md). The goal: pause anywhere, put the device away,
come back days later, and continue without finding your place or hearing unexpected speech.

## The core change

`TextToSpeech.speak()` is "say this sentence": the audio plays in the engine's process, so the app
holds no audio and has no clock. Narration now renders sentences to files ahead of time and plays
them as one continuous stream, which is what makes these possible:

- Pause and resume are sample-exact, and position comes from the audio hardware.
- Time is real: elapsed, time left, scrubbing and 15/30-second skips.
- Speed changes instantly, with pitch preserved and no re-rendering.
- Pauses between sentences are chosen, not whatever the voice happened to leave, and keep their
  length at any speed.

## Pipeline

```
Chapter HTML
  → TtsTextPreparation.prepareTtsNarration   sentences + what kind of break follows each
  → TtsNarrationPlanning.items               script: spoken title, sentences, pauses
  → TtsTimeline                              time ↔ item; estimates become exact as items render
  → TtsAudioRenderer → TtsAudioCache         one trimmed PCM file per distinct sentence
  → TtsPcmPlayer                             one AudioTrack stream, position from its head counter
```

| Piece | Responsibility |
|---|---|
| `TtsEngine` | Commands, pause rules, chapter flow. Public API for the service and screens. |
| `TtsLoadedSession` | The loaded session and chapter, saved positions, history, snapshots. |
| `TtsOutputs` | Chooses rendered audio or the live fallback. |
| `RenderedTtsOutput` | Render plan + player for one chapter. |
| `LiveTtsOutput` | Fallback: the engine speaks one sentence at a time from the same script. |
| `TtsSpeechSynthesizer` | One function: text in, mono 16-bit WAV out. The seam for another voice source. |
| `TtsSpeechEngineHost` | The `TextToSpeech` instance: lazy creation, init wait, voice settings. |

### Rendering

- Always rendered at 1x. The speed setting is applied at playback by `AudioTrack` time stretching,
  so one rendered chapter works at any speed. Pitch is applied at render time.
- Order: listening position to the end of the chapter, then backward, then the next chapter.
- Cache keys hash voice + pitch + text, so renumbering cannot invalidate them and repeated lines
  render once. Files live in `cacheDir/tts_audio/` (never in backups), capped at 500 MB by least
  recent use. At 24 kHz mono that is about three hours.
- Each sentence's lead-in and tail are trimmed, because voices leave varying silence (measured:
  333 ms median, 689 ms p90 at 1x), with a 15 ms pad and a 5 ms fade.

### Pacing

Pauses are real time: `TtsPcmPlayer` writes more silence as speed rises, because the track's time
stretching would otherwise shorten it. Voice & Speech has a slider for the first four, and a Try it
card (`TtsSamplePreview`) that plays editable text through this same pipeline with the unsaved values,
because these can only be judged by ear. Saving reaches a loaded chapter from its next pause.

| Break | Default | Range |
|---|---|---|
| Extra at commas and phrases | Off | up to 1.5 s |
| Sentence | 500 ms | up to 3 s |
| Paragraph | 1 s | up to 5 s |
| Scene (`<hr>`, heading, a line like `* * *`) | 2 s | up to 8 s |
| After a spoken chapter title | 1.2 s | fixed |
| End of chapter | 1.5 s | fixed |

The phrase pause is inserted at playback in the middle of each silent gap of 80 ms or more inside a
rendered sentence, so moving its slider needs no re-render and intonation is untouched. Measured on a
Google voice at 1x, the 5 commas in the default sample gave 5 gaps. Rendered audio only: the live
fallback ignores it. Other voices may leave different gaps, which is unmeasured.

Word gaps cannot be set: voices run most words together with no silence to stretch, so only the speed
changes them. Time left divides by speed, so with pauses in real time it reads a little short at fast
speeds.

### Sentence splitting

Fragments the sentence breaker wrongly splits are rejoined, since each piece would otherwise get
its own closing intonation: `"Stop!"` + `the guard shouted.`, and `Dr.` + `Venn's seal…`. Scene
separators and symbol-only text are no longer sent to the voice. Reader highlighting stays aligned
because both paths share this code. Bump `CHUNKING_VERSION` when numbering changes.

## Position model

A saved place is a sentence number, an offset into it, and the sentence's opening text (the anchor).

- `TtsPositionPlanning.resolve` prefers the text: edits and cleanup changes can shift numbers, so the
  nearest sentence with that opening wins. The offset survives only if the same sentence is found.
- Positions from before anchors existed (`chunkingVersion` 0) are translated through the old
  numbering, recomputed by `TtsTextPreparation.legacyChunkTexts`.
- A finished story keeps its position with `caughtUp = true`, so a chapter published later continues
  from the right place. "Continue listening" hides it until then.
- `history` keeps the last 10 places jumped away from (chapter skips, paragraph starts, jumps of a
  minute or more), shown under History in Now Playing.

## Pause rules

Pause is the listener's decision. Only an explicit play, or recovery from a brief audio interruption,
starts audio.

- Sentence, chapter and time moves keep the current play/pause state.
- A service restart after process death plays only if the session was actually playing. A paused
  session comes back loaded and silent, with its notification, so headphones can resume it.
- Opening the app restores a saved session as paused and writes nothing, so its age stays accurate.
- A sleep timer running out cancels pending auto-resume and releases audio focus. Stopping playback
  cancels the timer. The last 10 seconds fade out. An end-of-chapter timer stops at the top of the next
  chapter rather than replaying the last sentence.

### Context replay on resume

`TtsPositionPlanning.resumePoint`, and it can be turned off in Voice & Speech:

| Break | Resumes at |
|---|---|
| Under 30 seconds | Exactly where it stopped |
| Under 10 minutes | Start of the sentence |
| Under 6 hours | Previous sentence |
| Under 3 days | Start of the paragraph |
| Longer | Start of the paragraph before |

## Controls

- **Headphones, notification, lock screen:** 1 tap toggles, 2 taps forward 30 s, 3 taps back 15 s. A
  setting switches next/previous back to whole chapters. Seeking past a chapter's end moves to the next.
- **System media card:** position, duration, speed, seek bar and cover come from `TtsMediaSessionManager`.
  In short-skip mode the skips are custom actions, which Android places in the previous/next slots.
- **Now Playing:** time labels and a time scrubber. `~` marks a length that is still an estimate.
- **Reader "Read aloud":** when the saved place is in another chapter it asks "Continue listening" or
  "Listen from this chapter". Double-tapping a paragraph still starts there.
- **Library:** a "Continue listening" card appears when nothing is loaded in the player. Its close
  button hides it across restarts, without forgetting any saved place, until something is played again.

## Fallback

If rendering fails after one retry, or an engine keeps producing no audio, `TtsOutputs` switches to
`LiveTtsOutput` for the rest of the process, from the same place. Time is then only an estimate, and
the silent `TtsMediaButtonClaim` track is used again, because the engine's process plays the audio.
Pausing continues from the last word when the voice sends word callbacks, else from the sentence.

## Decisions

- **No Media3 or ExoPlayer.** They were the first plan. A growing, chapter-long stream with its own
  pauses fits one `AudioTrack` better than a playlist of hundreds of sentence files, which would also
  show each sentence as a track on system surfaces. The existing `MediaSessionCompat` service already
  handled media buttons and restarts, and needed only position and custom actions added. Revisit for
  Android Auto; see `tts-media3-migration-evaluation.md`.
- **Render at 1x and stretch at playback**, rather than rendering at the chosen speed. Speed changes
  are instant and the cache survives them. The trade-off is that fast speeds are time-stretched rather
  than spoken faster by the voice, which may sound more processed.

## Verification (2026-11-04, `webnovel_api36`, debug build, Google `en-us-x-sfg-local`)

Device test `TtsPcmPlayerDeviceTest`: position tracks content time at 2x, pause holds it, seeks land,
playback ends. Live: skip while paused stays paused; kill while paused restarts silent and the
notification and media key still work; kill while playing continues; force-stop then launch restores
the mini-player; headset double and triple tap; chapter auto-advance with title; history undo;
end-of-chapter timer; Continue listening card; a forced render failure falling back to live speech;
folded and unfolded layouts.

Pauses (2026-11-05, same emulator and voice, 1.8x): raising the sentence slider from 0.5 s to 3 s added
10.0 s across the 4 sentence gaps of the sample, which is real time; time-stretched pauses would have
added 5.6 s. Saving during playback took a chapter from 10 to 6 sentences per 30 s and back to 11, with
no restart. Playing a sample pauses a playing session, which stays paused in the same place.

**Not yet verified:** the owner's phone and its voice engine, real earbuds and car Bluetooth, long
screen-off playback, how it sounds, including whether the new default pauses and phrase pauses sound natural (the emulator run was muted), and the fade-out on a timed sleep.
Render speed was measured at about 50x real time on the emulator; a phone will differ.
