/* v1 Refined — Story Details. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;

  const TYPE_ICON = { Original: 'edit_note', Fanfiction: 'diversity_3', Translation: 'translate' };

  V.chapterRowStatus = (s, c, dl) => {
    const q = dl && dl.chapters.find((x) => x.id === c.id);
    const parts = [];
    if (q && q.status === 'downloading') parts.push(`<span class="live"><span class="spin"></span>Downloading…</span>`);
    else if (q && q.status === 'queued') parts.push(`<span>${V.ic('schedule', 'xs')} Queued</span>`);
    else if (q && q.status === 'failed') parts.push(`<span class="bad">${V.ic('error', 'xs')} Download failed</span>`);
    else if (c.downloaded) parts.push(`<span class="ok">${V.ic('offline_pin', 'xs')}Offline</span>`);
    else parts.push('<span>Not downloaded</span>');
    if (c.audio) parts.push(`<span class="au">${V.ic('graphic_eq', 'xs')}Audio</span>`);
    if (c.publishedAt) parts.push(`<span>${fmt.dateShort(c.publishedAt)}</span>`);
    return parts.join('');
  };

  V.screens.story = (r) => {
    const s = WNA.story(r.params.id);
    if (!s) return { el: WNA.el(`<div class="screen">${V.appBar({ title: 'Story not found' })}${V.empty({ icon: 'search_off', title: 'This story is not in your library' })}</div>`) };
    const id = s.id;
    const st = { q: '', filter: 'all', descOpen: false, syncing: false, syncMsg: null, epub: null, epubReady: false, patLinked: null, inc: null };
    const hidden = () => !!V.state.detailsHidden[id];

    const el = WNA.el(`
      <div class="screen det">
        <div class="det-appbar">${V.appBar({ title: s.title, subtitle: s.author ? 'by ' + s.author : '', fadeTitle: true, actions: V.ib('more_vert', 'more', 'More options') })}</div>
        <div class="det-grid">
          <div class="pane pane-l"><div class="info-slot"></div></div>
          <div class="pane pane-r">
            <section class="chap-sec">
              <div class="chap-h"><h2>Chapters<small class="tnum chn"></small></h2>${V.btn('Select', 'select', { kind: 'text', icon: 'checklist', cls: 'sm' })}</div>
              <div class="chap-tools sticky">
                <label class="search">${V.ic('search')}<input type="search" placeholder="Search chapters" aria-label="Search chapters"></label>
                <div class="chip-scroll cfilters"></div>
              </div>
              <div class="chap-list"></div>
            </section>
            <div class="pad-bottom"></div>
          </div>
        </div>
      </div>`);
    const $ = (q) => el.querySelector(q);

    // ---------- info pane ----------
    function infoHtml() {
      const fc = WNA.forecast(s);
      const tr = WNA.scoreTrend(id);
      const m = V.metrics(s);
      const remaining = s.total - s.downloaded;
      const dl = WNA.downloads.forStory(id);
      const bmc = s.bookmark != null ? WNA.chapters(id)[s.bookmark] : null;
      const fcIcon = { due: 'sync', late: 'schedule', expected: 'event_upcoming', unknown: s.pub === 'completed' ? 'task_alt' : 'event_busy' }[fc.state];
      const wide = matchMedia('(min-width: 760px)').matches;
      const aiS = WNA.ai.state(id);
      const aiD = aiS.showAiDesc && aiS.desc ? aiS.desc : '';
      return `
        <div class="det-hero">
          <button class="covbtn" data-act="zoom" aria-label="Zoom cover">${V.cover(s, wide ? 168 : 112)}</button>
          <div class="info">
            <h1 class="det-title">${esc(s.title)}</h1>
            ${s.author ? `<button class="det-author" data-act="author">${esc(s.author)}${V.ic('chevron_right')}</button>` : ''}
            <div class="det-badges">
              <span class="pill src" data-act="source">${esc(s.source)}${V.ic('open_in_new')}</span>
              ${V.pubPill(s)}
              ${s.type ? `<span class="pill">${V.ic(TYPE_ICON[s.type] || 'label')}${esc(s.type)}</span>` : ''}
            </div>
            ${s.score ? `<button class="det-score" data-act="trends">${V.ic('star', 'fill')}<b>${esc(s.score)}</b>${tr ? `<span class="trend ${tr}">${tr === 'up' ? '↑' : '↓'}</span>` : ''}<small>Score</small></button>` : ''}
          </div>
        </div>
        ${fc.text ? `<div class="det-fc ${fc.state}">${V.ic(fcIcon)}<span>${fc.state === 'due' ? `<b>Next chapter may be out</b> · sync to check` : esc(fc.text)}</span></div>` : ''}
        <div class="det-cvg">
          <div class="lbl"><span><b>${fmt.int(s.downloaded)} / ${fmt.int(s.total)}</b>&nbsp; downloaded</span>${bmc ? `<span class="bml">${V.ic('bookmark', 'xs')}Ch. ${s.bookmark + 1}</span>` : '<span>No bookmark</span>'}</div>
          ${V.coverage(s)}
        </div>
        <div class="det-chips">
          ${m.followers != null ? `<button class="mchip" data-act="trends">${V.ic('group')}<b>${fmt.num(m.followers)}</b> Followers</button>` : ''}
          ${m.views != null ? `<button class="mchip" data-act="trends">${V.ic('visibility')}<b>${fmt.num(m.views)}</b> Views</button>` : ''}
          <span class="mchip">${V.ic('update')}Updated ${fmt.date(s.updated)}</span>
          ${(s.warnings || []).map((w) => `<span class="mchip warn">${esc(w)}</span>`).join('')}
        </div>
        <div class="det-actions">
          <div class="primary">
            <button class="btn filled lg" data-act="read">${V.ic(bmc ? 'auto_stories' : 'menu_book')}<span>${bmc ? 'Continue reading' : 'Start reading'}</span><span class="sub">Ch. ${bmc ? s.bookmark + 1 : 1}</span></button>
            <button class="btn tonal lg sqr" data-act="listen" aria-label="Listen">${V.ic('headphones')}</button>
          </div>
          <div class="trio">
            <button class="btn tonal" data-act="sync" ${st.syncing ? 'disabled' : ''}>${st.syncing ? '<span class="spin"></span>' : V.ic('sync')}<span>${st.syncing ? 'Checking' : 'Sync'}</span></button>
            <button class="btn tonal" data-act="download" ${remaining <= 0 || dl ? 'disabled' : ''}>${V.ic(remaining > 0 ? 'download' : 'download_done')}<span>${dl ? 'Queued' : remaining > 0 ? `Get ${fmt.num(remaining)}` : 'All saved'}</span></button>
            <button class="btn tonal" data-act="epub">${st.epub != null ? '<span class="spin"></span>' : V.ic('menu_book')}<span>EPUB</span></button>
          </div>
          ${st.syncMsg ? `<div class="det-status ${st.syncMsg.ok ? 'ok' : ''}">${st.syncMsg.ok ? V.ic('check_circle') : '<span class="spin"></span>'}<span class="grow">${esc(st.syncMsg.text)}</span></div>` : ''}
          ${st.epub != null ? `<div class="det-status"><span class="spin"></span><span class="grow">Generating EPUB… ${Math.round(st.epub * 100)}%</span></div>` : ''}
          ${st.epubReady && st.epub == null ? `<div class="det-status ok">${V.ic('check_circle')}<span class="grow">EPUB ready · ${esc(V.short(s.title))}.epub</span><button class="btn text sm" data-act="readepub">Read</button></div>` : ''}
          ${dl ? dlBanner(dl) : ''}
        </div>
        <div class="det-more">
          ${s.patreon && s.patreon.members ? patreonCard() : ''}
          <section class="section">
            <div class="sec-h"><h2>About</h2></div>
            ${aiD ? `<span class="pill ongoing" style="margin-bottom:8px">${V.ic('auto_awesome')}AI description</span>` : ''}
            <p class="desc ${st.descOpen ? '' : 'clamp'}">${esc(aiD || s.desc || 'No description.')}</p>
            <div class="desc-acts">${V.btn(st.descOpen ? 'Read less' : 'Read more', 'desc', { kind: 'text', cls: 'sm' })}${V.btn('Listen', 'desclisten', { kind: 'text', cls: 'sm', icon: 'volume_up' })}</div>
            <div class="tagrow">${s.tags.map((t) => `<button class="chip" data-act="tag" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}</div>
          </section>
        </div>
        <div class="hide-toggle">${V.btn(hidden() ? 'Show details' : 'Hide details', 'hide', { kind: 'text', cls: 'sm', icon: hidden() ? 'expand_more' : 'expand_less' })}</div>
        <div class="wide-pad"></div>`;
    }
    function dlBanner(dl) {
      const cur = dl.chapters.find((c) => c.status === 'downloading');
      return `<div class="dlban">
        <div class="top">${V.ic('downloading', 'gold')}<div class="grow"><b class="ell" style="display:block">${dl.status === 'paused' ? 'Download paused' : cur ? esc(cur.title) : 'Waiting to download'}</b><span class="tnum">${dl.done} of ${dl.total} chapters${dl.failed ? ` · ${dl.failed} failed` : ''}</span></div>${V.btn('Downloads', 'gotoq', { kind: 'text', cls: 'sm' })}</div>
        <div class="prog"><i style="width:${V.pct(dl.done / Math.max(1, dl.total))}"></i></div></div>`;
    }
    function patreonCard() {
      const p = s.patreon;
      const lo = p.usd ? Math.round(p.usd * 0.62) : Math.round(p.members * 3.15);
      const hi = p.usd ? Math.round(p.usd * 1.2) : Math.round(p.members * 9);
      return `<section class="section"><div class="card pcard" data-act="trends">
        <div class="ph"><span class="lbl">${V.ic('volunteer_activism', 'fill')}Patreon</span>${V.ib('edit', 'patedit', 'Edit Patreon link')}${V.ib('open_in_new', 'patopen', 'Open Patreon')}</div>
        <div class="pstats"><div><b>${fmt.int(p.members)}</b><span>Paid members</span></div><div><b>$${fmt.int(lo)}–$${fmt.int(hi)}</b><span>Estimated per month</span></div></div>
        <div class="pfoot">Updated ${fmt.date(s.lastSync)} · tap for trends</div>
        <div class="pea" data-act="patlink">${V.ic('lock_clock')}<div class="grow"><b>Early-access chapters</b><span>${st.patLinked ? esc(st.patLinked) : 'Not linked · choose a Patreon collection'}</span></div>${V.ic('chevron_right')}</div>
      </div></section>`;
    }
    function renderInfo() {
      const slot = $('.info-slot');
      slot.innerHTML = infoHtml();
      el.classList.toggle('det-collapsed', hidden());
    }

    // ---------- chapter list ----------
    const chapters = () => WNA.chapters(id);
    function filtered() {
      const q = st.q.trim().toLowerCase();
      let list = chapters();
      if (st.filter === 'dl') list = list.filter((c) => c.downloaded);
      if (st.filter === 'bm' && s.bookmark != null) list = list.slice(s.bookmark);
      if (st.filter === 'nd') list = list.filter((c) => !c.downloaded);
      if (q) list = list.filter((c) => c.title.toLowerCase().includes(q) || String(c.index) === q);
      return list;
    }
    function rowHtml(c) {
      const dl = WNA.downloads.forStory(id);
      const isBm = s.bookmark === c.index - 1;
      return `<div class="cr ${c.downloaded ? '' : 'off'} ${isBm ? 'cur' : ''}" data-cid="${esc(c.id)}" role="button" tabindex="0">
        <span class="idx">${c.index}</span>
        <div class="grow"><div class="ct clamp2">${esc(c.title)}</div><div class="cs">${V.chapterRowStatus(s, c, dl)}</div></div>
        <button class="ib bmk ${isBm ? 'on' : ''}" data-act="bm" data-cid="${esc(c.id)}" aria-label="${isBm ? 'Remove bookmark' : 'Bookmark'}">${V.ic('bookmark', isBm ? 'fill' : '')}</button>
      </div>`;
    }
    function renderFilters() {
      const list = chapters();
      const dlN = list.filter((c) => c.downloaded).length;
      const nd = list.length - dlN;
      const fromBm = s.bookmark != null ? list.length - s.bookmark : 0;
      const chip = (k, label) => `<button class="chip ${st.filter === k ? 'on' : ''}" data-act="cf" data-k="${k}">${st.filter === k ? V.ic('check') : ''}${label}</button>`;
      $('.cfilters').innerHTML = [
        chip('all', `All <span class="n">${fmt.int(list.length)}</span>`),
        chip('dl', `Downloaded <span class="n">${fmt.int(dlN)}</span>`),
        s.bookmark != null ? chip('bm', `From bookmark <span class="n">${fmt.int(fromBm)}</span>`) : '',
        nd ? chip('nd', `Not downloaded <span class="n">${fmt.int(nd)}</span>`) : '',
      ].join('');
      $('.chn').textContent = fmt.int(list.length);
    }
    function renderList() {
      if (st.inc) st.inc.disconnect();
      const box = $('.chap-list');
      box.innerHTML = '';
      const list = filtered();
      if (!list.length) { box.innerHTML = V.empty({ icon: 'manage_search', title: 'No chapters match', body: st.q ? `Nothing matches “${st.q}”.` : 'Try another filter.' }); return; }
      const wide = matchMedia('(min-width: 760px)').matches;
      st.inc = V.incremental(box, wide ? $('.pane-r') : el, list, rowHtml, { chunk: 60 });
    }
    function refreshRows() {
      const dl = WNA.downloads.forStory(id);
      el.querySelectorAll('.cr[data-cid]').forEach((row) => {
        const c = chapters().find((x) => x.id === row.dataset.cid);
        if (!c) return;
        const isBm = s.bookmark === c.index - 1;
        row.classList.toggle('cur', isBm);
        row.classList.toggle('off', !c.downloaded);
        row.querySelector('.cs').innerHTML = V.chapterRowStatus(s, c, dl);
        const b = row.querySelector('.bmk'); b.classList.toggle('on', isBm); b.innerHTML = V.ic('bookmark', isBm ? 'fill' : '');
      });
    }

    renderInfo(); renderFilters(); renderList();

    // ---------- actions ----------
    const input = $('.chap-tools input');
    input.addEventListener('input', WNA.debounce(() => { st.q = input.value; renderList(); }, 150));

    function startSync(full) {
      if (st.syncing) return;
      st.syncing = true; st.syncMsg = { text: full ? 'Full sync · re-reading every chapter page…' : `Checking ${s.source} for new chapters…` }; renderInfo();
      setTimeout(() => { st.syncMsg = { text: 'Comparing chapter list…' }; renderInfo(); }, 1100);
      setTimeout(() => {
        st.syncing = false;
        const due = WNA.forecast(s).state === 'due' && !s._synced;
        s._synced = true;
        if (due) {
          const list = chapters(); const n = 2;
          for (let i = 0; i < n; i++) { const idx = list.length + 1; list.push({ id: `${id}-new${idx}`, title: `Chapter ${idx}`, downloaded: false, publishedAt: Date.now() - (n - i) * 3600e3, index: idx, audio: false }); }
          s.total += n; s.lastSync = Date.now();
          st.syncMsg = { ok: true, text: `${n} new chapters found` };
          V.toast(`${n} new chapters found`, { action: 'Download', onAction: () => act.download() });
          V.libDirty = true;
        } else { s.lastSync = Date.now(); st.syncMsg = { ok: true, text: 'Up to date · no new chapters' }; }
        renderInfo(); renderFilters(); renderList();
        setTimeout(() => { st.syncMsg = null; renderInfo(); }, 4200);
      }, 2300);
    }
    function genEpub() {
      if (st.epub != null) return;
      st.epub = 0; renderInfo();
      const t = setInterval(() => {
        st.epub = Math.min(1, st.epub + 0.12 + Math.random() * 0.1);
        if (st.epub >= 1) { clearInterval(t); st.epub = null; st.epubReady = true; V.toast('EPUB generated', { action: 'Read', onAction: () => V.toast('Opening in your EPUB reader…') }); }
        renderInfo();
      }, 420);
    }
    function toggleBookmark(cid) {
      const c = chapters().find((x) => x.id === cid);
      const was = s.bookmark;
      s.bookmark = s.bookmark === c.index - 1 ? null : c.index - 1;
      renderInfo(); renderFilters(); refreshRows(); V.libDirty = true;
      V.toast(s.bookmark == null ? 'Bookmark removed' : `Bookmarked chapter ${c.index}`, { action: 'Undo', onAction: () => { s.bookmark = was; renderInfo(); renderFilters(); refreshRows(); } });
    }
    function openReader(c) { WNA.go(`/reader/${id}/${encodeURIComponent(c.id)}`); }

    const act = {
      more: openMore,
      author: () => WNA.go('/author/' + id),
      trends: () => WNA.go(`/story/${id}/trends`),
      source: () => V.toast(`Opening ${(WNA.sources.find((x) => x.name === s.source) || {}).host || 'source'} in the browser…`),
      zoom() {
        const url = V.coverOf(s);
        const z = WNA.el(`<div class="zoom scrim">${url ? `<img src="${url}" alt="">` : V.cover(s, 260)}</div>`);
        z.addEventListener('click', () => { z.classList.add('out'); setTimeout(() => z.remove(), 200); });
        document.getElementById('chrome').appendChild(z);
      },
      read() { const list = chapters(); const c = list[s.bookmark != null ? s.bookmark : 0] || list[0]; openReader(c); },
      listen() {
      const ps = WNA.player.state();
        if (!(ps.loaded && ps.story.id === id)) {
          const ss = WNA.session;
          if (ss && ss.storyId === id) WNA.player.load(id, ss.chapterId, { index: ss.index });
          else { const list = chapters(); const c = list[s.bookmark != null ? s.bookmark : 0]; WNA.player.load(id, c.id); }
        }
        WNA.go('/player');
      },
      sync: () => startSync(false),
      download() {
        const n = s.total - s.downloaded;
        WNA.downloads.enqueue(id);
        renderInfo(); refreshRows();
        V.toast(`Downloading ${V.plural(n, 'chapter')}`, { action: 'View', onAction: () => WNA.go('/queue') });
      },
      epub: () => V.menu({
        title: 'EPUB', sub: st.epubReady ? `${V.short(s.title)}.epub · 4.2 MB` : `${fmt.int(s.downloaded)} chapters · up to 150 per file`,
        items: [
          { act: 'gen', icon: 'auto_stories', label: st.epubReady ? 'Regenerate EPUB' : 'Generate EPUB' },
          { act: 'read', icon: 'chrome_reader_mode', label: 'Read EPUB', sub: st.epubReady ? 'Open in your EPUB reader' : 'Generate it first', disabled: !st.epubReady },
          { act: 'files', icon: 'folder_open', label: 'EPUB files' },
          { act: 'settings', icon: 'tune', label: 'EPUB settings' },
        ],
        onPick(a) {
          if (a === 'gen') genEpub();
          if (a === 'read') V.toast('Opening in your EPUB reader…');
          if (a === 'files') V.toast('1 file · Downloads/WebnovelArchiver');
          if (a === 'settings') WNA.go('/settings/downloads');
        },
      }),
      readepub: () => V.toast('Opening in your EPUB reader…'),
      gotoq: () => WNA.go('/queue'),
      desc() { st.descOpen = !st.descOpen; renderInfo(); },
      desclisten: () => V.toast('Reading the description aloud'),
      tag(t) { const S = V.state; S.tab = 'all'; S.tags = [t.dataset.tag]; S.sources = []; S.query = ''; V.libDirty = true; WNA.go('/library'); },
      hide() { V.state.detailsHidden[id] = !hidden(); renderInfo(); },
      patedit: async () => {
        const r2 = await V.dialog({ title: 'Patreon link', html: V.field('url', 'Patreon page', `https://www.patreon.com/${(s.author || 'author').toLowerCase().replace(/\W/g, '')}`, { help: 'Used for member counts and early-access chapters.' }), actions: [{ label: 'Remove', value: 'rm' }, { label: 'Cancel', value: null }, { label: 'Save', value: 'save', kind: 'filled' }] });
        if (r2.value === 'save') V.toast('Patreon link saved'); else if (r2.value === 'rm') V.toast('Patreon link removed');
      },
      patopen: () => V.toast('Opening Patreon in the browser…'),
      patlink: () => V.menu({
        title: 'Early-access chapters', sub: 'Pick the Patreon collection that holds advance chapters.',
        items: [
          { act: `${V.short(s.title)} — Advance chapters`, icon: 'collections_bookmark', label: `${V.short(s.title)} — Advance chapters`, sub: '18 posts · updated 2 days ago', on: st.patLinked === `${V.short(s.title)} — Advance chapters` },
          { act: 'Book 2 drafts', icon: 'collections_bookmark', label: 'Book 2 drafts', sub: '4 posts', on: st.patLinked === 'Book 2 drafts' },
          { act: '', icon: 'link_off', label: 'Not linked' },
        ],
        onPick(a) { st.patLinked = a || null; renderInfo(); V.toast(a ? 'Collection linked' : 'Unlinked'); },
      }),
      select: () => WNA.go(`/story/${id}/select`),
      cf(t) { st.filter = t.dataset.k; renderFilters(); renderList(); },
      bm(t) { toggleBookmark(t.dataset.cid); },
    };
    V.bind(el, act);
    WNA.on(el, 'click', '.cr[data-cid]', (e, t) => {
      const c = chapters().find((x) => x.id === t.dataset.cid);
      if (!c) return;
      if (c.downloaded) openReader(c);
      else V.toast(`Chapter ${c.index} isn’t downloaded yet`, { action: 'Download', onAction: () => { WNA.downloads.enqueue(id, [c.id]); renderInfo(); refreshRows(); } });
    });

    function openMore() {
      V.menu({
        title: s.title, sub: s.author ? `by ${s.author}` : '',
        groups: [
          { items: [
            { act: 'full', icon: 'cloud_sync', label: 'Full sync', sub: 'Re-read every chapter from the source' },
            { act: 'pat', icon: 'volunteer_activism', label: 'Edit Patreon link' },
            { act: 'clean', icon: 'cleaning_services', label: 'Apply text cleanup' },
          ] },
          { label: 'EPUB', items: [
            { act: 'epubset', icon: 'tune', label: 'EPUB settings' },
            { act: 'epubfiles', icon: 'folder_open', label: 'EPUB files' },
          ] },
          { label: 'Explore', items: [
            { act: 'trends', icon: 'monitoring', label: 'Trends' },
            { act: 'ai', icon: 'auto_awesome', label: 'AI controls' },
            { act: 'narr', icon: 'graphic_eq', label: 'Narration audio' },
          ] },
          { items: [{ act: 'del', icon: 'delete', label: 'Delete novel', danger: true }] },
        ],
        async onPick(a) {
          if (a === 'full') startSync(true);
          if (a === 'pat') act.patedit();
          if (a === 'clean') { V.toast('Applying cleanup rules…'); setTimeout(() => V.toast('Cleaned 14 chapters · 2 rules matched'), 1600); }
          if (a === 'epubset') WNA.go('/settings/downloads');
          if (a === 'epubfiles') V.toast(st.epubReady ? '1 file · Downloads/WebnovelArchiver' : 'No EPUB files yet');
          if (a === 'trends') WNA.go(`/story/${id}/trends`);
          if (a === 'ai') WNA.go(`/story/${id}/ai`);
          if (a === 'narr') WNA.go(`/narration/${id}`);
          if (a === 'del') {
            const ok = await V.confirm('Delete this novel?', `“${s.title}”, its ${fmt.int(s.downloaded)} downloaded chapters and generated audio will be removed from this phone.`, 'Delete');
            if (ok) { V.state.removed.add(id); V.libDirty = true; WNA.back(); setTimeout(() => V.toast('Novel deleted', { action: 'Undo', onAction: () => { V.state.removed.delete(id); if (V.refreshLibrary) V.refreshLibrary(); } }), 300); }
          }
        },
      });
    }

    // ---------- live updates ----------
    const subs = V.subs();
    let prevDl = JSON.stringify(WNA.downloads.forStory(id));
    subs.add(WNA.downloads.on(() => {
      const now = JSON.stringify(WNA.downloads.forStory(id));
      if (now === prevDl) return;
      prevDl = now; renderInfo(); renderFilters(); refreshRows();
    }));

    // title appears in the app bar once the hero title scrolls away
    const titles = $('.det-appbar .titles');
    const io = new IntersectionObserver(([e]) => titles.classList.toggle('show', !e.isIntersecting && e.boundingClientRect.top < 80), { threshold: 0 });
    const watchTitle = () => { io.disconnect(); const t = $('.det-title'); if (t) io.observe(t); };
    watchTitle();
    const origRender = renderInfo;
    // re-observe after each info render
    renderInfo = function () { origRender(); watchTitle(); }; // eslint-disable-line no-func-assign
    V.watchScroll(el);
    el.querySelectorAll('.pane').forEach((p) => p.addEventListener('scroll', () => el.classList.toggle('scrolled', p.scrollTop > 4), { passive: true }));

    const mq = matchMedia('(min-width: 760px)');
    const onMq = () => { renderInfo(); renderList(); };
    mq.addEventListener('change', onMq);
    subs.add(() => mq.removeEventListener('change', onMq));
    subs.add(() => io.disconnect());

    if (r.query.new === '1') setTimeout(() => V.toast(`Added to ${V.tabName(s.tab)}`, { action: 'Library', onAction: () => WNA.go('/library') }), 400);
    if (r.query.focus === 'chapters') setTimeout(() => $('.chap-sec').scrollIntoView({ block: 'start' }), 50);

    return { el, title: s.title, destroy: () => { subs.off(); if (st.inc) st.inc.disconnect(); } };
  };
})();
