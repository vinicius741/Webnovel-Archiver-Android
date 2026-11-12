/* v4 Editorial — core: helpers, shell chrome (running foot, mini player, AI chip), layers (sheets, dialogs, toasts). */
(function () {
  const W = window.WNA;
  const esc = W.esc;
  const A = (window.APP = { W, esc, screens: {}, cleanups: [], route: null });
  const ICONS = (n, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${n}</span>`;
  A.icon = ICONS;
  A.$ = (s, r = document) => r.querySelector(s);
  A.$$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  // ---------- shared app state (survives navigation; in-memory like the kit) ----------
  const reading = W.tabs().find((t) => t.name === 'Reading');
  A.state = {
    tab: reading ? reading.id : 'all', view: 'shelf', query: '', sources: [], tags: [], sort: 'default', asc: null,
    filtersOpen: false, selecting: false, selected: new Set(), deleted: new Set(), continueDismissed: false, libScroll: 0,
    aiCover: {}, epub: {}, hiddenDetails: false,
  };
  A.alive = (s) => s && !A.state.deleted.has(s.id);

  // ---------- text helpers ----------
  A.roman = (n) => { const m = [[1000, 'm'], [900, 'cm'], [500, 'd'], [400, 'cd'], [100, 'c'], [90, 'xc'], [50, 'l'], [40, 'xl'], [10, 'x'], [9, 'ix'], [5, 'v'], [4, 'iv'], [1, 'i']]; let r = ''; for (const [v, s] of m) while (n >= v) { r += s; n -= v; } return r; };
  A.splitTitle = (t) => { const m = String(t).match(/^(.*?)\s*([[(][^\])]*[\])])\s*$/); return m && m[1] ? { main: m[1], sub: m[2].slice(1, -1) } : { main: t, sub: '' }; };
  A.titleHTML = (t) => { const p = A.splitTitle(t); return esc(p.main) + (p.sub ? ` <span class="tsub">${esc(p.sub)}</span>` : ''); };
  A.shortTitle = (t) => A.splitTitle(t).main;
  A.chParts = (title, index) => {
    const m = String(title).match(/^(?:chapter|ch\.?|book\s+\d+\s*[,:-]?\s*chapter)\s*(\d+)\s*[:.\-–—]?\s*(.*)$/i);
    if (m) return { n: m[1], name: m[2] || '' };
    const m2 = String(title).match(/^(\d+)\s*[.:\-–—]\s*(.*)$/);
    if (m2) return { n: m2[1], name: m2[2] };
    return { n: String(index || ''), name: title };
  };
  A.pubLabel = { ongoing: 'Ongoing', completed: 'Completed', hiatus: 'Hiatus', outdated: 'Outdated', unknown: '' };

  // ---------- covers ----------
  A.coverSrc = (s) => { const v = A.state.aiCover[s.id]; return v || W.coverUrl(s); };
  A.book = (s, { lazy = true, cls = '' } = {}) => {
    const url = A.coverSrc(s);
    const inner = url
      ? `<img src="${url}" alt="" ${lazy ? 'loading="lazy"' : ''} decoding="async">`
      : `<div class="fb" style="background:${W.coverFallback(s)}"><b>${esc(A.shortTitle(s.title))}</b><small>${esc(s.author || '')}</small></div>`;
    return `<div class="book ${cls}">${inner}</div>`;
  };
  A.cov = (s, cls = '') => {
    const c = W.coverage(s);
    const segs = c.segments.map(([a, b]) => `<i style="left:${(a * 100).toFixed(2)}%;width:${Math.max(0.6, (b - a) * 100).toFixed(2)}%"></i>`).join('');
    const rib = c.bookmark != null ? `<span class="rib" style="left:${(c.bookmark * 100).toFixed(2)}%"></span>` : '';
    return `<div class="cov ${cls}">${segs}${rib}</div>`;
  };

  // ---------- events ----------
  A.acts = (root, map) => {
    root.addEventListener('click', (e) => {
      const t = e.target.closest('[data-act]');
      if (!t || !root.contains(t)) return;
      const fn = map[t.dataset.act];
      if (fn) { e.preventDefault(); fn(t, e); }
    });
  };
  A.longPress = (root, sel, fn) => {
    let timer = null, start = null, fired = false;
    root.addEventListener('pointerdown', (e) => {
      const t = e.target.closest(sel); if (!t) return;
      fired = false; start = [e.clientX, e.clientY]; clearTimeout(timer);
      timer = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(12); fn(t, e); }, 450);
    });
    const cancel = () => { clearTimeout(timer); start = null; };
    root.addEventListener('pointermove', (e) => { if (start && Math.hypot(e.clientX - start[0], e.clientY - start[1]) > 10) cancel(); });
    root.addEventListener('pointerup', cancel); root.addEventListener('pointercancel', cancel);
    root.addEventListener('click', (e) => { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(sel)) e.preventDefault(); });
  };
  A.listen = (em, fn) => { const off = em.on(fn); A.cleanups.push(off); return off; };
  A.onLeave = (fn) => A.cleanups.push(fn);
  A.every = (ms, fn) => { const h = setInterval(fn, ms); A.cleanups.push(() => clearInterval(h)); };

  // global navigation clicks
  document.addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]');
    if (g) { e.preventDefault(); A.closeLayers(); W.go(g.dataset.go); return; }
    const b = e.target.closest('[data-back]');
    if (b) { e.preventDefault(); W.back(b.dataset.back || '/library'); }
  });

  // ---------- top bar ----------
  A.bar = ({ back = true, title = '', sub = '', actions = '', cls = '', fade = false, lead = '' } = {}) => `
    <header class="bar ${cls}">
      ${back ? `<button class="ib" data-back aria-label="Back">${ICONS('arrow_back')}</button>` : lead}
      <div class="ttl ${fade ? 'fade' : ''}">${title ? `<b>${title}</b>` : ''}${sub ? `<small>${sub}</small>` : ''}</div>
      ${actions}
    </header>`;
  A.ib = (icon, act, label, extra = '') => `<button class="ib" data-act="${act}" aria-label="${esc(label)}" ${extra}>${ICONS(icon)}</button>`;
  A.secHead = (n, t, a = '') => `<div class="sec-head">${n ? `<span class="n">${n}</span>` : ''}<span class="t">${t}</span><span class="r"></span>${a}</div>`;

  // ---------- layers ----------
  const layers = document.getElementById('layers');
  const open = [];
  function mount(node, { onClose } = {}) {
    const scrim = document.createElement('div'); scrim.className = 'scrim';
    layers.append(scrim, node);
    requestAnimationFrame(() => requestAnimationFrame(() => { scrim.classList.add('in'); node.classList.add('in'); }));
    const L = {
      el: node,
      close() {
        if (L.closed) return; L.closed = true;
        scrim.classList.remove('in'); node.classList.remove('in');
        setTimeout(() => { scrim.remove(); node.remove(); }, 320);
        const i = open.indexOf(L); if (i >= 0) open.splice(i, 1);
        if (onClose) onClose();
      },
    };
    scrim.addEventListener('click', () => L.close());
    open.push(L);
    return L;
  }
  A.closeLayers = () => open.slice().forEach((l) => l.close());
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && open.length) open[open.length - 1].close(); });

  A.sheet = ({ title = '', sub = '', html = '', cls = '', onClose } = {}) => {
    const node = document.createElement('section');
    node.className = 'sheet ' + cls;
    node.setAttribute('role', 'dialog');
    node.innerHTML = `<div class="grab"></div>${title ? `<div class="sh-h"><div class="disp">${title}</div>${sub ? `<div class="meta">${sub}</div>` : ''}</div>` : ''}<div class="sh-b">${html}</div>`;
    const L = mount(node, { onClose });
    // drag the grab area down to dismiss
    let y0 = null;
    node.addEventListener('pointerdown', (e) => { if (node.scrollTop <= 0 && e.target.closest('.grab, .sh-h')) { y0 = e.clientY; node.setPointerCapture(e.pointerId); } });
    node.addEventListener('pointermove', (e) => { if (y0 != null) { const dy = Math.max(0, e.clientY - y0); node.style.transition = 'none'; node.style.transform = `translateY(${dy}px)`; } });
    node.addEventListener('pointerup', (e) => { if (y0 == null) return; const dy = e.clientY - y0; y0 = null; node.style.transition = ''; node.style.transform = ''; if (dy > 90) L.close(); });
    return L;
  };
  // menu: groups [{label, items:[{icon,label,sub,danger,on,act}]}]
  A.menu = ({ title, sub, groups }) => {
    const fns = [];
    const html = `<ul class="mlist">${groups.map((g) => `${g.label ? `<li class="mgroup">${g.label}</li>` : ''}${g.items.filter(Boolean).map((it) => { fns.push(it.act); return `<li><button class="mi ${it.danger ? 'danger' : ''} ${it.on ? 'on' : ''}" data-i="${fns.length - 1}">${it.icon ? ICONS(it.icon) : ''}<span class="grow">${it.label}${it.sub ? `<small>${it.sub}</small>` : ''}</span>${it.end || ''}</button></li>`; }).join('')}`).join('')}</ul>`;
    const L = A.sheet({ title, sub, html });
    L.el.addEventListener('click', (e) => { const b = e.target.closest('[data-i]'); if (!b) return; const fn = fns[+b.dataset.i]; L.close(); if (fn) setTimeout(fn, 60); });
    return L;
  };
  A.dialog = ({ title, body = '', actions = [], cls = '' }) => {
    const node = document.createElement('div');
    node.className = 'dialog ' + cls; node.setAttribute('role', 'alertdialog');
    node.innerHTML = `${title ? `<h3>${title}</h3>` : ''}<div class="dbody">${body}</div><div class="acts">${actions.map((a, i) => `<button class="btn ${a.cls || 'text'}" data-a="${i}">${a.label}</button>`).join('')}</div>`;
    const L = mount(node);
    node.addEventListener('click', (e) => { const b = e.target.closest('[data-a]'); if (!b) return; const a = actions[+b.dataset.a]; if (!a.keep) L.close(); if (a.act) a.act(node, L); });
    return L;
  };
  A.confirm = (title, body, ok, fn, danger = false) => A.dialog({ title, body: `<p>${body}</p>`, actions: [{ label: 'Cancel' }, { label: ok, cls: danger ? 'danger' : '', act: fn }] });

  A.toast = (msg, { action, onAction, ms = 3200 } = {}) => {
    const box = document.getElementById('toasts');
    const t = document.createElement('div'); t.className = 'toast';
    t.innerHTML = `<span class="grow">${msg}</span>${action ? `<button>${action}</button>` : ''}`;
    box.appendChild(t);
    while (box.children.length > 2) box.firstChild.remove();
    const kill = () => { t.classList.add('out'); setTimeout(() => t.remove(), 260); };
    if (action) t.querySelector('button').onclick = () => { kill(); if (onAction) onAction(); };
    setTimeout(kill, ms);
  };

  // ---------- running foot (section tabs) ----------
  const foot = document.getElementById('foot');
  const SECTIONS = [
    ['library', '/library', 'auto_stories', 'Library'],
    ['narration', '/narration', 'graphic_eq', 'Listen'],
    ['queue', '/queue', 'download', 'Downloads'],
    ['settings', '/settings', 'tune', 'Settings'],
  ];
  A.renderFoot = (active) => {
    const dl = W.downloads.state(); const nar = W.narration.state();
    const badge = { queue: dl.downloading.length + dl.upNext.length, narration: nar.current && !nar.paused ? '•' : '' };
    foot.innerHTML = SECTIONS.map(([k, p, ic, l]) => `<button class="${k === active ? 'on' : ''}" data-go="${p}" aria-label="${l}">${ICONS(ic, k === active ? 'fill' : '')}<span>${l}</span>${badge[k] ? `<em>${badge[k]}</em>` : ''}</button>`).join('');
  };

  // ---------- contents (full table of contents of the app) ----------
  A.contents = () => {
    const p = W.player.state(); const nar = W.narration.state(); const dl = W.downloads.state();
    const row = (n, label, go, v = '', sub = false) => `<button class="toc-row ${sub ? 'sub' : ''}" data-go="${go}"><span class="n">${n}</span><span class="leaders grow"><span class="l">${label}</span><span class="d"></span><span class="v">${v}</span></span></button>`;
    const html = `<div class="toc">
      ${row('i', 'Library', '/library', W.stories().filter(A.alive).length)}
      ${row('ii', 'Add a story', '/add', '')}
      ${row('iii', 'Now playing', '/player', p.loaded ? 'Ch. ' + (p.chapterIndex + 1) : '—')}
      ${row('iv', 'Audio generation', '/narration', nar.queuedCount + ' queued')}
      ${row('v', 'Downloads', '/queue', dl.items.length + ' novels')}
      ${row('vi', 'Settings', '/settings', '')}
      ${[['Voice &amp; Speech', 'tts'], ['Text Cleanup', 'cleanup'], ['AI Settings', 'ai'], ['Notifications', 'notifications'], ['Manage Tabs', 'tabs'], ['Data &amp; Backup', 'data'], ['Download Settings', 'downloads'], ['App Updates', 'update']].map(([l, k], i) => row(String.fromCharCode(97 + i), l, '/settings/' + k, '', true)).join('')}
    </div>`;
    A.sheet({ title: '<i>Contents</i>', sub: 'Webnovel Archiver · v1.0.21-native', html, cls: 'toc-sheet' });
  };

  // ---------- mini player ----------
  const mini = document.getElementById('mini');
  let miniOn = false;
  A.miniAllowed = true;
  function renderMini() {
    const p = W.player.state();
    const show = A.miniAllowed && p.loaded;
    if (!show) { mini.hidden = true; if (miniOn) { miniOn = false; setVars(); } return; }
    const ch = p.chapter;
    const parts = A.chParts(ch.title, p.chapterIndex + 1);
    mini.hidden = false;
    mini.innerHTML = `
      <div class="mini-in">
        <button class="mp-play" data-mp="toggle" aria-label="${p.playing ? 'Pause' : 'Play'}">${p.buffering ? '<span class="spin"></span>' : ICONS(p.playing ? 'pause' : 'play_arrow', 'fill')}</button>
        <button class="mp-body" data-go="/player">
          <b class="clamp1">${esc(parts.name || ch.title)}</b>
          <small class="clamp1">${p.playing || p.buffering ? '<span class="eq"><i></i><i></i><i></i></span>' : ''}${W.fmt.dur(p.remainingSec)} left · ${esc(A.shortTitle(p.story.title))}</small>
        </button>
        <button class="ib" data-go="/player" aria-label="Open player">${ICONS('expand_less')}</button>
        <button class="ib" data-mp="close" aria-label="Close player">${ICONS('close')}</button>
        <div class="prog"><i style="width:${(p.progress * 100).toFixed(1)}%"></i></div>
      </div>`;
    if (!miniOn) { miniOn = true; setVars(); }
  }
  mini.addEventListener('click', (e) => {
    const b = e.target.closest('[data-mp]'); if (!b) return;
    if (b.dataset.mp === 'toggle') W.player.toggle();
    if (b.dataset.mp === 'close') { W.player.stop(); A.toast('Player closed · your place is saved'); }
  });
  W.player.on(() => { renderMini(); if (A.onPlayer) A.onPlayer(); });

  // ---------- AI job chip ----------
  const aichip = document.getElementById('aijob');
  A.aiJob = null;
  W.ai.on((st) => {
    if (st.job) A.aiJob = st.job; else if (A.aiJob && A.aiJob.storyId && !st.job) A.aiJob = null;
    renderAi();
  });
  let aiDismissed = false;
  function renderAi() {
    const j = A.aiJob; const r = A.route;
    if (!j || aiDismissed || (r && r.name === 'ai')) { aichip.hidden = true; if (!j) aiDismissed = false; return; }
    const s = W.story(j.storyId);
    aichip.hidden = false;
    aichip.innerHTML = `<button class="aj-b" data-go="/story/${j.storyId}/ai"><span class="spin"></span><span class="grow"><b>${esc(j.message)}</b><small class="clamp1">${esc(A.shortTitle(s.title))}</small></span></button><button class="ib" data-aj="x" aria-label="Dismiss">${ICONS('close', 'sm')}</button>`;
  }
  aichip.addEventListener('click', (e) => { if (e.target.closest('[data-aj]')) { aiDismissed = true; renderAi(); } });

  function setVars() {
    const root = document.documentElement.style;
    root.setProperty('--mini-h', !mini.hidden ? '72px' : '0px');
    root.setProperty('--foot-h', !foot.hidden ? '62px' : '0px');
  }

  // ---------- router ----------
  const view = document.getElementById('view');
  const ROOTS = { library: 'library', narration: 'narration', queue: 'queue', settings: 'settings' };
  let lastName = null;
  A.show = (r) => {
    A.closeLayers();
    if (lastName === 'library') A.state.libScroll = window.scrollY;
    A.cleanups.splice(0).forEach((f) => { try { f(); } catch (e) { console.error(e); } });
    A.onPlayer = null;
    A.route = r;
    const fn = A.screens[r.name] || A.screens.library;
    const page = document.createElement('div');
    page.className = 'page page-' + r.name;
    view.replaceChildren(page);
    view.classList.remove('enter'); void view.offsetWidth; view.classList.add('enter');
    const isRoot = !!ROOTS[r.name];
    foot.hidden = !isRoot;
    if (isRoot) A.renderFoot(ROOTS[r.name]);
    setVars();
    window.scrollTo(0, 0);
    // QA hooks: ?listen=1 starts the saved session, ?contents=1 opens the contents sheet
    if (r.query.listen && !W.player.state().loaded && W.session) W.player.load(W.session.storyId, W.session.chapterId, { index: W.session.index, play: true });
    const opts = fn(page, r) || {};
    if (r.query.contents) A.contents();
    A.miniAllowed = !opts.noMini;
    document.body.dataset.screen = r.name;
    renderMini(); renderAi(); setVars();
    if (r.name === 'library' && lastName && lastName !== 'library') window.scrollTo(0, A.state.libScroll);
    lastName = r.name;
    onScroll();
  };
  A.refreshFoot = () => { if (!foot.hidden && A.route) A.renderFoot(ROOTS[A.route.name]); };
  W.downloads.on(A.refreshFoot); W.narration.on(A.refreshFoot);

  function onScroll() {
    const b = view.querySelector('.bar');
    if (b) b.classList.toggle('scrolled', window.scrollY > 6);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
})();
