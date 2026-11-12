/* v1 Refined — Library (home). */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;
  const S = V.state;

  const TREND = { up: '<span class="trend up" aria-label="trending up">↑</span>', down: '<span class="trend down" aria-label="trending down">↓</span>' };

  V.listCard = (s, { sel = false, selMode = false } = {}) => {
    const fc = WNA.forecast(s);
    const tr = WNA.scoreTrend(s.id);
    const meta = [
      V.pubPill(s),
      s.score ? `<span class="score">${V.ic('star', 'xs')}<b class="tnum">${esc(s.score)}</b>${tr ? TREND[tr] : ''}</span>` : '',
      fc.state === 'due' ? `<span class="due">${V.ic('sync', 'xs')}New chapter?</span>` : '',
      V.patreonShort(s) ? `<span class="pat">${V.ic('volunteer_activism', 'xs')}${esc(V.patreonShort(s))}</span>` : '',
    ].filter(Boolean).join('');
    const bmLabel = s.bookmark != null ? `<span class="bml">${V.ic('bookmark', 'xs')}Ch. ${s.bookmark + 1}</span>` : '';
    return `
      <article class="lc ${sel ? 'sel' : ''} ${selMode ? 'selmode' : ''}" data-id="${s.id}" tabindex="0">
        <div class="lc-top">
          ${V.cover(s, 72, `<span class="selck">${V.ic('check')}</span>`)}
          <div class="lc-info">
            <h3 class="lc-t clamp2">${esc(s.title)}</h3>
            <div class="lc-by ell">${esc(s.author || 'Unknown author')}<span class="dot">·</span><span class="src">${esc(s.source)}</span></div>
            <div class="lc-meta">${meta}</div>
            ${s.tags.length ? `<div class="lc-tags ell">${s.tags.slice(0, 5).map(esc).join('<i>·</i>')}</div>` : ''}
          </div>
        </div>
        <div class="lc-foot">
          <span class="cnt tnum"><b>${fmt.int(s.downloaded)}</b> / ${fmt.int(s.total)}</span>
          ${V.coverage(s, 'thin grow')}
          ${bmLabel}
        </div>
      </article>`;
  };

  V.coverCard = (s, { sel = false, selMode = false } = {}) => {
    const fc = WNA.forecast(s);
    const ov = `${fc.state === 'due' ? `<span class="cc-due" title="Next chapter may be out">${V.ic('sync', 'xs')}</span>` : ''}<span class="selck">${V.ic('check')}</span>`;
    return `
      <article class="cc ${sel ? 'sel' : ''} ${selMode ? 'selmode' : ''}" data-id="${s.id}" tabindex="0">
        ${V.coverFluid(s, ov)}
        <h3 class="cc-t clamp2">${esc(s.title)}</h3>
        <div class="cc-a ell">${esc(s.author || '')}</div>
        <div class="cc-f">${V.coverage(s, 'thin grow')}<span class="tnum">${fmt.int(s.downloaded)}/${fmt.int(s.total)}</span></div>
      </article>`;
  };

  V.screens.library = (r) => {
    if (r.query.view === 'cover' || r.query.view === 'list') S.view = r.query.view;
    if (r.query.tab) S.tab = r.query.tab;
    const sel = new Set();
    let selMode = r.query.select === '1';
    let inc = null;

    const el = WNA.el(`
      <div class="screen lib">
        <div class="sticky-head">
          <div class="bar-slot"></div>
          <nav class="tabs" role="tablist"></nav>
          <div class="wrap wide searchrow">
            <label class="search grow">${V.ic('search')}<input type="search" placeholder="Search title or author" value="${esc(S.query)}" aria-label="Search stories"><button class="ib clr" data-act="clearq" aria-label="Clear search" hidden>${V.ic('close')}</button></label>
            <button class="sortbtn" data-act="sort" aria-label="Sort"></button>
          </div>
        </div>
        <div class="wrap wide lib-body">
          <div class="chip-scroll fchips"></div>
          <div class="cont-slot"></div>
          <div class="resline"></div>
          <div class="grid"></div>
        </div>
        <div class="pad-bottom"></div>
        <button class="fab" data-act="add" aria-label="Add story">${V.ic('add')}</button>
      </div>`);
    const $ = (q) => el.querySelector(q);
    const input = $('.search input');

    const tabList = () => {
      const st = V.stories();
      const un = st.filter((s) => !s.tab || !WNA.tabs().some((t) => t.id === s.tab)).length;
      const list = [];
      if (un) list.push({ id: 'unassigned', name: 'Unassigned', n: un });
      WNA.tabs().forEach((t) => list.push({ id: t.id, name: t.name, n: st.filter((s) => s.tab === t.id).length }));
      list.push({ id: 'all', name: 'All', n: st.length });
      return list;
    };
    const current = () => WNA.filterStories({ tab: S.tab, query: S.query, sources: S.sources, tags: S.tags, sort: S.sort, asc: S.asc }).filter((s) => !S.removed.has(s.id));
    const base = () => WNA.filterStories({ tab: S.tab }).filter((s) => !S.removed.has(s.id));
    const filtersOn = () => !!(S.query || S.sources.length || S.tags.length);

    function renderBar() {
      const slot = $('.bar-slot');
      if (selMode) {
        slot.innerHTML = `
          <header class="appbar contextual">
            ${V.ib('close', 'selx', 'Exit selection')}
            <div class="titles"><div class="t">${sel.size} selected</div></div>
            <div class="actions">${V.ib('select_all', 'selall', 'Select all')}</div>
          </header>`;
        return;
      }
      const dl = WNA.downloads.state();
      const active = dl.downloading.length > 0;
      slot.innerHTML = V.appBar({
        title: 'Library', subtitle: `${fmt.int(V.stories().length)} novels`, back: false, large: true,
        actions:
          V.ib(S.view === 'list' ? 'grid_view' : 'view_agenda', 'view', S.view === 'list' ? 'Cover view' : 'List view') +
          V.ib('headphones', 'narration', 'Audio generation') +
          V.ib('download', 'queue', 'Downloads', { extra: active ? '<i class="dot"></i>' : '' }) +
          V.ib('settings', 'settings', 'Settings'),
      });
    }

    function renderTabs() {
      $('.tabs').innerHTML = tabList().map((t) => `<button class="tab ${t.id === S.tab ? 'on' : ''}" role="tab" aria-selected="${t.id === S.tab}" data-act="tab" data-id="${t.id}">${esc(t.name)}<span class="n">${t.n}</span></button>`).join('');
      const on = $('.tab.on');
      if (on) on.scrollIntoView({ block: 'nearest', inline: 'center' });
    }

    function renderSort() {
      const so = WNA.SORTS[S.sort];
      const asc = S.asc == null ? so.asc : S.asc;
      $('.sortbtn').innerHTML = `${V.ic('swap_vert', 'sm')}<span>${esc(so.short)}</span>${V.ic(asc ? 'arrow_upward' : 'arrow_downward', 'xs')}`;
    }

    function renderChips() {
      const list = base();
      const srcs = WNA.sourceCounts(list);
      const tags = WNA.tagCounts(list);
      const chip = (kind, name, n, on) => `<button class="chip ${on ? 'on' : ''}" data-act="chip" data-kind="${kind}" data-name="${esc(name)}">${on ? V.ic('check') : kind === 'src' ? V.ic('public') : ''}${esc(name)}<span class="n">${n}</span></button>`;
      const selTags = S.tags.map((t) => chip('tag', t, (tags.find((x) => x.name === t) || {}).count || 0, true));
      const html = [
        filtersOn() ? `<button class="chip clear" data-act="clearf">${V.ic('filter_alt_off')}Clear</button>` : '',
        ...srcs.map((x) => chip('src', x.name, x.count, S.sources.includes(x.name))),
        '<span class="vsep"></span>',
        ...selTags,
        ...tags.filter((x) => !S.tags.includes(x.name)).slice(0, 18).map((x) => chip('tag', x.name, x.count, false)),
        `<button class="chip" data-act="alltags">${V.ic('sell')}All tags<span class="n">${tags.length}</span></button>`,
      ].join('');
      const sc = $('.fchips'); const left = sc.scrollLeft; sc.innerHTML = html; sc.scrollLeft = left;
    }

    function renderContinue() {
      const slot = $('.cont-slot');
      const ss = WNA.session;
      if (!ss || S.continueDismissed || WNA.player.state().loaded) { slot.innerHTML = ''; return; }
      const s = WNA.story(ss.storyId);
      slot.innerHTML = `
        <div class="cont" data-act="resume" role="button" tabindex="0">
          ${V.cover(s, 40)}
          <div class="grow">
            <div class="cont-l">${V.ic('headphones', 'xs')}Continue listening</div>
            <div class="cont-t ell">${esc(ss.chapterTitle)}</div>
            <div class="cont-s ell">${esc(s.title)} · sentence ${ss.index + 1}</div>
          </div>
          <span class="cont-play">${V.ic('play_arrow', 'fill')}</span>
          ${V.ib('close', 'contx', 'Dismiss', { cls: 'sm' })}
        </div>`;
    }

    function renderGrid() {
      if (inc) inc.disconnect();
      const list = current();
      const grid = $('.grid');
      grid.className = `grid ${S.view}`;
      grid.innerHTML = '';
      const total = base().length;
      $('.resline').innerHTML = filtersOn()
        ? `<span><b class="tnum">${list.length}</b> of ${total} novels</span><button class="btn text sm" data-act="clearf">Clear filters</button>`
        : `<span class="tnum">${V.plural(list.length, 'novel')}</span>`;
      if (!list.length) {
        if (!V.stories().length) grid.innerHTML = V.empty({ icon: 'auto_stories', title: 'Your library is empty', body: 'Paste a story link from RoyalRoad, Scribble Hub, AO3 and more to start archiving.', action: V.btn('Add a story', 'add', { kind: 'filled', icon: 'add' }) });
        else if (filtersOn()) grid.innerHTML = V.empty({ icon: 'search_off', title: 'No matches', body: 'Try clearing your search or filters.', action: V.btn('Clear filters', 'clearf', { kind: 'tonal' }) });
        else grid.innerHTML = V.empty({ icon: 'bookmarks', title: 'Nothing here yet', body: 'Long-press novels in another tab and choose Move to file them here.' });
        return;
      }
      const render = S.view === 'list' ? V.listCard : V.coverCard;
      inc = V.incremental(grid, el, list, (s) => render(s, { sel: sel.has(s.id), selMode }), { chunk: S.view === 'list' ? 24 : 36 });
    }

    function refreshSelUI() {
      renderBar();
      el.querySelectorAll('.grid [data-id]').forEach((c) => { c.classList.toggle('sel', sel.has(c.dataset.id)); c.classList.toggle('selmode', selMode); });
      $('.fab').classList.toggle('hide', selMode);
      let bar = el.querySelector('.botbar');
      if (selMode && !bar) {
        bar = WNA.el(`<div class="botbar fixed selbar">${V.btn('Move', 'move', { kind: 'tonal', icon: 'drive_file_move', cls: 'grow' })}${V.btn('Delete', 'del', { kind: 'danger', icon: 'delete', cls: 'grow' })}</div>`);
        el.appendChild(bar);
      } else if (!selMode && bar) bar.remove();
      if (bar) bar.querySelectorAll('.btn').forEach((b) => b.toggleAttribute('disabled', sel.size === 0));
      renderContinue();
    }
    function enterSel(id) { selMode = true; if (id) sel.add(id); refreshSelUI(); }
    function exitSel() { selMode = false; sel.clear(); refreshSelUI(); }

    function all() { renderBar(); renderTabs(); renderSort(); renderChips(); renderContinue(); renderGrid(); }
    all();
    V.refreshLibrary = all;

    // ---------- interactions ----------
    const debounced = WNA.debounce(() => { renderChips(); renderGrid(); }, 140);
    input.addEventListener('input', () => { S.query = input.value; $('.clr').hidden = !S.query; debounced(); });
    $('.clr').hidden = !S.query;

    V.bind(el, {
      view() { S.view = S.view === 'list' ? 'cover' : 'list'; renderBar(); renderGrid(); },
      narration: () => WNA.go('/narration'),
      queue: () => WNA.go('/queue'),
      settings: () => WNA.go('/settings'),
      add: () => WNA.go('/add'),
      tab(t) { if (S.tab === t.dataset.id) return; S.tab = t.dataset.id; S.sources = []; S.tags = []; renderTabs(); renderChips(); renderGrid(); el.scrollTo({ top: 0 }); },
      clearq() { S.query = ''; input.value = ''; $('.clr').hidden = true; renderChips(); renderGrid(); input.focus(); },
      clearf() { S.query = ''; input.value = ''; S.sources = []; S.tags = []; $('.clr').hidden = true; renderChips(); renderGrid(); },
      chip(t) {
        const arr = t.dataset.kind === 'src' ? S.sources : S.tags;
        const i = arr.indexOf(t.dataset.name);
        if (i >= 0) arr.splice(i, 1); else arr.push(t.dataset.name);
        renderChips(); renderGrid();
      },
      alltags() { openTags(); },
      sort() { openSort(); },
      resume() { const ss = WNA.session; WNA.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); renderContinue(); },
      contx() { S.continueDismissed = true; renderContinue(); V.toast('Session kept in Voice & Speech', { action: 'Undo', onAction: () => { S.continueDismissed = false; renderContinue(); } }); },
      selx: exitSel,
      selall() { const l = current(); if (sel.size === l.length) sel.clear(); else l.forEach((s) => sel.add(s.id)); refreshSelUI(); },
      move() { openMove(); },
      async del() {
        const n = sel.size;
        const ok = await V.confirm(`Delete ${V.plural(n, 'novel')}?`, 'Their downloaded chapters, EPUBs and generated audio will be removed from this phone.', 'Delete');
        if (!ok) return;
        const ids = [...sel];
        ids.forEach((id) => S.removed.add(id));
        exitSel(); all();
        V.toast(`Deleted ${V.plural(n, 'novel')}`, { action: 'Undo', onAction: () => { ids.forEach((id) => S.removed.delete(id)); all(); } });
      },
    });

    // card taps + long-press selection
    WNA.on(el, 'click', '.grid [data-id]', (e, t) => {
      const id = t.dataset.id;
      if (selMode) { sel.has(id) ? sel.delete(id) : sel.add(id); if (!sel.size) exitSel(); else refreshSelUI(); return; }
      WNA.go('/story/' + id);
    });
    el.addEventListener('keydown', (e) => { const t = e.target.closest('.grid [data-id]'); if (t && e.key === 'Enter') t.click(); });
    V.longPress(el, '.grid [data-id]', (t) => { if (!selMode) enterSel(t.dataset.id); else { sel.add(t.dataset.id); refreshSelUI(); } });

    // swipe between tabs
    let sx = null, sy = 0;
    const body = $('.lib-body');
    body.addEventListener('pointerdown', (e) => { if (e.target.closest('.chip-scroll') || e.pointerType === 'mouse') { sx = null; return; } sx = e.clientX; sy = e.clientY; });
    body.addEventListener('pointerup', (e) => {
      if (sx == null) return;
      const dx = e.clientX - sx, dy = e.clientY - sy; sx = null;
      if (Math.abs(dx) < 80 || Math.abs(dy) > Math.abs(dx) * 0.5 || selMode) return;
      const tl = tabList(); const i = tl.findIndex((t) => t.id === S.tab);
      const n = tl[i + (dx < 0 ? 1 : -1)];
      if (n) { S.tab = n.id; S.sources = []; S.tags = []; renderTabs(); renderChips(); renderGrid(); el.scrollTo({ top: 0 }); }
    });

    V.watchScroll(el);

    function openSort() {
      const so = WNA.SORTS[S.sort];
      const asc = S.asc == null ? so.asc : S.asc;
      V.sheet({
        title: 'Sort by',
        body: `
          <div class="sh-b-in">
            ${Object.entries(WNA.SORTS).map(([k, v]) => `<button class="mi" data-k="${k}"><span class="radio ${k === S.sort ? 'on' : ''}"></span><span class="grow">${esc(v.label)}</span></button>`).join('')}
            <div style="padding:12px 24px 4px"><div class="seg" role="radiogroup">
              <button data-dir="asc" class="${asc ? 'on' : ''}">${V.ic('arrow_upward', 'sm')}Ascending</button>
              <button data-dir="desc" class="${!asc ? 'on' : ''}">${V.ic('arrow_downward', 'sm')}Descending</button>
            </div></div>
          </div>`,
        onMount(sh, close) {
          WNA.on(sh, 'click', '[data-k]', (e, t) => { S.sort = t.dataset.k; S.asc = null; renderSort(); renderGrid(); close(); });
          WNA.on(sh, 'click', '[data-dir]', (e, t) => { S.asc = t.dataset.dir === 'asc'; sh.querySelectorAll('[data-dir]').forEach((b) => b.classList.toggle('on', b === t)); renderSort(); renderGrid(); setTimeout(close, 180); });
        },
      });
    }

    function openTags() {
      const tags = WNA.tagCounts(base());
      const row = (x) => `<button class="mi" data-tag="${esc(x.name)}"><span class="check ${S.tags.includes(x.name) ? 'on' : ''}">${V.ic('check')}</span><span class="grow">${esc(x.name)}</span><span class="tr tnum">${x.count}</span></button>`;
      V.sheet({
        title: 'Filter by tags', sub: 'Novels must have every selected tag.',
        body: `<div style="padding:0 16px 8px"><label class="search">${V.ic('search')}<input type="search" placeholder="Find a tag" class="tagq"></label></div><div class="taglist">${tags.map(row).join('')}</div>`,
        footer: `${V.btn('Clear', 'tclear', { kind: 'text' })}${V.btn('Done', 'tdone', { kind: 'filled' })}`,
        onMount(sh, close) {
          const q = sh.querySelector('.tagq');
          q.addEventListener('input', () => { const v = q.value.toLowerCase(); sh.querySelector('.taglist').innerHTML = tags.filter((x) => x.name.toLowerCase().includes(v)).map(row).join(''); });
          WNA.on(sh, 'click', '[data-tag]', (e, t) => { const n = t.dataset.tag; const i = S.tags.indexOf(n); if (i >= 0) S.tags.splice(i, 1); else S.tags.push(n); t.querySelector('.check').classList.toggle('on', i < 0); renderChips(); renderGrid(); });
          WNA.on(sh, 'click', '[data-act="tclear"]', () => { S.tags = []; sh.querySelectorAll('.check').forEach((c) => c.classList.remove('on')); renderChips(); renderGrid(); });
          WNA.on(sh, 'click', '[data-act="tdone"]', () => close());
        },
      });
    }

    function openMove() {
      const n = sel.size;
      const items = [{ act: '', icon: 'inbox', label: 'Unassigned' }, ...WNA.tabs().map((t) => ({ act: t.id, icon: 'folder', label: t.name, on: [...sel].every((id) => WNA.story(id).tab === t.id) }))];
      V.menu({
        title: `Move ${V.plural(n, 'novel')}`, sub: 'Choose a tab', items,
        onPick(tabId) {
          const prev = [...sel].map((id) => [id, WNA.story(id).tab]);
          prev.forEach(([id]) => (WNA.story(id).tab = tabId || null));
          exitSel(); all();
          V.toast(`Moved ${V.plural(n, 'novel')} to ${tabId ? V.tabName(tabId) : 'Unassigned'}`, { action: 'Undo', onAction: () => { prev.forEach(([id, t]) => (WNA.story(id).tab = t)); all(); } });
        },
      });
    }

    const subs = V.subs();
    subs.add(WNA.player.on(() => renderContinue()));
    let lastActive = null;
    subs.add(WNA.downloads.on((st) => { const a = st.downloading.length > 0; if (a !== lastActive && !selMode) { lastActive = a; renderBar(); } }));

    return {
      el, keep: true, fab: true, title: 'Library',
      onShow() { if (V.libDirty) { V.libDirty = false; all(); } else { renderBar(); renderContinue(); } },
      destroy: () => subs.off(),
    };
  };
})();
