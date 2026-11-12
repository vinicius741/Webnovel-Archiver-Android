/* Audio generation hub + Chapter audio per story. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const N = WNA.narration;
  const KIND = { none: ['radio_button_unchecked', 'dim'], queued: ['schedule', 'muted'], generating: ['graphic_eq', 'accent'], failed: ['error', 'err'], ready: ['check_circle', 'accent'], stale: ['sync_problem', 'err'] };
  const jobText = (j) => (j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}%` : j.status === 'failed' ? `Failed · ${j.error}` : `Queued · ${j.voice}`);

  function liveCard(n, storyId) {
    const c = n.current;
    if (!c || (storyId && c.storyId !== storyId)) return storyId ? '' : `<div class="card gen-idle"><span class="hint-ic">${ic('bedtime')}</span><div class="grow"><b class="t-tm">Nothing is generating</b><p class="t-bs muted">Queue chapters from a story’s Chapter audio, or turn on Keep ahead.</p></div></div>`;
    const after = n.queuedCount - 1;
    return `<div class="card tonal gen-live ${n.paused ? 'paused' : ''}">
      <div class="row" style="gap:14px"><div class="gen-ring">${V2.ring(c.progress, 76, 5)}<span class="gen-cov">${V2.cover(c.story, { w: 44, h: 44, label: false })}</span></div>
        <div class="grow"><b class="t-tm gen-pct">${n.paused ? 'Paused' : 'Generating'} · ${Math.round(c.progress * 100)}%</b><span class="t-bs gen-where">${n.targetComputer ? `on ${esc(n.targetComputer.name)}` : 'on this phone'}</span><span class="t-bs nowrap gen-what">${esc(c.title)} · ${esc(V2.shortTitle(c.story.title))}</span></div>
        <button class="ib tonal" data-a="pause" aria-label="${n.paused ? 'Resume' : 'Pause'}">${ic(n.paused ? 'play_arrow' : 'pause', 'fill')}</button></div>
      ${V2.wavy(c.progress, n.paused ? 'flat' : '')}
      <p class="t-bs gen-det">${c.sentence} of ${c.sentences} sentences · ${c.speed} · ~${c.etaMin} min left${after > 0 ? ` · ${V2.plural(after, 'chapter')} after this` : ''}</p></div>`;
  }
  function patchLive(root, n) {
    const c = n.current; const card = root.querySelector('.gen-live'); if (!c || !card) return false;
    if (card.dataset.job !== c.chapterId) return false;
    V2.setRing(card.querySelector('.ring'), c.progress); V2.setWavy(card.querySelector('.wavy'), c.progress);
    card.querySelector('.gen-pct').textContent = `${n.paused ? 'Paused' : 'Generating'} · ${Math.round(c.progress * 100)}%`;
    const after = n.queuedCount - 1;
    card.querySelector('.gen-det').textContent = `${c.sentence} of ${c.sentences} sentences · ${c.speed} · ~${c.etaMin} min left${after > 0 ? ` · ${V2.plural(after, 'chapter')} after this` : ''}`;
    return true;
  }
  const setH = (node, h) => { if (node._h !== h) { node._h = h; node.innerHTML = h; } };
  const tagLive = (root, n) => { const card = root.querySelector('.gen-live'); if (card && n.current) card.dataset.job = n.current.chapterId; };

  // ---------- hub ----------
  V2.screens.narration = (ctx) => {
    const page = ctx.el;
    page.innerHTML = `${V2.bar({ title: 'Audio generation', acts: V2.ib('more_vert', 'more', 'Queue options') })}<div class="scroll"><div class="maxw">${V2.large('Audio generation', 'Kokoro voices, made ahead of time')}<div class="nh"></div></div></div>`;
    V2.collapse(page);
    const box = page.querySelector('.nh');
    let sig = '';
    function render(n) {
      const s2 = JSON.stringify([n.queue.map((j) => j.chapterId + j.status), n.paused, n.keepAhead, n.target, n.batteryRestricted, n.computers.map((c) => c.id), n.generated.map((g) => g.chapters)]);
      if (s2 === sig) { patchLive(box, n); return; }
      sig = s2;
      box.innerHTML = `<div class="nh-a"><div class="wrap">${liveCard(n)}</div>
        <div class="sec-h"><h2>Queue · ${V2.plural(n.queuedCount, 'chapter')}</h2>${n.failedCount ? `<button class="btn text s" data-a="retry">Retry failed</button>` : ''}</div>
        <p class="sec-hint">${n.queue.length ? 'Chapters generate in this order. The story you’re listening to always goes first.' : 'Nothing is queued. Open a story’s Chapter audio (story → More → Narration audio, or Read aloud) to generate chapters.'}</p>
        <div class="wrap col" style="gap:10px">${n.byStory.map((g, gi) => { const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.length - q; return `<section class="card q-story">
          <button class="row q-h" data-story="${g.storyId}">${V2.cover(g.story, { w: 40, h: 60 })}<span class="grow"><b class="clamp2">${esc(g.story.title)}</b><span class="t-bs muted">${q} queued${f ? ` · <span class="err">${f} failed</span>` : ''}</span></span>${ic('chevron_right')}</button>
          <div class="q-jobs">${g.jobs.slice(0, 5).map((j) => `<button class="q-job ${j.status}" data-job="${g.storyId}|${j.chapterId}"><span class="ic ${KIND[j.status === 'generating' ? 'generating' : j.status === 'failed' ? 'failed' : 'queued'][1]}">${KIND[j.status === 'generating' ? 'generating' : j.status === 'failed' ? 'failed' : 'queued'][0]}</span><span class="grow"><b class="nowrap">${esc(j.title)}</b><span class="t-bs ${j.status === 'failed' ? 'err' : 'muted'}">${esc(jobText(j))}</span></span></button>`).join('')}
          ${g.jobs.length > 5 ? `<p class="t-bs dim" style="padding:4px 4px 0 40px">and ${g.jobs.length - 5} more</p>` : ''}</div>
          ${gi > 0 ? `<button class="btn tonal s" data-first="${g.storyId}">${ic('vertical_align_top')}Generate this story first</button>` : ''}</section>`; }).join('')}</div>
        ${n.batteryRestricted ? `<div class="wrap" style="margin-top:12px"><section class="card warn bat"><div class="row"><span class="hint-ic" style="background:color-mix(in oklch,var(--warn) 30%,transparent);color:var(--warn)">${ic('battery_alert')}</span><h3 class="grow">Generating with the screen off</h3></div><p class="t-bs muted">Android pauses background work while the phone sleeps. Allow this app to run without battery restrictions so queued chapters keep generating with the screen off.</p><button class="btn filled" data-a="battery">Allow</button></section></div>` : ''}
        </div><div class="nh-b"><div class="sec-h"><h2>Generate on</h2></div>
        <p class="sec-hint">Kokoro chapters can generate on one of your computers over Wi-Fi or Tailscale, several times faster than on the phone and with the same voice.</p>
        <div class="wrap"><div class="chips">${[{ id: 'phone', name: 'This phone', icon: 'smartphone' }, ...n.computers.map((c) => ({ ...c, icon: 'computer' }))].map((c) => `<button class="chip ${n.target === c.id ? 'sel' : ''}" data-target="${c.id}">${ic(n.target === c.id ? 'check' : c.icon, 'chk')}${esc(c.name)}</button>`).join('')}</div></div>
        <div class="seglist" style="margin-top:12px">${n.computers.map((c) => `<div class="li"><span class="lead">${ic('computer')}</span><span class="tx"><b>${esc(c.name)}</b><span>${esc(c.address)}${n.target === c.id ? ' · in use' : ''}</span></span>${V2.ib('more_vert', 'pc', 'Computer options').replace('data-a="pc"', `data-pc="${c.id}"`)}</div>`).join('')}
          <button class="li" data-a="addpc"><span class="lead plain">${ic('add')}</span><span class="tx"><b class="accent">Add a computer</b></span></button></div>
        <div class="sec-h"><h2>Keep ahead while listening</h2></div>
        <p class="sec-hint">While a story plays, the chapters after it are queued so they’re ready in time.</p>
        <div class="wrap"><div class="seg">${[0, 1, 3, 5, 10].map((k) => `<button class="${n.keepAhead === k ? 'sel' : ''}" data-keep="${k}">${ic('check')}${k ? k : 'Off'}</button>`).join('')}</div>
          <div class="store"><div class="row t-bs muted"><span class="grow">Audio uses ${n.storage.used}</span><span>${n.storage.free} free</span></div><div class="lin"><i style="width:6%"></i></div></div></div>
        <div class="sec-h"><h2>Generated audio</h2></div>
        <div class="seglist">${n.generated.map((g) => `<button class="li" data-story="${g.storyId}">${V2.cover(g.story, { w: 40, h: 40, label: false })}<span class="tx"><b class="nowrap">${esc(g.story.title)}</b><span>${V2.plural(g.chapters, 'chapter')} · ${fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)}${g.voices.includes('Bella') ? ' · Kokoro' : ''}</span></span>${ic('chevron_right')}</button>`).join('') || `<div class="li"><span class="tx"><span>No audio yet.</span></span></div>`}</div></div>`;
      tagLive(box, n);
    }
    ctx.sub(N.on((n) => { if (page.isConnected) render(n); }));
    render(N.state());
    page.addEventListener('click', async (e) => {
      const t = (sel) => e.target.closest(sel);
      let b;
      if ((b = t('[data-first]'))) { N.storyFirst(b.dataset.first); V2.snack(`${V2.shortTitle(WNA.story(b.dataset.first).title)} moved to the front`); return; }
      if ((b = t('[data-job]'))) { const [sid, cid] = b.dataset.job.split('|'); jobMenu(sid, cid); return; }
      if ((b = t('[data-story]'))) { V2.go('/narration/' + b.dataset.story); return; }
      if ((b = t('[data-target]'))) { N.setTarget(b.dataset.target); V2.snack(b.dataset.target === 'phone' ? 'Generating on this phone' : `Generating on ${N.state().targetComputer.name}`); return; }
      if ((b = t('[data-keep]'))) { N.setKeepAhead(+b.dataset.keep); return; }
      if ((b = t('[data-pc]'))) { const c = N.state().computers.find((x) => x.id === b.dataset.pc); V2.menu({ title: c.name, sub: esc(c.address), items: [{ icon: 'wifi_tethering', label: 'Test connection', onClick: async () => { V2.snack(`Connecting to ${c.name}…`); await V2.wait(1200); V2.snack(`${c.name} is ready · 9.6× real time`); } }, { icon: 'check', label: 'Generate on this computer', onClick: () => N.setTarget(c.id) }, { icon: 'delete', label: 'Remove', danger: true, onClick: () => { N.removeComputer(c.id); V2.snack(`Removed ${c.name}`); } }] }); return; }
      if (!(b = t('[data-a]'))) return;
      const n = N.state();
      switch (b.dataset.a) {
        case 'pause': N.setPaused(!n.paused); break;
        case 'retry': N.retryFailed(); V2.snack('Retrying failed chapters'); break;
        case 'battery': N.allowBattery(); V2.snack('Battery restrictions removed'); break;
        case 'addpc': { const v = await V2.prompt({ title: 'Add a computer', sub: 'Run the narration server on your computer, then enter its details.', fields: [{ label: 'Name', placeholder: 'Desktop' }, { label: 'Address', placeholder: '192.168.1.20:8790' }, { label: 'Token', placeholder: 'Shown by the server', type: 'password' }], ok: 'Add' }); if (v && v[0] && v[1]) { N.addComputer({ name: v[0], address: v[1] }); V2.snack(`Added ${v[0]}`); } else if (v) V2.snack('Name and address are required'); break; }
        case 'more': V2.menu({ title: 'Queue options', items: [{ icon: n.paused ? 'play_arrow' : 'pause', label: n.paused ? 'Resume generating' : 'Pause generating', onClick: () => N.setPaused(!n.paused) }, { icon: 'replay', label: 'Retry failed', sub: n.failedCount ? V2.plural(n.failedCount, 'chapter') : 'Nothing failed', disabled: !n.failedCount, onClick: () => N.retryFailed() }, { icon: 'clear_all', label: 'Clear the queue', danger: true, disabled: !n.queue.length, onClick: async () => { if (await V2.confirm({ title: 'Clear the queue?', text: `${V2.plural(n.queue.length, 'chapter')} will stop generating. Audio that’s already made stays.`, ok: 'Clear', danger: true, icon: 'clear_all' })) { N.clearQueue(); V2.snack('Queue cleared'); } } }] }); break;
      }
    });
  };
  function jobMenu(sid, cid) {
    const c = WNA.chapter(sid, cid); const stt = N.chapterStatus(sid, cid);
    V2.menu({ title: c.title, sub: esc(stt.text), items: [
      stt.kind === 'failed' && { icon: 'replay', label: 'Retry', onClick: () => N.retryFailed() },
      stt.kind === 'queued' && { icon: 'vertical_align_top', label: 'Generate it next', onClick: () => { N.remove(sid, cid); N.enqueue(sid, [cid], { first: true }); } },
      { icon: 'menu_book', label: 'Open in reader', onClick: () => V2.go(`/reader/${sid}/${cid}`) },
      { icon: 'remove_circle', label: 'Remove from queue', danger: true, onClick: () => { N.remove(sid, cid); V2.snack('Removed from queue', { action: 'Undo', onAction: () => N.enqueue(sid, [cid]) }); } },
    ].filter(Boolean) });
  }

  // ---------- chapter audio (per story) ----------
  const ui = {};
  V2.screens.narrationStory = (ctx) => {
    const s = WNA.story(ctx.params.id); const page = ctx.el;
    if (!s) { page.innerHTML = `${V2.bar({ title: 'Chapter audio', cls: 'always' })}<div class="scroll"><div class="empty"><h3>Story not found</h3></div></div>`; return; }
    V2.theme(page, s);
    const u = (ui[s.id] = ui[s.id] || { filter: s.bookmark != null ? 'bm' : 'all' });
    let sel = null, shown = 0, list = [];
    const chs = WNA.chapters(s.id);
    const focusIdx = ctx.query.chapter ? WNA.chapterIndex(s.id, ctx.query.chapter) : s.bookmark;
    const barHtml = () => sel
      ? `<header class="bar ctx">${V2.ib('close', 'selx', 'Exit selection')}<div class="bt"><b>${sel.size} selected</b></div><div class="acts">${V2.ib('select_all', 'selall', 'Select all')}</div></header>`
      : V2.bar({ title: 'Chapter audio', sub: s.title, acts: V2.ib('checklist', 'sel', 'Select chapters') + V2.ib('queue_music', 'queue', 'Generation queue') + V2.ib('more_vert', 'more', 'More') });
    page.innerHTML = `${barHtml()}<div class="scroll"><div class="maxw">${V2.large('Chapter audio', s.title)}
      <div class="wrap na-live"></div><div class="wrap"><div class="chips scroller na-chips"></div></div><div class="wrap na-focus"></div>
      <p class="sec-hint na-line" style="padding-top:12px"></p><div class="wrap"><div class="na-list"></div><div class="na-sent"></div></div></div></div>`;
    V2.collapse(page);
    const listEl = page.querySelector('.na-list');
    const status = (c) => (c.downloaded ? N.chapterStatus(s.id, c.id) : { kind: 'nodl', text: 'Not downloaded' });
    function counts() {
      let ready = 0, none = 0; chs.forEach((c) => { const k = status(c).kind; if (k === 'ready') ready++; else if (k === 'none') none++; });
      return { all: chs.length, bm: s.bookmark != null ? chs.length - s.bookmark : 0, none, ready };
    }
    function renderTop(n) {
      const live = page.querySelector('.na-live');
      if (!patchLive(live, n)) { live.innerHTML = liveCard(n, s.id); tagLive(live, n); }
      const k = counts();
      setH(page.querySelector('.na-chips'), [['bm', 'From bookmark', k.bm], ['all', 'All', k.all], ['none', 'Not generated', k.none], ['ready', 'Ready', k.ready]].filter((x) => x[0] !== 'bm' || x[2]).map(([key, l, c]) => `<button class="chip ${u.filter === key ? 'sel' : ''}" data-f="${key}">${u.filter === key ? ic('check', 'chk') : ''}${l} <span class="n">${fmt.int(c)}</span></button>`).join(''));
      const g = n.generated.find((x) => x.storyId === s.id);
      setH(page.querySelector('.na-line'), `${ic('record_voice_over', 's16')} New audio uses <b>Bella</b> · ${k.ready} ready${g ? ` · ${g.sizeMb} MB` : ''}`);
      if (focusIdx != null && chs[focusIdx]) {
        const c = chs[focusIdx]; const st = status(c);
        setH(page.querySelector('.na-focus'), `<section class="card high na-fc"><span class="t-lm accent">${ctx.query.chapter ? 'THIS CHAPTER' : 'AT YOUR BOOKMARK'}</span><b class="t-tm">${esc(c.title)}</b><span class="t-bs ${KIND[st.kind] ? KIND[st.kind][1] : 'dim'}">${esc(st.text)}</span>${st.kind === 'generating' ? V2.wavy(st.progress) : ''}
          <div class="row" style="margin-top:8px;flex-wrap:wrap">${st.kind === 'ready' ? `<button class="btn filled s" data-play="${c.id}">${ic('play_arrow', 'fill')}Play</button>` : st.kind === 'none' || st.kind === 'stale' || st.kind === 'failed' ? `<button class="btn filled s" data-now="${c.id}">${ic('bolt')}Generate now</button><button class="btn tonal s" data-next="${c.id}">Generate it next</button>` : st.kind === 'queued' ? `<button class="btn tonal s" data-now="${c.id}">${ic('vertical_align_top')}Generate it next</button>` : ''}</div></section>`);
      }
    }
    function rowHtml(c, i) {
      const st = status(c); const k = KIND[st.kind] || ['cloud_off', 'dim'];
      return `<div class="na-row ${sel && sel.has(i) ? 'is-sel' : ''} ${st.kind}" data-ci="${i}"><span class="na-ic ${k[1]}">${sel ? `<span class="cbox ${sel.has(i) ? 'on' : ''}">${ic('check')}</span>` : ic(k[0], st.kind === 'ready' ? 'fill' : '')}</span><div class="grow"><b class="clamp2">${esc(c.title)}</b><span class="t-bs ${k[1]} na-st">${esc(st.text)}</span>${st.kind === 'generating' ? V2.wavy(st.progress) : ''}</div>${st.kind === 'ready' && !sel ? `<button class="ib" data-play="${c.id}" aria-label="Play">${ic('play_circle')}</button>` : ''}</div>`;
    }
    function renderList() {
      list = chs.map((c, i) => [c, i]).filter(([c, i]) => { const k = status(c).kind; return u.filter === 'all' || (u.filter === 'bm' && i >= (s.bookmark || 0)) || (u.filter === 'none' && k === 'none') || (u.filter === 'ready' && k === 'ready'); });
      shown = 0; listEl.innerHTML = list.length ? '' : `<div class="empty"><div class="blob">${ic('graphic_eq')}</div><h3>${u.filter === 'ready' ? 'No audio yet' : 'Nothing here'}</h3><p>${u.filter === 'ready' ? 'Generate a few chapters and they’ll be ready to play offline.' : 'Try another filter.'}</p></div>`;
      moreRows();
    }
    function moreRows() { const n = Math.min(list.length, shown + 60); listEl.insertAdjacentHTML('beforeend', list.slice(shown, n).map(([c, i]) => rowHtml(c, i)).join('')); shown = n; }
    function patchRows() { listEl.querySelectorAll('[data-ci]').forEach((r) => { const i = +r.dataset.ci; const st = status(chs[i]); if (r.querySelector('.na-st').textContent !== st.text) r.outerHTML = rowHtml(chs[i], i); }); }
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && shown < list.length) moreRows(); }, { rootMargin: '600px' });
    io.observe(page.querySelector('.na-sent')); ctx.sub(() => io.disconnect());
    ctx.sub(N.on((n) => { if (!page.isConnected) return; renderTop(n); patchRows(); }));
    renderTop(N.state()); renderList();

    let ftb = null;
    function setSel(v) {
      sel = v; const old = page.querySelector('.bar'); const nb = el(barHtml()); old.replaceWith(nb);
      renderList();
      if (sel && !ftb) { ftb = el(`<div class="ftb"><button class="btn tonal" data-b="gen">${ic('graphic_eq')}Generate</button><button class="btn tonal" data-b="regen">${ic('autorenew')}Regenerate</button><button class="btn danger" data-b="del">${ic('delete')}</button></div>`); page.appendChild(ftb); }
      if (!sel && ftb) { ftb.remove(); ftb = null; }
    }
    const paintSel = () => { page.querySelector('.bar .bt b').textContent = `${sel.size} selected`; listEl.querySelectorAll('[data-ci]').forEach((r) => { const on = sel.has(+r.dataset.ci); r.classList.toggle('is-sel', on); const cb = r.querySelector('.cbox'); if (cb) cb.classList.toggle('on', on); }); };
    V2.longPress(listEl, '[data-ci]', (r) => { if (!sel) setSel(new Set([+r.dataset.ci])); else { sel.add(+r.dataset.ci); paintSel(); } });

    function chapterMenu(i) {
      const c = chs[i]; const st = status(c);
      if (st.kind === 'nodl') { V2.snack('Download this chapter before generating audio'); return; }
      const items = [];
      if (st.kind === 'ready') items.push({ icon: 'play_arrow', label: 'Play', onClick: () => play(c.id) });
      if (st.kind === 'none') items.push({ icon: 'vertical_align_top', label: 'Generate next', onClick: () => gen([c.id], true) }, { icon: 'bolt', label: 'Generate now', onClick: () => gen([c.id], true) }, { icon: 'playlist_add', label: 'Add to queue', onClick: () => gen([c.id]) });
      if (st.kind === 'failed') items.push({ icon: 'replay', label: 'Retry', onClick: () => N.retryFailed() });
      if (st.kind === 'queued') items.push({ icon: 'vertical_align_top', label: 'Generate next', onClick: () => { N.remove(s.id, c.id); gen([c.id], true); } });
      if (st.kind === 'ready' || st.kind === 'stale') items.push({ icon: 'autorenew', label: 'Regenerate in Bella', onClick: () => gen([c.id], true) });
      if (['queued', 'generating', 'failed'].includes(st.kind)) items.push({ icon: 'remove_circle', label: 'Remove from queue', onClick: () => N.remove(s.id, c.id) });
      items.push({ icon: 'menu_book', label: 'Open in reader', onClick: () => V2.go(`/reader/${s.id}/${c.id}`) });
      if (st.kind === 'ready' || st.kind === 'stale') items.push({ icon: 'delete', label: 'Delete audio', danger: true, onClick: () => { N.deleteAudio(s.id, [c.id]); renderList(); V2.snack('Audio deleted'); } });
      V2.menu({ title: c.title, sub: esc(st.text), items });
    }
    const play = (cid) => { WNA.player.load(s.id, cid); V2.go('/player'); };
    function gen(ids, first) { const n = N.enqueue(s.id, ids, { first }); renderList(); V2.snack(n ? `${V2.plural(n, 'chapter')} queued${first ? ' first' : ''}` : 'Already in the queue', n ? { action: 'View queue', onAction: () => V2.go('/narration') } : {}); }
    function more() {
      const k = counts(); const notGen = chs.filter((c) => c.downloaded && status(c).kind === 'none').map((c) => c.id); const ready = chs.filter((c) => status(c).kind === 'ready').map((c) => c.id);
      V2.menu({ title: 'Chapter audio', sub: esc(s.title), items: [
        { icon: 'graphic_eq', label: `Generate everything not generated (${fmt.int(notGen.length)})`, disabled: !notGen.length, onClick: () => gen(notGen) },
        { icon: 'vertical_align_top', label: 'Generate this story first', onClick: () => { N.storyFirst(s.id); V2.snack('Moved to the front of the queue'); } },
        { icon: 'autorenew', label: `Regenerate ${k.ready} in Bella`, disabled: !k.ready, onClick: () => gen(ready) },
        { icon: 'record_voice_over', label: 'Voice & Speech', onClick: () => V2.go('/settings/tts') },
        { icon: 'queue_music', label: 'Generation queue', onClick: () => V2.go('/narration') },
        { icon: 'remove_circle', label: 'Remove from queue', onClick: () => { N.remove(s.id); renderList(); V2.snack('Removed this story from the queue'); } },
        { icon: 'delete', label: 'Delete all audio', danger: true, disabled: !k.ready, onClick: async () => { if (await V2.confirm({ title: 'Delete all audio?', text: `${V2.plural(k.ready, 'chapter')} of generated audio for ${s.title} will be removed.`, ok: 'Delete', danger: true, icon: 'delete' })) { N.deleteAudio(s.id); renderList(); V2.snack('All audio deleted'); } } },
      ] });
    }
    page.addEventListener('click', (e) => {
      const t = (q) => e.target.closest(q); let b;
      if ((b = t('[data-play]'))) { play(b.dataset.play); return; }
      if ((b = t('[data-now]'))) { N.remove(s.id, b.dataset.now); gen([b.dataset.now], true); return; }
      if ((b = t('[data-next]'))) { gen([b.dataset.next], true); return; }
      if ((b = t('[data-f]'))) { u.filter = b.dataset.f; renderTop(N.state()); renderList(); return; }
      if ((b = t('[data-ci]'))) { const i = +b.dataset.ci; if (sel) { sel.has(i) ? sel.delete(i) : sel.add(i); paintSel(); } else chapterMenu(i); return; }
      if ((b = t('[data-b]'))) {
        const ids = [...sel].map((i) => chs[i]).filter((c) => c.downloaded).map((c) => c.id); if (!ids.length) return;
        if (b.dataset.b === 'gen') gen(ids.filter((id) => status(WNA.chapter(s.id, id)).kind !== 'ready'));
        if (b.dataset.b === 'regen') gen(ids);
        if (b.dataset.b === 'del') { N.deleteAudio(s.id, ids); V2.snack(`Deleted audio for ${V2.plural(ids.length, 'chapter')}`); }
        setSel(null); return;
      }
      if (!(b = t('[data-a]'))) return;
      switch (b.dataset.a) {
        case 'sel': setSel(new Set()); break;
        case 'selx': setSel(null); break;
        case 'selall': list.forEach(([, i]) => sel.add(i)); paintSel(); break;
        case 'queue': V2.go('/narration'); break;
        case 'more': more(); break;
        case 'pause': N.setPaused(!N.state().paused); break;
      }
    });
  };
})();
