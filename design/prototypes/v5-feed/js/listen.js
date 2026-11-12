/* v5-feed Listen tab (audio generation hub) and per-story Chapter audio. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const N = W.narration;

  function genBanner(n, storyId) {
    const c = n.current;
    if (!c || (storyId && c.storyId !== storyId)) {
      if (storyId) return '';
      return `<div class="gen idle">${A.ic(n.paused ? 'pause_circle' : 'graphic_eq')}<div><b>${n.paused ? 'Generating is paused' : 'Nothing is generating'}</b><small>${n.paused ? `${n.queuedCount} chapters wait in the queue` : 'Queue chapters from a story to make audio ahead of time.'}</small></div>${n.paused ? `<button class="btn primary sm" data-resume>${A.ic('play_arrow', 'f')}Resume</button>` : ''}</div>`;
    }
    const where = n.targetComputer ? `Generating on ${esc(n.targetComputer.name)}` : 'Generating';
    return `<div class="gen" data-tint="${c.storyId}">${A.ambBg(c.story)}<div class="gen-in">
      <div class="gen-top">${A.cover(c.story, 'gen-cv')}<div class="gen-t"><span class="eyebrow">${n.paused ? 'Paused' : where} · ${Math.round(c.progress * 100)}%</span><b>${esc(c.title)}</b><small>${esc(A.short(c.story.title))}</small></div>
        <button class="ibtn solid" data-${n.paused ? 'resume' : 'pause'} aria-label="${n.paused ? 'Resume' : 'Pause'}">${A.ic(n.paused ? 'play_arrow' : 'pause', 'f')}</button></div>
      ${A.bar(c.progress, n.paused ? '' : 'live')}
      <p class="gen-s">${c.sentence} of ${c.sentences} sentences · ${c.speed} · ~${c.etaMin} min left${n.queuedCount > 1 ? ` · ${n.queuedCount - 1} chapters after this` : ''}</p></div></div>`;
  }

  function nowCard() {
    const ps = W.player.state();
    if (ps.loaded) return `<div class="lnow">${A.ambBg(ps.story)}<button class="lnow-b" data-go="/player">${A.cover(ps.story, 'lnow-cv', true)}<span><small>${ps.playing ? 'Now playing' : 'Paused'}</small><b>${esc(ps.chapter.title)}</b><em>${esc(A.short(ps.story.title))} · ${fmt.dur(ps.remainingSec)} left</em></span></button><button class="ibtn solid" data-ptoggle aria-label="Play or pause">${A.ic(ps.playing ? 'pause' : 'play_arrow', 'f')}</button></div>`;
    const ss = W.session; if (!ss) return '';
    const s = W.story(ss.storyId);
    return `<div class="lnow">${A.ambBg(s)}<button class="lnow-b" data-go="/player">${A.cover(s, 'lnow-cv', true)}<span><small>Continue listening</small><b>${esc(ss.chapterTitle)}</b><em>${esc(A.short(s.title))} · sentence ${ss.index + 1}</em></span></button><button class="ibtn solid" data-presume aria-label="Resume">${A.ic('play_arrow', 'f')}</button></div>`;
  }

  function jobRow(j, n) {
    const t = j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences` : j.status === 'failed' ? `Failed · ${j.error}` : `Queued · ${j.voice}`;
    void n;
    return `<button class="job st-${j.status}" data-job="${j.storyId}|${j.chapterId}"><i class="dot ${j.status === 'generating' ? 'd-dl' : j.status === 'failed' ? 'd-bad' : 'd-q'}"></i><span><b>${esc(j.title)}</b><small>${esc(t)}</small>${j.status === 'generating' ? A.bar(j.progress) : ''}</span>${A.ic('more_vert', 'chev')}</button>`;
  }

  function hubBody(n) {
    const gen = n.generated;
    return `<div class="gen-slot">${genBanner(n)}</div>
      <div class="sec-h"><div><h2>Ready to listen</h2><p>${gen.length ? `${gen.reduce((a, g) => a + g.chapters, 0)} chapters of generated audio` : 'No generated audio yet'}</p></div></div>
      <div class="group">${gen.map((g) => `<button class="row" data-go="/narration/${g.storyId}">${A.cover(g.story, 'row-cv')}<span class="rt"><b>${esc(A.short(g.story.title))}</b><small>${g.chapters} chapters · ${fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)} · Kokoro</small></span>${A.ic('chevron_right', 'chev')}</button>`).join('') || `<div class="row"><span class="rt"><small>Generated chapters appear here.</small></span></div>`}</div>
      <div class="sec-h"><div><h2>Queue · ${n.queuedCount} chapters</h2><p>Chapters generate in order. Move a story to the front to hear it sooner.</p></div></div>
      ${n.byStory.length ? n.byStory.map((g) => {
        const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.filter((j) => j.status === 'failed').length;
        return `<div class="qcard" data-tint="${g.storyId}"><div class="qc-h">${A.cover(g.story, 'qc-cv')}<button class="qc-t" data-go="/narration/${g.storyId}"><b>${esc(A.short(g.story.title))}</b><small>${q} queued${f ? ` · <span class="bad">${f} failed</span>` : ''}</small></button></div>
          <div class="qc-jobs">${g.jobs.slice(0, 5).map((j) => jobRow(j, n)).join('')}${g.jobs.length > 5 ? `<p class="qc-more">and ${g.jobs.length - 5} more</p>` : ''}</div>
          <div class="qc-a"><button class="btn tonal sm" data-first="${g.storyId}">${A.ic('vertical_align_top')}Generate this story first</button>${f ? `<button class="btn ghost sm" data-retry>${A.ic('refresh')}Retry</button>` : ''}</div></div>`;
      }).join('') : `<div class="card" style="padding:4px">${A.empty('queue_music', 'The queue is empty', 'Open a story’s Chapter audio to queue chapters.')}</div>`}
      ${n.batteryRestricted ? `<div class="bat card"><span class="icon-disc">${A.ic('battery_alert')}</span><div><b>Generating with the screen off</b><small>Android may stop generation when the screen is off. Allow unrestricted battery use for steady progress.</small><button class="btn primary sm" data-battery>Allow</button></div></div>` : ''}
      <div class="sec-h"><div><h2>Generate on</h2><p>Use a computer on your network for ~3× faster audio.</p></div></div>
      <div class="card gen-on">${A.chips([['phone', `${A.ic('smartphone')}This phone`], ...n.computers.map((c) => [c.id, `${A.ic('computer')}${esc(c.name)}`])], n.target, 'data-target')}
        <div class="group" style="margin-top:12px">${n.computers.map((c) => `<div class="row">${A.ic('lan')}<span class="rt"><b>${esc(c.name)}</b><small>${esc(c.address)}${n.target === c.id ? ' · in use' : ''}</small></span><button class="ibtn sm" data-rmpc="${c.id}" aria-label="Remove computer">${A.ic('delete')}</button></div>`).join('')}
          <button class="row" data-addpc>${A.ic('add_circle')}<span class="rt"><b>Add a computer</b><small>Run the narration server, then pair it here</small></span></button></div></div>
      <div class="sec-h"><div><h2>Keep ahead while listening</h2><p>Generate upcoming chapters automatically while you listen.</p></div></div>
      ${A.chips([[0, 'Off'], [1, '1 chapter'], [3, '3 chapters'], [5, '5 chapters'], [10, '10 chapters']], n.keepAhead, 'data-ahead')}
      <p class="storage">${A.ic('sd_storage')}Audio uses ${n.storage.used} · ${n.storage.free} free</p>`;
  }

  A.screens.narration = (ctx) => {
    const ss = W.session;
    A.tint(ctx.el, ss ? W.story(ss.storyId) : null);
    ctx.el.innerHTML = `<div class="sc listen"><div class="lib-title" style="padding-top:calc(16px + var(--sat))"><h1>Listen</h1>
        <button class="ibtn" data-go="/settings/tts" aria-label="Voice and speech">${A.ic('tune')}</button><button class="ibtn" data-qopts aria-label="Queue options">${A.ic('more_vert')}</button></div>
      <div class="wrap"><div class="now-slot">${nowCard()}</div><div class="hub-body"></div></div></div>`;
    const body = ctx.el.querySelector('.hub-body'), now = ctx.el.querySelector('.now-slot');
    const tintAll = () => body.querySelectorAll('[data-tint]').forEach((x) => A.tint(x, W.story(x.dataset.tint)));
    const render = () => { body.innerHTML = hubBody(N.state()); tintAll(); };
    render();
    let last = '';
    ctx.on(N.on((n) => {
      const key = n.queue.map((j) => j.chapterId + j.status).join() + n.paused + n.target + n.keepAhead + n.computers.length + n.batteryRestricted;
      if (key !== last) { last = key; render(); return; }
      const slot = body.querySelector('.gen-slot'); if (slot) { slot.innerHTML = genBanner(n); tintAll(); }
      const gj = n.queue.find((j) => j.status === 'generating'); if (gj) { const r = body.querySelector(`[data-job="${gj.storyId}|${gj.chapterId}"]`); if (r) { r.outerHTML = jobRow(gj, n); } }
    }));
    let pk = '';
    ctx.on(W.player.on((ps) => { const k = ps.loaded ? ps.chapter.id + ps.playing + Math.round(ps.remainingSec / 60) : 'x'; if (k !== pk) { pk = k; now.innerHTML = nowCard(); } }));
    ctx.el.addEventListener('click', (e) => {
      const t = e.target, n = N.state();
      if (t.closest('[data-pause]')) { N.setPaused(true); A.toast('Generating paused', { icon: 'pause' }); return; }
      if (t.closest('[data-resume]')) { N.setPaused(false); A.toast('Generating resumed', { icon: 'play_arrow' }); return; }
      if (t.closest('[data-ptoggle]')) { W.player.toggle(); return; }
      if (t.closest('[data-presume]')) { W.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); return; }
      if (t.closest('[data-qopts]')) return A.menu({ title: 'Queue options', items: [{ icon: n.paused ? 'play_arrow' : 'pause', label: n.paused ? 'Resume generating' : 'Pause generating', fn: () => N.setPaused(!n.paused) }, { icon: 'refresh', label: 'Retry failed', sub: `${n.failedCount} failed`, disabled: !n.failedCount, fn: () => { N.retryFailed(); A.toast('Retrying failed chapters', { icon: 'refresh' }); } }, { icon: 'clear_all', label: 'Clear the queue', danger: true, fn: () => A.confirm({ title: 'Clear the queue?', body: `${n.queue.length} chapters will be removed. Generated audio is kept.`, ok: 'Clear', danger: true }).then((ok) => ok && (N.clearQueue(), A.toast('Queue cleared'))) }] });
      const f = t.closest('[data-first]'); if (f) { N.storyFirst(f.dataset.first); A.toast('Moved to the front of the queue', { icon: 'vertical_align_top' }); return; }
      if (t.closest('[data-retry]')) { N.retryFailed(); A.toast('Retrying failed chapters', { icon: 'refresh' }); return; }
      const j = t.closest('[data-job]'); if (j) { const [sid, cid] = j.dataset.job.split('|'); const job = n.queue.find((x) => x.storyId === sid && x.chapterId === cid); return A.menu({ title: job.title, sub: A.short(W.story(sid).title), tint: W.story(sid), items: [...(job.status === 'failed' ? [{ icon: 'refresh', label: 'Retry', fn: () => N.retryFailed() }] : []), { icon: 'vertical_align_top', label: 'Generate this story first', fn: () => N.storyFirst(sid) }, { icon: 'chrome_reader_mode', label: 'Open in reader', fn: () => A.go(`/reader/${sid}/${cid}`) }, { icon: 'remove_circle', label: 'Remove from queue', danger: true, fn: () => { N.remove(sid, cid); A.toast('Removed from queue'); } }] }); }
      if (t.closest('[data-battery]')) { N.allowBattery(); A.toast('Battery use: unrestricted', { icon: 'battery_full' }); return; }
      const tg = t.closest('[data-target]'); if (tg) { N.setTarget(tg.dataset.target); A.toast(tg.dataset.target === 'phone' ? 'Generating on this phone' : `Generating on ${n.computers.find((c) => c.id === tg.dataset.target).name}`, { icon: 'swap_horiz' }); return; }
      const rm = t.closest('[data-rmpc]'); if (rm) { const c = n.computers.find((x) => x.id === rm.dataset.rmpc); A.confirm({ title: `Remove ${c.name}?`, body: 'You can pair it again later.', ok: 'Remove', danger: true }).then((ok) => ok && N.removeComputer(c.id)); return; }
      if (t.closest('[data-addpc]')) { A.form({ title: 'Add a computer', body: 'Start the narration server on your computer, then enter what it shows.', fields: [{ key: 'name', label: 'Name', placeholder: 'Living room PC' }, { key: 'address', label: 'Address', placeholder: '192.168.1.30:8790' }, { key: 'token', label: 'Token', placeholder: 'Pairing token', type: 'password' }], ok: 'Add' }).then((v) => { if (v && v.name && v.address) { N.addComputer({ name: v.name, address: v.address }); A.toast(`${v.name} added`, { icon: 'computer' }); } else if (v) A.toast('Name and address are required', { icon: 'error' }); }); return; }
      const ah = t.closest('[data-ahead]'); if (ah) { N.setKeepAhead(+ah.dataset.ahead); }
    });
  };

  // ---------- Chapter audio (per story) ----------
  const ST_CLS = { none: 'muted', queued: 'acc', generating: 'acc', failed: 'bad', ready: 'gold', stale: 'bad' };
  A.screens.narrationStory = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    A.tint(ctx.el, s);
    const list = W.chapters(s.id).filter((c) => c.downloaded);
    const voice = W.settings.tts.voiceLabel.split(' ')[0];
    const st = { filter: s.bookmark != null ? 'bm' : 'all', shown: 80, sel: ctx.query.select ? new Set(list.slice(0, 3).map((c) => c.id)) : null };
    const status = (c) => N.chapterStatus(s.id, c.id);
    const counts = () => { const k = { none: 0, ready: 0 }; list.forEach((c) => { const x = status(c).kind; if (x === 'ready') k.ready++; if (x === 'none' || x === 'stale') k.none++; }); return k; };
    const filtered = () => list.filter((c, i) => st.filter === 'all' || (st.filter === 'bm' && W.chapterIndex(s.id, c.id) >= (s.bookmark || 0)) || (st.filter === 'none' && ['none', 'stale'].includes(status(c).kind)) || (st.filter === 'ready' && status(c).kind === 'ready') || void i);
    const focusC = list[Math.min(list.length - 1, s.bookmark || 0)];
    function rowHtml(c) {
      const x = status(c);
      const sel = st.sel && st.sel.has(c.id);
      return `<button class="ca ${sel ? 'selected' : ''}" data-cid="${c.id}"><span class="selbox">${A.ic('check')}</span><span class="ca-t"><b>${esc(c.title)}</b><small class="s-${ST_CLS[x.kind]}">${x.kind === 'ready' ? A.ic('graphic_eq') : ''}${esc(x.text)}</small>${x.kind === 'generating' ? A.bar(x.progress, 'live') : ''}</span>${x.kind === 'ready' ? `<span class="ibtn sm ca-play">${A.ic('play_arrow', 'f')}</span>` : A.ic('more_vert', 'chev')}</button>`;
    }
    function render() {
      const n = N.state(), k = counts();
      const gen = n.generated.find((g) => g.storyId === s.id);
      const fx = status(focusC);
      const fl = filtered();
      body.innerHTML = `<div class="gen-slot">${genBanner(n, s.id)}</div>
        ${A.chips([...(s.bookmark != null ? [['bm', `From bookmark <small>${list.length - s.bookmark}</small>`]] : []), ['all', `All <small>${list.length}</small>`], ['none', `Not generated <small>${k.none}</small>`], ['ready', `Ready <small>${k.ready}</small>`]], st.filter, 'data-caf')}
        ${focusC && !st.sel ? `<div class="focus card"><span class="eyebrow">At your bookmark</span><b>${esc(focusC.title)}</b><small class="s-${ST_CLS[fx.kind]}">${esc(fx.text)}</small>
          <div class="nc-acts">${fx.kind === 'ready' ? `<button class="btn primary sm" data-play="${focusC.id}">${A.ic('play_arrow', 'f')}Play</button>` : `<button class="btn primary sm" data-gennow="${focusC.id}">${A.ic('bolt')}Generate now</button><button class="btn glassy sm" data-gennext="${focusC.id}">Generate it next</button>`}</div></div>` : ''}
        <p class="ca-line">New audio uses ${esc(voice)} · ${k.ready} ready${gen ? ` · ${gen.sizeMb} MB` : ''}</p>
        <div class="ca-list ${st.sel ? 'selecting' : ''}">${fl.length ? fl.slice(0, st.shown).map(rowHtml).join('') : A.empty('graphic_eq', 'Nothing here', 'No chapters match this filter.')}</div>
        ${fl.length > st.shown ? `<button class="btn outline block" data-camore style="margin-top:12px">Show more · ${fl.length - st.shown} left</button>` : ''}`;
      body.querySelectorAll('[data-tint]').forEach((x) => A.tint(x, W.story(x.dataset.tint)));
      selUi();
    }
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Chapter audio', sub: A.short(s.title), back: '/story/' + s.id, actions: `<button class="ibtn" data-selmode aria-label="Select chapters">${A.ic('checklist')}</button><button class="ibtn" data-go="/narration" aria-label="Generation queue">${A.ic('queue_music')}</button><button class="ibtn" data-camenu aria-label="More">${A.ic('more_vert')}</button>` })}
      <div class="wrap ca-body"></div></div>
      <div class="seltop" hidden><button class="ibtn" data-selx aria-label="Exit selection">${A.ic('close')}</button><b class="selcount"></b><button class="btn ghost sm" data-selall>Select all</button></div>
      <div class="selbar glass"><button class="btn glassy" data-sgen>${A.ic('graphic_eq')}Generate</button><button class="btn glassy" data-sregen>${A.ic('autorenew')}Regenerate</button><button class="btn danger" data-sdel>${A.ic('delete')}</button></div>`;
    A.bindTopbar(ctx);
    const body = ctx.el.querySelector('.ca-body');
    function selUi() { const on = !!st.sel; ctx.el.querySelector('.seltop').hidden = !on; ctx.el.querySelector('.selbar').classList.toggle('in', on); document.body.classList.toggle('selecting-lib', on); if (on) ctx.el.querySelector('.selcount').textContent = `${st.sel.size} selected`; }
    ctx.on(() => document.body.classList.remove('selecting-lib'));
    render();
    let lastK = '';
    ctx.on(N.on((n) => {
      const k = n.queue.map((j) => j.chapterId + j.status).join() + n.generated.map((g) => g.chapters).join() + n.paused;
      if (k !== lastK) { lastK = k; const y = ctx.el.querySelector('.sc').scrollTop; render(); ctx.el.querySelector('.sc').scrollTop = y; return; }
      const slot = body.querySelector('.gen-slot'); if (slot) { slot.innerHTML = genBanner(n, s.id); slot.querySelectorAll('[data-tint]').forEach((x) => A.tint(x, s)); }
      n.queue.filter((j) => j.status === 'generating' && j.storyId === s.id).forEach((j) => { const r = body.querySelector(`[data-cid="${j.chapterId}"]`); if (r) r.outerHTML = rowHtml(W.chapter(s.id, j.chapterId)); });
    }));
    A.longPress(body, '[data-cid]', (r) => { if (!st.sel) st.sel = new Set(); st.sel.add(r.dataset.cid); render(); });
    const play = (cid) => { W.player.load(s.id, cid, { play: true }); A.toast('Playing ' + W.chapter(s.id, cid).title, { icon: 'play_arrow', action: 'Open player', fn: () => A.go('/player') }); };
    const exitSel = () => { st.sel = null; render(); };
    ctx.el.addEventListener('click', (e) => {
      const t = e.target;
      const row = t.closest('[data-cid]');
      if (row) {
        const cid = row.dataset.cid;
        if (st.sel) { st.sel.has(cid) ? st.sel.delete(cid) : st.sel.add(cid); if (!st.sel.size) st.sel = null; render(); return; }
        const x = status(W.chapter(s.id, cid));
        if (x.kind === 'ready' && t.closest('.ca-play')) return play(cid);
        return chapterMenu(cid, x);
      }
      const f = t.closest('[data-caf]'); if (f) { st.filter = f.dataset.caf; st.shown = 80; render(); return; }
      if (t.closest('[data-camore]')) { st.shown += 80; render(); return; }
      const p = t.closest('[data-play]'); if (p) return play(p.dataset.play);
      const gn = t.closest('[data-gennow]'); if (gn) { N.enqueue(s.id, [gn.dataset.gennow], { first: true }); A.toast('Generating now', { icon: 'bolt' }); return; }
      const gx = t.closest('[data-gennext]'); if (gx) { N.enqueue(s.id, [gx.dataset.gennext], { first: true }); A.toast('Up next in the queue', { icon: 'vertical_align_top' }); return; }
      if (t.closest('[data-pause]')) return N.setPaused(true);
      if (t.closest('[data-resume]')) return N.setPaused(false);
      if (t.closest('[data-selmode]')) { st.sel = new Set(); render(); A.toast('Tap chapters to select them', { icon: 'checklist' }); return; }
      if (t.closest('[data-selx]')) return exitSel();
      if (t.closest('[data-selall]')) { const fl = filtered(); st.sel = st.sel.size === fl.length ? new Set() : new Set(fl.map((c) => c.id)); render(); return; }
      if (t.closest('[data-sgen]')) { const ids = [...st.sel].filter((id) => status(W.chapter(s.id, id)).kind !== 'ready'); const nq = N.enqueue(s.id, ids); A.toast(`${nq} chapters queued`, { icon: 'graphic_eq' }); exitSel(); return; }
      if (t.closest('[data-sregen]')) { const ids = [...st.sel]; N.deleteAudio(s.id, ids); N.enqueue(s.id, ids); A.toast(`Regenerating ${ids.length} in ${voice}`, { icon: 'autorenew' }); exitSel(); return; }
      if (t.closest('[data-sdel]')) { const ids = [...st.sel]; A.confirm({ title: `Delete audio for ${ids.length} chapters?`, body: 'The chapter text stays. You can generate the audio again.', ok: 'Delete', danger: true }).then((ok) => { if (ok) { N.deleteAudio(s.id, ids); A.toast('Audio deleted', { icon: 'delete' }); exitSel(); } }); return; }
      if (t.closest('[data-camenu]')) {
        const k = counts();
        A.menu({ title: 'Chapter audio', sub: A.short(s.title), tint: s, items: [
          { icon: 'graphic_eq', label: `Generate everything not generated (${k.none})`, fn: () => { const ids = list.filter((c) => status(c).kind === 'none').map((c) => c.id); A.toast(`${N.enqueue(s.id, ids)} chapters queued`, { icon: 'graphic_eq' }); } },
          { icon: 'vertical_align_top', label: 'Generate this story first', fn: () => { N.storyFirst(s.id); A.toast('Moved to the front of the queue'); } },
          { icon: 'autorenew', label: `Regenerate ${k.ready} in ${voice}`, fn: () => { const ids = list.filter((c) => status(c).kind === 'ready').map((c) => c.id); N.deleteAudio(s.id, ids); N.enqueue(s.id, ids); A.toast(`Regenerating ${ids.length} chapters`); } },
          { icon: 'record_voice_over', label: 'Voice & Speech', fn: () => A.go('/settings/tts') },
          { icon: 'queue_music', label: 'Generation queue', fn: () => A.go('/narration') },
          { icon: 'remove_circle', label: 'Remove from queue', fn: () => { N.remove(s.id); A.toast('Removed this story from the queue'); } },
          { divider: true },
          { icon: 'delete', label: 'Delete all audio', danger: true, fn: () => A.confirm({ title: 'Delete all audio?', body: `All generated audio for “${A.short(s.title)}” is removed.`, ok: 'Delete', danger: true }).then((ok) => ok && (N.deleteAudio(s.id), A.toast('All audio deleted', { icon: 'delete' }))) },
        ] });
      }
    });
    function chapterMenu(cid, x) {
      const c = W.chapter(s.id, cid);
      const items = [];
      if (x.kind === 'ready') items.push({ icon: 'play_arrow', label: 'Play', fn: () => play(cid) });
      if (x.kind === 'none' || x.kind === 'stale') { items.push({ icon: 'bolt', label: 'Generate now', fn: () => N.enqueue(s.id, [cid], { first: true }) }); items.push({ icon: 'vertical_align_top', label: 'Generate next', fn: () => { N.enqueue(s.id, [cid], { first: true }); A.toast('Up next in the queue'); } }); }
      if (x.kind === 'failed') items.push({ icon: 'refresh', label: 'Retry', fn: () => N.retryFailed() });
      if (x.kind === 'queued') items.push({ icon: 'remove_circle', label: 'Remove from queue', fn: () => N.remove(s.id, cid) });
      if (x.kind === 'ready' || x.kind === 'stale') items.push({ icon: 'autorenew', label: `Regenerate in ${voice}`, fn: () => { N.deleteAudio(s.id, [cid]); N.enqueue(s.id, [cid], { first: true }); } });
      items.push({ icon: 'chrome_reader_mode', label: 'Open in reader', fn: () => A.go(`/reader/${s.id}/${cid}`) });
      if (x.kind === 'ready' || x.kind === 'stale') items.push({ icon: 'delete', label: 'Delete audio', danger: true, fn: () => { N.deleteAudio(s.id, [cid]); A.toast('Audio deleted', { icon: 'delete' }); } });
      A.menu({ title: c.title, sub: x.text, tint: s, items });
    }
  };
})();
