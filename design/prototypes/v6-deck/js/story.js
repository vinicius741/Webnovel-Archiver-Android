/* v6 — Story details: poster header, big-number stats, actions, Patreon, description, scrubbable chapter list. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;

  V6.screens.story = (ctx) => {
    const { root, route } = ctx;
    const s = W.story(route.params.id);
    if (!s || V6.state.deleted.has(s.id)) { root.innerHTML = V6.topbar('Not found', '') + V6.empty('help', 'Novel not found', 'It may have been deleted.', '<button class="btn acc" data-go="/library">Library</button>'); return; }
    const chs = W.chapters(s.id);
    const ui = { filter: 'all', q: '', descOpen: false, epub: V6.state.epub[s.id] || null, hideDetails: false };

    const series = W.trendSeries(s.id);
    const followers = series.find((x) => x.key === 'followers');
    const fmtPatreon = () => {
      const p = s.patreon; if (!p || !p.members) return null;
      const lo = p.usd || p.members * 3.15, hi = p.usd ? p.usd * 1.4 : p.members * 9;
      return { members: p.members, range: p.usd ? `$${W.fmt.int(p.usd)}` : `$${W.fmt.int(lo)}–$${W.fmt.int(hi)}` };
    };

    root.innerHTML = `
      ${V6.topbar(`<span class="tb-mini">${esc(s.title)}</span>`, '', `${V6.iconBtn('headphones', 'data-a="audio"', 'Chapter audio')}${V6.iconBtn('more_vert', 'data-a="more"', 'More options')}`, { kicker: esc(s.source) })}
      <div class="sd">
        <div class="sd-info">
          <div class="sd-hero">
            <button class="sd-cov" data-a="zoom" aria-label="Zoom cover">${V6.cover(s, '', true)}</button>
            <div class="sd-meta">
              <div class="sd-badges"><a class="tag acc" href="#" data-a="ext">${esc(s.source)} ${ic('arrow_outward')}</a>${V6.pubLabel(s.pub) ? `<span class="tag">${V6.pubLabel(s.pub)}</span>` : ''}${s.type ? `<span class="tag">${esc(s.type)}</span>` : ''}</div>
              <div class="sd-fc" data-fc></div>
              <div class="sd-upd mono">Updated ${W.fmt.date(s.updated)}</div>
            </div>
          </div>
          <h1 class="sd-title">${esc(s.title)}</h1>
          <button class="sd-author" data-go="/author/${s.id}">by <u>${esc(s.author || 'Unknown')}</u> ${ic('arrow_forward')}</button>
          <div class="sd-stats" data-stats></div>
          <div class="sd-cov-wrap"><div class="sd-covl"><span class="kick">Offline</span><span class="kick" data-bml></span></div><div data-cbar></div></div>
          <div class="sd-actions" data-actions></div>
          <div data-dlbanner></div>
          <button class="sd-hide" data-a="hide">${ic('unfold_less')}<span>Hide details</span></button>
          <div class="sd-more" data-more>
            <div data-patreon></div>
            ${V6.sec('A', 'About', `<button class="btn sm ghost" data-a="listen-desc">${ic('volume_up')} Listen</button>`)}
            <div class="sd-desc" data-desc></div>
            <div class="sd-tags">${s.warnings && s.warnings.length ? s.warnings.map((w) => `<span class="tag hot">${ic('warning')}${esc(w)}</span>`).join('') : ''}${s.tags.map((t) => `<button class="tag" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}</div>
            <div class="sd-metrics">${followers ? `<button class="chip" data-go="/story/${s.id}/trends">${ic('group')} ${W.fmt.num(followers.points[followers.points.length - 1].v)} Followers</button>` : ''}${series.filter((x) => !['score', 'followers', 'patreon_members', 'patreon_usd'].includes(x.key)).slice(0, 3).map((x) => `<button class="chip" data-go="/story/${s.id}/trends">${W.fmt.num(x.points[x.points.length - 1].v)} ${esc(x.label)}</button>`).join('')}</div>
          </div>
        </div>
        <div class="sd-chs">
          <div class="sd-ch-h">
            <div class="sec"><span class="sec-n">${chs.length}</span><h2>Chapters</h2><button class="btn sm ghost" data-go="/story/${s.id}/select">${ic('checklist')} Select</button></div>
            <label class="search">${ic('search')}<input type="search" placeholder="Search chapters" data-cq></label>
            <div class="chips" data-cf></div>
          </div>
          <div class="chl-wrap"><div class="chl" data-chl></div><div class="rail" data-rail><i data-railth></i><span class="rail-bub" data-bub></span></div></div>
        </div>
      </div>`;

    const $ = (sel) => root.querySelector(sel);

    function renderForecast() {
      const f = W.forecast(s);
      $('[data-fc]').innerHTML = `<span class="fc-dot ${f.state}"></span><span>${esc(f.text)}</span>`;
    }
    function renderStats() {
      const tr = W.scoreTrend(s.id), bm = V6.bookmarkOf(s), pat = fmtPatreon();
      $('[data-stats]').innerHTML = `
        <button data-go="/story/${s.id}/trends"><span class="num">${s.score ? parseFloat(s.score).toFixed(2) : '—'}${tr ? `<em class="${tr}">${tr === 'up' ? '↑' : '↓'}</em>` : ''}</span><small>Score ${ic('show_chart')}</small></button>
        <div><span class="num">${s.downloaded}</span><small>of ${s.total} offline</small></div>
        <div><span class="num">${bm != null ? bm + 1 : '—'}</span><small>Bookmark</small></div>
        <button data-go="/story/${s.id}/trends"><span class="num">${pat ? W.fmt.num(pat.members) : followers ? W.fmt.num(followers.points[followers.points.length - 1].v) : '—'}</span><small>${pat ? 'Patrons' : 'Followers'}</small></button>`;
      $('[data-cbar]').innerHTML = V6.coverageBar(s, 'thick');
      $('[data-bml]').innerHTML = bm != null ? `<span class="hot-t">▮</span> Bookmark · Ch ${bm + 1}` : `${Math.round((s.downloaded / Math.max(1, s.total)) * 100)}%`;
    }
    function renderActions() {
      const rem = V6.remaining(s), c = V6.resumeChapter(s), sync = V6.state.syncing[s.id];
      const ep = ui.epub;
      $('[data-actions]').innerHTML = `
        <button class="btn acc wide sd-go" data-a="play">${ic('play_arrow', 'fill')} ${W.player.state().loaded && W.player.state().story.id === s.id ? 'Playing' : 'Listen'} · Ch ${c ? c.index : 1}</button>
        <div class="sd-agrid">
          <button class="btn ghost" data-a="read">${ic('menu_book')} Read</button>
          <button class="btn ghost ${sync ? 'busy' : ''}" data-a="sync" ${sync ? 'disabled' : ''}>${ic(sync ? 'progress_activity' : 'sync', sync ? 'spin' : '')} ${sync ? 'Checking' : 'Sync'}</button>
          <button class="btn ${rem ? 'solid' : 'ghost'}" data-a="dl" ${rem ? '' : 'disabled'}>${ic('download')} ${rem ? `Remaining ${rem}` : 'All offline'}</button>
          <button class="btn ghost" data-go="/story/${s.id}/select">${ic('checklist')} Select</button>
          <button class="btn ghost" data-a="epub" ${ep === 'gen' ? 'disabled' : ''}>${ic(ep === 'gen' ? 'progress_activity' : 'book_2', ep === 'gen' ? 'spin' : '')} ${ep === 'gen' ? 'Generating…' : ep ? 'Rebuild EPUB' : 'EPUB'}</button>
          <button class="btn ghost" data-a="readepub" ${ep === 'ready' ? '' : 'disabled'}>${ic('auto_stories')} Read EPUB</button>
        </div>
        ${sync ? `<div class="sd-syncmsg mono">${ic('progress_activity', 'spin')} ${esc(sync)}</div>` : V6.state.synced[s.id] ? `<div class="sd-syncmsg mono">${ic('check')} Synced ${W.fmt.ago(V6.state.synced[s.id])}</div>` : ''}`;
    }
    function renderDl() {
      const it = W.downloads.forStory(s.id);
      if (!it) { $('[data-dlbanner]').innerHTML = ''; return; }
      const cur = it.chapters.find((c) => c.status === 'downloading');
      $('[data-dlbanner]').innerHTML = `<button class="dlb" data-go="/queue"><div class="dlb-t"><span class="num">${it.done}<em>/${it.total}</em></span><span><b>${it.status === 'paused' ? 'Paused' : cur ? `Downloading: ${esc(cur.title)}` : 'Waiting'}</b><small>Go to Downloads ${ic('arrow_forward')}</small></span></div>${V6.bar(it.done / Math.max(1, it.total))}</button>`;
    }
    function renderPatreon() {
      const pat = fmtPatreon();
      const link = V6.state.patreonLinks[s.id];
      $('[data-patreon]').innerHTML = pat ? `<div class="card pat">
        <div class="pat-h"><b>Patreon</b><button class="ib" data-a="patedit" aria-label="Edit Patreon link">${ic('edit')}</button><button class="ib" data-a="ext" aria-label="Open Patreon">${ic('open_in_new')}</button></div>
        <button class="pat-n" data-go="/story/${s.id}/trends"><div><span class="num">${pat.members}</span><small>Paid members</small></div><div><span class="num">${pat.range}</span><small>${s.patreon.usd ? 'Per month' : 'Estimated amount'}</small></div></button>
        <div class="pat-meta mono">Updated ${W.fmt.date(s.lastSync || s.updated)} · per month · tap for trends</div>
        <button class="row pat-ea" data-a="patcol">${ic('lock_clock')}<span class="rt"><b>Early-access chapters</b><small>${link ? esc(link) : 'Not linked · tap to choose a Patreon collection'}</small></span>${ic('chevron_right')}</button>
      </div>` : '';
    }
    function renderDesc() {
      const st = W.ai.state(s.id);
      const text = st.showAiDesc && st.desc ? st.desc : s.desc || 'No description.';
      $('[data-desc]').innerHTML = `${st.showAiDesc && st.desc ? `<span class="tag acc">${ic('auto_awesome')} AI-generated</span>` : ''}<div class="desc-t ${ui.descOpen ? 'open' : ''}">${esc(text).split(/\n+/).map((p) => `<p>${p}</p>`).join('')}</div><button class="lnk" data-a="desc">${ui.descOpen ? 'Read less' : 'Read more'}</button>`;
    }

    // ---------- chapters ----------
    function filtered() {
      const bm = V6.bookmarkOf(s);
      let l = chs;
      if (ui.filter === 'dl') l = l.filter((c) => c.downloaded);
      if (ui.filter === 'bm' && bm != null) l = l.slice(bm);
      if (ui.filter === 'rm') l = l.slice(-(V6.state.removed[s.id] || 0));
      if (ui.q) { const q = ui.q.toLowerCase(); l = l.filter((c) => c.title.toLowerCase().includes(q) || String(c.index) === q); }
      return l;
    }
    function chRow(c) {
      const bm = V6.bookmarkOf(s) === c.index - 1;
      const dl = W.downloads.forStory(s.id); const dc = dl && dl.chapters.find((x) => x.id === c.id);
      const st = dc && dc.status !== 'done' ? { downloading: 'Downloading…', queued: 'Queued', failed: 'Download failed', paused: 'Paused' }[dc.status] : c.downloaded ? 'Available offline' : 'Not downloaded';
      const au = W.narration.chapterStatus(s.id, c.id).kind === 'ready';
      const pl = W.player.state(); const now = pl.loaded && pl.story.id === s.id && pl.chapter.id === c.id;
      return `<div class="ch ${c.downloaded ? '' : 'off'} ${now ? 'now' : ''} ${dc && dc.status === 'failed' ? 'fail' : ''}" data-ch="${c.id}"><span class="ch-i num">${c.index}</span><span class="ch-t"><b>${esc(c.title)}</b><small>${now ? `${ic('graphic_eq')} Playing · ` : ''}${au ? 'Audio · ' : ''}${st}</small></span><button class="ib ch-bm ${bm ? 'on' : ''}" data-bm="${c.index - 1}" aria-label="Bookmark">${ic('bookmark', bm ? 'fill' : '')}</button></div>`;
    }
    function renderChapterFilters() {
      const bm = V6.bookmarkOf(s); const rm = V6.state.removed[s.id] || 0;
      const opts = [['all', `All`, chs.length], ['dl', 'Downloaded', chs.filter((c) => c.downloaded).length]];
      if (bm != null) opts.push(['bm', 'From bookmark', chs.length - bm]);
      if (rm) opts.push(['rm', 'Removed from source', rm]);
      $('[data-cf]').innerHTML = opts.map(([k, l, n]) => `<button class="chip ${ui.filter === k ? 'on' : ''}" data-cfk="${k}">${l} <span class="n">${n}</span></button>`).join('');
    }
    let rows = [];
    function renderChapters() {
      rows = filtered();
      $('[data-chl]').innerHTML = rows.length ? rows.map(chRow).join('') : V6.empty('search_off', 'No chapters', ui.q ? 'Nothing matches that search.' : 'Nothing in this filter.');
      $('[data-rail]').style.display = rows.length > 40 ? '' : 'none';
    }
    // scrub rail: drag to jump through a long chapter list
    const rail = $('[data-rail]'), bub = $('[data-bub]'), th = $('[data-railth]');
    V6.scrub(rail, (f) => {
      if (!rows.length) return;
      const i = Math.min(rows.length - 1, Math.floor(f * rows.length));
      bub.textContent = 'CH ' + rows[i].index; rail.classList.add('on'); th.style.top = f * 100 + '%';
      const el = root.querySelector(`[data-ch="${rows[i].id}"]`); if (el) root.scrollTop += el.getBoundingClientRect().top - root.getBoundingClientRect().top - 200;
    }, () => rail.classList.remove('on'), 'y');
    const syncRail = () => { const l = $('[data-chl]'); const r = l.getBoundingClientRect(); const vh = root.clientHeight; const f = Math.max(0, Math.min(1, (vh * 0.4 - r.top) / Math.max(1, r.height))); th.style.top = f * 100 + '%'; rail.classList.toggle('vis', r.top < vh * 0.5 && r.bottom > vh * 0.5); };
    root.addEventListener('scroll', syncRail, { passive: true });

    root.addEventListener('input', (e) => { if (e.target.matches('[data-cq]')) { ui.q = e.target.value.trim(); renderChapters(); } });
    root.addEventListener('click', (e) => {
      const t = e.target;
      const cf = t.closest('[data-cfk]'); if (cf) { ui.filter = cf.dataset.cfk; renderChapterFilters(); return renderChapters(); }
      const bm = t.closest('[data-bm]'); if (bm) { const i = +bm.dataset.bm; const on = V6.bookmarkOf(s) === i; V6.setBookmark(s, on ? null : i); V6.toast(on ? 'Bookmark removed' : `Bookmarked chapter ${i + 1}`, { icon: 'bookmark' }); renderStats(); renderChapterFilters(); return renderChapters(); }
      const ch = t.closest('[data-ch]'); if (ch) { const c = W.chapter(s.id, ch.dataset.ch); if (!c.downloaded) return V6.toast('Not downloaded yet', { icon: 'cloud_off', action: 'Download', onAction: () => { W.downloads.enqueue(s.id, [c.id]); V6.toast('Queued 1 chapter', { icon: 'download' }); } }); return W.go(`/reader/${s.id}/${c.id}`); }
      const tg = t.closest('[data-tag]'); if (tg) { Object.assign(V6.state.filters, { tags: [tg.dataset.tag], sources: [], query: '' }); V6.state.homeMode = 'grid'; V6.state.tab = 'all'; return W.go('/library?view=grid'); }
      const a = t.closest('[data-a]'); if (!a) return;
      e.preventDefault();
      const act = a.dataset.a;
      if (act === 'play') V6.act.play(s), setTimeout(renderActions, 600);
      else if (act === 'read') { const c = V6.resumeChapter(s); W.go(`/reader/${s.id}/${c.id}`); }
      else if (act === 'sync') V6.act.sync(s, () => { renderActions(); renderForecast(); renderStats(); });
      else if (act === 'dl') { V6.act.download(s); renderDl(); renderChapters(); }
      else if (act === 'epub') { ui.epub = 'gen'; renderActions(); setTimeout(() => { ui.epub = V6.state.epub[s.id] = 'ready'; renderActions(); V6.toast(`EPUB ready · ${esc(V6.short(s))} (${Math.ceil(s.downloaded / 150)} file${s.downloaded > 150 ? 's' : ''})`, { icon: 'book_2' }); }, 2200); }
      else if (act === 'readepub') V6.toast('Opening EPUB in your reader app', { icon: 'auto_stories' });
      else if (act === 'desc') { ui.descOpen = !ui.descOpen; renderDesc(); }
      else if (act === 'listen-desc') V6.toast('Reading the description aloud', { icon: 'volume_up' });
      else if (act === 'hide') { ui.hideDetails = !ui.hideDetails; root.querySelector('.sd').classList.toggle('collapsed', ui.hideDetails); a.querySelector('span:last-child').textContent = ui.hideDetails ? 'Show details' : 'Hide details'; a.querySelector('.ms').textContent = ui.hideDetails ? 'unfold_more' : 'unfold_less'; }
      else if (act === 'zoom') zoom();
      else if (act === 'ext') V6.toast(`Opening ${esc(s.source)} in the browser`, { icon: 'open_in_new' });
      else if (act === 'audio') W.go('/narration/' + s.id);
      else if (act === 'patedit' || act === 'patcol') patreonEdit(act);
      else if (act === 'more') moreSheet();
    });

    function zoom() {
      const u = W.coverUrl(s);
      const z = W.el(`<div class="zoom">${u ? `<img src="${u}" alt="">` : V6.cover(s)}<button class="ib" aria-label="Close">${V6.ic('close')}</button></div>`);
      z.onclick = () => { z.classList.remove('in'); setTimeout(() => z.remove(), 200); };
      document.getElementById('layer').appendChild(z); requestAnimationFrame(() => z.classList.add('in'));
    }
    async function patreonEdit(kind) {
      if (kind === 'patcol') {
        return V6.sheet({ title: 'Early-access collection', sub: 'Pick the Patreon collection with advance chapters', items: ['Advance chapters', 'Gunwitch — early access', 'Book 2 drafts'].map((n) => ({ icon: 'collections_bookmark', label: n, run: () => { V6.state.patreonLinks[s.id] = n; renderPatreon(); V6.toast('Collection linked', { icon: 'link' }); } })) });
      }
      const v = await V6.dialog({ title: 'Edit Patreon link', fields: [{ label: 'Patreon URL', value: `https://www.patreon.com/${(s.author || 'author').toLowerCase().replace(/\W+/g, '')}` }], ok: 'Save' });
      if (v) V6.toast('Patreon link saved', { icon: 'link' });
    }
    function moreSheet() {
      V6.sheet({ title: 'More options', sub: esc(s.title), items: [
        { icon: 'sync', label: 'Full Sync', sub: 'Re-check every chapter and metadata', run: () => V6.act.sync(s, () => { renderActions(); renderStats(); }) },
        { icon: 'link', label: 'Edit Patreon Link', run: () => patreonEdit('patedit') },
        { section: true, label: 'EPUB' },
        { icon: 'tune', label: 'EPUB Settings', sub: 'Max 150 chapters per file', run: () => W.go('/settings/downloads') },
        { icon: 'folder', label: 'EPUB Files', sub: ui.epub === 'ready' ? '1 file · 2.4 MB' : 'No files yet', run: () => V6.toast(ui.epub === 'ready' ? 'EPUB files: Gunwitch 1–150.epub' : 'Generate an EPUB first', { icon: 'folder' }) },
        { icon: 'cleaning_services', label: 'Apply Text Cleanup', run: () => V6.toast(`Cleanup applied to ${s.downloaded} chapters`, { icon: 'cleaning_services' }) },
        { section: true, label: 'Explore' },
        { icon: 'show_chart', label: 'Trends', run: () => W.go(`/story/${s.id}/trends`) },
        { icon: 'auto_awesome', label: 'AI Controls', run: () => W.go(`/story/${s.id}/ai`) },
        { icon: 'library_music', label: 'Narration audio', run: () => W.go(`/narration/${s.id}`) },
        { sep: true },
        { icon: 'delete', label: 'Delete Novel', danger: true, run: () => V6.confirmDelete([s.id]) },
      ] });
    }

    ctx.sub(W.downloads.on(() => { renderDl(); renderStats(); }));
    let lastNow = '';
    ctx.sub(W.player.on((p) => { const k = p.loaded ? p.chapter.id : ''; if (k !== lastNow) { lastNow = k; renderChapters(); } }));
    renderForecast(); renderStats(); renderActions(); renderDl(); renderPatreon(); renderDesc(); renderChapterFilters(); renderChapters();
    if (route.query.more) setTimeout(moreSheet, 100);
  };
})();
