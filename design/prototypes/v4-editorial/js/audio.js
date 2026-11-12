/* v4 Editorial — Listen: Audio generation hub (ledger) and per-story Chapter audio. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon;
  const N = W.narration;

  const rootTop = (actions) => `<header class="mast"><button class="ib" data-act="contents" aria-label="Contents">${I('menu_book')}</button><div class="wordmark">Webnovel <i>Archiver</i></div>${actions}</header>`;
  A.rootTop = rootTop;
  const chName = (c) => esc(A.chParts(c.title, c.index).name || c.title);

  function pressHTML(st, storyId) {
    const cur = st.current;
    if (!cur || (storyId && cur.storyId !== storyId)) {
      if (storyId) return '';
      return `<div class="press idle"><div class="top"><span class="kicker" style="color:var(--bone-3)">The press is idle</span></div><b class="big">Nothing is generating</b><p class="sub">Queue chapters from any story’s audio page, or let “Keep ahead” fill the queue while you listen.</p></div>`;
    }
    const pct = Math.round(cur.progress * 100);
    const after = st.queue.filter((j) => j.status === 'queued').length;
    const where = st.targetComputer ? `Generating on ${esc(st.targetComputer.name)}` : 'Generating';
    return `<div class="press">
      <div class="top"><span class="kicker">${st.paused ? 'Paused' : where} · ${pct}%</span><button class="btn sm ghost" data-act="pauseGen">${I(st.paused ? 'play_arrow' : 'pause', 'sm')}${st.paused ? 'Resume' : 'Pause'}</button></div>
      <div class="row" style="align-items:flex-end;margin-top:6px"><div class="grow"><b class="big clamp2">${chName(cur)}</b><p class="sub clamp1">${esc(A.shortTitle(cur.story.title))}</p></div><span class="pct">${pct}<small style="font-size:.4em">%</small></span></div>
      <div class="prog thick"><i style="width:${pct}%"></i></div>
      <div class="meta">${cur.sentence} of ${cur.sentences} sentences · ${cur.speed} · ~${cur.etaMin} min left${after ? ` · ${after} chapter${after === 1 ? '' : 's'} after this` : ''}</div>
    </div>`;
  }
  A.pressHTML = pressHTML;
  function jobLine(j, pos) {
    if (j.status === 'generating') return `<span class="st gen">Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences</span><div class="prog" style="margin-top:7px"><i style="width:${(j.progress * 100).toFixed(0)}%"></i></div>`;
    if (j.status === 'failed') return `<span class="st fail">Failed · ${esc(j.error)}</span>`;
    return `<span class="st q">Queued · #${pos} · ${esc(j.voice)}</span>`;
  }

  // ---------- hub ----------
  A.screens.narration = (page) => {
    function render() {
      const st = N.state();
      let pos = 0; const posOf = new Map(); st.queue.forEach((j) => { if (j.status !== 'failed') posOf.set(j, ++pos); });
      page.innerHTML = `${rootTop(A.ib('more_vert', 'qmenu', 'Queue options'))}
        <div class="set-wrap">
          <div class="ledger-h"><div class="kicker">Listen</div><h1 class="disp">Audio <i>generation</i></h1>
          <p class="dek">${st.queuedCount} chapter${st.queuedCount === 1 ? '' : 's'} in the queue${st.failedCount ? ` · <span class="err">${st.failedCount} failed</span>` : ''}</p></div>
          <div class="press-slot">${pressHTML(st)}</div>
          ${A.secHead('i', `Queue · ${st.queue.length} chapters`)}
          <p class="sec-hint">Chapters generate top to bottom. The story you’re listening to always goes first.</p>
          ${st.byStory.length ? st.byStory.map((g, gi) => {
            const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.length - q;
            return `<div class="group">
              <button class="group-h" data-go="/narration/${g.storyId}" style="width:100%;text-align:left"><span class="gc">${A.book(g.story)}</span><span class="grow"><b class="clamp1">${esc(A.shortTitle(g.story.title))}</b><small>${q} queued${f ? ` · <span class="err">${f} failed</span>` : ''}</small></span>${I('chevron_right')}</button>
              ${g.jobs.slice(0, 5).map((j) => `<div class="lrow"><div class="grow"><b class="clamp1">${chName(j)}</b>${jobLine(j, posOf.get(j))}</div>${j.status === 'failed' ? `<button class="btn text sm" data-act="retry">Retry</button>` : `<button class="ib" data-act="rm" data-s="${j.storyId}" data-c="${j.chapterId}" aria-label="Remove from queue">${I('close', 'sm')}</button>`}</div>`).join('')}
              ${g.jobs.length > 5 ? `<div class="more-line">and ${g.jobs.length - 5} more</div>` : ''}
              ${gi > 0 ? `<div class="acts"><button class="btn text sm" data-act="first" data-s="${g.storyId}">${I('vertical_align_top', 'sm')}Generate this story first</button></div>` : ''}
            </div>`;
          }).join('') : '<p class="it faint">The queue is empty.</p>'}
          ${st.batteryRestricted ? `<div class="notice"><span class="ms">battery_saver</span><div class="grow"><b>Generating with the screen off</b><p>Android may stop audio generation when the screen is off. Allow it to keep running in the background.</p><div class="acts"><button class="btn text" data-act="battery">Allow</button></div></div></div>` : ''}
          ${A.secHead('ii', 'Generate on')}
          <p class="sec-hint">A computer on your network renders far faster than this phone.</p>
          <div class="chips wrapy"><button class="chip ${st.target === 'phone' ? 'on' : ''}" data-act="target" data-v="phone">${I('smartphone')}This phone</button>${st.computers.map((c) => `<button class="chip ${st.target === c.id ? 'on' : ''}" data-act="target" data-v="${c.id}">${I('computer')}${esc(c.name)}</button>`).join('')}</div>
          <div style="margin-top:10px">${st.computers.map((c) => `<div class="comp"><span class="ms" style="color:var(--bone-3)">dns</span><div class="grow"><b>${esc(c.name)}</b><small>${esc(c.address)}${st.target === c.id ? ' · <span class="copper">in use</span>' : ''}</small></div><button class="ib" data-act="rmpc" data-v="${c.id}" aria-label="Remove ${esc(c.name)}">${I('delete', 'sm')}</button></div>`).join('')}</div>
          <button class="btn text" data-act="addpc" style="margin:6px 0 0 -10px">${I('add')}Add a computer</button>
          ${A.secHead('iii', 'Keep ahead while listening')}
          <p class="sec-hint">Queue the next chapters automatically as you listen.</p>
          <div class="chips wrapy">${[0, 1, 3, 5, 10].map((n) => `<button class="chip ${st.keepAhead === n ? 'on' : ''}" data-act="ahead" data-v="${n}">${n ? n + (n === 1 ? ' chapter' : ' chapters') : 'Off'}</button>`).join('')}</div>
          <div class="storage">Audio uses <b>${st.storage.used}</b> · ${st.storage.free} free<div class="bar2"><i></i></div></div>
          ${A.secHead('iv', 'Generated audio')}
          <div class="ledger">${st.generated.filter((g) => g.chapters).map((g) => `<button class="lrow gen-row" data-go="/narration/${g.storyId}" style="width:100%;text-align:left"><span class="gc">${A.book(g.story)}</span><span class="grow"><b class="clamp1">${esc(A.shortTitle(g.story.title))}</b><small>${g.chapters} chapter${g.chapters === 1 ? '' : 's'} · ${W.fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)}${/Google/.test(g.voices) ? '' : ' · Kokoro'}</small></span>${I('chevron_right')}</button>`).join('')}</div>
        </div>`;
    }
    render();
    let last = 0;
    A.listen(N, () => { const now = Date.now(); if (now - last < 300) return; last = now; const y = window.scrollY; render(); window.scrollTo(0, y); });
    A.acts(page, {
      contents: () => A.contents(),
      qmenu: () => { const st = N.state(); A.menu({ title: 'Queue <i>options</i>', groups: [{ items: [
        { icon: st.paused ? 'play_arrow' : 'pause', label: st.paused ? 'Resume generating' : 'Pause generating', act: () => N.setPaused(!st.paused) },
        { icon: 'refresh', label: 'Retry failed', sub: st.failedCount ? `${st.failedCount} chapter${st.failedCount === 1 ? '' : 's'}` : 'Nothing failed', act: () => { N.retryFailed(); A.toast('Retrying failed chapters'); } },
        { icon: 'clear_all', label: 'Clear the queue', danger: true, act: () => A.confirm('Clear the queue?', 'Generated audio stays. Queued and failed chapters are removed.', 'Clear', () => { N.clearQueue(); A.toast('Queue cleared'); }, true) }] }] }); },
      pauseGen: () => { const p = N.state().paused; N.setPaused(!p); A.toast(p ? 'Generating resumed' : 'Generating paused'); },
      retry: () => { N.retryFailed(); A.toast('Retrying'); },
      rm: (t) => { N.remove(t.dataset.s, t.dataset.c); A.toast('Removed from the queue'); },
      first: (t) => { N.storyFirst(t.dataset.s); A.toast('Moved to the front of the queue'); },
      battery: () => { N.allowBattery(); A.toast('Background generation allowed'); },
      target: (t) => { N.setTarget(t.dataset.v); A.toast(t.dataset.v === 'phone' ? 'Generating on this phone' : `Generating on ${esc(N.state().computers.find((c) => c.id === t.dataset.v).name)}`); },
      rmpc: (t) => { const c = N.state().computers.find((x) => x.id === t.dataset.v); A.confirm(`Remove ${esc(c.name)}?`, 'You can add it again later with its address and token.', 'Remove', () => N.removeComputer(c.id), true); },
      addpc: () => A.dialog({ title: 'Add a computer', body: `<p>Run the narration server on a computer on your network, then enter its details.</p>
          <label class="field"><span class="lab">Name</span><input class="input" id="pn" placeholder="Living-room PC"></label>
          <label class="field" style="margin-top:12px"><span class="lab">Address</span><input class="input" id="pa" placeholder="192.168.1.30:8790" inputmode="url"></label>
          <label class="field" style="margin-top:12px"><span class="lab">Token</span><input class="input" id="pt" placeholder="Shown by the server" type="password"></label>`,
        actions: [{ label: 'Cancel' }, { label: 'Add', act: (n) => { const name = n.querySelector('#pn').value.trim() || 'New computer'; N.addComputer({ name, address: n.querySelector('#pa').value.trim() || '192.168.1.30:8790' }); A.toast(`${esc(name)} added · connection OK`); } }] }),
      ahead: (t) => { N.setKeepAhead(+t.dataset.v); A.toast(+t.dataset.v ? `Keeping ${t.dataset.v} ahead` : 'Keep ahead off'); },
    });
  };

  // ---------- chapter audio ----------
  A.screens.narrationStory = (page, r) => {
    const s = W.story(r.params.id);
    if (!s) { page.innerHTML = A.bar({ title: 'Not found' }); return; }
    const id = s.id; const all = W.chapters(id);
    const ui = { filter: s.bookmark != null ? 'bm' : 'all', sel: false, picked: new Set(), shown: 120, focus: r.query.focus || (all[s.bookmark != null ? s.bookmark : 0] || {}).id, focusOff: false };
    page.innerHTML = `${A.bar({ title: 'Chapter audio', sub: esc(A.shortTitle(s.title)), actions: `${A.ib('checklist', 'selmode', 'Select chapters')}${A.ib('queue_music', 'queue', 'Generation queue')}${A.ib('more_vert', 'more', 'More')}` })}
      <div class="set-wrap">
        <div class="press-slot"></div>
        <div class="chips ca-chips" style="margin-top:14px"></div>
        <div class="focus-slot"></div>
        <p class="newline"></p>
        <div class="ca-list ledger"></div>
        <div class="ca-more"></div>
      </div>
      <div class="selbar ca-sel" hidden></div>`;
    const $ = (q) => page.querySelector(q);
    const stat = (c) => (c.downloaded ? N.chapterStatus(id, c.id) : { kind: 'off', text: 'Not downloaded' });
    function counts() {
      const ready = all.filter((c) => stat(c).kind === 'ready').length;
      const none = all.filter((c) => c.downloaded && stat(c).kind === 'none').length;
      return { ready, none };
    }
    function stCls(k) { return { generating: 'gen', failed: 'fail', ready: 'ready', stale: 'fail', none: 'none', off: 'none', queued: 'q' }[k] || 'q'; }
    function filtered() {
      let l = all;
      if (ui.filter === 'bm' && s.bookmark != null) l = all.slice(s.bookmark);
      if (ui.filter === 'none') l = all.filter((c) => c.downloaded && stat(c).kind === 'none');
      if (ui.filter === 'ready') l = all.filter((c) => stat(c).kind === 'ready');
      return l;
    }
    function render() {
      const st = N.state(); const cn = counts();
      $('.press-slot').innerHTML = pressHTML(st, id);
      const fromB = s.bookmark != null ? all.length - s.bookmark : all.length;
      $('.ca-chips').innerHTML = [['bm', 'From bookmark', fromB], ['all', 'All', all.length], ['none', 'Not generated', cn.none], ['ready', 'Ready', cn.ready]].map(([k, l, n]) => `<button class="chip ${ui.filter === k ? 'on' : ''}" data-act="filt" data-v="${k}">${l} <span class="c">${n}</span></button>`).join('');
      const fc = !ui.focusOff && all.find((c) => c.id === ui.focus);
      if (fc) {
        const k = stat(fc);
        $('.focus-slot').innerHTML = `<div class="focus"><div class="row"><span class="kicker grow">Chapter ${fc.index}${s.bookmark === fc.index - 1 ? ' · your bookmark' : ''}</span><button class="ib" data-act="unfocus" aria-label="Dismiss" style="margin:-12px -12px -12px 0">${I('close', 'sm')}</button></div><b>${chName(fc)}</b><span class="st ${stCls(k.kind)}">${esc(k.text)}</span>
          <div class="acts">${k.kind === 'ready' ? `<button class="btn sm" data-act="play" data-c="${fc.id}">${I('play_arrow', 'fill')}Play</button>` : ''}${k.kind === 'none' || k.kind === 'failed' || k.kind === 'stale' ? `<button class="btn sm" data-act="now" data-c="${fc.id}">Generate now</button><button class="btn sm ghost" data-act="next" data-c="${fc.id}">Generate it next</button>` : ''}${k.kind === 'off' ? `<button class="btn sm ghost" data-act="dlone" data-c="${fc.id}">Download first</button>` : ''}</div></div>`;
      } else $('.focus-slot').innerHTML = '';
      $('.newline').innerHTML = `New audio uses <b>Bella</b> · ${cn.ready} ready · ${(st.generated.find((g) => g.storyId === id) || { sizeMb: 0 }).sizeMb} MB`;
      const l = filtered();
      $('.ca-list').innerHTML = l.slice(0, ui.shown).map((c) => {
        const k = stat(c); const picked = ui.picked.has(c.id);
        return `<div class="arow ${picked ? 'sel' : ''}" data-c="${c.id}">${ui.sel ? `<span class="check ${picked ? 'on' : ''}">${I('check')}</span>` : ''}<span class="no">${c.index}</span><div class="grow"><b>${chName(c)}</b><span class="st ${stCls(k.kind)}">${esc(k.text)}</span>${k.kind === 'generating' ? `<div class="prog"><i style="width:${(k.progress * 100).toFixed(0)}%"></i></div>` : ''}</div>${k.kind === 'ready' && !ui.sel ? `<button class="ib" data-act="play" data-c="${c.id}" aria-label="Play">${I('play_circle')}</button>` : ''}</div>`;
      }).join('') || '<div class="empty sm"><h3>Nothing here</h3><p>No chapters match this filter.</p></div>';
      $('.ca-more').innerHTML = l.length > ui.shown ? `<button class="btn ghost block" style="margin-top:16px" data-act="moreRows">Show all ${l.length}</button>` : '';
      const sb = $('.ca-sel'); sb.hidden = !ui.sel;
      if (ui.sel) sb.innerHTML = `<button data-act="selGen">${I('graphic_eq')}<span>Generate</span></button><button data-act="selRegen">${I('refresh')}<span>Regenerate</span></button><button data-act="selDel" class="danger">${I('delete')}<span>Delete</span></button>`;
      page.querySelector('.bar .ttl b').textContent = ui.sel ? `${ui.picked.size} selected` : 'Chapter audio';
    }
    render();
    A.listen(N, () => { if (A.route.name === 'narrationStory') render(); });
    const toggleSel = (cid) => { ui.picked.has(cid) ? ui.picked.delete(cid) : ui.picked.add(cid); render(); };
    A.longPress(page, '.arow', (t) => { ui.sel = true; ui.picked.add(t.dataset.c); render(); });
    page.addEventListener('click', (e) => {
      const row = e.target.closest('.arow'); if (!row || e.target.closest('[data-act]')) return;
      if (ui.sel) { toggleSel(row.dataset.c); return; }
      chapterMenu(all.find((c) => c.id === row.dataset.c));
    });
    function chapterMenu(c) {
      const k = stat(c);
      A.menu({ title: chName(c), sub: `Chapter ${c.index} · ${esc(k.text)}`, groups: [{ items: [
        k.kind === 'ready' && { icon: 'play_arrow', label: 'Play', act: () => { W.player.load(id, c.id, { play: true }); A.toast('Playing'); } },
        (k.kind === 'none') && { icon: 'playlist_add', label: 'Generate next', act: () => { N.enqueue(id, [c.id], { first: true }); A.toast('Generating next'); } },
        k.kind === 'failed' && { icon: 'refresh', label: 'Retry', act: () => { N.retryFailed(); A.toast('Retrying'); } },
        (k.kind === 'none' || k.kind === 'failed') && { icon: 'bolt', label: 'Generate now', act: () => { N.remove(id, c.id); N.enqueue(id, [c.id], { first: true }); A.toast('Generating this chapter now'); } },
        (k.kind === 'ready' || k.kind === 'stale') && { icon: 'autorenew', label: 'Regenerate in Bella', act: () => { N.deleteAudio(id, [c.id]); N.enqueue(id, [c.id], { first: true }); A.toast('Regenerating in Bella'); } },
        k.kind === 'off' && { icon: 'download', label: 'Download chapter', act: () => { W.downloads.enqueue(id, [c.id]); A.toast('Downloading chapter ' + c.index); } },
        { icon: 'chrome_reader_mode', label: 'Open in reader', act: () => W.go(`/reader/${id}/${c.id}`) },
        (k.kind === 'ready' || k.kind === 'stale') && { icon: 'delete', label: 'Delete audio', danger: true, act: () => { N.deleteAudio(id, [c.id]); A.toast('Audio deleted'); } },
      ] }] });
    }
    const picked = () => [...ui.picked];
    const endSel = () => { ui.sel = false; ui.picked.clear(); render(); };
    A.acts(page, {
      selmode: () => { ui.sel = !ui.sel; ui.picked.clear(); render(); },
      queue: () => W.go('/narration'),
      more: () => {
        const cn = counts(); const readyIds = all.filter((c) => stat(c).kind === 'ready').map((c) => c.id);
        const noneIds = all.filter((c) => c.downloaded && stat(c).kind === 'none').map((c) => c.id);
        A.menu({ title: 'Chapter <i>audio</i>', sub: esc(A.shortTitle(s.title)), groups: [
          { items: [
            { icon: 'library_music', label: `Generate everything not generated (${cn.none})`, act: () => { const n = N.enqueue(id, noneIds); A.toast(`Queued ${n} chapters`); } },
            { icon: 'vertical_align_top', label: 'Generate this story first', act: () => { N.storyFirst(id); A.toast('This story goes first'); } },
            { icon: 'autorenew', label: `Regenerate ${readyIds.length} in Bella`, act: () => { N.deleteAudio(id, readyIds); N.enqueue(id, readyIds); A.toast(`Regenerating ${readyIds.length} chapters`); } },
          ] },
          { items: [
            { icon: 'record_voice_over', label: 'Voice & Speech', act: () => W.go('/settings/tts') },
            { icon: 'queue_music', label: 'Generation queue', act: () => W.go('/narration') },
            { icon: 'playlist_remove', label: 'Remove from queue', act: () => { N.remove(id); A.toast('Removed this story from the queue'); } },
            { icon: 'delete', label: 'Delete all audio', danger: true, act: () => A.confirm('Delete all audio?', `Generated audio for “${esc(A.shortTitle(s.title))}” will be removed. Chapters stay downloaded.`, 'Delete', () => { N.deleteAudio(id); A.toast('All audio deleted'); }, true) },
          ] }] });
      },
      filt: (t) => { ui.filter = t.dataset.v; ui.shown = 120; render(); },
      unfocus: () => { ui.focusOff = true; render(); },
      play: (t) => { W.player.load(id, t.dataset.c, { play: true }); A.toast('Playing'); },
      now: (t) => { N.remove(id, t.dataset.c); N.enqueue(id, [t.dataset.c], { first: true }); A.toast('Generating now'); },
      next: (t) => { N.enqueue(id, [t.dataset.c], { first: true }); A.toast('Generating it next'); },
      dlone: (t) => { W.downloads.enqueue(id, [t.dataset.c]); A.toast('Downloading'); },
      moreRows: () => { ui.shown = 100000; render(); },
      selGen: () => { const n = N.enqueue(id, picked().filter((c) => W.chapter(id, c).downloaded)); A.toast(`Queued ${n} chapters`); endSel(); },
      selRegen: () => { N.deleteAudio(id, picked()); N.enqueue(id, picked()); A.toast(`Regenerating ${ui.picked.size}`); endSel(); },
      selDel: () => { N.deleteAudio(id, picked()); A.toast(`Deleted audio for ${ui.picked.size} chapters`); endSel(); },
      pauseGen: () => { const p = N.state().paused; N.setPaused(!p); },
    });
  };
})();
