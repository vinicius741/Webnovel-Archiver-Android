/* v0 Library (home) + Add Story. */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon;
  const prefs = (W.data && W.data.prefs) || {};
  const L = {
    tab: prefs.libraryTabId || 'all', query: '', sources: [], tags: [], sort: prefs.librarySortOption || 'default', asc: null,
    view: prefs.libraryViewMode === 'cover' ? 'cover' : 'list', filtersOpen: false, sel: null, continueDismissed: false,
  };
  const PAGE = 40;

  function tabList() {
    const tabs = W.tabs().map((t) => ({ id: t.id, name: t.name }));
    const un = W.storiesInTab('unassigned').length;
    return [...(un ? [{ id: 'unassigned', name: `Unassigned (${un})` }] : []), ...tabs, { id: 'all', name: 'All' }];
  }
  const filtersActive = () => !!(L.query || L.sources.length || L.tags.length);
  const currentList = () => W.filterStories({ tab: L.tab, query: L.query, sources: L.sources, tags: L.tags, sort: L.sort, asc: L.asc });

  function listCard(s) {
    const f = W.forecast(s);
    const pat = A.patreonLine(s);
    const sel = L.sel && L.sel.has(s.id);
    return `<article class="lcard${sel ? ' selected' : ''}" data-id="${s.id}">
      <div class="lc-main">
        <div class="lc-cover">${A.cover(s, 'c-list')}${L.sel ? `<span class="selcheck${sel ? ' on' : ''}">${sel ? ic('check') : ''}</span>` : ''}</div>
        <div class="lc-info">
          <h3>${esc(s.title)}</h3>
          <div class="lc-by">by ${esc(s.author || 'Unknown')}</div>
          <div class="lc-badges"><span class="lc-src">${esc(s.source)}</span>${A.pubPill(s)}${s.unavailable ? '<span class="pill warn">Source unavailable</span>' : ''}${f.state === 'due' ? `<span class="lc-due" title="Next chapter may be out, sync to check">${ic('refresh')}</span>` : ''}</div>
          ${s.score ? `<div class="lc-score">${ic('star')}<b>${A.score(s)}</b></div>` : ''}
          ${pat ? `<div class="lc-meta">${esc(pat)}</div>` : ''}
          ${s.tags.length ? `<div class="lc-tags">${s.tags.slice(0, 5).map(esc).join('<i> &nbsp;•&nbsp; </i>')}</div>` : ''}
        </div>
      </div>
      <div class="lc-foot"><b>${s.downloaded} / ${s.total}</b>${A.capsule(s)}</div>
    </article>`;
  }
  function gridCard(s) {
    const f = W.forecast(s);
    const sel = L.sel && L.sel.has(s.id);
    const ai = W.ai.state(s.id).inUse !== 'src';
    return `<article class="gcard${sel ? ' selected' : ''}" data-id="${s.id}">
      <div class="gc-cover">${A.cover(s, 'c-grid')}
        ${f.state === 'due' ? `<span class="gc-badge">${ic('refresh')}</span>` : ''}
        ${ai ? `<span class="gc-badge ai">${ic('auto_awesome')}</span>` : ''}
        ${L.sel ? `<span class="selcheck${sel ? ' on' : ''}">${sel ? ic('check') : ''}</span>` : ''}</div>
      <h3>${esc(s.title)}</h3>
      <div class="gc-by">${esc(s.author || '')}</div>
      <div class="lc-foot"><b>${s.downloaded} / ${s.total}</b>${A.capsule(s)}</div>
    </article>`;
  }

  function render(route, ctx) {
    if (route.query.view) L.view = route.query.view === 'cover' ? 'cover' : 'list';
    if (route.query.filters) L.filtersOpen = true;
    if (route.query.tab) L.tab = route.query.tab;
    if (route.query.select && !L.sel) L.sel = new Set(currentList().slice(0, 2).map((s) => s.id));
    const el = W.el('<div class="lib"></div>');
    let list = [], shown = 0, io = null;

    function bar() {
      if (L.sel) {
        return `<header class="appbar"><button class="icon-btn" data-a="selclose" aria-label="Close">${ic('close')}</button>
          <div class="ab-title" style="padding-left:4px"><h1>${L.sel.size} selected</h1></div>
          <button class="btn text" data-a="selall">Select all</button></header>`;
      }
      return A.appBar({ title: 'Library', sub: `${W.stories().length} novels`, back: false, actions: [
        { id: 'view', icon: L.view === 'list' ? 'grid_view' : 'format_list_bulleted', label: L.view === 'list' ? 'Cover view' : 'List view', o: L.view === 'list' },
        { id: 'narration', icon: 'headphones', label: 'Narration' },
        { id: 'downloads', icon: 'download', label: 'Downloads' },
        { id: 'settings', icon: 'settings', label: 'Settings' },
      ] });
    }
    function tabsHtml() {
      return `<nav class="tabs" role="tablist">${tabList().map((t) => `<button class="tab${t.id === L.tab ? ' on' : ''}" data-tab="${t.id}" role="tab">${esc(t.name)}</button>`).join('')}</nav>`;
    }
    function filtersHtml() {
      const base = W.filterStories({ tab: L.tab, query: L.query, sort: L.sort });
      const srcs = W.sourceCounts(base), tags = W.tagCounts(base).slice(0, 40);
      const so = W.SORTS[L.sort] || W.SORTS.default;
      const asc = L.asc == null ? so.asc : L.asc;
      return `<div class="filters">
        <div class="f-head" data-a="ftoggle"><span>Filters${filtersActive() ? ' •' : ''}</span>${filtersActive() ? '<button class="btn text sm" data-a="fclear">Clear filters</button>' : ''}<span class="f-chev">${ic(L.filtersOpen ? 'expand_less' : 'expand_more')}</span></div>
        ${L.filtersOpen ? `<div class="f-body">
          <div class="f-search"><div class="field"><input data-a="q" placeholder="Search stories" value="${esc(L.query)}" enterkeyhint="search"></div>
            <button class="sort-pill" data-a="sort">${ic(asc ? 'arrow_upward' : 'arrow_downward')}<span>${so.short} ${asc ? '↑' : '↓'}</span></button></div>
          <div class="chip-scroll f-chips">
            ${srcs.map((c) => `<button class="chip tonal${L.sources.includes(c.name) ? ' sel' : ''}" data-src="${esc(c.name)}">${ic(L.sources.includes(c.name) ? 'check' : 'language')}${esc(c.name)} (${c.count})</button>`).join('')}
            ${tags.map((c) => `<button class="chip${L.tags.includes(c.name) ? ' sel' : ''}" data-tag="${esc(c.name)}">${L.tags.includes(c.name) ? ic('check') : ''}${esc(c.name)} (${c.count})</button>`).join('')}
          </div></div>` : ''}
      </div>`;
    }
    function continueHtml() {
      const ses = W.session;
      if (!ses || L.continueDismissed || W.player.state().loaded || L.sel) return '';
      return `<div class="continue" data-a="continue">${ic('play_arrow')}<div><b>Continue listening: ${esc(ses.storyTitle)} · ${esc(ses.chapterTitle)}</b></div><button class="icon-btn" data-a="continue-x" aria-label="Dismiss">${ic('close')}</button></div>`;
    }
    function bodyHtml() {
      list = currentList();
      if (!W.stories().length) return `<div class="empty"><div class="disc">${ic('menu_book')}</div><b>Your library is empty</b><span>Add stories to start archiving.</span><button class="btn filled" data-a="add" style="margin-top:16px">Add a story</button></div>`;
      if (!list.length) return filtersActive() ? `<div class="empty"><div class="disc">${ic('search')}</div><b>No matches</b><span>Try clearing your search or filters.</span></div>` : `<div class="empty"><div class="disc">${ic('inbox')}</div><b>Nothing here yet</b><span>Move novels into this tab from the library.</span></div>`;
      return `<div class="${L.view === 'list' ? 'lgrid' : 'cgrid'}" data-grid></div><div class="sentinel" data-sentinel></div>`;
    }
    function more() {
      const grid = el.querySelector('[data-grid]'); if (!grid) return;
      const next = list.slice(shown, shown + PAGE); shown += next.length;
      grid.insertAdjacentHTML('beforeend', next.map(L.view === 'list' ? listCard : gridCard).join(''));
      if (shown >= list.length && io) io.disconnect();
    }
    function draw(keepScroll) {
      const sc = el.querySelector('.scroll'); const y = sc ? sc.scrollTop : 0;
      el.innerHTML = `${bar()}${L.sel ? '' : tabsHtml()}<div class="scroll lib-scroll pb-mini" data-keep>${L.sel ? '' : filtersHtml()}${continueHtml()}${bodyHtml()}</div>
        ${L.sel ? `<div class="bottom-bar"><button class="btn tonal" data-a="move">${ic('drive_file_move')}Move</button><button class="btn filled-danger" data-a="delete">${ic('delete')}Delete</button></div>` : `<button class="fab" data-a="add" aria-label="Add story">${ic('add')}</button>`}`;
      shown = 0; more();
      if (io) io.disconnect();
      const sent = el.querySelector('[data-sentinel]');
      if (sent) { io = new IntersectionObserver((e) => { if (e[0].isIntersecting) { const need = Math.max(shown + PAGE, 0); while (shown < need && shown < list.length) more(); } }, { root: el.querySelector('.scroll'), rootMargin: '800px' }); io.observe(sent); }
      if (keepScroll) { const s2 = el.querySelector('.scroll'); const target = y; while (shown < list.length && s2.scrollHeight < target + 2000) more(); s2.scrollTop = target; }
      const t = el.querySelector('.tab.on'); if (t) t.scrollIntoView({ block: 'nearest', inline: 'center' });
      el.classList.toggle('selecting', !!L.sel);
    }
    function redrawCards() {
      el.querySelectorAll('[data-id]').forEach((c) => {
        const on = L.sel && L.sel.has(c.dataset.id);
        c.classList.toggle('selected', !!on);
        const chk = c.querySelector('.selcheck'); if (chk) { chk.classList.toggle('on', !!on); chk.innerHTML = on ? ic('check') : ''; }
      });
      const h = el.querySelector('.appbar h1'); if (h && L.sel) h.textContent = `${L.sel.size} selected`;
    }
    function enterSel(id) { L.sel = new Set([id]); draw(true); }
    function exitSel() { L.sel = null; draw(true); }

    const searchDeb = W.debounce(() => { const sc = el.querySelector('.scroll'); const body = sc.querySelectorAll('.lgrid,.cgrid,.sentinel,.empty,.continue'); body.forEach((n) => n.remove()); sc.insertAdjacentHTML('beforeend', continueHtml() + bodyHtml()); shown = 0; more(); const sent = el.querySelector('[data-sentinel]'); if (sent && io) { io.disconnect(); io.observe(sent); } const fh = el.querySelector('.f-head span'); if (fh) fh.textContent = 'Filters' + (filtersActive() ? ' •' : ''); }, 180);

    el.addEventListener('input', (e) => { if (e.target.matches('[data-a="q"]')) { L.query = e.target.value; searchDeb(); } });
    el.addEventListener('click', (e) => {
      const a = e.target.closest('[data-a]'); const act = a && a.dataset.a;
      const tab = e.target.closest('[data-tab]');
      if (tab) { L.tab = tab.dataset.tab; draw(); el.querySelector('.scroll').scrollTop = 0; return; }
      const src = e.target.closest('[data-src]'); if (src) { const n = src.dataset.src; L.sources = L.sources.includes(n) ? L.sources.filter((x) => x !== n) : [...L.sources, n]; draw(true); return; }
      const tg = e.target.closest('[data-tag]'); if (tg) { const n = tg.dataset.tag; L.tags = L.tags.includes(n) ? L.tags.filter((x) => x !== n) : [...L.tags, n]; draw(true); return; }
      const card = e.target.closest('[data-id]');
      if (card && !act) { if (L.sel) { const id = card.dataset.id; L.sel.has(id) ? L.sel.delete(id) : L.sel.add(id); if (!L.sel.size) exitSel(); else redrawCards(); } else W.go('/story/' + card.dataset.id); return; }
      if (!act) return;
      if (act === 'view') { L.view = L.view === 'list' ? 'cover' : 'list'; draw(); }
      else if (act === 'narration') W.go('/narration');
      else if (act === 'downloads') W.go('/queue');
      else if (act === 'settings') W.go('/settings');
      else if (act === 'add') W.go('/add');
      else if (act === 'fclear') { e.stopPropagation(); L.query = ''; L.sources = []; L.tags = []; draw(); }
      else if (act === 'ftoggle') { if (e.target.closest('[data-a="fclear"]')) return; L.filtersOpen = !L.filtersOpen; draw(true); }
      else if (act === 'sort') sortDialog();
      else if (act === 'continue-x') { e.stopPropagation(); L.continueDismissed = true; a.closest('.continue').remove(); }
      else if (act === 'continue') { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); draw(true); }
      else if (act === 'selclose') exitSel();
      else if (act === 'selall') { list.forEach((s) => L.sel.add(s.id)); while (shown < list.length) more(); redrawCards(); }
      else if (act === 'move') moveDialog();
      else if (act === 'delete') deleteDialog();
    });
    A.longPress(el, '[data-id]', (card) => { if (!L.sel) enterSel(card.dataset.id); else { L.sel.add(card.dataset.id); redrawCards(); } });

    // swipe between tabs
    let sx = null, sy = null;
    el.addEventListener('touchstart', (e) => { if (L.sel || e.target.closest('.chip-scroll,.tabs')) { sx = null; return; } sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
    el.addEventListener('touchend', (e) => {
      if (sx == null) return; const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy; sx = null;
      if (Math.abs(dx) > 90 && Math.abs(dy) < 50) { const ts = tabList(); const i = ts.findIndex((t) => t.id === L.tab); const n = ts[i + (dx < 0 ? 1 : -1)]; if (n) { L.tab = n.id; draw(); } }
    });

    function sortDialog() {
      const so = W.SORTS[L.sort]; const asc = L.asc == null ? so.asc : L.asc;
      const body = `${Object.entries(W.SORTS).map(([k, v]) => `<button class="dg-item" data-sort="${k}"><span class="radio${k === L.sort ? ' on' : ''}"></span>${esc(v.label)}</button>`).join('')}
        <div class="dg-div"></div><div class="chip-row"><button class="chip${asc ? ' sel' : ''}" data-dir="1">${asc ? ic('check') : ''}Ascending</button><button class="chip${!asc ? ' sel' : ''}" data-dir="0">${!asc ? ic('check') : ''}Descending</button></div>`;
      const d = A.dialog({ title: 'Sort by', body, actions: [{ label: 'Close' }] });
      d.el.addEventListener('click', (e) => {
        const s = e.target.closest('[data-sort]'); const dr = e.target.closest('[data-dir]');
        if (s) { L.sort = s.dataset.sort; L.asc = null; d.close(); draw(); }
        if (dr) { L.asc = dr.dataset.dir === '1'; d.close(); draw(); }
      });
    }
    function moveDialog() {
      const n = L.sel.size;
      const opts = [{ value: '', label: 'Unassigned' }, ...W.tabs().map((t) => ({ value: t.id, label: t.name }))];
      A.choose({ title: `Move ${n} Novel${n === 1 ? '' : 's'}`, options: opts, value: null, fn: (v) => {
        L.sel.forEach((id) => { const s = W.story(id); if (s) s.tab = v || null; });
        const name = (opts.find((o) => o.value === v) || {}).label;
        A.toast(`Moved ${n} novel${n === 1 ? '' : 's'} to ${name}`); exitSel();
      } });
    }
    function deleteDialog() {
      const n = L.sel.size;
      A.confirm({ title: `Delete ${n} novel${n === 1 ? '' : 's'}?`, message: 'This removes the downloaded chapters, audio, and EPUB files from this device.', ok: 'Delete', danger: true, fn: () => {
        const all = W.stories(); L.sel.forEach((id) => { const i = all.findIndex((s) => s.id === id); if (i >= 0) all.splice(i, 1); });
        A.toast(`Deleted ${n} novel${n === 1 ? '' : 's'}`); exitSel();
      } });
    }

    ctx.sub(W.player.on(W.debounce(() => { const c = el.querySelector('.continue'); if (c && W.player.state().loaded) c.remove(); }, 50)));
    draw();
    return { el, destroy: () => io && io.disconnect() };
  }
  A.register('library', render);

  // ---------- Add Story ----------
  A.register('add', (route) => {
    const tabs = W.tabs();
    let tabId = (tabs[0] || {}).id, busy = false;
    const el = W.el(`<div>${A.appBar({ title: 'Add Story', sub: 'Paste a story URL to import' })}
      <div class="scroll pb-mini"><div class="pad add">
        <div class="add-url"><div class="field"><input data-url placeholder="Story URL" inputmode="url" value="${esc(route.query.url || '')}"></div><button class="paste" data-a="paste" aria-label="Paste">${ic('content_paste')}</button></div>
        <div class="sec-label sm">Save to tab</div>
        <button class="field dropdown" data-a="tab"><span data-tabname>${esc((tabs[0] || {}).name || 'Unassigned')}</span>${ic('expand_more')}</button>
        <div data-fetch><button class="btn filled full" data-a="fetch" style="margin-top:20px">${ic('download')}Fetch Story</button></div>
        <div class="fetch-status muted" data-status></div>
        <div class="sec-label sm" style="margin-top:28px">Or browse a source</div>
        ${W.sources.map((s) => `<button class="src-row" data-src-open="${esc(s.name)}"><span class="globe">${ic('language')}</span><div><b>${esc(s.name)}</b><span>${esc(s.host)}</span></div><span class="open">Open ${ic('arrow_forward')}</span></button>`).join('')}
      </div></div></div>`);
    const urlIn = el.querySelector('[data-url]');
    el.addEventListener('click', (e) => {
      const a = e.target.closest('[data-a]'); const so = e.target.closest('[data-src-open]');
      if (so) { A.toast(`Opening ${so.dataset.srcOpen} in the in-app browser…`); return; }
      if (!a) return;
      if (a.dataset.a === 'paste') { urlIn.value = 'https://www.royalroad.com/fiction/141704/gunwitch-litrpg'; A.toast('Pasted from clipboard'); }
      if (a.dataset.a === 'tab') A.choose({ title: 'Save to tab', options: tabs.map((t) => ({ value: t.id, label: t.name })), value: tabId, fn: (v) => { tabId = v; el.querySelector('[data-tabname]').textContent = tabs.find((t) => t.id === v).name; } });
      if (a.dataset.a === 'fetch') fetchStory();
    });
    function fetchStory() {
      if (busy) return;
      const url = urlIn.value.trim();
      if (!/^https?:\/\//.test(url)) { A.toast('Enter a valid story URL'); return; }
      busy = true;
      const box = el.querySelector('[data-fetch]'); const st = el.querySelector('[data-status]');
      box.innerHTML = `<button class="btn filled full disabled" style="margin-top:20px"><span class="spinner"></span>Fetching...</button>`;
      const steps = ['Starting...', 'Loading story page...', 'Reading chapter list (210 chapters)...', 'Saving cover...', 'Done'];
      let i = 0; st.textContent = steps[0];
      const tick = () => { i++; if (i < steps.length) { st.textContent = steps[i]; setTimeout(tick, 700); } else { const m = url.match(/fiction\/(\d+)/); const id = m && W.story('rr_' + m[1]) ? 'rr_' + m[1] : 'rr_141704'; A.toast('Story added'); W.go('/story/' + id, { replace: true }); } };
      setTimeout(tick, 700);
    }
    return { el };
  });
})();
