/* Library (home), Add Story, Author Novels. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const prefs = WNA.data.prefs || {};
  const L = (V2.lib = {
    tab: prefs.libraryTabId || 'all', query: '', sources: [], tags: [], sort: prefs.librarySortOption || 'default', asc: null,
    view: prefs.libraryViewMode === 'grid' ? 'cover' : 'list', sel: null, contDismissed: false,
  });

  const tabsList = () => {
    const un = WNA.storiesInTab('unassigned').filter((s) => !V2.deleted.has(s.id)).length;
    return [...(un ? [{ id: 'unassigned', name: 'Unassigned', n: un }] : []), ...WNA.tabs().map((t) => ({ ...t, n: WNA.storiesInTab(t.id).filter((s) => !V2.deleted.has(s.id)).length })), { id: 'all', name: 'All', n: WNA.stories().length - V2.deleted.size }];
  };
  const sortLabel = () => { const so = WNA.SORTS[L.sort] || WNA.SORTS.default; const asc = L.asc == null ? so.asc : L.asc; return `${so.short} ${asc ? '↑' : '↓'}`; };

  // ---------- cards ----------
  function badges(s) {
    const f = WNA.forecast(s);
    return `<span class="pill src">${esc(s.source)}</span>${V2.pubLabel[s.pub] ? `<span class="pill ${s.pub}">${V2.pubLabel[s.pub]}</span>` : ''}${s.status === 'unavailable' ? '<span class="pill bad">Source unavailable</span>' : ''}${f.state === 'due' ? `<span class="pill gold" title="Next chapter may be out, sync to check">${ic('update')}May be out</span>` : ''}`;
  }
  V2.listCard = (s, sel) => `<article class="lc tap ${sel && sel.has(s.id) ? 'is-sel' : ''}" data-id="${s.id}">
    <div class="lc-c">${V2.cover(s, { w: 84, h: 126 })}<span class="selmark">${ic('check')}</span></div>
    <div class="lc-b">
      <h3 class="clamp2">${esc(s.title)}</h3>
      <p class="by nowrap">by ${esc(s.author || 'Unknown')}</p>
      <div class="lc-badges">${badges(s)}</div>
      <div class="lc-meta">${s.score ? `<span class="score">${ic('star', 'fill s16')}${esc(s.score)}</span>` : ''}${WNA.patreonLine(s) ? `<span class="nowrap">${esc(WNA.patreonLine(s))}</span>` : ''}</div>
      <p class="tags clamp2">${s.tags.slice(0, 5).map(esc).join(' • ')}</p>
    </div>
    <footer class="lc-f"><span class="t-lm">${s.downloaded} / ${s.total}</span>${V2.coverage(s)}</footer>
  </article>`;
  const gridCard = (s, sel) => {
    const f = WNA.forecast(s);
    return `<article class="gc tap ${sel && sel.has(s.id) ? 'is-sel' : ''}" data-id="${s.id}">
    <div class="gc-c">${V2.cover(s, { cls: 'r' })}${f.state === 'due' ? `<span class="gc-new" title="Next chapter may be out">${ic('update', 's18')}</span>` : ''}${V2.aiCovers[s.id] ? `<span class="gc-ai">${ic('auto_awesome', 'fill s18')}</span>` : ''}<span class="selmark">${ic('check')}</span>
      <div class="gc-p"><i style="width:${(s.total ? s.downloaded / s.total : 0) * 100}%"></i></div></div>
    <h3 class="clamp2">${esc(s.title)}</h3><p class="nowrap">${esc(s.author || '')}</p>
  </article>`;
  };

  // ---------- library screen ----------
  V2.screens.library = (ctx) => {
    const q = ctx.query;
    if (q.view === 'cover' || q.view === 'grid') L.view = 'cover'; else if (q.view === 'list') L.view = 'list';
    if (q.tag) { L.tags = [q.tag]; L.tab = 'all'; }
    if (q.tab) L.tab = q.tab;
    if (q.select) L.sel = new Set();
    if (!tabsList().some((t) => t.id === L.tab)) L.tab = 'all';
    const page = ctx.el;
    page.innerHTML = `<header class="bar"><span style="width:12px"></span><div class="bt"><b>Library</b></div><div class="acts">
        ${V2.ib(L.view === 'list' ? 'grid_view' : 'view_agenda', 'view', L.view === 'list' ? 'Cover view' : 'List view')}
        ${V2.ib('headphones', 'narration', 'Audio generation')}
        <button class="ib" data-a="queue" aria-label="Downloads" title="Downloads">${ic('download')}<span class="dot" hidden></span></button>
        ${V2.ib('settings', 'settings', 'Settings')}</div></header>
      <div class="scroll lib">
        <div class="bar-large"><h1>Library</h1><p class="lib-count"></p></div>
        <div class="wrap"><label class="search">${ic('search')}<input type="search" placeholder="Search stories" value="${esc(L.query)}" enterkeyhint="search">${V2.ib('close', 'clearq', 'Clear search', L.query ? '' : 'hide')}</label></div>
        <nav class="tabs lib-tabs" role="tablist"></nav>
        <div class="wrap lib-chips"><div class="chips scroller"></div></div>
        <div class="wrap lib-cont"></div>
        <div class="wrap lib-list"></div>
        <div class="sentinel"></div>
      </div>
      <button class="fab" data-a="add" aria-label="Add story">${ic('add')}<span class="lbl">Add story</span></button>`;
    const sc = page.querySelector('.scroll'), listEl = page.querySelector('.lib-list'), fab = page.querySelector('.fab');
    let items = [], shown = 0;

    function renderTabs() {
      page.querySelector('.lib-tabs').innerHTML = tabsList().map((t) => `<button class="tab ${t.id === L.tab ? 'sel' : ''}" data-tab="${t.id}" role="tab">${esc(t.name)}<span class="n">${t.n}</span></button>`).join('');
      const s = page.querySelector('.tab.sel'); if (s) s.scrollIntoView({ inline: 'center', block: 'nearest' });
    }
    function renderChips() {
      const base = V2.filter({ tab: L.tab });
      const srcs = WNA.sourceCounts(base);
      const tags = WNA.tagCounts(L.sources.length ? base.filter((s) => L.sources.includes(s.source)) : base);
      const topTags = [...L.tags.map((t) => tags.find((x) => x.name === t) || { name: t, count: 0 }), ...tags.filter((t) => !L.tags.includes(t.name)).slice(0, 10)];
      const active = L.query || L.sources.length || L.tags.length;
      page.querySelector('.lib-chips .chips').innerHTML =
        `<button class="chip solid trail" data-a="sort">${ic('swap_vert')}${sortLabel()}${ic('arrow_drop_down')}</button>` +
        (active ? `<button class="chip" data-a="clearf">${ic('filter_alt_off')}Clear</button>` : '') +
        srcs.map((x) => `<button class="chip ${L.sources.includes(x.name) ? 'sel' : ''}" data-src="${esc(x.name)}">${ic(L.sources.includes(x.name) ? 'check' : 'language', 'chk')}${esc(x.name)} <span class="n">${x.count}</span></button>`).join('') +
        '<span class="chip-div"></span>' +
        topTags.map((x) => `<button class="chip ${L.tags.includes(x.name) ? 'sel' : ''}" data-tag="${esc(x.name)}">${L.tags.includes(x.name) ? ic('check', 'chk') : ''}${esc(x.name)} <span class="n">${x.count}</span></button>`).join('') +
        `<button class="chip trail" data-a="alltags">All tags${ic('arrow_drop_down')}</button>`;
    }
    function renderCont() {
      const box = page.querySelector('.lib-cont');
      const ses = WNA.session; const st = WNA.player.state();
      if (!ses || st.loaded || L.contDismissed || L.sel) { box.innerHTML = ''; return; }
      const s = WNA.story(ses.storyId);
      box.innerHTML = `<div class="cont tap" data-a="cont-open">
        ${V2.cover(s, { w: 56, h: 84 })}
        <div class="grow"><span class="t-lm accent">CONTINUE LISTENING</span><b class="clamp2">${esc(ses.chapterTitle)}</b><span class="dim nowrap">${esc(s.title)} · sentence ${ses.index + 1}</span></div>
        <button class="ib filled big" data-a="cont-play" aria-label="Resume">${ic('play_arrow', 'fill')}</button>
        <button class="ib x" data-a="cont-x" aria-label="Dismiss">${ic('close', 's20')}</button></div>`;
      V2.theme(box.firstElementChild, s);
    }
    function renderList(keepScroll) {
      items = V2.filter({ tab: L.tab, query: L.query, sources: L.sources, tags: L.tags, sort: L.sort, asc: L.asc });
      page.querySelector('.lib-count').textContent = `${V2.plural(WNA.stories().length - V2.deleted.size, 'novel')}${L.tab !== 'all' || items.length !== WNA.stories().length ? ` · ${items.length} shown` : ''}`;
      listEl.className = `wrap lib-list ${L.view === 'cover' ? 'grid' : 'list'}`;
      shown = 0; listEl.innerHTML = '';
      if (!items.length) {
        const filtered = L.query || L.sources.length || L.tags.length;
        listEl.innerHTML = filtered
          ? `<div class="empty"><div class="blob">${ic('search_off')}</div><h3>No matches</h3><p>Try clearing your search or filters.</p><button class="btn tonal" data-a="clearf">Clear filters</button></div>`
          : WNA.stories().length - V2.deleted.size === 0
            ? `<div class="empty"><div class="blob">${ic('auto_stories')}</div><h3>Your library is empty</h3><p>Paste a link from RoyalRoad, Scribble Hub, or a forum to start archiving.</p><button class="btn filled" data-a="add">${ic('add')}Add a story</button></div>`
            : `<div class="empty"><div class="blob">${ic('bookmarks')}</div><h3>Nothing here yet</h3><p>Long-press a novel in another tab and choose Move to put it here.</p></div>`;
        return;
      }
      more();
      if (!keepScroll) { const t = page.querySelector('.lib-tabs'); if (sc.scrollTop > t.offsetTop) sc.scrollTop = t.offsetTop - 4; }
    }
    function more() {
      const n = Math.min(items.length, shown + (L.view === 'cover' ? 42 : 30));
      const html = items.slice(shown, n).map((s) => (L.view === 'cover' ? gridCard(s, L.sel) : V2.listCard(s, L.sel))).join('');
      listEl.insertAdjacentHTML('beforeend', html); shown = n;
    }
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && shown < items.length) more(); }, { rootMargin: '900px' });
    io.observe(page.querySelector('.sentinel'));
    ctx.sub(() => io.disconnect());

    // ---------- selection mode ----------
    let ftb = null;
    function renderSel() {
      const bar = page.querySelector('.bar');
      if (L.sel) {
        bar.classList.add('ctx');
        bar.innerHTML = `${V2.ib('close', 'selx', 'Exit selection')}<div class="bt"><b>${L.sel.size} selected</b></div><div class="acts">${V2.ib('select_all', 'selall', 'Select all')}</div>`;
        listEl.classList.add('selecting'); fab.classList.add('hide');
        if (!ftb) { ftb = el(`<div class="ftb"><button class="btn tonal" data-a="move">${ic('drive_file_move')}Move</button><button class="btn danger" data-a="del">${ic('delete')}Delete</button></div>`); page.appendChild(ftb); }
        ftb.querySelectorAll('.btn').forEach((b) => b.classList.toggle('disabled', !L.sel.size));
      } else {
        bar.classList.remove('ctx');
        bar.innerHTML = `<span style="width:12px"></span><div class="bt"><b>Library</b><small>${V2.plural(WNA.stories().length - V2.deleted.size, 'novel')}</small></div><div class="acts">${V2.ib(L.view === 'list' ? 'grid_view' : 'view_agenda', 'view', L.view === 'list' ? 'Cover view' : 'List view')}${V2.ib('headphones', 'narration', 'Audio generation')}<button class="ib" data-a="queue" aria-label="Downloads">${ic('download')}<span class="dot" hidden></span></button>${V2.ib('settings', 'settings', 'Settings')}</div>`;
        bar.classList.toggle('raised', sc.scrollTop > 56);
        listEl.classList.remove('selecting'); fab.classList.remove('hide');
        if (ftb) { ftb.remove(); ftb = null; }
        dlDot();
      }
    }
    const markSel = () => listEl.querySelectorAll('[data-id]').forEach((c) => c.classList.toggle('is-sel', !!L.sel && L.sel.has(c.dataset.id)));
    function toggleSel(id) { L.sel.has(id) ? L.sel.delete(id) : L.sel.add(id); markSel(); renderSel(); }
    V2.longPress(listEl, '[data-id]', (card) => { if (!L.sel) { L.sel = new Set(); renderCont(); } toggleSel(card.dataset.id); });

    async function moveSel() {
      const ids = [...L.sel];
      const to = await V2.choose({ title: `Move ${V2.plural(ids.length, 'novel')}`, sub: 'Choose a tab', options: [{ value: '__none', label: 'Unassigned', icon: 'inbox' }, ...WNA.tabs().map((t) => ({ value: t.id, label: t.name, icon: 'bookmark', sub: `${WNA.storiesInTab(t.id).length} novels` }))] });
      if (!to) return;
      const prev = ids.map((id) => [id, WNA.story(id).tab]);
      ids.forEach((id) => (WNA.story(id).tab = to === '__none' ? null : to));
      L.sel = null; renderSel(); renderTabs(); renderList(true); renderCont();
      V2.snack(`Moved ${V2.plural(ids.length, 'novel')} to ${to === '__none' ? 'Unassigned' : V2.tabName(to)}`, { action: 'Undo', onAction: () => { prev.forEach(([id, t]) => (WNA.story(id).tab = t)); renderTabs(); renderList(true); } });
    }
    async function delSel() {
      const ids = [...L.sel];
      const ok = await V2.confirm({ title: `Delete ${V2.plural(ids.length, 'novel')}?`, text: 'Downloaded chapters, audio and EPUB files for these novels will be removed from this phone.', ok: 'Delete', danger: true, icon: 'delete' });
      if (!ok) return;
      ids.forEach((id) => V2.deleted.add(id));
      L.sel = null; renderSel(); renderTabs(); renderList(true); renderCont();
      V2.snack(`Deleted ${V2.plural(ids.length, 'novel')}`, { action: 'Undo', onAction: () => { ids.forEach((id) => V2.deleted.delete(id)); renderTabs(); renderList(true); } });
    }

    // ---------- sheets ----------
    function sortSheet() {
      const so = WNA.SORTS[L.sort]; const asc = L.asc == null ? so.asc : L.asc;
      const s = V2.sheet({
        title: 'Sort by',
        body: `<div class="pad" style="padding-bottom:12px"><div class="seg l" data-dir><button class="${asc ? 'sel' : ''}" data-asc="1">${ic('check')}Ascending</button><button class="${!asc ? 'sel' : ''}" data-asc="0">${ic('check')}Descending</button></div></div>
          <div class="seglist">${Object.entries(WNA.SORTS).map(([k, v]) => `<button class="li" data-sort="${k}"><span class="tx"><b>${esc(v.label)}</b></span><span class="radio ${k === L.sort ? 'on' : ''}"></span></button>`).join('')}</div>`,
      });
      s.body.addEventListener('click', (e) => {
        const d = e.target.closest('[data-asc]');
        if (d) { L.asc = d.dataset.asc === '1'; s.body.querySelectorAll('[data-asc]').forEach((b) => b.classList.toggle('sel', b === d)); renderChips(); renderList(); return; }
        const b = e.target.closest('[data-sort]'); if (!b) return;
        L.sort = b.dataset.sort; L.asc = null;
        s.body.querySelectorAll('.radio').forEach((r) => r.classList.toggle('on', r.parentNode === b));
        renderChips(); renderList(); setTimeout(() => s.close(), 200);
      });
    }
    function tagSheet() {
      const base = V2.filter({ tab: L.tab, sources: L.sources });
      const all = WNA.tagCounts(base);
      let picked = new Set(L.tags), qq = '';
      const s = V2.sheet({
        title: 'Tags', sub: 'Novels must have every selected tag.',
        body: `<div class="pad"><label class="search s">${ic('search')}<input placeholder="Find a tag"></label></div><div class="pad chips tagwrap" style="padding-top:14px"></div>`,
        foot: `<button class="btn text" data-t="clear">Clear</button><button class="btn filled" data-t="ok">Show results</button>`,
      });
      const wrap = s.el.querySelector('.tagwrap');
      const draw = () => {
        wrap.innerHTML = all.filter((t) => !qq || t.name.toLowerCase().includes(qq)).map((t) => `<button class="chip ${picked.has(t.name) ? 'sel' : ''}" data-tg="${esc(t.name)}">${picked.has(t.name) ? ic('check', 'chk') : ''}${esc(t.name)} <span class="n">${t.count}</span></button>`).join('') || '<p class="dim">No tags match.</p>';
        const n = V2.filter({ tab: L.tab, query: L.query, sources: L.sources, tags: [...picked] }).length;
        s.el.querySelector('[data-t="ok"]').textContent = `Show ${V2.plural(n, 'novel')}`;
      };
      draw();
      s.el.querySelector('input').addEventListener('input', (e) => { qq = e.target.value.trim().toLowerCase(); draw(); });
      s.el.addEventListener('click', (e) => {
        const c = e.target.closest('[data-tg]'); if (c) { const t = c.dataset.tg; picked.has(t) ? picked.delete(t) : picked.add(t); draw(); return; }
        const b = e.target.closest('[data-t]'); if (!b) return;
        if (b.dataset.t === 'clear') { picked = new Set(); draw(); return; }
        L.tags = [...picked]; renderChips(); renderList(); s.close();
      });
    }

    // ---------- events ----------
    page.addEventListener('click', (e) => {
      const card = e.target.closest('[data-id]');
      if (card && listEl.contains(card)) {
        if (L.sel) { toggleSel(card.dataset.id); return; }
        const s = WNA.story(card.dataset.id); const cov = card.querySelector('.cov');
        if (cov) V2.heroFrom(cov, s);
        V2.go('/story/' + s.id); return;
      }
      const t = e.target.closest('[data-tab]');
      if (t) { setTab(t.dataset.tab); return; }
      const sc2 = e.target.closest('[data-src]');
      if (sc2) { const n = sc2.dataset.src; L.sources = L.sources.includes(n) ? L.sources.filter((x) => x !== n) : [...L.sources, n]; renderChips(); renderList(); return; }
      const tg = e.target.closest('[data-tag]');
      if (tg) { const n = tg.dataset.tag; L.tags = L.tags.includes(n) ? L.tags.filter((x) => x !== n) : [...L.tags, n]; renderChips(); renderList(); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const ses = WNA.session;
      switch (a.dataset.a) {
        case 'view': L.view = L.view === 'list' ? 'cover' : 'list'; V2.setQuery({ view: L.view }); renderSel(); renderList(true); break;
        case 'narration': V2.go('/narration'); break;
        case 'queue': V2.go('/queue'); break;
        case 'settings': V2.go('/settings'); break;
        case 'add': V2.go('/add'); break;
        case 'sort': sortSheet(); break;
        case 'alltags': tagSheet(); break;
        case 'clearf': L.query = ''; L.sources = []; L.tags = []; page.querySelector('.search input').value = ''; page.querySelector('[data-a="clearq"]').classList.add('hide'); renderChips(); renderList(); break;
        case 'clearq': L.query = ''; page.querySelector('.search input').value = ''; a.classList.add('hide'); renderChips(); renderList(); break;
        case 'cont-play': WNA.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); break;
        case 'cont-open': WNA.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); V2.go('/player'); break;
        case 'cont-x': L.contDismissed = true; renderCont(); V2.snack('Continue listening hidden', { action: 'Undo', onAction: () => { L.contDismissed = false; renderCont(); } }); break;
        case 'selx': L.sel = null; markSel(); renderSel(); renderCont(); break;
        case 'selall': { const all = items.map((s) => s.id); L.sel = L.sel.size === all.length ? new Set() : new Set(all); markSel(); renderSel(); break; }
        case 'move': moveSel(); break;
        case 'del': delSel(); break;
      }
    });
    const inp = page.querySelector('.search input');
    inp.addEventListener('input', WNA.debounce(() => { L.query = inp.value; page.querySelector('[data-a="clearq"]').classList.toggle('hide', !L.query); renderChips(); renderList(true); }, 160));

    function setTab(id, dir) {
      if (id === L.tab) return;
      const order = tabsList().map((t) => t.id);
      const d = dir || (order.indexOf(id) > order.indexOf(L.tab) ? 1 : -1);
      L.tab = id; renderTabs(); renderChips(); renderList();
      listEl.animate([{ transform: `translateX(${36 * d}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }], { duration: 320, easing: 'cubic-bezier(.05,.7,.1,1)' });
    }
    // swipe between tabs
    let sx = null, sy = null;
    listEl.addEventListener('pointerdown', (e) => { if (e.pointerType === 'mouse') return; sx = e.clientX; sy = e.clientY; });
    listEl.addEventListener('pointerup', (e) => {
      if (sx == null) return; const dx = e.clientX - sx, dy = e.clientY - sy; sx = null;
      if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.8 && !L.sel) {
        const order = tabsList().map((t) => t.id); const i = order.indexOf(L.tab) + (dx < 0 ? 1 : -1);
        if (i >= 0 && i < order.length) setTab(order[i], dx < 0 ? 1 : -1);
      }
    });
    listEl.addEventListener('pointercancel', () => { sx = null; });

    // shrink the extended FAB on scroll down; raise the bar
    V2.collapse(page, { onScroll: (y, d) => { if (Math.abs(d) > 4) fab.classList.toggle('shrink', d > 0 && y > 80); } });

    // downloads activity dot
    const dlDot = () => { const d = page.querySelector('[data-a="queue"] .dot'); if (d) d.hidden = !WNA.downloads.state().downloading.length; };
    ctx.sub(WNA.downloads.on(dlDot));
    ctx.sub(WNA.player.on((st) => { if (!!st.loaded !== renderCont.loaded) { renderCont.loaded = !!st.loaded; renderCont(); } }));
    ctx.onShow = () => { renderTabs(); renderChips(); renderCont(); renderList(true); dlDot(); };

    renderTabs(); renderChips(); renderCont(); renderSel(); renderList(true);
  };

  // ---------- Add story ----------
  const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return ''; } };
  V2.screens.add = (ctx) => {
    let tab = L.tab !== 'all' && L.tab !== 'unassigned' ? L.tab : (WNA.tabs()[0] || {}).id;
    const page = ctx.el;
    page.innerHTML = `${V2.bar({ title: 'Add story' })}
      <div class="scroll"><div class="maxw">
        ${V2.large('Add story', 'Paste a story URL to import')}
        <div class="wrap"><div class="card add-card">
          <label class="tf"><label>Story URL</label><input type="url" inputmode="url" placeholder="https://www.royalroad.com/fiction/…" data-url><span class="trail">${V2.ib('content_paste', 'paste', 'Paste')}</span></label>
          <p class="tf-err err t-bs" hidden></p>
          <button class="tf dd" data-a="tab"><label>Save to tab</label><span class="val">${esc(V2.tabName(tab))}</span><span class="trail">${ic('arrow_drop_down')}</span></button>
          <div class="add-go"><button class="btn filled l block" data-a="fetch">${ic('cloud_download')}Fetch story</button></div>
        </div></div>
        <div class="sec-h"><h2>Or browse a source</h2></div>
        <div class="seglist">${WNA.sources.map((s, i) => `<button class="li" data-src="${i}"><span class="lead src-av" style="--h:${(i * 57 + 30) % 360}">${esc(s.name[0])}</span><span class="tx"><b>${esc(s.name)}</b><span>${esc(s.host)}</span></span><span class="tr t-ll accent">Open${ic('arrow_forward', 's18')}</span></button>`).join('')}</div>
      </div></div>`;
    V2.collapse(page);
    const url = page.querySelector('[data-url]'), err = page.querySelector('.tf-err'), go = page.querySelector('.add-go');
    let busy = false;
    async function fetchStory() {
      const u = url.value.trim(); const host = hostOf(u);
      const src = WNA.sources.find((s) => host && host.endsWith(s.host.replace(/^forums\./, '')));
      if (!u) { err.textContent = 'Paste a link to a story page first.'; err.hidden = false; return; }
      if (!src) { err.textContent = 'That site isn’t supported yet. Try one of the sources below.'; err.hidden = false; return; }
      err.hidden = true; busy = true;
      const m = u.match(/fiction\/(\d+)/); const pool = WNA.stories().filter((s) => s.source === src.name && s.cover);
      const s = (m && WNA.story('rr_' + m[1])) || pool[WNA.hash(u) % Math.max(1, pool.length)] || WNA.story('rr_164525');
      const steps = ['Starting…', `Opening ${src.host}…`, 'Reading story details…', `Found ${s.total} chapters`, 'Saving cover…'];
      go.innerHTML = `<div class="fetching"><span class="loader l accent"></span><div class="grow"><b class="t-tm">Fetching…</b><p class="muted step">${steps[0]}</p>${V2.wavy(0)}</div></div>`;
      for (let i = 0; i < steps.length; i++) { go.querySelector('.step').textContent = steps[i]; V2.setWavy(go.querySelector('.wavy'), (i + 1) / steps.length); await V2.wait(650); if (!page.isConnected) return; }
      s.tab = tab; V2.deleted.delete(s.id);
      V2.replace('/story/' + s.id + '?added=' + encodeURIComponent(V2.tabName(tab)));
    }
    page.addEventListener('click', async (e) => {
      const srcB = e.target.closest('[data-src]');
      if (srcB) { V2.snack(`Opening ${WNA.sources[+srcB.dataset.src].host} in the in-app browser…`); return; }
      const a = e.target.closest('[data-a]'); if (!a || busy) return;
      if (a.dataset.a === 'paste') { url.value = 'https://www.royalroad.com/fiction/164525/'; err.hidden = true; url.animate([{ background: 'var(--secondary-container)' }, { background: 'var(--sc-high)' }], 600); }
      if (a.dataset.a === 'tab') { const v = await V2.choose({ title: 'Save to tab', value: tab, options: WNA.tabs().map((t) => ({ value: t.id, label: t.name, icon: 'bookmark' })) }); if (v) { tab = v; a.querySelector('.val').textContent = V2.tabName(v); } }
      if (a.dataset.a === 'fetch') fetchStory();
    });
    url.addEventListener('keydown', (e) => { if (e.key === 'Enter') fetchStory(); });
  };

  // ---------- Author novels ----------
  V2.screens.author = (ctx) => {
    const s = WNA.story(ctx.params.id); const page = ctx.el;
    if (!s) { page.innerHTML = `${V2.bar({ title: 'Author' })}<div class="scroll"><div class="empty"><h3>Novel not found</h3></div></div>`; return; }
    const list = WNA.authorNovels(s.id).filter((x) => !V2.deleted.has(x.id));
    page.innerHTML = `${V2.bar({ title: s.author, sub: 'Author' })}<div class="scroll"><div class="maxw">
      ${V2.large(s.author || 'Unknown author', list.length ? `${V2.plural(list.length, 'other novel')} in your library` : 'No other novels in your library')}
      <div class="wrap"><button class="author-from tap" data-id="${s.id}">${V2.cover(s, { w: 40, h: 60 })}<span class="grow"><span class="t-lm dim">FROM</span><b class="nowrap">${esc(s.title)}</b></span>${ic('chevron_right')}</button></div>
      <div class="wrap lib-list list" style="margin-top:16px">${list.length ? list.map((x) => V2.listCard(x)).join('') : `<div class="empty"><div class="blob">${ic('person_search')}</div><h3>Just this one</h3><p>When you add more novels by ${esc(s.author)}, they’ll show up here.</p></div>`}</div>
    </div></div>`;
    V2.collapse(page);
    page.addEventListener('click', (e) => { const c = e.target.closest('[data-id]'); if (c) { const cov = c.querySelector('.cov'); if (cov) V2.heroFrom(cov, WNA.story(c.dataset.id)); V2.go('/story/' + c.dataset.id); } });
  };
})();
