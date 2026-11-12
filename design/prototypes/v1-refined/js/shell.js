/* v1 Refined — shell: router, screen stack + transitions, global mini player, AI job chip. Loaded last. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;
  const app = document.getElementById('app');
  const chrome = document.getElementById('chrome');

  // ---------- screen stack ----------
  let current = null; // {path, name, inst}
  const cache = {};   // name -> inst for screens with keep:true (Library)
  const trail = [];   // visited paths, to detect back navigation

  const ALIAS = { settingsPage: (r) => 'settings_' + r.params.page };

  function resolve(r) {
    const key = ALIAS[r.name] ? ALIAS[r.name](r) : r.name;
    return V.screens[key] ? key : (r.name === 'settingsPage' ? 'settings' : V.screens[r.name] ? r.name : 'library');
  }

  function onRoute(r) {
    V.closeOverlays();
    const name = resolve(r);
    const full = r.path + (Object.keys(r.query).length ? '?' + new URLSearchParams(r.query) : '');
    if (current && current.full === full) return;
    const isBack = trail.length > 1 && trail[trail.length - 2] === full;
    if (isBack) trail.pop(); else trail.push(full);
    if (trail.length > 40) trail.shift();

    const prev = current;
    let inst;
    if (cache[name] && cache[name].key === full) inst = cache[name];
    else {
      try { inst = V.screens[name](r); } catch (e) { console.error(e); inst = { el: WNA.el(`<div class="screen">${V.appBar({ title: 'Something went wrong' })}${V.empty({ icon: 'error', title: 'This screen failed to load', body: String(e.message) })}</div>`) }; }
      inst.key = full;
      if (inst.keep) cache[name] = inst;
    }
    current = { full, name, inst };

    const el = inst.el;
    el.classList.remove('leave', 'leave-back', 'enter', 'enter-back');
    app.appendChild(el);
    if (inst.scrollTop != null) el.scrollTop = inst.scrollTop;
    if (prev) {
      const pel = prev.inst.el;
      prev.inst.scrollTop = pel.scrollTop;
      el.classList.add(isBack ? 'enter-back' : 'enter');
      pel.classList.add(isBack ? 'leave-back' : 'leave');
      setTimeout(() => {
        if (pel.parentNode && current.inst !== prev.inst) pel.remove();
        if (!prev.inst.keep || cache[prev.name] !== prev.inst) prev.inst.destroy && prev.inst.destroy();
      }, 240);
      setTimeout(() => el.classList.remove('enter', 'enter-back'), 340);
    }
    if (inst.onShow) inst.onShow(r);
    document.body.classList.toggle('fab-on', !!inst.fab);
    document.title = `${inst.title || 'Webnovel Archiver'} · v1 Refined`;
    renderMini();
    renderAiChip();
  }

  // ---------- mini player ----------
  let miniEl = null;
  const hideMiniOn = new Set(['player', 'reader']);
  function miniHtml(st, docked) {
    const s = st.story;
    const left = st.buffering ? 'Loading voice…' : `${fmt.dur(st.remainingSec)} left`;
    return `
      <button class="play" data-mini="toggle" aria-label="${st.playing ? 'Pause' : 'Play'}">${st.buffering ? '<span class="spin" style="width:22px;height:22px"></span>' : V.ic(st.playing ? 'pause' : 'play_arrow', 'fill')}</button>
      <button class="mt" data-mini="open">
        <b>${esc(st.chapter ? st.chapter.title : '')}</b>
        <span>${left} · ${esc(s.title)}</span>
      </button>
      ${docked ? V.ib('skip_next', 'mini-next', 'Next chapter', { cls: 'sm' }).replace('data-act="mini-next"', 'data-mini="next"') : ''}
      <button class="ib sm" data-mini="open" aria-label="Open player">${V.ic('keyboard_arrow_up')}</button>
      <button class="ib sm" data-mini="close" aria-label="Close player">${V.ic('close')}</button>
      <div class="mprog"><i style="width:${V.pct(st.progress)}"></i></div>`;
  }
  V.miniHtml = miniHtml;
  V.miniAction = (a) => {
    if (a === 'toggle') WNA.player.toggle();
    else if (a === 'open') WNA.go('/player');
    else if (a === 'next') WNA.player.nextChapter();
    else if (a === 'close') { WNA.player.stop(); V.toast('Playback stopped'); }
  };
  function renderMini() {
    const st = WNA.player.state();
    const show = st.loaded && current && !hideMiniOn.has(current.name) && !(current.inst.noMini);
    document.body.classList.toggle('has-mini', !!show);
    if (!show) {
      if (miniEl) { const m = miniEl; miniEl = null; m.classList.add('out'); setTimeout(() => m.remove(), 220); }
      return;
    }
    if (!miniEl) {
      miniEl = WNA.el('<div class="mini" role="region" aria-label="Now playing"></div>');
      miniEl.addEventListener('click', (e) => { const t = e.target.closest('[data-mini]'); if (t) V.miniAction(t.dataset.mini); });
      chrome.appendChild(miniEl);
    }
    miniEl.innerHTML = miniHtml(st, false);
  }
  WNA.player.on(() => renderMini());

  // ---------- AI job chip ----------
  let aiJob = null, aiDismissed = false, chipEl = null;
  V.aiWatch = (id) => { aiDismissed = false; const st = WNA.ai.state(id); aiJob = st.job ? { ...st.job, storyId: id } : null; renderAiChip(); };
  WNA.ai.on((st) => {
    const prevJob = aiJob;
    aiJob = st.job ? { ...st.job } : null;
    if (prevJob && !aiJob) {
      const sid = prevJob.storyId;
      if (!current || current.name !== 'ai') V.toast(prevJob.kind === 'cover' ? 'New cover ready' : 'Description draft ready', { action: 'View', onAction: () => WNA.go(`/story/${sid}/ai`) });
      aiDismissed = false;
    }
    renderAiChip();
  });
  function renderAiChip() {
    const show = aiJob && !aiDismissed && current && current.name !== 'ai';
    if (!show) { if (chipEl) { chipEl.remove(); chipEl = null; } return; }
    const s = WNA.story(aiJob.storyId);
    if (!chipEl) {
      chipEl = WNA.el('<div class="aichip"></div>');
      chipEl.addEventListener('click', (e) => {
        if (e.target.closest('[data-x]')) { aiDismissed = true; renderAiChip(); return; }
        WNA.go(`/story/${aiJob.storyId}/ai`);
      });
      chrome.appendChild(chipEl);
    }
    chipEl.innerHTML = `<span class="spin"></span><div class="col" style="min-width:0"><b class="ell">${esc(aiJob.message)}</b><span class="ell">${esc(s ? s.title : '')}</span></div><button class="ib" data-x aria-label="Hide">${V.ic('close')}</button>`;
  }

  // Android-ish back: Escape closes overlays, else navigates back
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (V.hasOverlay()) V.closeOverlays(); else WNA.back();
  });

  WNA.router(onRoute);
})();
