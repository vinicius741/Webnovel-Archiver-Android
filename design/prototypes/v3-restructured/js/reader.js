/* v3 Restructured — Reader with live TTS sentence highlighting, follow-along scroll, docked player, reader settings. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  const R = { black: false };

  A.screens.reader = {
    nav: false, mini: false,
    render(route) {
      const s = W.story(route.params.id);
      const el = A.view('readerv');
      if (!s) { el.innerHTML = A.appbar({ title: 'Not found', back: true }); return { el }; }
      const list = W.chapters(s.id);
      const c = W.chapter(s.id, route.params.chapterId);
      const ci = W.chapterIndex(s.id, c.id);
      const sents = W.sentences(s.id, c.id);
      let follow = true;
      const offs = [];
      el.classList.toggle('black', R.black);

      const paras = [];
      sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="sn" data-si="${i}">${esc(x.text)} </span>`); });
      const firstOf = (p) => sents.findIndex((x) => x.p === p);
      const isBm = () => A.bookmark(s) === ci;
      const bar = () => A.appbar({
        title: esc(c.title), sub: `${ci + 1} / ${list.length} · ${esc(A.short(s.title, 26))}`, back: true,
        actions: `<button class="ib ${isBm() ? 'bmon' : ''}" data-act="bm" aria-label="Bookmark">${A.icon('bookmark', isBm() ? 'f' : '')}</button>` + A.ab('headphones', 'aloud', 'Read aloud') + A.ab('text_fields', 'settings', 'Reader settings'),
      });
      const prev = list[ci - 1], next = list[ci + 1];
      el.innerHTML = `<div data-r="bar" style="display:contents">${bar()}</div>
        <article class="rtext" style="--rs:${W.settings.reader.fontScale}">
          <div class="rkicker">${esc(s.title)} · Chapter ${c.index}</div>
          <h1>${esc(c.title)}</h1>
          ${c.downloaded ? paras.map((p, i) => `<p data-p="${i}">${p.join('')}</p>`).join('') : `<div class="card pad16 nd-note">${A.icon('cloud_off', 'gold')}<div class="grow"><b>This chapter isn’t downloaded</b><div class="muted">Download it to read and listen offline.</div></div><button class="btn sm fill" data-act="dlch">Download</button></div>`}
          <div class="rend">${next ? `<button class="nextcard" data-go="${next.id}"><span class="muted">Next chapter</span><b>${esc(next.title)}</b>${A.icon('arrow_forward')}</button>` : `<div class="muted" style="text-align:center">You’re at the latest chapter.</div>`}</div>
        </article>
        <button class="followpill" data-act="refollow" hidden>${A.icon('my_location', 'sm')}Back to current sentence</button>
        <div class="rdock" data-r="dock"></div>`;
      const dock = el.querySelector('[data-r=dock]');
      const pill = el.querySelector('.followpill');

      function renderDock() {
        const st = W.player.state();
        if (st.loaded) {
          const same = st.story.id === s.id && st.chapter.id === c.id;
          if (dock.dataset.mode !== 'player') {
            dock.dataset.mode = 'player';
            dock.innerHTML = `<div class="rplayer"><div class="bar"><i></i></div><button class="play" data-act="toggle" aria-label="Play or pause"></button>
              <button class="grow mt" data-act="open"><b class="nowrap" data-f="t"></b><span class="nowrap" data-f="s"></span></button>
              <button class="ib" data-act="back15" aria-label="Back 15 seconds">${A.icon('fast_rewind')}</button><button class="ratechip" data-act="rate"></button>
              <button class="ib" data-act="open" aria-label="Open player">${A.icon('expand_less')}</button><button class="ib" data-act="close" aria-label="Close player">${A.icon('close')}</button></div>`;
          }
          dock.querySelector('[data-act=toggle]').innerHTML = st.buffering ? '<span class="spinner"></span>' : A.icon(st.playing ? 'pause' : 'play_arrow', 'f');
          dock.querySelector('[data-f=t]').textContent = same ? `Sentence ${st.index + 1} of ${st.sentences.length}` : st.chapter.title;
          dock.querySelector('[data-f=s]').textContent = `${fmt.dur(st.remainingSec)} left${same ? '' : ' · ' + A.short(st.story.title, 20)}`;
          dock.querySelector('[data-act=rate]').textContent = st.rate + '×';
          dock.querySelector('.bar i').style.width = Math.round(st.progress * 100) + '%';
        } else if (dock.dataset.mode !== 'nav') {
          dock.dataset.mode = 'nav';
          dock.innerHTML = `<div class="rnav"><button class="btn text" data-go="${prev ? prev.id : ''}" ${prev ? '' : 'disabled'}>${A.icon('chevron_left')}Previous</button><span class="muted">${ci + 1} / ${list.length}</span><button class="btn text" data-go="${next ? next.id : ''}" ${next ? '' : 'disabled'}>Next${A.icon('chevron_right')}</button></div>`;
        }
      }
      let lastIdx = -1, lastCh = null;
      function highlight() {
        const st = W.player.state();
        const same = st.loaded && st.story.id === s.id && st.chapter.id === c.id;
        // player moved on to the next chapter while we were following: follow it
        if (st.loaded && st.story.id === s.id && lastCh === c.id && st.chapter.id !== c.id && follow) { W.go(`/reader/${s.id}/${st.chapter.id}`, { replace: true }); return; }
        lastCh = st.loaded ? st.chapter.id : null;
        if (!same) { const o = el.querySelector('.sn.hl'); if (o) o.classList.remove('hl'); lastIdx = -1; return; }
        if (st.index === lastIdx) return;
        lastIdx = st.index;
        el.querySelectorAll('.sn.hl').forEach((x) => x.classList.remove('hl'));
        const sp = el.querySelector(`.sn[data-si="${st.index}"]`);
        if (sp) { sp.classList.add('hl'); if (follow) { autoScrolling = true; sp.scrollIntoView({ block: 'center', behavior: 'smooth' }); setTimeout(() => (autoScrolling = false), 700); } }
      }
      let autoScrolling = false;
      const stopFollow = () => { if (autoScrolling) return; const st = W.player.state(); if (st.loaded && st.playing && st.chapter.id === c.id) { follow = false; pill.hidden = false; } };
      el.addEventListener('touchmove', stopFollow, { passive: true });
      el.addEventListener('wheel', stopFollow, { passive: true });

      function readAloud() {
        const st = W.player.state();
        if (st.loaded && st.story.id === s.id && st.chapter.id === c.id) { W.player.toggle(); return; }
        const sess = W.session;
        const resume = st.loaded ? { storyId: st.story.id, chapterId: st.chapter.id, title: st.chapter.title, index: st.index } : sess && sess.storyId === s.id ? { storyId: sess.storyId, chapterId: sess.chapterId, title: sess.chapterTitle, index: sess.index } : null;
        if (resume && resume.chapterId !== c.id) {
          A.menu({ title: 'Read aloud', sections: [{ items: [
            { icon: 'history', label: `Continue listening`, sub: `${resume.title} · sentence ${resume.index + 1}`, fn: () => { W.player.load(resume.storyId, resume.chapterId, { index: resume.index, play: true }); } },
            { icon: 'play_arrow', label: 'Listen from this chapter', sub: c.title, fn: () => start(0) },
          ] }] });
        } else start(resume && resume.chapterId === c.id ? resume.index : 0);
      }
      const start = (i) => { follow = true; pill.hidden = true; W.player.load(s.id, c.id, { index: i, play: true }); };

      function settings() {
        const scale = () => Math.round(W.settings.reader.fontScale * 100);
        A.sheet({
          title: 'Reader settings',
          body: `<div class="fontrow"><button class="btn surface" data-f="-1" aria-label="Smaller text">A−</button><div class="grow" style="text-align:center"><b data-r="pct" style="font-size:20px">${scale()}%</b><div class="muted" style="font-size:12px">Text size</div></div><button class="btn surface" data-f="1" aria-label="Larger text" style="font-size:18px">A+</button></div>
            <div class="list" style="margin-top:14px">
              <div class="li"><span class="lead">${A.icon('dark_mode')}</span><div class="grow"><div class="t">Dark reader</div><div class="s">Warm dark page</div></div>${A.sw(true, 'data-sw="dark"')}</div>
              <div class="li"><span class="lead">${A.icon('contrast')}</span><div class="grow"><div class="t">Pure black</div><div class="s">Saves battery on OLED screens</div></div>${A.sw(R.black, 'data-sw="black"')}</div>
              <button class="li" data-x="voice"><span class="lead">${A.icon('record_voice_over')}</span><div class="grow"><div class="t">Voice settings</div><div class="s">Bella · Kokoro · ${W.player.state().rate}×</div></div>${A.icon('chevron_right', 'trail')}</button>
              <button class="li" data-x="copy"><span class="lead">${A.icon('content_copy')}</span><div class="grow"><div class="t">Copy chapter text</div></div></button>
            </div>`,
          onMount(sh, close) {
            W.on(sh, 'click', '[data-f]', (e, b) => { const r = W.settings.reader; r.fontScale = Math.max(0.8, Math.min(1.6, Math.round((r.fontScale + 0.1 * b.dataset.f) * 10) / 10)); sh.querySelector('[data-r=pct]').textContent = scale() + '%'; el.querySelector('.rtext').style.setProperty('--rs', r.fontScale); });
            W.on(sh, 'click', '[data-sw]', (e, b) => {
              if (b.dataset.sw === 'dark') { A.toast('The reader always stays dark'); return; }
              R.black = !R.black; b.classList.toggle('on', R.black); el.classList.toggle('black', R.black);
            });
            W.on(sh, 'click', '[data-x]', (e, b) => { close(); if (b.dataset.x === 'voice') W.go('/settings/tts'); else A.toast('Chapter text copied'); });
          },
        });
      }

      // double-tap a paragraph to read from there
      let lastTap = { t: 0, p: null };
      el.addEventListener('click', (e) => {
        const p = e.target.closest('p[data-p]'); if (!p) return;
        const now = Date.now();
        if (lastTap.p === p && now - lastTap.t < 320) { start(firstOf(+p.dataset.p)); A.toast('Reading from this paragraph'); lastTap = { t: 0, p: null }; }
        else lastTap = { t: now, p };
      });
      W.on(el, 'click', '[data-go]', (e, b) => { if (b.dataset.go) W.go(`/reader/${s.id}/${b.dataset.go}`, { replace: true }); });
      W.on(el, 'click', '[data-act]', (e, b) => {
        const a = b.dataset.act;
        if (a === 'bm') { const on = isBm(); A.state.bookmarks[s.id] = on ? null : ci; el.querySelector('[data-r=bar]').innerHTML = bar(); A.emit(); A.toast(on ? 'Bookmark removed' : 'Bookmarked this chapter'); }
        else if (a === 'aloud') readAloud();
        else if (a === 'settings') settings();
        else if (a === 'toggle') W.player.toggle();
        else if (a === 'back15') W.player.skip(-15);
        else if (a === 'rate') W.player.cycleRate();
        else if (a === 'open') W.go('/player');
        else if (a === 'close') W.player.stop();
        else if (a === 'refollow') { follow = true; pill.hidden = true; lastIdx = -1; highlight(); }
        else if (a === 'dlch') { W.downloads.enqueue(s.id, [c.id]); A.toast('Downloading this chapter', 'View', () => W.go('/queue')); }
      });
      offs.push(W.player.on(() => { renderDock(); highlight(); }));
      renderDock();
      if (route.query.play) {
        const sess = W.session;
        setTimeout(() => start(sess && sess.chapterId === c.id ? sess.index : 0), 60);
      } else setTimeout(highlight, 50);
      return { el, dispose() { offs.forEach((f) => f()); } };
    },
  };
})();
