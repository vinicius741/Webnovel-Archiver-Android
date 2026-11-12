/* v3 Restructured — Listen hub (now playing + generation queue + generated audio + setup) and the Now Playing sheet. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;

  // ---------- Now Playing (expanding sheet, route #/player) ----------
  const np = document.getElementById('np');
  let npOpen = false;
  let npSid = null;
  function npShell(st) {
    const s = st.story;
    const url = W.coverUrl(s);
    np.innerHTML = `<div class="np-bg" style="${url ? `background-image:url('${url}')` : `background:${W.coverFallback(s)}`}"></div>
      <div class="np-inner">
        <header class="np-head"><button class="ib" data-n="close" aria-label="Collapse">${A.icon('keyboard_arrow_down', 'lg')}</button><div class="grow" style="text-align:center"><b>Now Playing</b><div class="muted" style="font-size:12px">Voice: ${esc(st.voice)}</div></div><button class="ib" data-n="audio" aria-label="Chapter audio">${A.icon('graphic_eq')}</button></header>
        <div class="np-body">
          <div class="np-art"><button data-n="story" aria-label="Open story">${A.coverHtml(s)}</button></div>
          <div class="np-ctl">
            <div class="np-titles"><div class="np-ch" data-f="ch"></div><button class="np-st" data-n="story">${esc(s.title)}</button><div class="muted" data-f="pos" style="font-size:12.5px"></div></div>
            <button class="np-caption" data-n="read"><span class="eyebrow">${A.icon('subtitles', 'xs')} Now reading</span><span data-f="cap"></span></button>
            <div class="np-seek"><input type="range" min="0" max="1" step="1" value="0" data-f="seek" aria-label="Seek"><div class="row muted" style="font-size:12px;justify-content:space-between"><span data-f="el"></span><span data-f="rem"></span></div></div>
            <div class="np-transport">
              <button class="ib" data-n="prev" aria-label="Previous chapter">${A.icon('skip_previous', 'f lg')}</button>
              <button class="ib" data-n="b15" aria-label="Back 15 seconds">${A.icon('replay', 'lg')}<i class="sk">15</i></button>
              <button class="np-play" data-n="toggle" aria-label="Play or pause"></button>
              <button class="ib" data-n="f30" aria-label="Forward 30 seconds">${A.icon('forward_30', 'lg')}</button>
              <button class="ib" data-n="next" aria-label="Next chapter">${A.icon('skip_next', 'f lg')}</button>
            </div>
            <div class="chips np-chips"><button class="chip" data-n="rate">${A.icon('speed')}<span data-f="rate"></span></button><button class="chip" data-n="sleep">${A.icon('bedtime')}<span data-f="sleep"></span></button><button class="chip" data-n="hist">${A.icon('history')}History</button><button class="chip" data-n="stop">${A.icon('stop')}Stop</button></div>
            <button class="btn tonal block" data-n="read" style="margin-top:16px">${A.icon('chrome_reader_mode')}Read along</button>
          </div>
        </div>
      </div>`;
    const seek = np.querySelector('[data-f=seek]');
    seek.addEventListener('input', () => { A.rangeFill(seek); });
    seek.addEventListener('change', () => W.player.seekSentence(+seek.value));
  }
  function npEmpty() {
    const sess = W.session;
    const s = sess && W.story(sess.storyId);
    np.innerHTML = `<div class="np-inner"><header class="np-head"><button class="ib" data-n="close" aria-label="Close">${A.icon('keyboard_arrow_down', 'lg')}</button><div class="grow" style="text-align:center"><b>Now Playing</b></div><span style="width:48px"></span></header>
      <div class="empty" style="padding-top:18vh">${A.icon('headphones')}<h3>Nothing playing</h3><p>${s ? `Pick up where you left off in ${esc(A.short(s.title, 30))}.` : 'Open a chapter and tap the headphones to listen.'}</p>${s ? `<button class="btn fill" data-n="resume">${A.icon('play_arrow', 'f')}Continue · ${esc(sess.chapterTitle)}</button>` : ''}</div></div>`;
    npSid = null;
  }
  function npPaint() {
    if (!npOpen) return;
    const st = W.player.state();
    if (!st.loaded) { if (npSid !== null || !np.querySelector('.empty')) npEmpty(); return; }
    if (npSid !== st.story.id || !np.querySelector('.np-body')) { npShell(st); npSid = st.story.id; }
    const q = (f) => np.querySelector(`[data-f=${f}]`);
    q('ch').textContent = st.chapter.title;
    q('pos').textContent = `Chapter ${st.chapterIndex + 1} / ${st.chapterCount}`;
    q('cap').textContent = (st.sentences[st.index] || {}).text || '';
    const seek = q('seek');
    seek.max = Math.max(1, st.sentences.length - 1);
    if (document.activeElement !== seek) seek.value = st.index;
    A.rangeFill(seek);
    q('el').textContent = fmt.clock(st.elapsedSec / st.rate);
    q('rem').textContent = '-' + fmt.clock(st.remainingSec);
    q('rate').textContent = st.rate + '×';
    q('sleep').textContent = st.sleep === 'chapter' ? 'End of chapter' : st.sleep ? `${st.sleepLeftMin} min left` : 'Timer: Off';
    np.querySelector('[data-n=sleep]').classList.toggle('on', !!st.sleep);
    np.querySelector('[data-n=toggle]').innerHTML = st.buffering ? '<span class="spinner" style="width:28px;height:28px;border-width:3px"></span>' : A.icon(st.playing ? 'pause' : 'play_arrow', 'f');
  }
  A.np = {
    isOpen: () => npOpen,
    open(r) {
      if (r && r.query.play && !W.player.state().loaded && W.session) { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); }
      npOpen = true; npSid = -1; np.classList.add('open'); np.setAttribute('aria-hidden', 'false'); npPaint(); A.renderDock && A.renderDock();
    },
    close() { npOpen = false; np.classList.remove('open'); np.setAttribute('aria-hidden', 'true'); A.renderDock && A.renderDock(); },
  };
  W.player.on(npPaint);
  W.on(np, 'click', '[data-n]', (e, b) => {
    const st = W.player.state(); const a = b.dataset.n;
    if (a === 'close') W.back('/narration');
    else if (a === 'toggle') W.player.toggle();
    else if (a === 'prev') W.player.prevChapter();
    else if (a === 'next') W.player.nextChapter();
    else if (a === 'b15') W.player.skip(-15);
    else if (a === 'f30') W.player.skip(30);
    else if (a === 'rate') W.player.cycleRate();
    else if (a === 'stop') { W.player.stop(); A.toast('Stopped'); }
    else if (a === 'resume') { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); }
    else if (a === 'audio') W.go('/narration/' + st.story.id);
    else if (a === 'story') W.go('/story/' + st.story.id);
    else if (a === 'read') W.go(`/reader/${st.story.id}/${st.chapter.id}`);
    else if (a === 'sleep') {
      const opts = [['Off', null], ['15 minutes', 15], ['30 minutes', 30], ['45 minutes', 45], ['60 minutes', 60], ['End of chapter', 'chapter']];
      A.menu({ title: 'Sleep timer', sections: [{ items: opts.map(([l, v]) => ({ icon: v === 'chapter' ? 'last_page' : v ? 'timer' : 'timer_off', label: l, on: st.sleep === v, fn: () => { W.player.setSleep(v); A.toast(v ? `Sleep timer: ${l}` : 'Sleep timer off'); } })) }] });
    } else if (a === 'hist') {
      const h = st.history;
      A.menu({ title: 'Go back to', sub: h.length ? 'Recent listening positions' : 'Nothing yet. Positions appear when you change chapters.', sections: [{ items: h.map((x) => ({ icon: 'history', label: W.chapter(x.storyId, x.chapterId).title, sub: `${fmt.ago(x.at)} · “${x.excerpt.slice(0, 60)}…”`, fn: () => W.player.restore(x) })) }] });
    }
  });
  // swipe the header/cover down to collapse
  let y0 = null;
  np.addEventListener('pointerdown', (e) => { if (e.target.closest('.np-head,.np-art')) y0 = e.clientY; });
  np.addEventListener('pointerup', (e) => { if (y0 != null && e.clientY - y0 > 70) W.back('/narration'); y0 = null; });

  // ---------- Listen hub ----------
  A.screens.listen = {
    persistent: true, navId: 'listen', hideResume: true,
    render(route) {
      const el = A.view('listenv');
      let seg = route.query.seg || 'queue';
      el.innerHTML = `${A.appbar({ title: 'Listen', sub: 'Player and audio generation', big: true, actions: A.ab('record_voice_over', 'voice', 'Voice & Speech') + A.ab('more_vert', 'qmenu', 'Queue options') })}
        <div class="pad wrap"><div data-r="now"></div>
          <div class="seg listen-seg" data-r="seg"></div>
          <div class="lgrid" data-seg="${seg}"><div class="lsec s-queue" data-r="queue"></div><div class="lsec s-gen" data-r="gen"></div><div class="lsec s-setup" data-r="setup"></div></div></div>`;
      const $ = (k) => el.querySelector(`[data-r=${k}]`);

      function now() {
        const st = W.player.state();
        const sess = W.session;
        let h;
        if (st.loaded) {
          h = `<div class="nowcard"><button class="row grow" data-l="player" style="text-align:left;min-width:0">${A.coverHtml(st.story)}<div class="grow"><div class="eyebrow">${st.playing ? 'Playing now' : 'Paused'}</div><div class="t nowrap">${esc(st.chapter.title)}</div><div class="muted nowrap" style="font-size:12.5px">${esc(st.story.title)}</div>${A.prog(st.progress)}<div class="muted" style="font-size:12px;margin-top:6px">${fmt.dur(st.remainingSec)} left · ${st.rate}× · ${esc(st.voice)}</div></div></button>
            <button class="np-play sm" data-l="toggle" aria-label="Play or pause">${A.icon(st.playing ? 'pause' : 'play_arrow', 'f')}</button></div>`;
        } else if (sess) {
          const s = W.story(sess.storyId);
          h = `<div class="nowcard resume"><button class="row grow" data-l="resume" style="text-align:left;min-width:0">${A.coverHtml(s)}<div class="grow"><div class="eyebrow">Continue listening</div><div class="t nowrap">${esc(sess.chapterTitle)}</div><div class="muted nowrap" style="font-size:12.5px">${esc(s.title)} · sentence ${sess.index + 1}</div></div></button><button class="np-play sm" data-l="resume" aria-label="Resume">${A.icon('play_arrow', 'f')}</button></div>`;
        } else h = `<div class="card pad16 muted">Nothing playing. Open a chapter and tap the headphones.</div>`;
        $('now').innerHTML = h;
      }
      function segs() {
        const n = W.narration.state();
        $('seg').innerHTML = [['queue', 'Queue', n.queuedCount], ['gen', 'Generated', n.generated.length], ['setup', 'Setup', '']].map(([k, l, c]) => `<button data-seg="${k}" class="${seg === k ? 'on' : ''}">${l}${c ? ` <span class="faint">${c}</span>` : ''}</button>`).join('');
        el.querySelector('.lgrid').dataset.seg = seg;
      }
      function queue() {
        const n = W.narration.state();
        const cur = n.current;
        const banner = cur ? `<div class="card copper pad16 genbanner"><div class="row"><span class="${n.paused ? '' : 'spinner'}" style="color:var(--copper)">${n.paused ? A.icon('pause_circle') : ''}</span><b class="grow">${n.paused ? 'Paused' : 'Generating'}${n.targetComputer ? ` on ${esc(n.targetComputer.name)}` : ''} · ${Math.round(cur.progress * 100)}%</b><button class="btn sm surface" data-l="pause">${n.paused ? A.icon('play_arrow', 'sm') + 'Resume' : A.icon('pause', 'sm') + 'Pause'}</button></div>
            <button class="nowrap gb-title" data-story="${cur.storyId}">${esc(cur.title)} · <span class="muted">${esc(A.short(cur.story.title, 30))}</span></button>${A.prog(cur.progress, 'copper thick')}
            <div class="muted" style="font-size:12.5px;margin-top:8px">${cur.sentence} of ${cur.sentences} sentences · ${cur.speed} · ~${cur.etaMin} min left · ${Math.max(0, n.queuedCount - 1)} chapters after this</div></div>`
          : `<div class="card pad16 idle">${A.icon('check_circle', 'sage')}<div><b>Nothing is generating</b><div class="muted" style="font-size:12.5px">Queue chapters from a story’s Audio tab.</div></div></div>`;
        const battery = n.batteryRestricted ? `<div class="card pad16 battery">${A.icon('battery_alert', 'gold')}<div class="grow"><b>Generating with the screen off</b><div class="muted" style="font-size:12.5px">Allow background use so audio keeps generating while the phone is locked.</div></div><button class="btn sm tonal" data-l="battery">Allow</button></div>` : '';
        const groups = n.byStory.map((g) => {
          const failed = g.jobs.filter((j) => j.status === 'failed').length;
          const queued = g.jobs.length - failed;
          return `<div class="card qcard"><button class="row qhead" data-story="${g.storyId}">${A.coverHtml(g.story)}<div class="grow"><div class="t nowrap">${esc(g.story.title)}</div><div class="muted" style="font-size:12.5px">${queued} queued${failed ? ` · <span class="red">${failed} failed</span>` : ''}</div></div>${A.icon('chevron_right', 'faint')}</button>
            ${g.jobs.slice(0, 5).map((j) => `<div class="jrow"><span class="aic k-${j.status === 'generating' ? 'generating' : j.status === 'failed' ? 'failed' : 'queued'}">${A.icon(j.status === 'generating' ? 'graphic_eq' : j.status === 'failed' ? 'error' : 'schedule', 'sm')}</span><div class="grow"><div class="nowrap" style="font-size:14px">${esc(j.title)}</div><div class="${j.status === 'failed' ? 'red' : j.status === 'generating' ? 'gold' : 'muted'}" style="font-size:12px">${j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences` : j.status === 'failed' ? `Failed · ${esc(j.error)}` : `Queued · ${j.voice}`}</div>${j.status === 'generating' ? A.prog(j.progress, 'copper') : ''}</div>${j.status === 'failed' ? `<button class="ib" data-l="retry" aria-label="Retry">${A.icon('refresh')}</button>` : `<button class="ib" data-rm="${g.storyId}|${j.chapterId}" aria-label="Remove">${A.icon('close', 'sm')}</button>`}</div>`).join('')}
            ${g.jobs.length > 5 ? `<div class="muted" style="font-size:12.5px;padding:4px 16px 0">and ${g.jobs.length - 5} more</div>` : ''}
            <div class="row" style="padding:8px 8px 4px 16px"><span class="grow"></span><button class="btn sm text" data-first="${g.storyId}">${A.icon('low_priority', 'sm')}Generate this story first</button></div></div>`;
        }).join('');
        $('queue').innerHTML = `${banner}${battery}<div class="section-label">Queue · ${n.queuedCount} chapter${n.queuedCount === 1 ? '' : 's'}<span class="aside">${n.failedCount ? `<button data-l="retry">Retry failed</button>` : ''}</span></div>
          <div class="upd-hint">Chapters generate in this order. Stories you’re listening to move up automatically.</div>
          ${groups || `<div class="empty" style="padding:28px">${A.icon('queue_music')}<h3>Queue is empty</h3><p>Open a story’s Audio tab to generate chapters.</p></div>`}`;
      }
      function gen() {
        const n = W.narration.state();
        $('gen').innerHTML = `<div class="section-label">Generated audio<span class="aside">${n.storage.used} · ${n.storage.free} free</span></div>
          <div class="list">${n.generated.map((g) => `<button class="li" data-story="${g.storyId}">${A.coverHtml(g.story, '', 'width:40px')}<div class="grow"><div class="t nowrap">${esc(g.story.title)}</div><div class="s">${g.chapters} chapters · ${fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)}</div></div>${A.icon('chevron_right', 'trail')}</button>`).join('') || '<div class="empty">No audio generated yet</div>'}</div>
          <div class="storage"><div class="row"><span class="grow">Audio uses ${n.storage.used}</span><span class="muted">${n.storage.free} free</span></div><div class="prog thick"><i style="width:6%"></i></div></div>`;
      }
      function setup() {
        const n = W.narration.state();
        $('setup').innerHTML = `<div class="section-label">Generate on</div>
          <div class="card pad16"><div class="chips">${[{ id: 'phone', name: 'This phone' }, ...n.computers].map((c) => `<button class="chip ${n.target === c.id ? 'on' : ''}" data-target="${c.id}">${A.icon(c.id === 'phone' ? 'smartphone' : 'computer')}${esc(c.name)}</button>`).join('')}</div>
            <div class="muted" style="font-size:12.5px;margin:10px 0 4px">${n.target === 'phone' ? 'Uses the built-in Kokoro voice on this phone (~3× real time).' : 'A computer on your network generates faster and saves battery.'}</div>
            <div class="list" style="margin-top:10px">${n.computers.map((c) => `<div class="li"><span class="lead">${A.icon('dns')}</span><div class="grow"><div class="t">${esc(c.name)}</div><div class="s">${esc(c.address)}</div></div><button class="ib" data-pc="${c.id}" aria-label="Computer options">${A.icon('more_vert')}</button></div>`).join('')}
            <button class="li" data-l="addpc"><span class="lead">${A.icon('add')}</span><div class="grow t">Add a computer</div></button></div></div>
          <div class="section-label">Keep ahead while listening</div>
          <div class="list">${A.stepRow('keepAhead', n.keepAhead, { step: 1, min: 0, max: 30, zero: 'Off', unit: ' ch' }, 'Chapters ahead', 'Generated ahead of where you’re listening')}</div>
          <div class="muted" style="font-size:12.5px;margin:10px 4px 0">${n.keepAhead ? `Keeps the next ${n.keepAhead} chapter${n.keepAhead > 1 ? 's' : ''} generated ahead of where you’re listening.` : 'Chapters only generate when you queue them.'}</div>`;
      }
      const renderAll = () => { if (el.hidden) return; now(); segs(); queue(); gen(); setup(); };
      W.on(el, 'click', '[data-seg]', (e, b) => { seg = b.dataset.seg; segs(); });
      W.on(el, 'click', '[data-story]', (e, b) => W.go('/narration/' + b.dataset.story));
      W.on(el, 'click', '[data-first]', (e, b) => { W.narration.storyFirst(b.dataset.first); A.toast('Moved to the front of the queue'); });
      W.on(el, 'click', '[data-rm]', (e, b) => { const [sid, cid] = b.dataset.rm.split('|'); W.narration.remove(sid, cid); A.toast('Removed from queue'); });
      W.on(el, 'click', '[data-target]', (e, b) => { W.narration.setTarget(b.dataset.target); A.toast(`Generating on ${b.textContent.trim()}`); });
      A.bindSteppers(el, (k, v) => { if (k === 'keepAhead') W.narration.setKeepAhead(v); });
      W.on(el, 'click', '[data-pc]', (e, b) => {
        const c = W.narration.state().computers.find((x) => x.id === b.dataset.pc);
        A.menu({ title: c.name, sub: c.address, sections: [{ items: [{ icon: 'network_check', label: 'Test connection', fn: () => { A.toast('Connecting…'); setTimeout(() => A.toast(`${c.name} is ready · 9.6× real time`), 1200); } }, { icon: 'check', label: 'Generate on this computer', fn: () => W.narration.setTarget(c.id) }, { icon: 'delete', label: 'Remove', danger: true, fn: () => { W.narration.removeComputer(c.id); A.toast('Computer removed'); } }] }] });
      });
      W.on(el, 'click', '[data-l],[data-act]', async (e, b) => {
        const a = b.dataset.l || b.dataset.act;
        if (a === 'player') W.go('/player');
        else if (a === 'toggle') W.player.toggle();
        else if (a === 'resume') { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); }
        else if (a === 'pause') W.narration.setPaused(!W.narration.state().paused);
        else if (a === 'retry') { W.narration.retryFailed(); A.toast('Retrying failed chapters'); }
        else if (a === 'battery') { W.narration.allowBattery(); A.toast('Background generation allowed'); }
        else if (a === 'voice') W.go('/settings/tts');
        else if (a === 'addpc') {
          const v = await A.form('Add a computer', [{ name: 'name', label: 'Name', placeholder: 'Living room PC' }, { name: 'address', label: 'Address', placeholder: '192.168.1.30:8790' }, { name: 'token', label: 'Token', placeholder: 'Paste the server token', type: 'password' }], 'Add');
          if (v && v.name && v.address) { W.narration.addComputer({ name: v.name, address: v.address }); A.toast(`${v.name} added`); } else if (v) A.toast('Name and address are required');
        } else if (a === 'qmenu') {
          const n = W.narration.state();
          A.menu({ title: 'Queue options', sections: [{ items: [
            { icon: n.paused ? 'play_arrow' : 'pause', label: n.paused ? 'Resume generating' : 'Pause generating', fn: () => W.narration.setPaused(!n.paused) },
            { icon: 'refresh', label: 'Retry failed', sub: n.failedCount ? `${n.failedCount} failed` : 'Nothing failed', fn: () => { W.narration.retryFailed(); A.toast('Retrying failed chapters'); } },
            { icon: 'clear_all', label: 'Clear the queue', danger: true, fn: async () => { if (await A.confirm('Clear the queue?', 'Generated audio stays. Queued chapters are removed.', 'Clear', true)) { W.narration.clearQueue(); A.toast('Queue cleared'); } } },
          ] }] });
        }
      });
      W.narration.on(W.debounce(() => { if (!el.hidden) { segs(); queue(); gen(); setup(); } }, 150));
      W.player.on(W.debounce(() => { if (!el.hidden) now(); }, 100));
      renderAll();
      return { el, update(r) { if (r.query.seg) seg = r.query.seg; renderAll(); } };
    },
  };
})();
