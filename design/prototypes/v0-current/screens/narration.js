/* v0 Audio generation hub + Chapter audio (per story). */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon, N = W.narration;
  const KEEP = [0, 1, 3, 5, 10];

  function banner(st, storyId) {
    const c = st.current;
    if (!c || (storyId && c.storyId !== storyId)) return storyId ? '' : `<div class="nb idle">${st.paused && st.queuedCount ? 'Generating is paused' : 'Nothing is generating'}</div>`;
    const pct = Math.round(c.progress * 100);
    const where = st.targetComputer ? `Generating on ${st.targetComputer.name}` : 'Generating';
    const after = st.queuedCount - 1;
    return `<div class="nb"><div class="nb-top"><div style="flex:1;min-width:0"><b>${st.paused ? 'Paused' : where} · ${pct}%</b><div class="nb-sub">${esc(c.title)} · ${esc(c.story.title)}</div></div>
      <button class="btn text sm nb-btn" data-a="pause">${ic(st.paused ? 'play_arrow' : 'pause')}${st.paused ? 'Resume' : 'Pause'}</button></div>
      <div class="bar nb-bar"><i style="width:${pct}%"></i></div>
      <div class="nb-sub">${c.sentence} of ${c.sentences} sentences · ${c.speed} · ~${c.etaMin} min left${after > 0 ? ` · ${after} chapter${after === 1 ? '' : 's'} after this` : ''}</div></div>`;
  }

  // ---------- hub ----------
  A.register('narration', (route, ctx) => {
    const el = W.el(`<div>${A.appBar({ title: 'Audio generation', actions: [{ id: 'qmenu', icon: 'more_vert', label: 'Queue options' }] })}<div class="scroll pb-mini" data-keep data-sc></div></div>`);
    const sc = el.querySelector('[data-sc]');
    function draw() {
      const st = N.state();
      const jobRow = (j) => `<div class="job"><span class="dot ${j.status}"></span><div><b>${esc(j.title)}</b><span class="${j.status === 'failed' ? 'red' : j.status === 'generating' ? 'gold' : 'muted'}">${j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences` : j.status === 'failed' ? `Failed · ${esc(j.error)}` : `Queued · ${esc(j.voice)}`}</span></div></div>`;
      const queue = st.byStory.length ? `<div class="sec-label">Queue · ${st.queue.length} chapter${st.queue.length === 1 ? '' : 's'}</div><div class="sec-help">Chapters generate in this order. Stories you are listening to keep a few chapters ready ahead of you.</div>
        ${st.byStory.map((g) => { const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.length - q; return `<div class="card nq-card"><button class="nq-head" data-story="${g.storyId}"><b>${esc(g.story.title)}</b><span class="muted">${q} queued${f ? ` · <span class="red">${f} failed</span>` : ''}</span></button>
          ${g.jobs.slice(0, 5).map(jobRow).join('')}${g.jobs.length > 5 ? `<div class="muted" style="margin:6px 0 0 22px">and ${g.jobs.length - 5} more</div>` : ''}
          <div class="btn-row" style="margin-top:6px"><button class="btn text sm" data-first="${g.storyId}" style="padding:0">Generate this story first</button><button class="btn text sm" data-rm="${g.storyId}">Remove</button></div></div>`; }).join('')}`
        : `<div class="sec-label">Queue</div><div class="sec-help">Nothing is queued. Open a story's Chapter audio (Details → More options → Narration audio, or Read aloud) to generate chapters.</div>`;
      sc.innerHTML = `<div class="pad">${banner(st)}
        ${queue}
        ${st.batteryRestricted ? `<div class="card" style="margin-top:12px"><h3>Generating with the screen off</h3><div class="card-sub">Android pauses background work while the phone sleeps. Allow this app to run without battery restrictions so queued chapters keep generating with the screen off.</div><button class="btn tonal full" data-a="battery" style="margin-top:16px">Allow</button></div>` : ''}
        <div class="sec-label">Generate on</div><div class="sec-help">Kokoro chapters can generate on one of your computers over Wi-Fi or Tailscale, several times faster than on the phone and with the same voice. The audio is sent back and plays from this phone.</div>
        ${st.computers.length ? `<div class="chip-row" style="margin-bottom:8px"><button class="chip${st.target === 'phone' ? ' sel' : ''}" data-target="phone">${st.target === 'phone' ? ic('check') : ''}This phone</button>${st.computers.map((c) => `<button class="chip${st.target === c.id ? ' sel' : ''}" data-target="${c.id}">${st.target === c.id ? ic('check') : ''}${esc(c.name)}</button>`).join('')}</div>
          ${st.computers.map((c) => `<div class="row" style="padding:4px 0">${ic('computer')}<div class="rt"><b style="font-size:15px">${esc(c.name)}</b><span>${esc(c.address)}</span></div><button class="icon-btn" data-pc="${c.id}" aria-label="Computer options">${ic('more_vert')}</button></div>`).join('')}` : ''}
        <button class="btn text full" data-a="addpc">${ic('add')}Add a computer</button>
        <div class="sec-label">Keep ahead while listening</div><div class="sec-help">While a story plays, the chapters after it are queued so they are ready in time.</div>
        <div class="chip-row">${KEEP.map((n) => `<button class="chip${st.keepAhead === n ? ' sel' : ''}" data-keep="${n}">${st.keepAhead === n ? ic('check') : ''}${n === 0 ? 'Off' : n === 1 ? '1 chapter' : n + ' chapters'}</button>`).join('')}</div>
        <div class="muted" style="margin:14px 0 4px">Audio uses ${st.storage.used} · ${st.storage.free} free</div>
        <div class="sec-label">Generated audio</div>
        ${st.generated.length ? st.generated.map((g) => `<button class="row" data-story="${g.storyId}" style="padding:8px 12px">${ic('headphones')}<div class="rt"><b style="font-size:16px">${esc(g.story.title)}</b><span>${g.chapters} chapters · ${W.fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)}${g.voices === 'Bella' ? ' · Kokoro' : ''}</span></div></button>`).join('') : '<div class="sec-help">No generated audio yet.</div>'}
      </div>`;
    }
    ctx.sub(N.on(() => draw()));
    sc.addEventListener('click', (e) => {
      const t = (sel) => e.target.closest(sel);
      let x;
      if ((x = t('[data-first]'))) { N.storyFirst(x.dataset.first); A.toast('Moved to the front of the queue'); return; }
      if ((x = t('[data-rm]'))) { N.remove(x.dataset.rm); A.toast('Removed from the queue'); return; }
      if ((x = t('[data-story]'))) { W.go('/narration/' + x.dataset.story); return; }
      if ((x = t('[data-target]'))) { N.setTarget(x.dataset.target); return; }
      if ((x = t('[data-keep]'))) { N.setKeepAhead(+x.dataset.keep); return; }
      if ((x = t('[data-pc]'))) { const id = x.dataset.pc; A.menu({ title: N.state().computers.find((c) => c.id === id).name, wide: false, sections: [{ items: [{ label: 'Test connection', fn: () => A.toast('Connected · Kokoro ready · 9.6× real time') }, { label: 'Remove', danger: true, fn: () => N.removeComputer(id) }] }] }); return; }
      if ((x = t('[data-a]'))) {
        const k = x.dataset.a;
        if (k === 'pause') N.setPaused(!N.state().paused);
        if (k === 'battery') { N.allowBattery(); A.toast('Battery restrictions removed'); }
        if (k === 'addpc') A.dialog({ title: 'Add a computer', body: `<div class="field-label">Name</div><div class="field"><input placeholder="Gaming PC"></div><div class="field-label">Address</div><div class="field"><input placeholder="192.168.1.20:8790"></div><div class="field-label">Token</div><div class="field"><input placeholder="Paste the token shown by the server"></div>`, actions: [{ label: 'Cancel' }, { label: 'Add', fn: (s) => { const v = [...s.querySelectorAll('input')].map((i) => i.value.trim()); N.addComputer({ name: v[0] || 'New computer', address: v[1] || '192.168.1.42:8790' }); A.toast('Computer added'); } }] });
      }
    });
    el.addEventListener('click', (e) => {
      if (!e.target.closest('[data-a="qmenu"]')) return;
      const st = N.state();
      A.menu({ title: 'Queue options', wide: false, sections: [{ items: [
        { label: st.paused ? 'Resume generating' : 'Pause generating', fn: () => N.setPaused(!st.paused) },
        { label: `Retry failed${st.failedCount ? ` (${st.failedCount})` : ''}`, fn: () => { N.retryFailed(); A.toast('Retrying failed chapters'); } },
        { label: 'Clear the queue', danger: true, fn: () => A.confirm({ title: 'Clear the queue?', message: 'Queued chapters will not be generated. Audio already generated is kept.', ok: 'Clear', danger: true, fn: () => N.clearQueue() }) },
      ] }] });
    });
    draw();
    return { el };
  });

  // ---------- chapter audio (per story) ----------
  const filt = {};
  A.register('narrationStory', (route, ctx) => {
    const s = W.story(route.params.id);
    if (!s) return { el: W.el(`<div>${A.appBar({ title: 'Not found' })}</div>`) };
    const chs = W.chapters(s.id).filter((c) => c.downloaded);
    const bm = Math.max(0, (s.bookmark || 1) - 1);
    let filter = filt[s.id] || 'bookmark', sel = route.query.select ? new Set(chs.slice(bm, bm + 2).map((c) => c.id)) : null, shown = 0, rows = [], io = null;
    const focusId = route.query.ch;
    const el = W.el(`<div><div data-bar></div><div class="scroll pb-mini" data-keep data-sc><div class="pad capped" data-top></div><div class="capped" data-list></div><div class="sentinel" data-sentinel></div></div><div data-foot></div></div>`);
    const $ = (q) => el.querySelector(q);
    const statusOf = (c) => N.chapterStatus(s.id, c.id);
    const counts = () => { let ready = 0, none = 0; chs.forEach((c) => { const k = statusOf(c).kind; if (k === 'ready') ready++; else if (k === 'none') none++; }); return { ready, none }; };
    function bar() {
      $('[data-bar]').innerHTML = sel ? `<header class="appbar"><button class="icon-btn" data-a="selx" aria-label="Close">${ic('close')}</button><div class="ab-title" style="padding-left:4px"><h1>${sel.size} selected</h1></div><button class="btn text" data-a="selall">Select all</button></header>`
        : A.appBar({ title: 'Chapter audio', sub: s.title, actions: [{ id: 'selmode', icon: 'checklist', label: 'Select chapters' }, { id: 'queue', icon: 'queue_music', label: 'Generation queue' }, { id: 'more', icon: 'more_vert', label: 'More' }] });
      $('[data-foot]').innerHTML = sel ? `<div class="bottom-bar"><button class="btn tonal" data-a="sgen">Generate</button><button class="btn tonal" data-a="sregen">Regenerate</button><button class="btn filled-danger" data-a="sdel">Delete</button></div>` : '';
    }
    function top() {
      const st = N.state(); const c = counts(); const g = st.generated.find((x) => x.storyId === s.id);
      const chip = (k, l) => `<button class="chip${filter === k ? ' sel' : ''}" data-f="${k}">${filter === k ? ic('check') : ''}${l}</button>`;
      const fc = focusId && chs.find((x) => x.id === focusId);
      const fst = fc && statusOf(fc);
      $('[data-top]').innerHTML = `${banner(st, s.id)}
        <div class="chip-row" style="margin:${st.current && st.current.storyId === s.id ? 14 : 0}px 0 12px">${chip('bookmark', `From bookmark (${chs.length - bm})`)}${chip('all', `All (${chs.length})`)}${chip('none', `Not generated (${c.none})`)}${chip('ready', `Ready (${c.ready})`)}</div>
        ${fc ? `<div class="card" style="margin-bottom:12px"><h3>${esc(fc.title)}</h3><div class="st-${fst.kind}">${esc(fst.text)}</div><div class="btn-row" style="margin-top:10px">${fst.kind === 'ready' ? `<button class="btn filled sm" data-play="${fc.id}">${ic('play_arrow')}Play</button>` : `<button class="btn filled sm" data-now="${fc.id}">Generate now</button><button class="btn text sm" data-next="${fc.id}">Generate it next</button>`}</div></div>` : ''}
        <div class="muted" style="margin-bottom:8px">New audio uses Bella · ${c.ready} ready · ${g ? g.sizeMb : 0} MB</div>`;
    }
    function list() {
      if (filter === 'bookmark') return chs.slice(bm);
      if (filter === 'none') return chs.filter((c) => statusOf(c).kind === 'none');
      if (filter === 'ready') return chs.filter((c) => statusOf(c).kind === 'ready');
      return chs;
    }
    function rowHtml(c) {
      const t = statusOf(c); const on = sel && sel.has(c.id);
      return `<div class="au-row${on ? ' selected' : ''}" data-cid="${c.id}">${sel ? `<span class="cbox${on ? ' on' : ''}">${on ? ic('check') : ''}</span>` : ''}<div class="au-t"><b>${esc(c.title)}</b><span class="st-${t.kind}" data-st>${esc(t.text)}</span>${t.kind === 'generating' ? `<div class="bar" style="margin-top:6px"><i style="width:${Math.round(t.progress * 100)}%"></i></div>` : ''}</div></div>`;
    }
    function more() { const next = rows.slice(shown, shown + 60); shown += next.length; $('[data-list]').insertAdjacentHTML('beforeend', next.map(rowHtml).join('')); }
    function drawList() {
      rows = list(); shown = 0; $('[data-list]').innerHTML = rows.length ? '' : `<div class="empty" style="min-height:0"><b>No chapters here</b><span>Try another filter.</span></div>`; more();
      if (io) io.disconnect(); io = new IntersectionObserver((e) => { if (e[0].isIntersecting) more(); }, { root: $('[data-sc]'), rootMargin: '600px' }); io.observe($('[data-sentinel]'));
    }
    function patchRows() { el.querySelectorAll('.au-row').forEach((r) => { const c = chs.find((x) => x.id === r.dataset.cid); const n = W.el(rowHtml(c)); r.replaceWith(n); }); }
    bar(); top(); drawList();
    ctx.sub(N.on(() => { top(); patchRows(); }));
    function chapterMenu(c) {
      const t = statusOf(c); const items = [];
      if (t.kind === 'ready') items.push({ label: 'Play', fn: () => { P().load(s.id, c.id, { play: true }); W.go('/player'); } });
      if (t.kind === 'none') items.push({ label: 'Generate next', fn: () => { N.enqueue(s.id, [c.id], { first: true }); A.toast('Generating next'); } }, { label: 'Generate now', fn: () => { N.enqueue(s.id, [c.id], { first: true }); A.toast('Generating now'); } });
      if (t.kind === 'failed') items.push({ label: 'Retry', fn: () => { N.retryFailed(); A.toast('Retrying'); } });
      if (t.kind === 'queued') items.push({ label: 'Generate now', fn: () => { N.remove(s.id, c.id); N.enqueue(s.id, [c.id], { first: true }); } }, { label: 'Remove from queue', fn: () => N.remove(s.id, c.id) });
      if (t.kind === 'ready' || t.kind === 'stale') items.push({ label: 'Regenerate in Bella', fn: () => { N.deleteAudio(s.id, [c.id]); N.enqueue(s.id, [c.id]); A.toast('Queued to regenerate'); } });
      items.push({ label: 'Open in reader', fn: () => W.go(`/reader/${s.id}/${c.id}`) });
      if (t.kind === 'ready' || t.kind === 'stale') items.push({ label: 'Delete audio', danger: true, fn: () => { N.deleteAudio(s.id, [c.id]); A.toast('Audio deleted'); } });
      A.menu({ title: c.title, wide: false, sections: [{ items }] });
    }
    const P = () => W.player;
    function moreMenu() {
      const c = counts(); const ready = c.ready;
      A.menu({ sections: [{ items: [
        { label: `Generate everything not generated (${c.none})`, fn: () => { const n = N.enqueue(s.id, chs.filter((x) => statusOf(x).kind === 'none').map((x) => x.id)); A.toast(`Queued ${n} chapters`); } },
        { label: 'Generate this story first', fn: () => { N.storyFirst(s.id); A.toast('Moved to the front of the queue'); } },
        { label: `Regenerate ${ready} in Bella`, fn: () => A.confirm({ title: 'Regenerate audio?', message: `${ready} chapters will be generated again in Bella.`, ok: 'Regenerate', fn: () => { const ids = chs.filter((x) => statusOf(x).kind === 'ready').map((x) => x.id); N.deleteAudio(s.id, ids); N.enqueue(s.id, ids); } }) },
        { label: 'Voice & Speech', fn: () => W.go('/settings/tts') },
        { label: 'Generation queue', fn: () => W.go('/narration') },
        { label: 'Remove from queue', fn: () => { N.remove(s.id); A.toast('Removed from the queue'); } },
      ] }, { divider: true, items: [{ label: 'Delete all audio', danger: true, fn: () => A.confirm({ title: 'Delete all audio?', message: `Delete every generated chapter for ${esc(s.title)}?`, ok: 'Delete', danger: true, fn: () => { N.deleteAudio(s.id); A.toast('Audio deleted'); } }) }] }] });
    }
    A.longPress(el, '.au-row', (r) => { if (!sel) { sel = new Set([r.dataset.cid]); bar(); patchRows(); } });
    el.addEventListener('click', (e) => {
      const t = (q) => e.target.closest(q); let x;
      if ((x = t('[data-f]'))) { filter = filt[s.id] = x.dataset.f; top(); drawList(); return; }
      if ((x = t('[data-play]'))) { W.player.load(s.id, x.dataset.play, { play: true }); W.go('/player'); return; }
      if ((x = t('[data-now]')) || (x = t('[data-next]'))) { N.enqueue(s.id, [x.dataset.now || x.dataset.next], { first: true }); A.toast('Queued'); return; }
      if ((x = t('.au-row'))) { const c = chs.find((y) => y.id === x.dataset.cid); if (sel) { sel.has(c.id) ? sel.delete(c.id) : sel.add(c.id); if (!sel.size) sel = null; bar(); patchRows(); } else chapterMenu(c); return; }
      if ((x = t('[data-a],[data-act]'))) {
        const k = x.dataset.a || x.dataset.act; const ids = sel ? [...sel] : [];
        if (k === 'selmode') { sel = new Set(); bar(); patchRows(); }
        if (k === 'selx') { sel = null; bar(); patchRows(); }
        if (k === 'selall') { rows.forEach((c) => sel.add(c.id)); bar(); patchRows(); }
        if (k === 'queue') W.go('/narration');
        if (k === 'more') moreMenu();
        if (k === 'pause') N.setPaused(!N.state().paused);
        if (k === 'sgen') { const n = N.enqueue(s.id, ids); A.toast(`Queued ${n} chapters`); sel = null; bar(); patchRows(); }
        if (k === 'sregen') { N.deleteAudio(s.id, ids); N.enqueue(s.id, ids); A.toast(`Regenerating ${ids.length} chapters`); sel = null; bar(); patchRows(); }
        if (k === 'sdel') A.confirm({ title: `Delete audio for ${ids.length} chapters?`, message: 'The text stays downloaded.', ok: 'Delete', danger: true, fn: () => { N.deleteAudio(s.id, ids); sel = null; bar(); patchRows(); } });
      }
    });
    return { el, destroy: () => io && io.disconnect() };
  });
})();
