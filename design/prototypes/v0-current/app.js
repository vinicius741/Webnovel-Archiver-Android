/* v0-current — core shell: screen registry, app bar, dialogs, toasts, mini player, shared bits. */
(function () {
  const W = window.WNA;
  const esc = W.esc;
  const screens = {};
  const scrollMem = {};
  let current = null; // {route, el, destroy, subs, mini}

  const icon = (name, o) => `<span class="ms${o ? ' o' : ''}" aria-hidden="true">${name}</span>`;

  function appBar({ title, sub, back = true, actions = [] }) {
    return `<header class="appbar">
      ${back ? `<button class="icon-btn ab-back" data-act="back" aria-label="Back">${icon('arrow_back')}</button>` : ''}
      <div class="ab-title"><h1>${esc(title)}</h1>${sub ? `<div class="ab-sub">${esc(sub)}</div>` : ''}</div>
      ${actions.map((a) => `<button class="icon-btn${a.gold ? ' gold' : ''}" data-a="${a.id}" aria-label="${esc(a.label)}" title="${esc(a.label)}">${icon(a.icon, a.o)}</button>`).join('')}
    </header>`;
  }

  // ---------- screen lifecycle ----------
  function register(name, fn) { screens[name] = fn; }
  function show(route) {
    if (current) {
      const sc = current.el.querySelector('.scroll[data-keep]');
      if (sc) scrollMem[current.route.path] = sc.scrollTop;
      current.subs.forEach((u) => { try { u(); } catch (e) { /* ignore */ } });
      if (current.destroy) current.destroy();
      current.el.remove();
    }
    closeAllDialogs();
    const fn = screens[route.name] || screens.library;
    const ctx = { route, subs: [], sub(u) { this.subs.push(u); return u; } };
    const res = fn(route, ctx) || {};
    const el = res.el;
    el.classList.add('screen');
    document.getElementById('app').appendChild(el);
    current = { route, el, destroy: res.destroy, subs: ctx.subs, mini: res.mini !== false };
    W.on(el, 'click', '[data-act="back"]', () => W.back());
    const sc = el.querySelector('.scroll[data-keep]');
    if (sc && scrollMem[route.path] != null) { const y = scrollMem[route.path]; requestAnimationFrame(() => (sc.scrollTop = y)); }
    if (res.mounted) res.mounted();
    renderMini();
  }

  // ---------- dialogs ----------
  const layer = () => document.getElementById('layer');
  function dialog({ title, body = '', actions = [], wide = false, onMount, dismissable = true }) {
    const scrim = W.el(`<div class="scrim"><div class="dialog${wide ? ' wide' : ''}" role="dialog">
      ${title ? `<div class="dg-title">${esc(title)}</div>` : ''}
      <div class="dg-body">${body}</div>
      ${actions.length ? `<div class="dg-actions">${actions.map((a, i) => `<button class="btn text${a.danger ? ' danger' : ''}" data-i="${i}">${esc(a.label)}</button>`).join('')}</div>` : ''}
    </div></div>`);
    const close = () => { scrim.remove(); };
    scrim.addEventListener('click', (e) => { if (e.target === scrim && dismissable) close(); });
    scrim.querySelectorAll('.dg-actions [data-i]').forEach((b) => b.addEventListener('click', () => { const a = actions[+b.dataset.i]; if (a.keep) { a.fn && a.fn(scrim, close); return; } close(); a.fn && a.fn(scrim); }));
    layer().appendChild(scrim);
    if (onMount) onMount(scrim.querySelector('.dialog'), close);
    return { el: scrim, close };
  }
  function closeAllDialogs() { layer().innerHTML = ''; }
  // "More options" style list dialog: sections [{label, items:[{label, sub, danger, fn, divider}]}]
  function menu({ title = 'More options', sections, wide = true }) {
    let k = 0; const fns = [];
    const body = sections.map((s) => `${s.divider ? '<div class="dg-div"></div>' : ''}${s.label ? `<div class="dg-sec">${esc(s.label)}</div>` : ''}${s.items.map((it) => { fns.push(it.fn); return `<button class="dg-item${it.danger ? ' danger' : ''}" data-k="${k++}">${it.icon ? icon(it.icon) : ''}<div>${esc(it.label)}${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</div></button>`; }).join('')}`).join('');
    const d = dialog({ title, body, wide, actions: [{ label: 'Cancel' }] });
    d.el.querySelectorAll('[data-k]').forEach((b) => b.addEventListener('click', () => { d.close(); const f = fns[+b.dataset.k]; f && f(); }));
    return d;
  }
  function confirm({ title, message, ok = 'OK', cancel = 'Cancel', danger = false, fn }) {
    return dialog({ title, body: `<p style="margin:0">${message}</p>`, actions: [{ label: cancel }, { label: ok, danger, fn }] });
  }
  // single-choice dialog with radio rows
  function choose({ title, options, value, fn }) {
    const body = options.map((o, i) => `<button class="dg-item" data-i="${i}"><span class="radio${o.value === value ? ' on' : ''}"></span><div>${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ''}</div></button>`).join('');
    const d = dialog({ title, body, actions: [{ label: 'Cancel' }] });
    d.el.querySelectorAll('.dg-item').forEach((b) => b.addEventListener('click', () => { d.close(); fn(options[+b.dataset.i].value); }));
    return d;
  }
  function prompt({ title, label, value = '', ok = 'Save', fn, multiline = false }) {
    const body = `<div class="field-label">${esc(label || '')}</div><div class="field${multiline ? ' area' : ''}">${multiline ? `<textarea>${esc(value)}</textarea>` : `<input value="${esc(value)}">`}</div>`;
    return dialog({ title, body, actions: [{ label: 'Cancel' }, { label: ok, fn: (s) => fn(s.querySelector('input,textarea').value) }], onMount: (dg) => { const i = dg.querySelector('input,textarea'); setTimeout(() => i.focus(), 50); } });
  }

  // ---------- toast ----------
  let toastTimer;
  function toast(msg) {
    const host = document.getElementById('toast-host');
    host.innerHTML = `<div class="toast">${esc(msg)}</div>`;
    host.style.bottom = (current && current.mini && W.player.state().loaded && current.route.name !== 'reader' ? 120 : 48) + 'px';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { const t = host.firstChild; if (t) { t.style.opacity = 0; setTimeout(() => t.remove(), 300); } }, 2400);
  }

  // ---------- mini player ----------
  function miniHtml(st, docked) {
    const ch = st.chapter;
    const title = ch ? ch.title : '';
    const left = `${W.fmt.dur(st.remainingSec).replace(/^(\d+) s$/, '1 min')} left`;
    return `<div class="mini${docked ? ' docked' : ''}" data-mini>
      <div class="mrow">
        <button class="icon-btn mplay" data-mini-act="toggle" aria-label="${st.playing ? 'Pause' : 'Play'}">${st.buffering ? '<span class="spinner"></span>' : icon(st.playing ? 'pause' : 'play_arrow')}</button>
        <div class="mtext" data-mini-act="open"><b>${esc(title)}</b><span>${esc(left)} · ${esc(st.story.title)}</span></div>
        <button class="icon-btn" data-mini-act="open" aria-label="Open player">${icon('keyboard_arrow_up')}</button>
        <button class="icon-btn" data-mini-act="close" aria-label="Close player">${icon('close')}</button>
      </div>
      <div class="mprog"><i style="width:${(st.progress * 100).toFixed(1)}%"></i></div>
    </div>`;
  }
  function bindMini(root) {
    W.on(root, 'click', '[data-mini-act]', (e, t) => {
      const a = t.dataset.miniAct;
      if (a === 'toggle') W.player.toggle();
      else if (a === 'open') W.go('/player');
      else if (a === 'close') { W.player.stop(); }
    });
  }
  let aiJob = null;
  function renderMini() {
    const host = document.getElementById('mini-host');
    const st = W.player.state();
    const name = current && current.route.name;
    const showMini = current && current.mini && st.loaded;
    const showAi = aiJob && !(name === 'ai' && current.route.params.id === aiJob.storyId);
    const aiHtml = showAi ? `<div class="ai-chip"><span class="spinner" style="width:16px;height:16px;border-width:2px"></span><span>${esc(aiJob.message)} · ${esc((W.story(aiJob.storyId) || {}).title || '')}</span><button class="icon-btn" data-ai="close" aria-label="Dismiss">${icon('close')}</button></div>` : '';
    host.innerHTML = aiHtml + (showMini ? miniHtml(st, false) : '');
    const chip = host.querySelector('.ai-chip');
    if (chip) chip.addEventListener('click', (e) => { if (e.target.closest('[data-ai]')) { aiJob = { ...aiJob, hidden: true }; host.querySelector('.ai-chip').remove(); return; } W.go('/story/' + aiJob.storyId + '/ai'); });
    if (aiJob && aiJob.hidden && chip) chip.remove();
    document.body.classList.toggle('has-mini', !!showMini);
  }
  W.player.on(() => renderMini());
  W.ai.on((s) => {
    if (s.job) { if (!aiJob || aiJob.message !== s.job.message) aiJob = { ...s.job, hidden: aiJob && aiJob.hidden }; }
    else if (aiJob) { const done = aiJob; aiJob = null; toast(done.kind === 'cover' ? 'New cover ready' : 'Description draft ready'); }
    renderMini();
  });

  // ---------- shared visuals ----------
  function cover(s, cls = '', attrs = '') {
    const url = W.coverUrl(s);
    if (url) return `<div class="cover ${cls}" ${attrs}><img src="${url}" alt="" loading="lazy" decoding="async"></div>`;
    return `<div class="cover fallback ${cls}" ${attrs} style="background:${W.coverFallback(s)}"><span>${esc(s.title)}</span></div>`;
  }
  function capsule(s) {
    const c = W.coverage(s);
    return `<div class="capsule">${c.segments.map(([a, b]) => `<i class="${b >= 0.999 ? 'end' : ''}" style="left:${(a * 100).toFixed(2)}%;width:${((b - a) * 100).toFixed(2)}%"></i>`).join('')}${c.bookmark != null ? `<span class="pin" style="left:${(c.bookmark * 100).toFixed(2)}%"></span>` : ''}</div>`;
  }
  const PUB = { ongoing: 'Ongoing', completed: 'Completed', hiatus: 'Hiatus', outdated: 'Outdated' };
  const score = (s) => { const v = parseFloat(s.score); return isNaN(v) ? '' : v.toFixed(2); };
  // The app shows the real monthly amount when known, otherwise an estimate (~$9 per paid member).
  function patreonLine(s) { const p = s.patreon; if (!p || !p.members) return ''; return `Patreon ${p.usd ? '$' + p.usd.toLocaleString('en-US') : '~$' + (p.members * 9).toLocaleString('en-US')}/mo · ${p.members.toLocaleString('en-US')} paid`; }
  function pubPill(s) { return PUB[s.pub] ? `<span class="pill status ${s.pub}">${PUB[s.pub]}</span>` : ''; }

  // pointer long-press: calls onLong(target) after 450 ms; suppresses the following click.
  function longPress(root, selector, onLong) {
    let timer = null, start = null, fired = false;
    root.addEventListener('pointerdown', (e) => {
      const t = e.target.closest(selector); if (!t) return;
      fired = false; start = { x: e.clientX, y: e.clientY };
      timer = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(20); onLong(t); }, 450);
    });
    const cancel = () => { clearTimeout(timer); timer = null; };
    root.addEventListener('pointermove', (e) => { if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 10) cancel(); });
    root.addEventListener('pointerup', () => { cancel(); if (fired) setTimeout(() => (fired = false), 400); }); root.addEventListener('pointercancel', cancel);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(selector)) e.preventDefault(); });
    root.addEventListener('click', (e) => { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
  }

  const isWide = () => window.innerWidth >= 600;

  window.App = { icon, appBar, register, show, dialog, menu, confirm, choose, prompt, toast, cover, capsule, pubPill, score, patreonLine, longPress, isWide, renderMini, miniHtml, bindMini, current: () => current, esc };
  bindMini(document.getElementById('mini-host'));
})();
