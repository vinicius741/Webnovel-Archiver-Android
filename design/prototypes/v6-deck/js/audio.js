/* v6 — Audio generation hub and per-story Chapter audio. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;

  function banner(n, storyId) {
    const c = n.current;
    if (!c || (storyId && c.storyId !== storyId)) {
      if (storyId) return '';
      return `<section class="gen idle"><div class="gen-l"><span class="num">00<em>%</em></span></div><div class="gen-r"><b>Nothing is generating</b><small>${n.paused ? 'Generating is paused' : 'Queue chapters to make audio ahead of time'}</small></div>${n.paused ? `<button class="btn sm acc" data-q="resume">${ic('play_arrow')} Resume</button>` : ''}</section>`;
    }
    const after = n.queue.filter((j) => j.status === 'queued').length;
    return `<section class="gen ${n.paused ? 'paused' : ''}">
      <div class="gen-top"><span class="kick">${n.paused ? 'Paused' : n.targetComputer ? `Generating on ${esc(n.targetComputer.name)}` : 'Generating'}</span><button class="btn sm ${n.paused ? 'acc' : 'ghost'}" data-q="${n.paused ? 'resume' : 'pause'}">${ic(n.paused ? 'play_arrow' : 'pause')} ${n.paused ? 'Resume' : 'Pause'}</button></div>
      <div class="gen-mid"><span class="num gen-pct">${Math.round(c.progress * 100)}<em>%</em></span><div><b>${esc(c.title)}</b><small>${esc(V6.short(c.story))}</small></div></div>
      ${V6.bar(c.progress, 'thick')}
      <p class="mono gen-stats">${c.sentence} of ${c.sentences} sentences · ${c.speed} · ~${c.etaMin} min left · ${after} chapter${after === 1 ? '' : 's'} after this</p></section>`;
  }

  // ---------- Hub ----------
  V6.screens.narration = (ctx) => {
    const { root } = ctx;
    root.innerHTML = `${V6.topbar('Audio generation', 'Make chapter audio ahead of time', V6.iconBtn('more_vert', 'data-q="menu"', 'Queue options'))}<div data-b></div>`;
    const b = root.querySelector('[data-b]');
    const exp = new Set();
    function draw() {
      const n = W.narration.state();
      const groups = n.byStory.map((g) => {
        const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.filter((j) => j.status === 'failed').length;
        const show = exp.has(g.storyId) ? g.jobs : g.jobs.slice(0, 5);
        return `<section class="card nq">
          <button class="nq-h" data-go="/narration/${g.storyId}">${V6.cover(g.story)}<span><b>${esc(g.story.title)}</b><small>${q} queued${f ? ` · <span class="hot-t">${f} failed</span>` : ''}</small></span>${ic('chevron_right')}</button>
          ${show.map((j) => `<div class="nq-j ${j.status}"><span class="dot"></span><span class="nq-t"><b>${esc(j.title)}</b><small>${j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences` : j.status === 'failed' ? `Failed · ${esc(j.error)}` : `Queued · ${j.voice}`}</small>${j.status === 'generating' ? V6.bar(j.progress) : ''}</span>${j.status === 'failed' ? `<button class="ib" data-retry aria-label="Retry">${ic('refresh')}</button>` : `<button class="ib" data-rmj="${g.storyId}|${j.chapterId}" aria-label="Remove">${ic('close')}</button>`}</div>`).join('')}
          ${g.jobs.length > 5 && !exp.has(g.storyId) ? `<button class="lnk pad" data-more="${g.storyId}">and ${g.jobs.length - 5} more</button>` : ''}
          <button class="btn sm ghost nq-first" data-first="${g.storyId}">${ic('vertical_align_top')} Generate this story first</button>
        </section>`;
      }).join('');
      b.innerHTML = `${banner(n)}
        ${V6.sec('01', `Queue · ${n.queuedCount}`, n.failedCount ? `<button class="btn sm ghost" data-q="retry">${ic('refresh')} Retry ${n.failedCount}</button>` : '')}
        <p class="hint">Chapters generate in order. Move a story to the front with “Generate this story first”.</p>
        ${groups || V6.empty('queue_music', 'Queue is empty', 'Queue chapters from a novel’s Chapter audio, or turn on Keep ahead.')}
        ${n.batteryRestricted ? `<section class="card hot"><div class="card-p"><b>Generating with the screen off</b><p class="mut">Android may stop audio generation while the screen is off. Allow it to keep running in the background.</p><button class="btn sm acc" data-q="battery">${ic('battery_charging_full')} Allow</button></div></section>` : ''}
        ${V6.sec('02', 'Generate on')}
        <div class="chips">${[{ id: 'phone', name: 'This phone' }, ...n.computers].map((c) => `<button class="chip ${n.target === c.id ? 'on' : ''}" data-tg="${c.id}">${ic(c.id === 'phone' ? 'smartphone' : 'computer')}${esc(c.name)}</button>`).join('')}</div>
        <div class="pad-t">${n.computers.map((c) => `<div class="row">${ic('computer')}<span class="rt"><b>${esc(c.name)}</b><small class="mono">${esc(c.address)}</small></span><button class="ib" data-rmc="${c.id}" aria-label="Remove computer">${ic('delete')}</button></div>`).join('')}
        <button class="row" data-q="addpc">${ic('add')}<span class="rt"><b>Add a computer</b><small>Run the narration server on a PC or Mac for ~3× faster audio</small></span></button></div>
        ${V6.sec('03', 'Keep ahead while listening')}
        <div class="ka">${[0, 1, 3, 5, 10].map((k) => `<button class="${n.keepAhead === k ? 'on' : ''}" data-ka="${k}"><span class="num">${k || 'Off'}</span>${k ? '<small>ch</small>' : ''}</button>`).join('')}</div>
        <p class="hint">${n.keepAhead ? `While you listen, the next ${n.keepAhead} chapter${n.keepAhead > 1 ? 's' : ''} get audio automatically.` : 'Audio is only made when you queue it.'}</p>
        ${V6.sec('04', 'Generated audio')}
        <div class="stor"><div><span class="num">${n.storage.used.replace(' MB', '')}</span><small class="mono">MB used</small></div><div><span class="num">${n.storage.free.replace(' GB', '')}</span><small class="mono">GB free</small></div></div>
        ${n.generated.map((g) => `<button class="row" data-go="/narration/${g.storyId}">${V6.cover(g.story, 'xs')}<span class="rt"><b>${esc(g.story.title)}</b><small>${g.chapters} chapters · ${W.fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)} · Kokoro</small></span>${ic('chevron_right')}</button>`).join('')}`;
    }
    root.addEventListener('click', async (e) => {
      const t = e.target;
      const q = t.closest('[data-q]');
      if (q) {
        const k = q.dataset.q, n = W.narration.state();
        if (k === 'pause') W.narration.setPaused(true);
        if (k === 'resume') W.narration.setPaused(false);
        if (k === 'retry') { W.narration.retryFailed(); V6.toast('Retrying failed chapters', { icon: 'refresh' }); }
        if (k === 'battery') { W.narration.allowBattery(); V6.toast('Background generation allowed', { icon: 'battery_charging_full' }); }
        if (k === 'addpc') { const v = await V6.dialog({ title: 'Add a computer', body: 'Run the WNA narration server, then enter its address and token.', fields: [{ label: 'Name', placeholder: 'Living-room PC' }, { label: 'Address', placeholder: '192.168.1.30:8790' }, { label: 'Token', placeholder: 'Shown by the server', type: 'password' }], ok: 'Add' }); if (v && v[1]) { W.narration.addComputer({ name: v[0] || 'Computer', address: v[1] }); V6.toast('Computer added · connection OK', { icon: 'computer' }); } }
        if (k === 'menu') V6.sheet({ title: 'Queue options', items: [
          { icon: n.paused ? 'play_arrow' : 'pause', label: n.paused ? 'Resume generating' : 'Pause generating', run: () => W.narration.setPaused(!n.paused) },
          { icon: 'refresh', label: 'Retry failed', sub: `${n.failedCount} failed`, disabled: !n.failedCount, run: () => W.narration.retryFailed() },
          { icon: 'clear_all', label: 'Clear the queue', danger: true, run: async () => { if (await V6.dialog({ title: 'Clear the queue?', body: 'Generated audio is kept.', ok: 'Clear', danger: true })) W.narration.clearQueue(); } },
        ] });
        return;
      }
      const tg = t.closest('[data-tg]'); if (tg) { W.narration.setTarget(tg.dataset.tg); return V6.toast(tg.dataset.tg === 'phone' ? 'Generating on this phone' : `Generating on ${tg.textContent.trim()}`, { icon: 'swap_horiz' }); }
      const rc = t.closest('[data-rmc]'); if (rc) return W.narration.removeComputer(rc.dataset.rmc);
      const ka = t.closest('[data-ka]'); if (ka) return W.narration.setKeepAhead(+ka.dataset.ka);
      const rj = t.closest('[data-rmj]'); if (rj) { const [sid, cid] = rj.dataset.rmj.split('|'); W.narration.remove(sid, cid); return V6.toast('Removed from queue', { icon: 'close' }); }
      if (t.closest('[data-retry]')) return W.narration.retryFailed();
      const mo = t.closest('[data-more]'); if (mo) { exp.add(mo.dataset.more); return draw(); }
      const fi = t.closest('[data-first]'); if (fi) { W.narration.storyFirst(fi.dataset.first); return V6.toast('Moved to the front', { icon: 'vertical_align_top' }); }
    });
    // redraw fully only when the queue shape changes; otherwise patch live progress in place
    const sig = (n) => JSON.stringify([n.queue.map((j) => j.chapterId + j.status), n.paused, n.target, n.keepAhead, n.computers.length, n.batteryRestricted, n.generated.map((g) => g.chapters), [...exp]]);
    let last = '';
    ctx.sub(W.narration.on((n) => {
      const k = sig(n); if (k !== last) { last = k; return draw(); }
      const g = b.querySelector('.gen'); if (g) g.outerHTML = banner(n);
      const j = b.querySelector('.nq-j.generating'); if (j && n.current) { j.querySelector('small').textContent = `Generating · ${Math.round(n.current.progress * 100)}% · ${n.current.sentence} of 80 sentences`; const i = j.querySelector('.pbar i'); if (i) i.style.width = n.current.progress * 100 + '%'; }
    }));
    last = sig(W.narration.state());
    draw();
  };

  // ---------- Chapter audio (per story) ----------
  V6.screens.narrationStory = (ctx) => {
    const { root, route } = ctx;
    const s = W.story(route.params.id); if (!s) { root.innerHTML = V6.topbar('Not found', ''); return; }
    const list = W.chapters(s.id).filter((c) => c.downloaded);
    const bm = V6.bookmarkOf(s) || 0;
    const ui = { filter: 'bm', focus: route.query.chapter || null };
    const sel = new Set();
    root.innerHTML = `${V6.topbar('Chapter audio', esc(s.title), `${V6.iconBtn('checklist', 'data-x="select"', 'Select chapters')}${V6.iconBtn('queue_music', 'data-go="/narration"', 'Generation queue')}${V6.iconBtn('more_vert', 'data-x="more"', 'More')}`)}
      <div class="selbar"><button class="ib" data-x="selx" aria-label="Exit selection">${ic('close')}</button><b class="num" data-seln>0</b><span>selected</span><button class="btn sm ghost" data-x="selall">All</button></div>
      <div data-ban></div><div class="chips" data-f></div><div data-focus></div><p class="mono dim pad" data-line></p><div data-rows></div>
      <div class="selbtns"><button class="btn acc" data-x="sgen">${ic('graphic_eq')} Generate</button><button class="btn ghost" data-x="sregen">${ic('autorenew')} Regenerate</button><button class="btn danger" data-x="sdel">${ic('delete')}</button></div>`;
    const $ = (q) => root.querySelector(q);
    const stOf = (c) => W.narration.chapterStatus(s.id, c.id);
    function counts() { const k = { none: 0, ready: 0 }; list.forEach((c) => { const x = stOf(c).kind; if (x === 'none') k.none++; if (x === 'ready') k.ready++; }); return k; }
    function rowHtml(c) {
      const st = stOf(c);
      return `<button class="au ${st.kind} ${sel.has(c.id) ? 'sel' : ''}" data-c="${c.id}"><span class="ckb">${ic('check')}</span><span class="num au-i">${c.index}</span><span class="au-t"><b>${esc(c.title)}</b><small class="${V6.statusColor(st.kind)}">${st.kind === 'ready' ? ic('play_circle') : ''}${esc(st.text)}</small>${st.kind === 'generating' ? V6.bar(st.progress) : ''}</span></button>`;
    }
    function draw() {
      const n = W.narration.state(); const k = counts();
      $('[data-ban]').innerHTML = banner(n, s.id);
      const fl = [['bm', `From bookmark`, list.filter((c) => c.index - 1 >= bm).length], ['all', 'All', list.length], ['none', 'Not generated', k.none], ['ready', 'Ready', k.ready]];
      $('[data-f]').innerHTML = fl.map(([key, l, c]) => `<button class="chip ${ui.filter === key ? 'on' : ''}" data-fk="${key}">${l} <span class="n">${c}</span></button>`).join('');
      const g = n.generated.find((x) => x.storyId === s.id);
      $('[data-line]').textContent = `New audio uses Bella · ${k.ready} ready${g ? ` · ${g.sizeMb} MB` : ''}`;
      const fc = ui.focus && W.chapter(s.id, ui.focus);
      if (fc) {
        const st = stOf(fc);
        $('[data-focus]').innerHTML = `<section class="card acc fc"><div class="card-p"><span class="kick">Chapter ${fc.index}</span><b>${esc(fc.title)}</b><small class="${V6.statusColor(st.kind)}">${esc(st.text)}</small><div class="row2">${st.kind === 'ready' ? `<button class="btn acc" data-cx="play|${fc.id}">${ic('play_arrow')} Play</button>` : `<button class="btn acc" data-cx="now|${fc.id}">Generate now</button><button class="btn ghost" data-cx="next|${fc.id}">Generate it next</button>`}</div></div></section>`;
      } else $('[data-focus]').innerHTML = '';
      let rows = list;
      if (ui.filter === 'bm') rows = list.filter((c) => c.index - 1 >= bm);
      if (ui.filter === 'none') rows = list.filter((c) => stOf(c).kind === 'none');
      if (ui.filter === 'ready') rows = list.filter((c) => stOf(c).kind === 'ready');
      const sc = root.scrollTop;
      $('[data-rows]').innerHTML = rows.length ? rows.slice(0, 400).map(rowHtml).join('') : V6.empty('graphic_eq', 'Nothing here', 'No chapters match this filter.');
      root.scrollTop = sc;
      $('[data-seln]').textContent = sel.size; root.classList.toggle('selmode', sel.size > 0); document.body.classList.toggle('selecting', sel.size > 0);
    }
    ctx.sub(() => document.body.classList.remove('selecting'));
    function chMenu(c) {
      const st = stOf(c);
      const items = [];
      if (st.kind === 'ready') items.push({ icon: 'play_arrow', label: 'Play', run: () => V6.act.play(s, c.id) });
      if (st.kind === 'none') items.push({ icon: 'bolt', label: 'Generate now', run: () => { W.narration.enqueue(s.id, [c.id], { first: true }); V6.toast('Generating now', { icon: 'graphic_eq' }); } }, { icon: 'playlist_add', label: 'Generate next', run: () => { W.narration.enqueue(s.id, [c.id], { first: true }); V6.toast('Up next in the queue', { icon: 'playlist_add' }); } });
      if (st.kind === 'failed') items.push({ icon: 'refresh', label: 'Retry', run: () => W.narration.retryFailed() });
      if (st.kind === 'queued' || st.kind === 'generating') items.push({ icon: 'close', label: 'Remove from queue', run: () => W.narration.remove(s.id, c.id) });
      if (st.kind === 'ready' || st.kind === 'stale') items.push({ icon: 'autorenew', label: 'Regenerate in Bella', run: () => { W.narration.deleteAudio(s.id, [c.id]); W.narration.enqueue(s.id, [c.id], { first: true }); } });
      items.push({ icon: 'menu_book', label: 'Open in reader', run: () => W.go(`/reader/${s.id}/${c.id}`) });
      if (st.kind === 'ready' || st.kind === 'stale') items.push({ icon: 'delete', label: 'Delete audio', danger: true, run: () => { W.narration.deleteAudio(s.id, [c.id]); V6.toast('Audio deleted', { icon: 'delete' }); } });
      V6.sheet({ title: esc(c.title), sub: esc(st.text), items });
    }
    V6.longPress(root, '[data-c]', (b) => { sel.add(b.dataset.c); draw(); });
    root.addEventListener('click', async (e) => {
      const t = e.target;
      const fk = t.closest('[data-fk]'); if (fk) { ui.filter = fk.dataset.fk; return draw(); }
      const c = t.closest('[data-c]'); if (c) { if (sel.size) { sel.has(c.dataset.c) ? sel.delete(c.dataset.c) : sel.add(c.dataset.c); return draw(); } return chMenu(W.chapter(s.id, c.dataset.c)); }
      const cx = t.closest('[data-cx]'); if (cx) { const [a, id] = cx.dataset.cx.split('|'); if (a === 'play') V6.act.play(s, id); else { W.narration.enqueue(s.id, [id], { first: true }); V6.toast(a === 'now' ? 'Generating now' : 'Up next', { icon: 'graphic_eq' }); } return; }
      const x = t.closest('[data-x]'); if (!x) return;
      const k = x.dataset.x; const none = list.filter((cc) => stOf(cc).kind === 'none');
      if (k === 'select') { list.filter((cc) => cc.index - 1 >= bm).slice(0, 5).forEach((cc) => sel.add(cc.id)); draw(); V6.toast('Long-press or tap rows to change the selection', { icon: 'checklist' }); }
      if (k === 'selx') { sel.clear(); draw(); }
      if (k === 'selall') { list.forEach((cc) => sel.add(cc.id)); draw(); }
      if (k === 'sgen') { const nn = W.narration.enqueue(s.id, [...sel]); V6.toast(`Queued ${nn} chapters`, { icon: 'graphic_eq' }); sel.clear(); draw(); }
      if (k === 'sregen') { W.narration.deleteAudio(s.id, [...sel]); W.narration.enqueue(s.id, [...sel]); V6.toast(`Regenerating ${sel.size} chapters in Bella`, { icon: 'autorenew' }); sel.clear(); draw(); }
      if (k === 'sdel') { if (await V6.dialog({ title: `Delete audio for ${sel.size} chapters?`, ok: 'Delete', danger: true })) { W.narration.deleteAudio(s.id, [...sel]); sel.clear(); draw(); } }
      if (k === 'more') {
        const g = W.narration.state().generated.find((gg) => gg.storyId === s.id);
        V6.sheet({ title: 'More', sub: esc(s.title), items: [
          { icon: 'graphic_eq', label: `Generate everything not generated (${none.length})`, run: () => { W.narration.enqueue(s.id, none.map((cc) => cc.id)); V6.toast(`Queued ${none.length} chapters`, { icon: 'graphic_eq' }); } },
          { icon: 'vertical_align_top', label: 'Generate this story first', run: () => W.narration.storyFirst(s.id) },
          { icon: 'autorenew', label: `Regenerate ${g ? g.chapters : 0} in Bella`, disabled: !g, run: () => V6.toast('Regenerating in Bella', { icon: 'autorenew' }) },
          { icon: 'record_voice_over', label: 'Voice & Speech', run: () => W.go('/settings/tts') },
          { icon: 'queue_music', label: 'Generation queue', run: () => W.go('/narration') },
          { icon: 'remove_from_queue', label: 'Remove from queue', run: () => W.narration.remove(s.id) },
          { sep: true },
          { icon: 'delete', label: 'Delete all audio', danger: true, run: async () => { if (await V6.dialog({ title: 'Delete all audio?', body: `${g ? g.chapters : 0} chapters · ${g ? g.sizeMb : 0} MB`, ok: 'Delete', danger: true })) W.narration.deleteAudio(s.id); } },
        ] });
      }
    });
    const sig = (n) => JSON.stringify([n.queue.filter((j) => j.storyId === s.id).map((j) => j.chapterId + j.status), n.generated.map((g) => g.storyId + g.chapters), n.paused]);
    let last = sig(W.narration.state());
    ctx.sub(W.narration.on((n) => {
      const k = sig(n); if (k !== last) { last = k; return draw(); }
      const bn = $('[data-ban]'); bn.innerHTML = banner(n, s.id);
      const c = n.current; if (!c || c.storyId !== s.id) return;
      const r = root.querySelector(`[data-c="${c.chapterId}"]`); if (r) { r.querySelector('small').textContent = `Generating · ${Math.round(c.progress * 100)}%`; const i = r.querySelector('.pbar i'); if (i) i.style.width = c.progress * 100 + '%'; }
    }));
    draw();
  };
})();
