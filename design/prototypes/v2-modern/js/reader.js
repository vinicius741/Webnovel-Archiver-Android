/* Reader (live TTS highlight, auto-scroll, docked player) and Now Playing. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const P = WNA.player;
  const skipIc = (n, fwd) => `<span class="skipic ${fwd ? 'fwd' : ''}">${ic('replay')}<i>${n}</i></span>`;
  const splitTitle = (t) => { const m = String(t).match(/^((?:Chapter|Ch\.?|Book|Part)\s*[\w.]+)\s*[:\-–—]\s*(.+)$/i); return m ? [m[1], m[2]] : ['', t]; };
  V2.splitTitle = splitTitle;
  const fontScale = () => WNA.settings.reader.fontScale || 1;

  function readAloudSheet(s, c, firstVisible) {
    const ses = WNA.session; const st = P.state();
    const items = [];
    if (st.loaded && st.story.id === s.id) items.push({ icon: 'play_circle', label: `Resume · ${st.chapter.title}`, sub: `${fmt.dur(st.remainingSec)} left`, onClick: () => { if (st.chapter.id !== c.id) V2.go('/player'); else P.play(); } });
    else if (ses && ses.storyId === s.id) items.push({ icon: 'history', label: 'Continue listening', sub: `${ses.chapterTitle} · sentence ${ses.index + 1}`, onClick: () => { P.load(s.id, ses.chapterId, { index: ses.index }); if (ses.chapterId !== c.id) V2.go(`/reader/${s.id}/${ses.chapterId}`); } });
    items.push({ icon: 'play_arrow', label: 'Listen from this chapter', sub: 'Start at the top', onClick: () => P.load(s.id, c.id, { index: 0 }) });
    if (firstVisible > 0) items.push({ icon: 'my_location', label: 'Listen from here', sub: 'Start at the first sentence on screen', onClick: () => P.load(s.id, c.id, { index: firstVisible }) });
    items.push({ icon: 'record_voice_over', label: 'Voice & speech settings', onClick: () => V2.go('/settings/tts') });
    V2.menu({ title: 'Read aloud', sub: `${esc(st.voice || 'Bella · Kokoro')} · ${(st.rate || 1.8)}×`, items });
  }

  // ---------- Reader ----------
  V2.screens.reader = (ctx) => {
    const s = WNA.story(ctx.params.id); const page = ctx.el;
    if (!s) { page.innerHTML = `${V2.bar({ title: 'Reader', cls: 'always' })}<div class="scroll"><div class="empty"><h3>Chapter not found</h3></div></div>`; return; }
    ctx.mini = false; V2.theme(page, s);
    const list = WNA.chapters(s.id); const c = WNA.chapter(s.id, ctx.params.chapterId); const ci = WNA.chapterIndex(s.id, c.id);
    const sents = WNA.sentences(s.id, c.id);
    const [lab, name] = splitTitle(c.title);
    const words = sents.reduce((a, x) => a + x.text.split(/\s+/).length, 0);
    const paras = []; sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="snt" data-i="${i}">${esc(x.text)}</span>`); });
    const prev = list.slice(0, ci).reverse().find((x) => x.downloaded), next = list.slice(ci + 1).find((x) => x.downloaded);
    page.classList.add('rd');
    page.innerHTML = `<header class="bar always rd-bar">${V2.ib('arrow_back', 'back', 'Back')}<div class="bt"><b>${esc(c.title)}</b><small>${ci + 1} / ${list.length} · ${esc(s.title)}</small></div><div class="acts">
        <button class="ib ${s.bookmark === ci ? 'on' : ''}" data-a="bm" aria-label="Bookmark">${ic('bookmark', s.bookmark === ci ? 'fill' : '')}</button>${V2.ib('volume_up', 'aloud', 'Read aloud')}${V2.ib('text_fields', 'settings', 'Reader settings')}</div><div class="rd-prog"><i></i></div></header>
      <div class="scroll rd-scroll"><article class="rd-text" style="--fs:${18 * fontScale()}px">
        <header class="rd-h">${lab ? `<span class="t-lm accent">${esc(lab.toUpperCase())}</span>` : ''}<h1>${esc(name)}</h1><p class="dim">${esc(s.title)} · ${Math.max(1, Math.round(words / 230))} min read</p></header>
        ${paras.map((p, i) => `<p data-p="${i}">${(p || []).join(' ')}</p>`).join('')}
        <footer class="rd-end">${next ? `<button class="card high rd-next tap" data-go="${next.id}"><span class="t-lm accent">UP NEXT</span><b>${esc(next.title)}</b><span class="btn filled s">Next chapter${ic('arrow_forward', 's18')}</span></button>` : `<div class="card high rd-next"><span class="t-lm accent">YOU’RE CAUGHT UP</span><b>That’s the latest downloaded chapter.</b></div>`}</footer>
      </article></div>
      <button class="chip solid rd-return hide" data-a="return">${ic('my_location', 's18')}Back to the voice</button>
      <div class="rd-dock"></div>`;
    const sc = page.querySelector('.scroll'), bar = page.querySelector('.bar'), dock = page.querySelector('.rd-dock'), ret = page.querySelector('.rd-return');
    const text = page.querySelector('.rd-text');
    let userScrollAt = 0, cur = -1, chromeHidden = false, lastY = 0, progScroll = false;

    function renderDock(st) {
      const mine = st.loaded && st.story.id === s.id && st.chapter.id === c.id;
      if (st.loaded && st.story.id === s.id) {
        if (dock.dataset.mode !== 'player') { dock.dataset.mode = 'player'; dock.innerHTML = `<div class="rd-player"><button class="ib np-play" data-a="play"></button><button class="grow rd-pinfo" data-a="open"><b class="nowrap"></b><span class="wv"></span></button><button class="chip s solid" data-a="rate"></button>${V2.ib('keyboard_arrow_up', 'open', 'Open player')}</div>`; }
        const pb = dock.querySelector('.np-play'); pb.classList.toggle('playing', st.playing);
        pb.innerHTML = st.buffering ? '<span class="loader"></span>' : ic(st.playing ? 'pause' : 'play_arrow', 'fill');
        dock.querySelector('.rd-pinfo b').textContent = mine ? `${fmt.dur(st.remainingSec)} left · sentence ${st.index + 1} of ${st.sentences.length}` : `Playing ${st.chapter.title}`;
        const wv = dock.querySelector('.wv'); if (!wv.firstChild) wv.innerHTML = V2.wavy(st.progress);
        V2.setWavy(wv.firstChild, st.progress); wv.firstChild.classList.toggle('flat', !st.playing);
        dock.querySelector('[data-a="rate"]').textContent = `${st.rate}×`;
      } else if (dock.dataset.mode !== 'nav') {
        dock.dataset.mode = 'nav';
        dock.innerHTML = `<div class="rd-nav"><button class="btn tonal" data-go="${prev ? prev.id : ''}" ${prev ? '' : 'disabled'}>${ic('chevron_left')}Previous</button><button class="rd-pos" data-a="toc"><b>${ci + 1}</b><span>/ ${list.length}</span></button><button class="btn filled" data-go="${next ? next.id : ''}" ${next ? '' : 'disabled'}>Next${ic('chevron_right')}</button></div>`;
      }
    }
    function highlight(st, force) {
      const mine = st.loaded && st.story.id === s.id && st.chapter.id === c.id;
      const i = mine ? st.index : -1;
      if (i === cur && !force) return;
      const old = text.querySelector('.snt.cur'); if (old) old.classList.remove('cur');
      cur = i; if (i < 0) { ret.classList.add('hide'); return; }
      const n = text.querySelector(`.snt[data-i="${i}"]`); if (!n) return;
      n.classList.add('cur');
      if (Date.now() - userScrollAt > 4000 || force) {
        const r = n.getBoundingClientRect(), sr = sc.getBoundingClientRect();
        progScroll = true; sc.scrollTo({ top: sc.scrollTop + r.top - sr.top - sr.height * 0.4, behavior: force === 'jump' ? 'auto' : 'smooth' });
        setTimeout(() => (progScroll = false), 700); ret.classList.add('hide');
      } else ret.classList.remove('hide');
    }
    const onPlayer = (st) => {
      if (!page.isConnected) return;
      // follow the voice into the next chapter while reading along
      if (st.loaded && st.story.id === s.id && st.chapter.id !== c.id && cur >= 0 && st.playing && V2.top() && V2.top().el === page) { V2.replace(`/reader/${s.id}/${st.chapter.id}`); return; }
      renderDock(st); highlight(st);
    };
    ctx.sub(P.on(onPlayer));
    renderDock(P.state());
    requestAnimationFrame(() => highlight(P.state(), 'jump'));

    sc.addEventListener('scroll', () => {
      const y = sc.scrollTop; const d = y - lastY; lastY = y;
      if (!progScroll) userScrollAt = Date.now();
      page.querySelector('.rd-prog i').style.width = (y / Math.max(1, sc.scrollHeight - sc.clientHeight) * 100).toFixed(1) + '%';
      if (!progScroll && Math.abs(d) > 6) setChrome(d > 0 && y > 120);
      if (cur >= 0 && !progScroll) ret.classList.remove('hide');
    }, { passive: true });
    function setChrome(hide) { if (hide === chromeHidden) return; chromeHidden = hide; page.classList.toggle('immersive', hide); }

    const firstVisible = () => { const top = sc.getBoundingClientRect().top + 80; const n = [...text.querySelectorAll('.snt')].find((x) => x.getBoundingClientRect().bottom > top); return n ? +n.dataset.i : 0; };
    function settings() {
      const r = WNA.settings.reader;
      const sh = V2.sheet({ title: 'Reader settings', body: `<div class="pad col" style="gap:8px">
          <div class="fs-row"><button class="ib tonal" data-f="-">${ic('text_decrease')}</button><div class="grow col" style="align-items:center"><b class="t-hs fs-v">${Math.round(fontScale() * 100)}%</b><span class="t-bs dim">Text size</span></div><button class="ib tonal" data-f="+">${ic('text_increase')}</button></div>
          <p class="fs-prev" style="font-size:${18 * fontScale()}px">The quick brown fox reads by lamplight.</p></div>
        <div class="seglist" style="margin-top:8px">
          <button class="li" data-f="dark"><span class="lead">${ic('dark_mode')}</span><span class="tx"><b>Dark reader</b><span>Ink-dark page, softer white text</span></span><span class="switch ${r.dark ? 'on' : ''}"></span></button>
          <button class="li" data-f="voice"><span class="lead">${ic('record_voice_over')}</span><span class="tx"><b>Voice settings</b><span>${esc(WNA.settings.tts.voiceLabel)} · ${WNA.settings.tts.rate}×</span></span>${ic('chevron_right')}</button>
          <button class="li" data-f="copy"><span class="lead">${ic('content_copy')}</span><span class="tx"><b>Copy chapter text</b><span>${fmt.int(words)} words</span></span></button>
        </div>` });
      sh.body.addEventListener('click', (e) => {
        const b = e.target.closest('[data-f]'); if (!b) return; const f = b.dataset.f;
        if (f === '+' || f === '-') { r.fontScale = V2.clamp(Math.round((fontScale() + (f === '+' ? 0.1 : -0.1)) * 10) / 10, 0.8, 1.6); text.style.setProperty('--fs', 18 * r.fontScale + 'px'); sh.body.querySelector('.fs-v').textContent = Math.round(r.fontScale * 100) + '%'; sh.body.querySelector('.fs-prev').style.fontSize = 18 * r.fontScale + 'px'; }
        if (f === 'dark') { r.dark = !r.dark; b.querySelector('.switch').classList.toggle('on', r.dark); page.classList.toggle('sepia', !r.dark); }
        if (f === 'voice') { sh.close(); V2.go('/settings/tts'); }
        if (f === 'copy') { sh.close(); V2.copy(WNA.chapterText(s.id, c.id).join('\n\n'), 'Chapter text copied'); }
      });
    }
    function toc() {
      const items = list.slice(Math.max(0, ci - 3), ci + 8).map((x) => ({ icon: x.id === c.id ? 'radio_button_checked' : x.downloaded ? 'article' : 'cloud_off', label: x.title, sub: x.downloaded ? (x.id === c.id ? 'Reading now' : `Chapter ${x.index}`) : 'Not downloaded', disabled: !x.downloaded, onClick: () => V2.replace(`/reader/${s.id}/${x.id}`) }));
      items.push({ icon: 'list', label: 'All chapters', onClick: () => V2.go(`/story/${s.id}`) });
      V2.menu({ title: 'Chapters', sub: esc(s.title), items });
    }
    let tapT = null, tapP = null;
    page.addEventListener('click', (e) => {
      const g = e.target.closest('[data-go]'); if (g) { if (g.dataset.go) V2.replace(`/reader/${s.id}/${g.dataset.go}`); return; }
      const a = e.target.closest('[data-a]');
      if (a) {
        switch (a.dataset.a) {
          case 'bm': { const was = s.bookmark; s.bookmark = s.bookmark === ci ? null : ci; a.classList.toggle('on', s.bookmark === ci); a.innerHTML = ic('bookmark', s.bookmark === ci ? 'fill' : ''); V2.snack(s.bookmark === ci ? `Bookmarked ${lab || 'this chapter'}` : 'Bookmark removed', { action: 'Undo', onAction: () => { s.bookmark = was; a.classList.toggle('on', s.bookmark === ci); a.innerHTML = ic('bookmark', s.bookmark === ci ? 'fill' : ''); } }); break; }
          case 'aloud': readAloudSheet(s, c, firstVisible()); break;
          case 'settings': settings(); break;
          case 'play': P.toggle(); break;
          case 'open': V2.go('/player'); break;
          case 'rate': P.cycleRate(); break;
          case 'return': userScrollAt = 0; highlight(P.state(), true); break;
          case 'toc': toc(); break;
        }
        return;
      }
      const p = e.target.closest('.rd-text p[data-p]');
      if (p) {
        if (tapP === p && tapT) { clearTimeout(tapT); tapT = null; tapP = null; const first = p.querySelector('.snt'); if (first) { P.load(s.id, c.id, { index: +first.dataset.i }); userScrollAt = 0; V2.snack('Reading from this paragraph'); } return; }
        tapP = p; clearTimeout(tapT); tapT = setTimeout(() => { tapT = null; tapP = null; setChrome(!chromeHidden); }, 280);
      }
    });
    if (ctx.query.play) { const ses = WNA.session; const idx = ses && ses.storyId === s.id && ses.chapterId === c.id ? ses.index : 0; const st = P.state(); if (!(st.loaded && st.chapter && st.chapter.id === c.id)) P.load(s.id, c.id, { index: idx }); }
  };

  // ---------- Now Playing ----------
  V2.screens.player = (ctx) => {
    const page = ctx.el; ctx.mini = false; ctx.anim = 'up';
    let st = P.state();
    if (!st.loaded) { const ses = WNA.session; if (ses) P.load(ses.storyId, ses.chapterId, { index: ses.index, play: !!ctx.query.play }); else P.load('rr_141704', null, { play: false }); st = P.state(); }
    else if (ctx.query.play && !st.playing) P.play();
    page.classList.add('np-page');
    let storyId = null, dragging = false;
    function shell() {
      st = P.state(); const s = st.story; storyId = s.id; V2.theme(page, s);
      page.innerHTML = `<div class="np-bg">${V2.coverUrl(s) ? `<img src="${V2.coverUrl(s)}" alt="">` : ''}</div>
        <header class="bar always np-bar">${V2.ib('keyboard_arrow_down', 'back', 'Close')}<button class="bt np-voice" data-a="voice"><b>Now playing</b><small>Voice: ${esc(st.voice)}</small></button><div class="acts">${V2.ib('graphic_eq', 'audio', 'Chapter audio')}${V2.ib('more_vert', 'more', 'More')}</div></header>
        <div class="scroll np"><div class="np-grid">
          <div class="np-art"><button class="np-cov" data-a="story">${V2.cover(s, { eager: true })}</button></div>
          <div class="np-ctl">
            <div class="np-meta"><h1 class="np-ch"></h1><button class="np-st" data-a="story">${esc(s.title)}</button><span class="t-lm dim np-n"></span></div>
            <button class="np-sent" data-a="read"><span class="np-q"></span></button>
            <div class="np-seek"><div class="np-track">${V2.wavy(0)}</div><span class="np-tip"></span></div>
            <div class="np-times t-lm"><span class="el"></span><span class="rm"></span></div>
            <div class="np-trans">
              <button class="ib np-sm" data-a="prev" aria-label="Previous chapter">${ic('skip_previous', 'fill')}</button>
              <button class="ib np-md" data-a="back15" aria-label="Back 15 seconds">${skipIc(15)}</button>
              <button class="np-big" data-a="play" aria-label="Play"></button>
              <button class="ib np-md" data-a="fwd30" aria-label="Forward 30 seconds">${skipIc(30, true)}</button>
              <button class="ib np-sm" data-a="next" aria-label="Next chapter">${ic('skip_next', 'fill')}</button>
            </div>
            <div class="chips np-chips"><button class="chip solid" data-a="rate">${ic('speed', 's18')}<span class="rt"></span></button><button class="chip solid" data-a="sleep">${ic('bedtime', 's18')}<span class="sl"></span></button><button class="chip solid" data-a="history">${ic('history', 's18')}History</button><button class="chip solid" data-a="stop">${ic('stop', 's18')}Stop</button></div>
            <button class="btn tonal l block np-read" data-a="read">${ic('auto_stories')}Read along</button>
          </div></div></div>`;
      update(st);
    }
    function update(x) {
      st = x; if (!x.loaded) return;
      if (x.story.id !== storyId) return shell();
      page.querySelector('.np-ch').textContent = x.chapter.title;
      page.querySelector('.np-n').textContent = `Chapter ${x.chapterIndex + 1} / ${x.chapterCount} · sentence ${x.index + 1} of ${x.sentences.length}`;
      const q = page.querySelector('.np-q'); const t = (x.sentences[x.index] || {}).text || '';
      if (q.textContent !== t) { q.textContent = t; q.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 300, easing: 'cubic-bezier(.05,.7,.1,1)' }); }
      if (!dragging) { const w = page.querySelector('.np-track .wavy'); V2.setWavy(w, x.progress); w.classList.toggle('flat', !x.playing); }
      page.querySelector('.np-times .el').textContent = fmt.clock(x.elapsedSec / x.rate);
      page.querySelector('.np-times .rm').textContent = '-' + fmt.clock(x.remainingSec);
      const b = page.querySelector('.np-big'); b.classList.toggle('playing', x.playing);
      b.innerHTML = x.buffering ? '<span class="loader l"></span>' : ic(x.playing ? 'pause' : 'play_arrow', 'fill');
      page.querySelector('.rt').textContent = `${x.rate}×`;
      page.querySelector('.sl').textContent = x.sleep === 'chapter' ? 'End of chapter' : x.sleep ? `${x.sleepLeftMin} min` : 'Timer: Off';
      page.querySelector('[data-a="sleep"]').classList.toggle('sel', !!x.sleep);
    }
    shell();
    ctx.sub(P.on((x) => { if (!page.isConnected) return; if (!x.loaded) return; update(x); }));
    const sleepT = setInterval(() => { if (st.sleep) update(P.state()); }, 15000); ctx.sub(() => clearInterval(sleepT));

    // seek by dragging the wavy track
    const fracAt = (e) => { const r = page.querySelector('.np-track').getBoundingClientRect(); return V2.clamp((e.clientX - r.left) / r.width, 0, 1); };
    page.addEventListener('pointerdown', (e) => { if (!e.target.closest('.np-seek')) return; dragging = true; page.querySelector('.np-seek').setPointerCapture(e.pointerId); seekPreview(fracAt(e)); });
    page.addEventListener('pointermove', (e) => { if (dragging) seekPreview(fracAt(e)); });
    page.addEventListener('pointerup', (e) => { if (!dragging) return; dragging = false; page.querySelector('.np-seek').classList.remove('drag'); P.seekFraction(fracAt(e)); });
    function seekPreview(f) {
      const seek = page.querySelector('.np-seek'); seek.classList.add('drag');
      V2.setWavy(seek.querySelector('.wavy'), f);
      const tip = seek.querySelector('.np-tip'); tip.style.left = (f * 100) + '%'; tip.textContent = fmt.clock((f * st.totalSec) / st.rate);
    }
    function history() {
      const hs = [...P.state().history, ...((WNA.data.session && WNA.data.session.history) || []).map((h) => ({ storyId: WNA.data.session.storyId, chapterId: h.chapterId, index: h.chunkIndex, at: h.recordedAt, excerpt: h.anchorText }))];
      if (!hs.length) { V2.sheet({ title: 'Go back to', body: `<div class="empty" style="padding:24px"><p>Places you jump away from will appear here.</p></div>` }); return; }
      V2.menu({ title: 'Go back to', sub: 'Places you listened before jumping', items: hs.slice(0, 8).map((h) => { const c = WNA.chapter(h.storyId, h.chapterId); return { icon: 'history', label: c.title, sub: `“${(h.excerpt || '').slice(0, 60)}…” · ${fmt.ago(h.at)}`, onClick: () => P.restore(h) }; }) });
    }
    page.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-a]'); if (!a) return; const x = P.state();
      switch (a.dataset.a) {
        case 'play': P.toggle(); break;
        case 'prev': P.prevChapter(); break;
        case 'next': P.nextChapter(); break;
        case 'back15': P.skip(-15); break;
        case 'fwd30': P.skip(30); break;
        case 'rate': P.cycleRate(); break;
        case 'sleep': { const v = await V2.choose({ title: 'Sleep timer', sub: 'Pause playback after…', value: x.sleep || 'off', options: [{ value: 'off', label: 'Off' }, ...[15, 30, 45, 60].map((m) => ({ value: m, label: `${m} minutes` })), { value: 'chapter', label: 'End of chapter' }] }); if (v != null) { P.setSleep(v === 'off' ? null : v); if (v !== 'off') V2.snack(v === 'chapter' ? 'Stopping at the end of this chapter' : `Sleep timer set for ${v} minutes`); } break; }
        case 'history': history(); break;
        case 'stop': P.stop(); V2.back(); setTimeout(() => V2.snack('Playback stopped'), 300); break;
        case 'read': V2.go(`/reader/${x.story.id}/${x.chapter.id}`); break;
        case 'story': V2.go(`/story/${x.story.id}`); break;
        case 'audio': V2.go(`/narration/${x.story.id}`); break;
        case 'voice': V2.go('/settings/tts'); break;
        case 'more': V2.menu({ title: 'Playback', items: [{ icon: 'speed', label: 'Playback speed', sub: `${x.rate}×`, onClick: async () => { const v = await V2.choose({ title: 'Playback speed', value: x.rate, options: P.SPEEDS.map((r) => ({ value: r, label: `${r}×` })) }); if (v) P.setRate(v); } }, { icon: 'record_voice_over', label: 'Voice & speech', onClick: () => V2.go('/settings/tts') }, { icon: 'graphic_eq', label: 'Chapter audio', onClick: () => V2.go(`/narration/${x.story.id}`) }, { icon: 'menu_book', label: 'Open story', onClick: () => V2.go(`/story/${x.story.id}`) }] }); break;
      }
    });
  };
})();
