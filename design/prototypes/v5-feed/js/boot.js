/* v5-feed boot: start the router once every screen module has registered. */
(function () {
  const A = window.V5;
  // Tint the base accent from the saved session so the app opens in its "current story" colour.
  const ss = A.W.session;
  if (ss) A.ambient(A.W.story(ss.storyId));
  A.start();
})();
