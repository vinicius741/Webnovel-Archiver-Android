/* v4 Editorial — Reader (book typography + live TTS ink-wash) and Now Playing (audiobook player). */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon, S = A.state;
  const P = W.player;
  const WORDS = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten', 'Eleven', 'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen', 'Twenty'];
  const numWord = (n) => WORDS[n] || A.roman(n).toUpperCase();

  function sleepLabel(st) {
    if (!st.sleep) return 'Timer: Off';
    if (st.sleep === 'chapter') return 'Timer: End of chapter';
    return `Timer: ${st.sleepLeftMin} min`;
  }
  function sleepDialog() {
    const st = P.state();
    const opts = [['Off', null], ['15 minutes', 15], ['30 minutes', 30], ['45 minutes', 45], ['60 minutes', 60], ['End of chapter', 'chapter']];
    A.menu({ title: 'Sleep <i>timer</i>', sub: 'Stop reading aloud after…', groups: [{ items: opts.map(([l, v]) => ({ label: l, on: st.sleep === v, end: `<span class="radio ${st.sleep === v ? 'on' : ''}"></span>`, act: () => { P.setSleep(v); A.toast(v == null ? 'Sleep timer off' : v === 'chapter' ? 'Stopping at the end of this chapter' : `Stopping in ${v} minutes`); } })) }] });
  }
  function historySheet() {
    const h = P.state().history;
    const ses = W.session;
    const items = h.map((x) => { const s = W.story(x.storyId); const c = W.chapter(x.storyId, x.chapterId); return { icon: 'history', label: `${esc(A.shortTitle(s.title))} · <i>${esc(A.chParts(c.title, c.index).name || c.title)}</i>`, sub: `“${esc(x.excerpt.slice(0, 70))}…” · ${W.fmt.ago(x.at)}`, act: () => { P.restore(x); A.toast('Went back'); } }; });
    if (!items.length && ses) items.push({ icon: 'bookmark', label: `${esc(A.shortTitle(ses.storyTitle))} · <i>${esc(ses.chapterTitle)}</i>`, sub: `Saved session · sentence ${ses.index + 1}`, act: () => P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }) });
    A.menu({ title: 'Go <i>back to</i>', sub: items.length ? 'Places you listened from recently' : 'Nothing earlier yet', groups: [{ items }] });
  }
  A.sleepDialog = sleepDialog; A.historySheet = historySheet;

  function readAloud(s, c) {
    const st = P.state(); const ses = W.session;
    const cont = st.loaded ? { storyId: st.story.id, chapterId: st.chapter.id, index: st.index, title: st.chapter.title } : ses && ses.storyId === s.id ? { storyId: ses.storyId, chapterId: ses.chapterId, index: ses.index, title: ses.chapterTitle } : null;
    if (!cont || (cont.storyId === s.id && cont.chapterId === c.id)) { P.load(s.id, c.id, { index: cont ? cont.index : 0, play: true }); return; }
    A.dialog({ title: 'Read aloud', body: '<p>Pick up where you stopped, or start this chapter from the top.</p>', actions: [
      { label: `Continue: ${esc(A.chParts(cont.title).name || cont.title).slice(0, 28)}`, cls: 'text', act: () => { P.load(cont.storyId, cont.chapterId, { index: cont.index, play: true }); if (cont.chapterId !== c.id) W.go(`/reader/${cont.storyId}/${cont.chapterId}`, { replace: true }); } },
      { label: 'Listen from this chapter', act: () => P.load(s.id, c.id, { index: 0, play: true }) }] });
  }

  // ---------- Reader ----------
  A.screens.reader = (page, r) => {
    const s = W.story(r.params.id);
    if (!s) { page.innerHTML = A.bar({ title: 'Not found' }); return; }
    const list = W.chapters(s.id);
    const c = W.chapter(s.id, r.params.chapterId);
    const ci = W.chapterIndex(s.id, c.id);
    const parts = A.chParts(c.title, c.index);
    const sents = W.sentences(s.id, c.id);
    const prev = list[ci - 1], next = list[ci + 1];
    const rs = W.settings.reader;
    // group sentences into paragraphs
    const paras = []; sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="s" data-i="${i}">${esc(x.text)}</span>`); });
    const words = sents.reduce((a, x) => a + x.text.split(/\s+/).length, 0);

    page.innerHTML = `
      <header class="rbar">
        <button class="ib" data-back="/story/${s.id}" aria-label="Back">${I('arrow_back')}</button>
        <div class="ttl"><b class="clamp1">${esc(parts.name || c.title)}</b><small>${ci + 1} / ${list.length} · ${esc(A.shortTitle(s.title))}</small></div>
        <button class="ib bmk" data-act="bm" aria-label="Bookmark">${I('bookmark')}</button>
        <button class="ib" data-act="aloud" aria-label="Read aloud">${I('volume_up')}</button>
        <button class="ib" data-act="settings" aria-label="Reader settings">${I('text_fields')}</button>
      </header>
      <div class="rprog"><i></i></div>
      <article class="chapter ${rs.dark ? '' : 'dim'}" style="--fs:${rs.fontScale}">
        <header class="opener">
          <div class="kicker">${esc(A.shortTitle(s.title))}</div>
          <div class="op-num">Chapter <span>${numWord(c.index)}</span></div>
          <h1 class="disp">${esc(parts.name || c.title)}</h1>
          <div class="op-rule"><span>❦</span></div>
          <div class="meta dim op-meta">${Math.max(1, Math.round(words / 230))} min read · ${words.toLocaleString('en-US')} words${c.downloaded ? '' : ' · <span class="err">Not downloaded · showing a preview</span>'}</div>
        </header>
        <div class="text">${paras.map((p, i) => `<p data-p="${i}" class="${i === 0 ? 'first' : ''}">${p.join(' ')}</p>`).join('')}</div>
        <footer class="ch-end">
          <div class="ornament">⁂</div>
          <div class="label">End of chapter ${c.index}</div>
          ${next ? `<button class="next-card" data-go="/reader/${s.id}/${next.id}"><span class="kicker">Next</span><b>${esc(A.chParts(next.title, next.index).name || next.title)}</b><small>Chapter ${next.index} ${next.downloaded ? '' : '· not downloaded'}</small>${I('arrow_forward')}</button>` : '<p class="it muted">You’ve reached the latest chapter.</p>'}
        </footer>
      </article>
      <div class="rfoot"></div>`;
    const $ = (q) => page.querySelector(q);
    const textEl = $('.text');

    function bmIcon() { const on = s.bookmark === ci; const b = $('.bmk'); b.classList.toggle('on', on); b.innerHTML = I('bookmark', on ? 'fill' : ''); }
    bmIcon();

    let mineLast = false;
    function foot() {
      const st = P.state();
      const mine = st.loaded && st.story.id === s.id && st.chapter.id === c.id;
      if (mine) {
        $('.rfoot').innerHTML = `<div class="dock">
          <div class="prog"><i style="width:${(st.progress * 100).toFixed(1)}%"></i></div>
          <div class="dock-in">
            <button class="ib" data-act="back15" aria-label="Back 15 seconds">${I('replay_10')}</button>
            <button class="dock-play" data-act="toggle" aria-label="${st.playing ? 'Pause' : 'Play'}">${st.buffering ? '<span class="spin"></span>' : I(st.playing ? 'pause' : 'play_arrow', 'fill')}</button>
            <button class="ib" data-act="fwd30" aria-label="Forward 30 seconds">${I('forward_30')}</button>
            <button class="dock-mid" data-go="/player"><b>${W.fmt.dur(st.remainingSec)} left</b><small>Sentence ${st.index + 1} of ${st.sentences.length}</small></button>
            <button class="dock-rate" data-act="rate">${st.rate}×</button>
            <button class="ib" data-go="/player" aria-label="Open player">${I('expand_less')}</button>
            <button class="ib" data-act="stop" aria-label="Stop">${I('close')}</button>
          </div></div>`;
      } else {
        $('.rfoot').innerHTML = `<nav class="rnav">
          <button ${prev ? `data-go="/reader/${s.id}/${prev.id}"` : 'disabled'}>${I('chevron_left')}<span>Previous</span></button>
          <span class="rnav-n"><i>${ci + 1}</i> of ${list.length}</span>
          <button ${next ? `data-go="/reader/${s.id}/${next.id}"` : 'disabled'}><span>Next</span>${I('chevron_right')}</button></nav>`;
      }
      return mine;
    }
    let lastIdx = -1, userScrollAt = 0;
    function highlight(center) {
      const st = P.state();
      const mine = st.loaded && st.story.id === s.id && st.chapter.id === c.id;
      page.classList.toggle('tts', mine);
      if (!mine) { if (lastIdx >= 0) textEl.querySelectorAll('.s.now, .s.read').forEach((n) => n.classList.remove('now', 'read')); lastIdx = -1; return; }
      if (st.index === lastIdx) return;
      textEl.querySelectorAll('.s.now').forEach((n) => n.classList.remove('now'));
      textEl.querySelectorAll('.s').forEach((n) => n.classList.toggle('read', +n.dataset.i < st.index));
      const cur = textEl.querySelector(`.s[data-i="${st.index}"]`);
      if (cur) {
        cur.classList.add('now');
        if (center !== false && Date.now() - userScrollAt > 3500) {
          const rect = cur.getBoundingClientRect();
          if (r.query.shot) { // headless screenshots can't capture deep scroll offsets: shift the page instead
            const art = page.querySelector('.chapter'); const cur0 = parseFloat(art.dataset.shift || 0);
            const shift = Math.max(0, cur0 + rect.top - window.innerHeight * 0.42); art.dataset.shift = shift; art.style.transform = `translateY(${-shift}px)`;
          } else window.scrollTo({ top: window.scrollY + rect.top - window.innerHeight * 0.42, behavior: lastIdx < 0 ? 'auto' : 'smooth' });
        }
      }
      lastIdx = st.index;
    }
    function sync() {
      const st = P.state();
      // follow the player into the next chapter when it advances on its own
      if (mineLast && st.loaded && st.story.id === s.id && st.chapter.id !== c.id) { W.go(`/reader/${s.id}/${st.chapter.id}`, { replace: true }); return; }
      mineLast = foot(); highlight();
    }
    A.onPlayer = sync;
    if (r.query.play && !(P.state().loaded && P.state().chapter.id === c.id)) {
      const ses = W.session;
      P.load(s.id, c.id, { index: ses && ses.chapterId === c.id ? ses.index : 0, play: true });
    }
    sync();
    // re-centre once the book faces have loaded and reflowed the text
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { if (page.isConnected) { lastIdx = -1; highlight(); } });

    // progress + immersive bars
    let lastY = window.scrollY;
    const onScroll = () => {
      const y = window.scrollY; const h = document.documentElement.scrollHeight - window.innerHeight;
      $('.rprog i').style.width = (h > 0 ? Math.min(100, (y / h) * 100) : 0) + '%';
      const hide = y > 120 && y > lastY + 4; const show = y < lastY - 4 || y < 120;
      if (hide) page.classList.add('immersive'); else if (show) page.classList.remove('immersive');
      lastY = y;
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    const mark = () => { userScrollAt = Date.now(); };
    window.addEventListener('touchmove', mark, { passive: true }); window.addEventListener('wheel', mark, { passive: true });
    A.onLeave(() => { window.removeEventListener('scroll', onScroll); window.removeEventListener('touchmove', mark); window.removeEventListener('wheel', mark); });

    // double-tap a sentence to read aloud from there
    let lastTap = { t: 0, x: 0, y: 0 };
    const startAt = (sp) => { const i = +sp.dataset.i; P.load(s.id, c.id, { index: i, play: true }); userScrollAt = 0; A.toast(`Reading aloud from here`); };
    textEl.addEventListener('pointerup', (e) => {
      const sp = e.target.closest('.s'); if (!sp) return;
      const now = Date.now();
      if (now - lastTap.t < 320 && Math.hypot(e.clientX - lastTap.x, e.clientY - lastTap.y) < 30) { lastTap.t = 0; startAt(sp); } else lastTap = { t: now, x: e.clientX, y: e.clientY };
    });
    textEl.addEventListener('dblclick', (e) => e.preventDefault());

    A.acts(page, {
      bm: () => { const was = s.bookmark; s.bookmark = was === ci ? null : ci; bmIcon(); A.toast(s.bookmark === ci ? `Bookmarked chapter ${c.index}` : 'Bookmark removed', { action: 'Undo', onAction: () => { s.bookmark = was; bmIcon(); } }); },
      aloud: () => readAloud(s, c),
      settings: () => readerSettings(page),
      toggle: () => P.toggle(), back15: () => P.skip(-15), fwd30: () => P.skip(30), rate: () => P.cycleRate(),
      stop: () => { P.stop(); A.toast('Stopped reading aloud'); },
    });
    if (r.query.settings) readerSettings(page);
    return { noMini: true };
  };

  function readerSettings(page) {
    const rs = W.settings.reader;
    const body = () => `<div class="wrap rset">
      <div class="label">Text size</div>
      <div class="fsz"><button data-f="-1" aria-label="Smaller">A<sup>−</sup></button><b>${Math.round(rs.fontScale * 100)}%</b><button data-f="1" class="big" aria-label="Larger">A<sup>+</sup></button></div>
      <p class="sample" style="font-size:${(18 * rs.fontScale).toFixed(1)}px">The amphitheater came into view as we walked on, the crowd thickening with every step.</p>
      <button class="srow" data-k="dark"><span class="grow"><b>Dark reader</b><small>${rs.dark ? 'Ink-black page' : 'Dim sepia page'}</small></span><span class="switch ${rs.dark ? 'on' : ''}"></span></button>
      <button class="srow" data-k="voice"><span class="grow"><b>Voice settings</b><small>${esc(W.settings.tts.voiceLabel)} · ${P.state().rate}×</small></span>${I('chevron_right')}</button>
      <button class="srow" data-k="copy"><span class="grow"><b>Copy chapter text</b><small>Plain text, without the chapter title</small></span>${I('content_copy')}</button>
    </div>`;
    const L = A.sheet({ title: 'Reader <i>settings</i>', html: body() });
    L.el.addEventListener('click', (e) => {
      const f = e.target.closest('[data-f]'); const k = e.target.closest('[data-k]');
      if (f) { rs.fontScale = Math.max(0.8, Math.min(1.6, Math.round((rs.fontScale + (+f.dataset.f) * 0.1) * 10) / 10)); page.querySelector('.chapter').style.setProperty('--fs', rs.fontScale); }
      if (k && k.dataset.k === 'dark') { rs.dark = !rs.dark; page.querySelector('.chapter').classList.toggle('dim', !rs.dark); }
      if (k && k.dataset.k === 'voice') { L.close(); W.go('/settings/tts'); return; }
      if (k && k.dataset.k === 'copy') { L.close(); A.toast('Chapter text copied'); return; }
      if (f || k) L.el.querySelector('.sh-b').innerHTML = body();
    });
  }

  // ---------- Now Playing ----------
  A.screens.player = (page, r) => {
    if (r.query.play && !P.state().loaded && W.session) { const ses = W.session; P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); }
    function render() {
      const st = P.state();
      if (!st.loaded) {
        const ses = W.session;
        page.innerHTML = `${A.bar({ title: 'Now Playing', sub: 'Nothing is playing' })}
          <div class="empty"><div class="ornament">❦</div><h3>The room is quiet</h3><p>Start reading aloud from any chapter, or pick up your saved place.</p>
          ${ses ? `<button class="btn" data-act="resume">${I('play_arrow', 'fill')}Continue · ${esc(A.shortTitle(ses.storyTitle))}</button>` : ''}</div>`;
        return;
      }
      const s = st.story, c = st.chapter; const parts = A.chParts(c.title, c.index);
      page.innerHTML = `
        <header class="bar">
          <button class="ib" data-back aria-label="Close player">${I('keyboard_arrow_down')}</button>
          <div class="ttl" style="text-align:center"><b>Now Playing</b><small>Voice: ${esc(st.voice)}</small></div>
          <button class="ib" data-go="/narration/${s.id}" aria-label="Chapter audio">${I('graphic_eq')}</button>
        </header>
        <div class="np">
          <div class="np-head">
            <button class="np-cover" data-go="/story/${s.id}">${A.book(s, { lazy: false })}</button>
            <div class="np-t">
              <div class="kicker clamp1">${esc(A.shortTitle(s.title))}</div>
              <h1 class="disp">${esc(parts.name || c.title)}</h1>
              <div class="meta">Chapter ${st.chapterIndex + 1} of ${st.chapterCount}</div>
            </div>
          </div>
          <section class="passage" aria-live="polite">
            <div class="label">Currently reading</div>
            <div class="pass-body"></div>
          </section>
          <div class="seek">
            <input type="range" class="rng" min="0" max="${Math.max(0, st.sentences.length - 1)}" step="1" value="${st.index}" aria-label="Seek">
            <div class="times tab-nums"><span class="el"></span><span class="rm"></span></div>
          </div>
          <div class="transport">
            <button class="ib" data-act="prevCh" aria-label="Previous chapter">${I('skip_previous')}</button>
            <button class="ib" data-act="back" aria-label="Back 15 seconds">${I('replay_10')}</button>
            <button class="np-play" data-act="toggle"></button>
            <button class="ib" data-act="fwd" aria-label="Forward 30 seconds">${I('forward_30')}</button>
            <button class="ib" data-act="nextCh" aria-label="Next chapter">${I('skip_next')}</button>
          </div>
          <div class="np-chips">
            <button class="chip on" data-act="rate"></button>
            <button class="chip" data-act="sleep"></button>
            <button class="chip" data-act="hist">${I('history')}History</button>
            <button class="chip" data-act="stop">${I('stop')}Stop</button>
          </div>
          <button class="btn tonal block np-along" data-go="/reader/${s.id}/${c.id}">${I('chrome_reader_mode')}Read along</button>
        </div>`;
      const rng = page.querySelector('.seek input');
      rng.addEventListener('input', () => { P.seekSentence(+rng.value); });
      update();
    }
    let key = '';
    function update() {
      const st = P.state();
      const k = st.loaded ? st.story.id + st.chapter.id : '';
      if (k !== key) { key = k; render(); return; }
      if (!st.loaded) return;
      const sn = st.sentences, i = st.index;
      const pb = page.querySelector('.pass-body');
      pb.innerHTML = `${i > 0 ? `<p class="pv">${esc(sn[i - 1].text)}</p>` : '<p class="pv first">❦</p>'}<p class="cur"><span>${esc(sn[i].text)}</span></p>${sn[i + 1] ? `<p class="nx">${esc(sn[i + 1].text)}</p>` : ''}`;
      const rng = page.querySelector('.seek input'); if (document.activeElement !== rng) rng.value = i;
      rng.style.setProperty('--p', (st.progress * 100).toFixed(1) + '%');
      const rate = st.rate;
      page.querySelector('.times .el').textContent = W.fmt.clock(st.elapsedSec / rate);
      page.querySelector('.times .rm').textContent = '−' + W.fmt.clock(st.remainingSec);
      page.querySelector('.np-play').innerHTML = st.buffering ? '<span class="spin"></span>' : I(st.playing ? 'pause' : 'play_arrow', 'fill');
      page.querySelector('.np-play').setAttribute('aria-label', st.playing ? 'Pause' : 'Play');
      page.querySelector('[data-act=rate]').innerHTML = `${I('speed')}${rate}×`;
      const sl = page.querySelector('[data-act=sleep]'); sl.innerHTML = `${I('bedtime')}${sleepLabel(st)}`; sl.classList.toggle('on', !!st.sleep);
    }
    A.onPlayer = update;
    key = 'x'; update();
    A.acts(page, {
      resume: () => { const ses = W.session; P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); },
      toggle: () => P.toggle(), back: () => P.skip(-15), fwd: () => P.skip(30), prevCh: () => P.prevChapter(), nextCh: () => P.nextChapter(),
      rate: () => P.cycleRate(), sleep: sleepDialog, hist: historySheet,
      stop: () => { P.stop(); A.toast('Stopped · your place is saved'); },
    });
    if (r.query.sheet === 'sleep') sleepDialog();
    return { noMini: true };
  };
})();
