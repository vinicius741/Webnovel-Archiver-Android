/* v2-modern shell: page stack navigation with shared-axis / container transitions, mini player, AI job chip. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const stage = document.getElementById('stage');
  const stack = [];
  let pendingBack = false, pendingReplace = false;
  let lastWide = V2.wide();

  const parentOf = (r) => {
    const p = r.params || {};
    switch (r.name) {
      case 'select': case 'trends': case 'ai': return '/story/' + p.id;
      case 'reader': return '/story/' + p.id;
      case 'narrationStory': return '/narration';
      case 'settingsPage': return '/settings';
      default: return '/library';
    }
  };

  V2.go = (path) => {
    const bare = path.split('?')[0];
    for (let i = stack.length - 2; i >= 0; i--) {
      if (stack[i].path === bare) { history.go(i - (stack.length - 1)); return; }
    }
    WNA.go(path);
  };
  V2.replace = (path) => { pendingReplace = true; WNA.go(path, { replace: true }); };
  V2.back = () => {
    V2.closeSheets();
    if (stack.length > 1) history.back();
    else { const top = stack[stack.length - 1]; const r = top ? top.r : WNA.route(); if (r.name === 'library') return; pendingBack = true; WNA.go(parentOf(r), { replace: true }); }
  };
  V2.top = () => stack[stack.length - 1];

  // ---------- page build ----------
  function build(r) {
    const page = document.createElement('section');
    page.className = 'page';
    page.dataset.route = r.name;
    const ctx = {
      el: page, r, params: r.params, query: r.query, cleanups: [], mini: true, anim: 'x',
      sub(fn) { ctx.cleanups.push(fn); return fn; },
      onShow: null,
    };
    const fn = V2.screens[r.name] || V2.screens.library;
    try { fn(ctx); } catch (e) { console.error(e); page.innerHTML = `${V2.bar({ title: 'Something went wrong' })}<div class="scroll"><div class="empty"><div class="blob">${ic('error')}</div><h3>This screen failed to load</h3><p>${esc(e.message)}</p></div></div>`; }
    stage.appendChild(page);
    return { path: r.path, qs: JSON.stringify(r.query), r, el: page, ctx };
  }
  function destroy(entry) { entry.ctx.cleanups.forEach((f) => { try { f(); } catch (e) { console.error(e); } }); entry.el.remove(); }
  function saveScroll(entry) { entry.scroll = [...entry.el.querySelectorAll('.scroll,.pane')].map((s) => s.scrollTop); }
  function restoreScroll(entry) { if (!entry.scroll) return; [...entry.el.querySelectorAll('.scroll,.pane')].forEach((s, i) => { s.scrollTop = entry.scroll[i] || 0; }); }

  const D_IN = 420, D_OUT = 220;
  function animate(from, to, dir, kind) {
    const fwd = dir === 'fwd';
    if (to) { to.hidden = false; to.style.zIndex = fwd ? 2 : 1; }
    if (from) from.style.zIndex = fwd ? 1 : 2;
    const opt = (d, e) => ({ duration: d, easing: e, fill: 'both' });
    const decel = 'cubic-bezier(.05,.7,.1,1)', accel = 'cubic-bezier(.3,0,.8,.15)', emph = 'cubic-bezier(.2,0,0,1)';
    let a1 = null, a2 = null;
    if (kind === 'up') {
      if (fwd) { a2 = to.animate([{ transform: 'translateY(14%)', opacity: 0, borderRadius: '28px' }, { transform: 'none', opacity: 1, borderRadius: '0px' }], opt(D_IN, decel)); a1 = from && from.animate([{ opacity: 1 }, { opacity: 0.4 }], opt(D_IN, emph)); }
      else { a1 = from && from.animate([{ transform: 'none', opacity: 1 }, { transform: 'translateY(14%)', opacity: 0, borderRadius: '28px' }], opt(260, accel)); a2 = to && to.animate([{ opacity: 0.4 }, { opacity: 1 }], opt(260, emph)); }
    } else if (kind === 'fade') {
      a2 = to && to.animate([{ opacity: 0, transform: 'scale(.98)' }, { opacity: 1, transform: 'none' }], opt(360, decel));
      a1 = from && from.animate([{ opacity: 1 }, { opacity: 0 }], opt(180, emph));
    } else {
      const s = fwd ? 1 : -1;
      a2 = to && to.animate([{ transform: `translateX(${48 * s}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }], { ...opt(D_IN, decel), delay: 60 });
      a1 = from && from.animate([{ transform: 'none', opacity: 1 }, { transform: `translateX(${-48 * s}px)`, opacity: 0 }], opt(D_OUT, emph));
    }
    return Promise.all([a1 && a1.finished, a2 && a2.finished].filter(Boolean)).then(() => {
      if (to) to.getAnimations().forEach((a) => a.cancel());
      if (from) from.getAnimations().forEach((a) => a.cancel());
    }).catch(() => {});
  }

  function onRoute(r) {
    V2.closeSheets();
    const top = stack[stack.length - 1];
    if (top && top.path === r.path) {
      pendingBack = false; pendingReplace = false;
      if (JSON.stringify(r.query) !== top.qs) { const n = build(r); destroy(top); stack[stack.length - 1] = n; chrome(); }
      return;
    }
    let idx = -1;
    for (let i = stack.length - 2; i >= 0; i--) if (stack[i].path === r.path) { idx = i; break; }
    if (idx >= 0) {
      const removed = stack.splice(idx + 1);
      const target = stack[idx];
      const leaving = removed[removed.length - 1];
      removed.slice(0, -1).forEach(destroy);
      if (target.stale) { const n = build(target.r); destroy(target); stack[idx] = n; }
      const t = stack[idx];
      t.el.hidden = false; restoreScroll(t);
      if (t.ctx.onShow) t.ctx.onShow();
      chrome();
      animate(leaving.el, t.el, 'back', leaving.ctx.anim === 'up' ? 'up' : 'x').then(() => destroy(leaving));
    } else {
      const entry = build(r);
      const prev = stack[stack.length - 1];
      if ((pendingBack || pendingReplace) && prev) {
        stack[stack.length - 1] = entry; chrome();
        animate(prev.el, entry.el, pendingBack ? 'back' : 'fwd', pendingBack ? (prev.ctx.anim === 'up' ? 'up' : 'x') : 'fade').then(() => destroy(prev));
      } else {
        stack.push(entry); chrome();
        if (prev) { saveScroll(prev); animate(prev.el, entry.el, 'fwd', entry.ctx.anim).then(() => { if (stack.includes(prev) && stack[stack.length - 1] !== prev) prev.el.hidden = true; }); }
      }
    }
    pendingBack = false; pendingReplace = false;
  }

  addEventListener('resize', WNA.debounce(() => {
    const w = V2.wide();
    if (w === lastWide) return; lastWide = w;
    stack.forEach((e, i) => { if (i < stack.length - 1) e.stale = true; });
    const top = stack[stack.length - 1]; if (!top) return;
    saveScroll(top); const n = build(top.r); destroy(top); stack[stack.length - 1] = n; restoreScroll(n); chrome();
  }, 150));

  // global back handler
  document.addEventListener('click', (e) => { if (e.target.closest('[data-a="back"]')) { e.preventDefault(); V2.back(); } });

  // ---------- hero (cover → details container transform) ----------
  V2.heroFrom = (imgOrCov, s) => {
    const r = imgOrCov.getBoundingClientRect();
    V2._hero = { rect: r, url: WNA.coverUrl(s), bg: WNA.coverFallback(s), at: Date.now(), id: s.id };
  };
  V2.heroTake = (id) => { const h = V2._hero; V2._hero = null; return h && h.id === id && Date.now() - h.at < 1500 ? h : null; };
  V2.flyHero = (h, target) => {
    requestAnimationFrame(() => {
      const t = target.getBoundingClientRect();
      if (!t.width) return;
      const ghost = el(`<div style="position:fixed;z-index:850;left:${t.left}px;top:${t.top}px;width:${t.width}px;height:${t.height}px;border-radius:24px;overflow:hidden;background:${h.url ? 'var(--sc-high)' : h.bg};box-shadow:0 18px 40px rgb(0 0 0/.45);pointer-events:none">${h.url ? `<img src="${h.url}" style="width:100%;height:100%;object-fit:cover">` : ''}</div>`);
      document.body.appendChild(ghost);
      target.style.visibility = 'hidden';
      const sx = h.rect.width / t.width, sy = h.rect.height / t.height;
      ghost.animate([
        { transform: `translate(${h.rect.left - t.left}px, ${h.rect.top - t.top}px) scale(${sx}, ${sy})`, transformOrigin: '0 0', borderRadius: '14px' },
        { transform: 'none', transformOrigin: '0 0', borderRadius: '24px' },
      ], { duration: 480, easing: 'cubic-bezier(.2,0,0,1)' }).finished.then(() => { target.style.visibility = ''; ghost.remove(); });
    });
  };

  // ---------- mini player ----------
  const mini = el(`<div id="mini" class="mini" hidden>
    <button class="mini-main" data-m="open" aria-label="Open player"><span class="mini-cov"></span><span class="tx"><b></b><span></span></span></button>
    <button class="ib mini-play" data-m="play" aria-label="Play">${ic('play_arrow', 'fill')}</button>
    <button class="ib" data-m="expand" aria-label="Open player">${ic('keyboard_arrow_up')}</button>
    <button class="ib" data-m="close" aria-label="Close player">${ic('close')}</button>
    <div class="mini-prog"><i></i></div>
  </div>`);
  document.body.appendChild(mini);
  let miniStory = null;
  function renderMini(st) {
    const top = stack[stack.length - 1];
    const show = st.loaded && (!top || top.ctx.mini !== false);
    mini.hidden = !show;
    document.documentElement.style.setProperty('--mini-space', show ? '84px' : '0px');
    if (!st.loaded) return;
    if (miniStory !== st.story.id) {
      miniStory = st.story.id; V2.theme(mini, st.story);
      mini.querySelector('.mini-cov').innerHTML = V2.cover(st.story, { w: 44, h: 44, eager: true, label: false });
    }
    mini.querySelector('.tx b').textContent = st.chapter.title;
    mini.querySelector('.tx span').textContent = `${st.buffering ? 'Loading voice…' : fmt.dur(st.remainingSec) + ' left'} · ${st.story.title}`;
    const pb = mini.querySelector('.mini-play');
    pb.classList.toggle('playing', st.playing);
    pb.innerHTML = st.buffering ? '<span class="loader"></span>' : ic(st.playing ? 'pause' : 'play_arrow', 'fill');
    mini.querySelector('.mini-prog i').style.width = (st.progress * 100).toFixed(1) + '%';
  }
  function chrome() { renderMini(WNA.player.state()); }
  V2.chrome = chrome;
  WNA.player.on(renderMini);
  mini.addEventListener('click', (e) => {
    const b = e.target.closest('[data-m]'); if (!b) return;
    const m = b.dataset.m;
    if (m === 'play') WNA.player.toggle();
    else if (m === 'close') { const st = WNA.player.state(); WNA.player.stop(); V2.snack('Player closed', { action: 'Undo', onAction: () => WNA.player.load(st.story.id, st.chapter.id, { index: st.index, play: false }) }); }
    else V2.go('/player');
  });
  // swipe the mini player up to open Now Playing
  let my = null;
  mini.addEventListener('pointerdown', (e) => { my = e.clientY; });
  mini.addEventListener('pointerup', (e) => { if (my != null && my - e.clientY > 40) V2.go('/player'); my = null; });

  // ---------- AI job chip (global) ----------
  let chip = null;
  V2.aiWatch = (storyId) => {
    const s = WNA.story(storyId);
    const update = () => {
      const st = WNA.ai.state(storyId);
      if (st.job) {
        if (!chip) { chip = el(`<div class="jobchip"><span class="loader"></span><span class="grow nowrap t-ll"></span><button class="ib" aria-label="Dismiss">${ic('close')}</button></div>`); chip.querySelector('.ib').onclick = () => { chip.remove(); chip = null; }; chip.querySelector('.grow').onclick = () => V2.go(`/story/${storyId}/ai`); document.body.appendChild(chip); }
        chip.querySelector('.grow').innerHTML = `${esc(st.job.kind === 'cover' ? 'Generating cover' : 'Writing description')} · <span style="opacity:.75">${esc(V2.shortTitle(s.title))}</span>`;
      } else if (!st.job) {
        if (!chip) { off(); return; }
        chip.remove(); chip = null;
        const t = V2.top(); if (t && t.r.name === 'ai' && t.r.params.id === storyId) { off(); return; }
        V2.snack(st.pending ? 'New cover ready' : st.draft ? 'Description draft ready' : 'AI job finished', { action: 'View', onAction: () => V2.go(`/story/${storyId}/ai`) });
        off();
      }
    };
    const off = WNA.ai.on(update);
  };

  V2.start = () => {
    WNA.router(onRoute);
    document.getElementById('boot').remove();
  };
})();
