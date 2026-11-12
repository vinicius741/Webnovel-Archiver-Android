/* v4 Editorial — boot: start the router once every screen module has registered. */
(function () {
  const A = window.APP;
  A.W.router((r) => A.show(r));
})();
