/* v3 Restructured — Story Details: tabbed (Overview · Chapters · Audio · Trends · AI), two-pane on the inner screen.
   Also Select Chapters. Audio / Trends / AI panels live in story-tabs.js (A.storyPanels). */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  const mq = matchMedia('(min-width: 720px)');
  A.storyPanels = A.storyPanels || {};

  const TABS = [['overview', 'Overview'], ['chapters', 'Chapters'], ['audio', 'Audio'], ['trends', 'Trends'], ['ai', 'AI']];
  const tabFromRoute = (r) => (r.name === 'trends' ? 'trends' : r.name === 'ai' ? 'ai' : r.name === 'narrationStory' ? 'audio' : r.query.tab === 'chapters' ? 'chapters' : 'overview');
  const tabPath = (id, t) => ({ overview: `/story/${id}`, chapters: `/story/${id}?tab=chapters`, audio: `/narration/${id}`, trends: `/story/${id}/trends`, ai: `/story/${id}/ai` }[t]);
  A.storyTabPath = tabPath;

  const estUsd = (s) => (s.patreon && s.patreon.usd ? [s.patreon.usd, s.patreon.usd] : s.patreon ? [Math.round(s.patreon.members * 3.15), Math.round(s.patreon.members * 9)] : null);

  A.screens.story = {
    navId: 'library',
    render(route) {
      const s = W.story(route.params.id);
      const el = A.view('storyv');
      if (!s) { el.innerHTML = A.appbar({ title: 'Not found', back: true }) + `<div class="empty">${A.icon('error')}<h3>Story not found</h3></div>`; return { el }; }
      let tab = tabFromRoute(route);
      let wide = mq.matches;
      let disposePanel = null;
      const chf = { q: '', filter: 'all' };
      let descOpen = false;
      const offs = [];

      function shell() {
        el.innerHTML = `${A.appbar({ title: esc(A.short(s.title, 40)), sub: `by ${esc(s.author)}`, back: true, actions: A.ab('search', 'chsearch', 'Search chapters') + A.ab('more_vert', 'more', 'More options') })}
          <div class="sd ${wide ? 'wide' : ''}">${wide ? `<aside class="sd-aside" data-r="hero"></aside>` : ''}
            <div class="sd-main"><nav class="tabs sd-tabs" data-r="tabs"></nav><div class="sd-panel" data-r="panel"></div></div></div>`;
        renderTabs(); renderPanel();
        if (wide) renderHero(el.querySelector('[data-r=hero]'));
      }
      function renderTabs() {
        const nAudio = W.chapters(s.id).filter((c) => c.audio).length;
        const counts = { chapters: fmt.num(s.total), audio: nAudio || '' };
        el.querySelector('[data-r=tabs]').innerHTML = TABS.map(([k, l]) => `<button class="tab ${tab === k ? 'on' : ''}" data-tab="${k}">${wide && k === 'overview' ? 'About' : l}${counts[k] ? `<span class="n">${counts[k]}</span>` : ''}</button>`).join('');
      }
      function renderPanel() {
        disposePanel && disposePanel(); disposePanel = null;
        const p = el.querySelector('[data-r=panel]');
        p.className = 'sd-panel p-' + tab;
        if (tab === 'overview') {
          p.innerHTML = `${wide ? '' : '<div data-r="hero"></div>'}<div data-r="about"></div>`;
          if (!wide) renderHero(p.querySelector('[data-r=hero]'));
          renderAbout(p.querySelector('[data-r=about]'));
        } else if (tab === 'chapters') renderChapters(p);
        else if (A.storyPanels[tab]) disposePanel = A.storyPanels[tab](p, s, { el, route: A.currentRoute() || route });
      }

      // ---------- hero (header + contextual actions) ----------
      function heroHtml() {
        const f = W.forecast(s);
        const tr = W.scoreTrend(s.id);
        const b = A.bookmark(s);
        const list = W.chapters(s.id);
        const rem = s.total - s.downloaded;
        const dl = W.downloads.forStory(s.id);
        const syncing = A.state.syncing[s.id];
        const url = W.coverUrl(s);
        const contCh = list[b != null ? b : 0];
        const caughtUp = b == null || b >= s.downloaded - 1;
        const fIcon = { due: 'notifications_active', late: 'schedule', expected: 'event', unknown: 'event_busy' }[f.state];
        let cta;
        const cont = `<button class="btn fill grow" data-act="continue">${A.icon(b != null ? 'menu_book' : 'play_arrow', 'f')}<span class="nowrap">${b != null ? 'Continue' : 'Start'} reading · Ch. ${contCh ? contCh.index : 1}</span></button>`;
        const listen = `<button class="btn tonal sq" data-act="listen" aria-label="Listen">${A.icon('headphones')}</button>`;
        if (dl) {
          const cur = dl.chapters.find((c) => c.status === 'downloading');
          cta = `<div class="dlbanner"><div class="row"><span class="spinner gold"></span><div class="grow"><b class="nowrap" style="display:block">${dl.status === 'paused' ? 'Download paused' : `Downloading${cur ? ': ' + esc(cur.title) : '…'}`}</b><span class="muted">${dl.done} of ${dl.total} chapters</span></div><button class="ib" data-act="dlpause" aria-label="Pause or resume">${A.icon(dl.status === 'paused' ? 'play_arrow' : 'pause')}</button></div>${A.prog(dl.done / Math.max(1, dl.total), 'thick')}<button class="btn text sm" data-act="queue" style="margin:6px -8px -6px auto;display:flex">Go to Downloads${A.icon('arrow_forward', 'sm')}</button></div><div class="ctarow">${cont}${listen}</div>`;
        } else if (rem > 0 && caughtUp) {
          cta = `<div class="ctarow"><button class="btn fill grow" data-act="dlrem">${A.icon('download')}Download ${rem} remaining</button>${listen}</div><button class="btn surface block" data-act="continue" style="margin-top:10px">${A.icon('menu_book')}${b != null ? 'Continue' : 'Start'} · Ch. ${contCh ? contCh.index : 1}</button>`;
        } else {
          cta = `<div class="ctarow">${cont}${listen}</div>${rem > 0 ? `<button class="btn copper block" data-act="dlrem" style="margin-top:10px">${A.icon('download')}Download ${rem} remaining</button>` : ''}`;
        }
        return `<div class="hero">
          ${url ? `<div class="hero-bg" style="background-image:url('${url}')"></div>` : `<div class="hero-bg" style="background:${W.coverFallback(s)}"></div>`}
          <div class="hero-top">
            <button class="hero-cover" data-act="zoom" aria-label="View cover">${A.coverHtml(s)}</button>
            <div class="hero-info">
              <h2>${esc(s.title)}</h2>
              <button class="author" data-act="author">${esc(s.author)}</button>
              <div class="badges"><button class="pillb copper" data-act="source">${esc(s.source)}${A.icon('open_in_new')}</button>${A.pub(s)}${s.type ? `<span class="pillb">${esc(s.type)}</span>` : ''}</div>
              ${s.score ? `<button class="scorebtn" data-act="trends">${A.icon('star', 'f gold')}<b>${esc(s.score)}</b>${tr ? A.icon(tr === 'up' ? 'trending_up' : 'trending_down', 'sm ' + (tr === 'up' ? 'sage' : 'red')) : ''}<span class="muted">Trends</span>${A.icon('chevron_right', 'sm muted')}</button>` : ''}
            </div>
          </div>
          <div class="fc fc-${f.state}">${A.icon(fIcon, 'sm')}<span class="grow">${syncing ? 'Checking source for new chapters…' : esc(A.state.synced[s.id] ? `Synced ${fmt.ago(A.state.synced[s.id])} · ${f.text.split(' · ').slice(-1)[0]}` : f.text)}</span>${syncing ? '<span class="spinner"></span>' : `<button class="fcbtn" data-act="sync">${A.icon('sync', 'sm')}Sync</button>`}</div>
          ${cta}
          <div class="quick">
            <button data-act="sync" ${syncing ? 'disabled' : ''}><span>${syncing ? '<span class="spinner"></span>' : A.icon('sync')}</span>Sync</button>
            <button data-act="epub"><span>${A.icon(A.state.epub[s.id] === 'busy' ? 'hourglass_top' : 'menu_book')}</span>EPUB</button>
            <button data-act="select"><span>${A.icon('checklist')}</span>Select</button>
            <button data-act="audio"><span>${A.icon('graphic_eq')}</span>Audio</button>
          </div>
          <button class="covcard" data-act="chapters">
            <div class="row"><b>${fmt.int(s.downloaded)} / ${fmt.int(s.total)}</b><span class="muted">offline</span><span class="grow"></span>${b != null ? `<span class="bm">${A.icon('bookmark', 'f xs')}Ch. ${b + 1}</span>` : ''}${A.icon('chevron_right', 'sm muted')}</div>
            ${A.cov(s)}
          </button>
        </div>`;
      }
      function renderHero(box) { if (box) box.innerHTML = heroHtml(); }
      const heroBox = () => el.querySelector('[data-r=hero]');

      // ---------- about ----------
      function renderAbout(box) {
        const ser = W.trendSeries(s.id);
        const metric = (k) => ser.find((x) => x.key === k);
        const fol = metric('followers'), views = metric('total_views') || metric('views'), fav = metric('favorites');
        const pat = s.patreon;
        const est = estUsd(s);
        const ai = W.ai.state(s.id);
        const desc = ai.showAiDesc && ai.desc ? ai.desc : s.desc || 'No description.';
        box.innerHTML = `<div class="about">
          <div class="mchips">${[fol && ['group', fmt.num(fol.points[fol.points.length - 1].v), 'Followers'], views && ['visibility', fmt.num(views.points[views.points.length - 1].v), 'Views'], fav && ['favorite', fmt.num(fav.points[fav.points.length - 1].v), 'Favorites'], ['menu_book', fmt.num(s.total), 'Chapters']].filter(Boolean).map(([i, v, l]) => `<button class="mchip" data-act="trends">${A.icon(i, 'sm')}<b>${v}</b><span>${l}</span></button>`).join('')}</div>
          ${s.warnings && s.warnings.length ? `<div class="chips" style="margin-top:12px">${s.warnings.map((w) => `<span class="pillb red">${A.icon('warning', 'xs')}${esc(w)}</span>`).join('')}<span class="pillb">Updated ${fmt.date(s.updated)}</span></div>` : `<div class="chips" style="margin-top:12px"><span class="pillb">Updated ${fmt.date(s.updated)}</span></div>`}
          ${pat ? `<div class="card patreon">
            <div class="row"><span class="plogo">${A.icon('volunteer_activism', 'sm')}</span><h3 class="grow">Patreon</h3><button class="ib" data-act="editpatreon" aria-label="Edit Patreon link">${A.icon('edit', 'sm')}</button><button class="ib" data-act="openpatreon" aria-label="Open Patreon">${A.icon('open_in_new', 'sm')}</button></div>
            <button class="pstats" data-act="trends"><div><b>${fmt.int(pat.members)}</b><span>Paid members</span></div><div><b>${est[0] === est[1] ? fmt.money(est[0]) : `${fmt.money(est[0])}–${fmt.money(est[1])}`}</b><span>Estimated / month</span></div></button>
            <div class="faint" style="font-size:12px;margin-top:6px">Updated ${fmt.date(s.lastSync)} · tap for trends</div>
            <button class="li tap pearly" data-act="earlyaccess"><span class="lead copper">${A.icon('lock_clock')}</span><div class="grow"><div class="t">Early-access chapters</div><div class="s">${A.state.patreonLinked[s.id] ? esc(A.state.patreonLinked[s.id]) + ' · 4 chapters ahead' : 'Not linked · tap to choose a Patreon collection'}</div></div>${A.icon('chevron_right', 'trail')}</button>
          </div>` : `<button class="card nopat" data-act="editpatreon">${A.icon('volunteer_activism', 'muted')}<div class="grow"><b>No Patreon linked</b><div class="muted">Add the author’s Patreon to track members and early access.</div></div>${A.icon('add', 'gold')}</button>`}
          <div class="section-label">About${ai.showAiDesc && ai.desc ? '<span class="pillb gold" style="text-transform:none;letter-spacing:0">' + A.icon('auto_awesome', 'xs') + 'AI</span>' : ''}<span class="aside"><button data-act="listendesc">${A.icon('volume_up', 'xs')} Listen</button></span></div>
          <div class="desc ${descOpen ? 'open' : ''}">${esc(desc).replace(/\n+/g, '<br><br>')}</div>
          <button class="btn text sm" data-act="desc" style="margin-left:-12px">${descOpen ? 'Read less' : 'Read more'}</button>
          <div class="section-label">Tags</div>
          <div class="chips">${s.tags.map((t) => `<span class="chip static">${esc(t)}</span>`).join('')}</div>
          <div class="section-label">Details</div>
          <div class="list">${[['public', 'Source', s.source], ['category', 'Type', s.type || '—'], ['calendar_add_on', 'Added', fmt.date(s.added)], ['sync', 'Last synced', fmt.ago(A.state.synced[s.id] || s.lastSync)], ['label', 'Tab', (W.tabs().find((t) => t.id === s.tab) || { name: 'Unassigned' }).name]].map(([i, l, v]) => `<div class="li">${A.icon(i, 'muted')}<div class="grow">${l}</div><span class="val">${esc(v)}</span></div>`).join('')}</div>
        </div>`;
      }

      // ---------- chapters ----------
      let stopInc = null;
      function chapterRow(c, b, dlMap) {
        const st = dlMap[c.id];
        const status = st === 'downloading' ? '<span class="gold">Downloading…</span>' : st === 'queued' ? 'Queued' : st === 'failed' ? '<span class="red">Download failed</span>' : c.downloaded ? 'Available offline' : 'Not downloaded';
        const dot = st === 'downloading' ? '<span class="spinner gold" style="width:12px;height:12px;border-width:2px"></span>' : `<i class="cdot ${c.downloaded ? 'on' : ''} ${st === 'failed' ? 'fail' : ''}"></i>`;
        const isB = b === c.index - 1;
        return `<div class="chrow ${isB ? 'bmk' : ''} ${c.downloaded ? '' : 'nd'}" data-ch="${c.id}">${dot}<div class="grow"><div class="t">${esc(c.title)}</div><div class="s">Index ${c.index}${c.audio ? ` • <span class="gold">${A.icon('graphic_eq', 'xs')}Audio</span>` : ''} • ${status}</div></div><button class="ib bmbtn ${isB ? 'gold' : ''}" data-bm="${c.index - 1}" aria-label="Bookmark">${A.icon('bookmark', isB ? 'f' : '')}</button></div>`;
      }
      function renderChapters(p) {
        const list = W.chapters(s.id);
        const b = A.bookmark(s);
        const fromB = b != null ? list.length - b : 0;
        const notDl = list.filter((c) => !c.downloaded).length;
        p.innerHTML = `<div class="chhead">
            <div class="field" style="min-height:46px;border-radius:23px">${A.icon('search')}<input data-r="chq" placeholder="Search chapters" value="${esc(chf.q)}"></div>
            <div class="chips scroll" style="margin-top:10px">${[['all', `All`, list.length], ['dl', 'Downloaded', s.downloaded], b != null && ['bm', 'From bookmark', fromB], notDl && ['nd', 'Not downloaded', notDl]].filter(Boolean).map(([k, l, n]) => `<button class="chip ${chf.filter === k ? 'on' : ''}" data-chf="${k}">${l} <span class="n">${fmt.int(n)}</span></button>`).join('')}</div>
            <div class="row chtools"><span class="muted grow" data-r="chcount"></span>${b != null ? `<button class="btn text sm" data-act="jumpbm">${A.icon('bookmark', 'sm')}Ch. ${b + 1}</button>` : ''}<button class="btn text sm" data-act="select">${A.icon('checklist', 'sm')}Select</button></div>
          </div><div class="chlist" data-r="chlist"></div>`;
        const q = p.querySelector('[data-r=chq]');
        q.addEventListener('input', W.debounce(() => { chf.q = q.value; fillChapters(); }, 120));
        fillChapters();
      }
      function dlMapNow() { const it = W.downloads.forStory(s.id); const m = {}; if (it) it.chapters.forEach((c) => { if (c.status !== 'done') m[c.id] = c.status; }); return m; }
      function fillChapters() {
        const box = el.querySelector('[data-r=chlist]'); if (!box) return;
        const list = W.chapters(s.id); const b = A.bookmark(s); const q = chf.q.trim().toLowerCase();
        let items = list;
        if (chf.filter === 'dl') items = items.filter((c) => c.downloaded);
        if (chf.filter === 'nd') items = items.filter((c) => !c.downloaded);
        if (chf.filter === 'bm' && b != null) items = items.slice(b);
        if (q) items = items.filter((c) => c.title.toLowerCase().includes(q) || String(c.index) === q);
        el.querySelector('[data-r=chcount]').textContent = `${fmt.int(items.length)} chapter${items.length === 1 ? '' : 's'}`;
        stopInc && stopInc();
        if (!items.length) { box.innerHTML = `<div class="empty">${A.icon('search_off')}<h3>No chapters match</h3><p>Try another search or filter.</p></div>`; return; }
        const m = dlMapNow();
        stopInc = A.incremental(box, items, (c) => chapterRow(c, b, m), { chunk: 60, scroller: el });
      }
      function patchChapters() {
        const m = dlMapNow(); const b = A.bookmark(s);
        el.querySelectorAll('[data-ch]').forEach((r) => { const c = W.chapter(s.id, r.dataset.ch); const want = chapterRow(c, b, m); if (r.outerHTML !== want) r.outerHTML = want; });
      }

      // ---------- actions ----------
      function openMore() {
        A.menu({
          title: A.short(s.title, 40), sub: 'More options', sections: [
            { items: [{ icon: 'sync', label: 'Full sync', sub: 'Re-check every chapter and metadata', fn: () => A.sync(s) }, { icon: 'volunteer_activism', label: 'Edit Patreon link', fn: editPatreon }] },
            { label: 'EPUB', items: [{ icon: 'tune', label: 'EPUB settings', fn: () => epubSettings() }, { icon: 'folder_open', label: 'EPUB files', sub: A.state.epub[s.id] === true ? '1 file · 3.4 MB' : 'No files yet', fn: () => A.toast(A.state.epub[s.id] === true ? 'Opening EPUB files' : 'Generate an EPUB first') }] },
            { label: 'Text', items: [{ icon: 'cleaning_services', label: 'Apply text cleanup', sub: 'Run your cleanup rules on saved chapters', fn: () => { A.toast('Cleaning 210 chapters…'); setTimeout(() => A.toast('Cleanup removed 3 lines in 2 chapters'), 1600); } }] },
            { label: 'Explore', items: [{ icon: 'monitoring', label: 'Trends', fn: () => setTab('trends') }, { icon: 'auto_awesome', label: 'AI controls', fn: () => setTab('ai') }, { icon: 'graphic_eq', label: 'Narration audio', fn: () => setTab('audio') }] },
            { items: [{ icon: 'delete', label: 'Delete novel', danger: true, fn: del }] },
          ],
        });
      }
      async function del() {
        if (await A.confirm('Delete this novel?', `“${esc(s.title)}” and its ${fmt.int(s.downloaded)} downloaded chapters will be removed from this phone.`, 'Delete', true)) {
          A.state.hidden.add(s.id); A.emit(); W.back();
          A.toast('Novel deleted', 'Undo', () => { A.state.hidden.delete(s.id); A.emit(); });
        }
      }
      async function editPatreon() {
        const v = await A.form('Patreon link', [{ name: 'url', label: 'Creator page URL', value: s.patreon ? `https://www.patreon.com/${(s.author || '').replace(/\s+/g, '')}` : '', placeholder: 'https://www.patreon.com/…' }]);
        if (v && v.url) { if (!s.patreon) s.patreon = { members: 38 + (W.hash(s.id) % 300), usd: 0 }; rerender(); A.toast('Patreon link saved'); }
      }
      function epubSettings() {
        A.sheet({ title: 'EPUB settings', sub: A.short(s.title, 40), body: `<div class="list">${[['Chapters per file', `${W.settings.downloads.maxPerEpub}`], ['Include cover', 'On'], ['Apply text cleanup', 'On'], ['Range', `1–${s.downloaded}`]].map(([l, v]) => `<div class="li"><div class="grow">${l}</div><span class="val">${v}</span></div>`).join('')}</div>`, foot: '<button class="btn fill block" data-x="1">Done</button>', onMount: (sh, close) => (sh.querySelector('[data-x]').onclick = close) });
      }
      function epub() {
        const has = A.state.epub[s.id] === true;
        const busy = A.state.epub[s.id] === 'busy';
        A.menu({ title: 'EPUB', sub: has ? 'Ready · 1 file · 3.4 MB' : busy ? 'Generating…' : `${fmt.int(s.downloaded)} chapters offline`, sections: [{ items: [
          { icon: 'auto_stories', label: busy ? 'Generating…' : has ? 'Regenerate EPUB' : 'Generate EPUB', sub: `Up to ${W.settings.downloads.maxPerEpub} chapters per file`, fn: () => { if (busy) return; A.state.epub[s.id] = 'busy'; rerender(); A.toast('Generating EPUB…'); setTimeout(() => { A.state.epub[s.id] = true; rerender(); A.toast('EPUB ready', 'Read', () => A.toast('Opening in your EPUB reader')); }, 2400); } },
          { icon: 'chrome_reader_mode', label: 'Read EPUB', sub: has ? 'Open in your EPUB reader' : 'Generate it first', fn: () => A.toast(has ? 'Opening in your EPUB reader' : 'Generate an EPUB first') },
          { icon: 'tune', label: 'EPUB settings', fn: epubSettings },
          { icon: 'folder_open', label: 'EPUB files', fn: () => A.toast(has ? 'Opening EPUB files' : 'No EPUB files yet') },
        ] }] });
      }
      function earlyAccess() {
        const opts = ['Early access (all tiers)', 'Advanced chapters · $5 tier', 'Bonus side stories'];
        A.menu({ title: 'Early-access chapters', sub: 'Choose a Patreon collection to follow', sections: [{ items: opts.map((o) => ({ icon: 'collections_bookmark', label: o, on: A.state.patreonLinked[s.id] === o, fn: () => { A.state.patreonLinked[s.id] = o; rerender(); A.toast('Collection linked'); } })).concat([{ icon: 'link_off', label: 'Unlink', fn: () => { delete A.state.patreonLinked[s.id]; rerender(); } }]) }] });
      }
      function zoom() {
        A.viewStoryCover(s, { actions: () => [{ id: 'ai', label: 'Cover versions', icon: 'auto_awesome' }], onAction: (x, it, close) => { close(); setTab('ai'); } });
      }
      function setTab(t) { if (t === tab) return; W.go(tabPath(s.id, t), { replace: true }); }
      function rerender() { if (wide) renderHero(heroBox()); if (tab === 'overview') renderPanel(); renderTabs(); }

      W.on(el, 'click', '[data-tab]', (e, b) => setTab(b.dataset.tab));
      W.on(el, 'click', '[data-chf]', (e, b) => { chf.filter = b.dataset.chf; el.querySelectorAll('[data-chf]').forEach((x) => x.classList.toggle('on', x === b)); fillChapters(); });
      W.on(el, 'click', '[data-ch]', (e, r) => { if (e.target.closest('[data-bm]')) return; W.go(`/reader/${s.id}/${r.dataset.ch}`); });
      W.on(el, 'click', '[data-bm]', (e, b) => {
        const i = +b.dataset.bm; const cur = A.bookmark(s);
        A.state.bookmarks[s.id] = cur === i ? null : i; patchChapters(); if (wide) renderHero(heroBox());
        A.toast(cur === i ? 'Bookmark removed' : `Bookmarked chapter ${i + 1}`);
      });
      W.on(el, 'click', '[data-act]', (e, b) => {
        const a = b.dataset.act; const list = W.chapters(s.id); const bm = A.bookmark(s);
        if (a === 'more') openMore();
        else if (a === 'chsearch') { setTab('chapters'); setTimeout(() => { const q = el.querySelector('[data-r=chq]'); q && q.focus(); }, 60); }
        else if (a === 'continue') W.go(`/reader/${s.id}/${(list[bm != null ? bm : 0] || list[0]).id}`);
        else if (a === 'listen') { A.listen(s); A.toast(`Listening to ${A.short(s.title, 28)}`, 'Open player', () => W.go('/player')); }
        else if (a === 'dlrem') { A.downloadRemaining(s); setTimeout(rerender, 50); }
        else if (a === 'dlpause') { const d = W.downloads.forStory(s.id); d && d.status === 'paused' ? W.downloads.resume(s.id) : W.downloads.pause(s.id); }
        else if (a === 'queue') W.go('/queue');
        else if (a === 'sync') { A.sync(s); }
        else if (a === 'epub') epub();
        else if (a === 'select') W.go(`/story/${s.id}/select`);
        else if (a === 'audio') setTab('audio');
        else if (a === 'trends') setTab('trends');
        else if (a === 'chapters') setTab('chapters');
        else if (a === 'author') W.go('/author/' + s.id);
        else if (a === 'source') A.toast(`Opening ${s.source} page`);
        else if (a === 'zoom') zoom();
        else if (a === 'desc') { descOpen = !descOpen; const d = el.querySelector('.desc'); d.classList.toggle('open', descOpen); b.textContent = descOpen ? 'Read less' : 'Read more'; }
        else if (a === 'listendesc') A.toast('Reading the description aloud');
        else if (a === 'editpatreon') editPatreon();
        else if (a === 'openpatreon') A.toast('Opening Patreon');
        else if (a === 'earlyaccess') earlyAccess();
        else if (a === 'jumpbm') { chf.filter = 'bm'; renderPanel(); }
      });

      offs.push(A.onChange(() => { rerender(); if (tab === 'chapters') patchChapters(); }));
      offs.push(W.downloads.on(W.debounce(() => { if (wide || tab === 'overview') renderHero(heroBox()); if (tab === 'chapters') patchChapters(); }, 250)));
      const onMq = () => { wide = mq.matches; shell(); };
      mq.addEventListener('change', onMq);
      shell();
      return {
        el,
        update(r) { const t = tabFromRoute(r); if (t !== tab) { tab = t; renderTabs(); renderPanel(); el.scrollTo({ top: 0 }); } },
        dispose() { offs.forEach((f) => f()); disposePanel && disposePanel(); stopInc && stopInc(); mq.removeEventListener('change', onMq); },
      };
    },
  };

  // ---------- Select Chapters ----------
  A.screens.select = {
    nav: false, mini: false,
    render(route) {
      const s = W.story(route.params.id);
      const el = A.view('selv');
      const list = W.chapters(s.id).filter((c) => !c.downloaded);
      const sel = new Set();
      let rangeStart = null, rangeMode = false;
      el.innerHTML = `${A.appbar({ title: 'Select chapters', sub: esc(s.title), back: true })}
        <div class="pad narrow"><div class="hint">${A.icon('touch_app', 'sm gold')}<span data-r="hint">Tap chapters individually, or long-press one and drag to select or clear a range.</span></div>
        <div class="btnrow" style="margin:12px 0"><button class="btn sm tonal" data-act="range">${A.icon('linear_scale', 'sm')}Select range</button><button class="btn sm outline" data-act="all">All</button><button class="btn sm outline" data-act="clear">Clear</button></div>
        <div data-r="list"></div></div>
        <div class="stickyfoot"><button class="btn fill block" data-act="go" disabled>${A.icon('download')}<span data-r="go">Download 0 selected</span></button></div>`;
      const box = el.querySelector('[data-r=list]');
      if (!list.length) box.innerHTML = `<div class="empty">${A.icon('done_all')}<h3>All caught up</h3><p>Every chapter of this novel is already downloaded.</p></div>`;
      else box.innerHTML = `<div class="list">${list.map((c, i) => `<button class="li selrow" data-i="${i}"><span class="ck sq"></span><span class="idx">${c.index}</span><div class="grow t" style="font-weight:500">${esc(c.title)}</div></button>`).join('')}</div>`;
      const paint = () => {
        el.querySelectorAll('[data-i]').forEach((r) => { const on = sel.has(+r.dataset.i); r.querySelector('.ck').classList.toggle('on', on); r.classList.toggle('on', on); r.classList.toggle('start', +r.dataset.i === rangeStart); });
        el.querySelector('[data-r=go]').textContent = `Download ${sel.size} selected`;
        el.querySelector('[data-act=go]').disabled = !sel.size;
      };
      const hint = (t) => (el.querySelector('[data-r=hint]').textContent = t);
      W.on(el, 'click', '[data-i]', (e, r) => {
        const i = +r.dataset.i;
        if (rangeMode) {
          if (rangeStart == null) { rangeStart = i; hint(`Chapter ${list[i].index} is the start. Tap the last chapter.`); }
          else { const [a, z] = [Math.min(rangeStart, i), Math.max(rangeStart, i)]; for (let k = a; k <= z; k++) sel.add(k); rangeMode = false; rangeStart = null; hint(`Selected chapters ${list[a].index}–${list[z].index}.`); }
        } else sel.has(i) ? sel.delete(i) : sel.add(i);
        paint();
      });
      // long-press then drag to paint a range
      let dragMode = null;
      A.longPress(el, '[data-i]', (e, r) => { const i = +r.dataset.i; dragMode = !sel.has(i); dragMode ? sel.add(i) : sel.delete(i); paint(); el.style.touchAction = 'none'; });
      el.addEventListener('pointermove', (e) => { if (dragMode == null) return; const r = document.elementFromPoint(e.clientX, e.clientY); const row = r && r.closest('[data-i]'); if (row) { const i = +row.dataset.i; dragMode ? sel.add(i) : sel.delete(i); paint(); } });
      const endDrag = () => { dragMode = null; el.style.touchAction = ''; };
      el.addEventListener('pointerup', endDrag); el.addEventListener('pointercancel', endDrag);
      W.on(el, 'click', '[data-act]', (e, b) => {
        const a = b.dataset.act;
        if (a === 'range') { rangeMode = true; rangeStart = null; hint('Tap the first chapter in your range.'); }
        else if (a === 'all') { list.forEach((_, i) => sel.add(i)); paint(); }
        else if (a === 'clear') { sel.clear(); rangeStart = null; rangeMode = false; paint(); }
        else if (a === 'go') { W.downloads.enqueue(s.id, [...sel].map((i) => list[i].id)); A.toast(`Downloading ${sel.size} chapters`, 'View', () => W.go('/queue')); W.back(); }
      });
      if (route.query.demo && list.length) { for (let k = 0; k < Math.min(5, list.length); k++) sel.add(k); paint(); }
      return { el };
    },
  };
})();
