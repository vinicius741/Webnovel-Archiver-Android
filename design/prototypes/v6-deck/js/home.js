/* v6 — Home: the Stage (now listening) + the Deck (swipeable novel cards). Grid mode lives in grid.js. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;

  V6.tabList = () => {
    const all = V6.stories();
    const tabs = W.tabs().map((t) => ({ id: t.id, name: t.name, n: all.filter((s) => s.tab === t.id).length }));
    const un = all.filter((s) => !s.tab || !W.tabs().some((t) => t.id === s.tab)).length;
    return [...(un ? [{ id: 'unassigned', name: 'Unassigned', n: un }] : []), ...tabs, { id: 'all', name: 'All', n: all.length }];
  };
  const deckList = () => {
    const list = W.filterStories({ tab: V6.state.tab, sort: 'default' }).filter((s) => !V6.state.deleted.has(s.id));
    const sid = W.session && W.session.storyId; const p = W.player.state();
    const lead = p.loaded ? p.story.id : sid;
    const i = list.findIndex((s) => s.id === lead);
    if (i > 0) list.unshift(...list.splice(i, 1));
    return list;
  };

  // ---------- Stage ----------
  function stageHtml() {
    const p = W.player.state();
    if (p.loaded) {
      const s = p.story;
      return `<div class="stage live ${p.playing ? 'playing' : ''}" data-stage="open">
        <div class="st-top"><span class="kick">${p.playing ? '<i class="eq"><b></b><b></b><b></b></i> Now listening' : 'Continue listening'}</span><span class="kick">${p.rate}× · ${p.sleep ? ic('bedtime') + (p.sleep === 'chapter' ? 'end of ch' : p.sleepLeftMin + ' min') : 'Bella'}${p.playing ? '' : `<button class="ib sm" data-stage="stop" aria-label="Close player">${ic('close')}</button>`}</span></div>
        <div class="st-mid">
          <div class="st-cov">${V6.cover(s, '', true)}</div>
          <div class="st-info">
            <div class="st-time"><span class="num">${W.fmt.clock(p.remainingSec)}</span><small>left in<br>chapter</small></div>
            <b class="st-ch">${esc(p.chapter.title)}</b>
            <span class="st-s">${esc(V6.short(s))} · ${p.chapterIndex + 1}/${p.chapterCount}</span>
          </div>
          <button class="st-play" data-stage="toggle" aria-label="${p.playing ? 'Pause' : 'Play'}">${ic(p.buffering ? 'progress_activity' : p.playing ? 'pause' : 'play_arrow', p.buffering ? 'spin' : 'fill')}</button>
        </div>
        <div class="st-scrub" data-stage="scrub"><div class="st-track"><i style="width:${p.progress * 100}%"></i><b style="left:${p.progress * 100}%"></b></div></div>
        <div class="st-row"><button data-stage="back15">${ic('replay')}15</button><button data-stage="speed">${p.rate}×</button><button data-stage="sleep">${ic('bedtime')}${p.sleep ? (p.sleep === 'chapter' ? 'Ch' : p.sleepLeftMin + 'm') : 'Off'}</button><button data-stage="fwd30">30${ic('forward_media')}</button><button data-stage="open">${ic('open_in_full')}</button></div>
      </div>`;
    }
    const ses = W.session;
    if (ses && !V6.state.continueDismissed) {
      const s = W.story(ses.storyId);
      return `<div class="stage idle" data-stage="resume">
        <div class="st-top"><span class="kick">Continue listening</span><button class="ib sm" data-stage="dismiss" aria-label="Dismiss">${ic('close')}</button></div>
        <div class="st-mid">
          <div class="st-cov">${V6.cover(s, '', true)}</div>
          <div class="st-info"><div class="st-time"><span class="num">CH ${W.chapterIndex(s.id, ses.chapterId) + 1}</span><small>sentence<br>${ses.index + 1}</small></div>
            <b class="st-ch">${esc(ses.chapterTitle)}</b><span class="st-s">${esc(V6.short(s))} · ${esc(s.author)}</span></div>
          <button class="st-play" data-stage="resume" aria-label="Resume">${ic('play_arrow', 'fill')}</button>
        </div></div>`;
    }
    return `<div class="stage empty-stage"><div class="st-top"><span class="kick">Nothing playing</span></div><p class="st-hint">${ic('swipe_right')} Swipe a card right to start listening.</p></div>`;
  }

  // ---------- Deck card ----------
  function cardHtml(s, depth) {
    const c = V6.resumeChapter(s), f = W.forecast(s), tr = W.scoreTrend(s.id);
    const pct = s.total ? Math.round((s.downloaded / s.total) * 100) : 0;
    const bm = V6.bookmarkOf(s);
    return `<article class="dk-card" data-id="${s.id}" data-d="${depth}" style="--d:${depth}">
      <div class="dk-art">${V6.cover(s, '', depth < 2)}
        <div class="dk-stamp play">${ic('play_arrow', 'fill')}Play</div><div class="dk-stamp dl">${ic('download')}Queue</div><div class="dk-stamp up">${ic('north')}Open</div>
        <div class="dk-badges">${f.state === 'due' ? `<span class="tag solid">${ic('fiber_new')} May be out</span>` : ''}${s.pub && s.pub !== 'ongoing' ? `<span class="tag solid">${V6.pubLabel(s.pub)}</span>` : ''}</div>
      </div>
      <div class="dk-info">
        <h3>${esc(s.title)}</h3>
        <div class="dk-by">${esc(s.author || 'Unknown')} · ${esc(s.source)}</div>
        <div class="dk-stats">
          <div><span class="num">${bm != null ? bm + 1 : c ? c.index : 1}<em>/${s.total}</em></span><small>${bm != null ? 'Bookmark' : 'Start'}</small></div>
          <div><span class="num">${pct}<em>%</em></span><small>Offline</small></div>
          <div><span class="num">${s.score ? parseFloat(s.score).toFixed(2) : '—'}${tr ? `<em class="${tr}">${tr === 'up' ? '↑' : '↓'}</em>` : ''}</span><small>Score</small></div>
        </div>
        ${V6.coverageBar(s)}
      </div>
    </article>`;
  }

  V6.screens.library = (ctx) => {
    const { root, route } = ctx;
    if (route.query.view === 'grid' || route.query.view === 'list' || route.query.view === 'cover') { V6.state.homeMode = 'grid'; if (route.query.view !== 'grid') V6.state.gridView = route.query.view; }
    if (route.query.view === 'deck') V6.state.homeMode = 'deck';
    if (route.query.tab) V6.state.tab = route.query.tab;
    if (V6.state.homeMode === 'grid') return V6.renderGrid(ctx);
    let list = deckList();
    let pos = (V6.state.deckPos[V6.state.tab] || 0) % Math.max(1, list.length);
    const d = W.downloads.state(), n = W.narration.state();

    root.innerHTML = `
      <header class="hm-h">
        <div class="hm-logo"><b>WNA</b><span class="kick">${V6.stories().length} novels · ${W.fmt.num(W.stats.downloaded)} ch offline</span></div>
        <div class="seg" role="tablist"><button class="on" aria-selected="true">${ic('style')} Deck</button><button data-mode="grid">${ic('grid_view')} Grid</button></div>
      </header>
      <div class="hm-grid">
        <div class="hm-left">
          <div id="stage">${stageHtml()}</div>
          <div class="ticker">
            <button data-go="/queue" class="tk"><span class="tk-l">${ic('download')}Downloads</span><b class="num" data-tk="dl">${d.summary.done}<em>/${d.summary.total}</em></b></button>
            <button data-go="/narration" class="tk"><span class="tk-l">${ic('graphic_eq')}Audio</span><b class="num" data-tk="au">${n.current ? Math.round(n.current.progress * 100) + '<em>%</em>' : n.queuedCount}</b></button>
          </div>
          <div class="dk-next"><span class="kick">Up next in the deck</span><div data-next></div></div>
        </div>
        <div class="hm-right">
          <div class="chips dk-tabs">${V6.tabList().map((t) => `<button class="chip ${t.id === V6.state.tab ? 'on' : ''}" data-tab="${t.id}">${esc(t.name)} <span class="n">${t.n}</span></button>`).join('')}</div>
          <div class="dk-head"><span class="num dk-pos"></span><span class="dk-hint">${ic('swipe_right')}Play ${ic('swipe_left')}Queue ${ic('swipe_up')}Open</span></div>
          <div class="deck" id="deck"></div>
          <div class="dk-btns">
            <button data-dk="prev" aria-label="Previous card">${ic('undo')}<small>Back</small></button>
            <button data-dk="left" aria-label="Queue download">${ic('download')}<small>Queue</small></button>
            <button data-dk="right" class="big" aria-label="Play">${ic('play_arrow', 'fill')}</button>
            <button data-dk="up" aria-label="Open details">${ic('north')}<small>Open</small></button>
            <button data-dk="more" aria-label="More actions">${ic('more_horiz')}<small>More</small></button>
          </div>
        </div>
      </div>`;
    root.classList.add('deckmode');
    const deckEl = root.querySelector('#deck');
    const centerTab = () => { const c = root.querySelector('.dk-tabs'), on = c.querySelector('.on'); if (on) c.scrollLeft = on.offsetLeft - (c.clientWidth - on.offsetWidth) / 2; };
    requestAnimationFrame(centerTab);
    const stageEl = root.querySelector('#stage');

    function renderDeck() {
      root.querySelector('.dk-pos').innerHTML = list.length ? `${String(pos + 1).padStart(2, '0')}<em>/${list.length}</em>` : '00';
      if (!list.length) { deckEl.innerHTML = V6.empty('style', 'Nothing here yet', 'This tab has no novels. Pick another tab or add a story.', '<button class="btn acc" data-go="/add">Add a story</button>'); return; }
      const cards = [];
      for (let k = Math.min(2, list.length - 1); k >= 0; k--) cards.push(cardHtml(list[(pos + k) % list.length], k));
      deckEl.innerHTML = cards.join('');
      root.querySelector('[data-next]').innerHTML = list.slice(1, 8).map((_, k) => { const x = list[(pos + k + 1) % list.length]; return `<button class="dk-nx" data-jump="${(pos + k + 1) % list.length}"><span class="num">${String(((pos + k + 1) % list.length) + 1).padStart(2, '0')}</span>${V6.cover(x)}<span><b>${esc(x.title)}</b><small>${x.downloaded}/${x.total} CH · ★ ${x.score ? parseFloat(x.score).toFixed(2) : '—'}</small></span></button>`; }).join('');
      bindTop();
    }
    function advance(dir = 1) { pos = (pos + dir + list.length) % list.length; V6.state.deckPos[V6.state.tab] = pos; renderDeck(); }

    // ---------- swipe physics ----------
    function bindTop() {
      const card = deckEl.querySelector('.dk-card[data-d="0"]'); if (!card) return;
      let x0, y0, dx = 0, dy = 0, samples = [], down = false, moved = false, lp = null;
      card.addEventListener('pointerdown', (e) => {
        down = true; moved = false; x0 = e.clientX; y0 = e.clientY; dx = dy = 0; samples = [[e.clientX, e.clientY, performance.now()]];
        card.setPointerCapture(e.pointerId); card.style.transition = 'none';
        lp = setTimeout(() => { if (!moved) { down = false; card.style.transform = ''; navigator.vibrate && navigator.vibrate(12); V6.storySheet(list[pos]); } }, 450);
      });
      card.addEventListener('pointermove', (e) => {
        if (!down) return;
        dx = e.clientX - x0; dy = e.clientY - y0;
        if (Math.hypot(dx, dy) > 8) { moved = true; clearTimeout(lp); }
        samples.push([e.clientX, e.clientY, performance.now()]); if (samples.length > 5) samples.shift();
        const up = Math.max(0, -dy);
        card.style.transform = `translate(${dx}px, ${Math.min(dy, 40) * (dy > 0 ? 0.3 : 1)}px) rotate(${dx * 0.05}deg)`;
        card.querySelector('.dk-stamp.play').style.opacity = Math.max(0, Math.min(1, dx / 110));
        card.querySelector('.dk-stamp.dl').style.opacity = Math.max(0, Math.min(1, -dx / 110));
        card.querySelector('.dk-stamp.up').style.opacity = Math.abs(dx) < up ? Math.min(1, up / 110) : 0;
        deckEl.style.setProperty('--pull', Math.min(1, Math.hypot(dx, dy) / 140));
      });
      const end = () => {
        if (!down) return; down = false; clearTimeout(lp);
        const a = samples[0], b = samples[samples.length - 1]; const dt = Math.max(1, b[2] - a[2]);
        const vx = (b[0] - a[0]) / dt, vy = (b[1] - a[1]) / dt;
        deckEl.style.setProperty('--pull', 0);
        if (!moved) { card.style.transition = ''; card.style.transform = ''; return W.go('/story/' + list[pos].id); }
        if (dx > 110 || vx > 0.7) fling(card, 'right', vx);
        else if (dx < -110 || vx < -0.7) fling(card, 'left', vx);
        else if (dy < -110 || vy < -0.7) fling(card, 'up', vy);
        else { card.style.transition = 'transform .45s var(--spring)'; card.style.transform = ''; card.querySelectorAll('.dk-stamp').forEach((s) => (s.style.opacity = 0)); }
      };
      card.addEventListener('pointerup', end); card.addEventListener('pointercancel', end);
    }
    function fling(card, dir, v = 0) {
      const s = list[pos];
      if (dir === 'left' && !V6.remaining(s)) {
        card.style.transition = 'transform .5s var(--spring)'; card.style.transform = ''; card.classList.add('shake');
        card.querySelectorAll('.dk-stamp').forEach((x) => (x.style.opacity = 0));
        setTimeout(() => card.classList.remove('shake'), 500);
        return V6.toast(`All ${s.total} chapters already offline`, { icon: 'offline_pin' });
      }
      const sp = Math.max(0.28, 0.42 - Math.abs(v) * 0.08);
      card.style.transition = `transform ${sp}s cubic-bezier(.3,.6,.4,1), opacity ${sp}s`;
      const W2 = window.innerWidth;
      card.style.transform = dir === 'up' ? 'translateY(-120%) scale(.9)' : `translate(${dir === 'right' ? W2 : -W2}px, 30px) rotate(${dir === 'right' ? 24 : -24}deg)`;
      card.style.opacity = '0';
      card.querySelector(`.dk-stamp.${dir === 'right' ? 'play' : dir === 'left' ? 'dl' : 'up'}`).style.opacity = 1;
      setTimeout(() => {
        if (dir === 'up') return W.go('/story/' + s.id);
        if (dir === 'right') V6.act.play(s);
        if (dir === 'left') { const k = V6.remaining(s); W.downloads.enqueue(s.id); V6.toast(`Queued ${k} chapters · ${esc(V6.short(s))}`, { icon: 'download', action: 'Undo', onAction: () => { W.downloads.remove(s.id); V6.toast('Removed from queue', { icon: 'undo' }); } }); }
        advance(1);
      }, sp * 1000 * 0.8);
    }

    // ---------- controls ----------
    root.addEventListener('click', (e) => {
      const m = e.target.closest('[data-mode]'); if (m) { V6.state.homeMode = 'grid'; return V6.refresh(); }
      const t = e.target.closest('[data-tab]'); if (t) { V6.state.tab = t.dataset.tab; root.querySelectorAll('[data-tab]').forEach((x) => x.classList.toggle('on', x === t)); list = deckList(); pos = (V6.state.deckPos[V6.state.tab] || 0) % Math.max(1, list.length); renderDeck(); return; }
      const j = e.target.closest('[data-jump]'); if (j) { pos = +j.dataset.jump; V6.state.deckPos[V6.state.tab] = pos; return renderDeck(); }
      const b = e.target.closest('[data-dk]');
      if (b && list.length) {
        const card = deckEl.querySelector('.dk-card[data-d="0"]');
        const a = b.dataset.dk;
        if (a === 'prev') advance(-1);
        else if (a === 'more') V6.storySheet(list[pos]);
        else fling(card, a);
        return;
      }
      const st = e.target.closest('[data-stage]'); if (!st) return;
      const a = st.dataset.stage;
      e.stopPropagation();
      if (a === 'toggle') W.player.toggle();
      else if (a === 'resume') { const s = W.story(W.session.storyId); W.player.load(s.id, W.session.chapterId, { index: W.session.index, play: true }); }
      else if (a === 'dismiss') { V6.state.continueDismissed = true; stageEl.innerHTML = stageHtml(); V6.toast('Hidden until you listen again', { icon: 'visibility_off', action: 'Undo', onAction: () => { V6.state.continueDismissed = false; stageEl.innerHTML = stageHtml(); } }); }
      else if (a === 'open') W.go('/player');
      else if (a === 'stop') { W.player.stop(); V6.toast('Player closed', { icon: 'stop', action: 'Undo', onAction: () => { const ss = W.session; W.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: false }); } }); }
      else if (a === 'back15') W.player.skip(-15);
      else if (a === 'fwd30') W.player.skip(30);
      else if (a === 'speed') W.player.cycleRate();
      else if (a === 'sleep') V6.sleepSheet();
    });

    // stage live updates (patch in place to keep the scrub smooth)
    let scrubbing = false;
    function bindScrub() {
      const tr = stageEl.querySelector('[data-stage="scrub"]'); if (!tr) return;
      V6.scrub(tr, (f) => { scrubbing = true; tr.querySelector('i').style.width = f * 100 + '%'; tr.querySelector('b').style.left = f * 100 + '%'; }, (f) => { scrubbing = false; W.player.seekFraction(f); });
    }
    let lastKey = '';
    ctx.sub(W.player.on((p) => {
      if (scrubbing) return;
      const key = `${p.loaded}|${p.story && p.story.id}|${p.chapter && p.chapter.id}|${p.playing}|${p.buffering}|${p.rate}|${p.sleep}`;
      if (key !== lastKey) { lastKey = key; stageEl.innerHTML = stageHtml(); bindScrub(); return; }
      const t = stageEl.querySelector('.st-time .num'); if (t) t.textContent = W.fmt.clock(p.remainingSec);
      const i = stageEl.querySelector('.st-track i'), bb = stageEl.querySelector('.st-track b');
      if (i) { i.style.width = p.progress * 100 + '%'; bb.style.left = p.progress * 100 + '%'; }
    }));
    ctx.sub(W.downloads.on((st) => { const e = root.querySelector('[data-tk="dl"]'); if (e) e.innerHTML = `${st.summary.done}<em>/${st.summary.total}</em>`; }));
    ctx.sub(W.narration.on((st) => { const e = root.querySelector('[data-tk="au"]'); if (e) e.innerHTML = st.current ? `${Math.round(st.current.progress * 100)}<em>%</em>` : st.queuedCount; }));
    renderDeck(); bindScrub();
    if (route.query.play) setTimeout(() => (W.player.state().loaded ? W.player.play() : V6.act.play(W.story(W.session.storyId))), 50);
  };
})();
