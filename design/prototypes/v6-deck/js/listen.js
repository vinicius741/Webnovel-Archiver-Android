/* v6 — Reader (calm, live TTS highlight, swipe between chapters) and Now Playing (pull down to dismiss). */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;

  // ---------- Reader ----------
  V6.screens.reader = (ctx) => {
    const { root, route } = ctx;
    const s = W.story(route.params.id); if (!s) { root.innerHTML = V6.topbar('Not found', ''); return; }
    const list = W.chapters(s.id);
    const c = W.chapter(s.id, route.params.chapterId);
    const ci = W.chapterIndex(s.id, c.id);
    const sents = W.sentences(s.id, c.id);
    const paras = W.chapterText(s.id, c.id);
    const rs = W.settings.reader;
    const prev = list.slice(0, ci).reverse().find((x) => x.downloaded), next = list.slice(ci + 1).find((x) => x.downloaded);
    const isBm = () => V6.bookmarkOf(s) === ci;
    const thisPlaying = () => { const p = W.player.state(); return p.loaded && p.story.id === s.id && p.chapter.id === c.id; };

    // sentences grouped into paragraphs, each sentence its own span for highlighting
    let si = 0;
    const body = paras.map((_, p) => {
      const out = [];
      while (si < sents.length && sents[si].p === p) { out.push(`<span class="sn" data-s="${si}">${esc(sents[si].text)}</span>`); si++; }
      return `<p data-p="${p}">${out.join(' ')}</p>`;
    }).join('');

    root.innerHTML = `
      <header class="rd-h">
        <button class="tb-back" data-back aria-label="Back">${ic('arrow_back')}</button>
        <div class="rd-ht"><b>${esc(c.title)}</b><small class="mono">${ci + 1} / ${list.length} · ${esc(V6.short(s))}</small></div>
        <button class="ib" data-a="bm" aria-label="Bookmark">${ic('bookmark', isBm() ? 'fill acc-t' : '')}</button>
        <button class="ib" data-a="aloud" aria-label="Read aloud">${ic('record_voice_over')}</button>
        <button class="ib" data-a="set" aria-label="Reader settings">${ic('text_fields')}</button>
      </header>
      <div class="rd-prog"><i></i></div>
      <article class="rd ${rs.serif ? 'serif' : ''}" style="--fs:${rs.fontScale}">
        <div class="rd-kick mono">Chapter ${c.index}</div>
        <h1 class="rd-title">${esc(c.title.replace(/^Chapter\s+\d+\s*[:.-]\s*/i, '')) || esc(c.title)}</h1>
        <div class="rd-body">${body}</div>
        <div class="rd-end">
          <span class="kick">End of chapter ${c.index}</span>
          ${next ? `<button class="rd-next" data-go="/reader/${s.id}/${next.id}"><span class="kick">Next ${ic('arrow_forward')}</span><b>${esc(next.title)}</b></button>` : '<p class="dim">You are at the latest downloaded chapter.</p>'}
        </div>
      </article>
      <div class="rd-peek l">${prev ? `${ic('arrow_back')}<b>Ch ${prev.index}</b>` : '<b>First</b>'}</div><div class="rd-peek r">${next ? `<b>Ch ${next.index}</b>${ic('arrow_forward')}` : '<b>Latest</b>'}</div>
      <div class="rd-bar" data-bar></div>`;

    const art = root.querySelector('.rd');
    const bar = root.querySelector('[data-bar]');

    function renderBar() {
      const p = W.player.state();
      if (thisPlaying()) {
        bar.className = 'rd-bar dockp';
        bar.innerHTML = `<div class="mn-prog"><i style="width:${p.progress * 100}%"></i></div><div class="rdp">
          <button class="ib" data-p="back" aria-label="Back 15 seconds">${ic('replay')}</button>
          <button class="mn-play" data-p="toggle" aria-label="Play or pause">${ic(p.buffering ? 'progress_activity' : p.playing ? 'pause' : 'play_arrow', p.buffering ? 'spin' : '')}</button>
          <button class="ib" data-p="fwd" aria-label="Forward 30 seconds">${ic('forward_media')}</button>
          <button class="rdp-t" data-go="/player"><span class="num">${W.fmt.clock(p.remainingSec)}</span><small class="mono">left · ${p.rate}×</small></button>
          <button class="ib" data-go="/player" aria-label="Open player">${ic('expand_less')}</button></div>`;
      } else {
        bar.className = 'rd-bar nav';
        bar.innerHTML = `<button class="btn ghost sm" ${prev ? `data-go="/reader/${s.id}/${prev.id}"` : 'disabled'}>${ic('arrow_back')} Prev</button><span class="num rd-pos">${ci + 1}<em>/${list.length}</em></span><button class="btn ghost sm" ${next ? `data-go="/reader/${s.id}/${next.id}"` : 'disabled'}>Next ${ic('arrow_forward')}</button>`;
      }
    }
    let lastIdx = -1;
    function highlight(follow = true) {
      const p = W.player.state();
      const on = thisPlaying();
      const prevEl = art.querySelector('.sn.cur'); if (prevEl && (!on || +prevEl.dataset.s !== p.index)) prevEl.classList.remove('cur');
      if (!on) { lastIdx = -1; return; }
      const el = art.querySelector(`.sn[data-s="${p.index}"]`);
      if (el) { el.classList.add('cur'); if (follow && p.index !== lastIdx && !userScrolling) { const r = el.getBoundingClientRect(); root.scrollTo({ top: root.scrollTop + r.top - root.clientHeight * 0.42, behavior: 'smooth' }); } }
      lastIdx = p.index;
    }
    let userScrolling = false, usT = null, lastY = 0;
    root.addEventListener('scroll', () => {
      const y = root.scrollTop; const pr = root.querySelector('.rd-prog i'); pr.style.width = Math.min(100, (y / Math.max(1, root.scrollHeight - root.clientHeight)) * 100) + '%';
      document.body.classList.toggle('dock-hide', y > lastY + 4 && y > 80); if (y < lastY - 6) document.body.classList.remove('dock-hide');
      lastY = y;
    }, { passive: true });
    root.addEventListener('touchstart', () => { userScrolling = true; clearTimeout(usT); }, { passive: true });
    root.addEventListener('touchend', () => { usT = setTimeout(() => (userScrolling = false), 2500); }, { passive: true });

    // double-tap a paragraph to read from there
    let lastTap = 0, lastP = null;
    art.addEventListener('click', (e) => {
      const pEl = e.target.closest('[data-p]'); if (!pEl) return;
      const now = Date.now();
      if (now - lastTap < 320 && lastP === pEl) {
        const first = pEl.querySelector('.sn'); if (first) { W.player.load(s.id, c.id, { index: +first.dataset.s, play: true }); V6.toast('Reading from here', { icon: 'record_voice_over' }); }
        lastTap = 0; return;
      }
      lastTap = now; lastP = pEl;
    });

    // horizontal swipe between chapters
    (() => {
      let x0 = null, y0 = 0, dx = 0, mode = null;
      art.addEventListener('pointerdown', (e) => { x0 = e.clientX; y0 = e.clientY; dx = 0; mode = null; });
      art.addEventListener('pointermove', (e) => {
        if (x0 == null) return; const mx = e.clientX - x0, my = e.clientY - y0;
        if (!mode) { if (Math.abs(mx) > 14 && Math.abs(mx) > Math.abs(my) * 1.6) mode = 'x'; else if (Math.abs(my) > 12) { x0 = null; return; } }
        if (mode !== 'x') return;
        dx = mx; art.style.transform = `translateX(${dx * 0.5}px)`; art.style.transition = 'none';
        root.classList.toggle('peek-l', dx > 60); root.classList.toggle('peek-r', dx < -60);
      });
      const end = () => {
        if (x0 == null) return; x0 = null;
        art.style.transition = 'transform .3s var(--ease)'; art.style.transform = '';
        root.classList.remove('peek-l', 'peek-r');
        if (mode === 'x' && Math.abs(dx) > 110) { const t = dx < 0 ? next : prev; if (t) W.go(`/reader/${s.id}/${t.id}`, { replace: true }); else V6.toast(dx < 0 ? 'This is the latest downloaded chapter' : 'This is the first chapter', { icon: 'block' }); }
      };
      art.addEventListener('pointerup', end); art.addEventListener('pointercancel', end);
    })();

    root.addEventListener('click', (e) => {
      const pb = e.target.closest('[data-p]'); if (pb && bar.contains(pb)) { const a = pb.dataset.p; if (a === 'toggle') W.player.toggle(); if (a === 'back') W.player.skip(-15); if (a === 'fwd') W.player.skip(30); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const k = a.dataset.a;
      if (k === 'bm') { V6.setBookmark(s, isBm() ? null : ci); a.innerHTML = ic('bookmark', isBm() ? 'fill acc-t' : ''); V6.toast(isBm() ? `Bookmarked chapter ${ci + 1}` : 'Bookmark removed', { icon: 'bookmark' }); }
      if (k === 'aloud') aloud();
      if (k === 'set') settings();
    });
    function aloud() {
      const ses = W.session; const p = W.player.state();
      const items = [];
      if (p.loaded && !thisPlaying()) items.push({ icon: 'play_circle', label: `Continue listening: ${V6.short(p.story)}`, sub: p.chapter.title, run: () => { W.player.play(); W.go('/player'); } });
      else if (ses && ses.storyId === s.id && !p.loaded) items.push({ icon: 'play_circle', label: `Continue listening: ${ses.chapterTitle}`, sub: `Sentence ${ses.index + 1}`, run: () => W.player.load(s.id, ses.chapterId, { index: ses.index, play: true }) });
      items.push({ icon: 'record_voice_over', label: 'Listen from this chapter', sub: c.title, run: () => W.player.load(s.id, c.id, { index: 0, play: true }) });
      const vis = [...art.querySelectorAll('.sn')].find((x) => x.getBoundingClientRect().top > 80);
      if (vis) items.push({ icon: 'vertical_align_center', label: 'Listen from here', sub: vis.textContent.slice(0, 60), run: () => W.player.load(s.id, c.id, { index: +vis.dataset.s, play: true }) });
      V6.sheet({ title: 'Read aloud', items });
    }
    function settings() {
      V6.sheet({ title: 'Reader settings', html: `<div class="sh-pad rs">
        <div class="rs-font"><button class="btn ghost" data-f="-1" aria-label="Smaller">A−</button><span class="num" data-fv>${Math.round(rs.fontScale * 100)}<em>%</em></span><button class="btn ghost" data-f="1" aria-label="Larger">A+</button></div>
        <div class="seg wide"><button data-ff="0" class="${!rs.serif ? 'on' : ''}">Sans</button><button data-ff="1" class="${rs.serif ? 'on' : ''}">Serif</button></div>
        <div class="row nob"><span class="rt"><b>Dark reader</b><small>Always on in this app</small></span>${V6.sw(rs.dark, 'data-dark')}</div></div>
        <button class="sh-it" data-vs>${ic('record_voice_over')}<span class="sh-l"><b>Voice settings</b><small>Bella · Kokoro · ${W.player.state().rate}×</small></span></button>
        <button class="sh-it" data-cp>${ic('content_copy')}<span class="sh-l"><b>Copy chapter text</b></span></button>`,
      onMount(b, api) {
        b.addEventListener('click', (e) => {
          const f = e.target.closest('[data-f]'); if (f) { rs.fontScale = Math.max(0.8, Math.min(1.6, Math.round((rs.fontScale + 0.1 * +f.dataset.f) * 10) / 10)); art.style.setProperty('--fs', rs.fontScale); b.querySelector('[data-fv]').innerHTML = `${Math.round(rs.fontScale * 100)}<em>%</em>`; }
          const ff = e.target.closest('[data-ff]'); if (ff) { rs.serif = ff.dataset.ff === '1'; art.classList.toggle('serif', rs.serif); b.querySelectorAll('[data-ff]').forEach((x) => x.classList.toggle('on', x === ff)); }
          const d = e.target.closest('[data-dark]'); if (d) { d.classList.add('on'); V6.toast('The reader stays dark — light themes are off in this build', { icon: 'dark_mode' }); }
          if (e.target.closest('[data-vs]')) { api.close(); W.go('/settings/tts'); }
          if (e.target.closest('[data-cp]')) { api.close(); V6.toast('Chapter text copied', { icon: 'content_copy' }); }
        });
      } });
    }

    let lastKey = '';
    ctx.sub(W.player.on((p) => {
      const key = `${thisPlaying()}|${p.playing}|${p.buffering}|${p.rate}`;
      if (key !== lastKey) { lastKey = key; renderBar(); } else if (thisPlaying()) { const t = bar.querySelector('.rdp-t .num'); if (t) t.textContent = W.fmt.clock(p.remainingSec); const pr = bar.querySelector('.mn-prog i'); if (pr) pr.style.width = p.progress * 100 + '%'; }
      // the player moved on to the next chapter: follow it
      if (p.loaded && p.story.id === s.id && p.chapter.id !== c.id && p.playing && lastPlayingHere) { lastPlayingHere = false; W.go(`/reader/${s.id}/${p.chapter.id}`, { replace: true }); return; }
      lastPlayingHere = thisPlaying();
      highlight();
    }));
    let lastPlayingHere = thisPlaying();
    renderBar(); highlight(false);
    if (route.query.play && thisPlaying() && !W.player.state().playing) W.player.play();
    else if (route.query.play && !thisPlaying()) {
      const ses = W.session; const idx = ses && ses.chapterId === c.id ? ses.index : 0;
      W.player.load(s.id, c.id, { index: idx, play: true });
    }
    if (thisPlaying()) setTimeout(() => { const el = art.querySelector('.sn.cur'); if (el) root.scrollTop += el.getBoundingClientRect().top - root.clientHeight * 0.42; }, 60);
  };

  // ---------- Now Playing ----------
  V6.screens.player = (ctx) => {
    const { root, route } = ctx;
    if (!W.player.state().loaded) {
      if (route.query.play || W.session) { const ses = W.session; W.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: !!route.query.play }); }
    }
    if (route.query.play && W.player.state().loaded && !W.player.state().playing) W.player.play();
    const p0 = W.player.state();
    if (!p0.loaded) { root.innerHTML = V6.topbar('Now Playing', '') + V6.empty('headphones', 'Nothing playing', 'Swipe a card right on the deck to start listening.', '<button class="btn acc" data-go="/library">Open the deck</button>'); return; }
    root.innerHTML = `<div class="pl-sheet">
      <div class="grab pl-grab"><i></i></div>
      <header class="pl-h"><button class="ib" data-back aria-label="Close player">${ic('keyboard_arrow_down')}</button><div class="pl-ht"><span class="kick">Now playing</span><small class="mono" data-voice></small></div><button class="ib" data-a="audio" aria-label="Chapter audio">${ic('library_music')}</button></header>
      <div class="pl-grid">
        <div class="pl-art"><div class="pl-cov" data-cov></div><blockquote class="pl-sent" data-sent></blockquote></div>
        <div class="pl-ctl">
          <div class="pl-titles"><button class="pl-story" data-a="story"></button><h1 class="pl-ch" data-ch></h1><span class="mono dim" data-chn></span></div>
          <div class="pl-time"><span class="num" data-left></span><span class="pl-tl"><small class="mono">left</small><small class="mono" data-el></small></span></div>
          <div class="pl-seek" data-seek><div class="st-track"><i></i><b></b></div></div>
          <div class="pl-tr">
            <button class="ib" data-a="prev" aria-label="Previous chapter">${ic('skip_previous')}</button>
            <button class="ib lg" data-a="back" aria-label="Back 15 seconds">${ic('replay')}<small>15</small></button>
            <button class="pl-play" data-a="toggle" aria-label="Play or pause"></button>
            <button class="ib lg" data-a="fwd" aria-label="Forward 30 seconds">${ic('forward_media')}<small>30</small></button>
            <button class="ib" data-a="next" aria-label="Next chapter">${ic('skip_next')}</button>
          </div>
          <div class="pl-chips">
            <button class="chip" data-a="speed" data-speed></button><button class="chip" data-a="sleep" data-sleep></button>
            <button class="chip" data-a="hist">${ic('history')} History</button><button class="chip" data-a="stop">${ic('stop')} Stop</button>
          </div>
          <button class="btn solid wide" data-a="along">${ic('menu_book')} Read along</button>
        </div>
      </div></div>`;
    const $ = (q) => root.querySelector(q);
    let scrubbing = false, lastStory = null;
    function upd(p) {
      if (!p.loaded) return W.back();
      if (lastStory !== p.story.id) { lastStory = p.story.id; $('[data-cov]').innerHTML = V6.cover(p.story, '', true); }
      $('[data-voice]').textContent = `Voice: ${p.voice}`;
      $('.pl-story').textContent = p.story.title;
      $('[data-ch]').textContent = p.chapter.title;
      $('[data-chn]').textContent = `Chapter ${p.chapterIndex + 1} / ${p.chapterCount}`;
      $('[data-left]').textContent = W.fmt.clock(p.remainingSec);
      $('[data-el]').textContent = `${W.fmt.clock(p.elapsedSec / p.rate)} in`;
      const sent = p.sentences[p.index]; $('[data-sent]').innerHTML = sent ? `<span>${esc(sent.text)}</span>` : '';
      if (!scrubbing) { $('[data-seek] i').style.width = p.progress * 100 + '%'; $('[data-seek] b').style.left = p.progress * 100 + '%'; }
      $('.pl-play').innerHTML = ic(p.buffering ? 'progress_activity' : p.playing ? 'pause' : 'play_arrow', p.buffering ? 'spin' : 'fill');
      root.querySelector('.pl-sheet').classList.toggle('playing', p.playing);
      $('[data-speed]').innerHTML = `${ic('speed')} ${p.rate}×`;
      $('[data-sleep]').innerHTML = `${ic('bedtime')} ${p.sleep ? (p.sleep === 'chapter' ? 'End of chapter' : `${p.sleepLeftMin} min`) : 'Timer: Off'}`;
      $('[data-sleep]').classList.toggle('on', !!p.sleep);
    }
    V6.scrub($('[data-seek]'), (f) => { scrubbing = true; $('[data-seek] i').style.width = f * 100 + '%'; $('[data-seek] b').style.left = f * 100 + '%'; }, (f) => { scrubbing = false; W.player.seekFraction(f); });
    root.addEventListener('click', (e) => {
      const a = e.target.closest('[data-a]'); if (!a) return;
      const p = W.player.state(); const k = a.dataset.a;
      if (k === 'toggle') W.player.toggle();
      else if (k === 'back') W.player.skip(-15);
      else if (k === 'fwd') W.player.skip(30);
      else if (k === 'prev') W.player.prevChapter();
      else if (k === 'next') W.player.nextChapter();
      else if (k === 'speed') W.player.cycleRate();
      else if (k === 'sleep') V6.sleepSheet();
      else if (k === 'stop') { W.player.stop(); V6.toast('Stopped', { icon: 'stop' }); }
      else if (k === 'along') W.go(`/reader/${p.story.id}/${p.chapter.id}`);
      else if (k === 'audio') W.go(`/narration/${p.story.id}`);
      else if (k === 'story') W.go('/story/' + p.story.id);
      else if (k === 'hist') {
        const h = p.history.length ? p.history : W.data.session && W.data.session.history ? W.data.session.history.map((x) => ({ storyId: W.data.session.storyId, chapterId: x.chapterId, index: x.chunkIndex, excerpt: x.anchorText, at: x.recordedAt })) : [];
        V6.sheet({ title: 'Go back to', sub: 'Places you listened to recently', items: h.length ? h.map((x) => ({ icon: 'history', label: W.chapter(x.storyId, x.chapterId).title, sub: `“${x.excerpt || ''}” · ${W.fmt.ago(x.at)}`, run: () => W.player.restore(x) })) : [{ label: 'No history yet', disabled: true }] });
      }
    });
    // speed: long-press opens the full list
    V6.longPress(root, '[data-speed]', () => V6.speedSheet());
    // pull down anywhere on the art/header to dismiss
    const sheet = root.querySelector('.pl-sheet');
    V6.dragDismiss(root.querySelector('.pl-grab'), sheet, () => setTimeout(() => W.back(), 120), { area: root });
    V6.dragDismiss(root.querySelector('.pl-art'), sheet, () => setTimeout(() => W.back(), 120), { area: root });
    ctx.sub(W.player.on(upd));
    upd(W.player.state());
  };
})();
