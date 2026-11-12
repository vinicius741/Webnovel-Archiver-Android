/* v3 Restructured — Library (home), Add Story, Author Novels. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;

  // ---------- shared story cards ----------
  A.listCard = (s, sel) => {
    const f = W.forecast(s);
    const tr = W.scoreTrend(s.id);
    const due = f.state === 'due' && !A.state.synced[s.id];
    const pat = W.patreonLine(s);
    return `<article class="scard ${sel ? 'sel' : ''}" data-id="${s.id}" tabindex="0">
      <div class="cwrap">${A.coverHtml(s)}${sel !== undefined ? `<span class="ck ${sel ? 'on' : ''}"></span>` : ''}</div>
      <div class="sbody">
        <h3>${esc(s.title)}</h3>
        <div class="by">by ${esc(s.author || 'Unknown')}</div>
        <div class="meta"><span class="src">${esc(s.source)}</span>${A.pub(s)}${s.score ? `<span class="score">${A.icon('star', 'f xs')}${esc(s.score)}${tr ? A.icon(tr === 'up' ? 'arrow_upward' : 'arrow_downward', 'xs ' + (tr === 'up' ? 'sage' : 'red')) : ''}</span>` : ''}${due ? `<span class="pillb gold" title="Next chapter may be out, sync to check">${A.icon('sync', 'xs')}New?</span>` : ''}${A.state.syncing[s.id] ? '<span class="spinner gold" style="width:13px;height:13px;border-width:2px"></span>' : ''}</div>
        ${pat ? `<div class="pat">${esc(pat)}</div>` : ''}
        <div class="tags">${esc(s.tags.slice(0, 4).join(' • '))}</div>
        <div class="foot"><span>${fmt.int(s.downloaded)} / ${fmt.int(s.total)}</span>${A.cov(s, true)}</div>
      </div></article>`;
  };
  A.gridCard = (s, sel) => {
    const f = W.forecast(s);
    const due = f.state === 'due' && !A.state.synced[s.id];
    const ai = W.hash(s.id) % 9 === 0;
    return `<article class="gcard ${sel ? 'sel' : ''}" data-id="${s.id}" tabindex="0">
      <div class="cwrap">${A.coverHtml(s)}${due ? `<span class="ov ov-new" title="Next chapter may be out">${A.icon('sync', 'xs')}</span>` : ''}${ai ? `<span class="ov ov-ai">${A.icon('auto_awesome', 'xs f')}</span>` : ''}${sel !== undefined ? `<span class="ck ${sel ? 'on' : ''}"></span>` : ''}
</div>
      <div class="gprog">${A.cov(s, true)}</div>
      <h3>${esc(s.title)}</h3><div class="by">${esc(s.author || '')}</div></article>`;
  };

  // ---------- Library ----------
  const L = { query: '', sources: [], tags: [], sort: 'default', asc: null, searching: false, selecting: false, sel: new Set() };

  A.screens.library = {
    persistent: true, navId: 'library',
    render(route) {
      const el = A.view('lib');
      el.innerHTML = `<div class="libhead"><div data-r="bar"></div><nav class="tabs" data-r="tabs"></nav><div data-r="active"></div></div>
        <div class="libbody"><div class="countline" data-r="count"></div><div data-r="list"></div></div>`;
      const $ = (k) => el.querySelector(`[data-r=${k}]`);
      let stopInc = null;
      const filtered = () => W.filterStories({ tab: A.state.libTab, query: L.query, sources: L.sources, tags: L.tags, sort: L.sort, asc: L.asc }).filter((s) => !A.state.hidden.has(s.id));
      const filtersActive = () => L.sources.length + L.tags.length;

      function bar() {
        if (L.selecting) {
          $('bar').innerHTML = A.appbar({ title: `${L.sel.size} selected`, cls: 'raised selbar-top', actions: A.ab('select_all', 'selall', 'Select all') })
            .replace('<div class="ttl">', `<button class="ib" data-act="unsel" aria-label="Exit selection">${A.icon('close')}</button><div class="ttl">`);
        } else if (L.searching) {
          $('bar').innerHTML = `<header class="appbar search-bar"><button class="ib" data-act="endsearch" aria-label="Close search">${A.icon('arrow_back')}</button>
            <div class="field grow" style="min-height:46px;border-radius:23px">${A.icon('search')}<input data-r="q" placeholder="Search title or author" value="${esc(L.query)}" enterkeyhint="search"><button class="ib" data-act="clearq" style="width:40px;height:40px" ${L.query ? '' : 'hidden'}>${A.icon('close')}</button></div>
            <button class="ib" data-act="filters" aria-label="Filters">${A.icon('tune')}${filtersActive() ? '<i class="dotb"></i>' : ''}</button></header>`;
          const q = el.querySelector('[data-r=q]');
          q.addEventListener('input', W.debounce(() => { L.query = q.value; el.querySelector('[data-act=clearq]').hidden = !L.query; list(); }, 120));
        } else {
          const total = A.stories().length;
          $('bar').innerHTML = A.appbar({
            title: 'Library', sub: `${total} novels`, big: true,
            actions: A.ab('search', 'search', 'Search') + A.ab(A.state.libView === 'list' ? 'grid_view' : 'view_agenda', 'view', A.state.libView === 'list' ? 'Cover view' : 'List view') +
              `<button class="ib" data-act="filters" aria-label="Sort and filter">${A.icon('tune')}${filtersActive() ? '<i class="dotb"></i>' : ''}</button>` + `<span class="phone-only">${A.ab('add', 'add', 'Add story')}</span>`,
          });
        }
      }
      function tabs() {
        const un = W.storiesInTab('unassigned').filter((s) => !A.state.hidden.has(s.id)).length;
        const t = [...(un ? [{ id: 'unassigned', name: 'Unassigned' }] : []), ...W.tabs(), { id: 'all', name: 'All' }];
        $('tabs').innerHTML = t.map((x) => `<button class="tab ${A.state.libTab === x.id ? 'on' : ''}" data-tab="${x.id}">${esc(x.name)}<span class="n">${W.storiesInTab(x.id).filter((s) => !A.state.hidden.has(s.id)).length}</span></button>`).join('');
        const on = $('tabs').querySelector('.tab.on'); if (on) on.scrollIntoView({ inline: 'center', block: 'nearest' });
      }
      function active() {
        const so = W.SORTS[L.sort];
        const asc = L.asc == null ? so.asc : L.asc;
        const chips = [`<button class="chip sortchip" data-act="filters">${A.icon(asc ? 'arrow_upward' : 'arrow_downward')}${esc(so.short)}</button>`]
          .concat(L.sources.map((x) => `<button class="chip on" data-rm-src="${esc(x)}">${A.icon('public')}${esc(x)}${A.icon('close', 'sm')}</button>`))
          .concat(L.tags.map((x) => `<button class="chip on" data-rm-tag="${esc(x)}">${esc(x)}${A.icon('close', 'sm')}</button>`));
        if (filtersActive()) chips.push(`<button class="chip" data-act="clearf" style="border-style:dashed">Clear filters</button>`);
        else chips.push(`<button class="chip" data-act="filters">${A.icon('filter_list')}Filter</button>`);
        $('active').innerHTML = `<div class="chips scroll actrow">${chips.join('')}</div>`;
      }
      function list() {
        stopInc && stopInc();
        const items = filtered();
        $('count').innerHTML = items.length ? `${items.length} ${items.length === 1 ? 'novel' : 'novels'}${L.query ? ` matching “${esc(L.query)}”` : ''}` : '';
        const box = $('list');
        if (!items.length) {
          const empty = !A.stories().length;
          box.className = '';
          box.innerHTML = empty ? `<div class="empty">${A.icon('library_books')}<h3>Your library is empty</h3><p>Paste a link from RoyalRoad, Scribble Hub and more.</p><button class="btn fill" data-act="add">${A.icon('add')}Add a story</button></div>`
            : L.query || filtersActive() ? `<div class="empty">${A.icon('search_off')}<h3>No matches</h3><p>Try clearing your search or filters.</p><button class="btn tonal" data-act="clearall">Clear search and filters</button></div>`
              : `<div class="empty">${A.icon('inventory_2')}<h3>Nothing here yet</h3><p>Long-press novels in another tab and move them here.</p></div>`;
          return;
        }
        box.className = A.state.libView === 'list' ? 'slist' : 'sgrid';
        const sel = L.selecting ? (s) => L.sel.has(s.id) : () => undefined;
        stopInc = A.incremental(box, items, A.state.libView === 'list' ? (s) => A.listCard(s, sel(s)) : (s) => A.gridCard(s, sel(s)), { chunk: 36, scroller: el });
      }
      function all() { bar(); tabs(); active(); list(); selbar(); }

      // selection bottom bar
      const sb = W.el(`<div class="selbar" hidden><button class="sbb" data-act="move">${A.icon('drive_file_move')}<span>Move</span></button><button class="sbb" data-act="dl">${A.icon('download')}<span>Download</span></button><button class="sbb danger" data-act="del">${A.icon('delete')}<span>Delete</span></button></div>`);
      document.getElementById('app').appendChild(sb);
      function selbar() {
        sb.hidden = !L.selecting || el.hidden;
        document.body.classList.toggle('selecting', L.selecting && !el.hidden);
        A.renderDock && A.renderDock();
      }
      function enterSel(id) { L.selecting = true; L.sel = new Set(id ? [id] : []); all(); }
      function exitSel() { L.selecting = false; L.sel.clear(); all(); }
      function toggleSel(id) {
        L.sel.has(id) ? L.sel.delete(id) : L.sel.add(id);
        if (!L.sel.size) return exitSel();
        bar();
        const c = el.querySelector(`[data-id="${id}"]`); if (c) { c.classList.toggle('sel', L.sel.has(id)); c.querySelector('.ck').classList.toggle('on', L.sel.has(id)); }
      }

      A.longPress(el, '[data-id]', (e, card) => { if (!L.selecting) enterSel(card.dataset.id); else toggleSel(card.dataset.id); });
      W.on(el, 'click', '[data-id]', (e, card) => { if (L.selecting) toggleSel(card.dataset.id); else W.go('/story/' + card.dataset.id); });
      W.on(el, 'click', '[data-tab]', (e, b) => { A.state.libTab = b.dataset.tab; tabs(); list(); el.scrollTo({ top: 0 }); });
      W.on(el, 'click', '[data-rm-src]', (e, b) => { L.sources = L.sources.filter((x) => x !== b.dataset.rmSrc); active(); bar(); list(); });
      W.on(el, 'click', '[data-rm-tag]', (e, b) => { L.tags = L.tags.filter((x) => x !== b.dataset.rmTag); active(); bar(); list(); });
      W.on(el, 'click', '[data-act]', (e, b) => {
        const a = b.dataset.act;
        if (a === 'search') { L.searching = true; bar(); el.querySelector('[data-r=q]').focus(); }
        else if (a === 'endsearch') { L.searching = false; L.query = ''; bar(); list(); }
        else if (a === 'clearq') { L.query = ''; const q = el.querySelector('[data-r=q]'); q.value = ''; q.focus(); b.hidden = true; list(); }
        else if (a === 'view') { A.state.libView = A.state.libView === 'list' ? 'cover' : 'list'; bar(); list(); }
        else if (a === 'filters') openFilters();
        else if (a === 'clearf') { L.sources = []; L.tags = []; all(); }
        else if (a === 'clearall') { L.sources = []; L.tags = []; L.query = ''; L.searching = false; all(); }
        else if (a === 'add') W.go('/add');
        else if (a === 'unsel') exitSel();
        else if (a === 'selall') { filtered().forEach((s) => L.sel.add(s.id)); all(); }
      });
      W.on(sb, 'click', '[data-act]', async (e, b) => {
        const a = b.dataset.act; const ids = [...L.sel]; const n = ids.length;
        if (a === 'move') {
          const opts = [{ id: '', name: 'Unassigned' }, ...W.tabs()];
          A.menu({ title: `Move ${n} novel${n > 1 ? 's' : ''}`, sub: 'Choose a tab', sections: [{ items: opts.map((t) => ({ icon: t.id ? 'label' : 'label_off', label: t.name, sub: t.id ? `${W.storiesInTab(t.id).length} novels` : 'Not in any tab', fn: () => { ids.forEach((id) => (W.story(id).tab = t.id)); exitSel(); A.emit(); A.toast(`Moved ${n} to ${t.name}`); } })) }] });
        } else if (a === 'dl') {
          ids.forEach((id) => W.downloads.enqueue(id)); exitSel(); A.toast(`Queued missing chapters for ${n} novel${n > 1 ? 's' : ''}`, 'View', () => W.go('/queue'));
        } else if (a === 'del') {
          if (await A.confirm(`Delete ${n} novel${n > 1 ? 's' : ''}?`, 'Downloaded chapters, audio and EPUBs for these novels will be removed from this phone.', 'Delete', true)) {
            ids.forEach((id) => A.state.hidden.add(id)); exitSel(); A.emit();
            A.toast(`Deleted ${n} novel${n > 1 ? 's' : ''}`, 'Undo', () => { ids.forEach((id) => A.state.hidden.delete(id)); A.emit(); });
          }
        }
      });

      // swipe between tabs
      let sx = null, sy = 0;
      el.addEventListener('touchstart', (e) => { if (e.target.closest('.tabs,.chips')) { sx = null; return; } sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
      el.addEventListener('touchend', (e) => {
        if (sx == null || L.selecting) return; const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy; sx = null;
        if (Math.abs(dx) < 80 || Math.abs(dy) > Math.abs(dx) * 0.6) return;
        const ids = [...el.querySelectorAll('[data-tab]')].map((b) => b.dataset.tab); const i = ids.indexOf(A.state.libTab);
        const ni = i + (dx < 0 ? 1 : -1); if (ni < 0 || ni >= ids.length) return;
        A.state.libTab = ids[ni]; tabs(); list();
      });

      // filters + sort bottom sheet
      function openFilters() {
        const base = W.storiesInTab(A.state.libTab).filter((s) => !A.state.hidden.has(s.id));
        let showAllTags = false;
        const body = () => {
          const so = W.SORTS[L.sort]; const asc = L.asc == null ? so.asc : L.asc;
          const srcs = W.sourceCounts(base);
          const tagsAll = W.tagCounts(base);
          const tg = showAllTags ? tagsAll : tagsAll.slice(0, 18);
          return `<div class="fsec"><div class="menu-sec">Sort by</div><div class="sortgrid">${Object.entries(W.SORTS).map(([k, v]) => `<button class="chip ${L.sort === k ? 'on' : ''}" data-sort="${k}">${L.sort === k ? A.icon('check') : ''}${esc(v.label)}</button>`).join('')}</div>
            <div class="seg" style="margin-top:12px"><button data-dir="desc" class="${!asc ? 'on' : ''}">${A.icon('arrow_downward', 'sm')}Descending</button><button data-dir="asc" class="${asc ? 'on' : ''}">${A.icon('arrow_upward', 'sm')}Ascending</button></div></div>
            <div class="fsec"><div class="menu-sec">Source</div><div class="chips">${srcs.map((x) => `<button class="chip ${L.sources.includes(x.name) ? 'on' : ''}" data-src="${esc(x.name)}">${A.icon('public')}${esc(x.name)} <span class="n">${x.count}</span></button>`).join('')}</div></div>
            <div class="fsec"><div class="menu-sec">Tags · match all</div><div class="chips">${tg.map((x) => `<button class="chip ${L.tags.includes(x.name) ? 'on' : ''}" data-tag="${esc(x.name)}">${esc(x.name)} <span class="n">${x.count}</span></button>`).join('')}</div>
            ${tagsAll.length > 18 ? `<button class="btn text" data-act="moretags" style="margin-top:6px">${showAllTags ? 'Show fewer' : `Show all ${tagsAll.length} tags`}</button>` : ''}</div>`;
        };
        const foot = () => `<button class="btn outline" data-act="fclear">Clear</button><button class="btn fill grow" data-act="fdone">Show ${filtered().length} novels</button>`;
        A.sheet({
          title: 'Sort & filter', sub: W.tabs().concat([{ id: 'all', name: 'All' }, { id: 'unassigned', name: 'Unassigned' }]).find((t) => t.id === A.state.libTab).name + ' tab', body: body(), foot: foot(),
          onMount(sh, close) {
            const refresh = () => { const sc = sh.querySelector('.sh-body').scrollTop; sh.querySelector('.sh-body').innerHTML = body(); sh.querySelector('.sh-body').scrollTop = sc; sh.querySelector('.sh-foot').innerHTML = foot(); all(); };
            W.on(sh, 'click', '[data-sort]', (e, b) => { L.sort = b.dataset.sort; L.asc = null; refresh(); });
            W.on(sh, 'click', '[data-dir]', (e, b) => { L.asc = b.dataset.dir === 'asc'; refresh(); });
            W.on(sh, 'click', '[data-src]', (e, b) => { const v = b.dataset.src; L.sources = L.sources.includes(v) ? L.sources.filter((x) => x !== v) : [...L.sources, v]; refresh(); });
            W.on(sh, 'click', '[data-tag]', (e, b) => { const v = b.dataset.tag; L.tags = L.tags.includes(v) ? L.tags.filter((x) => x !== v) : [...L.tags, v]; refresh(); });
            W.on(sh, 'click', '[data-act=moretags]', () => { showAllTags = !showAllTags; refresh(); });
            W.on(sh, 'click', '[data-act=fclear]', () => { L.sources = []; L.tags = []; L.sort = 'default'; L.asc = null; refresh(); });
            W.on(sh, 'click', '[data-act=fdone]', close);
          },
        });
      }

      A.onChange(() => { if (!el.hidden) { bar(); tabs(); list(); } else el.dataset.stale = '1'; });
      all();
      if (route.query.view === 'cover') { A.state.libView = 'cover'; all(); }
      if (route.query.select) enterSel(filtered()[0].id), filtered().slice(1, 3).forEach((s) => L.sel.add(s.id)), all();
      if (route.query.sheet === 'filters') setTimeout(openFilters, 50);
      if (route.query.q) { L.searching = true; L.query = route.query.q; all(); }
      return {
        el,
        update(r) { if (r.query.view) { A.state.libView = r.query.view === 'cover' ? 'cover' : 'list'; all(); } else if (el.dataset.stale) { delete el.dataset.stale; all(); } selbar(); },
        onHide() { selbar(); },
      };
    },
  };

  // ---------- Add Story ----------
  A.screens.add = {
    nav: false, mini: false,
    render() {
      const el = A.view('addv');
      const tabsOpts = W.tabs().map((t) => `<option value="${t.id}" ${t.id === A.state.libTab ? 'selected' : ''}>${esc(t.name)}</option>`).join('');
      el.innerHTML = `${A.appbar({ title: 'Add story', sub: 'Paste a story URL to import', back: true })}
        <div class="pad narrow">
          <div class="addhero">${A.icon('link', 'lg')}<div><b>Import from a link</b><div class="muted">Chapters, cover, tags and metrics are saved for offline reading.</div></div></div>
          <label class="field-label">Story URL</label>
          <div class="field">${A.icon('link')}<input data-r="url" inputmode="url" placeholder="https://www.royalroad.com/fiction/…"><button class="btn sm text" data-act="paste">${A.icon('content_paste', 'sm')}Paste</button></div>
          <label class="field-label" style="margin-top:16px">Save to tab</label>
          <div class="field">${A.icon('label')}<select data-r="tab"><option value="">Unassigned</option>${tabsOpts}</select>${A.icon('expand_more')}</div>
          <button class="btn fill block" data-act="fetch" style="margin-top:20px">${A.icon('cloud_download')}Fetch story</button>
          <div class="fetchst" data-r="st" hidden></div>
          <div class="section-label">Or browse a source</div>
          <div class="list">${W.sources.map((s) => `<button class="li" data-host="${s.host}"><span class="lead">${A.icon('public')}</span><div class="grow"><div class="t">${esc(s.name)}</div><div class="s">${s.host}</div></div><span class="gold" style="font-weight:650;font-size:13.5px">Open</span>${A.icon('arrow_forward', 'sm gold')}</button>`).join('')}</div>
        </div>`;
      const url = el.querySelector('[data-r=url]');
      W.on(el, 'click', '[data-host]', (e, b) => A.toast(`Opening ${b.dataset.host} in the in-app browser`));
      W.on(el, 'click', '[data-act=paste]', async () => {
        let t = '';
        try { t = await navigator.clipboard.readText(); } catch (e) { /* ignore */ }
        url.value = /^https?:/.test(t) ? t : 'https://www.royalroad.com/fiction/164525';
        url.dispatchEvent(new Event('input'));
      });
      W.on(el, 'click', '[data-act=fetch]', (e, b) => {
        const v = url.value.trim();
        if (!/^https?:\/\/\S+\.\S+/.test(v)) { url.parentElement.style.borderColor = 'var(--red)'; A.toast('Paste a full story link first'); return; }
        const m = v.match(/(\d{4,})/);
        const target = (m && W.story('rr_' + m[1])) || W.story('rr_164525') || A.stories()[0];
        const st = el.querySelector('[data-r=st]');
        b.disabled = true; b.innerHTML = '<span class="spinner"></span>Fetching…';
        st.hidden = false;
        const steps = ['Starting…', 'Opening story page…', `Reading chapter list · ${target.total} chapters`, 'Saving cover and details…', 'Done'];
        let i = 0;
        const tick = () => {
          st.innerHTML = `<div class="row">${i < steps.length - 1 ? '<span class="spinner gold"></span>' : A.icon('check_circle', 'gold')}<span>${steps[i]}</span></div>${A.prog((i + 1) / steps.length)}`;
          if (++i < steps.length) setTimeout(tick, 650);
          else setTimeout(() => { const tab = el.querySelector('[data-r=tab]'); if (tab.value) target.tab = tab.value; A.emit(); W.go('/story/' + target.id, { replace: true }); A.toast(`Added to ${tab.selectedOptions[0].text}`); }, 500);
        };
        tick();
      });
      url.addEventListener('input', () => { url.parentElement.style.borderColor = ''; });
      return { el };
    },
  };

  // ---------- Author Novels ----------
  A.screens.author = {
    navId: 'library',
    render(route) {
      const s = W.story(route.params.id);
      const el = A.view();
      if (!s) { el.innerHTML = A.appbar({ title: 'Author', back: true }) + '<div class="empty"><h3>Story not found</h3></div>'; return { el }; }
      const others = W.authorNovels(s.id).filter((o) => !A.state.hidden.has(o.id));
      el.innerHTML = `${A.appbar({ title: esc(s.author), sub: `${others.length} other novel${others.length === 1 ? '' : 's'} in your library`, back: true })}
        <div class="pad wrap"><div class="authorhero"><div class="avatar">${esc((s.author || '?').slice(0, 1).toUpperCase())}</div><div><b>${esc(s.author)}</b><div class="muted">${others.length + 1} novel${others.length ? 's' : ''} in your library · ${esc(s.source)}</div></div></div>
        <div class="section-label">You came from</div><div class="slist">${A.listCard(s)}</div>
        <div class="section-label">Also by ${esc(s.author)}</div>
        ${others.length ? `<div class="slist">${others.map((o) => A.listCard(o)).join('')}</div>` : `<div class="empty">${A.icon('person_search')}<h3>No other novels</h3><p>Nothing else by ${esc(s.author)} is in your library yet.</p></div>`}</div>`;
      W.on(el, 'click', '[data-id]', (e, c) => W.go('/story/' + c.dataset.id));
      return { el };
    },
  };
})();
