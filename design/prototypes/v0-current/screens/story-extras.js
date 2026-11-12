/* v0 Select Chapters, Trends, AI Controls, Author Novels. */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon;
  const notFound = () => ({ el: W.el(`<div>${A.appBar({ title: 'Not found' })}<div class="empty"><b>Story not found</b></div></div>`) });

  // ---------- Select Chapters ----------
  A.register('select', (route) => {
    const s = W.story(route.params.id); if (!s) return notFound();
    const pending = W.chapters(s.id).filter((c) => !c.downloaded);
    const sel = new Set(); let rangeStart = null, rangeMode = false;
    const el = W.el(`<div>${A.appBar({ title: 'Select Chapters', sub: s.title })}<div class="scroll" data-keep data-sc></div><div data-foot></div></div>`);
    const sc = el.querySelector('[data-sc]');
    function hint() { return rangeMode ? (rangeStart == null ? 'Tap the first chapter in your range.' : `Chapter ${pending[rangeStart].index} is the start. Tap the last chapter.`) : 'Tap chapters individually, or long-press one and drag to select or clear a range.'; }
    function draw() {
      if (!pending.length) { sc.innerHTML = `<div class="empty"><div class="disc">${ic('done_all')}</div><b>All caught up</b><span>Every chapter of this story is already downloaded.</span></div>`; el.querySelector('[data-foot]').innerHTML = ''; return; }
      sc.innerHTML = `<div class="pad capped"><div class="sel-hint" data-hint>${esc(hint())}</div>
        <div class="btn-row" style="margin:12px 0 8px"><button class="btn ${rangeMode ? 'filled' : 'tonal'} sm" data-a="range">${ic('linear_scale')}Select Range</button><button class="btn text sm" data-a="all">All</button><button class="btn text sm" data-a="clear">Clear</button></div>
        <div data-rows>${pending.slice(0, 400).map((c, i) => `<div class="sel-row" data-i="${i}"><span class="cbox${sel.has(i) ? ' on' : ''}">${sel.has(i) ? ic('check') : ''}</span><span class="sel-idx">${c.index}</span><span class="sel-t">${esc(c.title)}</span></div>`).join('')}</div></div>`;
      foot();
    }
    function foot() { el.querySelector('[data-foot]').innerHTML = `<div class="bottom-bar"><button class="btn filled${sel.size ? '' : ' disabled'}" data-a="dl">${ic('download')}Download ${sel.size} Selected</button></div>`; }
    function paint() { el.querySelectorAll('.sel-row').forEach((r) => { const on = sel.has(+r.dataset.i); const b = r.querySelector('.cbox'); b.classList.toggle('on', on); b.innerHTML = on ? ic('check') : ''; }); const h = el.querySelector('[data-hint]'); if (h) h.textContent = hint(); foot(); }
    let drag = null;
    A.longPress(sc, '.sel-row', (r) => { const i = +r.dataset.i; drag = { from: i, on: !sel.has(i), base: new Set(sel) }; sc.style.touchAction = 'none'; applyDrag(i); });
    function applyDrag(to) { const [a, b] = [Math.min(drag.from, to), Math.max(drag.from, to)]; sel.clear(); drag.base.forEach((x) => sel.add(x)); for (let i = a; i <= b; i++) drag.on ? sel.add(i) : sel.delete(i); paint(); }
    sc.addEventListener('pointermove', (e) => { if (!drag) return; const r = document.elementFromPoint(e.clientX, e.clientY); const row = r && r.closest('.sel-row'); if (row) applyDrag(+row.dataset.i); });
    const endDrag = () => { if (drag) { drag = null; sc.style.touchAction = ''; } };
    sc.addEventListener('pointerup', endDrag); sc.addEventListener('pointercancel', endDrag);
    sc.addEventListener('touchmove', (e) => { if (drag) { e.preventDefault(); const t = e.touches[0]; const r = document.elementFromPoint(t.clientX, t.clientY); const row = r && r.closest('.sel-row'); if (row) applyDrag(+row.dataset.i); } }, { passive: false });
    el.addEventListener('click', (e) => {
      const r = e.target.closest('.sel-row');
      if (r) { const i = +r.dataset.i; if (rangeMode) { if (rangeStart == null) { rangeStart = i; sel.add(i); } else { const [a, b] = [Math.min(rangeStart, i), Math.max(rangeStart, i)]; for (let k = a; k <= b; k++) sel.add(k); rangeMode = false; rangeStart = null; } } else sel.has(i) ? sel.delete(i) : sel.add(i); paint(); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'range') { rangeMode = !rangeMode; rangeStart = null; draw(); }
      if (a.dataset.a === 'all') { pending.forEach((_, i) => sel.add(i)); paint(); }
      if (a.dataset.a === 'clear') { sel.clear(); paint(); }
      if (a.dataset.a === 'dl') { const ids = [...sel].map((i) => pending[i].id); W.downloads.enqueue(s.id, ids); A.toast(`Queued ${ids.length} chapters`); W.back(); }
    });
    draw();
    return { el };
  });

  // ---------- Trends ----------
  const RANGES = [['7D', 7], ['30D', 30], ['90D', 90], ['1Y', 365], ['All time', 0]];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['engagement', 'Engagement']];
  const fmtV = (kind, v) => kind === 'score' ? v.toFixed(2) : kind === 'usd' ? '$' + W.fmt.num(Math.round(v)) : W.fmt.num(Math.round(v));
  function chart(pts, kind) {
    const w = 600, h = 180, p = 6;
    if (pts.length < 2) return `<div class="tr-empty">Only one snapshot in this range</div>`;
    const t0 = pts[0].t, t1 = pts[pts.length - 1].t; let lo = Math.min(...pts.map((x) => x.v)), hi = Math.max(...pts.map((x) => x.v));
    if (hi === lo) { hi += kind === 'score' ? 0.05 : 1; lo -= kind === 'score' ? 0.05 : 1; }
    const X = (t) => p + ((t - t0) / Math.max(1, t1 - t0)) * (w - 2 * p), Y = (v) => p + (1 - (v - lo) / (hi - lo)) * (h - 2 * p - 14);
    const line = pts.map((x, i) => `${i ? 'L' : 'M'}${X(x.t).toFixed(1)},${Y(x.v).toFixed(1)}`).join('');
    return `<svg class="tr-chart" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><defs><linearGradient id="g${kind}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#C9A84C" stop-opacity=".35"/><stop offset="1" stop-color="#C9A84C" stop-opacity="0"/></linearGradient></defs>
      ${[0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${w}" y1="${(h - 14) * f}" y2="${(h - 14) * f}" stroke="#3D3A36" stroke-width="1"/>`).join('')}
      <path d="${line}L${X(t1)},${h - 14}L${X(t0)},${h - 14}Z" fill="url(#g${kind})"/><path d="${line}" fill="none" stroke="#C9A84C" stroke-width="2.5" vector-effect="non-scaling-stroke" stroke-linejoin="round"/>
      <circle cx="${X(t1)}" cy="${Y(pts[pts.length - 1].v)}" r="4" fill="#C9A84C"/></svg>
      <div class="tr-axis"><span>${W.fmt.dateShort(t0)}</span><span>${W.fmt.dateShort(t1)}</span></div>`;
  }
  A.register('trends', (route) => {
    const s = W.story(route.params.id); if (!s) return notFound();
    let range = 0, group = 'all';
    const series = W.trendSeries(s.id);
    const all = series.flatMap((x) => x.points.map((p) => p.t));
    const el = W.el(`<div>${A.appBar({ title: 'Trends', sub: s.title })}<div class="scroll pb-mini" data-keep data-sc></div></div>`);
    const sc = el.querySelector('[data-sc]');
    function draw() {
      if (!series.length) { sc.innerHTML = `<div class="empty"><div class="disc">${ic('show_chart')}</div><b>No trend data yet</b><span>Sync this story a few times to start tracking its numbers.</span></div>`; return; }
      const end = Math.max(...all);
      const inGroup = (x) => group === 'all' || (group === 'score' && x.kind === 'score') || (group === 'patreon' && x.key.startsWith('patreon')) || (group === 'engagement' && x.kind === 'count');
      const cards = series.filter(inGroup).map((x) => {
        const pts = range ? x.points.filter((p) => p.t >= end - range * 86400000) : x.points;
        const use = pts.length ? pts : x.points.slice(-1);
        const cur = use[use.length - 1].v, prev = use.length > 1 ? use[use.length - 2].v : cur;
        const m30 = x.points.filter((p) => p.t >= end - 30 * 86400000); const d30 = m30.length ? cur - m30[0].v : 0;
        const lo = Math.min(...use.map((p) => p.v)), hi = Math.max(...use.map((p) => p.v));
        const sign = (d) => (d >= 0 ? '+' : '−') + fmtV(x.kind, Math.abs(d));
        return `<div class="card tr-card"><h3>${esc(x.label)}</h3><div class="tr-cur">Current <b>${fmtV(x.kind, cur)}</b> <span class="muted">(${sign(cur - prev)} since last sync)</span></div>
          <div class="card-sub">${sign(d30)} in the last 30 days · range ${fmtV(x.kind, lo)}–${fmtV(x.kind, hi)}</div>${chart(use, x.kind)}</div>`;
      }).join('');
      sc.innerHTML = `<div class="pad capped"><div class="tr-head"><h2>${esc(s.title)}</h2><div class="muted">by ${esc(s.author)}</div><div class="muted" style="font-size:13px;margin-top:4px">${series[0].points.length} snapshots · since ${W.fmt.date(Math.min(...all))} · updated ${W.fmt.date(end)}</div></div>
        <div class="chip-row" style="margin:14px 0 8px">${RANGES.map(([l, d]) => `<button class="chip${range === d ? ' sel' : ''}" data-r="${d}">${range === d ? ic('check') : ''}${l}</button>`).join('')}</div>
        <div class="chip-row" style="margin-bottom:16px">${GROUPS.map(([k, l]) => `<button class="chip${group === k ? ' sel' : ''}" data-g="${k}">${group === k ? ic('check') : ''}${l}</button>`).join('')}</div>
        ${cards || '<div class="empty" style="min-height:0"><b>No metrics in this group</b></div>'}</div>`;
    }
    sc.addEventListener('click', (e) => { const r = e.target.closest('[data-r]'), g = e.target.closest('[data-g]'); if (r) { range = +r.dataset.r; draw(); } if (g) { group = g.dataset.g; draw(); } });
    draw();
    return { el };
  });

  // ---------- AI Controls ----------
  const aiTab = {};
  A.register('ai', (route, ctx) => {
    const s = W.story(route.params.id); if (!s) return notFound();
    let tab = route.query.tab || aiTab[s.id] || 'cover', prompt = '', seenDesc = true, ctxChapters = 'Automatic';
    const el = W.el(`<div>${A.appBar({ title: 'AI Controls', sub: s.title })}<div class="scroll pb-mini" data-keep data-sc></div></div>`);
    const sc = el.querySelector('[data-sc]');
    function coverTab(x) {
      const cur = x.versions.find((v) => v.id === x.inUse) || x.versions[0];
      const nv = x.pending && x.versions.find((v) => v.id === x.pending);
      const thumb = (v) => v.url ? `<img src="${v.url}" alt="" loading="lazy">` : `<div class="cover fallback" style="background:${W.coverFallback(s)};height:100%"><span>${esc(s.title)}</span></div>`;
      return `${nv ? `<div class="card ai-new"><h3>New cover ready</h3><div class="ai-pair"><figure>${thumb(cur)}<figcaption>Current</figcaption></figure><figure>${thumb(nv)}<figcaption>New</figcaption></figure></div>
          <div class="card-sub">OpenRouter: ${esc(x.cost || '$0.04 · 2 calls')}</div><div class="btn-row" style="margin-top:12px"><button class="btn filled sm" data-a="use">Use new cover</button><button class="btn text sm" data-a="keep">Keep current</button></div></div>` : ''}
        <div class="sec-label">Covers</div>
        <div class="ai-strip">${x.versions.map((v) => `<button class="ai-ver${v.id === x.inUse ? ' inuse' : ''}" data-v="${v.id}">${thumb(v)}${v.id === x.inUse ? `<span class="ai-check">${ic('check')}</span>` : ''}${v.isNew ? '<span class="ai-newb">New</span>' : ''}<span class="ai-lbl">${esc(v.label)}</span></button>`).join('')}</div>
        <div class="sec-help">Tap a cover to use it. Long-press to see its prompt.</div>
        <div class="sec-label">Make a new cover</div>
        <button class="row" data-a="ctx" style="padding:8px 0;min-height:52px"><div class="rt"><b style="font-size:15px">Cover context chapters</b><span>${ctxChapters}</span></div>${ic('chevron_right')}</button>
        <div class="field area"><textarea data-prompt placeholder="Image prompt (optional)">${esc(prompt)}</textarea></div>
        <div class="btn-row" style="margin-top:12px"><button class="btn text" data-a="wp">${ic('auto_awesome')}Write prompt with AI</button></div>
        ${x.job && x.job.kind === 'cover' ? `<button class="btn filled full disabled" style="margin-top:8px"><span class="spinner"></span>${esc(x.job.message)}</button>` : `<button class="btn filled full" data-a="gen" style="margin-top:8px">${ic('auto_awesome')}Generate cover</button>`}
        <div class="sec-help" style="margin-top:14px">Image: ${esc(W.ai.models.image)} · Prompt: ${esc(W.ai.models.description)} · <button class="btn text sm" data-a="aiset" style="padding:0;min-height:0;display:inline">Change in AI Settings</button></div>`;
    }
    function descTab(x) {
      return `<button class="row" data-a="ctx" style="padding:8px 0;min-height:52px"><div class="rt"><b style="font-size:15px">Context chapters</b><span>${ctxChapters}</span></div>${ic('chevron_right')}</button>
        ${x.desc ? `<div class="card" style="margin-top:8px"><span class="pill upd">${ic('auto_awesome')}AI-generated</span><p style="margin:10px 0 0;line-height:1.5">${esc(x.desc)}</p></div>` : `<div class="card" style="margin-top:8px"><div class="card-sub">No AI description yet. The source description is shown in Details.</div></div>`}
        <button class="row" data-a="showai" style="padding:12px 0;min-height:52px"><span class="cbox${x.showAiDesc ? ' on' : ''}">${x.showAiDesc ? ic('check') : ''}</span><div class="rt"><b style="font-size:15px;font-weight:400">Show AI description in Details</b></div></button>
        ${x.job && x.job.kind === 'description' ? `<button class="btn filled full disabled"><span class="spinner"></span>Generating...</button>` : `<button class="btn filled full" data-a="gendesc">${ic('auto_awesome')}Generate Description with AI</button>`}
        ${x.draft ? `<div class="card ai-draft" style="margin-top:16px"><h3>Draft</h3><p style="margin:8px 0;line-height:1.5">${esc(x.draft)}</p><div class="card-sub">OpenRouter: ${esc(x.cost || '')}</div><div class="btn-row" style="margin-top:10px"><button class="btn filled sm" data-a="apply">Apply</button><button class="btn text sm" data-a="discard">Discard</button></div></div>` : ''}`;
    }
    function draw() {
      const x = W.ai.state(s.id); aiTab[s.id] = tab;
      const dot = x.draft && tab !== 'description';
      const pr = sc.querySelector('[data-prompt]'); if (pr) prompt = pr.value;
      sc.innerHTML = `<div class="pad capped"><div class="chip-row" style="margin-bottom:8px"><button class="chip${tab === 'cover' ? ' sel' : ''}" data-tab="cover">${tab === 'cover' ? ic('check') : ''}Cover${x.pending && tab !== 'cover' ? ' •' : ''}</button><button class="chip${tab === 'description' ? ' sel' : ''}" data-tab="description">${tab === 'description' ? ic('check') : ''}Description${dot ? ' •' : ''}</button></div>
        ${tab === 'cover' ? coverTab(x) : descTab(x)}</div>`;
      void seenDesc;
    }
    ctx.sub(W.ai.on(() => draw()));
    A.longPress(sc, '[data-v]', (b) => { const v = W.ai.state(s.id).versions.find((y) => y.id === b.dataset.v); A.dialog({ title: v.label, body: `<p style="margin:0">${esc(v.prompt || 'Source cover from the story page. No prompt.')}</p>`, actions: [{ label: 'Close' }] }); });
    sc.addEventListener('input', (e) => { if (e.target.matches('[data-prompt]')) prompt = e.target.value; });
    sc.addEventListener('click', (e) => {
      const t = e.target.closest('[data-tab]'); if (t) { tab = t.dataset.tab; draw(); return; }
      const v = e.target.closest('[data-v]'); if (v) { W.ai.useCover(s.id, v.dataset.v); A.toast('Cover updated'); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const k = a.dataset.a;
      if (k === 'use') { W.ai.useCover(s.id, W.ai.state(s.id).pending); A.toast('Using the new cover'); }
      if (k === 'keep') W.ai.keepCurrent(s.id);
      if (k === 'gen') W.ai.generateCover(s.id, prompt.trim());
      if (k === 'wp') { prompt = `A painted cover for "${s.title}": ${s.tags.slice(0, 3).join(', ').toLowerCase()} mood, a lone figure against dramatic light, rich texture, no text.`; draw(); A.toast('Prompt written'); }
      if (k === 'ctx') A.choose({ title: 'Context chapters', options: ['Automatic', 'First 3 chapters', 'First 10 chapters', 'Latest 5 chapters'].map((o) => ({ value: o, label: o })), value: ctxChapters, fn: (val) => { ctxChapters = val; draw(); } });
      if (k === 'aiset') W.go('/settings/ai');
      if (k === 'gendesc') W.ai.generateDescription(s.id);
      if (k === 'apply') { W.ai.applyDraft(s.id); A.toast('Description applied'); }
      if (k === 'discard') W.ai.discardDraft(s.id);
      if (k === 'showai') W.ai.setShowAi(s.id, !W.ai.state(s.id).showAiDesc);
    });
    draw();
    return { el };
  });

  // ---------- Author Novels ----------
  A.register('author', (route) => {
    const s = W.story(route.params.id); if (!s) return notFound();
    const list = W.authorNovels(s.id);
    const el = W.el(`<div>${A.appBar({ title: s.author || 'Unknown author', sub: `${list.length} other novel${list.length === 1 ? '' : 's'} in your library` })}<div class="scroll pb-mini" data-keep>
      ${list.length ? `<div class="lgrid">${list.map((o) => `<article class="lcard" data-id="${o.id}"><div class="lc-main"><div class="lc-cover">${A.cover(o, 'c-list')}</div><div class="lc-info"><h3>${esc(o.title)}</h3><div class="lc-by">by ${esc(o.author)}</div><div class="lc-badges"><span class="lc-src">${esc(o.source)}</span>${A.pubPill(o)}</div>${o.score ? `<div class="lc-score">${ic('star')}<b>${A.score(o)}</b></div>` : ''}<div class="lc-tags">${o.tags.slice(0, 5).map(esc).join('<i> &nbsp;•&nbsp; </i>')}</div></div></div><div class="lc-foot"><b>${o.downloaded} / ${o.total}</b>${A.capsule(o)}</div></article>`).join('')}</div>`
        : `<div class="empty"><div class="disc">${ic('person')}</div><b>No other novels</b><span>This is the only novel by ${esc(s.author)} in your library.</span></div>`}</div></div>`);
    el.addEventListener('click', (e) => { const c = e.target.closest('[data-id]'); if (c) W.go('/story/' + c.dataset.id); });
    return { el };
  });
})();
