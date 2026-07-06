# TTS Media3 Migration Evaluation

## Recommendation

Do not migrate the TTS foreground service from `MediaSessionCompat` to Media3 in the same change as
the audio-focus and robustness fixes.

## Rationale

- The existing `MediaSessionCompat` service already exposes lock-screen, notification, Bluetooth,
  and headset controls correctly.
- The highest-risk TTS gaps were behavioral correctness issues: audio focus, utterance errors,
  watchdog recovery, state serialization, and i18n. Those changes are small and testable against the
  current service.
- A Media3 migration would replace the service/session/controller contract and should be validated as
  its own user-visible playback migration, ideally with emulator QA across notification controls,
  hardware media buttons, background playback, and process restart.

## Suggested Scope For A Future Migration

- Replace `MediaSessionCompat` and `androidx.media:media` with the Media3 session stack.
- Preserve the current service actions long enough to keep existing notification/media-button intents
  compatible during the transition.
- Re-test TTS start, pause, resume, previous, next, stop, lock-screen controls, Bluetooth/media-button
  events, and resumed saved sessions.
- Revisit this if the reader UI moves to Compose or if the app adds Android Auto / Automotive media
  surfaces, where Media3 integration would provide more leverage.
