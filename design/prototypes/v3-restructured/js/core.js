/* v3 Restructured — core helpers shared by every screen (window.APP). */
(function () {
  const W = window.WNA;
  if (!W) return;
  const { esc, fmt } = W;
  const A = (window.APP = {});
  A.screens = {};
  A.state = {
    hidden: new Set(), // deleted stories
    resumeDismissed: false,
    libView: 'list',
    libTab: (W.data.prefs && W.data.prefs.libraryTabId) || 'all',
    bookmarks: {}, // storyId -> chapter index (0-based) override
    epub: {}, // storyId -> true when generated
    syncing: {}, // storyId -> true
    synced: {}, // storyId -> ts
    patreonLinked: {},
    covers: {}, // storyId -> chosen AI cover url
  };

  A.icon = (n, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${n}</span>`;
  A.short = (t, n = 34) => { const s = String(t || '').split(/\s[[(]/)[0].trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
  A.stories = () => W.stories().filter((s) => !A.state.hidden.has(s.id));
  A.bookmark = (s) => (A.state.bookmarks[s.id] !== undefined ? A.state.bookmarks[s.id] : s.bookmark);

  A.coverHtml = (s, cls = '', style = '', attrs = '') => {
    const url = A.state.covers[s.id] || W.coverUrl(s);
    if (url) return `<div class="cover ${cls}" style="${style}" ${attrs}><img class="bk" src="${url}" alt="" aria-hidden="true" loading="lazy" decoding="async"><img class="art" src="${url}" alt="" loading="lazy" decoding="async"></div>`;
    return `<div class="cover fb ${cls}" style="background:${W.coverFallback(s)};${style}"><span>${esc(s.title)}</span></div>`;
  };
  // ---------- full-screen cover viewer ----------
  // Shows covers whole and as large as the screen allows. items: [{ url, label, sub, prompt, story }], start index.
  // actions(item) -> [{ id, label, icon, fill }]; onAction(id, item, close). Swipe or use the arrows to move between items.
  A.viewCovers = ({ items, index = 0, title = '', actions = () => [], onAction = () => {} }) => {
    let i = Math.max(0, Math.min(items.length - 1, index));
    const el = W.el(`<div class="cviewer" role="dialog" aria-label="Cover"><div class="cv-bg"></div>
      <header class="cv-top"><button class="ib" data-cv="close" aria-label="Close">${A.icon('close')}</button><div class="grow cv-ttl"></div><span class="cv-count"></span></header>
      <div class="cv-stage"><button class="ib cv-nav prev" data-cv="prev" aria-label="Previous">${A.icon('chevron_left', 'lg')}</button><img class="cv-img" alt=""><button class="ib cv-nav next" data-cv="next" aria-label="Next">${A.icon('chevron_right', 'lg')}</button></div>
      <div class="cv-dots"></div><div class="cv-prompt"></div><footer class="cv-acts"></footer></div>`);
    const $ = (q) => el.querySelector(q);
    const paint = () => {
      const it = items[i];
      $('.cv-img').src = it.url; $('.cv-bg').style.backgroundImage = `url('${it.url}')`;
      $('.cv-ttl').innerHTML = `<b>${esc(it.label || title)}</b>${it.sub ? `<div class="muted">${esc(it.sub)}</div>` : ''}`;
      $('.cv-count').textContent = items.length > 1 ? `${i + 1} / ${items.length}` : '';
      $('.cv-nav.prev').hidden = i === 0; $('.cv-nav.next').hidden = i === items.length - 1;
      $('.cv-dots').innerHTML = items.length > 1 ? items.map((_, k) => `<i class="${k === i ? 'on' : ''}"></i>`).join('') : '';
      $('.cv-prompt').innerHTML = it.prompt ? `<details><summary>${A.icon('notes', 'xs')}Prompt</summary><p>${esc(it.prompt)}</p></details>` : '';
      const acts = actions(it) || [];
      $('.cv-acts').innerHTML = acts.map((a) => `<button class="btn ${a.fill ? 'fill' : 'surface'} grow" data-cva="${a.id}" ${a.disabled ? 'disabled' : ''}>${a.icon ? A.icon(a.icon, 'sm') : ''}${esc(a.label)}</button>`).join('');
      $('.cv-acts').hidden = !acts.length;
    };
    const go = (d) => { const n = i + d; if (n < 0 || n >= items.length) return; i = n; const img = $('.cv-img'); img.classList.remove('in'); void img.offsetWidth; img.classList.add('in'); paint(); };
    const close = () => { el.classList.add('out'); A.sheetStack = A.sheetStack.filter((x) => x !== close); setTimeout(() => el.remove(), 180); };
    W.on(el, 'click', '[data-cv]', (e, b) => { const a = b.dataset.cv; if (a === 'close') close(); else go(a === 'next' ? 1 : -1); });
    W.on(el, 'click', '[data-cva]', (e, b) => onAction(b.dataset.cva, items[i], close, (next) => { if (next) Object.assign(items[i], next); paint(); }));
    // swipe sideways to move between covers, swipe down to close
    let sx = null, sy = 0;
    const stage = $('.cv-stage');
    stage.addEventListener('pointerdown', (e) => { if (e.target.closest('button')) return; sx = e.clientX; sy = e.clientY; });
    stage.addEventListener('pointerup', (e) => { if (sx == null) return; const dx = e.clientX - sx, dy = e.clientY - sy; sx = null; if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) go(dx < 0 ? 1 : -1); else if (dy > 90) close(); });
    paint();
    document.getElementById('layer').appendChild(el);
    A.sheetStack.push(close);
    return close;
  };
  // Opens a story's current cover in the viewer.
  A.viewStoryCover = (s, extra = {}) => {
    const url = A.state.covers[s.id] || W.coverUrl(s);
    if (!url) return A.toast('This novel has no cover image');
    A.viewCovers({ items: [{ url, label: s.title, sub: s.author || '' }], ...extra });
  };
  // Covers marked data-zoom="<storyId>" open the viewer instead of the row they sit in.
  document.addEventListener('click', (e) => {
    const z = e.target.closest('[data-zoom]'); if (!z) return;
    const s = W.story(z.dataset.zoom); if (!s) return;
    e.preventDefault(); e.stopPropagation();
    A.viewStoryCover(s, { actions: () => [{ id: 'open', label: 'Open novel', icon: 'menu_book' }], onAction: (a, it, close) => { close(); W.go('/story/' + s.id); } });
  }, true);

  A.cov = (s, sm) => {
    const c = W.coverage(s);
    const b = A.bookmark(s);
    const list = W.chapters(s.id);
    const pin = b != null && list.length ? (b + 0.5) / list.length : null;
    return `<div class="cov ${sm ? 'sm' : ''}">${c.segments.map(([a, z]) => `<i class="seg-d" style="left:${(a * 100).toFixed(2)}%;width:${((z - a) * 100).toFixed(2)}%"></i>`).join('')}${pin != null ? `<i class="pin" style="left:${(pin * 100).toFixed(2)}%"></i>` : ''}</div>`;
  };
  A.pub = (s) => {
    const m = { ongoing: ['Ongoing', 'gold'], completed: ['Completed', 'sage'], hiatus: ['Hiatus', 'copper'], outdated: ['Outdated', 'red'] }[s.pub];
    return m ? `<span class="pillb ${m[1]}">${m[0]}</span>` : '';
  };
  A.prog = (f, cls = '') => `<div class="prog ${cls}"><i style="width:${Math.round(Math.max(0, Math.min(1, f)) * 100)}%"></i></div>`;
  A.sw = (on, attrs = '') => `<span class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${!!on}" ${attrs}></span>`;
  A.rangeFill = (inp) => { const min = +inp.min || 0, max = +inp.max || 100; inp.style.setProperty('--p', ((inp.value - min) / (max - min)) * 100 + '%'); };

  // ---------- stepper: − value + ----------
  // Replaces sliders for numeric settings. Tap ± to nudge, hold ± to repeat (speeds up), tap the value to type an exact number.
  // o: { step, min, max, unit = '', dp = 0, label, zero } — `zero` is the text shown at 0 (e.g. "Off").
  const stepFmt = (v, o) => (o.zero && +v === 0 ? o.zero : (+v).toFixed(o.dp || 0) + (o.unit || ''));
  A.stepper = (key, value, o) => `<div class="stepper" data-stp="${key}" data-v="${value}" data-o='${esc(JSON.stringify(o))}'>
      <button class="ib" data-sd="-1" aria-label="Decrease ${esc(o.label || key)}" ${value <= o.min ? 'disabled' : ''}>${A.icon('remove')}</button>
      <button class="stv" data-sv aria-label="Type an exact ${esc(o.label || key)}">${stepFmt(value, o)}</button>
      <button class="ib" data-sd="1" aria-label="Increase ${esc(o.label || key)}" ${value >= o.max ? 'disabled' : ''}>${A.icon('add')}</button>
    </div>`;
  // A settings row: label + hint on the left, stepper on the right.
  A.stepRow = (key, value, o, label, sub = '') => `<div class="li steprow"><div class="grow"><div class="t">${label}</div>${sub ? `<div class="s">${sub}</div>` : ''}</div>${A.stepper(key, value, { label, ...o })}</div>`;
  // Show a new value on a stepper (element, or root + key) without calling onChange — e.g. when another setting forces it.
  A.setStepper = (s, v, key) => {
    if (key) s = s.querySelector(`[data-stp="${key}"]`);
    if (!s) return;
    const o = JSON.parse(s.dataset.o);
    s.dataset.v = v;
    s.querySelector('[data-sv]').textContent = stepFmt(v, o);
    s.querySelector('[data-sd="-1"]').disabled = v <= o.min;
    s.querySelector('[data-sd="1"]').disabled = v >= o.max;
  };
  // Wire every stepper inside root. onChange(key, value) runs on each change; return a number from it to override the value.
  A.bindSteppers = (root, onChange) => {
    const read = (s) => ({ s, v: +s.dataset.v, o: JSON.parse(s.dataset.o) });
    const put = (s, v, o) => {
      v = Math.max(o.min, Math.min(o.max, +(+v).toFixed(o.dp || 0)));
      const r = onChange(s.dataset.stp, v);
      if (typeof r === 'number') v = r;
      A.setStepper(s, v);
      return v;
    };
    let hold = null;
    const stop = () => { if (hold) { clearTimeout(hold.t); hold = null; } };
    root.addEventListener('pointerdown', (e) => {
      const b = e.target.closest('[data-sd]'); if (!b || b.disabled) return;
      const s = b.closest('[data-stp]'); const dir = +b.dataset.sd;
      e.preventDefault(); stop();
      let n = 0;
      const tick = () => {
        const { v, o } = read(s);
        const nv = put(s, v + dir * o.step * (n > 14 ? 5 : 1), o);
        if (navigator.vibrate && n === 0) navigator.vibrate(8);
        if ((dir < 0 && nv <= o.min) || (dir > 0 && nv >= o.max)) return stop();
        n++; hold.t = setTimeout(tick, n === 1 ? 420 : n < 6 ? 140 : 70);
      };
      hold = { t: 0 }; tick();
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => root.addEventListener(t, stop));
    // keyboard activation (click without a pointer)
    root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-sd]');
      if (b && e.detail === 0) { const s = b.closest('[data-stp]'); const { v, o } = read(s); put(s, v + +b.dataset.sd * o.step, o); }
    });
    W.on(root, 'click', '[data-sv]', async (e, b) => {
      const s = b.closest('[data-stp]'); const { v, o } = read(s);
      const res = await A.dialog({
        title: o.label || 'Set value',
        body: `<div class="field numfield"><input name="v" type="number" inputmode="decimal" value="${v}" min="${o.min}" max="${o.max}" step="${o.step}">${o.unit ? `<span class="unit">${esc(o.unit.trim())}</span>` : ''}</div><div class="muted" style="font-size:12.5px;margin-top:8px">Between ${stepFmt(o.min, { ...o, zero: '' })} and ${stepFmt(o.max, o)}${o.zero ? ` · 0 = ${esc(o.zero)}` : ''}</div>`,
        actions: [{ label: 'Cancel', value: null }, { label: 'Set', cls: 'fill', collect: (el) => el.querySelector('input').value }],
      });
      if (res == null || res === '' || isNaN(+res)) return;
      const nv = put(s, +res, o);
      if (nv !== +res) A.toast(`Kept within ${stepFmt(o.min, { ...o, zero: '' })}–${stepFmt(o.max, o)}`);
    });
  };

  A.appbar = ({ title = '', sub = '', back = false, actions = '', big = false, cls = '' } = {}) => `
    <header class="appbar ${big ? 'big' : ''} ${cls}">
      ${back ? `<button class="ib" data-act="back" aria-label="Back">${A.icon('arrow_back')}</button>` : ''}
      <div class="ttl"><h1>${title}</h1>${sub ? `<div class="sub">${sub}</div>` : ''}</div>
      ${actions}
    </header>`;
  A.ab = (icon, act, label, extra = '') => `<button class="ib" data-act="${act}" aria-label="${esc(label)}" title="${esc(label)}" ${extra}>${A.icon(icon)}</button>`;

  A.view = (cls = '') => { const v = document.createElement('div'); v.className = 'view ' + cls; v.addEventListener('scroll', () => v.classList.toggle('scrolled', v.scrollTop > 4), { passive: true }); return v; };

  // ---------- toasts ----------
  A.toast = (msg, action, fn) => {
    const host = document.getElementById('toasts');
    const t = W.el(`<div class="toast"><div class="grow">${esc(msg)}</div>${action ? `<button>${esc(action)}</button>` : ''}</div>`);
    if (action) t.querySelector('button').onclick = () => { fn && fn(); kill(); };
    host.appendChild(t);
    while (host.querySelectorAll('.toast').length > 2) host.querySelector('.toast').remove();
    let h;
    const kill = () => { clearTimeout(h); t.classList.add('out'); setTimeout(() => t.remove(), 220); };
    h = setTimeout(kill, action ? 4800 : 2800);
  };

  // ---------- sheets / menus / dialogs ----------
  const layer = () => document.getElementById('layer');
  A.sheet = ({ title = '', sub = '', body = '', foot = '', cls = '', onMount, onClose, headExtra = '' } = {}) => {
    const scrim = W.el('<div class="scrim"></div>');
    const el = W.el(`<div class="sheet ${cls}" role="dialog" aria-modal="true"><div class="grab"></div>
      ${title ? `<div class="sh-head"><div class="grow"><h2>${title}</h2>${sub ? `<div class="sub">${sub}</div>` : ''}</div>${headExtra}</div>` : ''}
      <div class="sh-body">${body}</div>${foot ? `<div class="sh-foot">${foot}</div>` : ''}</div>`);
    let closed = false;
    const close = () => {
      if (closed) return; closed = true;
      el.classList.add('out'); scrim.classList.add('out');
      setTimeout(() => { el.remove(); scrim.remove(); }, 230);
      onClose && onClose();
      A.sheetStack = A.sheetStack.filter((x) => x !== close);
    };
    scrim.onclick = close;
    // drag the grab handle down to dismiss
    let y0 = null;
    el.querySelector('.grab').addEventListener('pointerdown', (e) => { y0 = e.clientY; el.style.transition = 'none'; });
    el.addEventListener('pointermove', (e) => { if (y0 == null) return; const d = Math.max(0, e.clientY - y0); el.style.transform = `translateY(${d}px)`; });
    el.addEventListener('pointerup', (e) => { if (y0 == null) return; const d = e.clientY - y0; y0 = null; el.style.transition = 'transform .2s'; if (d > 90) close(); else el.style.transform = ''; });
    layer().append(scrim, el);
    A.sheetStack.push(close);
    onMount && onMount(el, close);
    return { el, close };
  };
  A.sheetStack = [];
  A.closeSheets = () => A.sheetStack.slice().forEach((c) => c());

  A.menu = ({ title = '', sub = '', sections = [] }) => {
    const items = [];
    const body = sections.map((sec) => `${sec.label ? `<div class="menu-sec">${esc(sec.label)}</div>` : ''}${sec.items.filter(Boolean).map((it) => { items.push(it); return `<button class="mi ${it.danger ? 'danger' : ''} ${it.on ? 'on' : ''}" data-i="${items.length - 1}">${A.icon(it.icon || 'chevron_right')}<div class="grow"><div>${esc(it.label)}</div>${it.sub ? `<div class="s">${esc(it.sub)}</div>` : ''}</div>${it.on ? A.icon('check', 'chk') : ''}</button>`; }).join('')}`).join('');
    return A.sheet({ title, sub, body, onMount: (el, close) => W.on(el, 'click', '.mi', (e, b) => { const it = items[+b.dataset.i]; close(); setTimeout(() => it.fn && it.fn(), 60); }) });
  };

  A.dialog = ({ title, body = '', icon = '', actions = [{ label: 'OK', value: true }] }) => new Promise((resolve) => {
    const scrim = W.el('<div class="scrim"></div>');
    const el = W.el(`<div class="dialog" role="alertdialog">${icon ? A.icon(icon, 'icon-top') : ''}<h2>${title}</h2><div class="body">${body}</div><div class="acts">${actions.map((a, i) => `<button class="btn sm ${a.cls || 'text'}" data-i="${i}">${esc(a.label)}</button>`).join('')}</div></div>`);
    const done = (v) => { el.remove(); scrim.classList.add('out'); setTimeout(() => scrim.remove(), 200); A.sheetStack = A.sheetStack.filter((x) => x !== cancel); resolve(v); };
    const cancel = () => done(null);
    scrim.onclick = cancel;
    W.on(el, 'click', '[data-i]', (e, b) => { const a = actions[+b.dataset.i]; const v = a.collect ? a.collect(el) : a.value; done(v); });
    layer().append(scrim, el);
    A.sheetStack.push(cancel);
    const f = el.querySelector('input,textarea'); if (f) setTimeout(() => { f.focus(); if (f.select) f.select(); }, 50);
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') { const i = actions.findIndex((a) => a.collect); if (i >= 0) el.querySelector(`[data-i="${i}"]`).click(); } });
  });
  A.confirm = (title, body, ok = 'Confirm', danger = false) => A.dialog({ title, body, actions: [{ label: 'Cancel', value: false }, { label: ok, value: true, cls: danger ? 'danger' : 'fill' }] }).then((v) => !!v);
  A.form = (title, fields, ok = 'Save') => A.dialog({
    title,
    body: fields.map((f) => `<label class="field-label" style="margin-top:12px">${esc(f.label)}</label><div class="field"><input name="${f.name}" value="${esc(f.value || '')}" placeholder="${esc(f.placeholder || '')}" ${f.type ? `type="${f.type}"` : ''}></div>`).join(''),
    actions: [{ label: 'Cancel', value: null }, { label: ok, cls: 'fill', collect: (el) => Object.fromEntries([...el.querySelectorAll('input')].map((i) => [i.name, i.value.trim()])) }],
  });

  // ---------- gestures ----------
  // Pointer-based long-press (450ms). onLong(e, target); suppresses the following click.
  A.longPress = (root, sel, onLong) => {
    let h = null, sx = 0, sy = 0, fired = false, tgt = null;
    root.addEventListener('pointerdown', (e) => {
      tgt = e.target.closest(sel); if (!tgt || !root.contains(tgt)) return;
      fired = false; sx = e.clientX; sy = e.clientY;
      clearTimeout(h);
      h = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(12); onLong(e, tgt); }, 450);
    });
    const cancel = () => clearTimeout(h);
    root.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8) cancel(); });
    root.addEventListener('pointerup', cancel);
    root.addEventListener('pointercancel', cancel);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(sel)) e.preventDefault(); });
    root.addEventListener('click', (e) => { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
  };

  // Render a long list incrementally (keeps 282 stories / 1500 chapters cheap).
  A.incremental = (container, items, renderItem, { chunk = 40, scroller } = {}) => {
    let i = 0;
    const sentinel = document.createElement('div');
    sentinel.style.height = '1px';
    const more = () => {
      const html = items.slice(i, i + chunk).map(renderItem).join('');
      i += chunk;
      sentinel.insertAdjacentHTML('beforebegin', html);
      if (i >= items.length) { io.disconnect(); sentinel.remove(); }
    };
    const io = new IntersectionObserver((en) => { if (en.some((x) => x.isIntersecting)) more(); }, { root: scroller || null, rootMargin: '900px' });
    container.innerHTML = '';
    container.appendChild(sentinel);
    more();
    if (i < items.length) io.observe(sentinel);
    return () => io.disconnect();
  };

  // ---------- simulated actions shared by several screens ----------
  A.sync = (s, after) => {
    if (A.state.syncing[s.id]) return;
    A.state.syncing[s.id] = true; A.emit();
    setTimeout(() => {
      delete A.state.syncing[s.id];
      A.state.synced[s.id] = Date.now();
      const f = W.forecast(s);
      const found = f.state === 'due' || f.state === 'late' ? 1 + (W.hash(s.id) % 3) : 0;
      if (found) { s.total += found; const list = W.chapters(s.id); for (let k = 0; k < found; k++) list.push({ id: `${s.id}-new${list.length + 1}`, title: `Chapter ${list.length + 1}`, downloaded: false, publishedAt: Date.now(), index: list.length + 1, audio: false }); }
      s.lastSync = Date.now();
      A.emit();
      A.toast(found ? `${A.short(s.title, 26)}: ${found} new chapter${found > 1 ? 's' : ''}` : `${A.short(s.title, 26)} is up to date`, found ? 'Download' : '', () => { W.downloads.enqueue(s.id); A.toast('Added to downloads'); });
      after && after(found);
    }, 1800 + (W.hash(s.id) % 900));
  };
  A.downloadRemaining = (s) => {
    const n = s.total - s.downloaded;
    if (n <= 0) { A.toast('Every chapter is already offline'); return; }
    W.downloads.enqueue(s.id);
    A.toast(`Downloading ${n} chapter${n > 1 ? 's' : ''}`, 'View', () => W.go('/queue'));
  };
  A.listen = (s, chapterId, index = 0) => {
    const list = W.chapters(s.id);
    const b = A.bookmark(s);
    const ch = chapterId || (W.session && W.session.storyId === s.id ? W.session.chapterId : (list[b || 0] || list[0]).id);
    const idx = chapterId ? index : (W.session && W.session.storyId === s.id ? W.session.index : 0);
    W.player.load(s.id, ch, { index: idx, play: true });
  };

  // tiny app-level event bus (sync state, bookmarks, tab moves…)
  const subs = new Set();
  A.onChange = (fn) => { subs.add(fn); return () => subs.delete(fn); };
  A.emit = () => subs.forEach((f) => { try { f(); } catch (e) { console.error(e); } });

  // ---------- SVG line chart ----------
  A.chart = (pts, { h = 150, w = 320, color = 'var(--gold)', id = 'g' } = {}) => {
    if (!pts || pts.length < 2) return `<div class="chart-empty">Not enough snapshots yet</div>`;
    const xs = pts.map((p) => p.t), ys = pts.map((p) => p.v);
    const x0 = Math.min(...xs), x1 = Math.max(...xs);
    let y0 = Math.min(...ys), y1 = Math.max(...ys);
    if (y1 - y0 < 1e-9) { y0 -= 1; y1 += 1; }
    const pad = (y1 - y0) * 0.12; y0 -= pad; y1 += pad;
    const X = (t) => ((t - x0) / Math.max(1, x1 - x0)) * w;
    const Y = (v) => h - ((v - y0) / (y1 - y0)) * h;
    const d = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p.t).toFixed(1)},${Y(p.v).toFixed(1)}`).join('');
    const last = pts[pts.length - 1];
    const grid = [0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${w}" y1="${h * f}" y2="${h * f}" stroke="rgba(255,236,200,.07)" stroke-dasharray="3 5"/>`).join('');
    return `<div class="chartbox" style="height:${h + 12}px"><svg class="chart" viewBox="0 -6 ${w} ${h + 12}" preserveAspectRatio="none" role="img">
      <defs><linearGradient id="${id}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".32"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
      ${grid}<path d="${d}L${w},${h}L0,${h}Z" fill="url(#${id})"/><path d="${d}" fill="none" stroke="${color}" stroke-width="2.2" vector-effect="non-scaling-stroke" stroke-linejoin="round" stroke-linecap="round"/></svg>
      <i class="chartdot" style="left:${((X(last.t) / w) * 100).toFixed(2)}%;top:${(((Y(last.v) + 6) / (h + 12)) * 100).toFixed(2)}%;background:${color}"></i></div>`;
  };

  A.fmtVal = (v, kind) => (kind === 'score' ? (+v).toFixed(2) : kind === 'usd' ? fmt.money(v) : fmt.num(Math.abs(v) < 1000 ? Math.round(v) : v));
  A.esc = esc;
})();
