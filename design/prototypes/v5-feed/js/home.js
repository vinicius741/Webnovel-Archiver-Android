/* v5-feed Home: "what should I read or listen to next, and what's new?" */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const DAY = 86400000;
  let upTab = null;
  let radarDay = null;

  function heroSource() {
    const ps = W.player.state();
    if (ps.loaded) return { live: true, story: ps.story, chapter: ps.chapter, index: ps.index, sents: ps.sentences, ps };
    const ss = W.session;
    if (ss && !A.deleted.has(ss.storyId)) {
      const story = W.story(ss.storyId), chapter = W.chapter(ss.storyId, ss.chapterId);
      return { live: false, story, chapter, index: ss.index, sents: W.sentences(ss.storyId, ss.chapterId) };
    }
    return null;
  }

  function heroHtml(h) {
    if (!h) {
      const s = A.stories().find((x) => x.bookmark != null) || A.stories()[0];
      return `<article class="hero">${A.ambBg(s)}<div class="hero-shade"></div><div class="hero-in"><button class="hero-cv" data-go="/story/${s.id}">${A.cover(s, '', true)}</button>
        <div class="hero-meta"><span class="eyebrow">Pick up a story</span><h2>${esc(A.short(s.title))}</h2><p class="hero-sub">by ${esc(s.author)}</p></div></div>
        <div class="hero-act"><button class="btn primary" data-go="/story/${s.id}">${A.ic('auto_stories')}Open story</button></div></article>`;
    }
    const s = h.story, ci = W.chapterIndex(s.id, h.chapter.id) + 1, total = W.chapters(s.id).length;
    const ps = h.ps;
    const frac = h.sents.length ? h.index / Math.max(1, h.sents.length - 1) : 0;
    const playing = ps && (ps.playing || ps.buffering);
    const left = ps ? fmt.dur(ps.remainingSec) + ' left' : `sentence ${h.index + 1} of ${h.sents.length}`;
    const cur = (h.sents[h.index] || {}).text || '';
    const nxt = (h.sents[h.index + 1] || {}).text || '';
    return `<article class="hero ${playing ? 'is-playing' : ''}">${A.ambBg(s)}<div class="hero-shade"></div>
      <div class="hero-in">
        <button class="hero-cv" data-go="/story/${s.id}" aria-label="Open story">${A.cover(s, '', true)}</button>
        <div class="hero-meta">
          <span class="eyebrow">${h.live ? (playing ? '<i class="eq"><b></b><b></b><b></b></i>Now playing' : 'Paused') : 'Continue listening'}</span>
          <h2>${esc(A.short(s.title))}</h2>
          <p class="hero-ch">${esc(h.chapter.title)}</p>
          <p class="hero-sub">Chapter ${ci} of ${total} · ${left}</p>
        </div>
      </div>
      <button class="hero-line" data-go="${h.live ? '/player' : `/reader/${s.id}/${h.chapter.id}`}"><span class="hl-cur">${esc(cur)}</span> <span class="hl-next">${esc(nxt)}</span></button>
      <div class="hero-act">
        <button class="playring" data-hero="play" aria-label="${playing ? 'Pause' : 'Resume'}">${A.ring(frac, 68, 3.5)}<span>${A.ic(playing ? 'pause' : 'play_arrow', 'f')}</span></button>
        <div class="hero-btns">
          <button class="btn glassy sm" data-go="/reader/${s.id}/${h.chapter.id}">${A.ic('chrome_reader_mode')}Read</button>
          <button class="btn glassy sm" data-go="${h.live ? '/player' : `/story/${s.id}`}">${A.ic(h.live ? 'open_in_full' : 'list')}${h.live ? 'Player' : 'Chapters'}</button>
        </div>
      </div></article>`;
  }

  function stripsHtml() {
    const d = W.downloads.state(), n = W.narration.state();
    let out = '';
    const dl = d.downloading[0];
    if (dl) {
      const s = W.story(dl.storyId);
      out += `<button class="strip" data-go="/queue">${A.cover(s, 'strip-cv')}<span class="strip-t"><small>${A.ic('download')}Downloading now${d.downloading.length > 1 ? ` · ${d.downloading.length} novels` : ''}</small><b>${esc(A.short(dl.title))}</b><em>${esc(W.downloads.statusLine(dl))} · ${dl.done} of ${dl.total}</em>${A.bar(dl.done / dl.total, 'live')}</span>${A.ic('chevron_right', 'chev')}</button>`;
    } else if (d.paused || d.pausedItems.length) {
      out += `<button class="strip" data-go="/queue"><span class="strip-ic">${A.ic('pause')}</span><span class="strip-t"><small>Downloads</small><b>Paused</b><em>${d.pausedItems.length} novel${d.pausedItems.length === 1 ? '' : 's'} waiting</em></span>${A.ic('chevron_right', 'chev')}</button>`;
    }
    if (n.current) {
      const c = n.current;
      out += `<button class="strip" data-go="/narration">${A.cover(c.story, 'strip-cv')}<span class="strip-t"><small>${A.ic('graphic_eq')}Generating audio · ${Math.round(c.progress * 100)}%</small><b>${esc(c.title)}</b><em>${esc(A.short(c.story.title))} · ~${c.etaMin} min left · ${n.queuedCount - 1} after this</em>${A.bar(c.progress, 'live')}</span>${A.ic('chevron_right', 'chev')}</button>`;
    } else if (n.paused) {
      out += `<button class="strip" data-go="/narration"><span class="strip-ic">${A.ic('pause')}</span><span class="strip-t"><small>Audio generation</small><b>Paused</b><em>${n.queuedCount} chapters queued</em></span>${A.ic('chevron_right', 'chev')}</button>`;
    }
    if (d.blocked && d.blocked.state === 'needs') out += `<button class="strip warn" data-go="/queue"><span class="strip-ic">${A.ic('verified_user')}</span><span class="strip-t"><small>Needs you</small><b>${esc(d.blocked.site)} needs a quick check</b><em>${d.blocked.chapters} chapters are waiting</em></span>${A.ic('chevron_right', 'chev')}</button>`;
    if (!out) out = `<div class="strip calm"><span class="strip-ic">${A.ic('check_circle')}</span><span class="strip-t"><b>All quiet</b><em>Nothing downloading or generating.</em></span></div>`;
    return out;
  }

  function dueList() {
    return A.stories().filter((s) => { const f = A.forecast(s); return f.state === 'due' || (f.state === 'synced' && f.fresh); })
      .sort((a, b) => (b.updated || 0) - (a.updated || 0));
  }
  function dueCard(s) {
    const f = A.forecast(s);
    let btn;
    if (A.isSyncing(s.id)) btn = `<button class="due-btn busy" disabled><span class="spin"></span>Checking</button>`;
    else if (f.state === 'synced') btn = s.downloaded < s.total ? `<button class="due-btn got" data-dl="${s.id}">${A.ic('download')}Get ${s.total - s.downloaded}</button>` : `<button class="due-btn done" disabled>${A.ic('check')}Up to date</button>`;
    else btn = `<button class="due-btn" data-sync="${s.id}">${A.ic('sync')}Sync</button>`;
    const sub = f.state === 'synced' ? f.short : f.cadence ? `every ${fmt.days(f.cadence)}` : 'May be out';
    return `<div class="due" data-sid="${s.id}"><button class="due-cv" data-go="/story/${s.id}">${A.cover(s)}<span class="due-badge ${f.state === 'synced' ? 'new' : ''}">${f.state === 'synced' ? A.ic('fiber_new', 'f') : '<i class="dot"></i>'}${f.state === 'synced' ? esc(f.short) : 'May be out'}</span></button>
      <b class="due-t">${esc(A.short(s.title))}</b><small class="due-s">${esc(sub)}</small>${btn}</div>`;
  }

  function radarData() {
    const days = Array.from({ length: 7 }, () => []);
    A.stories().forEach((s) => { if (A.forecast(s).state !== 'expected') return; const e = A.eta(s); if (e && e.days >= 0 && e.days < 7) days[Math.floor(e.days)].push({ s, e }); });
    days.forEach((d) => d.sort((a, b) => a.e.days - b.e.days));
    return days;
  }
  function radarHtml() {
    const days = radarData();
    if (radarDay == null) radarDay = Math.max(0, days.findIndex((d) => d.length));
    const now = new Date();
    const cols = days.map((d, i) => {
      const dt = new Date(now.getTime() + i * DAY);
      const lbl = i === 0 ? 'Today' : i === 1 ? 'Tmrw' : dt.toLocaleDateString('en-US', { weekday: 'short' });
      return `<button class="rd ${i === radarDay ? 'on' : ''} ${d.length ? '' : 'none'}" data-day="${i}"><small>${lbl}</small><b>${dt.getDate()}</b>
        <span class="rd-av">${d.slice(0, 3).map((x) => A.cover(x.s, 'rd-cv')).join('')}</span><em>${d.length || '–'}</em></button>`;
    }).join('');
    const sel = days[radarDay] || [];
    const dt = new Date(now.getTime() + radarDay * DAY);
    const when = radarDay === 0 ? 'today' : radarDay === 1 ? 'tomorrow' : dt.toLocaleDateString('en-US', { weekday: 'long' });
    const list = sel.length ? sel.slice(0, 5).map(({ s, e }) => `<button class="rrow" data-go="/story/${s.id}">${A.cover(s, 'rrow-cv')}<span><b>${esc(A.short(s.title))}</b><small>Expected ${when} · usually every ${fmt.days(e.cadence)}</small></span>${A.ic('chevron_right', 'chev')}</button>`).join('') + (sel.length > 5 ? `<p class="rmore">and ${sel.length - 5} more ${when}</p>` : '')
      : `<p class="rmore">No chapters expected ${when}.</p>`;
    return `<div class="radar"><div class="rd-row">${cols}</div><div class="rd-list">${list}</div></div>`;
  }

  function upNextHtml() {
    const tabs = W.tabs().map((t) => ({ ...t, n: A.filter({ tab: t.id }).length })).filter((t) => t.n);
    if (!upTab || !tabs.some((t) => t.id === upTab)) upTab = (tabs.find((t) => /reading/i.test(t.name)) || tabs[0] || {}).id;
    const list = A.filter({ tab: upTab, sort: 'default' }).slice(0, 16);
    return `${A.chips(tabs.map((t) => [t.id, `${esc(t.name)} <small>${t.n}</small>`]), upTab, 'data-up')}
      <div class="carousel up-car">${list.map((s) => {
        const pos = s.bookmark != null ? s.bookmark + 1 : 0;
        return `<button class="upc" data-go="/story/${s.id}">${A.cover(s)}${pos ? A.bar(pos / Math.max(1, s.total)) : ''}<b>${esc(A.short(s.title))}</b><small>${pos ? `Ch ${pos} of ${s.total}` : `${s.total} chapters · not started`}</small></button>`;
      }).join('')}<button class="upc more" data-go="/library?tab=${upTab}"><span>${A.ic('arrow_forward')}</span><b>See all</b></button></div>`;
  }

  function statsHtml() {
    const all = A.stories();
    const ch = all.reduce((a, s) => a + (s.total || 0), 0), dl = all.reduce((a, s) => a + (s.downloaded || 0), 0);
    const gen = W.narration.state().generated;
    const audioCh = gen.reduce((a, g) => a + g.chapters, 0), mb = gen.reduce((a, g) => a + g.sizeMb, 0);
    const srcs = W.sourceCounts(all);
    return `<div class="stats">
      <button class="stat" data-go="/library"><small>Novels</small><b>${all.length}</b><em>${W.tabs().length} tabs · ${srcs.length} sources</em></button>
      <button class="stat" data-go="/library?smart=partial"><span class="stat-ring">${A.ring(dl / ch, 46, 4)}<i>${Math.round((dl / ch) * 100)}%</i></span><small>Offline</small><b>${fmt.num(dl)}</b><em>of ${fmt.num(ch)} chapters</em></button>
      <button class="stat" data-go="/narration"><small>Audio ready</small><b>${audioCh}</b><em>chapters · ${mb} MB</em></button>
      <button class="stat" data-go="/library?smart=due"><small>May be out</small><b>${dueList().length}</b><em>novels to sync</em></button></div>`;
  }

  A.screens.home = (ctx) => {
    if (ctx.query.play && !W.player.state().loaded && W.session) W.player.load(W.session.storyId, W.session.chapterId, { index: W.session.index, play: true });
    const h = heroSource();
    const tintStory = h ? h.story : A.stories()[0];
    A.tint(ctx.el, tintStory);
    const hr = new Date().getHours();
    const greet = hr < 5 ? 'Up late' : hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
    const date = new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' });
    const due = dueList();
    ctx.el.innerHTML = `<div class="sc home">
      <div class="home-amb">${A.ambBg(tintStory)}</div>
      <header class="home-top wrap"><div class="ht-t"><small>${date}</small><h1>${greet}</h1></div>
        <button class="ibtn glassbtn" data-go="/library?focus=1" aria-label="Search library">${A.ic('search')}</button>
        <button class="ibtn glassbtn" data-go="/add" aria-label="Add story">${A.ic('add')}</button>
        <button class="ibtn glassbtn" data-go="/settings" aria-label="Settings">${A.ic('settings')}</button></header>
      <div class="wrap home-grid rise">
        <div class="hero-slot">${heroHtml(h)}</div>
        <div class="side"><div class="strips">${stripsHtml()}</div><div class="stats-slot">${statsHtml()}</div></div>
      </div>
      <section class="wrap">
        <div class="sec-h"><div><h2>Likely out now</h2><p>${due.length ? `${due.length} novels may have new chapters` : 'Everything is synced'}</p></div>${due.length ? '<button class="link" data-syncall>Sync all' + A.ic('sync') + '</button>' : ''}</div>
      </section>
      <div class="carousel due-car">${due.length ? due.slice(0, 14).map(dueCard).join('') + (due.length > 14 ? `<button class="due more" data-go="/library?smart=due"><span>${A.ic('arrow_forward')}</span><b>${due.length - 14} more</b></button>` : '') : `<p class="faint pad">Nothing due. Check back later.</p>`}</div>
      <section class="wrap"><div class="sec-h"><div><h2>Release radar</h2><p>Chapters we expect in the next 7 days</p></div></div><div class="radar-slot">${radarHtml()}</div></section>
      <section class="wrap"><div class="sec-h"><div><h2>Up next</h2><p>From your tabs</p></div><button class="link" data-go="/library">Library${A.ic('arrow_forward')}</button></div></section>
      <div class="up-slot">${upNextHtml()}</div>
      <p class="home-foot faint">Webnovel Archiver · ${A.stories().length} novels on this phone</p>
    </div>`;
    const sc = ctx.el.querySelector('.sc');
    const amb = ctx.el.querySelector('.home-amb');
    sc.addEventListener('scroll', () => { amb.style.transform = `translateY(${-sc.scrollTop * 0.35}px)`; amb.style.opacity = Math.max(0, 1 - sc.scrollTop / 520); }, { passive: true });

    const heroSlot = ctx.el.querySelector('.hero-slot');
    const renderHero = () => { heroSlot.innerHTML = heroHtml(heroSource()); };
    let lastKey = '';
    ctx.on(W.player.on((ps) => {
      const key = ps.loaded ? ps.story.id + ps.chapter.id + ps.playing + ps.buffering : 'none';
      if (key !== lastKey) { lastKey = key; renderHero(); return; }
      // live karaoke line + ring without rebuilding the card
      const cur = heroSlot.querySelector('.hl-cur'), nxt = heroSlot.querySelector('.hl-next');
      if (cur) { cur.textContent = (ps.sentences[ps.index] || {}).text || ''; nxt.textContent = (ps.sentences[ps.index + 1] || {}).text || ''; cur.classList.remove('flash'); void cur.offsetWidth; cur.classList.add('flash'); }
      const rp = heroSlot.querySelector('.playring .ring-p'); if (rp) { const c = rp.getAttribute('stroke-dasharray'); rp.setAttribute('stroke-dashoffset', c * (1 - ps.progress)); }
      const sub = heroSlot.querySelector('.hero-sub'); if (sub) sub.textContent = `Chapter ${ps.chapterIndex + 1} of ${ps.chapterCount} · ${fmt.dur(ps.remainingSec)} left`;
    }));
    const strips = ctx.el.querySelector('.strips');
    const rs = () => { strips.innerHTML = stripsHtml(); };
    ctx.on(W.downloads.on(rs)); ctx.on(W.narration.on(rs));
    const dueCar = ctx.el.querySelector('.due-car');
    ctx.on(A.onBus('sync', (id) => { const card = dueCar.querySelector(`[data-sid="${id}"]`); if (card) card.outerHTML = dueCard(W.story(id)); }));

    ctx.el.addEventListener('click', (e) => {
      const t = e.target;
      if (t.closest('[data-hero="play"]')) {
        const ps = W.player.state();
        if (ps.loaded) W.player.toggle();
        else if (h) { W.player.load(h.story.id, h.chapter.id, { index: h.index, play: true }); A.toast('Resuming ' + A.short(h.story.title), { icon: 'headphones', action: 'Open player', fn: () => A.go('/player') }); }
        return;
      }
      const sy = t.closest('[data-sync]');
      if (sy) { const s = W.story(sy.dataset.sync); A.sync(s).then((n) => A.toast(n ? `${A.short(s.title)}: ${n} new chapter${n > 1 ? 's' : ''}` : `${A.short(s.title)} is up to date`, n ? { icon: 'fiber_new', action: 'Download', fn: () => { W.downloads.enqueue(s.id); A.toast('Added to downloads', { icon: 'download' }); } } : { icon: 'check' })); return; }
      const dl = t.closest('[data-dl]');
      if (dl) { const s = W.story(dl.dataset.dl); W.downloads.enqueue(s.id); A.toast(`Downloading ${s.total - s.downloaded} chapters`, { icon: 'download', action: 'View', fn: () => A.go('/queue') }); dl.outerHTML = `<button class="due-btn done" disabled>${A.ic('downloading')}Queued</button>`; return; }
      if (t.closest('[data-syncall]')) {
        const list = dueList().filter((s) => A.forecast(s).state === 'due').slice(0, 14);
        A.toast(`Syncing ${list.length} novels…`, { icon: 'sync' });
        Promise.all(list.map((s) => A.sync(s))).then((r) => { const n = r.reduce((a, b) => a + b, 0); A.toast(`${n} new chapters across ${r.filter(Boolean).length} novels`, { icon: 'fiber_new', action: 'Download all', fn: () => { list.forEach((s) => W.downloads.enqueue(s.id)); A.toast('Added to downloads', { icon: 'download' }); } }); });
        return;
      }
      const day = t.closest('[data-day]');
      if (day) { radarDay = +day.dataset.day; ctx.el.querySelector('.radar-slot').innerHTML = radarHtml(); return; }
      const up = t.closest('[data-up]');
      if (up) { upTab = up.dataset.up; ctx.el.querySelector('.up-slot').innerHTML = upNextHtml(); }
    });
  };
})();
