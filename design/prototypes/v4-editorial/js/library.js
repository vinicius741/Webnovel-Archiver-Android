/* v4 Editorial — Library (shelves + index catalog), selection mode, Add Story. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon, S = A.state;
  const CHUNK = 36;

  const shelfName = (id) => id === 'all' ? 'The Collection' : id === 'unassigned' ? 'Unshelved' : (W.tabs().find((t) => t.id === id) || {}).name || 'Library';
  const tabCount = (id) => W.storiesInTab(id).filter(A.alive).length;

  function badges(s) {
    const f = W.forecast(s);
    const out = [`<span class="src">${esc(s.source)}</span>`];
    if (A.pubLabel[s.pub]) out.push(`<span class="pill ${s.pub}">${A.pubLabel[s.pub]}</span>`);
    if (s.pub === 'unknown') out.push('<span class="pill bad">Source unavailable</span>');
    if (f.state === 'due') out.push(`<span class="due" title="Next chapter may be out, sync to check">${I('autorenew', 'xs')}</span>`);
    if (s.score) out.push(`<span class="score">★ ${esc(s.score)}</span>`);
    return out.join('');
  }

  // A typographic catalog entry (index view, author page).
  A.entryHTML = (s, { sel = false } = {}) => {
    const pl = W.patreonLine(s);
    const tags = s.tags.slice(0, 5).map(esc).join(' • ');
    return `<article class="entry ${sel ? 'sel' : ''}" data-id="${s.id}">
      <div class="e-cover">${A.book(s)}${sel ? `<span class="selmark">${I('check')}</span>` : ''}</div>
      <div class="e-body">
        <h3 class="clamp2">${A.titleHTML(s.title)}</h3>
        <div class="by">by <i>${esc(s.author || 'Unknown')}</i></div>
        <div class="badges">${badges(s)}</div>
        ${pl ? `<div class="pat">${esc(pl)}</div>` : ''}
        ${tags ? `<div class="tags">${tags}</div>` : ''}
        <div class="e-foot"><span class="tab-nums">${s.downloaded} / ${s.total}</span>${A.cov(s)}</div>
      </div>
    </article>`;
  };
  function volHTML(s, sel) {
    const f = W.forecast(s);
    return `<article class="vol ${sel ? 'sel' : ''}" data-id="${s.id}">
      <div class="v-cover">${A.book(s)}
        ${f.state === 'due' ? `<span class="v-new" title="Next chapter may be out">${I('autorenew', 'xs')}</span>` : ''}
        ${S.aiCover[s.id] ? `<span class="v-ai">${I('auto_awesome', 'xs fill')}</span>` : ''}
        ${S.selecting ? `<span class="selmark">${I('check')}</span>` : ''}
      </div>
      <div class="v-cap"><b class="clamp2">${A.titleHTML(s.title)}</b><small class="clamp1">${esc(s.author || '')}</small>
        <div class="v-foot"><span class="tab-nums">${s.downloaded}/${s.total}</span>${A.cov(s)}</div></div>
    </article>`;
  }

  A.screens.library = (page, r) => {
    if (r.query.view) S.view = r.query.view === 'index' || r.query.view === 'list' ? 'index' : 'shelf';
    if (r.query.tab) { const t = r.query.tab === 'all' || r.query.tab === 'unassigned' ? r.query.tab : (W.tabs().find((x) => x.name.toLowerCase() === r.query.tab.toLowerCase()) || {}).id; if (t) S.tab = t; }
    if (r.query.filters) S.filtersOpen = true;
    let list = [], shown = 0, io = null;

    page.innerHTML = `
      <div class="lib-top"></div>
      <div class="lib-head wrap"></div>
      <nav class="shelves" aria-label="Shelves"></nav>
      <div class="catalog wrap"></div>
      <div class="resume-slot"></div>
      <div class="lib-body"></div>
      <div class="selbar" hidden></div>`;
    const $ = (s) => page.querySelector(s);

    function top() {
      if (S.selecting) {
        $('.lib-top').innerHTML = `<header class="bar selhead">
          <button class="ib" data-act="selClose" aria-label="Cancel selection">${I('close')}</button>
          <div class="ttl"><b>${S.selected.size} selected</b><small>Long-press or tap to add more</small></div>
          <button class="btn text" data-act="selAll">Select all</button></header>`;
        return;
      }
      $('.lib-top').innerHTML = `<header class="mast">
        <button class="ib" data-act="contents" aria-label="Contents">${I('menu_book')}</button>
        <div class="wordmark">Webnovel <i>Archiver</i></div>
        <button class="ib" data-act="view" aria-label="${S.view === 'shelf' ? 'Index view' : 'Shelf view'}">${I(S.view === 'shelf' ? 'view_list' : 'shelves')}</button>
        <button class="ib" data-go="/add" aria-label="Add a story">${I('add')}</button>
      </header>`;
    }
    function head() {
      const all = W.storiesInTab(S.tab).filter(A.alive);
      const due = all.filter((s) => W.forecast(s).state === 'due').length;
      const name = shelfName(S.tab);
      $('.lib-head').innerHTML = `
        <div class="kicker">The Library · ${W.stories().filter(A.alive).length} novels</div>
        <h1 class="disp lib-title">${name === 'The Collection' ? 'The <i>Collection</i>' : esc(name)}</h1>
        <p class="dek">${all.length} ${all.length === 1 ? 'volume' : 'volumes'} on this shelf${due ? ` · <span class="copper">${due} may have new chapters</span>` : ''}</p>`;
    }
    function shelves() {
      const un = tabCount('unassigned');
      const tabs = [...(un ? [{ id: 'unassigned', name: 'Unassigned' }] : []), ...W.tabs(), { id: 'all', name: 'All' }];
      $('.shelves').innerHTML = `<div class="shelves-in">${tabs.map((t) => `<button class="${t.id === S.tab ? 'on' : ''}" data-act="tab" data-tab="${t.id}">${esc(t.name)} <span>${tabCount(t.id)}</span></button>`).join('')}</div>`;
      const on = $('.shelves .on'); if (on) on.scrollIntoView({ inline: 'center', block: 'nearest' });
    }
    function active() { return S.sources.length + S.tags.length; }
    function catalog() {
      const base = W.storiesInTab(S.tab).filter(A.alive);
      const srcs = W.sourceCounts(base);
      const tagBase = S.sources.length ? base.filter((s) => S.sources.includes(s.source)) : base;
      const tags = W.tagCounts(tagBase).slice(0, 40);
      const so = W.SORTS[S.sort];
      const asc = S.asc == null ? so.asc : S.asc;
      const n = active();
      $('.catalog').innerHTML = `
        <div class="card-cat">
          <label class="cat-search">${I('search')}<input type="search" placeholder="Search titles &amp; authors" value="${esc(S.query)}" enterkeyhint="search" aria-label="Search stories">${S.query ? `<button class="ib" data-act="clearQ" aria-label="Clear search">${I('close', 'sm')}</button>` : ''}</label>
          <div class="cat-row">
            <button class="cat-tog ${S.filtersOpen ? 'open' : ''}" data-act="filters">${I('tune', 'sm')}<span>Refine</span>${n ? `<em>${n}</em>` : ''}${I('expand_more', 'sm chev')}</button>
            ${n ? '<button class="cat-clear" data-act="clearF">Clear filters</button>' : ''}
            <span class="grow"></span>
            <button class="cat-sort" data-act="sort">Sorted by <i>${so.short}</i> ${I(asc ? 'arrow_upward' : 'arrow_downward', 'xs')}</button>
          </div>
          <div class="cat-filters" ${S.filtersOpen ? '' : 'hidden'}>
            <div class="label">Sources</div>
            <div class="chips">${srcs.map((x) => `<button class="chip ${S.sources.includes(x.name) ? 'on' : ''}" data-act="src" data-v="${esc(x.name)}">${I('language')}${esc(x.name)} <span class="c">${x.count}</span></button>`).join('')}</div>
            <div class="label">Subjects</div>
            <div class="chips">${tags.map((x) => `<button class="chip ${S.tags.includes(x.name) ? 'on' : ''}" data-act="tag" data-v="${esc(x.name)}">${esc(x.name)} <span class="c">${x.count}</span></button>`).join('')}</div>
          </div>
        </div>`;
      const inp = $('.cat-search input');
      inp.addEventListener('input', W.debounce(() => { S.query = inp.value; body(); const has = !!S.query; if (has !== !!$('.cat-search .ib')) { const pos = inp.selectionStart; catalog(); const n2 = $('.cat-search input'); n2.focus(); n2.setSelectionRange(pos, pos); } }, 160));
    }
    function resume() {
      const p = W.player.state();
      const ses = W.session;
      if (p.loaded || !ses || S.continueDismissed || S.selecting) { $('.resume-slot').innerHTML = ''; return; }
      const s = W.story(ses.storyId);
      const parts = A.chParts(ses.chapterTitle, 0);
      $('.resume-slot').innerHTML = `<div class="wrap"><div class="resume">
        <button class="resume-b" data-act="resume">
          <span class="resume-cov">${A.book(s, { lazy: false })}</span>
          <span class="grow"><span class="kicker">Continue listening</span><b class="clamp1">${esc(A.shortTitle(s.title))}</b><small class="clamp1"><i>Chapter ${parts.n}</i> · ${esc(parts.name)}</small></span>
          <span class="resume-play">${I('play_arrow', 'fill')}</span>
        </button>
        <button class="ib" data-act="dismissResume" aria-label="Dismiss">${I('close', 'sm')}</button>
      </div></div>`;
    }
    function body() {
      list = W.filterStories({ tab: S.tab, query: S.query, sources: S.sources, tags: S.tags, sort: S.sort, asc: S.asc == null ? undefined : S.asc }).filter(A.alive);
      const b = $('.lib-body');
      if (io) io.disconnect();
      if (!list.length) {
        const total = W.storiesInTab(S.tab).filter(A.alive).length;
        b.innerHTML = total === 0 && S.tab === 'all'
          ? `<div class="empty"><div class="ornament">❦</div><h3>Your library is empty</h3><p>Paste a link from RoyalRoad, Scribble Hub or the forums to begin your collection.</p><button class="btn" data-go="/add">${I('add')}Add a story</button></div>`
          : total === 0 ? '<div class="empty"><div class="ornament">❦</div><h3>Nothing here yet</h3><p>Long-press novels on another shelf and move them here.</p></div>'
            : `<div class="empty"><div class="ornament">⁂</div><h3>No matches</h3><p>Try clearing your search or filters.</p><button class="btn ghost" data-act="clearAll">Clear search &amp; filters</button></div>`;
        return;
      }
      b.innerHTML = `<div class="${S.view === 'shelf' ? 'shelf-grid' : 'index-list'} wrap"></div><div class="sentinel"></div>`;
      shown = 0; more();
      io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) more(); }, { rootMargin: '900px' });
      io.observe($('.sentinel'));
    }
    let lastLetter = '';
    function more() {
      if (shown >= list.length) return;
      const g = $('.lib-body > div');
      if (!shown) lastLetter = '';
      const slice = list.slice(shown, shown + CHUNK);
      let html = '';
      slice.forEach((s) => {
        if (S.view === 'index' && S.sort === 'title') {
          const L = (s.title.match(/[A-Za-z0-9]/) || ['#'])[0].toUpperCase();
          if (L !== lastLetter) { lastLetter = L; html += `<div class="letter">${L}</div>`; }
        }
        html += S.view === 'shelf' ? volHTML(s, S.selected.has(s.id)) : A.entryHTML(s, { sel: S.selected.has(s.id) });
      });
      g.insertAdjacentHTML('beforeend', html);
      shown += slice.length;
    }
    function selbar() {
      const sb = $('.selbar');
      sb.hidden = !S.selecting;
      document.getElementById('foot').hidden = S.selecting;
      document.documentElement.style.setProperty('--foot-h', S.selecting ? '0px' : '62px');
      if (!S.selecting) return;
      sb.innerHTML = `<button data-act="move" ${S.selected.size ? '' : 'disabled'}>${I('drive_file_move')}<span>Move</span></button><button data-act="del" class="danger" ${S.selected.size ? '' : 'disabled'}>${I('delete')}<span>Delete</span></button>`;
    }
    function markSel() {
      page.querySelectorAll('[data-id]').forEach((n) => n.classList.toggle('sel', S.selected.has(n.dataset.id)));
      top(); selbar();
    }
    function enterSel(id) {
      S.selecting = true; if (id) S.selected.add(id);
      page.classList.add('selecting');
      top(); resume(); body(); selbar();
    }
    function exitSel() { S.selecting = false; S.selected.clear(); page.classList.remove('selecting'); top(); resume(); body(); selbar(); }
    A.onLeave(() => { if (S.selecting) { S.selecting = false; S.selected.clear(); } });

    function full() { top(); head(); shelves(); catalog(); resume(); body(); selbar(); }
    full();
    if (S.selecting) page.classList.add('selecting');
    if (r.query.select) { const first = W.filterStories({ tab: S.tab }).filter(A.alive).slice(0, 3); first.forEach((s) => S.selected.add(s.id)); enterSel(); }

    A.longPress(page, '.vol, .entry', (t) => { if (!S.selecting) enterSel(t.dataset.id); else { S.selected.add(t.dataset.id); markSel(); } });
    page.addEventListener('click', (e) => {
      const it = e.target.closest('.vol, .entry'); if (!it || e.target.closest('[data-act]')) return;
      if (S.selecting) { const id = it.dataset.id; S.selected.has(id) ? S.selected.delete(id) : S.selected.add(id); markSel(); return; }
      W.go('/story/' + it.dataset.id);
    });

    // swipe between shelves
    let sx = null, sy = 0;
    const lb = $('.lib-body');
    lb.addEventListener('touchstart', (e) => { const t = e.touches[0]; sx = t.clientX; sy = t.clientY; }, { passive: true });
    lb.addEventListener('touchend', (e) => {
      if (sx == null) return; const t = e.changedTouches[0]; const dx = t.clientX - sx, dy = t.clientY - sy; sx = null;
      if (Math.abs(dx) > 80 && Math.abs(dy) < 50 && !S.selecting) {
        const ids = [...(tabCount('unassigned') ? ['unassigned'] : []), ...W.tabs().map((x) => x.id), 'all'];
        const i = ids.indexOf(S.tab); const n = ids[Math.max(0, Math.min(ids.length - 1, i + (dx < 0 ? 1 : -1)))];
        if (n !== S.tab) { S.tab = n; S.sources = []; S.tags = []; lb.classList.remove('slide-l', 'slide-r'); void lb.offsetWidth; lb.classList.add(dx < 0 ? 'slide-l' : 'slide-r'); head(); shelves(); catalog(); body(); }
      }
    }, { passive: true });

    A.acts(page, {
      contents: () => A.contents(),
      view: () => { S.view = S.view === 'shelf' ? 'index' : 'shelf'; top(); body(); A.toast(S.view === 'shelf' ? 'Shelf view' : 'Index view'); },
      tab: (t) => { S.tab = t.dataset.tab; S.sources = []; S.tags = []; head(); shelves(); catalog(); body(); window.scrollTo({ top: Math.min(window.scrollY, page.querySelector('.shelves').offsetTop), behavior: 'smooth' }); },
      filters: () => { S.filtersOpen = !S.filtersOpen; catalog(); },
      clearQ: () => { S.query = ''; catalog(); body(); },
      clearF: () => { S.sources = []; S.tags = []; catalog(); body(); },
      clearAll: () => { S.query = ''; S.sources = []; S.tags = []; catalog(); body(); },
      src: (t) => { const v = t.dataset.v; S.sources = S.sources.includes(v) ? S.sources.filter((x) => x !== v) : [...S.sources, v]; catalog(); body(); },
      tag: (t) => { const v = t.dataset.v; S.tags = S.tags.includes(v) ? S.tags.filter((x) => x !== v) : [...S.tags, v]; catalog(); body(); },
      sort: () => sortSheet(() => { catalog(); body(); }),
      resume: () => { const ses = W.session; W.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); resume(); A.toast('Resuming where you left off'); },
      dismissResume: () => { S.continueDismissed = true; resume(); },
      selClose: exitSel,
      selAll: () => { list.forEach((s) => S.selected.add(s.id)); markSel(); },
      move: () => moveDialog([...S.selected], () => { exitSel(); head(); shelves(); }),
      del: () => {
        const ids = [...S.selected];
        A.confirm(`Delete ${ids.length} ${ids.length === 1 ? 'novel' : 'novels'}?`, 'Their downloaded chapters, audio and EPUB files will be removed from this phone.', 'Delete', () => {
          ids.forEach((id) => S.deleted.add(id)); exitSel(); head(); shelves();
          A.toast(`Deleted ${ids.length} ${ids.length === 1 ? 'novel' : 'novels'}`, { action: 'Undo', onAction: () => { ids.forEach((id) => S.deleted.delete(id)); head(); shelves(); body(); } });
        }, true);
      },
    });
    A.onPlayer = () => { if (!!$('.resume-slot').innerHTML !== (!W.player.state().loaded && !!W.session && !S.continueDismissed && !S.selecting)) resume(); };
    A.onLeave(() => { if (io) io.disconnect(); document.getElementById('foot').hidden = false; });
    if (r.query.sheet === 'sort') sortSheet(() => { catalog(); body(); });
  };

  function sortSheet(done) {
    const html = () => {
      const so = W.SORTS[S.sort]; const asc = S.asc == null ? so.asc : S.asc;
      return `<ul class="mlist">${Object.entries(W.SORTS).map(([k, v]) => `<li><button class="mi ${k === S.sort ? 'on' : ''}" data-sort="${k}"><span class="radio ${k === S.sort ? 'on' : ''}"></span><span class="grow">${v.label}</span></button></li>`).join('')}</ul>
      <div class="wrap" style="margin-top:12px"><div class="label" style="margin-bottom:10px">Direction</div>
      <div class="dir"><button class="${asc ? 'on' : ''}" data-dir="1">${I('arrow_upward', 'sm')} Ascending</button><button class="${!asc ? 'on' : ''}" data-dir="0">${I('arrow_downward', 'sm')} Descending</button></div></div>`;
    };
    const L = A.sheet({ title: 'Sort <i>by</i>', html: html() });
    L.el.addEventListener('click', (e) => {
      const so = e.target.closest('[data-sort]'); const d = e.target.closest('[data-dir]');
      if (so) { S.sort = so.dataset.sort; S.asc = null; L.close(); done(); }
      if (d) { S.asc = d.dataset.dir === '1'; L.el.querySelector('.sh-b').innerHTML = html(); done(); }
    });
  }

  function moveDialog(ids, done) {
    const opts = [{ id: '', name: 'Unassigned' }, ...W.tabs()];
    let pick = null;
    const body = `<p>Choose a shelf for ${ids.length === 1 ? 'this novel' : 'these novels'}.</p><div class="pick">${opts.map((o) => `<button class="mi" data-t="${o.id}"><span class="radio"></span><span class="grow">${esc(o.name)}</span><span class="faint num">${o.id ? W.storiesInTab(o.id).filter(A.alive).length : ''}</span></button>`).join('')}</div>`;
    const L = A.dialog({ title: `Move ${ids.length} ${ids.length === 1 ? 'Novel' : 'Novels'}`, body, actions: [{ label: 'Cancel' }, { label: 'Move', act: () => {
      if (pick == null) return;
      ids.forEach((id) => { W.story(id).tab = pick || null; });
      A.toast(`Moved ${ids.length} to ${esc(opts.find((o) => o.id === pick).name)}`); done();
    } }] });
    L.el.addEventListener('click', (e) => { const b = e.target.closest('[data-t]'); if (!b) return; pick = b.dataset.t; L.el.querySelectorAll('[data-t] .radio').forEach((r) => r.classList.toggle('on', r.parentNode === b)); });
  }
  A.moveDialog = moveDialog;

  // ---------- Add Story ----------
  A.screens.add = (page, r) => {
    let tab = (W.tabs().find((t) => t.name === 'Plan') || W.tabs()[0]).id;
    page.innerHTML = `${A.bar({ title: 'Add a Story', sub: 'Paste a story URL to import' })}
      <div class="wrap measure add">
        <div class="kicker" style="margin-top:18px">Acquisitions</div>
        <h1 class="disp add-h">A new volume<br><i>for the shelves.</i></h1>
        <div class="accession">
          <div class="acc-no">No. ${String(W.stories().length + 1).padStart(4, '0')}</div>
          <label class="field"><span class="lab">Story URL</span>
            <div class="input-row"><input class="input" id="url" inputmode="url" placeholder="https://www.royalroad.com/fiction/…" value="${esc(r.query.url || '')}"><button class="btn tonal" data-act="paste" aria-label="Paste">${I('content_paste')}</button></div></label>
          <label class="field" style="margin-top:18px"><span class="lab">Save to shelf</span>
            <button class="input select-btn" data-act="tab"><span id="tabname"></span>${I('expand_more')}</button></label>
          <button class="btn block" data-act="fetch" style="margin-top:22px">${I('download')}Fetch story</button>
          <div class="fetching" hidden><div class="row"><span class="spin copper"></span><b>Fetching…</b></div><p class="it muted" id="fstat">Starting...</p><div class="prog thick"><i></i></div></div>
        </div>
        ${A.secHead('', 'Or browse a source')}
        <div class="sources">${W.sources.map((s) => `<button class="srcrow" data-act="browse" data-n="${esc(s.name)}"><span class="globe">${I('language')}</span><span class="grow"><b>${esc(s.name)}</b><small>${esc(s.host)}</small></span><span class="open">Open →</span></button>`).join('')}</div>
      </div>`;
    const setTab = () => { page.querySelector('#tabname').textContent = W.tabs().find((t) => t.id === tab).name; };
    setTab();
    let busy = false;
    A.acts(page, {
      paste: () => { page.querySelector('#url').value = 'https://www.royalroad.com/fiction/169064/arcane-zoologist'; A.toast('Pasted from clipboard'); },
      tab: () => A.menu({ title: 'Save to <i>shelf</i>', groups: [{ items: W.tabs().map((t) => ({ label: esc(t.name), on: t.id === tab, end: `<span class="faint num">${tabCount(t.id)}</span>`, act: () => { tab = t.id; setTab(); } })) }] }),
      browse: (t) => A.toast(`Opening ${esc(t.dataset.n)} in the in-app browser…`),
      fetch: () => {
        if (busy) return;
        const url = page.querySelector('#url').value.trim();
        if (!/^https?:\/\/\S+\.\S+/.test(url)) { A.toast('Paste a story link first, like royalroad.com/fiction/…'); page.querySelector('#url').focus(); return; }
        busy = true;
        const m = url.match(/fiction\/(\d+)/); const target = (m && W.story('rr_' + m[1])) || W.story('rr_169064') || W.stories()[0];
        const box = page.querySelector('.fetching'); box.hidden = false; page.querySelector('[data-act=fetch]').disabled = true;
        const steps = ['Starting...', 'Reading the story page...', `Found “${A.shortTitle(target.title)}”`, `Listing ${target.total} chapters...`, 'Saving the cover...', 'Done'];
        let i = 0;
        const tick = () => {
          if (A.route.name !== 'add') return;
          page.querySelector('#fstat').textContent = steps[i]; page.querySelector('.fetching .prog i').style.width = ((i + 1) / steps.length * 100) + '%';
          i++; if (i < steps.length) setTimeout(tick, 650); else setTimeout(() => { W.go('/story/' + target.id, { replace: true }); A.toast(`Added to ${esc(W.tabs().find((t) => t.id === tab).name)}`); }, 450);
        };
        tick();
      },
    });
  };
})();
