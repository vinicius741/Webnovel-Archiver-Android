/* v6 — shell: router mount, dock (mini strip + command bar), places index, AI job chip, global delegation. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  const view = document.getElementById('view');
  const mini = document.getElementById('mini');
  const dock = document.getElementById('dock');
  let cur = null; // {name, cleanup, root, path}
  const scrollMem = {};

  const SCREEN_FOR = {
    library: 'library', add: 'add', story: 'story', select: 'select', trends: 'trends', ai: 'ai', author: 'author',
    reader: 'reader', player: 'player', narration: 'narration', narrationStory: 'narrationStory', queue: 'queue',
    settings: 'settings', settingsPage: 'settingsPage',
  };

  function mount(route) {
    // close any open sheets on navigation
    while (V6.closeTop()) { /* drain */ }
    if (cur) { scrollMem[cur.path] = cur.root.scrollTop; cur.subs.forEach((u) => { try { u(); } catch (e) { /* noop */ } }); cur.root.remove(); }
    const name = SCREEN_FOR[route.name] || 'library';
    document.body.className = 'r-' + name + (route.query.wide ? ' wide' : '');
    document.body.classList.remove('dock-hide', 'selecting');
    const root = W.el(`<section class="screen s-${name}"></section>`);
    view.appendChild(root);
    const ctx = { root, route, subs: [], sub(u) { this.subs.push(u); return u; } };
    cur = { name, root, path: route.path, subs: ctx.subs };
    const fn = V6.screens[name];
    try { fn ? fn(ctx) : (root.innerHTML = V6.empty('help', 'Unknown screen', esc(route.path))); } catch (e) { console.error(e); root.innerHTML = V6.empty('error', 'Something broke', esc(e.message)); }
    root.classList.add('enter');
    const y = scrollMem[route.path];
    if (y && !route.query.top) requestAnimationFrame(() => (root.scrollTop = y));
    renderMini();
    if (route.query.cmd != null) setTimeout(() => V6.palette.open(route.query.cmd), 60);
    if (route.query.places) setTimeout(placesSheet, 60);
  }
  V6.refresh = () => mount(W.route());
  V6.current = () => cur;

  // ---------- mini strip ----------
  function renderMini() {
    const p = W.player.state();
    if (!p.loaded) { mini.innerHTML = ''; mini.classList.remove('on'); return; }
    mini.classList.add('on');
    const left = p.remainingSec;
    if (!mini.firstElementChild) {
      mini.innerHTML = `<div class="mn-prog"><i></i></div><div class="mn-row">
        <button class="mn-play" data-mn="toggle" aria-label="Play or pause"></button>
        <button class="mn-t" data-mn="open"><b></b><small></small></button>
        <button class="ib" data-mn="open" aria-label="Open player">${ic('expand_less')}</button>
        <button class="ib" data-mn="close" aria-label="Close player">${ic('close')}</button></div>`;
    }
    mini.querySelector('.mn-prog i').style.width = (p.progress * 100).toFixed(1) + '%';
    mini.querySelector('.mn-play').innerHTML = ic(p.buffering ? 'progress_activity' : p.playing ? 'pause' : 'play_arrow', p.buffering ? 'spin' : '');
    mini.querySelector('.mn-t b').textContent = p.chapter.title;
    mini.querySelector('.mn-t small').textContent = `${W.fmt.dur(left)} left · ${V6.short(p.story)}`;
  }
  mini.addEventListener('click', (e) => {
    const b = e.target.closest('[data-mn]'); if (!b) return;
    const a = b.dataset.mn;
    if (a === 'toggle') W.player.toggle();
    else if (a === 'open') W.go('/player');
    else if (a === 'close') { W.player.stop(); V6.toast('Player closed', { icon: 'stop' }); }
  });
  // swipe the strip up to open the player
  (() => { let y0 = null; mini.addEventListener('pointerdown', (e) => (y0 = e.clientY)); mini.addEventListener('pointerup', (e) => { if (y0 != null && y0 - e.clientY > 40) W.go('/player'); y0 = null; }); })();
  W.player.on(renderMini);

  // dock height -> css var
  const ro = new ResizeObserver(() => document.documentElement.style.setProperty('--dock-h', dock.offsetHeight + 'px'));
  ro.observe(dock);

  // ---------- command bar ----------
  document.getElementById('cb-field').onclick = () => V6.palette.open();
  document.getElementById('cb-add').onclick = () => W.go('/add');
  document.getElementById('cb-menu').onclick = () => placesSheet();

  function placesSheet() {
    const d = W.downloads.state(), n = W.narration.state();
    const rows = [
      ['Library', '/library', String(V6.stories().length), 'Deck · grid · tabs'],
      ['Listen', '/player', W.player.state().loaded ? 'ON' : '—', 'Now playing'],
      ['Audio', '/narration', String(n.queuedCount), n.current ? `Generating · ${Math.round(n.current.progress * 100)}%` : 'Nothing is generating'],
      ['Downloads', '/queue', String(d.downloading.length + d.upNext.length), `${d.summary.done} of ${d.summary.total} chapters`],
      ['Settings', '/settings', '', 'Voice, reader, backups'],
      ['Add story', '/add', '+', 'Paste a story URL'],
    ];
    V6.sheet({ cls: 'places', html: `<nav class="pl">${rows.map(([t, p, n2, sub], i) => `<button class="pl-it" data-p="${p}"><span class="pl-i">0${i + 1}</span><span class="pl-t">${t}<small>${esc(sub)}</small></span><span class="pl-n">${n2}</span></button>`).join('')}</nav>
      <p class="pl-hint">${ic('bolt')} Tip: the command bar can do all of this. Try “sync”, “speed 2” or “sleep 30”.</p>`,
    onMount(body, api) { body.addEventListener('click', (e) => { const b = e.target.closest('[data-p]'); if (b) { api.close(); W.go(b.dataset.p); } }); } });
  }
  V6.placesSheet = placesSheet;

  // ---------- AI job chip (global) ----------
  const chip = W.el('<div class="aichip" hidden></div>');
  document.getElementById('app').appendChild(chip);
  let chipDismissed = null;
  V6.watchAi = (id) => {
    if (V6._aiWatch && V6._aiWatch.id === id) return;
    if (V6._aiWatch) V6._aiWatch.off();
    const off = W.ai.on((st) => {
      if (st.job && chipDismissed !== st.job.message) { chip.hidden = false; chip.innerHTML = `${ic('progress_activity', 'spin')}<span><b>${esc(st.job.message)}</b><small>${esc(V6.short(W.story(id)))}</small></span><button class="ib" aria-label="Dismiss">${ic('close')}</button>`; }
      else if (!st.job) { chip.hidden = true; chipDismissed = null; if (W.route().name !== 'ai') V6.toast(`AI result ready · ${esc(V6.short(W.story(id)))}`, { icon: 'auto_awesome', action: 'Open', onAction: () => W.go(`/story/${id}/ai`) }); }
    });
    V6._aiWatch = { id, off };
  };
  chip.addEventListener('click', (e) => { if (e.target.closest('button')) { chipDismissed = (chip.querySelector('b') || {}).textContent; chip.hidden = true; } else if (V6._aiWatch) W.go(`/story/${V6._aiWatch.id}/ai`); });

  // ---------- global delegation ----------
  document.addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]'); if (g) { e.preventDefault(); W.go(g.dataset.go); return; }
    const b = e.target.closest('[data-back]'); if (b) { e.preventDefault(); W.back(); }
  });
  document.addEventListener('keydown', (e) => {
    const typing = /INPUT|TEXTAREA/.test(document.activeElement && document.activeElement.tagName);
    if ((e.key === '/' || (e.key === 'k' && (e.metaKey || e.ctrlKey))) && !typing) { e.preventDefault(); V6.palette.open(); }
    else if (e.key === 'Escape') { if (!V6.closeTop() && !V6.palette.isOpen()) W.back(); }
  });

  W.router(mount);
})();
