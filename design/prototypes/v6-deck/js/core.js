/* v6 Deck & command — core: namespace, state, helpers, UI primitives (sheet, dialog, toast). */
(() => {
  const W = window.WNA;
  const esc = W.esc;
  const prefs = W.data.prefs || {};

  const V6 = (window.V6 = {
    W, esc, screens: {},
    state: {
      homeMode: 'deck', gridView: prefs.libraryViewMode === 'list' ? 'list' : 'cover',
      tab: prefs.libraryTabId || 'all', deckPos: {},
      filters: { query: '', sources: [], tags: [], sort: prefs.librarySortOption || 'default', asc: null },
      deleted: new Set(), continueDismissed: false, bookmarks: {}, epub: {}, queueOrder: [], recents: [],
      removed: { rr_118891: 3 }, syncing: {}, synced: {}, patreonLinks: {},
    },
  });

  // ---------- data helpers ----------
  V6.stories = () => W.stories().filter((s) => !V6.state.deleted.has(s.id));
  V6.short = (s) => {
    const t = (s && s.title) || '';
    const cut = t.split(/\s[[(]|:\s|\s[-–—|]\s|\s\(/)[0].trim();
    return cut.length > 2 ? cut : t;
  };
  V6.bookmarkOf = (s) => (V6.state.bookmarks[s.id] !== undefined ? V6.state.bookmarks[s.id] : s.bookmark);
  V6.setBookmark = (s, idx) => { V6.state.bookmarks[s.id] = idx; s.bookmark = idx; };
  V6.resumeChapter = (s) => {
    const list = W.chapters(s.id);
    const b = V6.bookmarkOf(s);
    if (W.session && W.session.storyId === s.id) return W.chapter(s.id, W.session.chapterId);
    const c = b != null ? list[b] : list.find((x) => x.downloaded) || list[0];
    return c && c.downloaded ? c : list.find((x) => x.downloaded) || c;
  };
  V6.remaining = (s) => Math.max(0, (s.total || 0) - (s.downloaded || 0));
  V6.ctxStory = () => {
    const r = W.route();
    if (r.params && r.params.id && W.story(r.params.id)) return W.story(r.params.id);
    const p = W.player.state();
    if (p.loaded) return p.story;
    return W.session ? W.story(W.session.storyId) : null;
  };
  V6.pubLabel = (p) => ({ ongoing: 'Ongoing', completed: 'Completed', hiatus: 'Hiatus', outdated: 'Outdated' }[p] || '');

  // ---------- html helpers ----------
  V6.ic = (n, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${n}</span>`;
  V6.cover = (s, cls = '', eager = false) => {
    const u = W.coverUrl(s);
    if (u) return `<div class="cov ${cls}"><img src="${u}" alt="" loading="${eager ? 'eager' : 'lazy'}" decoding="async"></div>`;
    return `<div class="cov cov-fb ${cls}" style="background:${W.coverFallback(s)}"><span>${esc(V6.short(s))}</span></div>`;
  };
  V6.bar = (f, cls = '') => `<div class="pbar ${cls}"><i style="width:${Math.max(0, Math.min(1, f)) * 100}%"></i></div>`;
  V6.coverageBar = (s, cls = '') => {
    const c = W.coverage(s);
    const segs = c.segments.map(([a, b]) => `<i style="left:${a * 100}%;width:${(b - a) * 100}%"></i>`).join('');
    const b = V6.bookmarkOf(s);
    const pin = b != null && s.total ? `<b class="pin" style="left:${((b + 0.5) / Math.max(1, W.chapters(s.id).length)) * 100}%"></b>` : '';
    return `<div class="cbar ${cls}">${segs}${pin}</div>`;
  };
  V6.topbar = (title, sub, actions = '', { back = true, kicker = '' } = {}) => `
    <header class="tb">
      ${back ? `<button class="tb-back" data-back aria-label="Back">${V6.ic('arrow_back')}</button>` : ''}
      <div class="tb-t">${kicker ? `<div class="kick">${kicker}</div>` : ''}<div class="tb-title">${title}</div>${sub ? `<div class="tb-sub">${sub}</div>` : ''}</div>
      <div class="tb-acts">${actions}</div>
    </header>`;
  V6.iconBtn = (icon, attrs = '', label = '') => `<button class="ib" ${attrs} aria-label="${esc(label)}">${V6.ic(icon)}</button>`;
  V6.sw = (on, attrs = '') => `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${!!on}" ${attrs}><i></i></button>`;
  V6.sec = (num, title, extra = '') => `<div class="sec"><span class="sec-n">${num}</span><h2>${title}</h2>${extra}</div>`;
  V6.empty = (icon, title, body, action = '') => `<div class="empty">${V6.ic(icon)}<h3>${title}</h3><p>${body}</p>${action}</div>`;
  V6.statusColor = (kind) => ({ ready: 'ok', generating: 'acc', queued: 'mut', failed: 'bad', stale: 'bad', none: 'mut' }[kind] || 'mut');

  // ---------- fuzzy ----------
  V6.fuzzy = (q, text) => {
    q = q.toLowerCase(); const t = text.toLowerCase();
    if (!q) return { score: 1, idx: [] };
    const sub = t.indexOf(q);
    if (sub >= 0) { const idx = []; for (let i = 0; i < q.length; i++) idx.push(sub + i); const ws = sub === 0 || /[\s\-[(:·]/.test(t[sub - 1]); return { score: 100 + (ws ? 40 : 0) - sub * 0.3 - t.length * 0.05, idx }; }
    let ti = 0, score = 0, prev = -2; const idx = [];
    for (let i = 0; i < q.length; i++) {
      const ch = q[i]; if (ch === ' ') continue;
      const f = t.indexOf(ch, ti); if (f < 0) return null;
      score += f === prev + 1 ? 6 : 1; if (f === 0 || /[\s\-[(:·]/.test(t[f - 1])) score += 5;
      idx.push(f); prev = f; ti = f + 1;
    }
    if (idx.length && idx[idx.length - 1] - idx[0] > q.length * 6) return null;
    return { score: score - t.length * 0.05, idx };
  };
  V6.hl = (text, idx) => {
    if (!idx || !idx.length) return esc(text);
    const set = new Set(idx); let out = '', open = false;
    for (let i = 0; i < text.length; i++) { const m = set.has(i); if (m && !open) { out += '<mark>'; open = true; } if (!m && open) { out += '</mark>'; open = false; } out += esc(text[i]); }
    return out + (open ? '</mark>' : '');
  };

  // ---------- layer: toast ----------
  const layer = () => document.getElementById('layer');
  let toastEl = null, toastT = null;
  V6.toast = (msg, { action, onAction, icon = 'check', ms = 3200 } = {}) => {
    if (toastEl) toastEl.remove();
    clearTimeout(toastT);
    toastEl = W.el(`<div class="toast" role="status">${V6.ic(icon)}<span>${msg}</span>${action ? `<button>${esc(action)}</button>` : ''}</div>`);
    if (action) toastEl.querySelector('button').onclick = () => { onAction && onAction(); hide(); };
    layer().appendChild(toastEl);
    requestAnimationFrame(() => toastEl && toastEl.classList.add('in'));
    const el = toastEl;
    const hide = () => { el.classList.remove('in'); setTimeout(() => el.remove(), 250); if (toastEl === el) toastEl = null; };
    toastT = setTimeout(hide, ms);
  };

  // ---------- layer: sheet ----------
  const stack = [];
  V6.closeTop = () => { const t = stack[stack.length - 1]; if (t) { t.close(); return true; } return false; };
  V6.sheet = ({ title = '', sub = '', items = null, html = '', onMount, cls = '', full = false } = {}) => {
    const wrap = W.el(`<div class="sheet-wrap ${full ? 'full' : ''}"><div class="scrim"></div><section class="sheet ${cls}" role="dialog" aria-modal="true">
      <div class="grab"><i></i></div>
      ${title ? `<header class="sh-h"><div><h3>${title}</h3>${sub ? `<p>${sub}</p>` : ''}</div><button class="ib sh-x" aria-label="Close">${V6.ic('close')}</button></header>` : ''}
      <div class="sh-body"></div></section></div>`);
    const body = wrap.querySelector('.sh-body');
    if (items) {
      body.innerHTML = items.map((it, i) => {
        if (it.label && it.section) return `<div class="sh-sec">${esc(it.label)}</div>`;
        if (it.sep) return '<hr class="sh-sep">';
        return `<button class="sh-it ${it.danger ? 'danger' : ''} ${it.on ? 'on' : ''}" data-i="${i}" ${it.disabled ? 'disabled' : ''}>${it.icon ? V6.ic(it.icon) : ''}<span class="sh-l"><b>${esc(it.label)}</b>${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</span>${it.value ? `<em>${esc(it.value)}</em>` : ''}${it.on ? V6.ic('check', 'sh-ck') : ''}</button>`;
      }).join('');
      body.addEventListener('click', (e) => { const b = e.target.closest('.sh-it'); if (!b) return; const it = items[+b.dataset.i]; close(); it.run && setTimeout(() => it.run(), 60); });
    } else body.innerHTML = html;
    let closed = false;
    const close = () => {
      if (closed) return; closed = true;
      wrap.classList.remove('in'); setTimeout(() => wrap.remove(), 260);
      const i = stack.indexOf(api); if (i >= 0) stack.splice(i, 1);
    };
    const api = { el: wrap, body, close };
    wrap.querySelector('.scrim').onclick = close;
    const x = wrap.querySelector('.sh-x'); if (x) x.onclick = close;
    layer().appendChild(wrap); stack.push(api);
    // drag the grab bar down to dismiss
    const sheet = wrap.querySelector('.sheet');
    V6.dragDismiss && V6.dragDismiss(wrap.querySelector('.grab'), sheet, close);
    requestAnimationFrame(() => !closed && wrap.classList.add('in'));
    onMount && onMount(body, api);
    return api;
  };

  V6.dialog = ({ title, body = '', ok = 'OK', cancel = 'Cancel', danger = false, fields = null }) => new Promise((res) => {
    const f = fields ? fields.map((fl, i) => `<label class="fld"><span>${esc(fl.label)}</span>${fl.multiline ? `<textarea data-f="${i}" rows="3" placeholder="${esc(fl.placeholder || '')}">${esc(fl.value || '')}</textarea>` : `<input data-f="${i}" type="${fl.type || 'text'}" value="${esc(fl.value || '')}" placeholder="${esc(fl.placeholder || '')}">`}</label>`).join('') : '';
    const wrap = W.el(`<div class="dlg-wrap"><div class="scrim"></div><div class="dlg" role="alertdialog"><h3>${title}</h3>${body ? `<p>${body}</p>` : ''}${f}<div class="dlg-a">${cancel ? `<button class="btn ghost" data-r="0">${esc(cancel)}</button>` : ''}<button class="btn ${danger ? 'danger' : 'acc'}" data-r="1">${esc(ok)}</button></div></div></div>`);
    const api = { close: () => done(false) };
    let fin = false;
    const done = (v) => {
      if (fin) return; fin = true;
      wrap.classList.remove('in'); setTimeout(() => wrap.remove(), 200);
      const i = stack.indexOf(api); if (i >= 0) stack.splice(i, 1);
      if (v && fields) res([...wrap.querySelectorAll('[data-f]')].map((x) => x.value)); else res(v);
    };
    wrap.querySelector('.scrim').onclick = () => done(false);
    wrap.querySelectorAll('[data-r]').forEach((b) => (b.onclick = () => done(b.dataset.r === '1')));
    layer().appendChild(wrap); stack.push(api);
    requestAnimationFrame(() => { if (!fin) wrap.classList.add('in'); const inp = wrap.querySelector('[data-f]'); if (inp) inp.focus(); });
  });

  // ---------- shared actions ----------
  V6.act = {
    play(s, chapterId, index) {
      const c = chapterId ? W.chapter(s.id, chapterId) : V6.resumeChapter(s);
      if (!c) return;
      const idx = index != null ? index : W.session && W.session.storyId === s.id && W.session.chapterId === c.id ? W.session.index : 0;
      W.player.load(s.id, c.id, { index: idx, play: true });
      V6.toast(`Playing · ${esc(V6.short(s))} · Ch ${c.index}`, { icon: 'graphic_eq' });
    },
    download(s, { quiet = false } = {}) {
      const n = V6.remaining(s);
      if (!n) { if (!quiet) V6.toast(`All ${s.total} chapters already offline`, { icon: 'offline_pin' }); return 0; }
      W.downloads.enqueue(s.id);
      V6.toast(`Queued ${n} chapter${n === 1 ? '' : 's'} · ${esc(V6.short(s))}`, { icon: 'download', action: 'View', onAction: () => W.go('/queue') });
      return n;
    },
    sync(s, cb) {
      if (V6.state.syncing[s.id]) return;
      V6.state.syncing[s.id] = 'Checking source…'; cb && cb();
      setTimeout(() => { V6.state.syncing[s.id] = 'Comparing chapter list…'; cb && cb(); }, 900);
      setTimeout(() => {
        delete V6.state.syncing[s.id];
        const f = W.forecast(s); const found = f.state === 'due' ? 1 + (W.hash(s.id) % 2) : 0;
        if (found) { s.total += found; }
        V6.state.synced[s.id] = Date.now(); s.lastSync = Date.now(); cb && cb();
        V6.toast(found ? `${found} new chapter${found > 1 ? 's' : ''} · ${esc(V6.short(s))}` : `Up to date · ${esc(V6.short(s))}`, { icon: found ? 'fiber_new' : 'sync', action: found ? 'Download' : null, onAction: () => V6.act.download(s) });
      }, 2100);
    },
    generate(s, n = 5, fromChapter) {
      const list = W.chapters(s.id);
      const start = fromChapter != null ? fromChapter : Math.max(0, list.indexOf(V6.resumeChapter(s)));
      const ids = list.slice(start).filter((c) => c.downloaded && W.narration.chapterStatus(s.id, c.id).kind === 'none').slice(0, n).map((c) => c.id);
      const k = ids.length ? W.narration.enqueue(s.id, ids) : 0;
      V6.toast(k ? `Generating audio for ${k} chapter${k > 1 ? 's' : ''} · ${esc(V6.short(s))}` : 'Those chapters already have audio', { icon: 'graphic_eq', action: k ? 'Queue' : null, onAction: () => W.go('/narration') });
    },
    sleep(v) {
      W.player.setSleep(v);
      V6.toast(v ? (v === 'chapter' ? 'Sleep at end of chapter' : `Sleep timer · ${v} min`) : 'Sleep timer off', { icon: 'bedtime' });
    },
    speed(r) { W.player.setRate(r); V6.toast(`Speed ${r}×`, { icon: 'speed' }); },
  };

  V6.sleepSheet = () => {
    const cur = W.player.state().sleep;
    V6.sheet({ title: 'Sleep timer', items: [
      { label: 'Off', on: !cur, run: () => V6.act.sleep(null) },
      ...[15, 30, 45, 60].map((m) => ({ label: `${m} minutes`, on: cur === m, run: () => V6.act.sleep(m) })),
      { label: 'End of chapter', on: cur === 'chapter', run: () => V6.act.sleep('chapter') },
    ] });
  };
  V6.speedSheet = () => {
    const cur = W.player.state().rate;
    V6.sheet({ title: 'Speed', items: W.player.SPEEDS.map((r) => ({ label: `${r}×`, on: r === cur, run: () => V6.act.speed(r) })) });
  };

  // story-level action sheet (deck long-press, grid long-press menu)
  V6.storySheet = (s) => {
    const c = V6.resumeChapter(s);
    V6.sheet({ title: esc(s.title), sub: `by ${esc(s.author || 'Unknown')}`, items: [
      { icon: 'play_arrow', label: `Play · Ch ${c ? c.index : 1}`, run: () => V6.act.play(s) },
      { icon: 'menu_book', label: 'Read', sub: c ? c.title : '', run: () => W.go(`/reader/${s.id}/${c.id}`) },
      { icon: 'open_in_full', label: 'Open details', run: () => W.go('/story/' + s.id) },
      { icon: 'download', label: V6.remaining(s) ? `Download remaining (${V6.remaining(s)})` : 'All chapters offline', disabled: !V6.remaining(s), run: () => V6.act.download(s) },
      { icon: 'sync', label: 'Sync chapters', run: () => V6.act.sync(s) },
      { icon: 'graphic_eq', label: 'Generate audio · next 5', run: () => V6.act.generate(s, 5) },
      { icon: 'drive_file_move', label: 'Move to tab', run: () => V6.moveSheet([s.id]) },
      { icon: 'show_chart', label: 'Trends', run: () => W.go(`/story/${s.id}/trends`) },
      { sep: true },
      { icon: 'delete', label: 'Delete novel', danger: true, run: () => V6.confirmDelete([s.id]) },
    ] });
  };
  V6.moveSheet = (ids, done) => {
    V6.sheet({ title: `Move ${ids.length} novel${ids.length > 1 ? 's' : ''}`, items: [
      { label: 'Unassigned', run: () => mv(null) },
      ...W.tabs().map((t) => ({ label: t.name, value: String(W.storiesInTab(t.id).filter((s) => !V6.state.deleted.has(s.id)).length), run: () => mv(t) })),
    ] });
    function mv(t) { ids.forEach((id) => (W.story(id).tab = t ? t.id : null)); V6.toast(`Moved ${ids.length} to ${t ? esc(t.name) : 'Unassigned'}`, { icon: 'drive_file_move' }); done && done(); V6.refresh && V6.refresh(); }
  };
  V6.confirmDelete = async (ids, done) => {
    const one = ids.length === 1 ? W.story(ids[0]) : null;
    const ok = await V6.dialog({ title: one ? 'Delete novel?' : `Delete ${ids.length} novels?`, body: one ? `“${esc(one.title)}” and its downloaded chapters will be removed from this phone.` : 'Their downloaded chapters will be removed from this phone.', ok: 'Delete', danger: true });
    if (!ok) return;
    ids.forEach((id) => V6.state.deleted.add(id));
    V6.toast(`Deleted ${ids.length} novel${ids.length > 1 ? 's' : ''}`, { icon: 'delete', action: 'Undo', onAction: () => { ids.forEach((id) => V6.state.deleted.delete(id)); V6.refresh && V6.refresh(); } });
    done && done(); V6.refresh && V6.refresh();
    if (W.route().name !== 'library') W.go('/library', { replace: true });
  };
})();
