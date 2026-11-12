/* v0 boot: restore the saved TTS session (paused, like the real app's mini player) and start the router. */
(function () {
  const W = window.WNA;
  if (!W) return;
  const q = new URLSearchParams((location.hash.split('?')[1]) || '');
  const ses = W.session;
  // ?idle=1 starts with nothing loaded so the Library shows the "Continue listening" card instead of the mini player.
  // The kit already restores the session (or the place carried over from another version); only fill a gap.
  if (q.get('idle')) W.player.stop();
  else if (ses && !W.player.state().loaded) W.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: false });
  W.router((route) => window.App.show(route));
})();
