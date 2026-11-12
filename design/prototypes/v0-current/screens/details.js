/* v0 Story Details (compact single scroll with "Hide details" / wide two-pane). */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon;
  const mem = {}; // per-story UI state
  const PAGE = 60;
  const stateFor = (id) => (mem[id] = mem[id] || { filter: 'all', q: '', expanded: true, desc: false, epub: 'none', syncing: false, removed: new Set(), bookmark: null });

  function metricChips(s) {
    const snaps = (W.data.metrics || {})[s.id] || [];
    const last = snaps[snaps.length - 1];
    if (!last || !last.m) return [];
    return Object.entries(last.m).filter(([, v]) => v != null).slice(0, 4).map(([k, v]) => `${W.fmt.num(v)} ${k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, ' ')}`);
  }
  function chapterSub(s, c) {
    const it = W.downloads.forStory(s.id);
    const q = it && it.chapters.find((x) => x.id === c.id);
    const parts = [`Index ${c.index}`];
    if (q && q.status === 'downloading') parts.push('Downloading…');
    else if (q && q.status === 'queued') parts.push('Queued');
    else if (q && q.status === 'failed') parts.push('Download failed');
    else if (q && q.status === 'paused') parts.push('Paused');
    else { if (c.audio) parts.push('Audio'); parts.push(c.downloaded ? 'Available offline' : 'Not downloaded'); }
    return parts.join('<i> &nbsp;•&nbsp; </i>');
  }

  A.register('story', (route, ctx) => {
    const s = W.story(route.params.id);
    if (!s) { const el = W.el(`<div>${A.appBar({ title: 'Not found' })}<div class="empty"><b>Story not found</b></div></div>`); return { el }; }
    const st = stateFor(s.id);
    if (st.bookmark == null) st.bookmark = s.bookmark;
    const wide = A.isWide();
    const chs = W.chapters(s.id);
    let rows = [], shown = 0, io = null;

    const el = W.el(`<div class="details ${wide ? 'wide' : 'compact'}">${A.appBar({ title: s.title, sub: `by ${s.author || 'Unknown'}`, actions: [{ id: 'more', icon: 'more_vert', label: 'More options' }] })}
      ${wide ? `<div class="d-panes"><div class="scroll d-left pb-mini" data-keep data-info></div><div class="d-vdiv"></div><div class="scroll d-right pb-mini" data-right><div data-controls></div><div class="d-list" data-list></div><div class="sentinel" data-sentinel></div></div></div>`
        : `<div class="scroll pb-mini" data-keep data-right><div class="d-compact-head"><button class="btn text d-toggle" data-a="toggle">${ic(st.expanded ? 'expand_less' : 'expand_more')}<span>${st.expanded ? 'Hide details' : 'Show details'}</span></button></div><div data-info class="${st.expanded ? '' : 'hidden'}"></div><div class="divider" style="margin:4px 16px 0"></div><div data-controls></div><div class="d-list" data-list></div><div class="sentinel" data-sentinel></div></div>`}
    </div>`);
    const $ = (q) => el.querySelector(q);

    function infoHtml() {
      const f = W.forecast(s); const tr = W.scoreTrend(s.id);
      const remaining = chs.filter((c) => !c.downloaded).length;
      const dl = W.downloads.forStory(s.id);
      const pat = s.patreon && s.patreon.members ? s.patreon : null;
      const lo = pat ? Math.round(pat.members * 3.15) : 0, hi = pat ? Math.round(pat.members * 9) : 0;
      const metrics = metricChips(s);
      const descLong = (s.desc || '').replace(/\n+/g, ' ');
      return `<div class="d-info">
        <button class="d-cover" data-a="zoom" aria-label="Zoom cover">${A.cover(s, 'c-detail')}</button>
        <h2 class="d-title">${esc(s.title)}</h2>
        <button class="d-author" data-a="author">${esc(s.author || 'Unknown')}</button>
        <div class="d-badges"><button class="pill src" data-a="source">${esc(s.source)}${ic('open_in_new')}</button>${A.pubPill(s)}${s.type ? `<span class="pill plain">${esc(s.type)}</span>` : ''}</div>
        ${f.text ? `<div class="d-forecast">${esc(f.text)}</div>` : ''}
        ${s.score ? `<button class="d-score" data-a="trends">${ic('star')}<b>${A.score(s)}</b>${tr ? `<span class="trend ${tr}">${ic(tr === 'up' ? 'arrow_upward' : 'arrow_downward')}</span>` : ''}</button>` : ''}
        <div class="lc-foot d-cov"><b>${s.downloaded} / ${s.total}</b>${A.capsule(s)}</div>
        <div class="d-pills">${metrics.map((m) => `<button class="pill plain" data-a="trends">${esc(m)}</button>`).join('')}${(s.warnings || []).map((w) => `<span class="pill warn">${esc(w)}</span>`).join('')}<span class="pill upd">Updated ${W.fmt.date(s.lastSync || s.updated)}</span></div>
        <div class="d-actions">
          <div data-sync>${st.syncing ? `<button class="btn filled full disabled"><span class="spinner"></span>Checking Source...</button><div class="d-msg">Fetching the chapter list from ${esc(s.source)}…</div>` : `<button class="btn filled full" data-a="sync">${ic('refresh')}Sync Chapters</button>`}</div>
          ${remaining && !dl ? `<button class="btn filled full" data-a="dlrem">${ic('download')}${remaining === chs.length ? 'Download All' : `Download Remaining (${remaining})`}</button><button class="btn text full" data-a="select">${ic('format_list_bulleted')}Select Chapters</button>` : ''}
          <div data-banner>${bannerHtml()}</div>
          ${st.epub === 'generating' ? `<button class="btn tonal full disabled"><span class="spinner"></span>Generating...</button>` : `<button class="btn tonal full" data-a="epub">${ic('menu_book', true)}Generate EPUB</button>`}
          <button class="btn outlined full${st.epub === 'ready' ? '' : ' disabled'}" data-a="readepub">${ic('menu_book', true)}Read EPUB</button>
        </div>
        ${pat ? `<div class="patreon card" data-a="ptrends">
          <div class="pt-head"><span class="pt-ic">${ic('star')}</span><b>Patreon</b><button class="icon-btn" data-a="pedit" aria-label="Edit Patreon link">${ic('edit')}</button><button class="icon-btn" data-a="popen" aria-label="Open Patreon">${ic('open_in_new')}</button></div>
          <div class="pt-nums"><div><b>${pat.members.toLocaleString('en-US')}</b><span>Paid members</span></div><div><b>${pat.usd ? '$' + pat.usd.toLocaleString('en-US') : `$${lo}–$${hi}`}</b><span>${pat.usd ? 'Monthly earnings' : 'Estimated amount'}</span></div></div>
          <div class="pt-upd">Updated ${W.fmt.date(s.lastSync || s.updated)} · per month · tap for trends</div>
          <div class="pt-early" data-a="pearly"><b>Early-access chapters</b><span>Not linked · tap to choose a Patreon collection</span></div>
        </div>` : ''}
        ${descLong ? `<div class="d-desc${st.desc ? ' open' : ''}">${esc(st.desc ? s.desc : descLong)}</div>
        <div class="d-desc-act"><button class="btn text" data-a="desc" style="padding:0">${st.desc ? 'Read less' : 'Read more'}</button><button class="btn text" data-a="listen">${ic('volume_up')}Listen</button></div>` : ''}
        <div class="d-tags">${s.tags.map((t) => `<span class="pill plain">${esc(t)}</span>`).join('')}</div>
      </div>`;
    }
    function bannerHtml() {
      const it = W.downloads.forStory(s.id);
      if (!it) return '';
      const cur = it.chapters.find((c) => c.status === 'downloading') || it.chapters.find((c) => c.status === 'queued');
      const label = it.status === 'paused' ? 'Download paused' : cur ? `Downloading: ${cur.title} (${it.done + 1}/${it.total})` : 'Finishing…';
      return `<div class="dl-banner"><div class="dlb-t">${esc(label)}</div><div class="bar"><i style="width:${(it.done / Math.max(1, it.total)) * 100}%"></i></div><button class="btn text sm" data-a="goq" style="align-self:flex-end">Go to Downloads</button></div>`;
    }
    function filtered() {
      const bm = st.bookmark ? st.bookmark - 1 : 0;
      let list = chs;
      if (st.filter === 'downloaded') list = list.filter((c) => c.downloaded);
      if (st.filter === 'bookmark') list = list.slice(bm);
      if (st.filter === 'removed') list = [];
      const q = st.q.trim().toLowerCase();
      if (q) list = list.filter((c) => c.title.toLowerCase().includes(q) || String(c.index) === q);
      return list;
    }
    function controlsHtml() {
      const bmCount = st.bookmark ? chs.length - st.bookmark + 1 : 0;
      const chip = (k, label) => `<button class="chip${st.filter === k ? ' sel' : ''}" data-filter="${k}">${st.filter === k ? ic('check') : ''}${label}</button>`;
      return `<div class="d-controls"><div class="field"><input data-a="cq" placeholder="Search chapters" value="${esc(st.q)}"></div>
        <div class="chip-row" style="margin-top:12px">${chip('all', 'All')}${chip('downloaded', 'Downloaded')}${bmCount ? chip('bookmark', `From Bookmark (${bmCount})`) : ''}</div></div>`;
    }
    function rowHtml(c) {
      const bm = st.bookmark === c.index;
      return `<div class="ch-row" data-cid="${esc(c.id)}"><span class="dot${c.downloaded ? ' on' : ''}"></span><div class="ch-t"><b>${esc(c.title)}</b><span data-sub>${chapterSub(s, c)}</span></div><button class="icon-btn bm${bm ? ' on' : ''}" data-bm="${c.index}" aria-label="Bookmark">${ic('bookmark', !bm)}</button></div>`;
    }
    function more() {
      const box = $('[data-list]'); const next = rows.slice(shown, shown + PAGE); shown += next.length;
      box.insertAdjacentHTML('beforeend', next.map(rowHtml).join(''));
      if (shown >= rows.length && io) io.disconnect();
    }
    function drawList() {
      rows = filtered(); shown = 0; const box = $('[data-list]');
      box.innerHTML = rows.length ? '' : `<div class="empty" style="min-height:0;padding:32px"><b>No chapters</b><span>${st.q ? 'No chapter matches your search.' : 'Nothing to show for this filter.'}</span></div>`;
      more();
      if (io) io.disconnect();
      io = new IntersectionObserver((e) => { if (e[0].isIntersecting) more(); }, { root: $('[data-right]'), rootMargin: '600px' });
      io.observe($('[data-sentinel]'));
    }
    function drawInfo() { $('[data-info]').innerHTML = infoHtml(); }
    function drawControls() { $('[data-controls]').innerHTML = controlsHtml(); }
    drawInfo(); drawControls(); drawList();
    if (route.query.menu) setTimeout(openMenu, 0);

    function refreshRows() { el.querySelectorAll('.ch-row').forEach((r) => { const c = chs.find((x) => x.id === r.dataset.cid); if (c) { r.querySelector('[data-sub]').innerHTML = chapterSub(s, c); r.querySelector('.dot').classList.toggle('on', c.downloaded); } }); }
    let hadDl = !!W.downloads.forStory(s.id);
    ctx.sub(W.downloads.on(() => {
      const it = W.downloads.forStory(s.id);
      if (!!it !== hadDl) { hadDl = !!it; const sc = $('[data-info]').closest('.scroll'); const y = sc.scrollTop; drawInfo(); sc.scrollTop = y; }
      else if (it) { const b = $('[data-banner]'); if (b) b.innerHTML = bannerHtml(); const cov = $('.d-cov'); if (cov) cov.innerHTML = `<b>${s.downloaded} / ${s.total}</b>${A.capsule(s)}`; }
      if (it || hadDl) refreshRows();
    }));

    function openMenu() {
      A.menu({ sections: [
        { items: [{ label: 'Full Sync', fn: () => A.confirm({ title: 'Full Sync', message: 'Re-check every chapter against the source, including edited and removed chapters. This can take a while for long stories.', ok: 'Start', fn: doSync }) },
          { label: 'Edit Patreon Link', fn: editPatreon }] },
        { label: 'EPUB', items: [{ label: 'EPUB Settings', fn: epubSettings }, { label: 'EPUB Files', fn: epubFiles }, { label: 'Apply Text Cleanup', fn: () => A.confirm({ title: 'Apply Text Cleanup', message: `Run your cleanup rules over the ${s.downloaded} downloaded chapters of ${esc(s.title)}?`, ok: 'Apply', fn: () => A.toast(`Cleaned ${s.downloaded} chapters · 14 changes`) }) }] },
        { label: 'Explore', items: [{ label: 'Trends', fn: () => W.go(`/story/${s.id}/trends`) }, { label: 'AI Controls', fn: () => W.go(`/story/${s.id}/ai`) }, { label: 'Narration audio', fn: () => W.go(`/narration/${s.id}`) }] },
        { divider: true, items: [{ label: 'Delete Novel', danger: true, fn: () => A.confirm({ title: 'Delete Novel', message: `Delete "${esc(s.title)}" and all its downloaded chapters?`, ok: 'Delete', danger: true, fn: () => { const all = W.stories(); const i = all.indexOf(s); if (i >= 0) all.splice(i, 1); A.toast('Novel deleted'); W.go('/library', { replace: true }); } }) }] },
      ] });
    }
    function doSync() {
      st.syncing = true; drawInfo();
      setTimeout(() => { st.syncing = false; if ($('[data-info]')) drawInfo(); A.toast(W.forecast(s).state === 'due' ? 'Found 1 new chapter' : 'No new chapters'); }, 2600);
    }
    function editPatreon() { A.prompt({ title: 'Edit Patreon Link', label: 'Patreon URL', value: `https://www.patreon.com/${(s.author || 'author').replace(/\s+/g, '')}`, fn: () => A.toast('Patreon link saved') }); }
    function epubSettings() {
      const max = W.settings.downloads.maxPerEpub;
      A.dialog({ title: 'EPUB Settings', body: `<div class="field-label">Start chapter</div><div class="field"><input value="1" inputmode="numeric"></div><div class="field-label">End chapter</div><div class="field"><input value="${s.total}" inputmode="numeric"></div><div class="field-label">Max chapters per EPUB</div><div class="field"><input value="${max}" inputmode="numeric"></div>`, actions: [{ label: 'Cancel' }, { label: 'Save', fn: () => A.toast('EPUB settings saved') }] });
    }
    function epubFiles() {
      const max = W.settings.downloads.maxPerEpub; const files = [];
      if (st.epub === 'ready') for (let i = 0; i < s.total; i += max) files.push(`${s.title} (${i + 1}-${Math.min(s.total, i + max)}).epub`);
      A.dialog({ title: 'EPUB Files', body: files.length ? files.map((f) => `<div class="dg-item" style="font-size:16px">${ic('menu_book', true)}<div style="flex:1">${esc(f)}<small>${(1.2 + Math.random()).toFixed(1)} MB</small></div><button class="icon-btn" onclick="App.toast('Sharing EPUB…')">${ic('share')}</button></div>`).join('') : '<p style="margin:0">No EPUB files yet. Use Generate EPUB to create one.</p>', actions: [{ label: 'Close' }] });
    }

    el.addEventListener('input', W.debounce((e) => { if (e.target.matches('[data-a="cq"]')) { st.q = e.target.value; drawList(); } }, 150));
    el.addEventListener('click', (e) => {
      const f = e.target.closest('[data-filter]'); if (f) { st.filter = f.dataset.filter; drawControls(); drawList(); return; }
      const bm = e.target.closest('[data-bm]');
      if (bm) { const i = +bm.dataset.bm; const prev = st.bookmark; st.bookmark = prev === i ? null : i; s.bookmark = st.bookmark; el.querySelectorAll('.bm.on').forEach((b) => { b.classList.remove('on'); b.innerHTML = ic('bookmark', true); }); if (st.bookmark != null) { bm.classList.add('on'); bm.innerHTML = ic('bookmark'); } A.toast(st.bookmark != null ? `Bookmarked chapter ${i}` : 'Bookmark removed'); drawControls(); const cov = $('.d-cov'); if (cov) cov.innerHTML = `<b>${s.downloaded} / ${s.total}</b>${A.capsule(s)}`; return; }
      const row = e.target.closest('.ch-row'); if (row) { W.go(`/reader/${s.id}/${row.dataset.cid}`); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const act = a.dataset.a;
      if (act === 'more') openMenu();
      else if (act === 'toggle') { st.expanded = !st.expanded; $('[data-info]').classList.toggle('hidden', !st.expanded); a.innerHTML = `${ic(st.expanded ? 'expand_less' : 'expand_more')}<span>${st.expanded ? 'Hide details' : 'Show details'}</span>`; }
      else if (act === 'zoom') { const u = W.coverUrl(s); A.dialog({ body: u ? `<img src="${u}" alt="" style="width:100%;border-radius:8px;margin-top:24px">` : `<div class="cover fallback" style="aspect-ratio:2/3;margin-top:24px;background:${W.coverFallback(s)}"><span>${esc(s.title)}</span></div>`, actions: [{ label: 'Close' }] }); }
      else if (act === 'author') W.go('/author/' + s.id);
      else if (act === 'source') A.toast(`Opening ${s.source}…`);
      else if (act === 'trends' || (act === 'ptrends' && !e.target.closest('[data-a="pedit"],[data-a="popen"],[data-a="pearly"]'))) W.go(`/story/${s.id}/trends`);
      else if (act === 'pedit') { e.stopPropagation(); editPatreon(); }
      else if (act === 'popen') { e.stopPropagation(); A.toast('Opening Patreon…'); }
      else if (act === 'pearly') { e.stopPropagation(); A.choose({ title: 'Early-access collection', options: [{ value: 'a', label: 'Advanced chapters' }, { value: 'b', label: 'Early access (+15 chapters)' }, { value: 'n', label: 'None' }], value: 'n', fn: () => A.toast('Patreon collection linked') }); }
      else if (act === 'sync') doSync();
      else if (act === 'dlrem') { W.downloads.enqueue(s.id); A.toast(`Queued ${chs.filter((c) => !c.downloaded).length} chapters`); }
      else if (act === 'select') W.go(`/story/${s.id}/select`);
      else if (act === 'goq') W.go('/queue');
      else if (act === 'epub') { st.epub = 'generating'; drawInfo(); setTimeout(() => { st.epub = 'ready'; if ($('[data-info]')) drawInfo(); A.toast('EPUB generated'); }, 2500); }
      else if (act === 'readepub') A.toast('Opening EPUB in your reader app…');
      else if (act === 'desc') { st.desc = !st.desc; drawInfo(); }
      else if (act === 'listen') { A.toast('Reading the description aloud'); }
    });
    return { el, destroy: () => io && io.disconnect() };
  });
})();
