/* v3 Restructured — Story Details panels: Audio (chapter audio), Trends (SVG charts), AI (covers + description).
   Each panel: (container, story, ctx) => dispose(). Plus the global floating AI job chip. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  const P = (A.storyPanels = A.storyPanels || {});

  // ---------- Audio (per-story chapter audio) ----------
  const STATUS_CLS = { none: 'faint', queued: 'muted', generating: 'gold', failed: 'red', ready: 'gold', stale: 'red' };
  P.audio = (box, s, ctx) => {
    const list = W.chapters(s.id);
    const b = A.bookmark(s);
    let filter = b != null ? 'bm' : 'all';
    let selecting = false; const sel = new Set();
    let stopInc = null;
    const voice = 'Bella';
    const st = (c) => W.narration.chapterStatus(s.id, c.id);
    const focusId = (ctx.route.query && ctx.route.query.focus) || (list[b != null ? b : 0] || {}).id;

    const sb = W.el(`<div class="selbar" hidden><button class="sbb" data-sa="gen">${A.icon('graphic_eq')}<span>Generate</span></button><button class="sbb" data-sa="regen">${A.icon('autorenew')}<span>Regenerate</span></button><button class="sbb danger" data-sa="del">${A.icon('delete')}<span>Delete</span></button><button class="sbb" data-sa="x">${A.icon('close')}<span>Done</span></button></div>`);
    document.getElementById('app').appendChild(sb);

    function counts() {
      let ready = 0, none = 0;
      list.forEach((c) => { const k = st(c).kind; if (k === 'ready') ready++; else if (k === 'none') none++; });
      return { ready, none };
    }
    function banner() {
      const n = W.narration.state();
      const cur = n.current;
      if (cur && cur.storyId === s.id) {
        return `<div class="card copper pad16 genbanner"><div class="row"><span class="spinner" style="color:var(--copper)"></span><b class="grow">${n.paused ? 'Paused' : 'Generating'} · ${Math.round(cur.progress * 100)}%</b><button class="btn sm surface" data-a="pause">${n.paused ? 'Resume' : 'Pause'}</button></div>
          <div class="nowrap" style="margin:6px 0 10px">${esc(cur.title)}</div>${A.prog(cur.progress, 'copper thick')}<div class="muted" style="font-size:12.5px;margin-top:8px">${cur.sentence} of ${cur.sentences} sentences · ${cur.speed} · ~${cur.etaMin} min left</div></div>`;
      }
      if (cur) return `<button class="otherjob" data-a="queue">${A.icon('graphic_eq', 'sm gold')}<span class="grow nowrap">Generating “${esc(A.short(cur.story.title, 26))}” · ${Math.round(cur.progress * 100)}%</span>${A.icon('chevron_right', 'sm')}</button>`;
      return '';
    }
    function focusCard() {
      const c = list.find((x) => x.id === focusId); if (!c) return '';
      const k = st(c);
      const acts = k.kind === 'ready' ? `<button class="btn sm fill" data-cp="${c.id}">${A.icon('play_arrow', 'f')}Play</button>` : k.kind === 'generating' || k.kind === 'queued' ? `<button class="btn sm surface" data-cr="${c.id}">Remove from queue</button>` : `<button class="btn sm fill" data-cg="${c.id}" data-first="1">Generate now</button><button class="btn sm surface" data-cg="${c.id}">Generate it next</button>`;
      return `<div class="card tint pad16 focus"><div class="eyebrow">${b != null && c.index === b + 1 ? 'Your bookmark' : 'Chapter'}</div><div class="t">${esc(c.title)}</div><div class="${STATUS_CLS[k.kind]}" style="font-size:13px;margin:2px 0 12px">${esc(k.text)}</div><div class="btnrow">${acts}</div></div>`;
    }
    function row(c) {
      const k = st(c);
      const on = sel.has(c.id);
      return `<div class="arow ${on ? 'sel' : ''}" data-ac="${c.id}">${selecting ? `<span class="ck ${on ? 'on' : ''}"></span>` : `<span class="aic k-${k.kind}">${A.icon({ none: 'radio_button_unchecked', queued: 'schedule', generating: 'graphic_eq', failed: 'error', ready: 'play_circle', stale: 'sync_problem' }[k.kind], k.kind === 'ready' ? 'f' : '')}</span>`}
        <div class="grow"><div class="t nowrap">${esc(c.title)}</div><div class="s ${STATUS_CLS[k.kind]}">${esc(k.text)}</div>${k.kind === 'generating' ? A.prog(k.progress) : ''}</div>${selecting ? '' : A.icon('more_vert', 'faint sm')}</div>`;
    }
    function items() {
      if (filter === 'bm' && b != null) return list.slice(b);
      if (filter === 'none') return list.filter((c) => st(c).kind === 'none');
      if (filter === 'ready') return list.filter((c) => st(c).kind === 'ready');
      return list;
    }
    function render() {
      const c = counts();
      const gen = W.narration.state().generated.find((g) => g.storyId === s.id);
      box.innerHTML = `<div class="audiohead">
          <div class="row"><div class="grow"><div class="t">Chapter audio</div><div class="muted" style="font-size:12.5px">New audio uses ${voice} · ${c.ready} ready${gen ? ` · ${gen.sizeMb} MB` : ''}</div></div>
          <button class="ib" data-a="select" aria-label="Select chapters">${A.icon('checklist')}</button><button class="ib" data-a="queue" aria-label="Generation queue">${A.icon('queue_music')}</button><button class="ib" data-a="more" aria-label="More">${A.icon('more_vert')}</button></div>
          <div data-r="banner">${banner()}</div>
          ${focusCard()}
          <div class="chips scroll" style="margin-top:12px">${[b != null && ['bm', 'From bookmark', list.length - b], ['all', 'All', list.length], ['none', 'Not generated', c.none], ['ready', 'Ready', c.ready]].filter(Boolean).map(([k, l, n]) => `<button class="chip ${filter === k ? 'on' : ''}" data-af="${k}">${l} <span class="n">${fmt.int(n)}</span></button>`).join('')}</div>
        </div><div class="alist" data-r="alist"></div>`;
      fill();
    }
    function fill() {
      const bx = box.querySelector('[data-r=alist]');
      const it = items();
      stopInc && stopInc();
      if (!it.length) { bx.innerHTML = `<div class="empty">${A.icon('graphic_eq')}<h3>${filter === 'ready' ? 'No audio yet' : 'Nothing here'}</h3><p>${filter === 'ready' ? 'Generate a chapter to listen with the Kokoro voice offline.' : 'Every chapter in this filter already has audio.'}</p></div>`; return; }
      stopInc = A.incremental(bx, it, row, { chunk: 50, scroller: ctx.el });
    }
    function patch() {
      const bn = box.querySelector('[data-r=banner]'); if (bn) bn.innerHTML = banner();
      box.querySelectorAll('[data-ac]').forEach((r) => { const want = row(W.chapter(s.id, r.dataset.ac)); if (r.outerHTML !== want) r.outerHTML = want; });
    }
    function setSel(on) { selecting = on; if (!on) sel.clear(); sb.hidden = !on; document.body.classList.toggle('selecting', on); A.renderDock(); patch(); }
    function chapterMenu(c) {
      const k = st(c);
      A.menu({ title: c.title, sub: k.text, sections: [{ items: [
        k.kind === 'ready' && { icon: 'play_arrow', label: 'Play', fn: () => { W.player.load(s.id, c.id, { play: true }); A.toast('Playing generated audio', 'Open player', () => W.go('/player')); } },
        (k.kind === 'none' || k.kind === 'stale') && { icon: 'bolt', label: 'Generate now', sub: 'Jump to the front of the queue', fn: () => { W.narration.enqueue(s.id, [c.id], { first: true }); A.toast('Generating next'); } },
        k.kind === 'none' && { icon: 'playlist_add', label: 'Generate next', fn: () => { W.narration.enqueue(s.id, [c.id]); A.toast('Added to the queue'); } },
        k.kind === 'failed' && { icon: 'refresh', label: 'Retry', fn: () => { W.narration.retryFailed(); A.toast('Retrying'); } },
        (k.kind === 'queued' || k.kind === 'generating') && { icon: 'playlist_remove', label: 'Remove from queue', fn: () => { W.narration.remove(s.id, c.id); A.toast('Removed from queue'); } },
        (k.kind === 'ready' || k.kind === 'stale') && { icon: 'autorenew', label: `Regenerate in ${voice}`, fn: () => { W.narration.deleteAudio(s.id, [c.id]); W.narration.enqueue(s.id, [c.id], { first: true }); A.toast(`Regenerating in ${voice}`); } },
        { icon: 'menu_book', label: 'Open in reader', fn: () => W.go(`/reader/${s.id}/${c.id}`) },
        (k.kind === 'ready' || k.kind === 'stale') && { icon: 'delete', label: 'Delete audio', danger: true, fn: () => { W.narration.deleteAudio(s.id, [c.id]); A.toast('Audio deleted'); render(); } },
      ] }] });
    }
    function moreMenu() {
      const none = list.filter((c) => st(c).kind === 'none' && c.downloaded);
      A.menu({ title: 'Chapter audio', sub: A.short(s.title, 40), sections: [
        { items: [
          { icon: 'library_add', label: `Generate everything not generated (${none.length})`, fn: () => { const n = W.narration.enqueue(s.id, none.map((c) => c.id)); A.toast(`Queued ${n} chapters`); render(); } },
          { icon: 'low_priority', label: 'Generate this story first', fn: () => { W.narration.storyFirst(s.id); A.toast('Moved to the front of the queue'); } },
          { icon: 'autorenew', label: `Regenerate ${counts().ready} in ${voice}`, fn: () => A.toast(`Regenerating ${counts().ready} chapters in ${voice}`) },
        ] },
        { items: [
          { icon: 'record_voice_over', label: 'Voice & Speech', fn: () => W.go('/settings/tts') },
          { icon: 'queue_music', label: 'Generation queue', fn: () => W.go('/narration') },
          { icon: 'playlist_remove', label: 'Remove from queue', fn: () => { W.narration.remove(s.id); A.toast('Removed this story from the queue'); } },
          { icon: 'delete_sweep', label: 'Delete all audio', danger: true, fn: async () => { if (await A.confirm('Delete all audio?', 'Generated audio for this story will be removed. You can generate it again later.', 'Delete', true)) { W.narration.deleteAudio(s.id); render(); A.toast('Audio deleted'); } } },
        ] },
      ] });
    }
    const onClick = (e) => {
      const t = e.target.closest('[data-a],[data-af],[data-ac],[data-cp],[data-cg],[data-cr]'); if (!t || !box.contains(t)) return;
      if (t.dataset.af) { filter = t.dataset.af; box.querySelectorAll('[data-af]').forEach((x) => x.classList.toggle('on', x === t)); fill(); return; }
      if (t.dataset.ac) { const c = W.chapter(s.id, t.dataset.ac); if (selecting) { sel.has(c.id) ? sel.delete(c.id) : sel.add(c.id); if (!sel.size) setSel(false); else patch(); } else chapterMenu(c); return; }
      if (t.dataset.cp) { W.player.load(s.id, t.dataset.cp, { play: true }); W.go('/player'); return; }
      if (t.dataset.cg) { W.narration.enqueue(s.id, [t.dataset.cg], { first: !!t.dataset.first }); A.toast(t.dataset.first ? 'Generating now' : 'Generating it next'); render(); return; }
      if (t.dataset.cr) { W.narration.remove(s.id, t.dataset.cr); render(); return; }
      const a = t.dataset.a;
      if (a === 'pause') W.narration.setPaused(!W.narration.state().paused);
      else if (a === 'queue') W.go('/narration');
      else if (a === 'select') setSel(!selecting);
      else if (a === 'more') moreMenu();
    };
    // every version, full size; swipe between them and pick one
    const browse = (vid) => {
      const st = W.ai.state(s.id);
      const items = st.versions.map((v) => ({ id: v.id, url: v.url || W.coverUrl(s), label: v.label, sub: v.id === st.inUse ? 'In use' : v.isNew ? 'New' : '', prompt: v.prompt }));
      A.viewCovers({
        items, index: Math.max(0, st.versions.findIndex((v) => v.id === vid)),
        actions: (it) => [it.id === W.ai.state(s.id).inUse ? { id: 'inuse', label: 'In use', icon: 'check', disabled: true } : { id: 'use', label: 'Use this cover', icon: 'check', fill: true }],
        onAction: (a, it, close, refresh) => { if (a === 'use') { useCover(it.id); A.toast(`Using “${it.label}”`); items.forEach((x) => (x.sub = x.id === it.id ? 'In use' : x.sub === 'In use' ? '' : x.sub)); refresh(); } },
      });
    };
    // the pending cover next to the current one, full size
    const compare = (i) => {
      const st = W.ai.state(s.id);
      const cur = st.versions.find((v) => v.id === st.inUse) || st.versions[0];
      const pend = st.versions.find((v) => v.id === st.pending);
      if (!pend) return;
      A.viewCovers({
        items: [{ url: cur.url || W.coverUrl(s), label: 'Current cover', sub: cur.label }, { url: pend.url, label: 'New cover', sub: pend.label, prompt: pend.prompt }], index: i,
        actions: () => [{ id: 'keep', label: 'Keep current' }, { id: 'use', label: 'Use new cover', icon: 'check', fill: true }],
        onAction: (a, it, close) => { close(); if (a === 'use') { useCover(pend.id); A.toast('Using the new cover'); } else { W.ai.keepCurrent(s.id); A.toast('Kept the current cover'); } },
      });
    };
    box.addEventListener('click', onClick);
    A.longPress(box, '[data-ac]', (e, r) => { if (!selecting) { selecting = true; sb.hidden = false; document.body.classList.add('selecting'); A.renderDock(); } sel.add(r.dataset.ac); patch(); });
    W.on(sb, 'click', '[data-sa]', async (e, b2) => {
      const a = b2.dataset.sa; const ids = [...sel];
      if (a === 'x') return setSel(false);
      if (a === 'gen') { const n = W.narration.enqueue(s.id, ids); A.toast(`Queued ${n} chapters`); }
      if (a === 'regen') { W.narration.deleteAudio(s.id, ids); W.narration.enqueue(s.id, ids); A.toast(`Regenerating ${ids.length} in ${voice}`); }
      if (a === 'del') { if (!(await A.confirm(`Delete audio for ${ids.length} chapters?`, 'You can generate it again later.', 'Delete', true))) return; W.narration.deleteAudio(s.id, ids); A.toast('Audio deleted'); }
      setSel(false);
    });
    const off = W.narration.on(W.debounce(patch, 200));
    render();
    return () => { off(); stopInc && stopInc(); sb.remove(); document.body.classList.remove('selecting'); A.renderDock(); };
  };

  // ---------- Trends ----------
  const RANGES = [['7', '7D', 7], ['30', '30D', 30], ['90', '90D', 90], ['365', '1Y', 365], ['all', 'All time', 1e9]];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['engagement', 'Engagement']];
  P.trends = (box, s) => {
    let range = 'all', group = 'all';
    const series = W.trendSeries(s.id);
    function render() {
      const all = series.flatMap((x) => x.points.map((p) => p.t));
      if (!series.length || !all.length) { box.innerHTML = `<div class="empty">${A.icon('monitoring')}<h3>No trend data yet</h3><p>Trends build up each time this novel syncs.</p><button class="btn tonal" data-t="sync">Sync now</button></div>`; return; }
      const days = RANGES.find((r) => r[0] === range)[2];
      const from = W.NOW - days * 86400000;
      const pick = series.filter((x) => group === 'all' || (group === 'score' && x.kind === 'score') || (group === 'patreon' && x.key.startsWith('patreon')) || (group === 'engagement' && x.kind === 'count'));
      const snaps = (W.data.metrics[s.id] || []).length || Math.max(...series.map((x) => x.points.length));
      box.innerHTML = `<div class="trhead"><div class="muted">${snaps} snapshots · since ${fmt.date(Math.min(...all))} · updated ${fmt.date(Math.max(...all))}</div>
        <div class="seg" style="margin-top:12px">${RANGES.map(([k, l]) => `<button data-tr="${k}" class="${range === k ? 'on' : ''}">${l}</button>`).join('')}</div>
        <div class="chips scroll" style="margin-top:10px">${GROUPS.map(([k, l]) => `<button class="chip ${group === k ? 'on' : ''}" data-tg="${k}">${l}</button>`).join('')}</div></div>
        <div class="trcards">${pick.length ? pick.map((x, i) => card(x, from, i)).join('') : `<div class="empty">${A.icon('query_stats')}<h3>No ${group} metrics</h3><p>This novel has no ${group} history yet.</p></div>`}</div>`;
    }
    function card(x, from, i) {
      const pts = x.points.filter((p) => p.t >= from);
      const use = pts.length >= 2 ? pts : null;
      const cur = x.points[x.points.length - 1].v;
      const prev = x.points.length > 1 ? x.points[x.points.length - 2].v : cur;
      const d = cur - prev;
      const m30 = x.points.filter((p) => p.t >= W.NOW - 30 * 86400000);
      const d30 = m30.length ? cur - m30[0].v : 0;
      const vs = (use || x.points).map((p) => p.v);
      const sign = (v) => (v > 0 ? '+' : v < 0 ? '−' : '±') + A.fmtVal(Math.abs(v), x.kind);
      const color = x.kind === 'score' ? 'var(--gold)' : x.key.startsWith('patreon') ? 'var(--copper)' : '#c9b98a';
      return `<div class="card trcard"><div class="row"><div class="grow"><div class="t">${esc(x.label)}</div><div class="big">${A.fmtVal(cur, x.kind)} <span class="${d > 0 ? 'sage' : d < 0 ? 'red' : 'muted'}" style="font-size:13px;font-weight:600">${sign(d)} since last sync</span></div></div></div>
        <div class="muted" style="font-size:12.5px;margin-bottom:8px">${sign(d30)} in the last 30 days · range ${A.fmtVal(Math.min(...vs), x.kind)}–${A.fmtVal(Math.max(...vs), x.kind)}</div>
        ${use ? A.chart(use, { h: 150, color, id: 'g' + s.id.replace(/\W/g, '') + i }) : '<div class="chart-empty">Not enough snapshots in this range</div>'}</div>`;
    }
    const onClick = (e) => {
      const t = e.target.closest('[data-tr],[data-tg],[data-t]'); if (!t) return;
      if (t.dataset.tr) range = t.dataset.tr; else if (t.dataset.tg) group = t.dataset.tg; else { A.sync(s); return; }
      render();
    };
    // every version, full size; swipe between them and pick one
    const browse = (vid) => {
      const st = W.ai.state(s.id);
      const items = st.versions.map((v) => ({ id: v.id, url: v.url || W.coverUrl(s), label: v.label, sub: v.id === st.inUse ? 'In use' : v.isNew ? 'New' : '', prompt: v.prompt }));
      A.viewCovers({
        items, index: Math.max(0, st.versions.findIndex((v) => v.id === vid)),
        actions: (it) => [it.id === W.ai.state(s.id).inUse ? { id: 'inuse', label: 'In use', icon: 'check', disabled: true } : { id: 'use', label: 'Use this cover', icon: 'check', fill: true }],
        onAction: (a, it, close, refresh) => { if (a === 'use') { useCover(it.id); A.toast(`Using “${it.label}”`); items.forEach((x) => (x.sub = x.id === it.id ? 'In use' : x.sub === 'In use' ? '' : x.sub)); refresh(); } },
      });
    };
    // the pending cover next to the current one, full size
    const compare = (i) => {
      const st = W.ai.state(s.id);
      const cur = st.versions.find((v) => v.id === st.inUse) || st.versions[0];
      const pend = st.versions.find((v) => v.id === st.pending);
      if (!pend) return;
      A.viewCovers({
        items: [{ url: cur.url || W.coverUrl(s), label: 'Current cover', sub: cur.label }, { url: pend.url, label: 'New cover', sub: pend.label, prompt: pend.prompt }], index: i,
        actions: () => [{ id: 'keep', label: 'Keep current' }, { id: 'use', label: 'Use new cover', icon: 'check', fill: true }],
        onAction: (a, it, close) => { close(); if (a === 'use') { useCover(pend.id); A.toast('Using the new cover'); } else { W.ai.keepCurrent(s.id); A.toast('Kept the current cover'); } },
      });
    };
    box.addEventListener('click', onClick);
    render();
    return () => {};
  };

  // ---------- AI ----------
  const art = (url) => `<img class="bk" src="${url}" alt="" aria-hidden="true" loading="lazy"><img class="art" src="${url}" alt="" loading="lazy">`;
  P.ai = (box, s) => {
    let mode = 'cover';
    let ctxChapters = 'Automatic';
    let prompt = '';
    let wrote = false;
    function render() {
      const st = W.ai.state(s.id);
      const coverDot = !!st.pending, descDot = !!st.draft;
      const job = st.job;
      const inUse = st.versions.find((v) => v.id === st.inUse) || st.versions[0];
      let body;
      if (mode === 'cover') {
        const pend = st.pending && st.versions.find((v) => v.id === st.pending);
        body = `${pend ? `<div class="card tint pad16 newcover"><div class="row"><h3 class="grow">New cover ready</h3><span class="pillb gold">New</span></div>
            <div class="cmp big"><button class="fig" data-x="cmpview" data-i="0"><div class="cimg">${inUse.url ? art(inUse.url) : A.coverHtml(s)}</div><span>Current</span></button><button class="fig" data-x="cmpview" data-i="1"><div class="cimg glow">${art(pend.url)}<span class="zoomhint">${A.icon('open_in_full', 'xs')}</span></div><span class="gold">New</span></button></div>
            <button class="btn tonal block" data-x="cmpview" data-i="1" style="margin:2px 0 10px">${A.icon('fullscreen', 'sm')}View full size</button>
            <div class="muted" style="font-size:12.5px;margin:4px 0 12px">OpenRouter: ${esc(st.cost || '$0.04 · 2 calls')}</div>
            <div class="btnrow"><button class="btn fill grow" data-x="use">Use new cover</button><button class="btn surface grow" data-x="keep">Keep current</button></div></div>` : ''}
          <div class="section-label">Covers<span class="aside">${st.versions.length} versions</span></div>
          <div class="cstrip">${st.versions.map((v) => `<button class="cv ${v.id === st.inUse ? 'inuse' : ''}" data-v="${v.id}"><div class="cimg">${v.url ? art(v.url) : A.coverHtml(s)}${v.id === st.inUse ? `<span class="cuse">${A.icon('check', 'xs')}</span>` : ''}${v.isNew ? '<span class="pillb gold cnew">New</span>' : ''}</div><span>${esc(v.label)}</span></button>`).join('')}</div>
          <div class="hint" style="margin-top:8px">${A.icon('info', 'sm muted')}<span>Tap a cover to see it full size, compare versions and choose one.</span></div>
          <div class="section-label">Make a new cover</div>
          <div class="card pad16">
            <button class="li tap" data-x="ctx" style="padding:0 0 12px;min-height:48px"><span class="lead">${A.icon('library_books')}</span><div class="grow"><div class="t">Cover context chapters</div><div class="s">${esc(ctxChapters)}</div></div>${A.icon('expand_more', 'trail')}</button>
            <label class="field-label">Image prompt (optional)</label>
            <div class="field"><textarea data-r="prompt" placeholder="Describe the cover, or leave empty and AI writes the prompt from the chapters.">${esc(prompt)}</textarea></div>
            <div class="btnrow" style="margin-top:12px"><button class="btn surface" data-x="write" ${job || wrote === 'busy' ? 'disabled' : ''}>${wrote === 'busy' ? '<span class="spinner"></span>Writing…' : A.icon('edit_note') + 'Write prompt with AI'}</button>
            <button class="btn fill grow" data-x="gen" ${job ? 'disabled' : ''}>${job && job.kind === 'cover' ? `<span class="spinner"></span>${esc(job.message)}` : A.icon('auto_awesome') + 'Generate cover'}</button></div>
            ${job && job.kind === 'cover' ? `<div style="margin-top:12px">${A.prog(0, 'indet')}</div>` : ''}
          </div>
          <div class="aifoot">Image: ${esc(W.ai.models.image)} · Prompt: ${esc(W.ai.models.description)} · <button data-x="aiset">Change in AI Settings</button></div>`;
      } else {
        body = `<div class="card pad16" style="margin-top:12px">
            <button class="li tap" data-x="ctx" style="padding:0 0 12px;min-height:48px"><span class="lead">${A.icon('library_books')}</span><div class="grow"><div class="t">Description context chapters</div><div class="s">${esc(ctxChapters)}</div></div>${A.icon('expand_more', 'trail')}</button>
            ${st.desc ? `<span class="pillb gold">${A.icon('auto_awesome', 'xs')}AI-generated</span><p class="aidesc">${esc(st.desc)}</p>` : `<p class="muted" style="margin:4px 0 12px">No AI description yet. The source description is shown in Details.</p>`}
            <button class="li tap" data-x="showai" style="padding:6px 0;min-height:48px"><span class="ck sq ${st.showAiDesc ? 'on' : ''}"></span><div class="grow">Show AI description in Details</div></button>
            <button class="btn fill block" data-x="gendesc" ${job ? 'disabled' : ''} style="margin-top:8px">${job && job.kind === 'description' ? '<span class="spinner"></span>Generating…' : A.icon('auto_awesome') + 'Generate description with AI'}</button>
          </div>
          ${st.draft ? `<div class="card tint pad16" style="margin-top:12px"><div class="row"><h3 class="grow">Draft</h3><span class="muted" style="font-size:12px">${esc(st.cost || '')}</span></div><p class="aidesc">${esc(st.draft)}</p><div class="btnrow"><button class="btn fill grow" data-x="apply">Apply</button><button class="btn surface grow" data-x="discard">Discard</button></div></div>` : ''}
          <div class="aifoot">Model: ${esc(W.ai.models.description)} · <button data-x="aiset">Change in AI Settings</button></div>`;
      }
      box.innerHTML = `<div class="aihead"><div class="seg"><button data-m="cover" class="${mode === 'cover' ? 'on' : ''}">${A.icon('image', 'sm')}Cover${coverDot ? '<i class="dotb"></i>' : ''}</button><button data-m="desc" class="${mode === 'desc' ? 'on' : ''}">${A.icon('notes', 'sm')}Description${descDot ? '<i class="dotb"></i>' : ''}</button></div></div>${body}`;
      const ta = box.querySelector('[data-r=prompt]');
      if (ta) ta.addEventListener('input', () => (prompt = ta.value));
    }
    const useCover = (vid) => { const v = W.ai.state(s.id).versions.find((x) => x.id === vid); if (v && v.id !== 'src') A.state.covers[s.id] = v.url; else delete A.state.covers[s.id]; W.ai.useCover(s.id, vid); A.emit(); };
    const onClick = (e) => {
      const t = e.target.closest('[data-m],[data-x],[data-v]'); if (!t) return;
      if (t.dataset.m) { mode = t.dataset.m; render(); return; }
      if (t.dataset.v) { browse(t.dataset.v); return; }
      const x = t.dataset.x;
      if (x === 'cmpview') { compare(+t.dataset.i); return; }
      if (x === 'use') { useCover(W.ai.state(s.id).pending); A.toast('Using the new cover'); }
      else if (x === 'keep') { W.ai.keepCurrent(s.id); A.toast('Kept the current cover'); }
      else if (x === 'ctx') A.menu({ title: 'Context chapters', sub: 'Which chapters the AI reads first', sections: [{ items: ['Automatic', 'First 3 chapters', 'Latest 3 chapters', 'Around my bookmark'].map((o) => ({ icon: 'library_books', label: o, on: o === ctxChapters, fn: () => { ctxChapters = o; render(); } })) }] });
      else if (x === 'write') { wrote = 'busy'; render(); setTimeout(() => { wrote = true; prompt = `A painted cover for “${s.title}”: ${s.tags.slice(0, 2).join(', ').toLowerCase()} mood, a lone figure under a stormy sky, rich oil texture, space for the title at the top, no text.`; render(); }, 1300); }
      else if (x === 'gen') { W.ai.generateCover(s.id, prompt.trim() || null); }
      else if (x === 'gendesc') W.ai.generateDescription(s.id);
      else if (x === 'apply') { W.ai.applyDraft(s.id); A.toast('Description updated'); A.emit(); }
      else if (x === 'discard') W.ai.discardDraft(s.id);
      else if (x === 'showai') { W.ai.setShowAi(s.id, !W.ai.state(s.id).showAiDesc); A.emit(); }
      else if (x === 'aiset') W.go('/settings/ai');
    };
    // every version, full size; swipe between them and pick one
    const browse = (vid) => {
      const st = W.ai.state(s.id);
      const items = st.versions.map((v) => ({ id: v.id, url: v.url || W.coverUrl(s), label: v.label, sub: v.id === st.inUse ? 'In use' : v.isNew ? 'New' : '', prompt: v.prompt }));
      A.viewCovers({
        items, index: Math.max(0, st.versions.findIndex((v) => v.id === vid)),
        actions: (it) => [it.id === W.ai.state(s.id).inUse ? { id: 'inuse', label: 'In use', icon: 'check', disabled: true } : { id: 'use', label: 'Use this cover', icon: 'check', fill: true }],
        onAction: (a, it, close, refresh) => { if (a === 'use') { useCover(it.id); A.toast(`Using “${it.label}”`); items.forEach((x) => (x.sub = x.id === it.id ? 'In use' : x.sub === 'In use' ? '' : x.sub)); refresh(); } },
      });
    };
    // the pending cover next to the current one, full size
    const compare = (i) => {
      const st = W.ai.state(s.id);
      const cur = st.versions.find((v) => v.id === st.inUse) || st.versions[0];
      const pend = st.versions.find((v) => v.id === st.pending);
      if (!pend) return;
      A.viewCovers({
        items: [{ url: cur.url || W.coverUrl(s), label: 'Current cover', sub: cur.label }, { url: pend.url, label: 'New cover', sub: pend.label, prompt: pend.prompt }], index: i,
        actions: () => [{ id: 'keep', label: 'Keep current' }, { id: 'use', label: 'Use new cover', icon: 'check', fill: true }],
        onAction: (a, it, close) => { close(); if (a === 'use') { useCover(pend.id); A.toast('Using the new cover'); } else { W.ai.keepCurrent(s.id); A.toast('Kept the current cover'); } },
      });
    };
    box.addEventListener('click', onClick);
    A.longPress(box, '[data-v]', (e, t) => {
      const v = W.ai.state(s.id).versions.find((x) => x.id === t.dataset.v);
      A.sheet({ title: `${v.label} prompt`, body: `<p class="aidesc">${esc(v.prompt || 'This is the cover from the source site. It has no prompt.')}</p>`, foot: `<button class="btn surface grow" data-y="copy">Copy</button><button class="btn fill grow" data-y="reuse" ${v.prompt ? '' : 'disabled'}>Reuse prompt</button>`, onMount: (sh, close) => W.on(sh, 'click', '[data-y]', (ev, bb) => { if (bb.dataset.y === 'reuse') { prompt = v.prompt; render(); } else A.toast('Prompt copied'); close(); }) });
    });
    const off = W.ai.on((st) => { if (st && st.versions && W.ai.state(s.id).job !== undefined) render(); });
    render();
    return () => off();
  };

  // ---------- global AI job chip ----------
  let chip = null;
  let lastJob = null;
  W.ai.on((st) => {
    const r = A.currentRoute && A.currentRoute();
    const job = st && st.job;
    const onAi = r && r.name === 'ai';
    if (job) lastJob = job.storyId;
    if (!job && lastJob) {
      const sid = lastJob; lastJob = null;
      if (chip) { chip.remove(); chip = null; }
      if (!onAi && st && (st.pending || st.draft)) A.toast(st.pending ? 'New cover ready' : 'Description draft ready', 'View', () => W.go(`/story/${sid}/ai`));
      return;
    }
    if (job && !onAi) {
      const s = W.story(job.storyId);
      if (!chip) { chip = W.el('<div class="jobchip"></div>'); document.getElementById('toasts').prepend(chip); }
      chip.innerHTML = `<span class="spinner"></span><span class="nowrap"><b>${esc(job.message)}</b> <span class="muted">${esc(A.short(s.title, 22))}</span></span><button class="ib" aria-label="Dismiss">${A.icon('close', 'sm')}</button>`;
      chip.querySelector('.ib').onclick = () => { chip.remove(); chip = null; };
      chip.onclick = (e) => { if (!e.target.closest('.ib')) W.go(`/story/${s.id}/ai`); };
    } else if (onAi && chip) { chip.remove(); chip = null; }
  });
})();
