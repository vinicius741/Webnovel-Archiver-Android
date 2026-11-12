/* Story Details: hero, stats, actions (sync / download split / EPUB split), Patreon, about, tags, chapter list. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const epub = {}; // storyId -> {at, files}
  const patreonColl = {};

  const fcIcon = { due: 'update', expected: 'schedule', late: 'hourglass_bottom', unknown: 'check_circle' };
  const followers = (s) => { const f = WNA.trendSeries(s.id).find((x) => x.key === 'followers'); return f ? f.points[f.points.length - 1].v : null; };
  const patreonRange = (p) => p.usd ? { lo: p.usd, hi: null } : { lo: Math.round(p.members * 3.15), hi: Math.round(p.members * 9) };
  V2.patreonRange = patreonRange;

  function chapterMeta(s, c, dl) {
    const d = dl && dl.chapters.find((x) => x.id === c.id);
    if (d && d.status === 'downloading') return { cls: 'busy', text: `Index ${c.index} • Downloading…` };
    if (d && d.status === 'queued') return { cls: 'queued', text: `Index ${c.index} • Queued` };
    if (d && d.status === 'paused') return { cls: 'queued', text: `Index ${c.index} • Paused` };
    if (d && d.status === 'failed') return { cls: 'fail', text: `Index ${c.index} • Download failed` };
    const bits = [`Index ${c.index}`]; if (c.audio) bits.push('Audio'); bits.push(c.downloaded ? 'Available offline' : 'Not downloaded');
    return { cls: c.downloaded ? 'ok' : 'none', text: bits.join(' • ') };
  }

  V2.screens.story = (ctx) => {
    const s = WNA.story(ctx.params.id); const page = ctx.el;
    if (!s || V2.deleted.has(s.id)) { page.innerHTML = `${V2.bar({ title: 'Story', cls: 'always' })}<div class="scroll"><div class="empty"><div class="blob">${ic('menu_book')}</div><h3>Story not found</h3><p>It may have been deleted.</p><button class="btn tonal" data-a="back">Go back</button></div></div>`; return; }
    V2.theme(page, s);
    const hero = V2.heroTake(s.id); if (hero) ctx.anim = 'fade';
    const wide = V2.wide();
    const st = { filter: 'all', q: '', descOpen: false, syncing: false, epubBusy: false, shown: 0, list: [] };

    page.innerHTML = `<header class="bar clear">${V2.ib('arrow_back', 'back', 'Back')}<div class="bt"><b>${esc(s.title)}</b><small>by ${esc(s.author)}</small></div><div class="acts">${V2.ib('share', 'share', 'Share')}${V2.ib('more_vert', 'more', 'More options')}</div></header>
      <div class="det ${wide ? 'two' : 'one'}">
        <div class="${wide ? 'pane' : 'scroll'} det-info"></div>
        ${wide ? '<div class="pane det-ch"></div>' : ''}
      </div>`;
    const info = page.querySelector('.det-info');
    const chHost = wide ? page.querySelector('.det-ch') : null;
    const aiSt = WNA.ai.state(s.id);
    const desc = aiSt.showAiDesc && aiSt.desc ? aiSt.desc : s.desc || '';
    const f = WNA.forecast(s); const tr = WNA.scoreTrend(s.id); const fol = followers(s);

    info.innerHTML = `
      <section class="hero">
        <div class="hero-bg">${V2.coverUrl(s) ? `<img src="${V2.coverUrl(s)}" alt="">` : ''}</div>
        <button class="hero-cov" data-a="zoom" aria-label="View cover">${V2.cover(s, { eager: true })}</button>
        <h1 class="hero-t">${esc(s.title)}</h1>
        <button class="author-link" data-a="author">by <u>${esc(s.author || 'Unknown')}</u>${ic('chevron_right', 's18')}</button>
        <div class="hero-badges">
          <button class="chip s solid" data-a="source">${esc(s.source)}${ic('open_in_new', 's16')}</button>
          ${V2.pubLabel[s.pub] ? `<span class="pill ${s.pub}">${V2.pubLabel[s.pub]}</span>` : ''}
          ${s.type ? `<span class="pill">${esc(s.type)}</span>` : ''}
        </div>
        ${f.text ? `<p class="fc fc-${f.state}">${ic(fcIcon[f.state] || 'schedule', 's18')}<span>${esc(f.text)}</span></p>` : ''}
      </section>
      <div class="wrap det-body">
        <div class="stats">
          <button class="stat" data-a="trends"><span class="v">${ic('star', 'fill s20')}${esc(s.score || '—')}${tr ? `<span class="tr ${tr}">${ic(tr === 'up' ? 'trending_up' : 'trending_down', 's18')}</span>` : ''}</span><span class="k">Score</span></button>
          <button class="stat" data-a="trends"><span class="v">${fol != null ? fmt.num(fol) : '—'}</span><span class="k">Followers</span></button>
          <button class="stat" data-a="jump"><span class="v cnt">${s.downloaded}<small>/${s.total}</small></span><span class="k">Chapters</span></button>
        </div>
        <div class="cov-card"><div class="row t-bs muted"><span class="grow">${s.downloaded === s.total ? 'Everything downloaded' : `${fmt.int(s.total - s.downloaded)} not downloaded`}</span>${s.bookmark != null ? `<span class="row bmk">${ic('bookmark', 'fill s16')}Chapter ${s.bookmark + 1}</span>` : ''}</div>${V2.coverage(s)}</div>
        ${s.warnings && s.warnings.length ? `<div class="chips warn-chips">${s.warnings.map((w) => `<span class="chip s danger">${ic('warning', 's16')}${esc(w)}</span>`).join('')}</div>` : ''}
        <p class="t-bs dim upd">Updated ${fmt.date(s.updated)} · synced ${fmt.ago(s.lastSync)}</p>

        <div class="cta">
          <button class="btn filled l grow" data-a="read">${ic('auto_stories', 'fill')}${s.bookmark != null ? `Continue · Ch. ${s.bookmark + 1}` : 'Start reading'}</button>
          <button class="btn pc l sq" data-a="listen" aria-label="Listen">${ic('headphones', 'fill')}</button>
        </div>
        <div class="split block dl-split"></div>
        <div class="dl-banner"></div>
        <div class="acts2">
          <button class="btn tonal" data-a="sync">${ic('sync')}<span>Sync</span></button>
          <div class="split grow epub-split"></div>
        </div>

        ${s.patreon && s.patreon.members ? patreonCard(s) : `<button class="li add-pat card outline" data-a="patreon-edit"><span class="lead">${ic('volunteer_activism')}</span><span class="tx"><b>Add Patreon link</b><span>Track paid members and earnings over time</span></span>${ic('add')}</button>`}

        <section class="card about">
          <div class="row"><h3 class="grow">About</h3>${aiSt.showAiDesc && aiSt.desc ? `<span class="pill gold">${ic('auto_awesome', 's16')}AI</span>` : ''}<button class="btn text s" data-a="listen-desc">${ic('volume_up')}Listen</button></div>
          <div class="desc ${desc.length > 280 ? 'clip' : ''}">${desc.split(/\n+/).map((p) => `<p>${esc(p)}</p>`).join('')}</div>
          ${desc.length > 280 ? `<button class="btn text s more" data-a="desc">Read more</button>` : ''}
        </section>
        <div class="chips tag-chips">${s.tags.map((t) => `<button class="chip s" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}</div>
      </div>
      ${wide ? '' : '<div class="det-ch"></div>'}`;
    const ch = chHost || info.querySelector('.det-ch');
    if (hero) V2.flyHero(hero, info.querySelector('.hero-cov .cov'));

    // ---------- chapter list ----------
    const chapters = WNA.chapters(s.id);
    const fromBm = () => (s.bookmark != null ? chapters.length - s.bookmark : 0);
    ch.innerHTML = `<div class="ch-head">
        <div class="row"><h2 class="t-tl grow">Chapters</h2><span class="t-lm dim ch-count"></span>${V2.ib('checklist', 'select', 'Select chapters')}${V2.ib('graphic_eq', 'narr', 'Chapter audio')}</div>
        <label class="search s">${ic('search')}<input placeholder="Search chapters" type="search"></label>
        <div class="chips scroller ch-chips"></div>
      </div>
      <div class="ch-list"></div><div class="ch-sent"></div>`;
    const listEl = ch.querySelector('.ch-list');
    function renderChips() {
      const dl = chapters.filter((c) => c.downloaded).length;
      const opts = [['all', `All`, chapters.length], ['dl', 'Downloaded', dl], ['bm', 'From bookmark', fromBm()], ['nd', 'Not downloaded', chapters.length - dl]].filter((o) => o[0] === 'all' || o[2] > 0);
      ch.querySelector('.ch-chips').innerHTML = opts.map(([k, l, n]) => `<button class="chip ${st.filter === k ? 'sel' : ''}" data-f="${k}">${st.filter === k ? ic('check', 'chk') : ''}${l} <span class="n">${fmt.int(n)}</span></button>`).join('');
    }
    function rowHtml(c, i, dl) {
      const m = chapterMeta(s, c, dl); const bm = s.bookmark === i;
      return `<div class="ch-row tap ${bm ? 'bm' : ''}" data-ci="${i}"><span class="ch-dot ${m.cls}"></span><div class="grow"><b class="clamp2">${esc(c.title)}</b><span class="meta">${bm ? '<em>Reading · </em>' : ''}${m.text}</span></div><button class="ib bm-btn ${bm ? 'on' : ''}" data-bm="${i}" aria-label="Bookmark">${ic('bookmark', bm ? 'fill' : '')}</button></div>`;
    }
    function renderList() {
      const q = st.q.toLowerCase();
      st.list = chapters.map((c, i) => [c, i]).filter(([c, i]) => (st.filter === 'all' || (st.filter === 'dl' && c.downloaded) || (st.filter === 'nd' && !c.downloaded) || (st.filter === 'bm' && s.bookmark != null && i >= s.bookmark)) && (!q || c.title.toLowerCase().includes(q) || String(c.index) === q));
      ch.querySelector('.ch-count').textContent = `${fmt.int(st.list.length)} shown`;
      st.shown = 0; listEl.innerHTML = st.list.length ? '' : `<div class="empty"><div class="blob">${ic('search_off')}</div><h3>No chapters match</h3></div>`;
      moreRows();
    }
    function moreRows() {
      const dl = WNA.downloads.forStory(s.id);
      const n = Math.min(st.list.length, st.shown + 60);
      listEl.insertAdjacentHTML('beforeend', st.list.slice(st.shown, n).map(([c, i]) => rowHtml(c, i, dl)).join('')); st.shown = n;
    }
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && st.shown < st.list.length) moreRows(); }, { rootMargin: '600px' });
    io.observe(ch.querySelector('.ch-sent')); ctx.sub(() => io.disconnect());
    ch.querySelector('input').addEventListener('input', WNA.debounce((e) => { st.q = e.target.value.trim(); renderList(); }, 150));
    renderChips(); renderList();

    // ---------- live bits ----------
    function renderDl() {
      const remaining = s.total - s.downloaded;
      const box = info.querySelector('.dl-split');
      box.innerHTML = `<button class="btn ${remaining ? 'filled' : 'tonal'} grow" data-a="dl">${ic(remaining ? 'download' : 'download_done')}<span class="nowrap">${remaining ? `Download remaining (${fmt.int(remaining)})` : 'All downloaded'}</span></button><button class="btn ${remaining ? 'filled' : 'tonal'}" data-a="dl-menu" aria-label="More download options">${ic('keyboard_arrow_down')}</button>`;
      const it = WNA.downloads.forStory(s.id); const ban = info.querySelector('.dl-banner');
      if (it) {
        const cur = it.chapters.find((c) => c.status === 'downloading');
        ban.innerHTML = `<div class="card tonal dlb"><div class="row"><span class="grow"><b class="t-tm">${it.status === 'paused' ? 'Download paused' : cur ? `Downloading: Chapter ${cur.index}` : 'Waiting to download'}</b><span class="t-bs">${it.done} of ${it.total} chapters${it.failed ? ` · ${it.failed} failed` : ''}</span></span>${V2.ib(it.status === 'paused' ? 'play_arrow' : 'pause', 'dl-toggle', it.status === 'paused' ? 'Resume' : 'Pause', 'tonal')}</div>${V2.wavy(it.done / Math.max(1, it.total), it.status === 'paused' ? 'flat' : '')}<button class="btn text s" data-a="queue">Go to downloads${ic('arrow_forward', 's18')}</button></div>`;
        it.chapters.forEach((c) => { const row = listEl.querySelector(`[data-ci="${c.index - 1}"]`); if (row) { const m = chapterMeta(s, chapters[c.index - 1], it); row.querySelector('.ch-dot').className = 'ch-dot ' + m.cls; row.querySelector('.meta').innerHTML = (s.bookmark === c.index - 1 ? '<em>Reading · </em>' : '') + m.text; } });
      } else ban.innerHTML = '';
      const cnt = info.querySelector('.stat .cnt'); if (cnt) cnt.innerHTML = `${s.downloaded}<small>/${s.total}</small>`;
    }
    function renderEpub() {
      const e = epub[s.id]; const box = info.querySelector('.epub-split');
      box.innerHTML = st.epubBusy
        ? `<button class="btn outlined"><span class="loader accent" style="width:20px;height:20px"></span>Generating EPUB…</button><button class="btn outlined disabled">${ic('keyboard_arrow_down')}</button>`
        : `<button class="btn outlined" data-a="${e ? 'epub-read' : 'epub-gen'}">${ic(e ? 'menu_book' : 'library_books')}${e ? 'Read EPUB' : 'Generate EPUB'}</button><button class="btn outlined" data-a="epub-menu" aria-label="EPUB options">${ic('keyboard_arrow_down')}</button>`;
    }
    renderDl(); renderEpub();
    ctx.sub(WNA.downloads.on(() => { if (page.isConnected) renderDl(); }));

    // ---------- actions ----------
    async function sync(full) {
      if (st.syncing) return; st.syncing = true;
      const b = info.querySelector('[data-a="sync"]');
      b.classList.add('busy'); b.innerHTML = `<span class="loader" style="width:20px;height:20px"></span><span>${full ? 'Full sync…' : 'Checking…'}</span>`;
      await V2.wait(full ? 3200 : 2200);
      st.syncing = false; s.lastSync = Date.now();
      if (!page.isConnected) return;
      b.classList.remove('busy'); b.innerHTML = `${ic('sync')}<span>Sync</span>`;
      if (WNA.forecast(s).state === 'due' && s.pub !== 'completed') {
        const n = chapters.length + 1; chapters.push({ id: `${s.id}-new${n}`, title: `Chapter ${n}`, downloaded: false, publishedAt: Date.now(), index: n, audio: false }); s.total++; s.recentPubs = [...(s.recentPubs || []), Date.now()];
        renderDl(); renderChips(); renderList();
        V2.snack('1 new chapter found', { action: 'Download', onAction: () => WNA.downloads.enqueue(s.id) });
      } else V2.snack(full ? `Full sync done · ${fmt.int(s.total)} chapters checked` : 'Up to date · no new chapters');
    }
    async function genEpub() {
      st.epubBusy = true; renderEpub(); await V2.wait(2600); st.epubBusy = false;
      const parts = Math.ceil(s.downloaded / (WNA.settings.downloads.maxPerEpub || 150)) || 1;
      epub[s.id] = { at: Date.now(), files: Array.from({ length: parts }, (_, i) => ({ name: `${V2.shortTitle(s.title)} ${parts > 1 ? `${i * 150 + 1}–${Math.min(s.downloaded, (i + 1) * 150)}` : ''}.epub`.replace(' .', '.'), size: (1.2 + (i % 3) * 0.4).toFixed(1) + ' MB' })) };
      if (page.isConnected) renderEpub();
      V2.snack(parts > 1 ? `${parts} EPUB files ready` : 'EPUB ready', { action: 'Read', onAction: () => V2.snack('Opening in your EPUB reader…') });
    }
    function epubFiles() {
      const e = epub[s.id];
      V2.sheet({ title: 'EPUB files', sub: e ? `Generated ${fmt.ago(e.at)}` : 'Nothing generated yet', body: e ? `<div class="seglist">${e.files.map((x) => `<div class="li"><span class="lead">${ic('book_2')}</span><span class="tx"><b>${esc(x.name)}</b><span>${x.size}</span></span><button class="ib" onclick="V2.snack('Sharing ${esc(x.name).replace(/'/g, '')}…')">${ic('share')}</button></div>`).join('')}</div>` : `<div class="empty" style="padding:24px"><p>Generate an EPUB to read this story in another app.</p></div>` });
    }
    function epubMenu() {
      V2.menu({ title: 'EPUB', items: [
        { icon: 'library_books', label: epub[s.id] ? 'Generate again' : 'Generate EPUB', sub: `${fmt.int(s.downloaded)} downloaded chapters`, onClick: genEpub },
        { icon: 'menu_book', label: 'Read EPUB', disabled: !epub[s.id], onClick: () => V2.snack('Opening in your EPUB reader…') },
        { icon: 'folder_open', label: 'EPUB files', onClick: epubFiles },
        { icon: 'tune', label: 'EPUB settings', sub: `Up to ${WNA.settings.downloads.maxPerEpub} chapters per file`, onClick: () => V2.go('/settings/downloads') },
      ] });
    }
    function dlMenu() {
      const b = info.querySelector('[data-a="dl-menu"]'); b.classList.add('open');
      const m = V2.menu({ title: 'Download', sub: esc(s.title), items: [
        { icon: 'download', label: 'Download remaining', sub: `${fmt.int(s.total - s.downloaded)} chapters`, disabled: s.total === s.downloaded, onClick: () => dl() },
        { icon: 'bookmark', label: 'Download from bookmark', sub: s.bookmark != null ? `Chapter ${s.bookmark + 1} onwards` : 'No bookmark yet', disabled: s.bookmark == null, onClick: () => dl(chapters.slice(s.bookmark).filter((c) => !c.downloaded).map((c) => c.id)) },
        { icon: 'checklist', label: 'Select chapters…', onClick: () => V2.go(`/story/${s.id}/select`) },
        { icon: 'restart_alt', label: 'Download all again', sub: 'Replaces downloaded text', onClick: () => dl(chapters.slice(-12).map((c) => c.id), true) },
      ] });
      const old = m.close; m.close = (x) => { b.classList.remove('open'); old(x); };
    }
    function dl(ids, again) {
      if (ids && !ids.length) { V2.snack('Those chapters are already downloaded'); return; }
      if (again) ids.forEach((id) => { const c = chapters.find((x) => x.id === id); if (c && c.downloaded) { c.downloaded = false; s.downloaded--; } });
      const n = ids ? ids.length : s.total - s.downloaded;
      WNA.downloads.enqueue(s.id, ids); renderDl();
      V2.snack(`Downloading ${V2.plural(n, 'chapter')}`, { action: 'View', onAction: () => V2.go('/queue') });
    }
    function more() {
      V2.menu({
        header: `<div class="sh-story">${V2.cover(s, { w: 44, h: 66 })}<div class="grow"><b class="clamp2">${esc(s.title)}</b><span class="dim">${esc(s.author)} · ${esc(s.source)}</span></div></div>`,
        groups: [
          { items: [{ icon: 'cloud_sync', label: 'Full sync', sub: 'Re-check every chapter on the source', onClick: () => sync(true) }, { icon: 'edit', label: 'Edit Patreon link', onClick: editPatreon }] },
          { label: 'EPUB', items: [{ icon: 'tune', label: 'EPUB settings', onClick: () => V2.go('/settings/downloads') }, { icon: 'folder_open', label: 'EPUB files', onClick: epubFiles }, { icon: 'cleaning_services', label: 'Apply text cleanup', sub: 'Run your cleanup rules on downloaded chapters', onClick: async () => { V2.snack('Cleaning up chapters…'); await V2.wait(1600); V2.snack(`Text cleanup applied to ${fmt.int(s.downloaded)} chapters`); } }] },
          { label: 'Explore', items: [{ icon: 'monitoring', label: 'Trends', onClick: () => V2.go(`/story/${s.id}/trends`) }, { icon: 'auto_awesome', label: 'AI controls', sub: 'Covers and descriptions', onClick: () => V2.go(`/story/${s.id}/ai`) }, { icon: 'graphic_eq', label: 'Narration audio', onClick: () => V2.go(`/narration/${s.id}`) }, { icon: 'person', label: `More by ${s.author}`, onClick: () => V2.go(`/author/${s.id}`) }] },
          { items: [{ icon: 'delete', label: 'Delete novel', danger: true, onClick: del }] },
        ],
      });
    }
    async function del() {
      const ok = await V2.confirm({ title: 'Delete this novel?', text: `${s.title} and its ${fmt.int(s.downloaded)} downloaded chapters will be removed from this phone.`, ok: 'Delete', danger: true, icon: 'delete' });
      if (!ok) return;
      V2.deleted.add(s.id); V2.back();
      setTimeout(() => V2.snack(`Deleted ${V2.shortTitle(s.title)}`, { action: 'Undo', onAction: () => { V2.deleted.delete(s.id); const t = V2.top(); if (t && t.ctx.onShow) t.ctx.onShow(); } }), 300);
    }
    async function editPatreon() {
      const v = await V2.prompt({ title: 'Patreon link', sub: 'Used to track paid members and earnings.', fields: [{ label: 'Patreon URL', value: s.patreon ? `https://www.patreon.com/${s.author.replace(/\s+/g, '')}` : '', placeholder: 'https://www.patreon.com/…', type: 'url' }] });
      if (v == null) return;
      if (v[0]) { if (!s.patreon) s.patreon = { members: 40 + (WNA.hash(s.id) % 300), usd: 0 }; V2.snack('Patreon link saved · fetching stats'); } else { s.patreon = null; V2.snack('Patreon link removed'); }
      V2.top().ctx.onShow = null; V2.replace(`/story/${s.id}?r=${Date.now() % 1000}`);
    }
    async function listen() {
      const ses = WNA.session; const p = WNA.player.state();
      if (!(p.loaded && p.story.id === s.id)) {
        if (ses && ses.storyId === s.id) WNA.player.load(s.id, ses.chapterId, { index: ses.index });
        else WNA.player.load(s.id, (chapters[s.bookmark || 0] || chapters[0]).id);
      }
      V2.go('/player');
    }
    function zoom() {
      const url = V2.coverUrl(s);
      const ov = el(`<div class="zoom">${url ? `<img src="${url}" alt="">` : `<div class="cov" style="width:240px;height:360px;background:${WNA.coverFallback(s)}"><span class="fb">${esc(s.title)}</span></div>`}<div class="zoom-b"><button class="btn tonal" data-z="ai">${ic('auto_awesome')}Change cover</button><button class="btn filled" data-z="x">Close</button></div></div>`);
      ov.addEventListener('click', (e) => { const z = e.target.closest('[data-z]'); ov.classList.add('out'); setTimeout(() => ov.remove(), 220); if (z && z.dataset.z === 'ai') V2.go(`/story/${s.id}/ai`); });
      document.body.appendChild(ov);
    }

    page.addEventListener('click', (e) => {
      const row = e.target.closest('[data-ci]'); const bm = e.target.closest('[data-bm]');
      if (bm) {
        const i = +bm.dataset.bm; const prev = s.bookmark;
        s.bookmark = s.bookmark === i ? null : i;
        renderChips(); renderList();
        V2.snack(s.bookmark == null ? 'Bookmark removed' : `Bookmark set to Chapter ${i + 1}`, { action: 'Undo', onAction: () => { s.bookmark = prev; renderChips(); renderList(); } });
        return;
      }
      if (row) { const c = chapters[+row.dataset.ci]; if (!c.downloaded) { V2.snack('This chapter isn’t downloaded yet', { action: 'Download', onAction: () => dl([c.id]) }); return; } V2.go(`/reader/${s.id}/${c.id}`); return; }
      const fb = e.target.closest('[data-f]'); if (fb) { st.filter = fb.dataset.f; renderChips(); renderList(); return; }
      const tg = e.target.closest('[data-tag]'); if (tg) { Object.assign(V2.lib, { tags: [tg.dataset.tag], tab: 'all', sources: [], query: '' }); V2.go('/library'); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      switch (a.dataset.a) {
        case 'more': more(); break;
        case 'share': V2.copy(`https://www.${(WNA.sources.find((x) => x.name === s.source) || { host: 'royalroad.com' }).host}/…`, 'Story link copied'); break;
        case 'author': V2.go(`/author/${s.id}`); break;
        case 'source': V2.snack(`Opening ${s.source} in the browser…`); break;
        case 'zoom': zoom(); break;
        case 'trends': V2.go(`/story/${s.id}/trends`); break;
        case 'jump': ch.scrollIntoView({ behavior: 'smooth', block: 'start' }); break;
        case 'read': { const c = chapters[s.bookmark || 0] || chapters[0]; V2.go(`/reader/${s.id}/${c.id}`); break; }
        case 'listen': listen(); break;
        case 'sync': sync(false); break;
        case 'dl': if (s.total > s.downloaded) dl(); else dlMenu(); break;
        case 'dl-menu': dlMenu(); break;
        case 'dl-toggle': { const it = WNA.downloads.forStory(s.id); if (it) it.status === 'paused' ? WNA.downloads.resume(s.id) : WNA.downloads.pause(s.id); break; }
        case 'queue': V2.go('/queue'); break;
        case 'epub-gen': genEpub(); break;
        case 'epub-read': V2.snack('Opening in your EPUB reader…'); break;
        case 'epub-menu': epubMenu(); break;
        case 'patreon-edit': editPatreon(); break;
        case 'patreon-open': V2.snack('Opening Patreon…'); break;
        case 'patreon-coll': pickCollection(); break;
        case 'desc': st.descOpen = !st.descOpen; info.querySelector('.desc').classList.toggle('open', st.descOpen); a.textContent = st.descOpen ? 'Read less' : 'Read more'; break;
        case 'listen-desc': V2.snack('Reading the description aloud'); break;
        case 'select': V2.go(`/story/${s.id}/select`); break;
        case 'narr': V2.go(`/narration/${s.id}`); break;
      }
    });
    async function pickCollection() {
      const v = await V2.choose({ title: 'Early-access chapters', sub: 'Pick the Patreon collection that holds advance chapters.', value: patreonColl[s.id] || 'none', options: [{ value: 'none', label: 'Not linked' }, { value: 'Advance chapters', label: 'Advance chapters', sub: '12 posts · Tier 2+' }, { value: `${V2.shortTitle(s.title)} early access`, label: `${V2.shortTitle(s.title)} early access`, sub: '8 posts · all tiers' }] });
      if (v == null) return; patreonColl[s.id] = v === 'none' ? null : v;
      const r = info.querySelector('[data-a="patreon-coll"] .tx span'); if (r) r.textContent = patreonColl[s.id] ? `${patreonColl[s.id]} · 3 chapters ahead` : 'Not linked · tap to choose a Patreon collection';
    }

    // scroll behaviour: raise the transparent bar once the hero scrolls away
    const scroller = wide ? info : info;
    const bar = page.querySelector('.bar');
    scroller.addEventListener('scroll', () => bar.classList.toggle('raised', scroller.scrollTop > 220), { passive: true });
    if (ctx.query.added) setTimeout(() => V2.snack(`Added to ${ctx.query.added}`, { action: 'Open tab', onAction: () => { V2.lib.tab = s.tab || 'all'; V2.go('/library'); } }), 500);
    ctx.onShow = () => { renderDl(); renderChips(); renderList(); };

    function patreonCard(x) {
      const r = patreonRange(x.patreon);
      return `<section class="card pat">
        <div class="row"><span class="pat-ic">${ic('volunteer_activism', 'fill s20')}</span><h3 class="grow">Patreon</h3>${V2.ib('edit', 'patreon-edit', 'Edit Patreon link')}${V2.ib('open_in_new', 'patreon-open', 'Open Patreon')}</div>
        <button class="pat-stats" data-a="trends"><span><b>${fmt.int(x.patreon.members)}</b><small>Paid members</small></span><span><b>${r.hi ? `$${fmt.int(r.lo)}–$${fmt.int(r.hi)}` : fmt.money(r.lo)}</b><small>${r.hi ? 'Estimated' : 'Per month'}</small></span></button>
        <p class="t-bs dim">Updated ${fmt.date(x.lastSync)} · per month · tap for trends</p>
        <button class="li pat-coll" data-a="patreon-coll"><span class="lead plain">${ic('lock_clock')}</span><span class="tx"><b>Early-access chapters</b><span>${patreonColl[x.id] ? `${esc(patreonColl[x.id])} · 3 chapters ahead` : 'Not linked · tap to choose a Patreon collection'}</span></span>${ic('chevron_right')}</button>
      </section>`;
    }
  };
})();
