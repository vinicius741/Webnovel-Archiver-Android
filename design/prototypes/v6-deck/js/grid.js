/* v6 — Library grid mode: tabs, search, filters, sort, cover/list views, selection (long-press) with Move/Delete. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  const F = () => V6.state.filters;

  function itemCover(s, sel) {
    const f = W.forecast(s);
    return `<button class="gc ${sel ? 'sel' : ''}" data-id="${s.id}">
      <div class="gc-art">${V6.cover(s)}${f.state === 'due' ? `<span class="gc-new" title="Next chapter may be out">${ic('sync')}</span>` : ''}<span class="gc-ck">${ic('check')}</span>
      <span class="gc-pct num">${s.downloaded}<em>/${s.total}</em></span></div>
      <b class="gc-t">${esc(s.title)}</b><small class="gc-a">${esc(s.author || '')}</small>${V6.coverageBar(s)}
    </button>`;
  }
  function itemList(s, sel) {
    const f = W.forecast(s), pl = W.patreonLine(s);
    return `<button class="gl ${sel ? 'sel' : ''}" data-id="${s.id}">
      <div class="gl-art">${V6.cover(s)}<span class="gc-ck">${ic('check')}</span></div>
      <div class="gl-b">
        <b class="gl-t">${esc(s.title)}</b>
        <small class="gl-a">by ${esc(s.author || 'Unknown')}</small>
        <div class="gl-badges"><span class="acc-t">${esc(s.source)}</span>${V6.pubLabel(s.pub) ? `<span class="tag">${V6.pubLabel(s.pub)}</span>` : ''}${f.state === 'due' ? `<span class="tag acc">${ic('sync')}May be out</span>` : ''}${s.score ? `<span class="gl-sc">★ ${parseFloat(s.score).toFixed(2)}</span>` : ''}</div>
        ${pl ? `<small class="gl-p">${esc(pl)}</small>` : ''}
        <small class="gl-tags">${esc(s.tags.slice(0, 5).join(' • '))}</small>
        <div class="gl-foot"><span class="num">${s.downloaded}<em>/${s.total}</em></span>${V6.coverageBar(s)}</div>
      </div></button>`;
  }

  V6.renderGrid = (ctx) => {
    const { root } = ctx;
    const sel = new Set();
    let list = [], shown = 0, io = null;
    const view = () => V6.state.gridView;
    const p = W.player.state();
    const showContinue = !p.loaded && W.session && !V6.state.continueDismissed;
    const ses = W.session;
    root.innerHTML = `
      <header class="hm-h">
        <div class="hm-logo"><b>WNA</b><span class="kick">${V6.stories().length} novels · ${W.fmt.num(W.stats.downloaded)} ch offline</span></div>
        <div class="seg"><button data-mode="deck">${ic('style')} Deck</button><button class="on">${ic('grid_view')} Grid</button></div>
      </header>
      <div class="selbar"><button class="ib" data-selx aria-label="Exit selection">${ic('close')}</button><b class="num" data-seln>0</b><span>selected</span><button class="btn sm ghost" data-selall>Select all</button></div>
      ${showContinue ? `<button class="cont" data-cont>${V6.cover(W.story(ses.storyId))}<span><span class="kick">Continue listening</span><b>${esc(V6.short(W.story(ses.storyId)))}</b><small>${esc(ses.chapterTitle)}</small></span><span class="cont-play">${ic('play_arrow', 'fill')}</span></button>` : ''}
      <div class="chips gr-tabs">${V6.tabList().map((t) => `<button class="chip ${t.id === V6.state.tab ? 'on' : ''}" data-tab="${t.id}">${esc(t.name)} <span class="n">${t.n}</span></button>`).join('')}</div>
      <div class="gr-tools">
        <label class="search">${ic('search')}<input type="search" placeholder="Search stories" value="${esc(F().query)}" data-q></label>
        <button class="gt" data-sort aria-label="Sort">${ic('swap_vert')}<span data-sortl></span></button>
        <button class="gt" data-filter aria-label="Filters">${ic('tune')}<span class="gt-n" data-fn></span></button>
        <button class="gt" data-view aria-label="Toggle view">${ic(view() === 'cover' ? 'view_list' : 'grid_view')}</button>
      </div>
      <div class="chips gr-active" data-active></div>
      <div class="gr-count"><span class="kick" data-count></span></div>
      <div class="gr-items" data-items></div>
      <div class="gr-sent" data-sent></div>
      <div class="selbtns"><button class="btn ghost" data-move>${ic('drive_file_move')} Move</button><button class="btn danger" data-del>${ic('delete')} Delete</button></div>`;
    const itemsEl = root.querySelector('[data-items]');

    function compute() {
      const f = F();
      list = W.filterStories({ tab: V6.state.tab, query: f.query, sources: f.sources, tags: f.tags, sort: f.sort, asc: f.asc }).filter((s) => !V6.state.deleted.has(s.id));
      const so = W.SORTS[f.sort] || W.SORTS.default; const asc = f.asc == null ? so.asc : f.asc;
      root.querySelector('[data-sortl]').textContent = `${so.short} ${asc ? '↑' : '↓'}`;
      const nf = f.sources.length + f.tags.length;
      root.querySelector('[data-fn]').textContent = nf ? nf : '';
      root.querySelector('[data-active]').innerHTML = nf ? [...f.sources.map((x) => `<button class="chip on" data-rm-src="${esc(x)}">${ic('public')}${esc(x)} ${ic('close')}</button>`), ...f.tags.map((x) => `<button class="chip acc on" data-rm-tag="${esc(x)}">${esc(x)} ${ic('close')}</button>`), '<button class="chip" data-clear>Clear filters</button>'].join('') : '';
      root.querySelector('[data-count]').textContent = `${list.length} novel${list.length === 1 ? '' : 's'}${nf || f.query ? ' · filtered' : ''} · ${so.label} ${asc ? '↑' : '↓'}`;
      itemsEl.className = 'gr-items ' + view();
      itemsEl.innerHTML = ''; shown = 0;
      if (!list.length) {
        itemsEl.innerHTML = V6.stories().length ? V6.empty('search_off', 'No matches', 'Try clearing your search or filters.', '<button class="btn ghost" data-clear>Clear filters</button>') : V6.empty('library_add', 'Your library is empty', 'Paste a link from RoyalRoad, Scribble Hub, SpaceBattles and more.', '<button class="btn acc" data-go="/add">Add a story</button>');
        return;
      }
      more();
    }
    function more() {
      const next = list.slice(shown, shown + 48);
      itemsEl.insertAdjacentHTML('beforeend', next.map((s) => (view() === 'cover' ? itemCover : itemList)(s, sel.has(s.id))).join(''));
      shown += next.length;
    }
    io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && shown < list.length) more(); }, { root, rootMargin: '600px' });
    io.observe(root.querySelector('[data-sent]'));
    ctx.sub(() => io.disconnect());

    // selection
    function updSel() {
      document.body.classList.toggle('selecting', sel.size > 0);
      root.classList.toggle('selmode', sel.size > 0);
      root.querySelector('[data-seln]').textContent = sel.size;
      itemsEl.querySelectorAll('[data-id]').forEach((b) => b.classList.toggle('sel', sel.has(b.dataset.id)));
    }
    ctx.sub(() => document.body.classList.remove('selecting'));
    V6.longPress(itemsEl, '[data-id]', (b) => { sel.add(b.dataset.id); updSel(); });

    const q = root.querySelector('[data-q]');
    q.addEventListener('input', W.debounce(() => { F().query = q.value; compute(); }, 120));

    root.addEventListener('click', (e) => {
      const t = e.target;
      if (t.closest('[data-mode]')) { V6.state.homeMode = 'deck'; return W.go('/library', { replace: true }); }
      const it = t.closest('[data-id]');
      if (it) { if (sel.size) { sel.has(it.dataset.id) ? sel.delete(it.dataset.id) : sel.add(it.dataset.id); return updSel(); } return W.go('/story/' + it.dataset.id); }
      const tb = t.closest('[data-tab]'); if (tb) { V6.state.tab = tb.dataset.tab; root.querySelectorAll('[data-tab]').forEach((x) => x.classList.toggle('on', x === tb)); return compute(); }
      if (t.closest('[data-cont]')) return V6.act.play(W.story(ses.storyId));
      if (t.closest('[data-view]')) { V6.state.gridView = view() === 'cover' ? 'list' : 'cover'; t.closest('[data-view]').innerHTML = ic(view() === 'cover' ? 'view_list' : 'grid_view'); return compute(); }
      if (t.closest('[data-sort]')) return sortSheet();
      if (t.closest('[data-filter]')) return filterSheet();
      const rs = t.closest('[data-rm-src]'); if (rs) { F().sources = F().sources.filter((x) => x !== rs.dataset.rmSrc); return compute(); }
      const rt = t.closest('[data-rm-tag]'); if (rt) { F().tags = F().tags.filter((x) => x !== rt.dataset.rmTag); return compute(); }
      if (t.closest('[data-clear]')) { Object.assign(F(), { sources: [], tags: [], query: '' }); q.value = ''; return compute(); }
      if (t.closest('[data-selx]')) { sel.clear(); return updSel(); }
      if (t.closest('[data-selall]')) { list.forEach((s) => sel.add(s.id)); return updSel(); }
      if (t.closest('[data-move]')) return V6.moveSheet([...sel], () => { sel.clear(); updSel(); });
      if (t.closest('[data-del]')) return V6.confirmDelete([...sel], () => { sel.clear(); updSel(); });
    });

    // swipe horizontally across the list to change tabs
    (() => {
      let x0 = null, y0 = 0;
      itemsEl.addEventListener('pointerdown', (e) => { if (e.pointerType === 'mouse') return; x0 = e.clientX; y0 = e.clientY; });
      itemsEl.addEventListener('pointerup', (e) => {
        if (x0 == null) return; const dx = e.clientX - x0, dy = e.clientY - y0; x0 = null;
        if (Math.abs(dx) > 90 && Math.abs(dy) < 40 && !sel.size) {
          const tabs = V6.tabList(); const i = tabs.findIndex((x) => x.id === V6.state.tab);
          const nt = tabs[Math.max(0, Math.min(tabs.length - 1, i + (dx < 0 ? 1 : -1)))];
          if (nt.id !== V6.state.tab) { V6.state.tab = nt.id; root.querySelectorAll('[data-tab]').forEach((x) => x.classList.toggle('on', x.dataset.tab === nt.id)); root.querySelector(`[data-tab="${nt.id}"]`).scrollIntoView({ inline: 'center', block: 'nearest' }); compute(); }
        }
      });
    })();

    function sortSheet() {
      const f = F(); const so = W.SORTS[f.sort]; const asc = f.asc == null ? so.asc : f.asc;
      V6.sheet({ title: 'Sort by', html: `<div class="sh-pad"><div class="seg wide"><button data-dir="1" class="${asc ? 'on' : ''}">${ic('arrow_upward')} Ascending</button><button data-dir="0" class="${!asc ? 'on' : ''}">${ic('arrow_downward')} Descending</button></div></div>
        ${Object.entries(W.SORTS).map(([k, v]) => `<button class="sh-it ${k === f.sort ? 'on' : ''}" data-k="${k}"><span class="sh-l"><b>${v.label}</b></span>${k === f.sort ? ic('check', 'sh-ck') : ''}</button>`).join('')}`,
      onMount(body, api) {
        body.addEventListener('click', (e) => {
          const d = e.target.closest('[data-dir]'); if (d) { f.asc = d.dataset.dir === '1'; body.querySelectorAll('[data-dir]').forEach((x) => x.classList.toggle('on', x === d)); compute(); return; }
          const k = e.target.closest('[data-k]'); if (k) { f.sort = k.dataset.k; f.asc = null; api.close(); compute(); }
        });
      } });
    }
    function filterSheet() {
      const base = W.storiesInTab(V6.state.tab).filter((s) => !V6.state.deleted.has(s.id));
      let allTags = false;
      const draw = (body) => {
        const f = F(); const tags = W.tagCounts(base);
        body.innerHTML = `<div class="sh-sec">Source</div><div class="wrapchips">${W.sourceCounts(base).map((x) => `<button class="chip ${f.sources.includes(x.name) ? 'on' : ''}" data-src="${esc(x.name)}">${ic('public')}${esc(x.name)} <span class="n">${x.count}</span></button>`).join('')}</div>
          <div class="sh-sec">Tags · match all</div><div class="wrapchips">${(allTags ? tags : tags.slice(0, 24)).map((x) => `<button class="chip acc ${f.tags.includes(x.name) ? 'on' : ''}" data-tg="${esc(x.name)}">${esc(x.name)} <span class="n">${x.count}</span></button>`).join('')}${!allTags && tags.length > 24 ? `<button class="chip" data-all>+${tags.length - 24} more</button>` : ''}</div>
          <div class="sh-pad row2"><button class="btn ghost" data-clr>Clear</button><button class="btn acc" data-done>Show ${list.length}</button></div>`;
      };
      V6.sheet({ title: 'Filters', sub: 'Sources and tags combine', onMount(body, api) {
        draw(body);
        body.addEventListener('click', (e) => {
          const f = F(); const s = e.target.closest('[data-src]'), tg = e.target.closest('[data-tg]');
          if (s) { const v = s.dataset.src; f.sources = f.sources.includes(v) ? f.sources.filter((x) => x !== v) : [...f.sources, v]; }
          else if (tg) { const v = tg.dataset.tg; f.tags = f.tags.includes(v) ? f.tags.filter((x) => x !== v) : [...f.tags, v]; }
          else if (e.target.closest('[data-all]')) allTags = true;
          else if (e.target.closest('[data-clr]')) { f.sources = []; f.tags = []; }
          else if (e.target.closest('[data-done]')) return api.close();
          else return;
          compute(); const sc = body.scrollTop; draw(body); body.scrollTop = sc;
        });
      } });
    }
    compute();
  };
})();
