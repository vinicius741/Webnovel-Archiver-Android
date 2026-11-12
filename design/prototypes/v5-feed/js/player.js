/* v5-feed Now Playing: full-screen ambient player with a karaoke-style live transcript. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  let lyricsMode = false;
  const sentSec = (t) => t.split(/\s+/).length * 0.36 + 0.3;

  A.screens.player = (ctx) => {
    let ps = W.player.state();
    if (!ps.loaded) {
      const ss = W.session;
      if (ss) W.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: !!ctx.query.play });
      else { const s0 = A.stories()[0]; W.player.load(s0.id, null, { play: false }); }
      ps = W.player.state();
    } else if (ctx.query.play && !ps.playing) W.player.play();
    if (ctx.query.lyrics) lyricsMode = ctx.query.lyrics === '1';
    let storyId = null;
    ctx.el.innerHTML = `<div class="pl-bg"><div class="pl-amb"></div><i class="blob b1"></i><i class="blob b2"></i><i class="blob b3"></i><div class="pl-vig"></div></div>
      <div class="pl ${lyricsMode ? 'lyrics' : ''}">
        <header class="pl-tb"><button class="ibtn" data-back="/home" aria-label="Close player">${A.ic('keyboard_arrow_down')}</button>
          <div class="pl-tb-t"><b>Now Playing</b><small class="pl-voice"></small></div>
          <button class="ibtn" data-chaudio aria-label="Chapter audio">${A.ic('graphic_eq')}</button></header>
        <div class="pl-main">
          <div class="pl-artrow"><button class="pl-art" data-art></button>
            <div class="pl-meta"><button class="pl-story" data-storylink></button><h1 class="pl-ch"></h1><p class="pl-chn"></p></div></div>
          <div class="pl-lyr" data-lyr><div class="pl-lyr-in"></div></div>
        </div>
        <div class="pl-ctl">
          <div class="pl-seek"><input type="range" class="rng pl-rng" min="0" max="1000" value="0" aria-label="Seek"><div class="pl-times"><span class="pl-el"></span><span class="pl-sn"></span><span class="pl-rem"></span></div></div>
          <div class="pl-tr"><button class="ibtn" data-pch aria-label="Previous chapter">${A.ic('skip_previous', 'f')}</button><button class="ibtn" data-sk="-15" aria-label="Back 15 seconds">${A.ic('replay_10')}</button>
            <button class="pl-pp" data-pp aria-label="Play or pause"></button>
            <button class="ibtn" data-sk="30" aria-label="Forward 30 seconds">${A.ic('forward_30')}</button><button class="ibtn" data-nch aria-label="Next chapter">${A.ic('skip_next', 'f')}</button></div>
          <div class="pl-chips"><button class="chip glassy" data-rate></button><button class="chip glassy" data-sleep></button><button class="chip glassy" data-hist>${A.ic('history')}History</button><button class="chip glassy icon" data-stop aria-label="Stop">${A.ic('stop', 'f')}</button></div>
          <div class="pl-foot"><button class="btn glassy" data-lyrics>${A.ic('subject')}<span class="lbl"></span></button><button class="btn primary" data-readalong>${A.ic('chrome_reader_mode')}Read along</button></div>
        </div></div>`;
    const el = ctx.el, root = el.querySelector('.pl'), lyr = el.querySelector('.pl-lyr-in'), rng = el.querySelector('.pl-rng');
    let seeking = false, lastIdx = -1, lastChapter = '';

    function setStory(p) {
      storyId = p.story.id;
      A.tint(el, p.story);
      const u = W.coverUrl(p.story);
      el.querySelector('.pl-amb').innerHTML = u ? `<img src="${u}" alt="">` : `<div style="position:absolute;inset:0;background:${W.coverFallback(p.story)}"></div>`;
      el.querySelector('.pl-art').innerHTML = A.cover(p.story, '', true);
      el.querySelector('.pl-story').innerHTML = `${esc(A.short(p.story.title))}${A.ic('chevron_right')}`;
    }
    function lyricsHtml(p) {
      const from = lyricsMode ? 0 : Math.max(0, p.index - 1), to = lyricsMode ? p.sentences.length : Math.min(p.sentences.length, p.index + 3);
      let out = '', lastP = null;
      for (let i = from; i < to; i++) {
        const x = p.sentences[i];
        if (lyricsMode && lastP != null && x.p !== lastP) out += '<i class="pbr"></i>';
        lastP = x.p;
        out += `<span class="ly ${i < p.index ? 'past' : i === p.index ? 'now' : 'next'}" data-i="${i}">${esc(x.text)}</span> `;
      }
      return out;
    }
    function karaoke(p) {
      const now = lyr.querySelector('.ly.now'); if (!now) return;
      const dur = Math.max(0.9, sentSec(p.sentences[p.index].text) / p.rate);
      now.style.animationDuration = dur + 's';
      now.style.animationPlayState = p.playing ? 'running' : 'paused';
    }
    function render(p) {
      if (!p.loaded) return;
      if (p.story.id !== storyId) setStory(p);
      const ck = p.story.id + p.chapter.id;
      el.querySelector('.pl-voice').textContent = `Voice: ${p.voice}`;
      el.querySelector('.pl-ch').textContent = p.chapter.title;
      el.querySelector('.pl-chn').textContent = `Chapter ${p.chapterIndex + 1} / ${p.chapterCount}`;
      root.classList.toggle('playing', p.playing);
      el.querySelector('[data-pp]').innerHTML = p.buffering ? '<span class="spin"></span>' : A.ic(p.playing ? 'pause' : 'play_arrow', 'f');
      el.querySelector('[data-rate]').innerHTML = `${A.ic('speed')}${p.rate}×`;
      el.querySelector('[data-sleep]').innerHTML = `${A.ic('bedtime')}${p.sleep === 'chapter' ? 'End of chapter' : p.sleep ? `${p.sleepLeftMin} min` : 'Timer: Off'}`;
      el.querySelector('[data-sleep]').classList.toggle('on', !!p.sleep);
      el.querySelector('[data-lyrics] .lbl').textContent = lyricsMode ? 'Artwork' : 'Transcript';
      if (!seeking) {
        rng.value = Math.round(p.progress * 1000); rng.style.setProperty('--p', p.progress * 100 + '%');
        el.querySelector('.pl-el').textContent = fmt.clock(p.elapsedSec / p.rate);
        el.querySelector('.pl-rem').textContent = '-' + fmt.clock(p.remainingSec);
        el.querySelector('.pl-sn').textContent = `${p.index + 1} / ${p.sentences.length}`;
      }
      if (ck !== lastChapter || p.index !== lastIdx || lyricsMode) {
        if (lyricsMode && ck === lastChapter && lyr.children.length) {
          lyr.querySelectorAll('.ly').forEach((n) => { const i = +n.dataset.i; n.className = 'ly ' + (i < p.index ? 'past' : i === p.index ? 'now' : 'next'); });
        } else lyr.innerHTML = lyricsHtml(p);
        const now = lyr.querySelector('.ly.now');
        if (now) { now.style.animation = 'none'; void now.offsetWidth; now.style.animation = ''; }
        if (lyricsMode && now) { const smooth = ck === lastChapter && el.isConnected; const go = () => { const box = el.querySelector('.pl-lyr'); box.scrollTo({ top: now.offsetTop - box.clientHeight * 0.35, behavior: smooth ? 'smooth' : 'auto' }); }; if (el.isConnected) go(); else setTimeout(go, 60); }
        lastChapter = ck; lastIdx = p.index;
      }
      karaoke(p);
    }
    render(W.player.state());
    ctx.on(W.player.on((p) => { if (!p.loaded) { A.back('/home'); return; } render(p); }));
    const tick = setInterval(() => { const p = W.player.state(); if (p.sleep && typeof p.sleep === 'number') render(p); }, 15000);
    ctx.on(() => clearInterval(tick));

    rng.addEventListener('input', () => {
      seeking = true; const p = W.player.state(); const f = rng.value / 1000;
      const i = Math.round(f * (p.sentences.length - 1));
      el.querySelector('.pl-sn').textContent = `${i + 1} / ${p.sentences.length}`;
      el.querySelector('.pl-el').textContent = fmt.clock((p.totalSec * f) / p.rate);
    });
    rng.addEventListener('change', () => { seeking = false; W.player.seekFraction(rng.value / 1000); });

    el.addEventListener('click', (e) => {
      const t = e.target, p = W.player.state();
      if (t.closest('[data-pp]')) return W.player.toggle();
      const sk = t.closest('[data-sk]'); if (sk) return W.player.skip(+sk.dataset.sk);
      if (t.closest('[data-pch]')) return W.player.prevChapter();
      if (t.closest('[data-nch]')) return W.player.nextChapter();
      if (t.closest('[data-rate]')) { W.player.cycleRate(); A.toast(`Speed ${W.player.state().rate}×`, { icon: 'speed' }); return; }
      if (t.closest('[data-sleep]')) return sleepSheet(p);
      if (t.closest('[data-hist]')) return historySheet(p);
      if (t.closest('[data-stop]')) { W.player.stop(); A.toast('Playback stopped', { icon: 'stop_circle' }); return; }
      if (t.closest('[data-readalong]')) return A.go(`/reader/${p.story.id}/${p.chapter.id}`);
      if (t.closest('[data-chaudio]')) return A.go(`/narration/${p.story.id}`);
      if (t.closest('[data-storylink]') || t.closest('[data-art]')) { if (lyricsMode && t.closest('[data-art]')) { toggleLyrics(); return; } return A.go(`/story/${p.story.id}`); }
      const ly = t.closest('.ly'); if (ly && lyricsMode) { W.player.seekSentence(+ly.dataset.i); if (!p.playing) W.player.play(); return; }
      if (t.closest('[data-lyrics]') || (t.closest('[data-lyr]') && !lyricsMode)) toggleLyrics();
    });
    function toggleLyrics() { lyricsMode = !lyricsMode; root.classList.toggle('lyrics', lyricsMode); lastChapter = ''; render(W.player.state()); }
  };

  function sleepSheet(p) {
    const opts = [[null, 'Off'], [15, '15 minutes'], [30, '30 minutes'], [45, '45 minutes'], [60, '60 minutes'], ['chapter', 'End of chapter']];
    A.menu({ title: 'Sleep timer', sub: p.sleep ? `On · ${p.sleep === 'chapter' ? 'stops at the end of this chapter' : p.sleepLeftMin + ' min left'}` : 'Stop playback after…', tint: p.story,
      items: opts.map(([v, l]) => ({ icon: v == null ? 'timer_off' : v === 'chapter' ? 'last_page' : 'bedtime', label: l, on: p.sleep === v, fn: () => { W.player.setSleep(v); A.toast(v == null ? 'Sleep timer off' : `Sleep timer: ${l}`, { icon: 'bedtime' }); } })) });
  }
  function historySheet(p) {
    const h = p.history;
    if (!h.length) { A.sheet({ title: 'Go back to', html: A.empty('history', 'No history yet', 'Places you leave by changing chapters or stories show up here.'), tint: p.story }); return; }
    A.menu({ title: 'Go back to', sub: 'Recent listening positions', tint: p.story,
      items: h.map((x) => { const s = W.story(x.storyId), c = W.chapter(x.storyId, x.chapterId); return { icon: 'history', label: `${A.short(s.title)} · ${c.title}`, sub: `${fmt.ago(x.at)} · “${(x.excerpt || '').slice(0, 70)}…”`, fn: () => W.player.restore(x) }; }) });
  }
})();
