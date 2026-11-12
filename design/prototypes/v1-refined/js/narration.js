/* v1 Refined — Audio generation hub + Chapter audio per story. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;
  const N = WNA.narration;

  const JOB_IC = { generating: '<span class="spin"></span>', queued: V.ic('schedule', 'sm'), failed: V.ic('error', 'sm') };

  V.genBanner = (n, { storyId = null, compact = false } = {}) => {
    const cur = n.current;
    if (!cur || (storyId && cur.storyId !== storyId)) {
      if (storyId) return '';
      return `<div class="gen-ban idle">${V.ic('graphic_eq')}<div class="grow"><b>Nothing is generating</b><span>Queue chapters from a story’s Chapter audio, or just start listening.</span></div></div>`;
    }
    const where = n.targetComputer ? `on ${esc(n.targetComputer.name)} · ` : '';
    const pct = Math.round(cur.progress * 100);
    const after = n.queuedCount - 1;
    return `<div class="gen-ban ${n.paused ? 'paused' : ''}">
      <div class="gb-top">
        <div class="grow"><div class="gb-l">${n.paused ? 'Paused' : 'Generating'} ${where}· ${pct}%</div>
        <b class="ell">${esc(cur.title)}</b><span class="ell">${esc(cur.story.title)}</span></div>
        <button class="btn tonal sm" data-act="${n.paused ? 'resume' : 'pause'}">${V.ic(n.paused ? 'play_arrow' : 'pause')}${n.paused ? 'Resume' : 'Pause'}</button>
      </div>
      <div class="prog ${n.paused ? '' : ''}"><i style="width:${pct}%"></i></div>
      ${compact ? '' : `<div class="gb-meta tnum">${cur.sentence} of ${cur.sentences} sentences · ${cur.speed} · ~${cur.etaMin} min left${after > 0 ? ` · ${V.plural(after, 'chapter')} after this` : ''}</div>`}
    </div>`;
  };

  // ---------- hub ----------
  V.screens.narration = () => {
    const el = WNA.el(`<div class="screen">${V.appBar({ title: 'Audio generation', subtitle: '', actions: V.ib('more_vert', 'more', 'Queue options') })}<div class="wrap body"></div><div class="pad-bottom"></div></div>`);
    const $ = (q) => el.querySelector(q);

    function render() {
      const n = N.state();
      $('.appbar .st') ? ($('.appbar .st').textContent = sub(n)) : $('.appbar .titles').insertAdjacentHTML('beforeend', `<div class="st">${sub(n)}</div>`);
      const queueCards = n.byStory.map((g) => {
        const q = g.jobs.filter((j) => j.status !== 'failed').length, f = g.jobs.filter((j) => j.status === 'failed').length;
        return `<div class="card qcard">
          <button class="qc-h" data-act="openstory" data-id="${g.storyId}">${V.cover(g.story, 32)}<div class="grow"><b class="ell">${esc(g.story.title)}</b><span>${q} queued${f ? ` · <em class="err">${f} failed</em>` : ''}</span></div>${V.ic('chevron_right', 'chev')}</button>
          ${g.jobs.slice(0, 5).map((j) => `<div class="job ${j.status}">${JOB_IC[j.status] || ''}<div class="grow"><div class="ell jt">${esc(j.title)}</div><div class="js">${j.status === 'generating' ? `Generating · ${Math.round(j.progress * 100)}% · ${Math.round(j.progress * 80)} of 80 sentences` : j.status === 'failed' ? `Failed · ${esc(j.error)}` : `Queued · ${esc(j.voice)}`}</div>${j.status === 'generating' ? `<div class="prog"><i style="width:${V.pct(j.progress)}"></i></div>` : ''}</div>${j.status === 'failed' ? `<button class="btn text sm" data-act="retry">Retry</button>` : ''}</div>`).join('')}
          ${g.jobs.length > 5 ? `<div class="qc-more">and ${g.jobs.length - 5} more</div>` : ''}
          <div class="qc-f">${V.btn('Generate this story first', 'first', { kind: 'text', cls: 'sm', icon: 'vertical_align_top', data: `data-id="${g.storyId}"` })}${V.btn('Remove', 'rmstory', { kind: 'text', cls: 'sm', data: `data-id="${g.storyId}"` })}</div>
        </div>`;
      }).join('');
      $('.body').innerHTML = `
        <div style="margin-top:4px">${V.genBanner(n)}</div>
        <section class="section">
          <div class="sec-h"><h2>Queue · ${V.plural(n.queuedCount, 'chapter')}</h2></div>
          ${n.byStory.length ? `<p class="sec-help">Chapters generate top to bottom. The story you’re listening to jumps ahead.</p><div class="col" style="gap:10px">${queueCards}</div>` : `<div class="card pad faint" style="font:var(--t-sm)">Nothing is queued. Open a story’s Chapter audio (Details → More → Narration audio) or tap Read aloud to generate chapters.</div>`}
        </section>
        ${n.batteryRestricted ? `<section class="section"><div class="card pad bat">${V.ic('battery_alert')}<div class="grow"><b>Generating with the screen off</b><p>Android pauses background work while the phone sleeps. Allow unrestricted battery use so queued chapters keep generating.</p>${V.btn('Allow', 'battery', { kind: 'tonal', cls: 'sm' })}</div></div></section>` : ''}
        <section class="section">
          <div class="sec-h"><h2>Generate on</h2></div>
          <p class="sec-help">Kokoro can run on one of your computers over Wi-Fi or Tailscale. It’s several times faster, uses the same voice, and sends the audio back to this phone.</p>
          <div class="chip-wrap" style="margin-bottom:12px">
            <button class="chip ${n.target === 'phone' ? 'on' : ''}" data-act="target" data-id="phone">${V.ic('smartphone')}This phone</button>
            ${n.computers.map((c) => `<button class="chip ${n.target === c.id ? 'on' : ''}" data-act="target" data-id="${c.id}">${V.ic('computer')}${esc(c.name)}</button>`).join('')}
          </div>
          <div class="list">
            ${n.computers.map((c) => `<div class="li has-ic"><span class="lic">${V.ic('computer')}</span><div class="grow"><div class="lt">${esc(c.name)}${n.target === c.id ? ' <span class="pill ongoing" style="margin-left:6px">In use</span>' : ''}</div><div class="ls tnum">${esc(c.address)}</div></div>${V.ib('delete', 'rmpc', 'Remove computer', { cls: 'sm', data: `data-id="${c.id}"` })}</div>`).join('')}
            <button class="li has-ic" data-act="addpc"><span class="lic">${V.ic('add')}</span><div class="grow"><div class="lt gold">Add a computer</div></div></button>
          </div>
        </section>
        <section class="section">
          <div class="sec-h"><h2>Keep ahead while listening</h2></div>
          <p class="sec-help">While a story plays, the next chapters are queued so they’re ready in time.</p>
          <div class="chip-wrap">${[0, 1, 3, 5, 10].map((k) => `<button class="chip ${n.keepAhead === k ? 'on' : ''}" data-act="ahead" data-k="${k}">${n.keepAhead === k ? V.ic('check') : ''}${k ? V.plural(k, 'chapter') : 'Off'}</button>`).join('')}</div>
        </section>
        <section class="section">
          <div class="sec-h"><h2>Generated audio</h2><span class="faint tnum" style="font:var(--t-xs)">${esc(n.storage.used)} used · ${esc(n.storage.free)} free</span></div>
          <div class="list">${n.generated.length ? n.generated.map((g) => `<button class="li has-ic" data-act="openstory" data-id="${g.storyId}">${V.cover(g.story, 32)}<div class="grow"><div class="lt ell">${esc(g.story.title)}</div><div class="ls">${V.plural(g.chapters, 'chapter')} · ${fmt.dur(g.durationSec)} · ${g.sizeMb} MB · ${esc(g.voices)}</div></div>${V.ic('chevron_right', 'chev')}</button>`).join('') : `<div class="li faint">No audio generated yet</div>`}</div>
        </section>`;
    }
    const sub = (n) => (n.queuedCount || n.failedCount ? `${n.queuedCount} queued${n.failedCount ? ` · ${n.failedCount} failed` : ''}` : 'Queue is empty');
    render();

    V.bind(el, {
      more() {
        const n = N.state();
        V.menu({ title: 'Queue options', items: [
          { act: 'pause', icon: n.paused ? 'play_arrow' : 'pause', label: n.paused ? 'Resume generating' : 'Pause generating' },
          { act: 'retry', icon: 'refresh', label: 'Retry failed', disabled: !n.failedCount, trailing: n.failedCount ? String(n.failedCount) : '' },
          { act: 'clear', icon: 'clear_all', label: 'Clear the queue', danger: true, disabled: !n.queue.length },
        ], async onPick(a) {
          if (a === 'pause') N.setPaused(!n.paused);
          if (a === 'retry') { N.retryFailed(); V.toast('Retrying failed chapters'); }
          if (a === 'clear' && await V.confirm('Clear the queue?', `${V.plural(n.queue.length, 'chapter')} will stop generating. Audio that’s already done is kept.`, 'Clear')) { N.clearQueue(); V.toast('Queue cleared'); }
        } });
      },
      pause: () => N.setPaused(true),
      resume: () => N.setPaused(false),
      retry: () => { N.retryFailed(); V.toast('Retrying'); },
      openstory: (t) => WNA.go('/narration/' + t.dataset.id),
      first: (t) => { N.storyFirst(t.dataset.id); V.toast('Moved to the front of the queue'); },
      rmstory: (t) => { N.remove(t.dataset.id); V.toast('Removed from the queue'); },
      battery: () => { N.allowBattery(); V.toast('Battery restrictions lifted'); },
      target: (t) => { N.setTarget(t.dataset.id); V.toast(t.dataset.id === 'phone' ? 'Generating on this phone' : `Generating on ${N.state().targetComputer.name}`); },
      ahead: (t) => N.setKeepAhead(+t.dataset.k),
      async rmpc(t) { if (await V.confirm('Remove this computer?', 'You can add it again later with its address and token.', 'Remove')) N.removeComputer(t.dataset.id); },
      async addpc() {
        const r = await V.dialog({ title: 'Add a computer', html: `<p style="margin-bottom:4px">Run the narration server on your computer, then enter what it shows.</p>${V.field('name', 'Name', '', { placeholder: 'Living room PC' })}${V.field('address', 'Address', '', { placeholder: '192.168.1.30:8790' })}${V.field('token', 'Token', '', { placeholder: 'Pairing token' })}`, actions: [{ label: 'Cancel', value: null }, { label: 'Add', value: 'add', kind: 'filled' }] });
        if (r.value !== 'add') return;
        N.addComputer({ name: r.form.name || 'New computer', address: r.form.address || '192.168.1.30:8790' });
        V.toast('Computer added · connection OK');
      },
    });
    const off = N.on(render);
    V.watchScroll(el);
    return { el, title: 'Audio generation', destroy: off };
  };

  // ---------- per story ----------
  V.screens.narrationStory = (r) => {
    const s = WNA.story(r.params.id);
    const id = s.id;
    const st = { filter: s.bookmark != null ? 'bm' : 'all', sel: new Set(), selMode: false, inc: null, focus: r.query.chapter || null };
    const chs = () => WNA.chapters(id).filter((c) => c.downloaded);

    const el = WNA.el(`
      <div class="screen">
        <div class="bar-slot"></div>
        <div class="wrap">
          <div class="ban-slot"></div>
          <div class="chip-scroll nf" style="margin-top:8px;padding-bottom:4px"></div>
          <div class="focus-slot"></div>
          <p class="ns-line"></p>
          <div class="nrows"></div>
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);

    function bar() {
      $('.bar-slot').innerHTML = st.selMode
        ? `<header class="appbar contextual">${V.ib('close', 'selx', 'Exit selection')}<div class="titles"><div class="t">${st.sel.size} selected</div></div><div class="actions">${V.ib('select_all', 'selall', 'Select all')}</div></header>`
        : V.appBar({ title: 'Chapter audio', subtitle: s.title, actions: V.ib('checklist', 'selon', 'Select chapters') + V.ib('queue_music', 'hub', 'Generation queue') + V.ib('more_vert', 'more', 'More') });
      let b = el.querySelector('.botbar');
      if (st.selMode && !b) { b = WNA.el(`<div class="botbar fixed">${V.btn('Generate', 'sgen', { kind: 'filled', icon: 'graphic_eq', cls: 'grow' })}${V.btn('Regenerate', 'sregen', { kind: 'tonal', icon: 'autorenew', cls: 'grow' })}${V.btn('Delete', 'sdel', { kind: 'danger', icon: 'delete', cls: 'grow' })}</div>`); el.appendChild(b); }
      if (!st.selMode && b) b.remove();
      if (b) b.querySelectorAll('.btn').forEach((x) => x.toggleAttribute('disabled', !st.sel.size));
    }
    const statusOf = (c) => N.chapterStatus(id, c.id);
    function counts() {
      const list = chs(); let ready = 0, none = 0;
      list.forEach((c) => { const k = statusOf(c).kind; if (k === 'ready') ready++; else if (k === 'none') none++; });
      const bm = s.bookmark != null ? list.filter((c) => c.index - 1 >= s.bookmark).length : 0;
      return { all: list.length, ready, none, bm };
    }
    function filtered() {
      let list = chs();
      if (st.filter === 'bm' && s.bookmark != null) list = list.filter((c) => c.index - 1 >= s.bookmark);
      if (st.filter === 'none') list = list.filter((c) => statusOf(c).kind === 'none');
      if (st.filter === 'ready') list = list.filter((c) => statusOf(c).kind === 'ready');
      return list;
    }
    const stHtml = (x) => `<span class="ns ${x.kind}">${x.kind === 'generating' ? '<span class="spin"></span>' : x.kind === 'ready' ? V.ic('check_circle', 'xs') : x.kind === 'failed' || x.kind === 'stale' ? V.ic('error', 'xs') : x.kind === 'queued' ? V.ic('schedule', 'xs') : ''}${esc(x.text)}</span>${x.kind === 'generating' ? `<div class="prog"><i style="width:${V.pct(x.progress)}"></i></div>` : ''}`;
    const rowHtml = (c) => `<div class="nr ${st.sel.has(c.id) ? 'sel' : ''}" data-cid="${esc(c.id)}" role="button" tabindex="0">
      ${st.selMode ? `<span class="check ${st.sel.has(c.id) ? 'on' : ''}">${V.ic('check')}</span>` : `<span class="idx tnum">${c.index}</span>`}
      <div class="grow"><div class="nt ell">${esc(c.title)}</div><div class="nst">${stHtml(statusOf(c))}</div></div>
      ${V.ic('more_vert', 'faint')}</div>`;
    function chips() {
      const k = counts();
      const chip = (f, l, n) => `<button class="chip ${st.filter === f ? 'on' : ''}" data-act="nf" data-k="${f}">${st.filter === f ? V.ic('check') : ''}${l}<span class="n">${fmt.int(n)}</span></button>`;
      $('.nf').innerHTML = (s.bookmark != null ? chip('bm', 'From bookmark', k.bm) : '') + chip('all', 'All', k.all) + chip('none', 'Not generated', k.none) + chip('ready', 'Ready', k.ready);
      const g = N.state().generated.find((x) => x.storyId === id);
      $('.ns-line').innerHTML = `New audio uses <b>Bella</b> · ${k.ready} ready${g ? ` · ${g.sizeMb} MB` : ''}`;
    }
    function rows() {
      if (st.inc) st.inc.disconnect();
      const box = $('.nrows'); box.innerHTML = '';
      const list = filtered();
      if (!list.length) { box.innerHTML = V.empty({ icon: 'graphic_eq', title: st.filter === 'ready' ? 'No audio yet' : 'Nothing here', body: st.filter === 'ready' ? 'Generate chapters and they’ll be ready to play offline.' : 'Every chapter in this filter already has audio.' }); return; }
      st.inc = V.incremental(box, el, list, rowHtml, { chunk: 50 });
    }
    function focusCard() {
      const slot = $('.focus-slot');
      if (!st.focus) { slot.innerHTML = ''; return; }
      const c = WNA.chapter(id, st.focus); const x = statusOf(c);
      slot.innerHTML = `<div class="card pad focus"><div class="row"><div class="grow"><div class="gb-l">This chapter</div><b class="ell" style="display:block;font:var(--t-h3)">${esc(c.title)}</b><div class="nst" style="margin-top:4px">${stHtml(x)}</div></div>${V.ib('close', 'nofocus', 'Dismiss', { cls: 'sm' })}</div>
        <div class="btn-row" style="margin-top:12px">${x.kind === 'ready' ? V.btn('Play', 'fplay', { kind: 'filled', icon: 'play_arrow', cls: 'sm' }) : x.kind === 'none' || x.kind === 'failed' || x.kind === 'stale' ? V.btn('Generate now', 'fnow', { kind: 'filled', icon: 'graphic_eq', cls: 'sm' }) + V.btn('Generate it next', 'fnext', { kind: 'tonal', cls: 'sm' }) : V.btn('Generate it next', 'fnext', { kind: 'tonal', cls: 'sm' })}</div></div>`;
    }
    function live() {
      $('.ban-slot').innerHTML = V.genBanner(N.state(), { storyId: id, compact: true });
      chips(); focusCard();
      el.querySelectorAll('.nr[data-cid]').forEach((row) => { const c = WNA.chapter(id, row.dataset.cid); row.querySelector('.nst').innerHTML = stHtml(statusOf(c)); });
    }
    function all() { bar(); live(); rows(); }
    all();

    function chapterMenu(c) {
      const x = statusOf(c);
      const items = [];
      if (x.kind === 'ready') items.push({ act: 'play', icon: 'play_arrow', label: 'Play' });
      if (x.kind === 'none') items.push({ act: 'next', icon: 'playlist_add', label: 'Generate next' }, { act: 'now', icon: 'bolt', label: 'Generate now' });
      if (x.kind === 'queued') items.push({ act: 'now', icon: 'vertical_align_top', label: 'Generate it next' });
      if (x.kind === 'failed') items.push({ act: 'retry', icon: 'refresh', label: 'Retry' });
      if (x.kind === 'ready' || x.kind === 'stale') items.push({ act: 'regen', icon: 'autorenew', label: 'Regenerate in Bella' });
      items.push({ act: 'reader', icon: 'chrome_reader_mode', label: 'Open in reader' });
      if (x.kind === 'queued' || x.kind === 'generating' || x.kind === 'failed') items.push({ act: 'rm', icon: 'playlist_remove', label: 'Remove from queue' });
      if (x.kind === 'ready' || x.kind === 'stale') items.push({ act: 'del', icon: 'delete', label: 'Delete audio', danger: true });
      V.menu({ title: c.title, sub: x.text, items, onPick(a) { doChapter(a, c); } });
    }
    function doChapter(a, c) {
      if (a === 'play') { WNA.player.load(id, c.id, { play: true }); WNA.go('/player'); }
      if (a === 'next') { N.enqueue(id, [c.id]); V.toast('Added to the queue'); }
      if (a === 'now') { N.enqueue(id, [c.id], { first: true }); V.toast('Generating it next'); }
      if (a === 'retry') { N.retryFailed(); V.toast('Retrying'); }
      if (a === 'regen') { N.deleteAudio(id, [c.id]); N.enqueue(id, [c.id], { first: true }); V.toast('Regenerating in Bella'); }
      if (a === 'reader') WNA.go(`/reader/${id}/${encodeURIComponent(c.id)}`);
      if (a === 'rm') { N.remove(id, c.id); V.toast('Removed from the queue'); }
      if (a === 'del') { N.deleteAudio(id, [c.id]); V.toast('Audio deleted'); }
    }
    const exitSel = () => { st.selMode = false; st.sel.clear(); bar(); rows(); };

    V.bind(el, {
      hub: () => WNA.go('/narration'),
      selon() { st.selMode = true; bar(); rows(); },
      selx: exitSel,
      selall() { const l = filtered(); if (st.sel.size === l.length) st.sel.clear(); else l.forEach((c) => st.sel.add(c.id)); bar(); rows(); },
      nf(t) { st.filter = t.dataset.k; chips(); rows(); },
      pause: () => N.setPaused(true), resume: () => N.setPaused(false),
      nofocus() { st.focus = null; focusCard(); },
      fplay: () => doChapter('play', WNA.chapter(id, st.focus)),
      fnow: () => doChapter('now', WNA.chapter(id, st.focus)),
      fnext: () => doChapter('now', WNA.chapter(id, st.focus)),
      sgen() { const n = N.enqueue(id, [...st.sel]); V.toast(`Queued ${V.plural(n, 'chapter')}`); exitSel(); },
      sregen() { const ids = [...st.sel]; N.deleteAudio(id, ids); N.enqueue(id, ids); V.toast(`Regenerating ${V.plural(ids.length, 'chapter')}`); exitSel(); },
      async sdel() { const ids = [...st.sel]; if (await V.confirm(`Delete audio for ${V.plural(ids.length, 'chapter')}?`, 'The chapters stay downloaded. You can generate audio again any time.', 'Delete')) { N.deleteAudio(id, ids); V.toast('Audio deleted'); exitSel(); } },
      more() {
        const k = counts();
        V.menu({ title: 'Chapter audio', sub: s.title, groups: [
          { items: [
            { act: 'all', icon: 'graphic_eq', label: `Generate everything not generated (${k.none})`, disabled: !k.none },
            { act: 'first', icon: 'vertical_align_top', label: 'Generate this story first' },
            { act: 'regen', icon: 'autorenew', label: `Regenerate ${k.ready} in Bella`, disabled: !k.ready },
          ] },
          { items: [
            { act: 'voice', icon: 'record_voice_over', label: 'Voice & Speech' },
            { act: 'hub', icon: 'queue_music', label: 'Generation queue' },
            { act: 'rmq', icon: 'playlist_remove', label: 'Remove from queue' },
            { act: 'delall', icon: 'delete', label: 'Delete all audio', danger: true },
          ] },
        ], async onPick(a) {
          if (a === 'all') { const n = N.enqueue(id, chs().filter((c) => statusOf(c).kind === 'none').map((c) => c.id)); V.toast(`Queued ${V.plural(n, 'chapter')}`); }
          if (a === 'first') { N.storyFirst(id); V.toast('This story generates first'); }
          if (a === 'regen') { const ids = chs().filter((c) => statusOf(c).kind === 'ready').map((c) => c.id); N.deleteAudio(id, ids); N.enqueue(id, ids); V.toast(`Regenerating ${V.plural(ids.length, 'chapter')}`); }
          if (a === 'voice') WNA.go('/settings/tts');
          if (a === 'hub') WNA.go('/narration');
          if (a === 'rmq') { N.remove(id); V.toast('Removed from the queue'); }
          if (a === 'delall' && await V.confirm('Delete all audio?', `Generated audio for “${s.title}” will be removed. Chapters stay downloaded.`, 'Delete')) { N.deleteAudio(id); V.toast('All audio deleted'); }
        } });
      },
    });
    WNA.on(el, 'click', '.nr[data-cid]', (e, t) => {
      const c = WNA.chapter(id, t.dataset.cid);
      if (st.selMode) { st.sel.has(c.id) ? st.sel.delete(c.id) : st.sel.add(c.id); t.classList.toggle('sel'); t.querySelector('.check').classList.toggle('on'); bar(); return; }
      chapterMenu(c);
    });
    V.longPress(el, '.nr[data-cid]', (t) => { st.selMode = true; st.sel.add(t.dataset.cid); bar(); rows(); });
    const off = N.on(live);
    V.watchScroll(el);
    return { el, title: 'Chapter audio', destroy: () => { off(); if (st.inc) st.inc.disconnect(); } };
  };
})();
