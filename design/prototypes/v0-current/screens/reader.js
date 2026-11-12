/* v0 Reader (live TTS highlight, docked player) + Now Playing. */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon, P = W.player;

  A.register('reader', (route, ctx) => {
    const s = W.story(route.params.id);
    if (!s) return { el: W.el(`<div>${A.appBar({ title: 'Not found' })}</div>`) };
    const chs = W.chapters(s.id);
    const ch = W.chapter(s.id, route.params.chapterId);
    const ci = W.chapterIndex(s.id, ch.id);
    const sents = W.sentences(s.id, ch.id);
    const rs = W.settings.reader;
    const isBm = () => s.bookmark === ci + 1;
    const el = W.el(`<div class="reader">${A.appBar({ title: ch.title, sub: `${ci + 1} / ${chs.length}`, actions: [
      { id: 'bm', icon: 'bookmark', label: 'Bookmark', o: !isBm() }, { id: 'aloud', icon: 'volume_up', label: 'Read aloud' }, { id: 'rset', icon: 'more_vert', label: 'Reader settings' }] })}
      <div class="scroll r-scroll" data-keep><article class="r-text" data-text></article></div><div class="r-bottom" data-bottom></div></div>`);
    const text = el.querySelector('[data-text]');
    const sc = el.querySelector('.r-scroll');
    const applyFont = () => { text.style.fontSize = ((A.isWide() ? 20 : 18) * rs.fontScale).toFixed(1) + 'px'; };
    applyFont();
    const paras = [];
    sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="s" data-si="${i}">${esc(x.text)}</span>`); });
    text.innerHTML = `${paras.map((p, k) => `<p data-p="${k}">${p.join(' ')}</p>`).join('')}<div class="r-end">${ci + 1 < chs.length ? `<button class="btn tonal" data-a="next">Next chapter${ic('arrow_forward')}</button>` : '<span class="muted">You reached the latest chapter.</span>'}</div>`;

    const mine = (st) => st.loaded && st.story.id === s.id && st.chapter && st.chapter.id === ch.id;
    let lastIdx = -1, lastChapter = P.state().chapter && P.state().chapter.id;
    function bottom() {
      const st = P.state(); const b = el.querySelector('[data-bottom]');
      if (mine(st)) { b.innerHTML = A.miniHtml(st, true); b.className = 'r-bottom docked'; return; }
      b.className = 'r-bottom';
      b.innerHTML = `<div class="r-nav"><button class="btn text" data-a="prev"${ci ? '' : ' disabled'}>${ic('chevron_left')}Previous chapter</button><span class="muted">${ci + 1} / ${chs.length}</span><button class="btn text" data-a="next"${ci + 1 < chs.length ? '' : ' disabled'}>Next chapter${ic('chevron_right')}</button></div>`;
    }
    function highlight(scroll) {
      const st = P.state();
      const prev = text.querySelector('.s.hl'); if (prev) prev.classList.remove('hl');
      if (!mine(st)) return;
      const cur = text.querySelector(`.s[data-si="${st.index}"]`);
      if (cur) { cur.classList.add('hl'); if (scroll && st.index !== lastIdx) { const r = cur.getBoundingClientRect(), rr = sc.getBoundingClientRect(); sc.scrollTo({ top: sc.scrollTop + r.top - rr.top - rr.height / 2 + r.height / 2, behavior: lastIdx < 0 ? 'auto' : 'smooth' }); } }
      lastIdx = st.index;
    }
    A.bindMini(el.querySelector('[data-bottom]'));
    ctx.sub(P.on((st) => {
      // follow the narration into the next chapter
      if (st.loaded && st.story.id === s.id && lastChapter === ch.id && st.chapter.id !== ch.id && st.playing) { W.go(`/reader/${s.id}/${st.chapter.id}`, { replace: true }); return; }
      lastChapter = st.chapter && st.chapter.id;
      bottom(); highlight(st.playing);
    }));
    bottom();

    function startAt(i) { P.load(s.id, ch.id, { index: i, play: true }); }
    function readAloud() {
      const st = P.state(); const ses = W.session;
      const cont = st.loaded ? { title: `${st.chapter.title}`, fn: () => { if (!mine(st)) W.go(`/reader/${st.story.id}/${st.chapter.id}`); P.play(); } }
        : ses && ses.storyId === s.id ? { title: ses.chapterTitle, fn: () => { if (ses.chapterId !== ch.id) { W.go(`/reader/${s.id}/${ses.chapterId}?play=1`); } else P.load(s.id, ch.id, { index: ses.index, play: true }); } } : null;
      if (mine(st)) { P.toggle(); return; }
      if (!cont) { startAt(0); return; }
      const d = A.dialog({ title: 'Read aloud', body: `<button class="dg-item" data-k="c">${ic('play_arrow')}<div>Continue listening: ${esc(cont.title)}</div></button><button class="dg-item" data-k="h">${ic('menu_book', true)}<div>Listen from this chapter</div></button>`, actions: [{ label: 'Cancel' }] });
      d.el.addEventListener('click', (e) => { const k = e.target.closest('[data-k]'); if (!k) return; d.close(); k.dataset.k === 'c' ? cont.fn() : startAt(0); });
    }
    function settingsPanel() {
      const body = () => `<div class="rs-font"><button class="btn tonal sm" data-f="-1">A−</button><b>${Math.round(rs.fontScale * 100)}%</b><button class="btn tonal sm" data-f="1">A+</button></div>
        <button class="dg-item" data-k="dark">${ic('dark_mode')}<div style="flex:1">Dark reader</div><span class="gold" style="font-size:15px;font-weight:700">${rs.dark ? 'On' : 'Off'}</span></button>
        <button class="dg-item" data-k="voice">${ic('record_voice_over')}<div>Voice settings</div></button>
        <button class="dg-item" data-k="copy">${ic('content_copy')}<div>Copy chapter text</div></button>`;
      const d = A.dialog({ title: 'Reader settings', body: body(), actions: [{ label: 'Close' }] });
      d.el.addEventListener('click', (e) => {
        const f = e.target.closest('[data-f]'); const k = e.target.closest('[data-k]');
        if (f) { rs.fontScale = Math.max(0.8, Math.min(1.6, Math.round((rs.fontScale + 0.1 * +f.dataset.f) * 10) / 10)); applyFont(); d.el.querySelector('.dg-body').innerHTML = body(); }
        if (k && k.dataset.k === 'dark') { rs.dark = !rs.dark; el.classList.toggle('light-reader', !rs.dark); d.el.querySelector('.dg-body').innerHTML = body(); }
        if (k && k.dataset.k === 'voice') { d.close(); W.go('/settings/tts'); }
        if (k && k.dataset.k === 'copy') { d.close(); A.toast('Chapter text copied'); }
      });
    }
    // double-tap a paragraph to read from there
    let lastTap = { t: 0, p: null };
    text.addEventListener('click', (e) => {
      const p = e.target.closest('p[data-p]'); if (!p) return;
      const now = Date.now();
      if (lastTap.p === p && now - lastTap.t < 350) { const first = p.querySelector('.s'); if (first) { startAt(+first.dataset.si); A.toast('Reading from this paragraph'); } lastTap = { t: 0, p: null }; return; }
      lastTap = { t: now, p };
    });
    el.addEventListener('click', (e) => {
      const a = e.target.closest('[data-act],[data-a]'); if (!a) return;
      const k = a.dataset.act || a.dataset.a;
      if (k === 'bm') { s.bookmark = isBm() ? null : ci + 1; a.innerHTML = ic('bookmark', !isBm()); A.toast(isBm() ? 'Bookmarked' : 'Bookmark removed'); }
      if (k === 'aloud') readAloud();
      if (k === 'rset') settingsPanel();
      if (k === 'prev' && ci > 0) W.go(`/reader/${s.id}/${chs[ci - 1].id}`, { replace: true });
      if (k === 'next' && ci + 1 < chs.length) W.go(`/reader/${s.id}/${chs[ci + 1].id}`, { replace: true });
    });
    return {
      el, mini: false,
      mounted() {
        if (route.query.play) { const ses = W.session; const i = ses && ses.chapterId === ch.id ? ses.index : 0; startAt(i); }
        else if (mine(P.state())) highlight(true);
        if (!rs.dark) el.classList.add('light-reader');
      },
    };
  });

  // ---------- Now Playing ----------
  const SLEEP = [['Off', null], ['15 minutes', 15], ['30 minutes', 30], ['45 minutes', 45], ['60 minutes', 60], ['End of chapter', 'chapter']];
  A.register('player', (route, ctx) => {
    const el = W.el(`<div class="player"><div data-bar></div><div class="scroll pl-scroll" data-sc></div></div>`);
    if (route.query.play && !P.state().playing) { const ses = W.session; if (P.state().loaded) P.play(); else if (ses) P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); }
    function draw() {
      const st = P.state();
      el.querySelector('[data-bar]').innerHTML = A.appBar({ title: 'Now Playing', sub: `Voice: ${st.voice}`, actions: st.loaded ? [{ id: 'chaudio', icon: 'headphones', label: 'Chapter audio' }] : [] });
      const sc = el.querySelector('[data-sc]');
      if (!st.loaded) {
        const ses = W.session;
        sc.innerHTML = `<div class="empty"><div class="disc">${ic('headphones')}</div><b>Nothing playing</b><span>Open a chapter and tap Read aloud.</span>${ses ? `<button class="btn tonal" data-a="resume" style="margin-top:16px">${ic('play_arrow')}Continue listening</button>` : ''}</div>`;
        return;
      }
      const el2 = st.elapsedSec / st.rate;
      const timer = st.sleep == null ? 'Off' : st.sleep === 'chapter' ? 'End of chapter' : `${st.sleepLeftMin} min`;
      sc.innerHTML = `<div class="pl">
        <button class="pl-cover" data-a="story">${A.cover(st.story, 'c-detail')}</button>
        <div class="pl-story">${esc(st.story.title)}</div><div class="pl-ch">${esc(st.chapter.title)}</div><div class="pl-n">Chapter ${st.chapterIndex + 1} / ${st.chapterCount}</div>
        <div class="pl-seek"><input type="range" min="0" max="1000" value="${Math.round(st.progress * 1000)}" data-seek style="--pct:${(st.progress * 100).toFixed(1)}%"><div class="pl-times"><span>${W.fmt.clock(el2)}</span><span>~-${W.fmt.clock(st.remainingSec)}</span></div></div>
        <div class="pl-transport">
          <button class="icon-btn gold" data-a="prevch" aria-label="Previous chapter">${ic('skip_previous')}</button>
          <button class="icon-btn gold" data-a="back15" aria-label="Back 15 seconds">${ic('replay')}</button>
          <button class="pl-play" data-a="toggle" aria-label="${st.playing ? 'Pause' : 'Play'}">${st.buffering ? '<span class="spinner" style="width:30px;height:30px;border-width:3px"></span>' : ic(st.playing ? 'pause' : 'play_arrow')}</button>
          <button class="icon-btn gold" data-a="fwd30" aria-label="Forward 30 seconds" style="transform:scaleX(-1)">${ic('replay')}</button>
          <button class="icon-btn gold" data-a="nextch" aria-label="Next chapter">${ic('skip_next')}</button>
        </div>
        <div class="pl-chips"><button class="btn text gold" data-a="rate">${st.rate}x</button><button class="btn text" data-a="sleep">Timer: ${timer}</button><button class="btn text" data-a="hist">History</button><button class="btn text" data-a="stop">Stop</button></div>
        <button class="btn tonal" data-a="along">${ic('menu_book', true)}Read along</button>
      </div>`;
    }
    let dragging = false;
    ctx.sub(P.on(() => { if (!dragging) draw(); }));
    el.addEventListener('input', (e) => { if (e.target.matches('[data-seek]')) { dragging = true; e.target.style.setProperty('--pct', e.target.value / 10 + '%'); } });
    el.addEventListener('change', (e) => { if (e.target.matches('[data-seek]')) { dragging = false; P.seekFraction(e.target.value / 1000); } });
    el.addEventListener('click', (e) => {
      const a = e.target.closest('[data-a],[data-act]'); if (!a) return;
      const k = a.dataset.a || a.dataset.act; const st = P.state();
      if (k === 'resume') { const ses = W.session; P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); }
      if (k === 'chaudio') W.go('/narration/' + st.story.id);
      if (k === 'story') W.go('/story/' + st.story.id);
      if (k === 'toggle') P.toggle();
      if (k === 'prevch') P.prevChapter();
      if (k === 'nextch') P.nextChapter();
      if (k === 'back15') P.skip(-15);
      if (k === 'fwd30') P.skip(30);
      if (k === 'rate') P.cycleRate();
      if (k === 'stop') { P.stop(); W.back(); }
      if (k === 'along') W.go(`/reader/${st.story.id}/${st.chapter.id}`);
      if (k === 'sleep') A.choose({ title: 'Sleep Timer', options: SLEEP.map(([l, v]) => ({ label: l, value: v })), value: st.sleep, fn: (v) => { P.setSleep(v); A.toast(v == null ? 'Sleep timer off' : v === 'chapter' ? 'Stopping at the end of this chapter' : `Stopping in ${v} minutes`); } });
      if (k === 'hist') {
        const h = st.history;
        const d = A.dialog({ title: 'Go back to', body: h.length ? h.map((x, i) => { const hs = W.story(x.storyId); const hc = W.chapter(x.storyId, x.chapterId); return `<button class="dg-item" data-h="${i}">${ic('history')}<div>${esc(hc.title)}<small>${esc(hs.title)} · sentence ${x.index + 1} · ${W.fmt.ago(x.at)}</small></div></button>`; }).join('') : '<p style="margin:0">No earlier positions yet. When you jump to another chapter, where you were is kept here.</p>', actions: [{ label: 'Close' }] });
        d.el.addEventListener('click', (ev) => { const b = ev.target.closest('[data-h]'); if (b) { d.close(); P.restore(h[+b.dataset.h]); } });
      }
    });
    draw();
    return { el, mini: false };
  });
})();
