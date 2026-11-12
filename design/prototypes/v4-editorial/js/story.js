/* v4 Editorial — Story Details as a magazine feature spread, plus Author Novels. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon, S = A.state;
  const syncing = {}; // storyId -> {state, msg}

  function patreonRange(p) {
    if (!p || !p.members) return null;
    if (p.usd) return [p.usd * 0.82, p.usd * 1.18];
    return [p.members * 3.15, p.members * 9];
  }
  A.patreonRange = patreonRange;
  function followers(id) { const f = W.trendSeries(id).find((x) => x.key === 'followers'); return f ? f.points[f.points.length - 1].v : null; }
  function pull(desc) {
    const paras = String(desc || '').split(/\n+/).map((x) => x.trim()).filter(Boolean);
    if (!paras.length) return { quote: '', rest: [] };
    let quote = paras[0], rest = paras.slice(1);
    if (quote.length > 150) { const m = quote.match(/^.{20,150}?[.!?…](\s|$)/); if (m) { rest = [quote.slice(m[0].length).trim(), ...rest].filter(Boolean); quote = m[0].trim(); } else { rest = paras; quote = ''; } }
    return { quote, rest };
  }

  A.screens.story = (page, r) => {
    const s = W.story(r.params.id);
    if (!s || !A.alive(s)) { page.innerHTML = `${A.bar({ title: 'Not found' })}<div class="empty"><div class="ornament">⁂</div><h3>This novel isn’t here</h3><p>It may have been deleted from your library.</p><button class="btn ghost" data-go="/library">Back to the library</button></div>`; return; }
    const id = s.id;
    const all = W.chapters(id);
    const ui = { filter: 'all', q: '', more: false, shown: 80, descOpen: false };
    const f = W.forecast(s);
    const trend = W.scoreTrend(id);
    const fol = followers(id);
    const { quote, rest } = pull(s.desc);
    const pr = patreonRange(s.patreon);
    const typ = s.type && s.type !== 'null' ? s.type : '';

    page.innerHTML = `
      ${A.bar({ cls: 'clear', fade: true, title: A.titleHTML(s.title), sub: 'by ' + esc(s.author || 'Unknown'), actions: `${A.ib('graphic_eq', 'audio', 'Narration audio')}${A.ib('more_vert', 'more', 'More options')}` })}
      <div class="spread">
        <section class="sp-left">
          <div class="hero">
            <div class="hero-glow" style="background:${W.coverFallback(s)}"></div>
            <div class="kicker hero-k"><button data-act="ext">${esc(s.source)} ${I('north_east', 'xs')}</button>${A.pubLabel[s.pub] ? `<span>${A.pubLabel[s.pub]}</span>` : ''}${typ ? `<span>${esc(typ)}</span>` : ''}</div>
            <button class="hero-cover" data-act="zoom" aria-label="Zoom cover">${A.book(s, { lazy: false })}</button>
            <h1 class="disp hero-t">${A.titleHTML(s.title)}</h1>
            <div class="byline">by <a href="#/author/${id}" data-go="/author/${id}" class="link">${esc(s.author || 'Unknown')}</a></div>
            ${f.text ? `<p class="forecast ${f.state}">${f.state === 'due' ? I('autorenew', 'xs') : f.state === 'late' ? I('schedule', 'xs') : I('event', 'xs')} ${esc(f.text)}</p>` : ''}
          </div>
          <div class="stats">
            <button data-go="/story/${id}/trends"><span class="k">Score</span><b>${s.score ? esc(s.score) : '—'}${trend ? `<em class="${trend}">${I(trend === 'up' ? 'trending_up' : 'trending_down', 'xs')}</em>` : ''}</b></button>
            <button data-act="toc"><span class="k">Chapters</span><b><span class="cnt-dl">${s.downloaded}</span><small>/${s.total}</small></b></button>
            <button data-go="/story/${id}/trends"><span class="k">Followers</span><b>${fol != null ? W.fmt.num(fol) : '—'}</b></button>
          </div>
          <div class="covwrap">
            <div class="meta covline"></div>
            ${A.cov(s, 'lg')}
          </div>
          ${s.warnings && s.warnings.length ? `<div class="warns">${s.warnings.map((w) => `<span>${esc(w)}</span>`).join('')}</div>` : ''}
          <div class="acts-blk">
            <div class="act-row">
              <button class="btn grow" data-act="sync">${I('sync')}<span>Sync chapters</span></button>
              <button class="btn ghost grow" data-act="read">${I('menu_book')}<span>${s.bookmark != null ? 'Ch. ' + (s.bookmark + 1) : 'Read'}</span></button>
            </div>
            <p class="sync-msg it" hidden></p>
            <div class="dl-slot"></div>
            <div class="act-row">
              <button class="btn tonal grow" data-act="epub">${I('book_2')}<span>Generate EPUB</span></button>
              <button class="btn ghost grow" data-act="readEpub" ${S.epub[id] ? '' : 'disabled'}>${I('chrome_reader_mode')}<span>Read EPUB</span></button>
            </div>
          </div>
        </section>
        <section class="sp-right">
          <div class="details-blk">
            ${quote ? `<figure class="pq"><span class="pq-mark">“</span><blockquote>${esc(quote)}</blockquote></figure>` : ''}
            <div class="desc ${rest.length ? '' : 'none'}">${rest.length ? rest.map((p, i) => `<p class="${i === 0 ? 'dropcap' : ''}">${esc(p)}</p>`).join('') : '<p class="it faint">No description.</p>'}</div>
            <div class="desc-acts">${rest.join(' ').length > 320 ? `<button class="btn text" data-act="descMore">${I('expand_more')}<span>Read more</span></button>` : ''}<button class="btn text" data-act="listen">${I('headphones')}<span>Listen</span></button></div>
            ${s.tags.length ? `<div class="subjects"><span class="label">Subjects</span><p>${s.tags.map((t) => `<button data-act="tag" data-v="${esc(t)}">${esc(t)}</button>`).join(' ')}</p></div>` : ''}
            ${A.secHead('', 'Patronage', `<button class="sec-ic" data-act="editPatreon" aria-label="Edit Patreon link">${I('edit', 'sm')}</button><button class="sec-ic" data-act="openPatreon" aria-label="Open Patreon">${I('open_in_new', 'sm')}</button>`)}
            ${pr ? `<button class="patron" data-go="/story/${id}/trends?group=patreon">
                <div class="pat-n"><b>${s.patreon.members.toLocaleString('en-US')}</b><span>Paid members</span></div>
                <div class="pat-n"><b>$${Math.round(pr[0]).toLocaleString('en-US')}<i>–</i>$${Math.round(pr[1]).toLocaleString('en-US')}</b><span>Estimated amount</span></div>
                <p class="meta dim">Updated ${W.fmt.date(s.lastSync)} · per month · tap for trends</p></button>`
              : '<p class="sec-hint">No Patreon linked. Use the pencil to add the author’s page.</p>'}
            <button class="early" data-act="early"><span class="grow"><b>Early-access chapters</b><small class="early-v">Not linked · tap to choose a Patreon collection</small></span>${I('chevron_right')}</button>
            ${A.secHead('', 'Colophon')}
            <dl class="colophon">
              ${[['Source', `<button class="link" data-act="ext">${esc(s.source)}</button>`], ['Publication', A.pubLabel[s.pub] || '—'], typ ? ['Type', esc(typ)] : null,
                ['Score', s.score ? `<button data-go="/story/${id}/trends">★ ${esc(s.score)} ${trend ? (trend === 'up' ? '↑' : '↓') : ''}</button>` : '—'],
                ['Chapters', `${s.total} · <span class="cnt-dl">${s.downloaded}</span> offline`], ['Forecast', esc(f.short || '—')],
                s.patreon && s.patreon.members ? ['Patreon', esc(W.patreonLine(s).replace(/^Patreon\s*[·~]?\s*/, ''))] : null,
                ['Added', W.fmt.date(s.added)], ['Updated', W.fmt.date(s.updated)], ['Last synced', `<span class="synced">${W.fmt.ago(s.lastSync)}</span>`]]
                .filter(Boolean).map(([k, v]) => `<div class="leaders"><dt class="l">${k}</dt><span class="d"></span><dd class="v">${v}</dd></div>`).join('')}
            </dl>
          </div>
          <div class="toc-head" id="toc">
            <div class="sec-head"><span class="t">Contents</span><span class="r"></span><button class="sec-a" data-act="hide">${S.hiddenDetails ? 'Show details' : 'Hide details'}</button></div>
            <label class="toc-search">${I('search', 'sm')}<input type="search" placeholder="Search chapters" aria-label="Search chapters"></label>
            <div class="chips toc-chips"></div>
          </div>
          <ol class="toc-list"></ol>
          <div class="toc-more"></div>
        </section>
      </div>`;
    const $ = (q) => page.querySelector(q);
    if (S.hiddenDetails) page.classList.add('nodetails');

    function covline() {
      const b = s.bookmark;
      $('.covline').innerHTML = `<span><b class="tab-nums">${s.downloaded}</b> of ${s.total} downloaded</span>${b != null ? `<span class="bm">${I('bookmark', 'xs fill')} Chapter ${b + 1}</span>` : ''}`;
      page.querySelector('.covwrap .cov').outerHTML = A.cov(s, 'lg');
      page.querySelectorAll('.cnt-dl').forEach((n) => (n.textContent = s.downloaded));
    }
    function dlBlock() {
      const it = W.downloads.forStory(id);
      const remaining = all.filter((c) => !c.downloaded).length;
      let html = '';
      if (it && it.status !== 'finished') {
        const cur = it.chapters.find((c) => c.status === 'downloading');
        html = `<button class="dl-banner" data-go="/queue"><div class="row"><span class="${it.status === 'paused' ? '' : 'spin'}"></span><span class="grow clamp1">${it.status === 'paused' ? 'Paused' : 'Downloading'}${cur ? ': ' + esc(A.chParts(cur.title, cur.index).name || cur.title) : ''} <i class="num">(${it.done}/${it.total})</i></span><span class="go">Go to Downloads →</span></div><div class="prog"><i style="width:${(it.done / Math.max(1, it.total) * 100).toFixed(1)}%"></i></div></button>`;
      } else {
        html = `<div class="act-row"><button class="btn tonal grow" data-act="dlrem">${I('download')}<span>${remaining ? `Download remaining (${remaining})` : 'Download all'}</span></button>
          <button class="btn text" data-go="/story/${id}/select">Select chapters</button></div>`;
      }
      $('.dl-slot').innerHTML = html;
    }
    function chips() {
      const dl = all.filter((c) => c.downloaded).length;
      const fromB = s.bookmark != null ? all.length - s.bookmark : all.length;
      const opts = [['all', `All <span class="c">${all.length}</span>`], ['dl', `Downloaded <span class="c">${dl}</span>`], ['bm', `From bookmark <span class="c">${fromB}</span>`], ['nodl', `Not downloaded <span class="c">${all.length - dl}</span>`]];
      $('.toc-chips').innerHTML = opts.map(([k, l]) => `<button class="chip ${ui.filter === k ? 'on' : ''}" data-act="filt" data-v="${k}">${l}</button>`).join('');
    }
    function chStatus(c) {
      const it = W.downloads.forStory(id);
      const q = it && it.chapters.find((x) => x.id === c.id);
      if (q && q.status === 'downloading') return ['busy', 'Downloading…'];
      if (q && q.status === 'queued') return ['wait', 'Queued'];
      if (q && q.status === 'failed') return ['bad', 'Download failed'];
      if (!c.downloaded) return ['none', 'Not downloaded'];
      return ['ok', (c.audio ? 'Audio · ' : '') + 'Available offline'];
    }
    function rows() {
      let list = all;
      if (ui.filter === 'dl') list = list.filter((c) => c.downloaded);
      if (ui.filter === 'nodl') list = list.filter((c) => !c.downloaded);
      if (ui.filter === 'bm' && s.bookmark != null) list = list.slice(s.bookmark);
      const q = ui.q.trim().toLowerCase();
      if (q) list = list.filter((c) => c.title.toLowerCase().includes(q) || String(c.index) === q);
      const vis = list.slice(0, ui.shown);
      $('.toc-list').innerHTML = vis.length ? vis.map((c) => {
        const p = A.chParts(c.title, c.index); const [k, txt] = chStatus(c); const bm = s.bookmark === c.index - 1;
        return `<li class="ch ${bm ? 'bm' : ''} ${c.downloaded ? '' : 'off'}" data-cid="${c.id}">
          <span class="ch-dot ${k}"></span>
          <button class="ch-main" data-act="open" data-cid="${c.id}"><span class="leaders"><span class="l ch-t">${esc(p.name || c.title)}</span><span class="d"></span><span class="v ch-n">${c.index}</span></span><small class="${k === 'bad' ? 'err' : ''}">${txt}${bm ? ' · <span class="copper">Bookmark</span>' : ''}</small></button>
          <button class="ch-bm" data-act="bm" data-cid="${c.id}" aria-label="${bm ? 'Remove bookmark' : 'Set bookmark'}">${I(bm ? 'bookmark' : 'bookmark_add', bm ? 'fill' : '')}</button></li>`;
      }).join('') : `<li class="empty sm"><h3>No chapters match</h3><p>Try another search or filter.</p></li>`;
      $('.toc-more').innerHTML = list.length > ui.shown ? `<button class="btn ghost block" data-act="moreCh">Show all ${list.length} chapters</button>` : '';
    }
    covline(); dlBlock(); chips(); rows();
    const inp = $('.toc-search input');
    inp.addEventListener('input', W.debounce(() => { ui.q = inp.value; rows(); }, 150));

    let lastDl = JSON.stringify(W.downloads.forStory(id));
    A.listen(W.downloads, () => {
      const cur = JSON.stringify(W.downloads.forStory(id)); if (cur === lastDl) return; lastDl = cur;
      dlBlock(); covline(); rows();
    });

    function doSync(full) {
      if (syncing[id]) return;
      syncing[id] = true;
      const b = $('[data-act=sync]'); const m = $('.sync-msg');
      b.innerHTML = '<span class="spin"></span><span>Checking source…</span>'; b.disabled = true;
      m.hidden = false; m.textContent = full ? `Re-reading every chapter page on ${s.source}…` : `Looking for new chapters on ${s.source}…`;
      setTimeout(() => {
        syncing[id] = false; if (!page.isConnected) return;
        b.disabled = false; b.innerHTML = `${I('sync')}<span>Sync chapters</span>`;
        s.lastSync = Date.now();
        page.querySelectorAll('.synced').forEach((n) => (n.textContent = 'just now'));
        m.textContent = f.state === 'due' ? 'No new chapter yet · checked just now' : 'Up to date · checked just now';
        A.toast(full ? 'Full sync complete' : 'Chapters are up to date');
      }, 2300);
    }

    A.acts(page, {
      more: () => storyMenu(s, { sync: () => doSync(true) }),
      audio: () => W.go('/narration/' + id),
      ext: () => A.toast(`Opening ${esc(s.source)} in the browser…`),
      zoom: () => { const u = A.coverSrc(s); A.dialog({ cls: 'zoom', body: u ? `<img src="${u}" alt="">` : A.book(s), actions: [{ label: 'Close' }, { label: 'AI covers', cls: 'text', act: () => W.go(`/story/${id}/ai`) }] }); },
      sync: () => doSync(false),
      read: () => { const c = all[s.bookmark != null ? s.bookmark : 0]; if (c) W.go(`/reader/${id}/${c.id}`); },
      toc: () => $('#toc').scrollIntoView({ behavior: 'smooth' }),
      dlrem: () => {
        const n = all.filter((c) => !c.downloaded).length;
        if (!n) { A.toast('Every chapter is already on this phone · use Full Sync to re-download'); return; }
        W.downloads.enqueue(id); A.toast(`Queued ${n} chapters`, { action: 'View', onAction: () => W.go('/queue') });
      },
      epub: (t) => {
        if (t.disabled) return; t.disabled = true; t.innerHTML = '<span class="spin"></span><span>Generating…</span>';
        setTimeout(() => { if (!page.isConnected) return; S.epub[id] = true; t.disabled = false; t.innerHTML = `${I('book_2')}<span>Generate EPUB</span>`; $('[data-act=readEpub]').disabled = false; A.toast(`${esc(A.shortTitle(s.title))}.epub is ready · ${Math.max(1, Math.round(s.downloaded / 60))} file${s.downloaded > 150 ? 's' : ''}`); }, 2400);
      },
      readEpub: () => A.toast('Opening the EPUB in your reader app…'),
      descMore: (t) => { ui.descOpen = !ui.descOpen; $('.desc').classList.toggle('open', ui.descOpen); t.innerHTML = `${I(ui.descOpen ? 'expand_less' : 'expand_more')}<span>${ui.descOpen ? 'Read less' : 'Read more'}</span>`; },
      listen: () => { W.player.load(id, (all[s.bookmark || 0] || all[0]).id, { play: true }); A.toast('Reading aloud from your bookmark'); },
      tag: (t) => { S.tab = 'all'; S.tags = [t.dataset.v]; S.sources = []; S.query = ''; S.filtersOpen = true; W.go('/library'); },
      editPatreon: () => editPatreon(s),
      openPatreon: () => A.toast(s.patreon ? 'Opening Patreon…' : 'No Patreon link yet'),
      early: () => A.menu({ title: 'Early-access <i>collection</i>', sub: 'Chapters from this Patreon collection are listed ahead of the public release.', groups: [{ items: [
        { icon: 'collections_bookmark', label: `${esc(A.shortTitle(s.title))} — Advance chapters`, sub: '12 posts · newest 2 days ago', act: () => { $('.early-v').textContent = 'Advance chapters · 12 posts'; A.toast('Early-access collection linked'); } },
        { icon: 'collections_bookmark', label: 'All public posts', sub: '86 posts', act: () => { $('.early-v').textContent = 'All public posts · 86 posts'; A.toast('Collection linked'); } },
        { icon: 'link_off', label: 'Not linked', act: () => { $('.early-v').textContent = 'Not linked · tap to choose a Patreon collection'; } }] }] }),
      hide: (t) => { S.hiddenDetails = !S.hiddenDetails; page.classList.toggle('nodetails', S.hiddenDetails); t.textContent = S.hiddenDetails ? 'Show details' : 'Hide details'; $('#toc').scrollIntoView({ block: 'start' }); window.scrollBy(0, -60); },
      filt: (t) => { ui.filter = t.dataset.v; ui.shown = 80; chips(); rows(); },
      moreCh: () => { ui.shown = 100000; rows(); },
      open: (t) => W.go(`/reader/${id}/${t.dataset.cid}`),
      bm: (t) => {
        const c = all.find((x) => x.id === t.dataset.cid); const idx = c.index - 1;
        const was = s.bookmark; s.bookmark = was === idx ? null : idx;
        rows(); chips(); covline();
        $('[data-act=read] span:last-child').textContent = s.bookmark != null ? 'Ch. ' + (s.bookmark + 1) : 'Read';
        A.toast(s.bookmark == null ? 'Bookmark removed' : `Bookmark set at chapter ${c.index}`, { action: 'Undo', onAction: () => { s.bookmark = was; rows(); chips(); covline(); } });
      },
    });
    // fade the title into the bar once the hero title scrolls away
    const bar = page.querySelector('.bar');
    const io = new IntersectionObserver(([e]) => bar.classList.toggle('titled', !e.isIntersecting), { rootMargin: '-60px 0px 0px 0px' });
    io.observe($('.hero-t'));
    A.onLeave(() => io.disconnect());
    if (r.query.menu) storyMenu(s, { sync: () => doSync(true) });
    if (r.query.toc) setTimeout(() => $('#toc').scrollIntoView(), 50);
  };

  function storyMenu(s, { sync }) {
    const id = s.id;
    A.menu({ title: A.titleHTML(s.title), sub: 'More options', groups: [
      { items: [
        { icon: 'sync', label: 'Full Sync', sub: 'Re-check every chapter, not just new ones', act: sync },
        { icon: 'edit', label: 'Edit Patreon Link', act: () => editPatreon(s) },
      ] },
      { label: 'EPUB', items: [
        { icon: 'tune', label: 'EPUB Settings', act: () => epubSettings() },
        { icon: 'folder_open', label: 'EPUB Files', sub: S.epub[id] ? '1 file' : 'None yet', act: () => A.toast(S.epub[id] ? 'Opening EPUB files…' : 'Generate an EPUB first') },
        { icon: 'cleaning_services', label: 'Apply Text Cleanup', sub: 'Run your rules on every downloaded chapter', act: () => { A.toast('Cleaning ' + s.downloaded + ' chapters…'); setTimeout(() => A.toast('Text cleanup applied · 3 chapters changed'), 1800); } },
      ] },
      { label: 'Explore', items: [
        { icon: 'monitoring', label: 'Trends', act: () => W.go(`/story/${id}/trends`) },
        { icon: 'auto_awesome', label: 'AI Controls', act: () => W.go(`/story/${id}/ai`) },
        { icon: 'graphic_eq', label: 'Narration audio', act: () => W.go(`/narration/${id}`) },
      ] },
      { items: [{ icon: 'delete', label: 'Delete Novel', danger: true, act: () => A.confirm('Delete this novel?', `“${esc(s.title)}” and its ${s.downloaded} downloaded chapters will be removed from this phone.`, 'Delete', () => { S.deleted.add(id); W.go('/library', { replace: true }); A.toast('Novel deleted', { action: 'Undo', onAction: () => S.deleted.delete(id) }); }, true) }] },
    ] });
  }
  A.storyMenu = storyMenu;

  function editPatreon(s) {
    A.dialog({ title: 'Patreon link', body: `<p>Paste the author’s Patreon page to track members and earnings.</p><input class="input" id="pl" placeholder="https://www.patreon.com/…" value="${s.patreon ? 'https://www.patreon.com/' + esc((s.author || '').toLowerCase().replace(/\W+/g, '')) : ''}">`,
      actions: [{ label: 'Remove', cls: 'text', act: () => A.toast('Patreon link removed') }, { label: 'Cancel' }, { label: 'Save', act: (n) => A.toast(n.querySelector('#pl').value ? 'Patreon link saved' : 'No link entered') }] });
  }
  function epubSettings() {
    const st = W.settings.downloads;
    A.dialog({ title: 'EPUB settings', body: `<p>Long novels are split into volumes.</p><div class="leaders"><span class="l">Max chapters per EPUB</span><span class="d"></span><span class="v"><input class="input mini-in-num" id="mx" type="number" value="${st.maxPerEpub}"></span></div>`,
      actions: [{ label: 'Cancel' }, { label: 'Save', act: (n) => { st.maxPerEpub = +n.querySelector('#mx').value || 150; A.toast('EPUB settings saved'); } }] });
  }

  // ---------- Author Novels ----------
  A.screens.author = (page, r) => {
    const s = W.story(r.params.id);
    const list = s ? W.authorNovels(s.id).filter(A.alive) : [];
    page.innerHTML = `${A.bar({ title: esc(s ? s.author : 'Author'), sub: list.length ? `${list.length} other novel${list.length === 1 ? '' : 's'} in your library` : 'Author' })}
      <div class="wrap measure">
        <div class="kicker" style="margin-top:20px">Also by</div>
        <h1 class="disp author-h">${esc(s ? s.author : '')}</h1>
        <p class="dek">${list.length ? `${list.length} other novel${list.length === 1 ? '' : 's'} in your library, besides <i>${esc(A.shortTitle(s.title))}</i>.` : `Only <i>${esc(s ? A.shortTitle(s.title) : '')}</i> is in your library.`}</p>
        <div class="index-list author-list">${list.map((x) => A.entryHTML(x)).join('')}</div>
        ${list.length ? '' : `<div class="empty"><div class="ornament">❦</div><h3>No other novels yet</h3><p>Add more of this author’s work from ${esc(s ? s.source : 'the source')}.</p><button class="btn ghost" data-go="/add">Add a story</button></div>`}
      </div>`;
    page.addEventListener('click', (e) => { const it = e.target.closest('.entry'); if (it) W.go('/story/' + it.dataset.id); });
  };
})();
