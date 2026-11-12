/* v2-modern core: helpers, dynamic colour, sheets, snackbars, progress, app bar. */
(function () {
  const { esc, el, fmt } = WNA;
  const V2 = (window.V2 = { screens: {}, deleted: new Set() });

  // ---------- small helpers ----------
  V2.ic = (name, cls = '') => `<span class="ic ${cls}" aria-hidden="true">${name}</span>`;
  const ic = V2.ic;
  V2.ib = (icon, a, label, extra = '') => `<button class="ib ${extra}" data-a="${a}" aria-label="${esc(label)}" title="${esc(label)}">${ic(icon)}</button>`;
  V2.clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  V2.wide = () => innerWidth >= 840;
  V2.medium = () => innerWidth >= 600;
  V2.plural = (n, one, many) => `${fmt.int(n)} ${n === 1 ? one : many || one + 's'}`;
  V2.shortTitle = (t) => String(t).split(/\s[[(]/)[0].trim();
  V2.filter = (o) => WNA.filterStories(o).filter((s) => !V2.deleted.has(s.id));
  V2.tabName = (id) => { const t = WNA.tabs().find((x) => x.id === id); return t ? t.name : 'Unassigned'; };
  V2.setQuery = (q) => {
    const r = WNA.route(); const qs = new URLSearchParams({ ...r.query, ...q });
    [...qs.keys()].forEach((k) => { if (qs.get(k) === '' || qs.get(k) == null) qs.delete(k); });
    const s = qs.toString(); history.replaceState(null, '', '#' + r.path + (s ? '?' + s : ''));
  };
  V2.pubLabel = { ongoing: 'Ongoing', completed: 'Completed', hiatus: 'Hiatus', outdated: 'Outdated', unknown: '' };

  V2.aiCovers = {};
  V2.coverUrl = (s) => V2.aiCovers[s.id] || WNA.coverUrl(s);
  V2.cover = (s, { w, h, cls = '', eager = false, label = true } = {}) => {
    const url = V2.coverUrl(s);
    const size = w ? `width:${w}px;height:${h || Math.round(w * 1.5)}px;` : '';
    const bg = url ? '' : `background:${WNA.coverFallback(s)};`;
    const inner = url
      ? `<img src="${url}" alt="" ${eager ? '' : 'loading="lazy"'} decoding="async" onerror="this.remove()">`
      : label ? `<span class="fb">${esc(V2.shortTitle(s.title))}</span>` : '';
    return `<div class="cov ${cls}" style="${size}${bg}">${inner}</div>`;
  };
  V2.coverage = (s) => {
    const c = WNA.coverage(s);
    return `<div class="cvg">${c.segments.map(([a, b]) => `<i style="left:${a * 100}%;width:${Math.max(0.6, (b - a) * 100)}%"></i>`).join('')}${c.bookmark != null ? `<b style="left:${c.bookmark * 100}%"></b>` : ''}</div>`;
  };

  // ---------- dynamic colour (sampled from covers, fallback WNA.hue) ----------
  const palCache = new Map();
  const lin = (u) => { u /= 255; return u <= 0.04045 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4); };
  function oklch(r, g, b) {
    r = lin(r); g = lin(g); b = lin(b);
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
    const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
    const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
    return [L, Math.hypot(A, B), (Math.atan2(B, A) * 180 / Math.PI + 360) % 360];
  }
  function sample(url) {
    return new Promise((res) => {
      const img = new Image();
      img.decoding = 'async';
      img.onload = () => {
        try {
          const cv = document.createElement('canvas'); cv.width = 24; cv.height = 36;
          const cx = cv.getContext('2d', { willReadFrequently: true }); cx.drawImage(img, 0, 0, 24, 36);
          const d = cx.getImageData(0, 0, 24, 36).data;
          const bins = new Array(24).fill(0).map(() => ({ w: 0, hx: 0, hy: 0, c: 0 }));
          let total = 0;
          for (let i = 0; i < d.length; i += 4) {
            const [L, C, H] = oklch(d[i], d[i + 1], d[i + 2]);
            if (C < 0.025 || L < 0.2 || L > 0.95) continue;
            const w = C * C * (L > 0.35 ? 1 : 0.5);
            const bin = bins[Math.floor(H / 15) % 24];
            bin.w += w; bin.hx += Math.cos(H * Math.PI / 180) * w; bin.hy += Math.sin(H * Math.PI / 180) * w; bin.c += C * w; total += w;
          }
          // smooth neighbouring bins so a spread of similar hues wins over a single spike
          let best = null, bestW = 0;
          bins.forEach((b, i) => { const w = b.w + 0.5 * (bins[(i + 23) % 24].w + bins[(i + 1) % 24].w); if (w > bestW) { bestW = w; best = b; } });
          if (!best || total < 0.02 || !best.w) return res({ h: 70, c: 0.02 });
          const h = (Math.atan2(best.hy, best.hx) * 180 / Math.PI + 360) % 360;
          res({ h, c: V2.clamp(best.c / best.w, 0.03, 0.16) });
        } catch (e) { res(null); }
      };
      img.onerror = () => res(null);
      img.src = url;
    });
  }
  function tokens({ h, c }) {
    const k = V2.clamp(c * 1.15, 0.065, 0.15);
    const n = Math.min(0.02, k * 0.14);
    h = Math.round(h * 10) / 10;
    return {
      '--primary': `oklch(0.83 ${(k * 0.95).toFixed(3)} ${h})`,
      '--on-primary': `oklch(0.27 ${(k * 0.55).toFixed(3)} ${h})`,
      '--primary-container': `oklch(0.39 ${(k * 0.7).toFixed(3)} ${h})`,
      '--on-primary-container': `oklch(0.93 ${(k * 0.4).toFixed(3)} ${h})`,
      '--secondary-container': `oklch(0.32 ${(k * 0.32).toFixed(3)} ${h})`,
      '--on-secondary-container': `oklch(0.92 ${(k * 0.22).toFixed(3)} ${h})`,
      '--wash': `oklch(0.34 ${(k * 0.6).toFixed(3)} ${h})`,
      '--surface': `oklch(0.16 ${n.toFixed(3)} ${h})`,
      '--sc-low': `oklch(0.19 ${n.toFixed(3)} ${h})`,
      '--sc': `oklch(0.21 ${n.toFixed(3)} ${h})`,
      '--sc-high': `oklch(0.24 ${n.toFixed(3)} ${h})`,
      '--sc-highest': `oklch(0.275 ${n.toFixed(3)} ${h})`,
      '--outline-variant': `oklch(0.37 ${(n * 1.2).toFixed(3)} ${h})`,
    };
  }
  const setVars = (node, t) => Object.entries(t).forEach(([k, v]) => node.style.setProperty(k, v));
  V2.forgetPalette = (id) => palCache.delete(id);
  V2.palette = (s) => palCache.get(s.id) || { h: (WNA.hue(s) + 20) % 360, c: 0.07, fallback: true };
  V2.theme = (node, s) => {
    if (!node || !s) return;
    node.dataset.themed = s.id;
    setVars(node, tokens(V2.palette(s)));
    const url = V2.coverUrl(s);
    if (url && !palCache.has(s.id)) {
      sample(url).then((p) => { if (!p) return; palCache.set(s.id, p); document.querySelectorAll(`[data-themed="${CSS.escape(s.id)}"]`).forEach((n) => setVars(n, tokens(p))); });
    }
  };
  V2.unTheme = (node) => { if (!node) return; delete node.dataset.themed; ['--primary', '--on-primary', '--primary-container', '--on-primary-container', '--secondary-container', '--on-secondary-container', '--wash', '--surface', '--sc-low', '--sc', '--sc-high', '--sc-highest', '--outline-variant'].forEach((k) => node.style.removeProperty(k)); };

  // ---------- progress ----------
  V2.wavy = (p, cls = '') => `<div class="wavy ${cls} ${p >= 1 ? 'done' : ''}" style="--p:${V2.clamp(p, 0, 1)}"><i class="wf"></i><i class="wt"></i></div>`;
  V2.setWavy = (node, p) => { if (!node) return; node.style.setProperty('--p', V2.clamp(p, 0, 1)); node.classList.toggle('done', p >= 1); };
  V2.ring = (p, size = 56, sw = 4, cls = '') => {
    const c = size / 2, amp = size > 40 ? 1.6 : 1.1, r = c - sw / 2 - amp - 1, n = size > 40 ? 10 : 8;
    let d = '';
    for (let i = 0; i <= 120; i++) { const t = (i / 120) * Math.PI * 2; const rr = r + amp * Math.sin(n * t); d += (i ? 'L' : 'M') + (c + rr * Math.cos(t)).toFixed(2) + ' ' + (c + rr * Math.sin(t)).toFixed(2); }
    const pv = p == null ? 28 : V2.clamp(p, 0, 1) * 100;
    return `<svg class="ring ${cls} ${p == null ? 'spin' : ''}" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><circle class="trk" cx="${c}" cy="${c}" r="${r}" fill="none" stroke-width="${sw}"/><path class="bar" d="${d}Z" pathLength="100" fill="none" stroke-width="${sw}" stroke-linecap="round" stroke-dasharray="${pv} 100" transform="rotate(-90 ${c} ${c})"/></svg>`;
  };
  V2.setRing = (svg, p) => { const b = svg && svg.querySelector('.bar'); if (b) b.setAttribute('stroke-dasharray', `${V2.clamp(p, 0, 1) * 100} 100`); };

  // ---------- app bar ----------
  V2.bar = ({ title = '', sub = '', back = true, acts = '', cls = '' } = {}) =>
    `<header class="bar ${cls}">${back ? V2.ib('arrow_back', 'back', 'Back') : '<span style="width:12px"></span>'}<div class="bt"><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</div><div class="acts">${acts}</div></header>`;
  V2.large = (title, sub = '') => `<div class="bar-large"><h1>${esc(title)}</h1>${sub ? `<p>${esc(sub)}</p>` : ''}</div>`;
  V2.collapse = (root, { threshold = 56, onScroll } = {}) => {
    const bar = root.querySelector('.bar'); const sc = root.querySelector('.scroll');
    if (!bar || !sc) return;
    let last = 0;
    sc.addEventListener('scroll', () => {
      const y = sc.scrollTop; bar.classList.toggle('raised', y > threshold);
      if (onScroll) onScroll(y, y - last); last = y;
    }, { passive: true });
  };

  // ---------- long press ----------
  V2.longPress = (root, sel, fn) => {
    let t = null, start = null, fired = false;
    const cancel = () => { clearTimeout(t); t = null; };
    root.addEventListener('pointerdown', (e) => {
      const tg = e.target.closest(sel); if (!tg || !root.contains(tg) || e.button > 0) return;
      fired = false; start = [e.clientX, e.clientY]; cancel();
      t = setTimeout(() => { fired = true; if (navigator.vibrate) navigator.vibrate(12); fn(tg, e); }, 450);
    });
    root.addEventListener('pointermove', (e) => { if (t && start && Math.hypot(e.clientX - start[0], e.clientY - start[1]) > 10) cancel(); });
    root.addEventListener('pointerup', cancel); root.addEventListener('pointercancel', cancel);
    root.addEventListener('click', (e) => { if (fired) { fired = false; e.stopPropagation(); e.preventDefault(); } }, true);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(sel)) e.preventDefault(); });
  };

  // ---------- sheets ----------
  const open = [];
  V2.sheet = ({ title = '', sub = '', body = '', foot = '', cls = '', onClose } = {}) => {
    const scrim = el('<div class="scrim"></div>');
    const sh = el(`<div class="sheet ${cls}" role="dialog" aria-modal="true"><div class="grab"></div>${title ? `<div class="sh-h"><h2>${esc(title)}</h2>${sub ? `<p>${sub}</p>` : ''}</div>` : ''}<div class="sh-b"></div>${foot ? `<div class="sh-f">${foot}</div>` : ''}</div>`);
    const b = sh.querySelector('.sh-b');
    if (typeof body === 'string') b.innerHTML = body; else if (body) b.appendChild(body);
    let closed = false;
    const api = {
      el: sh, body: b,
      close(instant) {
        if (closed) return; closed = true; open.splice(open.indexOf(api), 1);
        if (instant) { scrim.remove(); sh.remove(); } else { scrim.classList.add('out'); sh.classList.add('out'); setTimeout(() => { scrim.remove(); sh.remove(); }, 260); }
        if (onClose) onClose();
      },
    };
    scrim.addEventListener('click', () => api.close());
    // drag to dismiss from the handle/header
    let y0 = null, dy = 0;
    sh.addEventListener('pointerdown', (e) => { if (!e.target.closest('.grab,.sh-h')) return; y0 = e.clientY; dy = 0; sh.setPointerCapture(e.pointerId); sh.style.transition = 'none'; });
    sh.addEventListener('pointermove', (e) => { if (y0 == null) return; dy = Math.max(0, e.clientY - y0); sh.style.transform = `translateY(${dy}px)`; });
    const end = () => { if (y0 == null) return; y0 = null; sh.style.transition = 'transform .3s var(--ease-emph)'; if (dy > 90) { sh.style.transform = 'translateY(100%)'; api.close(); } else sh.style.transform = ''; };
    sh.addEventListener('pointerup', end); sh.addEventListener('pointercancel', end);
    const top = V2.top && V2.top();
    if (top && top.el.dataset.themed) V2.theme(sh, WNA.story(top.el.dataset.themed));
    document.body.append(scrim, sh);
    // Safety net: never leave a sheet parked off-screen if its entrance animation fails to start.
    setTimeout(() => [sh, scrim].forEach((n) => n.getAnimations().forEach((a) => { if (!a.currentTime) a.finish(); })), 700);
    open.push(api);
    return api;
  };
  V2.closeSheets = () => open.slice().forEach((s) => s.close(true));
  V2.sheetOpen = () => open.length > 0;

  const itemHtml = (it, i) => it.html ? it.html : `<button class="li ${it.danger ? 'danger' : ''}" data-i="${i}" ${it.disabled ? 'disabled style="opacity:.4"' : ''}>${it.icon ? `<span class="lead">${ic(it.icon)}</span>` : ''}<span class="tx"><b>${esc(it.label)}</b>${it.sub ? `<span>${esc(it.sub)}</span>` : ''}</span>${it.trail ? `<span class="tr">${it.trail}</span>` : ''}</button>`;
  V2.menu = ({ title = '', sub = '', items = [], groups = null, header = '' } = {}) => {
    const flat = []; const gs = groups || [{ items }];
    const body = header + gs.map((g) => `${g.label ? `<div class="grp-l">${esc(g.label)}</div>` : ''}${g.items.filter(Boolean).map((it) => { flat.push(it); return itemHtml(it, flat.length - 1); }).join('')}`).join('');
    const s = V2.sheet({ title, sub, body });
    s.body.addEventListener('click', (e) => { const b = e.target.closest('[data-i]'); if (!b) return; const it = flat[+b.dataset.i]; s.close(); if (it && it.onClick) setTimeout(it.onClick, 120); });
    return s;
  };
  V2.confirm = ({ title, text = '', ok = 'Confirm', danger = false, icon = 'help' } = {}) => new Promise((res) => {
    let v = false;
    const s = V2.sheet({
      cls: 'confirm',
      body: `<div class="cf"><div class="cf-ic ${danger ? 'bad' : ''}">${ic(icon, 'fill')}</div><h2>${esc(title)}</h2>${text ? `<p>${esc(text)}</p>` : ''}<div class="cf-b"><button class="btn tonal l" data-c="0">Cancel</button><button class="btn l ${danger ? 'danger' : 'filled'}" data-c="1">${esc(ok)}</button></div></div>`,
      onClose: () => res(v),
    });
    s.body.addEventListener('click', (e) => { const b = e.target.closest('[data-c]'); if (!b) return; v = b.dataset.c === '1'; s.close(); });
  });
  V2.choose = ({ title, sub = '', options, value } = {}) => new Promise((res) => {
    let v = null;
    const s = V2.sheet({
      title, sub,
      body: `<div class="seglist">${options.map((o, i) => `<button class="li" data-o="${i}">${o.icon ? `<span class="lead">${ic(o.icon)}</span>` : ''}<span class="tx"><b>${esc(o.label)}</b>${o.sub ? `<span>${esc(o.sub)}</span>` : ''}</span><span class="radio ${o.value === value ? 'on' : ''}"></span></button>`).join('')}</div>`,
      onClose: () => res(v),
    });
    s.body.addEventListener('click', (e) => {
      const b = e.target.closest('[data-o]'); if (!b) return;
      s.body.querySelectorAll('.radio').forEach((r) => r.classList.remove('on')); b.querySelector('.radio').classList.add('on');
      v = options[+b.dataset.o].value; setTimeout(() => s.close(), 180);
    });
  });
  V2.prompt = ({ title, sub = '', fields, ok = 'Save' }) => new Promise((res) => {
    let v = null;
    const s = V2.sheet({
      title, sub,
      body: `<div class="pad col" style="gap:12px">${fields.map((f, i) => `<label class="tf"><label>${esc(f.label)}</label>${f.multi ? `<textarea data-f="${i}" rows="3">${esc(f.value || '')}</textarea>` : `<input data-f="${i}" value="${esc(f.value || '')}" placeholder="${esc(f.placeholder || '')}" ${f.type ? `type="${f.type}"` : ''}>`}</label>`).join('')}</div>`,
      foot: `<button class="btn text" data-p="0">Cancel</button><button class="btn filled" data-p="1">${esc(ok)}</button>`,
      onClose: () => res(v),
    });
    s.el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-p]'); if (!b) return;
      if (b.dataset.p === '1') v = fields.map((_, i) => s.el.querySelector(`[data-f="${i}"]`).value.trim());
      s.close();
    });
    setTimeout(() => { const f = s.el.querySelector('[data-f="0"]'); if (f && !f.value) f.focus(); }, 350);
  });

  // ---------- snackbar ----------
  let snackEl = null, snackT = null;
  V2.snack = (msg, { action, onAction, ms = 4200 } = {}) => {
    if (snackEl) snackEl.remove();
    clearTimeout(snackT);
    const n = el(`<div class="snack" role="status"><span class="grow">${esc(msg)}</span>${action ? `<button class="btn text s">${esc(action)}</button>` : ''}${V2.ib('close', 'x', 'Dismiss')}</div>`);
    const hide = () => { n.classList.add('out'); setTimeout(() => n.remove(), 220); if (snackEl === n) snackEl = null; };
    n.addEventListener('click', (e) => { if (e.target.closest('.btn.text') && onAction) onAction(); if (e.target.closest('button')) hide(); });
    document.body.appendChild(n); snackEl = n; snackT = setTimeout(hide, ms);
  };

  // ---------- simulated async ----------
  V2.wait = (ms) => new Promise((r) => setTimeout(r, ms));
  V2.copy = (text, msg = 'Copied') => { try { navigator.clipboard.writeText(text); } catch (e) { /* not available over http */ } V2.snack(msg); };
})();
