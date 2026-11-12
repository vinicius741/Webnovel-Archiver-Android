/* v5-feed Library (search-first browsing), Add Story, Author novels. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const L = { tab: 'all', query: '', sources: [], tags: [], smart: null, sort: 'default', asc: null, view: 'cover', sel: null };
  A.lib = L;
  const SMART = [['due', 'May be out', 'sync'], ['partial', 'Not fully offline', 'cloud_download'], ['ongoing', 'Ongoing', 'trending_flat'], ['completed', 'Completed', 'task_alt']];

  function baseList() { return A.filter({ tab: L.tab, query: L.query, sort: L.sort, asc: L.asc }); }
  function applyRest(list) {
    let out = list;
    if (L.sources.length) out = out.filter((s) => L.sources.includes(s.source));
    if (L.tags.length) out = out.filter((s) => L.tags.every((t) => s.tags.includes(t)));
    if (L.smart === 'due') out = out.filter((s) => A.forecast(s).state === 'due');
    if (L.smart === 'partial') out = out.filter((s) => s.downloaded < s.total);
    if (L.smart === 'ongoing') out = out.filter((s) => s.pub === 'ongoing');
    if (L.smart === 'completed') out = out.filter((s) => s.pub === 'completed');
    return out;
  }
  const activeCount = () => L.sources.length + L.tags.length + (L.smart ? 1 : 0);

  function tabsHtml() {
    const tabs = W.tabs();
    const un = A.filter({ tab: 'unassigned' }).length;
    const items = [...(un ? [['unassigned', 'Unassigned', un]] : []), ...tabs.map((t) => [t.id, t.name, A.filter({ tab: t.id }).length]), ['all', 'All', A.stories().length]];
    return `<div class="tabbar">${items.map(([id, n, c]) => `<button class="${L.tab === id ? 'on' : ''}" data-tab="${id}">${esc(n)} <small>${c}</small></button>`).join('')}</div>`;
  }
  function pillsHtml() {
    const base = baseList();
    const so = W.SORTS[L.sort];
    const asc = L.asc == null ? so.asc : L.asc;
    const srcs = W.sourceCounts(base).slice(0, 5), tags = W.tagCounts(base).slice(0, 10);
    const n = activeCount();
    return `<div class="chips">
      <button class="chip strong" data-sortpill>${A.ic(asc ? 'arrow_upward' : 'arrow_downward')}${esc(so.short)}</button>
      <button class="chip ${n ? 'on' : ''}" data-filters>${A.ic('tune')}Filters${n ? ` <small>${n}</small>` : ''}</button><span class="sep"></span>
      ${SMART.map(([k, l, ic]) => `<button class="chip ${L.smart === k ? 'on' : ''}" data-smart="${k}">${A.ic(ic)}${l}</button>`).join('')}<span class="sep"></span>
      ${srcs.map((s) => `<button class="chip ${L.sources.includes(s.name) ? 'on' : ''}" data-src="${esc(s.name)}">${A.ic('public')}${esc(s.name)} <small>${s.count}</small></button>`).join('')}
      ${tags.map((t) => `<button class="chip ${L.tags.includes(t.name) ? 'on' : ''}" data-tag="${esc(t.name)}">${esc(t.name)} <small>${t.count}</small></button>`).join('')}</div>`;
  }
  function gridCard(s) {
    const f = A.forecast(s);
    const prog = s.bookmark != null ? (s.bookmark + 1) / Math.max(1, s.total) : 0;
    const sel = L.sel && L.sel.has(s.id);
    const pb = prog ? `<span class="gc-p"><i style="width:${prog * 100}%"></i></span>` : '';
    return `<button class="gc ${sel ? 'selected' : ''}" data-sid="${s.id}">${A.cover(s).replace(/<\/div>$/, pb + '</div>')}
      <span class="gc-b">${f.state === 'due' ? `<i title="Next chapter may be out">${A.ic('sync')}</i>` : ''}${A.aiCover && A.aiCover[s.id] ? `<i class="ai">${A.ic('auto_awesome', 'f')}</i>` : ''}</span>
      <span class="selbox">${A.ic('check')}</span>
      <b>${esc(A.short(s.title))}</b><small>${esc(s.author)} · ${s.downloaded}/${s.total}</small></button>`;
  }
  function listCard(s) {
    const f = A.forecast(s);
    const sel = L.sel && L.sel.has(s.id);
    const tr = W.scoreTrend(s.id);
    return `<button class="lc ${sel ? 'selected' : ''}" data-sid="${s.id}">${A.cover(s)}<span class="selbox">${A.ic('check')}</span>
      <span class="lc-t"><b>${esc(s.title)}</b><small>by ${esc(s.author)}</small>
        <span class="lc-badges"><span class="src">${esc(s.source)}</span>${A.pubPill(s)}${f.state === 'due' ? `<span class="pill p-warn">${A.ic('sync')}May be out</span>` : ''}${s.score ? `<span class="score">${A.ic('star', 'f')}${esc(s.score)}${tr ? A.ic(tr === 'up' ? 'north_east' : 'south_east') : ''}</span>` : ''}</span>
        ${W.patreonLine(s) ? `<span class="lc-pat">${esc(W.patreonLine(s))}</span>` : ''}
        <span class="lc-tags">${esc(s.tags.slice(0, 5).join(' • '))}</span>
        <span class="lc-cov"><span>${s.downloaded} / ${s.total}</span>${A.coverageBar(s)}</span></span></button>`;
  }
  function contHtml() {
    const ss = W.session;
    if (!ss || A.dismissedContinue || W.player.state().loaded || L.sel) return '';
    const s = W.story(ss.storyId);
    return `<div class="cont">${A.ambBg(s)}<button class="cont-b" data-cont>${A.cover(s)}<span><small>Continue listening</small><b>${esc(A.short(s.title))} · ${esc(ss.chapterTitle)}</b></span></button>
      <button class="ibtn solid sm" data-cont aria-label="Resume">${A.ic('play_arrow', 'f')}</button><button class="ibtn sm" data-contx aria-label="Dismiss">${A.ic('close')}</button></div>`;
  }
  function itemsHtml() {
    const list = applyRest(baseList());
    const tabTotal = A.filter({ tab: L.tab }).length;
    let body;
    if (!A.stories().length) body = A.empty('library_add', 'Your library is empty', 'Add a story from RoyalRoad, Scribble Hub and more.', `<button class="btn primary" data-go="/add">${A.ic('add')}Add a story</button>`);
    else if (!tabTotal) body = A.empty('inbox', 'Nothing here yet', 'Move novels into this tab from selection mode.');
    else if (!list.length) body = A.empty('search_off', 'No matches', 'Try clearing your search or filters.', `<button class="btn tonal" data-clear>Clear filters</button>`);
    else body = L.view === 'cover' ? `<div class="grid">${list.map(gridCard).join('')}</div>` : `<div class="list">${list.map(listCard).join('')}</div>`;
    const filtered = activeCount() || L.query;
    return `${contHtml()}<div class="lib-meta"><span>${list.length} ${list.length === 1 ? 'novel' : 'novels'}${filtered ? ' match' : ''}</span>${filtered ? '<button data-clear>Clear filters</button>' : ''}</div>${body}`;
  }

  A.screens.library = (ctx) => {
    const q = ctx.query;
    if (q.tab) L.tab = q.tab;
    if (q.smart) { L.smart = q.smart; }
    if (q.view) L.view = q.view === 'list' ? 'list' : 'cover';
    if (q.q != null) L.query = q.q;
    L.sel = q.select ? new Set(A.filter({ tab: L.tab }).slice(0, 3).map((s) => s.id)) : null;
    A.tint(ctx.el, W.session ? W.story(W.session.storyId) : null);
    ctx.el.innerHTML = `<div class="sc lib"><div class="lib-head">
        <div class="lib-title"><h1>Library<small>${A.stories().length}</small></h1>
          <button class="ibtn" data-view aria-label="Toggle view">${A.ic(L.view === 'cover' ? 'view_agenda' : 'grid_view')}</button>
          <button class="ibtn" data-go="/add" aria-label="Add story">${A.ic('add')}</button>
          <button class="ibtn" data-libmore aria-label="More">${A.ic('more_vert')}</button></div>
        <div class="lib-search"><label class="search">${A.ic('search')}<input type="search" placeholder="Search titles and authors" value="${esc(L.query)}" enterkeyhint="search"><button class="ibtn sm" data-qx aria-label="Clear search" ${L.query ? '' : 'hidden'}>${A.ic('close')}</button></label></div>
        <div class="lib-tabs">${tabsHtml()}</div><div class="lib-pills">${pillsHtml()}</div></div>
      <div class="lib-items">${itemsHtml()}</div></div>
      <div class="seltop" hidden><button class="ibtn" data-selx aria-label="Exit selection">${A.ic('close')}</button><b class="selcount"></b><button class="btn ghost sm" data-selall>Select all</button></div>
      <div class="selbar glass"><button class="btn glassy" data-move>${A.ic('drive_file_move')}Move</button><button class="btn danger" data-del>${A.ic('delete')}Delete</button></div>`;
    const el = ctx.el, sc = el.querySelector('.sc'), head = el.querySelector('.lib-head'), items = el.querySelector('.lib-items');
    const input = el.querySelector('input[type=search]');
    const renderItems = () => { items.innerHTML = itemsHtml(); syncSel(); };
    const renderPills = () => { el.querySelector('.lib-pills').innerHTML = pillsHtml(); };
    const renderAll = () => { const tb = el.querySelector('.tabbar'); const sl = tb ? tb.scrollLeft : 0; el.querySelector('.lib-tabs').innerHTML = tabsHtml(); el.querySelector('.tabbar').scrollLeft = sl; renderPills(); renderItems(); };
    function syncSel() {
      const on = !!L.sel;
      sc.classList.toggle('selecting', on);
      document.body.classList.toggle('selecting-lib', on);
      el.querySelector('.seltop').hidden = !on;
      el.querySelector('.selbar').classList.toggle('in', on);
      if (on) el.querySelector('.selcount').textContent = `${L.sel.size} selected`;
    }
    ctx.on(() => document.body.classList.remove('selecting-lib'));
    syncSel();
    const onTab = () => { const b = el.querySelector('.tabbar .on'); if (!b) return; const p = b.parentNode; p.scrollLeft += b.getBoundingClientRect().left - p.getBoundingClientRect().left - 60; };
    setTimeout(onTab, 30);
    sc.addEventListener('scroll', () => head.classList.toggle('scrolled', sc.scrollTop > 30), { passive: true });
    if (q.focus) setTimeout(() => input.focus(), 380);
    const doSearch = W.debounce(() => { renderPills(); renderItems(); }, 110);
    input.addEventListener('input', () => { L.query = input.value; el.querySelector('[data-qx]').hidden = !L.query; doSearch(); });

    const toggleSel = (id) => { if (L.sel.has(id)) L.sel.delete(id); else L.sel.add(id); const c = items.querySelector(`[data-sid="${id}"]`); if (c) c.classList.toggle('selected', L.sel.has(id)); if (!L.sel.size) L.sel = null; syncSel(); };
    A.longPress(items, '[data-sid]', (c) => { if (!L.sel) { L.sel = new Set(); const cont = items.querySelector('.cont'); if (cont) cont.remove(); } if (!L.sel.has(c.dataset.sid)) toggleSel(c.dataset.sid); else syncSel(); });

    // swipe between tabs
    let sx = null, sy = 0;
    items.addEventListener('pointerdown', (e) => { if (e.pointerType === 'mouse') return; sx = e.clientX; sy = e.clientY; });
    items.addEventListener('pointerup', (e) => {
      if (sx == null) return; const dx = e.clientX - sx, dy = e.clientY - sy; sx = null;
      if (Math.abs(dx) < 80 || Math.abs(dx) < Math.abs(dy) * 2 || L.sel) return;
      const ids = [...el.querySelectorAll('[data-tab]')].map((b) => b.dataset.tab); const i = ids.indexOf(L.tab);
      const ni = Math.max(0, Math.min(ids.length - 1, i + (dx < 0 ? 1 : -1))); if (ni === i) return;
      L.tab = ids[ni]; renderAll(); items.animate([{ opacity: 0, transform: `translateX(${dx < 0 ? 40 : -40}px)` }, { opacity: 1, transform: 'none' }], { duration: 320, easing: 'cubic-bezier(.2,.8,.2,1)' });
      el.querySelector('.tabbar .on').scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
    });

    el.addEventListener('click', (e) => {
      const t = e.target;
      const card = t.closest('[data-sid]');
      if (card) { if (L.sel) toggleSel(card.dataset.sid); else A.go('/story/' + card.dataset.sid); return; }
      if (t.closest('[data-tab]')) { L.tab = t.closest('[data-tab]').dataset.tab; renderAll(); sc.scrollTo({ top: 0 }); return; }
      if (t.closest('[data-smart]')) { const k = t.closest('[data-smart]').dataset.smart; L.smart = L.smart === k ? null : k; renderPills(); renderItems(); return; }
      if (t.closest('[data-src]')) { const k = t.closest('[data-src]').dataset.src; L.sources = L.sources.includes(k) ? L.sources.filter((x) => x !== k) : [...L.sources, k]; renderPills(); renderItems(); return; }
      if (t.closest('[data-tag]')) { const k = t.closest('[data-tag]').dataset.tag; L.tags = L.tags.includes(k) ? L.tags.filter((x) => x !== k) : [...L.tags, k]; renderPills(); renderItems(); return; }
      if (t.closest('[data-clear]')) { L.sources = []; L.tags = []; L.smart = null; L.query = ''; input.value = ''; el.querySelector('[data-qx]').hidden = true; renderPills(); renderItems(); return; }
      if (t.closest('[data-qx]')) { L.query = ''; input.value = ''; t.closest('[data-qx]').hidden = true; renderPills(); renderItems(); input.focus(); return; }
      if (t.closest('[data-view]')) { L.view = L.view === 'cover' ? 'list' : 'cover'; t.closest('[data-view]').innerHTML = A.ic(L.view === 'cover' ? 'view_agenda' : 'grid_view'); renderItems(); A.toast(L.view === 'cover' ? 'Cover view' : 'List view', { icon: L.view === 'cover' ? 'grid_view' : 'view_agenda' }); return; }
      if (t.closest('[data-sortpill]')) return sortSheet(() => { renderPills(); renderItems(); });
      if (t.closest('[data-filters]')) return filterSheet(() => { renderPills(); renderItems(); });
      if (t.closest('[data-libmore]')) return A.menu({ title: 'Library', items: [{ icon: 'add', label: 'Add story', fn: () => A.go('/add') }, { icon: 'checklist', label: 'Select novels', fn: () => { L.sel = new Set(); renderItems(); } }, { icon: 'tab', label: 'Manage tabs', fn: () => A.go('/settings/tabs') }, { icon: 'settings', label: 'Settings', fn: () => A.go('/settings') }] });
      if (t.closest('[data-cont]')) { const ss = W.session; W.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); renderItems(); A.toast('Resuming ' + A.short(ss.storyTitle), { icon: 'headphones' }); return; }
      if (t.closest('[data-contx]')) { A.dismissedContinue = true; renderItems(); A.toast('Continue card hidden', { action: 'Undo', fn: () => { A.dismissedContinue = false; renderItems(); } }); return; }
      if (t.closest('[data-selx]')) { L.sel = null; renderItems(); return; }
      if (t.closest('[data-selall]')) { const all = applyRest(baseList()).map((s) => s.id); L.sel = L.sel && L.sel.size === all.length ? new Set() : new Set(all); renderItems(); return; }
      if (t.closest('[data-move]')) {
        const ids = [...L.sel];
        return A.menu({ title: `Move ${ids.length} ${ids.length === 1 ? 'novel' : 'novels'}`, sub: 'Choose a tab', items: [{ icon: 'inbox', label: 'Unassigned', fn: () => mv(null, 'Unassigned') }, ...W.tabs().map((tb) => ({ icon: 'tab', label: tb.name, sub: `${A.filter({ tab: tb.id }).length} novels`, fn: () => mv(tb.id, tb.name) }))] });
        function mv(tabId, name) { const prev = ids.map((id) => [id, W.story(id).tab]); ids.forEach((id) => (W.story(id).tab = tabId)); L.sel = null; renderAll(); A.toast(`Moved ${ids.length} to ${name}`, { icon: 'drive_file_move', action: 'Undo', fn: () => { prev.forEach(([id, tb]) => (W.story(id).tab = tb)); renderAll(); } }); }
      }
      if (t.closest('[data-del]')) {
        const ids = [...L.sel];
        A.confirm({ title: `Delete ${ids.length} ${ids.length === 1 ? 'novel' : 'novels'}?`, body: 'Downloaded chapters, audio and EPUB files for these novels are removed from this phone.', ok: 'Delete', danger: true }).then((ok) => {
          if (!ok) return; ids.forEach((id) => A.deleted.add(id)); L.sel = null; renderAll();
          A.toast(`Deleted ${ids.length} ${ids.length === 1 ? 'novel' : 'novels'}`, { icon: 'delete', action: 'Undo', fn: () => { ids.forEach((id) => A.deleted.delete(id)); renderAll(); } });
        });
      }
    });
  };

  function sortSheet(done) {
    const so = () => W.SORTS[L.sort], asc = () => (L.asc == null ? so().asc : L.asc);
    const html = () => `<div class="mlist">${Object.entries(W.SORTS).map(([k, v]) => `<button class="mi ${L.sort === k ? 'on' : ''}" data-so="${k}">${A.ic({ default: 'auto_awesome', title: 'sort_by_alpha', updated: 'update', added: 'event', chapters: 'format_list_numbered', score: 'star', patreonUsd: 'paid', patreonMembers: 'group' }[k])}<span><b>${v.label}</b></span>${L.sort === k ? A.ic('check', 'mi-chk') : ''}</button>`).join('')}</div>
      <div style="padding:14px 8px 4px">${A.seg([['asc', `${A.ic('arrow_upward')} Ascending`], ['desc', `${A.ic('arrow_downward')} Descending`]], asc() ? 'asc' : 'desc', 'data-dir')}</div>`;
    const s = A.sheet({ title: 'Sort by', html: html() });
    s.el.addEventListener('click', (e) => {
      const o = e.target.closest('[data-so]'), d = e.target.closest('[data-dir]');
      if (o) { L.sort = o.dataset.so; L.asc = null; }
      else if (d) L.asc = d.dataset.dir === 'asc';
      else return;
      s.set(html()); done();
    });
  }
  function filterSheet(done) {
    const base = baseList();
    const html = () => `<div class="fs-sec"><h4>Smart</h4>${A.chips(SMART.map(([k, l]) => [k, l]), L.smart, 'data-fsmart', 'wrapchips')}</div>
      <div class="fs-sec"><h4>Sources</h4><div class="chips wrapchips">${W.sourceCounts(base).map((x) => `<button class="chip ${L.sources.includes(x.name) ? 'on' : ''}" data-fsrc="${esc(x.name)}">${A.ic('public')}${esc(x.name)} <small>${x.count}</small></button>`).join('')}</div></div>
      <div class="fs-sec"><h4>Tags · all must match</h4><div class="chips wrapchips">${W.tagCounts(base).slice(0, 60).map((x) => `<button class="chip ${L.tags.includes(x.name) ? 'on' : ''}" data-ftag="${esc(x.name)}">${esc(x.name)} <small>${x.count}</small></button>`).join('')}</div></div>
      <div class="fs-foot"><button class="btn outline" data-fclear>Clear</button><button class="btn primary" data-dismiss>Show ${applyRest(base).length} novels</button></div>`;
    const s = A.sheet({ title: 'Filters', sub: 'Narrow the current tab', html: html(), onClose: done });
    s.el.addEventListener('click', (e) => {
      const t = e.target; let hit = true;
      if (t.closest('[data-fsmart]')) { const k = t.closest('[data-fsmart]').dataset.fsmart; L.smart = L.smart === k ? null : k; }
      else if (t.closest('[data-fsrc]')) { const k = t.closest('[data-fsrc]').dataset.fsrc; L.sources = L.sources.includes(k) ? L.sources.filter((x) => x !== k) : [...L.sources, k]; }
      else if (t.closest('[data-ftag]')) { const k = t.closest('[data-ftag]').dataset.ftag; L.tags = L.tags.includes(k) ? L.tags.filter((x) => x !== k) : [...L.tags, k]; }
      else if (t.closest('[data-fclear]')) { L.smart = null; L.sources = []; L.tags = []; }
      else hit = false;
      if (hit) { const top = s.body.scrollTop; s.set(html()); s.body.scrollTop = top; }
    });
  }

  // ---------- Add story ----------
  const SRC_COL = { RoyalRoad: '#d9822b', 'Scribble Hub': '#3f8fd8', SpaceBattles: '#4f6bd1', 'Sufficient Velocity': '#2e9d8f', 'FanFiction.net': '#2a4e8a', 'Archive of Our Own': '#a32424' };
  A.screens.add = (ctx) => {
    const tabs = W.tabs();
    let tab = (tabs.find((t) => /plan/i.test(t.name)) || tabs[0] || {}).id || null;
    A.tint(ctx.el, null);
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Add story', sub: 'Paste a story URL to import', back: '/library' })}
      <div class="narrow"><div class="add-hero">
        <label class="search">${A.ic('link')}<input id="url" type="url" inputmode="url" placeholder="Story URL" value="${esc(ctx.query.url || '')}"><button class="btn tonal sm" data-paste>${A.ic('content_paste')}Paste</button></label>
        <div><p class="eyebrow" style="color:var(--t2);margin:4px 0 10px">Save to tab</p>${A.chips([[null, 'Unassigned'], ...tabs.map((t) => [t.id, esc(t.name)])], tab, 'data-st')}</div>
        <div class="add-run"><button class="btn primary block" data-fetch>${A.ic('cloud_download')}Fetch story</button></div>
      </div>
      <div class="sec-h" style="padding:0 var(--gutter)"><div><h2>Or browse a source</h2><p>Open a site, find a story, then share it here.</p></div></div>
      <div class="group" style="margin:0 var(--gutter)">${W.sources.map((s) => `<button class="row" data-src="${esc(s.name)}"><span class="src-disc" style="background:${SRC_COL[s.name] || '#555'}">${esc(s.name[0])}</span><span class="rt"><b>${esc(s.name)}</b><small>${esc(s.host)}</small></span><span class="pill">Open ${A.ic('arrow_forward')}</span></button>`).join('')}</div></div></div>`;
    A.bindTopbar(ctx);
    const url = ctx.el.querySelector('#url');
    ctx.el.addEventListener('click', (e) => {
      const t = e.target;
      if (t.closest('[data-paste]')) { url.value = 'https://www.royalroad.com/fiction/164525'; url.dispatchEvent(new Event('input')); A.toast('Pasted from clipboard', { icon: 'content_paste' }); return; }
      if (t.closest('[data-st]')) { tab = t.closest('[data-st]').dataset.st; if (tab === 'null') tab = null; ctx.el.querySelectorAll('[data-st]').forEach((b) => b.classList.toggle('on', b === t.closest('[data-st]'))); return; }
      if (t.closest('[data-src]')) { A.toast(`Opening ${t.closest('[data-src]').dataset.src}… share a story back to add it`, { icon: 'open_in_new' }); return; }
      if (t.closest('[data-fetch]')) {
        const v = url.value.trim();
        if (!/^https?:\/\/\S+\.\S+/.test(v)) { A.toast('Paste a story URL first', { icon: 'error' }); url.focus(); return; }
        const m = v.match(/royalroad\.com\/fiction\/(\d+)/);
        const target = (m && W.story('rr_' + m[1])) || W.story('rr_164525') || A.stories()[0];
        run(target);
      }
    });
    function run(s) {
      const box = ctx.el.querySelector('.add-run');
      const steps = ['Starting...', 'Reading the story page', 'Collecting 1 of 3 chapter pages', 'Saving cover and details'];
      let i = 0;
      const draw = () => { box.innerHTML = `<div class="add-status"><span class="spin" style="color:var(--acc)"></span><span><b>Fetching...</b><small>${esc(steps[i])}</small></span></div><div class="add-steps" style="margin-top:12px">${steps.map((st, k) => `<span class="${k < i ? 'done' : ''}">${A.ic(k < i ? 'check_circle' : 'radio_button_unchecked')}${esc(st)}</span>`).join('')}</div>`; };
      draw();
      const tm = setInterval(() => { i++; if (i < steps.length) { draw(); return; } clearInterval(tm); if (tab !== undefined) s.tab = tab; A.toast(`Added “${A.short(s.title)}” · ${s.total} chapters`, { icon: 'library_add_check' }); A.go('/story/' + s.id); }, 850);
      ctx.on(() => clearInterval(tm));
    }
  };

  // ---------- Author novels ----------
  A.screens.author = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    const list = W.authorNovels(s.id).filter((x) => !A.deleted.has(x.id));
    A.tint(ctx.el, s);
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: s.author, sub: 'Author', back: '/story/' + s.id, big: false })}
      <div class="auth-head"><span class="auth-av">${esc((s.author || '?')[0].toUpperCase())}</span><div><h1>${esc(s.author)}</h1><p>${list.length ? `${list.length} other ${list.length === 1 ? 'novel' : 'novels'} in your library` : 'No other novels in your library'}</p></div></div>
      <div class="list rise">${[s, ...list].map((x, i) => listCard(x).replace('<button class="lc', `<button data-go="/story/${x.id}" class="lc${i === 0 ? ' cur' : ''}`)).join('')}</div>
      ${list.length ? '' : `<p class="faint" style="text-align:center;padding:24px">Only “${esc(A.short(s.title))}” by this author is saved.</p>`}</div>`;
    A.bindTopbar(ctx);
  };
})();
