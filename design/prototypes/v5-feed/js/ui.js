/* v5-feed UI primitives: sheets/dialogs (wired to the back gesture), menus, confirm, forms, toast, AI job chip, controls. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc;
  const host = document.getElementById('layers');
  const layers = [];
  let ignorePop = 0, afterPop = null, popTimer = null;

  function finishPop() { clearTimeout(popTimer); const f = afterPop; afterPop = null; ignorePop = 0; if (f) f(); }
  window.addEventListener('popstate', () => {
    if (ignorePop > 0) { finishPop(); return; }
    if (layers.length) dismiss(layers[layers.length - 1], true);
  });

  function mount(el, opts = {}) {
    host.appendChild(el);
    const L = { el, onClose: opts.onClose, done: false };
    layers.push(L);
    try { history.pushState({ v5layer: layers.length }, '', location.href); } catch (e) { /* ignore */ }
    requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add('open')));
    el.addEventListener('click', (e) => { if (e.target.classList.contains('scrim') || e.target.closest('[data-dismiss]')) close(L); });
    return L;
  }
  function dismiss(L, viaPop, then) {
    if (L.done) return; L.done = true;
    const i = layers.indexOf(L); if (i >= 0) layers.splice(i, 1);
    L.el.classList.remove('open'); L.el.classList.add('closing');
    setTimeout(() => L.el.remove(), 340);
    const after = () => { if (L.onClose) L.onClose(); if (then) then(); };
    if (viaPop) after();
    else { afterPop = after; ignorePop = 1; history.back(); clearTimeout(popTimer); popTimer = setTimeout(finishPop, 700); }
  }
  function close(L, then) { dismiss(L, false, then); }
  A.layerClose = close;
  // Close every layer. silent=true: just drop DOM (route already changed). Otherwise unwind history, then run `then`.
  A.closeLayers = (arg) => {
    if (arg === true) { layers.splice(0).forEach((L) => { L.done = true; L.el.remove(); }); return; }
    const then = typeof arg === 'function' ? arg : null;
    if (!layers.length) { if (then) then(); return; }
    const n = layers.length;
    const closed = layers.splice(0);
    closed.forEach((L) => { L.done = true; L.el.classList.remove('open'); L.el.classList.add('closing'); setTimeout(() => L.el.remove(), 340); });
    afterPop = () => { closed.forEach((L) => L.onClose && L.onClose()); if (then) then(); };
    ignorePop = 1; history.go(-n); clearTimeout(popTimer); popTimer = setTimeout(finishPop, 700);
  };

  // ---------- bottom sheet ----------
  A.sheet = ({ title = '', sub = '', html = '', cls = '', actions = '', onMount, onClose, tint } = {}) => {
    const el = W.el(`<div class="layer lay-sheet ${cls}"><div class="scrim"></div><div class="sheet" role="dialog" aria-label="${esc(title)}">
      <div class="grab"><i></i></div>
      ${title ? `<div class="sheet-h"><div><h3>${esc(title)}</h3>${sub ? `<p>${esc(sub)}</p>` : ''}</div>${actions}</div>` : ''}
      <div class="sheet-b">${html}</div></div></div>`);
    if (tint) A.tint(el, tint);
    const L = mount(el, { onClose });
    // drag-to-dismiss from the grab area / header
    const sh = el.querySelector('.sheet');
    let y0 = null, dy = 0;
    sh.addEventListener('pointerdown', (e) => { if (!e.target.closest('.grab,.sheet-h')) return; y0 = e.clientY; dy = 0; sh.style.transition = 'none'; sh.setPointerCapture(e.pointerId); });
    sh.addEventListener('pointermove', (e) => { if (y0 == null) return; dy = Math.max(0, e.clientY - y0); sh.style.transform = `translateY(${dy}px)`; });
    const end = () => { if (y0 == null) return; y0 = null; sh.style.transition = ''; sh.style.transform = ''; if (dy > 90) close(L); };
    sh.addEventListener('pointerup', end); sh.addEventListener('pointercancel', end);
    const api = { el, body: el.querySelector('.sheet-b'), close: (then) => close(L, then), set: (h) => { el.querySelector('.sheet-b').innerHTML = h; } };
    if (onMount) onMount(api);
    return api;
  };

  // ---------- menu ----------
  A.menu = ({ title, sub, items, tint }) => {
    let html = '<div class="mlist">';
    items.forEach((it, i) => {
      if (it.section) { html += `<div class="msec">${esc(it.section)}</div>`; return; }
      if (it.divider) { html += '<hr class="mdiv">'; return; }
      html += `<button class="mi ${it.danger ? 'danger' : ''} ${it.on ? 'on' : ''}" data-i="${i}" ${it.disabled ? 'disabled' : ''}>${A.ic(it.icon || 'chevron_right')}<span><b>${esc(it.label)}</b>${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</span>${it.on ? A.ic('check', 'mi-chk') : ''}</button>`;
    });
    html += '</div>';
    const s = A.sheet({ title, sub, html, tint, cls: 'menu-sheet' });
    s.el.addEventListener('click', (e) => {
      const b = e.target.closest('.mi'); if (!b) return;
      const it = items[+b.dataset.i];
      if (it.keep) { it.fn && it.fn(b); return; }
      s.close(() => it.fn && it.fn());
    });
    return s;
  };

  // ---------- dialog / confirm / form ----------
  A.dialog = ({ title, body = '', html = '', buttons = [], onMount, tint }) => new Promise((res) => {
    const el = W.el(`<div class="layer lay-dlg"><div class="scrim"></div><div class="dlg" role="dialog"><h3>${esc(title)}</h3>${body ? `<p class="dlg-p">${esc(body)}</p>` : ''}${html}<div class="dlg-a">${buttons.map((b, i) => `<button class="btn ${b.cls || 'ghost'}" data-b="${i}">${esc(b.label)}</button>`).join('')}</div></div></div>`);
    if (tint) A.tint(el, tint);
    let val;
    const L = mount(el, { onClose: () => res(val) });
    if (onMount) onMount(el);
    el.addEventListener('click', (e) => { const b = e.target.closest('[data-b]'); if (!b) return; const bt = buttons[+b.dataset.b]; val = bt.value != null ? (typeof bt.value === 'function' ? bt.value(el) : bt.value) : null; close(L); });
  });
  A.confirm = ({ title, body, ok = 'OK', danger = false }) => A.dialog({ title, body, buttons: [{ label: 'Cancel', value: false }, { label: ok, cls: danger ? 'danger' : 'primary', value: true }] });
  A.form = ({ title, body, fields, ok = 'Save' }) => A.dialog({
    title, body,
    html: `<div class="dlg-f">${fields.map((f) => `<label class="field"><span>${esc(f.label)}</span><input data-k="${f.key}" type="${f.type || 'text'}" value="${esc(f.value || '')}" placeholder="${esc(f.placeholder || '')}" autocomplete="off"></label>`).join('')}</div>`,
    buttons: [{ label: 'Cancel', value: null }, { label: ok, cls: 'primary', value: (el) => Object.fromEntries([...el.querySelectorAll('input[data-k]')].map((i) => [i.dataset.k, i.value])) }],
    onMount: (el) => setTimeout(() => { const i = el.querySelector('input'); if (i) i.focus(); }, 280),
  });

  // ---------- toast ----------
  let toastT = null;
  A.toast = (msg, { action, fn, icon } = {}) => {
    const t = document.getElementById('toasts');
    t.innerHTML = `<div class="toast glass">${icon ? A.ic(icon) : ''}<span>${esc(msg)}</span>${action ? `<button class="tbtn">${esc(action)}</button>` : ''}</div>`;
    const el = t.firstElementChild;
    requestAnimationFrame(() => el.classList.add('in'));
    if (action) el.querySelector('.tbtn').onclick = () => { el.classList.remove('in'); fn && fn(); };
    clearTimeout(toastT); toastT = setTimeout(() => el.classList.remove('in'), 3400);
  };

  // ---------- controls ----------
  A.sw = (on, attrs = '') => `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${!!on}" ${attrs}><i></i></button>`;
  A.chips = (opts, val, attr = 'data-v', cls = '') => `<div class="chips ${cls}">${opts.map((o) => { const [v, l] = Array.isArray(o) ? o : [o, o]; return `<button class="chip ${String(v) === String(val) ? 'on' : ''}" ${attr}="${esc(v)}">${l}</button>`; }).join('')}</div>`;
  A.seg = (opts, val, attr = 'data-v') => `<div class="seg">${opts.map(([v, l]) => `<button class="${String(v) === String(val) ? 'on' : ''}" ${attr}="${esc(v)}">${l}</button>`).join('')}</div>`;
  A.range = (v, min, max, step, attrs = '') => `<input type="range" class="rng" min="${min}" max="${max}" step="${step}" value="${v}" style="--p:${((v - min) / (max - min)) * 100}%" ${attrs}>`;
  document.addEventListener('input', (e) => { if (e.target.classList && e.target.classList.contains('rng')) { const r = e.target; r.style.setProperty('--p', ((r.value - r.min) / (r.max - r.min)) * 100 + '%'); } });
  document.addEventListener('click', (e) => { const s = e.target.closest('.sw'); if (s && !s.hasAttribute('data-manual')) { s.classList.toggle('on'); s.setAttribute('aria-checked', s.classList.contains('on')); s.dispatchEvent(new CustomEvent('sw', { bubbles: true, detail: s.classList.contains('on') })); } });

  // Shared page header for secondary screens (glass bar that solidifies on scroll).
  A.topbar = ({ title, sub = '', actions = '', back = '/home', big = true }) => `
    <header class="tb"><button class="ibtn glassbtn" data-back="${back}" aria-label="Back">${A.ic('arrow_back')}</button>
      <div class="tb-t"><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</div><div class="tb-a">${actions}</div></header>
    ${big ? `<div class="pagehead"><h1>${esc(title)}</h1>${sub ? `<p>${esc(sub)}</p>` : ''}</div>` : ''}`;
  A.bindTopbar = (ctx) => {
    const sc = ctx.el.querySelector('.sc'), tb = ctx.el.querySelector('.tb');
    if (!sc || !tb) return;
    const f = () => tb.classList.toggle('solid', sc.scrollTop > 40);
    sc.addEventListener('scroll', f, { passive: true }); f();
  };
  A.empty = (icon, title, body, btn = '') => `<div class="empty">${A.ic(icon)}<h3>${esc(title)}</h3><p>${esc(body)}</p>${btn}</div>`;

  // ---------- AI job chip (global, floating) ----------
  let aiStory = null, aiHidden = false;
  W.ai.on((st) => {
    const chip = document.getElementById('aichip');
    if (st.job) {
      if (aiStory !== st.job.storyId) aiHidden = false;
      aiStory = st.job.storyId;
      if (aiHidden) return;
      const s = W.story(aiStory);
      chip.innerHTML = `<div class="aichip glass in"><span class="spin"></span><button class="aichip-b" data-ai="${aiStory}"><b>${esc(st.job.message)}</b><small>${esc(A.short(s.title))}</small></button><button class="ibtn sm" data-x>${A.ic('close')}</button></div>`;
    } else if (aiStory) {
      const s = W.story(aiStory), id = aiStory; aiStory = null;
      if (aiHidden) { chip.innerHTML = ''; A.toast(st.pending ? 'New cover ready' : 'Description draft ready', { icon: 'auto_awesome', action: 'View', fn: () => A.go('/story/' + id + '/ai') }); return; }
      chip.innerHTML = `<div class="aichip glass in done">${A.ic('auto_awesome', 'f')}<button class="aichip-b" data-ai="${id}"><b>${st.pending ? 'New cover ready' : 'Description draft ready'}</b><small>${esc(A.short(s.title))} · tap to review</small></button><button class="ibtn sm" data-x>${A.ic('close')}</button></div>`;
      setTimeout(() => { const c = chip.firstElementChild; if (c && c.classList.contains('done')) { c.classList.remove('in'); setTimeout(() => (chip.innerHTML = ''), 300); } }, 5000);
    }
  });
  document.getElementById('aichip').addEventListener('click', (e) => {
    if (e.target.closest('[data-x]')) { aiHidden = true; e.currentTarget.innerHTML = ''; return; }
    const b = e.target.closest('[data-ai]'); if (b) { e.currentTarget.innerHTML = ''; if (!(A.current && A.current.name === 'ai')) A.go('/story/' + b.dataset.ai + '/ai'); }
  });
})();
