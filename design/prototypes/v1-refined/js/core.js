/* v1 Refined — namespace, shared state, HTML builders. */
(function () {
  const { esc, fmt } = WNA;
  const V = (window.V1 = { screens: {}, state: {} });

  // ---------- app state (in-memory, session only) ----------
  const prefs = WNA.data.prefs || {};
  V.state = {
    tab: prefs.libraryTabId || 'all',
    view: prefs.libraryViewMode === 'cover' ? 'cover' : 'list',
    sort: prefs.librarySortOption || 'default',
    asc: prefs.librarySortAscending ? true : null,
    query: '',
    sources: [],
    tags: [],
    removed: new Set(),
    continueDismissed: false,
    readerScale: WNA.settings.reader.fontScale || 1,
    detailsHidden: {},
  };

  // ---------- small helpers ----------
  V.ic = (name, cls = '') => `<span class="ms ${cls}" aria-hidden="true">${name}</span>`;
  V.ib = (icon, act, label, { cls = '', extra = '', fill = false, data = '' } = {}) =>
    `<button class="ib ${cls}" data-act="${act}" ${data} aria-label="${esc(label)}" title="${esc(label)}">${V.ic(icon, fill ? 'fill' : '')}${extra}</button>`;
  V.btn = (label, act, { kind = 'tonal', icon = '', cls = '', data = '', disabled = false } = {}) =>
    `<button class="btn ${kind} ${cls}" data-act="${act}" ${data} ${disabled ? 'disabled' : ''}>${icon ? V.ic(icon) : ''}<span>${label}</span></button>`;
  V.short = (title) => String(title || '').split(/\s[[(]|:\s/)[0].trim();
  V.pct = (x) => `${Math.round(Math.max(0, Math.min(1, x)) * 100)}%`;
  V.plural = (n, one, many) => `${fmt.int(n)} ${n === 1 ? one : many || one + 's'}`;

  V.appBar = ({ title = '', subtitle = '', back = true, actions = '', large = false, cls = '', fadeTitle = false } = {}) => `
    <header class="appbar ${large ? 'large' : ''} ${cls}">
      ${back ? V.ib('arrow_back', 'back', 'Back') : ''}
      <div class="titles ${fadeTitle ? 'fade' : ''}">
        <div class="t">${esc(title)}</div>
        ${subtitle ? `<div class="st">${esc(subtitle)}</div>` : ''}
      </div>
      <div class="actions">${actions}</div>
    </header>`;

  V.coverOf = (s) => (V.coverOverride && V.coverOverride[s.id]) || WNA.coverUrl(s);
  V.cover = (s, w = 72, extra = '') => {
    const h = Math.round(w * 1.5);
    const url = V.coverOf(s);
    const inner = url
      ? `<img src="${url}" alt="" loading="lazy" decoding="async" width="${w}" height="${h}">`
      : `<div class="fb" style="background:${WNA.coverFallback(s)}">${esc(V.short(s.title))}</div>`;
    return `<div class="cov" style="width:${w}px;height:${h}px">${inner}${extra}</div>`;
  };
  V.coverFluid = (s, extra = '') => {
    const url = V.coverOf(s);
    const inner = url
      ? `<img src="${url}" alt="" loading="lazy" decoding="async">`
      : `<div class="fb" style="background:${WNA.coverFallback(s)};font-size:13px;line-height:17px;padding:10px">${esc(V.short(s.title))}</div>`;
    return `<div class="cov fluid">${inner}${extra}</div>`;
  };

  V.coverage = (s, cls = '') => {
    const c = WNA.coverage(s);
    const segs = c.segments.map(([a, b]) => `<i class="seg-d" style="left:${(a * 100).toFixed(2)}%;width:${Math.max(0.6, (b - a) * 100).toFixed(2)}%"></i>`).join('');
    const bm = c.bookmark != null ? `<i class="bm" style="left:${(c.bookmark * 100).toFixed(2)}%"></i>` : '';
    return `<div class="cvg ${cls}" role="img" aria-label="${s.downloaded} of ${s.total} chapters downloaded">${segs}${bm}</div>`;
  };

  const PUB = { ongoing: 'Ongoing', completed: 'Completed', hiatus: 'Hiatus', outdated: 'Outdated' };
  V.pubPill = (s) => (PUB[s.pub] ? `<span class="pill ${s.pub}">${PUB[s.pub]}</span>` : '');
  V.pubLabel = (s) => PUB[s.pub] || '';

  V.patreonShort = (s) => {
    if (!s.patreon || !s.patreon.members) return '';
    return s.patreon.usd ? `$${fmt.num(s.patreon.usd)}/mo` : `${fmt.num(s.patreon.members)} patrons`;
  };
  V.metrics = (s) => {
    const series = WNA.trendSeries(s.id);
    const last = (k) => { const x = series.find((v) => v.key === k); return x && x.points.length ? x.points[x.points.length - 1].v : null; };
    return { followers: last('followers'), views: last('total_views') || last('views'), favorites: last('favorites'), pages: last('pages'), series };
  };

  V.stories = () => WNA.stories().filter((s) => !V.state.removed.has(s.id));
  V.tabName = (id) => { const t = WNA.tabs().find((x) => x.id === id); return t ? t.name : 'Unassigned'; };

  V.empty = ({ icon = 'inbox', title, body = '', action = '' }) => `
    <div class="empty">
      <div class="eic">${V.ic(icon)}</div>
      <h3>${esc(title)}</h3>
      ${body ? `<p>${esc(body)}</p>` : ''}
      ${action}
    </div>`;

  // delegated actions within a root: data-act="name" -> handlers[name](el, event)
  V.bind = (root, handlers) => {
    WNA.on(root, 'click', '[data-act]', (e, t) => {
      const fn = handlers[t.dataset.act];
      if (fn) { e.stopPropagation(); fn(t, e); }
      else if (t.dataset.act === 'back') { e.stopPropagation(); WNA.back(); }
    });
  };

  // pointer-based long press (450 ms) on elements matching selector
  V.longPress = (root, selector, fn, ms = 450) => {
    let timer = null, sx = 0, sy = 0, target = null, suppressUntil = 0;
    const clear = () => { clearTimeout(timer); timer = null; };
    root.addEventListener('pointerdown', (e) => {
      const t = e.target.closest(selector);
      if (!t || !root.contains(t) || e.button > 0) return;
      target = t; sx = e.clientX; sy = e.clientY;
      timer = setTimeout(() => {
        timer = null;
        suppressUntil = Date.now() + 700;
        if (navigator.vibrate) try { navigator.vibrate(12); } catch (_) {}
        fn(t, e);
      }, ms);
    });
    root.addEventListener('pointermove', (e) => { if (timer && (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8)) clear(); });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => root.addEventListener(ev, clear));
    // swallow the click that follows a long-press
    root.addEventListener('click', (e) => {
      if (Date.now() < suppressUntil) { suppressUntil = 0; e.stopPropagation(); e.preventDefault(); }
    }, true);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(selector)) e.preventDefault(); });
    void target;
  };

  // elevate app bar when its screen scrolls
  V.watchScroll = (screen, cb) => {
    let raf = 0;
    screen.addEventListener('scroll', () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        screen.classList.toggle('scrolled', screen.scrollTop > 4);
        if (cb) cb(screen.scrollTop);
      });
    }, { passive: true });
  };

  // incremental list rendering (chunks appended as the sentinel approaches)
  V.incremental = (container, scrollRoot, items, renderOne, { chunk = 40, sentinelCls = '' } = {}) => {
    let i = 0;
    const sentinel = document.createElement('div');
    sentinel.className = 'inc-sentinel ' + sentinelCls;
    sentinel.style.height = '1px';
    const io = new IntersectionObserver((ents) => { if (ents.some((e) => e.isIntersecting)) more(); }, { root: scrollRoot, rootMargin: '900px 0px' });
    function more() {
      if (i >= items.length) { io.disconnect(); sentinel.remove(); return; }
      const html = items.slice(i, i + chunk).map(renderOne).join('');
      i += chunk;
      sentinel.insertAdjacentHTML('beforebegin', html);
      if (i >= items.length) { io.disconnect(); sentinel.remove(); }
    }
    container.appendChild(sentinel);
    more();
    io.observe(sentinel);
    return { disconnect: () => io.disconnect(), ensure: (n) => { while (i < Math.min(n, items.length)) more(); } };
  };

  V.subs = () => {
    const list = [];
    return { add: (u) => list.push(u), off: () => list.splice(0).forEach((u) => { try { u(); } catch (_) {} }) };
  };
})();
