# Text cleanup

Applies to `feature/cleanup/` (the Cleanup rules screen and its dialogs). These rules add to `android/AGENTS.md`.

- `CleanupScreenModel` (kept under `AppRoute.CleanupRules`) owns the mode, both lists, the sentence draft and each
  mode's scroll offset. The draft and scroll stay out of `state`, so typing and scrolling never rebuild; a mode or list
  change rebuilds the page. Saves run to the end (`NonCancellable`) and the lists are re-read after each.
- The draft, mode and scroll survive a process death: `CleanupScreenStatePersistence` writes them with the route stack
  (Bundle keys `cleanup.*`) and restores them through `ScreenModelStore.restore`; the model takes them on creation.
- The rule dialogs validate through model actions (`saveRegexRule`, `savePresetRule`) and dismiss only when the rule
  is accepted; their live previews call `previewRule`, which runs off the main thread. They never write the
  repository.
