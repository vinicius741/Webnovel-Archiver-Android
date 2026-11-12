/* v3 Restructured — app shell: router → screens, bottom nav / rail, mini player dock. Loaded last. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  const stage = document.getElementById('stage');
  const dock = document.getElementById('dock');
  const bottomnav = document.getElementById('bottomnav');
  const rail = document.getElementById('rail');

  const NAV = [
    { id: 'library', icon: 'auto_stories', label: 'Library', path: '/library' },
    { id: 'updates', icon: 'new_releases', label: 'Updates', path: '/updates' },
    { id: 'listen', icon: 'headphones', label: 'Listen', path: '/narration' },
    { id: 'downloads', icon: 'download', label: 'Downloads', path: '/queue' },
    { id: 'settings', icon: 'settings', label: 'Settings', path: '/settings' },
  ];

  // ---------- route → screen ----------
  function resolve(r) {
    const n = r.name;
    const id = r.params.id;
    if (n === 'unknown' && r.path === '/updates') return { screen: 'updates', key: 'updates' };
    if (n === 'story' || n === 'trends' || n === 'ai' || n === 'narrationStory') return { screen: 'story', key: 'story:' + id };
    if (n === 'reader') return { screen: 'reader', key: `reader:${id}:${r.params.chapterId}` };
    if (n === 'settingsPage') return { screen: 'settingsPage', key: 'settings:' + r.params.page };
    if (n === 'select' || n === 'author') return { screen: n, key: n + ':' + id };
    if (n === 'narration') return { screen: 'listen', key: 'listen' };
    if (n === 'queue' || n === 'settings' || n === 'add' || n === 'library') return { screen: n, key: n };
    return { screen: 'library', key: 'library' };
  }

  let current = null; // {key, screen, ctx}
  const cache = {}; // persistent screens
  let lastRoute = null;

  function show(res, route) {
    const def = A.screens[res.screen];
    if (!def) { console.error('missing screen', res.screen); return; }
    if (current && current.key === res.key) { current.ctx.update && current.ctx.update(route); chrome(def); return; }
    const wasTop = current && A.screens[current.screen].persistent;
    if (current) {
      if (A.screens[current.screen].persistent) { current.ctx.el.hidden = true; current.ctx.onHide && current.ctx.onHide(); }
      else { current.ctx.dispose && current.ctx.dispose(); current.ctx.el.remove(); }
    }
    let ctx = def.persistent ? cache[res.screen] : null;
    if (ctx) { ctx.el.hidden = false; ctx.update && ctx.update(route); }
    else {
      ctx = def.render(route);
      ctx.el.classList.add('view');
      stage.appendChild(ctx.el);
      if (def.persistent) cache[res.screen] = ctx;
    }
    const el = ctx.el;
    el.classList.remove('enter', 'enter-fade');
    void el.offsetWidth;
    el.classList.add(def.persistent && (wasTop || !current) ? 'enter-fade' : 'enter');
    current = { key: res.key, screen: res.screen, ctx };
    chrome(def);
  }

  function chrome(def) {
    document.body.classList.toggle('nav-on', def.nav !== false);
    A.miniAllowed = def.mini !== false;
    A.hideResume = !!def.hideResume;
    const active = def.navId || 'library';
    document.querySelectorAll('.navitem').forEach((b) => b.classList.toggle('on', b.dataset.nav === active));
    renderDock();
  }

  const startRouter = () => W.router((r) => {
    A.closeSheets();
    lastRoute = r;
    if (r.name === 'player') {
      if (!current) show({ screen: 'listen', key: 'listen' }, { name: 'narration', params: {}, query: {}, path: '/narration' });
      A.np.open(r);
      renderDock();
      return;
    }
    if (A.np.isOpen()) A.np.close();
    show(resolve(r), r);
  });
  A.currentRoute = () => lastRoute;

  // ---------- nav bar + rail ----------
  const navHtml = (railMode) => NAV.map((n) => `<button class="navitem" data-nav="${n.id}" data-path="${n.path}" aria-label="${n.label}"><span class="pill">${A.icon(n.icon)}<i class="badge" data-badge="${n.id}" hidden></i></span><span>${n.label}</span></button>`).join('');
  bottomnav.innerHTML = navHtml(false);
  rail.innerHTML = `<button class="fab-sm" data-act="add" aria-label="Add story">${A.icon('add')}</button>` + navHtml(true);
  const onNav = (e, b) => {
    const target = b.dataset.path;
    if (current && A.screens[current.screen].navId === b.dataset.nav && A.screens[current.screen].persistent) { current.ctx.el.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    const top = current && A.screens[current.screen].persistent;
    W.go(target, { replace: !!top });
  };
  W.on(bottomnav, 'click', '.navitem', onNav);
  W.on(rail, 'click', '.navitem', onNav);
  W.on(rail, 'click', '[data-act=add]', () => W.go('/add'));
  document.addEventListener('click', (e) => { const b = e.target.closest('[data-act=back]'); if (b) W.back(); });

  function badges() {
    const due = A.stories().filter((s) => W.forecast(s).state === 'due' && !A.state.synced[s.id]).length;
    const dl = W.downloads.state();
    const active = dl.downloading.length + dl.upNext.length;
    const nar = W.narration.state();
    const set = (id, txt, dot) => document.querySelectorAll(`[data-badge="${id}"]`).forEach((b) => { b.hidden = !txt && !dot; b.textContent = dot ? '' : txt; b.classList.toggle('dot', !!dot); });
    set('updates', due ? (due > 99 ? '99+' : String(due)) : '');
    set('downloads', active ? String(active) : '');
    set('listen', '', !!nar.current && !nar.paused);
  }
  W.downloads.on(W.debounce(badges, 300));
  W.narration.on(W.debounce(badges, 500));
  A.onChange(badges);
  badges();

  // ---------- mini player dock ----------
  let dockMode = null;
  function renderDock() {
    const st = W.player.state();
    const sess = W.session;
    let mode = st.loaded ? 'play' : sess && !A.state.resumeDismissed && !A.hideResume ? 'resume' : null;
    if (!A.miniAllowed || (A.np && A.np.isOpen())) mode = mode ? 'hidden-' + mode : null;
    const visible = mode === 'play' || mode === 'resume';
    dock.hidden = !visible;
    document.body.classList.toggle('mini-on', visible);
    if (!visible) { dockMode = mode; return; }
    if (mode !== dockMode || (mode === 'play' && dock.dataset.sid !== st.story.id)) {
      if (mode === 'play') {
        dock.dataset.sid = st.story.id;
        dock.innerHTML = `<div class="mini"><button class="mhit" data-act="open">${A.coverHtml(st.story)}<div class="mt"><b data-f="ch"></b><span data-f="sub"></span></div></button>
          <button class="play" data-act="toggle" aria-label="Play or pause">${A.icon('play_arrow', 'f')}</button>
          <button class="ib" data-act="close" aria-label="Close player">${A.icon('close')}</button><div class="bar"><i></i></div></div>`;
      } else {
        const s = W.story(sess.storyId);
        dock.innerHTML = `<div class="mini resume"><button class="mhit" data-act="resume">${A.coverHtml(s)}<div class="mt"><b>Continue listening</b><span>${esc(sess.chapterTitle)} · ${esc(A.short(s.title, 22))}</span></div></button>
          <button class="play" data-act="resume" aria-label="Resume">${A.icon('play_arrow', 'f')}</button>
          <button class="ib" data-act="dismiss" aria-label="Dismiss">${A.icon('close')}</button></div>`;
      }
      dockMode = mode;
    }
    if (mode === 'play') {
      dock.querySelector('[data-f=ch]').textContent = st.chapter.title;
      dock.querySelector('[data-f=sub]').innerHTML = `${st.playing ? '<span class="eq"><i></i><i></i><i></i></span>' : ''}${st.buffering ? 'Loading…' : fmt.dur(st.remainingSec) + ' left'} · ${esc(A.short(st.story.title, 24))}`;
      dock.querySelector('[data-act=toggle]').innerHTML = st.buffering ? '<span class="spinner"></span>' : A.icon(st.playing ? 'pause' : 'play_arrow', 'f');
      dock.querySelector('.bar i').style.width = Math.round(st.progress * 100) + '%';
    }
  }
  A.renderDock = renderDock;
  W.player.on(renderDock);
  W.on(dock, 'click', '[data-act]', (e, b) => {
    const a = b.dataset.act;
    if (a === 'open') W.go('/player');
    else if (a === 'toggle') W.player.toggle();
    else if (a === 'close') { W.player.stop(); A.toast('Player closed'); }
    else if (a === 'resume') { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); }
    else if (a === 'dismiss') { A.state.resumeDismissed = true; renderDock(); A.toast('Hidden until you listen again', 'Undo', () => { A.state.resumeDismissed = false; renderDock(); }); }
  });
  // swipe up on the mini player expands it into Now Playing
  let sy = null;
  dock.addEventListener('pointerdown', (e) => { sy = e.clientY; });
  dock.addEventListener('pointerup', (e) => { if (sy != null && sy - e.clientY > 40 && W.player.state().loaded) W.go('/player'); sy = null; });

  startRouter();
})();
