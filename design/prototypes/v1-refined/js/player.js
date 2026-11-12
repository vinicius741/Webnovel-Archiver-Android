/* v1 Refined — Now Playing. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;

  const sleepLabel = (ps) => (ps.sleep === 'chapter' ? 'End of chapter' : ps.sleep ? `${ps.sleepLeftMin} min left` : 'Timer off');

  V.screens.player = (r) => {
    if (r.query.play === '1' && !WNA.player.state().loaded && WNA.session) { const ss = WNA.session; WNA.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); }
    const el = WNA.el(`<div class="screen np">${V.appBar({ title: 'Now Playing', subtitle: 'Voice: Bella · Kokoro', actions: V.ib('graphic_eq', 'audio', 'Chapter audio') })}<div class="np-body"></div></div>`);
    const $ = (q) => el.querySelector(q);
    let seeking = false, built = null;

    function empty() {
      const ss = WNA.session;
      $('.np-body').innerHTML = V.empty({
        icon: 'headphones', title: 'Nothing is playing',
        body: ss ? `Pick up where you left off in ${ss.storyTitle}.` : 'Open a chapter and tap the headphones to listen.',
        action: ss ? V.btn(`Resume ${V.short(ss.chapterTitle)}`, 'resume', { kind: 'filled', icon: 'play_arrow' }) : '',
      });
      built = null;
    }

    function build(ps) {
      const s = ps.story;
      built = s.id + '|' + ps.chapter.id;
      $('.np-body').innerHTML = `
        <div class="np-grid">
          <div class="np-art">${V.cover(s, 200)}</div>
          <div class="np-ctl">
            <div class="np-meta">
              <button class="np-story" data-act="story">${esc(s.title)}</button>
              <div class="np-ch">${esc(ps.chapter.title)}</div>
              <div class="np-n tnum">Chapter ${ps.chapterIndex + 1} of ${ps.chapterCount}</div>
            </div>
            <button class="np-quote" data-act="along" aria-label="Read along"><span class="lbl">${V.ic('graphic_eq', 'xs')}Now reading</span><span class="q"></span></button>
            <div class="np-seek">
              <input class="slider seek" type="range" min="0" max="${Math.max(1, ps.sentences.length - 1)}" step="1" value="${ps.index}" aria-label="Seek">
              <div class="times tnum"><span class="el"></span><span class="rm"></span></div>
            </div>
            <div class="np-trans">
              ${V.ib('skip_previous', 'prevch', 'Previous chapter')}
              <button class="ib skipn" data-act="back15" aria-label="Back 15 seconds">${V.ic('replay')}<small>15</small></button>
              <button class="np-play" data-act="toggle" aria-label="Play or pause"></button>
              <button class="ib skipn fwd" data-act="fwd30" aria-label="Forward 30 seconds">${V.ic('replay')}<small>30</small></button>
              ${V.ib('skip_next', 'nextch', 'Next chapter')}
            </div>
            <div class="np-chips"></div>
            ${V.btn('Read along', 'along', { kind: 'tonal', icon: 'chrome_reader_mode', cls: 'np-along' })}
          </div>
        </div>`;
      const seek = $('.seek');
      seek.addEventListener('input', () => { seeking = true; V.syncSlider(seek); });
      seek.addEventListener('change', () => { WNA.player.seekSentence(+seek.value); seeking = false; });
    }

    function update(ps) {
      if (!ps.loaded) { empty(); return; }
      if (built !== ps.story.id + '|' + ps.chapter.id) build(ps);
      const q = ps.sentences[ps.index];
      $('.np-quote .q').textContent = q ? q.text : '';
      const seek = $('.seek');
      if (!seeking) { seek.max = Math.max(1, ps.sentences.length - 1); seek.value = ps.index; V.syncSlider(seek); }
      $('.times .el').textContent = fmt.clock(ps.elapsedSec / ps.rate);
      $('.times .rm').textContent = '−' + fmt.clock(ps.remainingSec);
      $('.np-play').innerHTML = ps.buffering ? '<span class="spin" style="width:28px;height:28px;border-width:3px"></span>' : V.ic(ps.playing ? 'pause' : 'play_arrow', 'fill');
      $('.np-play').setAttribute('aria-label', ps.playing ? 'Pause' : 'Play');
      $('.np-chips').innerHTML = `
        <button class="chip on" data-act="rate">${V.ic('speed')}${ps.rate}×</button>
        <button class="chip ${ps.sleep ? 'on' : ''}" data-act="sleep">${V.ic('bedtime')}${sleepLabel(ps)}</button>
        <button class="chip" data-act="history">${V.ic('history')}History</button>
        <button class="chip" data-act="stop">${V.ic('stop')}Stop</button>`;
      $('.np-art .cov').classList.toggle('playing', ps.playing);
    }

    function historyList() {
      const ps = WNA.player.state();
      const h = ps.history.slice();
      const seed = (WNA.data.session && WNA.data.session.history) || [];
      seed.forEach((x) => { if (!h.some((y) => y.chapterId === x.chapterId)) h.push({ storyId: WNA.data.session.storyId, chapterId: x.chapterId, index: x.chunkIndex, at: x.recordedAt, excerpt: x.anchorText }); });
      return h;
    }

    V.bind(el, {
      audio() { const ps = WNA.player.state(); WNA.go('/narration' + (ps.loaded ? '/' + ps.story.id : '')); },
      resume() { const ss = WNA.session; WNA.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); },
      story() { WNA.go('/story/' + WNA.player.state().story.id); },
      toggle: () => WNA.player.toggle(),
      back15: () => WNA.player.skip(-15),
      fwd30: () => WNA.player.skip(30),
      prevch: () => WNA.player.prevChapter(),
      nextch: () => WNA.player.nextChapter(),
      rate: () => WNA.player.cycleRate(),
      along() { const ps = WNA.player.state(); WNA.go(`/reader/${ps.story.id}/${encodeURIComponent(ps.chapter.id)}`); },
      stop() { WNA.player.stop(); V.toast('Playback stopped · position saved'); WNA.back(); },
      sleep() {
        const ps = WNA.player.state();
        const opts = [['off', 'Off'], ['15', '15 minutes'], ['30', '30 minutes'], ['45', '45 minutes'], ['60', '60 minutes'], ['chapter', 'End of chapter']];
        const cur = ps.sleep == null ? 'off' : String(ps.sleep);
        V.menu({ title: 'Sleep timer', items: opts.map(([k, l]) => ({ act: k, label: l, icon: k === 'off' ? 'timer_off' : k === 'chapter' ? 'last_page' : 'timer', on: cur === k })), onPick(k) { WNA.player.setSleep(k === 'off' ? null : k === 'chapter' ? 'chapter' : +k); V.toast(k === 'off' ? 'Sleep timer off' : k === 'chapter' ? 'Stopping at the end of this chapter' : `Stopping in ${k} minutes`); } });
      },
      history() {
        const h = historyList();
        if (!h.length) { V.toast('No history yet'); return; }
        V.sheet({
          title: 'Go back to', sub: 'Places you listened from recently',
          body: h.map((x, i) => { const s = WNA.story(x.storyId); const c = WNA.chapter(x.storyId, x.chapterId); return `<button class="mi" data-h="${i}" style="min-height:68px">${V.ic('history')}<span class="grow"><span class="ell" style="display:block">${esc(c.title)}</span><span class="mi-s clamp2">${esc(x.excerpt || '')}</span><span class="mi-s">${esc(V.short(s.title))} · ${fmt.ago(x.at)}</span></span></button>`; }).join(''),
          onMount(sh, close) { WNA.on(sh, 'click', '[data-h]', (e, t) => { WNA.player.restore(h[+t.dataset.h]); close(); }); },
        });
      },
    });

    const off = WNA.player.on(update);
    update(WNA.player.state());
    return { el, title: 'Now Playing', destroy: off, noMini: true };
  };
})();
