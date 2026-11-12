/* v5-feed core: helpers, ambient colour, shared story state (sync, deletions), stage/router, dock (nav + mini player). */
(function () {
  const W = window.WNA;
  const esc = W.esc;
  const A = (window.V5 = { screens: {}, W, esc });

  // ---------- tiny event bus ----------
  const subs = {};
  A.onBus = (k, fn) => { (subs[k] = subs[k] || new Set()).add(fn); return () => subs[k].delete(fn); };
  A.emit = (k, ...a) => (subs[k] || []).forEach((f) => { try { f(...a); } catch (e) { console.error(e); } });

  // ---------- icons / small html ----------
  A.ic = (name, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${name}</span>`;
  A.short = (t) => String(t || '').split(/\s*[[(]/)[0].trim() || t;
  A.cover = (s, cls = '', eager = false) => {
    if (!s) return `<div class="cv ${cls}"></div>`;
    const u = W.coverUrl(s);
    const ld = eager ? 'eager' : 'lazy';
    return u
      ? `<div class="cv ${cls}"><img src="${u}" alt="" loading="${ld}" decoding="async" draggable="false"></div>`
      : `<div class="cv cv-fb ${cls}" style="background:${W.coverFallback(s)}"><span>${esc(A.short(s.title))}</span></div>`;
  };
  A.ambBg = (s, cls = '') => {
    const u = s && W.coverUrl(s);
    return `<div class="ambbg ${cls}" aria-hidden="true">${u ? `<img src="${u}" alt="" decoding="async">` : `<div class="ambfb" style="background:${W.coverFallback(s || {})}"></div>`}</div>`;
  };
  A.ring = (pct, size = 44, stroke = 3) => {
    const r = (size - stroke) / 2, c = 2 * Math.PI * r;
    return `<svg class="ring" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${r}" stroke-width="${stroke}" class="ring-t"/><circle cx="${size / 2}" cy="${size / 2}" r="${r}" stroke-width="${stroke}" class="ring-p" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - Math.max(0, Math.min(1, pct)))}"/></svg>`;
  };
  A.bar = (pct, cls = '') => `<div class="bar ${cls}"><i style="width:${Math.round(Math.max(0, Math.min(1, pct)) * 1000) / 10}%"></i></div>`;
  A.coverageBar = (s) => {
    const cv = W.coverage(s);
    return `<div class="cov">${cv.segments.map(([a, b]) => `<i style="left:${a * 100}%;width:${Math.max(0.6, (b - a) * 100)}%"></i>`).join('')}${cv.bookmark != null ? `<b style="left:${cv.bookmark * 100}%"></b>` : ''}</div>`;
  };
  A.pubPill = (s) => {
    const m = { ongoing: ['Ongoing', 'ok'], completed: ['Completed', 'acc'], hiatus: ['Hiatus', 'warn'], outdated: ['Outdated', 'warn'] }[s.pub];
    return m ? `<span class="pill p-${m[1]}">${m[0]}</span>` : '';
  };

  // ---------- library state that the prototype mutates ----------
  const deleted = new Set();
  A.deleted = deleted;
  A.stories = () => W.stories().filter((s) => !deleted.has(s.id));
  A.filter = (opts) => W.filterStories(opts).filter((s) => !deleted.has(s.id));
  A.dismissedContinue = false;

  // Forecast with local sync overrides
  const fcOver = {};
  A.forecast = (s) => fcOver[s.id] || W.forecast(s);
  A.eta = (s) => {
    const pubs = (s.recentPubs || []).filter(Boolean);
    if (s.pub === 'completed' || pubs.length < 3 || fcOver[s.id]) return null;
    const gaps = pubs.slice(1).map((t, i) => t - pubs[i]).sort((a, b) => a - b);
    const cadence = gaps[Math.floor(gaps.length / 2)];
    const now = (s.lastSync || W.NOW) + (W.hash(s.id) % 5) * 86400000 * 0.6;
    return { days: (pubs[pubs.length - 1] + cadence - now) / 86400000, cadence };
  };
  const syncing = new Set();
  A.isSyncing = (id) => syncing.has(id);
  A.sync = (s, { full = false } = {}) => {
    if (syncing.has(s.id)) return Promise.resolve(0);
    syncing.add(s.id); A.emit('sync', s.id);
    return new Promise((res) => setTimeout(() => {
      const f = W.forecast(s);
      let n = f.state === 'due' ? 1 + (W.hash(s.id) % 3) : f.state === 'late' ? W.hash(s.id) % 2 : 0;
      if (fcOver[s.id]) n = 0;
      const list = W.chapters(s.id);
      for (let i = 0; i < n; i++) { const idx = list.length + 1; list.push({ id: `${s.id}-n${idx}`, title: `Chapter ${idx}`, downloaded: false, publishedAt: Date.now() - (n - i) * 3600e3, index: idx, audio: false }); }
      s.total = list.length; s.lastSync = Date.now(); if (n) s.updated = Date.now();
      fcOver[s.id] = { state: 'synced', text: n ? `${n} new chapter${n > 1 ? 's' : ''} found · synced just now` : `Up to date · synced just now${full ? ' (full)' : ''}`, short: n ? `${n} new` : 'Up to date', fresh: n };
      syncing.delete(s.id); A.emit('sync', s.id, n); res(n);
    }, 1300 + Math.random() * 900));
  };

  // ---------- ambient colour from the cover ----------
  const ambCache = {}, ambDone = {};
  const DEF = { h: 36, s: 78 };
  function rgb2hsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    if (mx === mn) return [0, 0, l];
    const d = mx - mn, s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    const h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return [h * 60, s, l];
  }
  A.ambient = (s) => {
    if (!s) return Promise.resolve(DEF);
    if (ambCache[s.id]) return ambCache[s.id];
    const fb = { h: W.hue(s), s: 52 };
    const url = W.coverUrl(s);
    if (!url) { ambDone[s.id] = fb; return (ambCache[s.id] = Promise.resolve(fb)); }
    return (ambCache[s.id] = new Promise((res) => {
      const img = new Image();
      img.onload = () => {
        try {
          const c = document.createElement('canvas'); c.width = 18; c.height = 27;
          const g = c.getContext('2d', { willReadFrequently: true }); g.drawImage(img, 0, 0, 18, 27);
          const d = g.getImageData(0, 0, 18, 27).data;
          const bins = Array.from({ length: 12 }, () => ({ w: 0, x: 0, y: 0, s: 0 }));
          let tot = 0;
          for (let i = 0; i < d.length; i += 4) {
            const [h, sa, l] = rgb2hsl(d[i], d[i + 1], d[i + 2]);
            const w = sa * sa * (1 - Math.abs(l - 0.5) * 1.8);
            if (w <= 0) continue;
            const b = bins[Math.floor(h / 30) % 12]; b.w += w; b.x += Math.cos(h * Math.PI / 180) * w; b.y += Math.sin(h * Math.PI / 180) * w; b.s += sa * w; tot += w;
          }
          const best = bins.reduce((a, b) => (b.w > a.w ? b : a));
          if (!best.w || tot < 6) { ambDone[s.id] = { h: fb.h, s: 22 }; return res(ambDone[s.id]); }
          const h = (Math.atan2(best.y, best.x) * 180 / Math.PI + 360) % 360;
          ambDone[s.id] = { h: Math.round(h), s: Math.round(Math.min(90, Math.max(30, (best.s / best.w) * 100))) };
          res(ambDone[s.id]);
        } catch (e) { ambDone[s.id] = fb; res(fb); }
      };
      img.onerror = () => { ambDone[s.id] = fb; res(fb); };
      img.src = url;
    }));
  };
  A.palette = ({ h, s }) => ({
    '--acc': `hsl(${h} ${Math.max(s, 48)}% 72%)`,
    '--acc-2': `hsl(${h} ${Math.max(s, 42)}% 62%)`,
    '--acc-ink': `hsl(${h} 45% 11%)`,
    '--acc-soft': `hsl(${h} ${s}% 70% / .14)`,
    '--acc-line': `hsl(${h} ${s}% 70% / .32)`,
    '--amb': `hsl(${h} ${Math.round(s * 0.62)}% 24%)`,
    '--amb-2': `hsl(${(h + 28) % 360} ${Math.round(s * 0.5)}% 16%)`,
    '--amb-deep': `hsl(${h} ${Math.round(s * 0.4)}% 8%)`,
  });
  A.applyPal = (el, pal) => Object.entries(A.palette(pal)).forEach(([k, v]) => el.style.setProperty(k, v));
  // Tint an element now (cached or hue fallback), then refine once the cover has been sampled.
  A.tint = (el, s) => {
    if (!s) return A.applyPal(el, DEF);
    A.applyPal(el, ambDone[s.id] || { h: W.hue(s), s: 45 });
    if (!ambDone[s.id]) A.ambient(s).then((p) => A.applyPal(el, p));
  };
  A.tintRoot = (s) => A.tint(document.documentElement, s);

  // ---------- long press ----------
  A.longPress = (root, sel, fn) => {
    let t = null, sx = 0, sy = 0, fired = false;
    root.addEventListener('pointerdown', (e) => {
      const el = e.target.closest(sel); if (!el || !root.contains(el)) return;
      fired = false; sx = e.clientX; sy = e.clientY; clearTimeout(t);
      t = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(12); fn(el, e); }, 450);
    });
    const cancel = () => clearTimeout(t);
    root.addEventListener('pointermove', (e) => { if (Math.hypot(e.clientX - sx, e.clientY - sy) > 10) cancel(); });
    const up = () => { cancel(); if (fired) setTimeout(() => { fired = false; }, 600); };
    root.addEventListener('pointerup', up); root.addEventListener('pointercancel', up);
    root.addEventListener('scroll', cancel, true);
    root.addEventListener('click', (e) => { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(sel)) e.preventDefault(); });
  };

  // ---------- stage / router ----------
  const TOP = { home: 'home', library: 'library', narration: 'listen', queue: 'queue' };
  A.isTop = (n) => !!TOP[n];
  const stack = [];
  const scrollMem = {};
  let cur = null;
  let replacing = false;
  A.go = (p) => {
    const r = W.route();
    const target = p.split('?')[0].replace(/^\//, '').split('/')[0];
    const map = { home: 'home', library: 'library', narration: 'narration', queue: 'queue' };
    // Switching between top-level tabs replaces history (Home stays underneath) so Back always lands on Home.
    if (cur && A.isTop(cur.name) && cur.name !== 'home' && map[target] && !p.includes('/', 1)) { replacing = true; W.go(p, { replace: true }); return; }
    void r; W.go(p);
  };
  A.back = (fb = '/home') => W.back(fb);
  A.replace = (p) => { replacing = true; W.go(p, { replace: true }); };

  function resolve(r) {
    const raw = location.hash.replace(/^#/, '');
    if (!raw || raw === '/' || r.path === '/home' || r.path === 'home') return { ...r, name: 'home', path: '/home' };
    if (r.name === 'unknown') return { ...r, name: 'home', path: '/home' };
    if (r.name === 'settingsPage' && !A.screens['settings_' + r.params.page]) return { ...r, name: 'settings' };
    return r;
  }

  function show(r0) {
    const r = resolve(r0);
    const key = r.path;
    let dir = 'fwd';
    if (replacing) { replacing = false; stack.pop(); stack.push(key); dir = 'tab'; }
    else if (stack.length > 1 && stack[stack.length - 2] === key) { stack.pop(); dir = 'back'; }
    else if (stack[stack.length - 1] === key) dir = 'none';
    else { stack.push(key); if (cur && A.isTop(cur.name) && A.isTop(r.name)) dir = 'tab'; }
    if (r.name === 'player') dir = dir === 'back' ? 'back' : 'up';
    if (cur && cur.name === 'player' && dir === 'back') dir = 'down';
    if (!cur) dir = 'none';

    A.closeLayers && A.closeLayers(true);
    const el = document.createElement('section');
    el.className = 'screen scr-' + r.name;
    const ctx = { el, name: r.name, params: r.params, query: r.query, path: key, cleanups: [], on(u) { this.cleanups.push(u); return u; } };
    const fn = A.screens[r.name === 'settingsPage' ? 'settings_' + r.params.page : r.name] || A.screens.home;
    document.documentElement.style.removeProperty('--acc');
    try { fn(ctx); } catch (e) { console.error(e); el.innerHTML = `<div class="sc"><div class="empty"><h3>Something broke</h3><p>${esc(e.message)}</p></div></div>`; }
    document.getElementById('stage').appendChild(el);
    const sc = el.querySelector('.sc');
    if (sc && dir === 'back' && scrollMem[key]) sc.scrollTop = scrollMem[key];
    if (cur) {
      const old = cur;
      const osc = old.el.querySelector('.sc'); if (osc) scrollMem[old.path] = osc.scrollTop;
      old.cleanups.forEach((f) => { try { f(); } catch (e) { console.error(e); } });
      old.el.classList.add('leave', 'leave-' + dir);
      setTimeout(() => old.el.remove(), dir === 'down' ? 420 : 320);
    }
    if (dir !== 'none') { el.classList.add('enter-' + dir); el.addEventListener('animationend', () => el.classList.remove('enter-' + dir), { once: true }); }
    cur = ctx;
    A.current = ctx;
    updateDock();
    if (ctx.afterMount) requestAnimationFrame(() => ctx.afterMount());
  }
  A.rerender = () => show(W.route());

  // ---------- dock: nav pill + mini player ----------
  const NAV = [['home', '/home', 'home', 'Home'], ['library', '/library', 'auto_stories', 'Library'], ['listen', '/narration', 'headphones', 'Listen'], ['queue', '/queue', 'download', 'Downloads']];
  function navHtml(active) {
    return NAV.map(([k, p, ic, label]) => `<button class="nv ${k === active ? 'on' : ''}" data-go="${p}" aria-label="${label}">${A.ic(ic, k === active ? 'f' : '')}<b>${label}</b>${k === 'queue' ? '<i class="nbadge" id="qbadge"></i>' : ''}</button>`).join('');
  }
  function updateDock() {
    const n = cur ? cur.name : 'home';
    const showNav = A.isTop(n);
    const ps = W.player.state();
    const showMini = ps.loaded && !['reader', 'player'].includes(n);
    const nav = document.getElementById('nav');
    nav.innerHTML = showNav ? navHtml(TOP[n]) : '';
    document.body.classList.toggle('has-nav', showNav);
    document.body.classList.toggle('has-mini', showMini);
    document.body.classList.toggle('no-dock', ['reader', 'player'].includes(n));
    renderMini(ps, showMini);
    updateBadge();
  }
  A.updateDock = updateDock;
  function updateBadge() {
    const b = document.getElementById('qbadge'); if (!b) return;
    const d = W.downloads.state();
    const n = d.downloading.length;
    b.textContent = n ? n : ''; b.classList.toggle('show', !!n);
  }
  let miniKey = '';
  function renderMini(ps, show) {
    const m = document.getElementById('mini');
    if (!show) { m.innerHTML = ''; m.className = ''; miniKey = ''; return; }
    const k = ps.story.id + ps.chapter.id;
    if (k !== miniKey) {
      miniKey = k;
      m.className = 'mini glass';
      A.tint(m, ps.story);
      m.innerHTML = `<button class="mini-body" data-go="/player" aria-label="Open player">${A.cover(ps.story, 'mini-cv', true)}<span class="mini-t"><b class="mini-ch"></b><small class="mini-sub"></small></span></button>
        <button class="ibtn mini-pp" data-act="toggle" aria-label="Play or pause"><span class="mini-ring"></span><span class="mini-ic"></span></button>
        <button class="ibtn sm" data-act="close" aria-label="Close player">${A.ic('close')}</button><div class="mini-prog"><i></i></div>`;
    }
    m.querySelector('.mini-ch').textContent = ps.chapter.title;
    m.querySelector('.mini-sub').textContent = `${ps.buffering ? 'Loading voice…' : W.fmt.dur(ps.remainingSec) + ' left'} · ${A.short(ps.story.title)}`;
    m.querySelector('.mini-ring').innerHTML = A.ring(ps.progress, 44, 3);
    m.querySelector('.mini-ic').innerHTML = A.ic(ps.playing || ps.buffering ? 'pause' : 'play_arrow', 'f');
    m.querySelector('.mini-prog i').style.width = ps.progress * 100 + '%';
  }
  document.getElementById('dock').addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]');
    if (g) { const p = g.dataset.go; if (cur && cur.path === p) { const sc = cur.el.querySelector('.sc'); if (sc) sc.scrollTo({ top: 0, behavior: 'smooth' }); return; } A.go(p); return; }
    const a = e.target.closest('[data-act]'); if (!a) return;
    if (a.dataset.act === 'toggle') W.player.toggle();
    if (a.dataset.act === 'close') { W.player.stop(); A.toast && A.toast('Player closed'); }
  });
  let lastLoaded = false;
  W.player.on((ps) => {
    if (ps.loaded !== lastLoaded) { lastLoaded = ps.loaded; updateDock(); return; }
    const showMini = ps.loaded && cur && !['reader', 'player'].includes(cur.name);
    if (showMini) renderMini(ps, true);
  });
  W.downloads.on(updateBadge);

  // ---------- delegated navigation inside screens ----------
  document.getElementById('stage').addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]');
    if (g && !e.defaultPrevented) { e.preventDefault(); A.go(g.dataset.go); return; }
    const b = e.target.closest('[data-back]');
    if (b) { e.preventDefault(); A.back(b.dataset.back || '/home'); }
  });

  A.start = () => W.router(show);
  A.stack = stack;
})();
