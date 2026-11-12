/* v5-feed immersive Story page: full-bleed cover header, ambient colour, parallax, sticky action bar, chapters. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const epub = {}; // storyId -> 'busy' | 'ready'
  A.aiCover = {};
  const CH_PAGE = 80;

  function bookmarkChapter(s) { const list = W.chapters(s.id); return list[Math.min(list.length - 1, s.bookmark != null ? s.bookmark : 0)]; }
  function metrics(s) {
    const out = [];
    W.trendSeries(s.id).forEach((x) => { if (x.kind === 'score' || !x.points.length) return; if (/patreon/.test(x.key)) return; out.push([x.label, x.points[x.points.length - 1].v]); });
    return out.slice(0, 4);
  }
  function fcIcon(f) { return { due: ['sync', 'warn'], late: ['schedule', 'bad'], expected: ['event_upcoming', 'acc'], synced: ['check_circle', 'ok'], unknown: ['info', 'muted'] }[f.state] || ['info', 'muted']; }

  function infoHtml(s) {
    const f = A.forecast(s), [fi, fc] = fcIcon(f);
    const tr = W.scoreTrend(s.id);
    const rem = s.total - s.downloaded;
    const dl = W.downloads.forStory(s.id);
    const syncing = A.isSyncing(s.id);
    const pat = s.patreon && s.patreon.members ? s.patreon : null;
    const ai = W.ai.state(s.id);
    const desc = ai.showAiDesc && ai.desc ? ai.desc : s.desc;
    return `
      <h1 class="st-title">${esc(s.title)}</h1>
      <button class="st-author" data-go="/author/${s.id}">by <u>${esc(s.author)}</u>${A.ic('chevron_right')}</button>
      <div class="st-badges"><button class="pill p-acc" data-ext>${esc(s.source)}${A.ic('open_in_new')}</button>${A.pubPill(s)}${s.type ? `<span class="pill">${esc(s.type)}</span>` : ''}${s.status === 'unavailable' ? '<span class="pill p-bad">Source unavailable</span>' : ''}</div>
      <div class="st-fc fc-${fc}">${syncing ? '<span class="spin"></span>' : A.ic(fi)}<span>${syncing ? 'Checking source… looking for new chapters' : esc(f.text)}</span></div>
      <div class="st-metrics">
        ${s.score ? `<button class="metric big" data-go="/story/${s.id}/trends"><b>${A.ic('star', 'f')}${esc(s.score)}${tr ? A.ic(tr === 'up' ? 'trending_up' : 'trending_down', 'tr-' + tr) : ''}</b><small>Score</small></button>` : ''}
        ${metrics(s).map(([l, v]) => `<button class="metric" data-go="/story/${s.id}/trends"><b>${fmt.num(v)}</b><small>${esc(l)}</small></button>`).join('')}
      </div>
      ${s.warnings && s.warnings.length ? `<div class="st-warn">${s.warnings.map((w) => `<span class="pill p-bad">${A.ic('warning')}${esc(w)}</span>`).join('')}</div>` : ''}
      <div class="st-cov"><div class="st-cov-h"><b>${s.downloaded} / ${s.total}</b><span>chapters offline${s.bookmark != null ? ` · bookmark at ${s.bookmark + 1}` : ''}</span><small>Updated ${fmt.date(s.updated)}</small></div>${A.coverageBar(s)}</div>
      <div class="st-tiles">
        <button class="tile ${syncing ? 'busy' : ''}" data-sync>${syncing ? '<span class="spin"></span>' : A.ic('sync')}<b>${syncing ? 'Checking…' : 'Sync'}</b><small>${syncing ? 'Source' : 'chapters'}</small></button>
        <button class="tile ${rem && !dl ? 'hot' : ''}" data-dlall ${!rem && !dl ? 'disabled' : ''}>${A.ic(dl ? 'downloading' : rem ? 'download' : 'download_done')}<b>${dl ? 'Downloading' : rem ? `Get ${rem}` : 'All offline'}</b><small>${dl ? `${dl.done} of ${dl.total}` : rem ? 'remaining' : `${s.total} chapters`}</small></button>
        <button class="tile" data-go="/story/${s.id}/select">${A.ic('checklist')}<b>Select</b><small>chapters</small></button>
        <button class="tile ${epub[s.id] === 'busy' ? 'busy' : ''}" data-epub>${epub[s.id] === 'busy' ? '<span class="spin"></span>' : A.ic('menu_book')}<b>${epub[s.id] === 'busy' ? 'Generating…' : epub[s.id] === 'ready' ? 'Read EPUB' : 'EPUB'}</b><small>${epub[s.id] === 'ready' ? `${Math.ceil(s.downloaded / 150)} file${s.downloaded > 150 ? 's' : ''}` : 'generate'}</small></button>
        <button class="tile" data-go="/narration/${s.id}">${A.ic('graphic_eq')}<b>Audio</b><small>chapters</small></button>
        <button class="tile" data-go="/story/${s.id}/ai">${A.ic('auto_awesome')}<b>AI</b><small>cover & text</small></button>
      </div>
      <div class="dl-slot">${dlBanner(s)}</div>
      ${pat ? `<div class="pat card"><div class="pat-h">${A.ic('volunteer_activism', 'f')}<b>Patreon</b><button class="ibtn sm" data-patedit aria-label="Edit Patreon link">${A.ic('edit')}</button><button class="ibtn sm" data-ext aria-label="Open Patreon">${A.ic('open_in_new')}</button></div>
        <button class="pat-g" data-go="/story/${s.id}/trends"><span><b>${fmt.int(pat.members)}</b><small>Paid members</small></span><span><b>${pat.usd ? `$${fmt.int(Math.round(pat.usd * 0.7))}–$${fmt.int(Math.round(pat.usd * 1.3))}` : '—'}</b><small>Estimated / month</small></span></button>
        <p class="pat-u">Updated ${fmt.date(s.lastSync || s.updated)} · per month · tap for trends</p>
        <button class="row pat-row" data-patcol>${A.ic('lock_clock')}<span class="rt"><b>Early-access chapters</b><small>Not linked · tap to choose a Patreon collection</small></span>${A.ic('chevron_right', 'chev')}</button></div>`
      : `<button class="row card pat-none" data-patedit>${A.ic('volunteer_activism')}<span class="rt"><b>Patreon</b><small>Not linked · add the author’s Patreon to track it</small></span>${A.ic('add', 'chev')}</button>`}
      <div class="st-desc"><div class="sec-h sm"><h2>About${ai.showAiDesc && ai.desc ? ' <span class="pill p-acc">' + A.ic('auto_awesome') + 'AI</span>' : ''}</h2><button class="link" data-listen-desc>${A.ic('volume_up')}Listen</button></div>
        <div class="desc clamp">${esc(desc).split(/\n+/).map((p) => `<p>${p}</p>`).join('')}</div><button class="link more" data-more>Read more</button></div>
      <div class="chips wrapchips st-tags">${s.tags.map((t) => `<button class="chip" data-tagnav="${esc(t)}">${esc(t)}</button>`).join('')}</div>`;
  }
  function dlBanner(s) {
    const dl = W.downloads.forStory(s.id);
    if (!dl) return '';
    const cur = dl.chapters.find((c) => c.status === 'downloading');
    return `<button class="dlb" data-go="/queue"><span class="dlb-t"><small>${dl.status === 'paused' ? 'Paused' : 'Downloading'}</small><b>${cur ? esc(cur.title) : W.downloads.statusLine(dl)} (${dl.done}/${dl.total})</b></span><span class="dlb-go">Downloads${A.ic('arrow_forward')}</span>${A.bar(dl.done / dl.total, dl.status === 'paused' ? '' : 'live')}</button>`;
  }

  // ---------- chapters ----------
  function chState(s, c) {
    const dl = W.downloads.forStory(s.id);
    const q = dl && dl.chapters.find((x) => x.id === c.id);
    if (q && q.status === 'downloading') return ['dl', 'Downloading…'];
    if (q && q.status === 'queued') return ['q', 'Queued'];
    if (q && q.status === 'failed') return ['bad', 'Download failed'];
    if (c.downloaded) return ['ok', c.audio ? 'Audio · Available offline' : 'Available offline'];
    return ['no', 'Not downloaded'];
  }
  function chRow(s, c, i) {
    const [k, txt] = chState(s, c);
    const bm = s.bookmark === i;
    return `<div class="chr ${bm ? 'here' : ''}" data-ci="${i}"><button class="chr-b" data-read="${c.id}"><i class="dot d-${k}"></i><span><b>${esc(c.title)}</b><small>Index ${c.index} • ${txt}${bm ? ' • <em>You’re here</em>' : ''}</small></span></button>
      <button class="ibtn sm bm ${bm ? 'on' : ''}" data-bm="${i}" aria-label="Bookmark">${A.ic(bm ? 'bookmark' : 'bookmark_add', bm ? 'f' : '')}</button></div>`;
  }

  A.screens.story = (ctx) => {
    const s = W.story(ctx.params.id);
    if (!s || A.deleted.has(s.id)) { ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Story', back: '/library' })}${A.empty('menu_book', 'Story not found', 'It may have been deleted.', '<button class="btn primary" data-go="/library">Go to Library</button>')}</div>`; return; }
    A.tint(ctx.el, s);
    const st = { filter: 'all', q: '', shown: CH_PAGE };
    const bc = bookmarkChapter(s);
    const ses = W.session && W.session.storyId === s.id ? W.session : null;
    ctx.el.innerHTML = `<div class="sc st-sc">
        <div class="st-left">
          <div class="st-hero">${A.ambBg(s, 'st-amb')}<div class="st-fade"></div>
            <button class="st-cover" data-zoom aria-label="Zoom cover">${A.cover(s, '', true)}</button></div>
          <div class="st-info">${infoHtml(s)}</div>
        </div>
        <div class="st-right"><div class="chs">
          <div class="chs-h"><div class="sec-h sm"><div><h2>Chapters</h2><p>${s.total} chapters · ${s.downloaded} offline</p></div>${s.bookmark != null ? `<button class="link" data-jump>${A.ic('bookmark')}Jump to ${s.bookmark + 1}</button>` : ''}</div>
            <label class="search sm">${A.ic('search')}<input type="search" placeholder="Search chapters" data-chq></label>
            <div class="chips ch-f"></div></div>
          <div class="ch-list"></div><div class="ch-more"></div></div></div>
      </div>
      <header class="st-tb"><button class="ibtn glassbtn" data-back="/library" aria-label="Back">${A.ic('arrow_back')}</button><b class="st-tb-t">${esc(A.short(s.title))}</b>
        <button class="ibtn glassbtn" data-share aria-label="Share">${A.ic('ios_share')}</button><button class="ibtn glassbtn" data-overflow aria-label="More options">${A.ic('more_vert')}</button></header>
      <div class="actbar glass"><button class="btn primary" data-read="${bc.id}">${A.ic('chrome_reader_mode')}${s.bookmark != null ? `Continue · Ch ${s.bookmark + 1}` : 'Start reading'}</button>
        <button class="btn glassy" data-listen>${A.ic('headphones')}${ses ? 'Resume' : 'Listen'}</button></div>`;
    document.body.classList.add('has-actbar');
    ctx.on(() => document.body.classList.remove('has-actbar'));
    const el = ctx.el, sc = el.querySelector('.sc'), left = el.querySelector('.st-left');
    const tb = el.querySelector('.st-tb'), amb = el.querySelector('.st-amb'), cov = el.querySelector('.st-cover');
    const info = el.querySelector('.st-info');
    const wide = () => innerWidth >= 760;
    const onScroll = () => {
      const y = wide() ? left.scrollTop : sc.scrollTop;
      amb.style.transform = `translateY(${y * 0.45}px) scale(${1 + Math.min(y, 300) / 1500})`;
      if (!wide()) { cov.style.transform = `translateY(${y * 0.18}px) scale(${1 - Math.min(y, 360) / 1800})`; cov.style.opacity = Math.max(0, 1 - y / 520); }
      tb.classList.toggle('solid', y > (wide() ? 280 : 360));
    };
    sc.addEventListener('scroll', onScroll, { passive: true }); left.addEventListener('scroll', onScroll, { passive: true });

    // chapter list
    const listEl = el.querySelector('.ch-list'), moreEl = el.querySelector('.ch-more'), fEl = el.querySelector('.ch-f');
    function filtered() {
      const all = W.chapters(s.id);
      let idx = all.map((c, i) => i);
      if (st.filter === 'dl') idx = idx.filter((i) => all[i].downloaded);
      if (st.filter === 'bm') idx = idx.filter((i) => i >= (s.bookmark || 0));
      if (st.filter === 'not') idx = idx.filter((i) => !all[i].downloaded);
      const q = st.q.trim().toLowerCase();
      if (q) idx = idx.filter((i) => all[i].title.toLowerCase().includes(q) || String(all[i].index) === q);
      return idx;
    }
    function renderChapters() {
      const all = W.chapters(s.id);
      const nb = s.bookmark != null ? all.length - s.bookmark : 0, nd = all.filter((c) => c.downloaded).length;
      fEl.innerHTML = [['all', `All <small>${all.length}</small>`], ['dl', `Downloaded <small>${nd}</small>`], ...(nb ? [['bm', `From bookmark <small>${nb}</small>`]] : []), ...(nd < all.length ? [['not', `Not downloaded <small>${all.length - nd}</small>`]] : [])]
        .map(([k, l]) => `<button class="chip ${st.filter === k ? 'on' : ''}" data-chf="${k}">${l}</button>`).join('');
      const idx = filtered();
      listEl.innerHTML = idx.length ? idx.slice(0, st.shown).map((i) => chRow(s, all[i], i)).join('') : A.empty('search_off', 'No chapters match', 'Try another search or filter.');
      moreEl.innerHTML = idx.length > st.shown ? `<button class="btn outline block" data-chmore>Show ${Math.min(CH_PAGE, idx.length - st.shown)} more · ${idx.length - st.shown} left</button>` : '';
    }
    renderChapters();
    const io = new IntersectionObserver((es) => { if (es[0].isIntersecting && moreEl.firstChild) { st.shown += CH_PAGE; renderChapters(); } }, { rootMargin: '400px' });
    io.observe(moreEl); ctx.on(() => io.disconnect());
    el.querySelector('[data-chq]').addEventListener('input', W.debounce((e) => { st.q = e.target.value; st.shown = CH_PAGE; renderChapters(); }, 120));

    const reInfo = () => { const y = sc.scrollTop; info.innerHTML = infoHtml(s); sc.scrollTop = y; };
    ctx.on(A.onBus('sync', (id, n) => { if (id !== s.id) return; reInfo(); if (n != null) { renderChapters(); el.querySelector('.chs-h p').textContent = `${s.total} chapters · ${s.downloaded} offline`; } }));
    let lastDl = '';
    ctx.on(W.downloads.on(() => {
      const d = W.downloads.forStory(s.id); const k = d ? d.done + '/' + d.total + d.status : 'none';
      if (k === lastDl) return; lastDl = k; reInfo(); renderChapters();
    }));

    if (ctx.query.menu) setTimeout(() => overflow(s, reInfo), 50);

    el.addEventListener('click', (e) => {
      const t = e.target;
      const r = t.closest('[data-read]');
      if (r) { const c = W.chapter(s.id, r.dataset.read); if (!c.downloaded) { A.toast(`${c.title} isn’t downloaded yet`, { icon: 'cloud_off', action: 'Download', fn: () => { W.downloads.enqueue(s.id, [c.id]); A.toast('Added to downloads', { icon: 'download' }); } }); return; } A.go(`/reader/${s.id}/${c.id}`); return; }
      if (t.closest('[data-listen]')) {
        const ps = W.player.state();
        if (ps.loaded && ps.story.id === s.id) { A.go('/player'); return; }
        if (ses) W.player.load(s.id, ses.chapterId, { index: ses.index, play: true }); else W.player.load(s.id, bc.id, { play: true });
        A.toast(`Listening to ${A.short(s.title)}`, { icon: 'headphones', action: 'Open player', fn: () => A.go('/player') }); return;
      }
      if (t.closest('[data-zoom]')) return zoom(s);
      if (t.closest('[data-overflow]')) return overflow(s, reInfo);
      if (t.closest('[data-share]')) return A.toast('Share sheet: story link copied', { icon: 'ios_share' });
      if (t.closest('[data-ext]')) return A.toast(`Opening ${s.source} in the browser`, { icon: 'open_in_new' });
      if (t.closest('[data-sync]')) { A.sync(s).then((n) => A.toast(n ? `${n} new chapter${n > 1 ? 's' : ''} found` : 'No new chapters', n ? { icon: 'fiber_new', action: 'Download', fn: () => W.downloads.enqueue(s.id) } : { icon: 'check' })); return; }
      if (t.closest('[data-dlall]')) { if (W.downloads.forStory(s.id)) { A.go('/queue'); return; } const n = s.total - s.downloaded; W.downloads.enqueue(s.id); A.toast(`Downloading ${n} chapters`, { icon: 'download', action: 'View', fn: () => A.go('/queue') }); reInfo(); return; }
      if (t.closest('[data-epub]')) {
        if (epub[s.id] === 'ready') { A.toast('Opening EPUB in your reader app', { icon: 'menu_book' }); return; }
        if (epub[s.id] === 'busy') return;
        epub[s.id] = 'busy'; reInfo(); setTimeout(() => { epub[s.id] = 'ready'; if (A.current === ctx) reInfo(); A.toast('EPUB ready', { icon: 'menu_book', action: 'Read', fn: () => A.toast('Opening EPUB in your reader app') }); }, 2600); return;
      }
      if (t.closest('[data-more]')) { const d = info.querySelector('.desc'); d.classList.toggle('clamp'); t.closest('[data-more]').textContent = d.classList.contains('clamp') ? 'Read more' : 'Read less'; return; }
      if (t.closest('[data-listen-desc]')) return A.toast('Reading the description aloud', { icon: 'volume_up' });
      if (t.closest('[data-tagnav]')) { const tg = t.closest('[data-tagnav]').dataset.tagnav; A.lib.tags = [tg]; A.lib.tab = 'all'; A.go('/library'); return; }
      if (t.closest('[data-patedit]')) return A.form({ title: 'Patreon link', fields: [{ key: 'url', label: 'Patreon page', value: s.patreon ? `https://www.patreon.com/${A.short(s.author).replace(/\s+/g, '')}` : '', placeholder: 'https://www.patreon.com/…' }] }).then((v) => v && A.toast('Patreon link saved', { icon: 'check' }));
      if (t.closest('[data-patcol]')) return A.menu({ title: 'Early-access collection', sub: 'Pick the Patreon collection with advance chapters', items: [{ icon: 'collections_bookmark', label: `${A.short(s.title)} — Advance chapters`, sub: '42 posts', fn: () => A.toast('Collection linked', { icon: 'link' }) }, { icon: 'collections_bookmark', label: 'All public posts', sub: '118 posts', fn: () => A.toast('Collection linked', { icon: 'link' }) }] });
      if (t.closest('[data-jump]')) { st.filter = 'all'; st.q = ''; st.shown = Math.max(CH_PAGE, s.bookmark + 20); renderChapters(); const row = listEl.querySelector(`[data-ci="${s.bookmark}"]`); if (row) row.scrollIntoView({ block: 'center', behavior: 'smooth' }); return; }
      const f = t.closest('[data-chf]'); if (f) { st.filter = f.dataset.chf; st.shown = CH_PAGE; renderChapters(); return; }
      if (t.closest('[data-chmore]')) { st.shown += CH_PAGE; renderChapters(); return; }
      const bm = t.closest('[data-bm]');
      if (bm) { const i = +bm.dataset.bm; const prev = s.bookmark; s.bookmark = s.bookmark === i ? null : i; renderChapters(); reInfo(); A.toast(s.bookmark == null ? 'Bookmark removed' : `Bookmark set at chapter ${i + 1}`, { icon: 'bookmark', action: 'Undo', fn: () => { s.bookmark = prev; renderChapters(); reInfo(); } }); }
    });
  };

  function zoom(s) {
    const u = W.coverUrl(s);
    A.sheet({ cls: 'zoom-sheet', html: `<div class="zoom">${u ? `<img src="${u}" alt="">` : A.cover(s)}<p>${esc(s.title)}</p></div>`, tint: s });
  }
  function overflow(s, reInfo) {
    A.menu({
      title: A.short(s.title), sub: 'More options', tint: s,
      items: [
        { section: 'Sync' },
        { icon: 'sync', label: 'Full Sync', sub: 'Re-check every chapter on the source', fn: () => { A.sync(s, { full: true }).then((n) => A.toast(n ? `Full sync: ${n} new chapters` : 'Full sync: everything matches', { icon: 'sync' })); } },
        { icon: 'volunteer_activism', label: 'Edit Patreon Link', fn: () => A.form({ title: 'Patreon link', fields: [{ key: 'url', label: 'Patreon page', placeholder: 'https://www.patreon.com/…' }] }).then((v) => v && A.toast('Patreon link saved')) },
        { section: 'EPUB' },
        { icon: 'tune', label: 'EPUB Settings', sub: `Max ${W.settings.downloads.maxPerEpub} chapters per file`, fn: () => epubSettings() },
        { icon: 'folder_open', label: 'EPUB Files', fn: () => epubFiles(s) },
        { icon: 'cleaning_services', label: 'Apply Text Cleanup', sub: 'Run your cleanup rules on saved chapters', fn: () => { A.toast('Cleaning ' + s.downloaded + ' chapters…', { icon: 'cleaning_services' }); setTimeout(() => A.toast('Text cleanup applied · 14 lines removed', { icon: 'check' }), 1800); } },
        { section: 'Explore' },
        { icon: 'monitoring', label: 'Trends', nav: true, fn: () => A.go(`/story/${s.id}/trends`) },
        { icon: 'auto_awesome', label: 'AI Controls', nav: true, fn: () => A.go(`/story/${s.id}/ai`) },
        { icon: 'graphic_eq', label: 'Narration audio', nav: true, fn: () => A.go(`/narration/${s.id}`) },
        { divider: true },
        { icon: 'delete', label: 'Delete Novel', danger: true, fn: () => A.confirm({ title: 'Delete this novel?', body: `“${A.short(s.title)}”, its ${s.downloaded} downloaded chapters, audio and EPUB files will be removed from this phone.`, ok: 'Delete', danger: true }).then((ok) => { if (!ok) return; A.deleted.add(s.id); A.back('/library'); setTimeout(() => A.toast('Novel deleted', { icon: 'delete', action: 'Undo', fn: () => { A.deleted.delete(s.id); A.rerender(); } }), 350); }) },
      ],
    });
    void reInfo;
  }
  function epubSettings() {
    const d = W.settings.downloads;
    const s = A.sheet({ title: 'EPUB settings', html: `<div style="padding:4px 8px 8px;display:grid;gap:18px"><div><div class="set-lbl"><b>Max chapters per EPUB</b><span class="v">${d.maxPerEpub}</span></div>${A.range(d.maxPerEpub, 50, 500, 10, 'data-k="max"')}</div>
      <div class="set-row"><span><b>Include cover</b><small>Use the cover currently in use</small></span>${A.sw(true)}</div><div class="set-row"><span><b>Start at bookmark</b><small>Skip chapters you’ve read</small></span>${A.sw(false)}</div>
      <button class="btn primary block" data-dismiss>Done</button></div>` });
    s.el.addEventListener('input', (e) => { if (e.target.dataset.k === 'max') { d.maxPerEpub = +e.target.value; s.el.querySelector('.v').textContent = d.maxPerEpub; } });
  }
  function epubFiles(s) {
    const n = Math.max(1, Math.ceil(s.downloaded / W.settings.downloads.maxPerEpub));
    const files = Array.from({ length: n }, (_, i) => `${A.short(s.title)} ${n > 1 ? `(${i * 150 + 1}–${Math.min(s.downloaded, (i + 1) * 150)})` : ''}.epub`);
    A.menu({ title: 'EPUB files', sub: `${n} file${n > 1 ? 's' : ''} on this phone`, items: files.map((f, i) => ({ icon: 'description', label: f, sub: `${(2.1 + i * 0.7).toFixed(1)} MB · tap to share`, fn: () => A.toast('Share sheet: ' + f, { icon: 'ios_share' }) })) });
  }
})();
