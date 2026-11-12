/* v5-feed Reader: immersive text, live TTS sentence highlight + auto-scroll, docked player, settings. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const R = { scale: W.settings.reader.fontScale || 1, black: false, spacing: 1.7 };
  A.readerPrefs = R;

  function nav(s, c, dir) {
    const list = W.chapters(s.id), i = W.chapterIndex(s.id, c.id);
    const pool = dir > 0 ? list.slice(i + 1) : list.slice(0, i).reverse();
    return pool.find((x) => x.downloaded) || null;
  }

  A.screens.reader = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    const c = W.chapter(s.id, ctx.params.chapterId);
    const ci = W.chapterIndex(s.id, c.id), total = W.chapters(s.id).length;
    const sents = W.sentences(s.id, c.id);
    A.tint(ctx.el, s);
    const paras = [];
    sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="s" data-s="${i}">${esc(x.text)}</span>`); });
    const words = sents.reduce((a, x) => a + x.text.split(/\s+/).length, 0);
    const prev = nav(s, c, -1), next = nav(s, c, 1);
    ctx.el.innerHTML = `<div class="sc rd-sc ${R.black ? 'black' : ''}"><div class="rd-prog"><i></i></div>
        <div class="rd-head">${A.ambBg(s)}<div class="rd-head-in"><button class="eyebrow" data-go="/story/${s.id}">${esc(A.short(s.title))}</button><h1>${esc(c.title)}</h1>
          <p>Chapter ${ci + 1} of ${total} · ${Math.max(1, Math.round(words / 230))} min read · ${fmt.dur(words * 0.36 / W.player.state().rate)} to listen</p></div></div>
        <article class="rd-text" style="--fs:${R.scale};--lh:${R.spacing}">${paras.map((p, i) => `<p data-p="${i}">${(p || []).join(' ')}</p>`).join('')}</article>
        <div class="rd-end">${next ? `<button class="rd-next" data-ch="${next.id}"><small>Up next</small><b>${esc(next.title)}</b>${A.ic('arrow_forward')}</button>` : `<div class="empty">${A.ic('celebration')}<h3>You’re all caught up</h3><p>No more downloaded chapters.</p></div>`}
          <p class="faint" style="text-align:center;font-size:12.5px;margin-top:16px">Double-tap a paragraph to read aloud from there.</p></div></div>
      <header class="rd-tb"><button class="ibtn" data-back="/story/${s.id}" aria-label="Back">${A.ic('arrow_back')}</button>
        <div class="rd-tb-t"><b>${esc(c.title)}</b><small>${ci + 1} / ${total}</small></div>
        <button class="ibtn bmk ${s.bookmark === ci ? 'on' : ''}" data-bmk aria-label="Bookmark">${A.ic(s.bookmark === ci ? 'bookmark' : 'bookmark_add', s.bookmark === ci ? 'f' : '')}</button>
        <button class="ibtn" data-aloud aria-label="Read aloud">${A.ic('record_voice_over')}</button>
        <button class="ibtn" data-rset aria-label="Reader settings">${A.ic('text_fields')}</button></header>
      <button class="rd-follow" hidden>${A.ic('my_location')}Back to voice</button>
      <div class="rd-bottom"></div>`;
    const el = ctx.el, sc = el.querySelector('.sc'), tb = el.querySelector('.rd-tb'), bottom = el.querySelector('.rd-bottom'), follow = el.querySelector('.rd-follow');
    const spans = [...el.querySelectorAll('.s')];
    const prog = el.querySelector('.rd-prog i');

    // chrome hide-on-scroll + progress
    let lastY = 0, userScrollAt = 0, autoScrolling = false;
    sc.addEventListener('scroll', () => {
      const y = sc.scrollTop;
      prog.style.width = Math.min(100, (y / Math.max(1, sc.scrollHeight - sc.clientHeight)) * 100) + '%';
      if (y > lastY + 6 && y > 140) { tb.classList.add('hide'); bottom.classList.add('low'); } else if (y < lastY - 6) { tb.classList.remove('hide'); bottom.classList.remove('low'); }
      tb.classList.toggle('solid', y > 60);
      lastY = y;
      if (!autoScrolling) userScrollAt = Date.now();
    }, { passive: true });
    ['touchstart', 'wheel'].forEach((ev) => sc.addEventListener(ev, () => { autoScrolling = false; userScrollAt = Date.now(); }, { passive: true }));

    // ---------- bottom: chapter nav or docked player ----------
    const mine = (ps) => ps.loaded && ps.story.id === s.id && ps.chapter.id === c.id;
    function renderBottom(ps) {
      if (!ps.loaded) {
        bottom.className = 'rd-bottom';
        bottom.innerHTML = `<div class="rd-nav glass"><button class="ibtn" data-ch="${prev ? prev.id : ''}" ${prev ? '' : 'disabled'} aria-label="Previous chapter">${A.ic('skip_previous')}</button>
          <button class="rd-nav-c" data-chlist><b>${ci + 1} / ${total}</b><small>Chapters</small></button>
          <button class="ibtn" data-ch="${next ? next.id : ''}" ${next ? '' : 'disabled'} aria-label="Next chapter">${A.ic('skip_next')}</button>
          <button class="btn primary sm" data-listenhere>${A.ic('play_arrow', 'f')}Listen</button></div>`;
        return;
      }
      bottom.className = 'rd-bottom docked';
      if (!bottom.querySelector('.dock-p')) {
        bottom.innerHTML = `<div class="dock-p glass"><div class="dp-top"><button class="dp-info" data-go="/player">${A.cover(ps.story, 'dp-cv', true)}<span><b class="dp-t"></b><small class="dp-s"></small></span></button>
          <button class="chip dp-rate" data-rate></button><button class="ibtn sm" data-go="/player" aria-label="Open player">${A.ic('open_in_full')}</button><button class="ibtn sm" data-stop aria-label="Stop">${A.ic('close')}</button></div>
          <div class="dp-bar"><i></i></div>
          <div class="dp-ctl"><button class="ibtn" data-pch aria-label="Previous chapter">${A.ic('skip_previous', 'f')}</button><button class="ibtn" data-sk="-15" aria-label="Back 15 seconds">${A.ic('replay_10')}</button>
            <button class="ibtn dp-pp" data-pp aria-label="Play or pause"></button><button class="ibtn" data-sk="30" aria-label="Forward 30 seconds">${A.ic('forward_30')}</button><button class="ibtn" data-nch aria-label="Next chapter">${A.ic('skip_next', 'f')}</button></div></div>`;
      }
      const m = mine(ps);
      bottom.querySelector('.dp-t').textContent = m ? `Sentence ${ps.index + 1} of ${ps.sentences.length}` : ps.chapter.title;
      bottom.querySelector('.dp-s').textContent = `${ps.buffering ? 'Loading voice…' : fmt.dur(ps.remainingSec) + ' left'}${m ? '' : ' · ' + A.short(ps.story.title)}`;
      bottom.querySelector('.dp-rate').textContent = ps.rate + '×';
      bottom.querySelector('.dp-pp').innerHTML = ps.buffering ? '<span class="spin"></span>' : A.ic(ps.playing ? 'pause' : 'play_arrow', 'f');
      bottom.querySelector('.dp-bar i').style.width = ps.progress * 100 + '%';
    }

    // ---------- highlight ----------
    let curIdx = -1;
    function highlight(ps) {
      const m = mine(ps);
      sc.classList.toggle('tts', m);
      if (!m) { if (curIdx >= 0 && spans[curIdx]) spans[curIdx].classList.remove('cur'); curIdx = -1; follow.hidden = true; return; }
      if (ps.index === curIdx) return;
      if (curIdx >= 0 && spans[curIdx]) spans[curIdx].classList.remove('cur');
      spans.forEach((sp, i) => sp.classList.toggle('done', i < ps.index));
      curIdx = ps.index;
      const sp = spans[curIdx]; if (!sp) return;
      sp.classList.add('cur');
      const userBusy = Date.now() - userScrollAt < 4000;
      if (userBusy) { follow.hidden = false; return; }
      follow.hidden = true;
      if (!el.isConnected) setTimeout(() => centerOn(sp, true), 80); else centerOn(sp);
    }
    function centerOn(sp, instant) {
      const r = sp.getBoundingClientRect(), sr = sc.getBoundingClientRect();
      const target = sc.scrollTop + r.top - sr.top - sc.clientHeight * 0.42 + r.height / 2;
      autoScrolling = true; sc.scrollTo({ top: Math.max(0, target), behavior: instant ? 'auto' : 'smooth' });
      setTimeout(() => { autoScrolling = false; }, 700);
    }
    follow.addEventListener('click', () => { userScrollAt = 0; follow.hidden = true; if (spans[curIdx]) centerOn(spans[curIdx]); });

    const onPs = (ps) => { renderBottom(ps); highlight(ps); };
    ctx.on(W.player.on(onPs));
    if (ctx.query.play) {
      const ses = W.session && W.session.storyId === s.id && W.session.chapterId === c.id ? W.session.index : 0;
      W.player.load(s.id, c.id, { index: +ctx.query.at || ses, play: true });
    } else onPs(W.player.state());
    if (ctx.query.settings) setTimeout(() => settings(), 60);

    const startAt = (i) => { W.player.load(s.id, c.id, { index: i, play: true }); userScrollAt = 0; };
    let tapP = null, tapT = 0;
    el.addEventListener('click', (e) => {
      const t = e.target;
      const p = t.closest('.rd-text p');
      if (p) {
        const now = Date.now();
        if (tapP === p && now - tapT < 340) { const first = p.querySelector('.s'); if (first) { startAt(+first.dataset.s); A.toast('Reading aloud from here', { icon: 'record_voice_over' }); } tapP = null; return; }
        tapP = p; tapT = now; return;
      }
      const ch = t.closest('[data-ch]'); if (ch && ch.dataset.ch) { A.replace(`/reader/${s.id}/${ch.dataset.ch}`); return; }
      if (t.closest('[data-chlist]')) { A.back('/story/' + s.id); return; }
      if (t.closest('[data-listenhere]')) { startAt(0); return; }
      if (t.closest('[data-pp]')) { W.player.toggle(); return; }
      const sk = t.closest('[data-sk]'); if (sk) { W.player.skip(+sk.dataset.sk); return; }
      if (t.closest('[data-pch]')) { W.player.prevChapter(); followPlayer(); return; }
      if (t.closest('[data-nch]')) { W.player.nextChapter(); followPlayer(); return; }
      if (t.closest('[data-rate]')) { W.player.cycleRate(); return; }
      if (t.closest('[data-stop]')) { W.player.stop(); A.toast('Stopped reading aloud', { icon: 'stop_circle' }); return; }
      if (t.closest('[data-bmk]')) {
        const b = t.closest('[data-bmk]'); s.bookmark = s.bookmark === ci ? null : ci; const on = s.bookmark === ci;
        b.classList.toggle('on', on); b.innerHTML = A.ic(on ? 'bookmark' : 'bookmark_add', on ? 'f' : ''); A.toast(on ? `Bookmarked chapter ${ci + 1}` : 'Bookmark removed', { icon: 'bookmark' }); return;
      }
      if (t.closest('[data-aloud]')) { aloud(); return; }
      if (t.closest('[data-rset]')) settings();
    });
    function followPlayer() { setTimeout(() => { const ps = W.player.state(); if (ps.loaded && ps.story.id === s.id && ps.chapter.id !== c.id) A.replace(`/reader/${s.id}/${ps.chapter.id}`); }, 30); }
    // when the voice moves to the next chapter on its own, follow it
    ctx.on(W.player.on((ps) => { if (ps.loaded && ps.story.id === s.id && ps.chapter.id !== c.id && curIdx >= 0 && ps.playing) A.replace(`/reader/${s.id}/${ps.chapter.id}`); }));

    function aloud() {
      const ss = W.session;
      const items = [];
      if (ss && !(ss.storyId === s.id && ss.chapterId === c.id && W.player.state().loaded)) items.push({ icon: 'history', label: 'Continue listening', sub: `${A.short(ss.storyTitle)} · ${ss.chapterTitle} (sentence ${ss.index + 1})`, fn: () => { W.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); if (ss.storyId === s.id && ss.chapterId !== c.id) A.replace(`/reader/${s.id}/${ss.chapterId}`); } });
      items.push({ icon: 'play_circle', label: 'Listen from this chapter', sub: c.title, fn: () => startAt(0) });
      const vis = spans.find((sp) => sp.getBoundingClientRect().top > 90);
      if (vis) items.push({ icon: 'subject', label: 'Listen from where I am', sub: vis.textContent.slice(0, 60) + '…', fn: () => startAt(+vis.dataset.s) });
      A.menu({ title: 'Read aloud', sub: 'Voice: Bella · Kokoro', items, tint: s });
    }
    function settings() {
      const txt = el.querySelector('.rd-text');
      const html = () => `<div style="padding:0 8px 8px;display:grid;gap:18px">
        <div class="fsz"><button class="ibtn tonal" data-fs="-1" aria-label="Smaller text">${A.ic('text_decrease')}</button><b>${Math.round(R.scale * 100)}%</b><button class="ibtn tonal" data-fs="1" aria-label="Larger text">${A.ic('text_increase')}</button></div>
        <div><div class="set-lbl"><b>Line spacing</b><span class="v">${R.spacing.toFixed(1)}</span></div>${A.range(R.spacing, 1.3, 2.2, 0.1, 'data-ls')}</div>
        <div class="set-row"><span><b>Dark reader</b><small>${R.black ? 'True black page for OLED' : 'Page tinted by the cover'}</small></span>${A.sw(R.black, 'data-black')}</div>
        <div class="group"><button class="row" data-voice>${A.ic('record_voice_over')}<span class="rt"><b>Voice settings</b><small>${esc(W.settings.tts.voiceLabel)} · ${W.player.state().rate}×</small></span>${A.ic('chevron_right', 'chev')}</button>
          <button class="row" data-copy>${A.ic('content_copy')}<span class="rt"><b>Copy chapter text</b><small>${words.toLocaleString('en-US')} words</small></span></button></div></div>`;
      const sh = A.sheet({ title: 'Reader settings', html: html(), tint: s });
      sh.el.addEventListener('click', (e) => {
        const f = e.target.closest('[data-fs]');
        if (f) { R.scale = Math.max(0.8, Math.min(1.6, Math.round((R.scale + f.dataset.fs * 0.1) * 10) / 10)); W.settings.reader.fontScale = R.scale; txt.style.setProperty('--fs', R.scale); sh.el.querySelector('.fsz b').textContent = Math.round(R.scale * 100) + '%'; return; }
        if (e.target.closest('[data-voice]')) { sh.close(() => A.go('/settings/tts')); return; }
        if (e.target.closest('[data-copy]')) { sh.close(() => A.toast('Chapter text copied', { icon: 'content_copy' })); }
      });
      sh.el.addEventListener('input', (e) => { if (e.target.matches('[data-ls]')) { R.spacing = +e.target.value; txt.style.setProperty('--lh', R.spacing); sh.el.querySelector('.v').textContent = R.spacing.toFixed(1); } });
      sh.el.addEventListener('sw', (e) => { R.black = e.detail; sc.classList.toggle('black', R.black); e.target.closest('.set-row').querySelector('small').textContent = R.black ? 'True black page for OLED' : 'Page tinted by the cover'; });
    }
  };
})();
