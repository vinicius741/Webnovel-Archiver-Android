/* Select Chapters, Trends, AI Controls. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const missing = (ctx) => { ctx.el.innerHTML = `${V2.bar({ title: 'Not found', cls: 'always' })}<div class="scroll"><div class="empty"><h3>Story not found</h3></div></div>`; };

  // ---------- Select chapters ----------
  V2.screens.select = (ctx) => {
    const s = WNA.story(ctx.params.id); if (!s) return missing(ctx);
    const page = ctx.el; V2.theme(page, s);
    const list = WNA.chapters(s.id).filter((c) => !c.downloaded);
    const sel = new Set(); let range = null; // null | 'start' | index
    page.innerHTML = `${V2.bar({ title: 'Select chapters', sub: s.title, cls: 'always' })}
      <div class="scroll sel-page"><div class="maxw">
        ${list.length ? `<div class="wrap"><div class="sel-hint card mid"><span class="hint-ic">${ic('touch_app')}</span><p class="hint-t">Tap chapters individually, or long-press one and drag to select or clear a range.</p></div>
        <div class="bgroup sel-tools"><button class="btn tonal" data-a="range">${ic('linear_scale')}Select range</button><button class="btn tonal" data-a="all">All</button><button class="btn tonal" data-a="clear">Clear</button></div></div>
        <div class="wrap"><div class="sel-list">${list.map((c, i) => `<div class="sel-row" data-i="${i}"><span class="cbox">${ic('check')}</span><span class="idx">${c.index}</span><b class="grow clamp2">${esc(c.title)}</b></div>`).join('')}</div></div>`
          : `<div class="empty"><div class="blob">${ic('task_alt')}</div><h3>All caught up</h3><p>Every chapter of this story is already downloaded.</p><button class="btn tonal" data-a="back">Back to story</button></div>`}
      </div></div>
      ${list.length ? `<div class="sel-go"><button class="btn filled l block" data-a="go" disabled>${ic('download')}<span>Select chapters to download</span></button></div>` : ''}`;
    if (!list.length) return;
    const rows = [...page.querySelectorAll('.sel-row')];
    const hint = page.querySelector('.hint-t');
    const paint = () => {
      rows.forEach((r, i) => { r.classList.toggle('on', sel.has(i)); r.firstElementChild.classList.toggle('on', sel.has(i)); r.classList.toggle('start', range === i); });
      const go = page.querySelector('[data-a="go"]'); go.disabled = !sel.size;
      go.querySelector('span').textContent = sel.size ? `Download ${V2.plural(sel.size, 'chapter')}` : 'Select chapters to download';
      page.querySelector('[data-a="range"]').classList.toggle('sel', range != null);
      hint.textContent = range === 'start' ? 'Tap the first chapter in your range.' : typeof range === 'number' ? `Chapter ${list[range].index} is the start. Tap the last chapter.` : 'Tap chapters individually, or long-press one and drag to select or clear a range.';
    };
    // long-press + drag range selection
    let drag = null;
    V2.longPress(page.querySelector('.sel-list'), '.sel-row', (row) => { const i = +row.dataset.i; drag = { from: i, on: !sel.has(i), base: new Set(sel) }; apply(i); });
    const apply = (j) => { const [a, b] = [Math.min(drag.from, j), Math.max(drag.from, j)]; sel.clear(); drag.base.forEach((x) => sel.add(x)); for (let k = a; k <= b; k++) drag.on ? sel.add(k) : sel.delete(k); paint(); };
    page.addEventListener('touchmove', (e) => { if (drag) e.preventDefault(); }, { passive: false });
    page.addEventListener('pointermove', (e) => { if (!drag) return; const r = document.elementFromPoint(e.clientX, e.clientY); const row = r && r.closest('.sel-row'); if (row) apply(+row.dataset.i); });
    const end = () => { if (drag) { drag = null; } };
    page.addEventListener('pointerup', end); page.addEventListener('pointercancel', end);
    page.addEventListener('click', (e) => {
      const row = e.target.closest('.sel-row');
      if (row) {
        const i = +row.dataset.i;
        if (range === 'start') { range = i; paint(); return; }
        if (typeof range === 'number') { const [a, b] = [Math.min(range, i), Math.max(range, i)]; for (let k = a; k <= b; k++) sel.add(k); range = null; paint(); V2.snack(`Selected chapters ${list[a].index}–${list[b].index}`); return; }
        sel.has(i) ? sel.delete(i) : sel.add(i); paint(); return;
      }
      const a = e.target.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'range') { range = range == null ? 'start' : null; paint(); }
      if (a.dataset.a === 'all') { list.forEach((_, i) => sel.add(i)); range = null; paint(); }
      if (a.dataset.a === 'clear') { sel.clear(); range = null; paint(); }
      if (a.dataset.a === 'go') {
        const ids = [...sel].sort((x, y) => x - y).map((i) => list[i].id);
        WNA.downloads.enqueue(s.id, ids); V2.back();
        setTimeout(() => V2.snack(`Downloading ${V2.plural(ids.length, 'chapter')}`, { action: 'View', onAction: () => V2.go('/queue') }), 300);
      }
    });
    paint();
  };

  // ---------- Trends ----------
  const RANGES = [['7D', 7], ['30D', 30], ['90D', 90], ['1Y', 365], ['All', 0]];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['eng', 'Engagement']];
  const groupOf = (x) => (x.kind === 'score' ? 'score' : x.kind === 'members' || x.kind === 'usd' ? 'patreon' : 'eng');
  const fv = (x, v) => (x.kind === 'score' ? v.toFixed(2) : x.kind === 'usd' ? fmt.money(v) : x.kind === 'members' ? fmt.int(v) : fmt.num(v));
  const fd = (x, d) => { const sgn = d > 0 ? '+' : d < 0 ? '−' : '±'; const a = Math.abs(d); return sgn + (x.kind === 'score' ? a.toFixed(2) : x.kind === 'usd' ? fmt.money(a) : fmt.num(Math.round(a))); };
  function monotone(pts) {
    const n = pts.length; if (n < 2) return ''; if (n === 2) return `M${pts[0][0]},${pts[0][1]}L${pts[1][0]},${pts[1][1]}`;
    const dx = [], dy = [], m = [], t = [];
    for (let i = 0; i < n - 1; i++) { dx[i] = pts[i + 1][0] - pts[i][0]; dy[i] = pts[i + 1][1] - pts[i][1]; m[i] = dx[i] ? dy[i] / dx[i] : 0; }
    t[0] = m[0]; t[n - 1] = m[n - 2];
    for (let i = 1; i < n - 1; i++) t[i] = m[i - 1] * m[i] <= 0 ? 0 : (3 * (dx[i - 1] + dx[i])) / ((2 * dx[i] + dx[i - 1]) / m[i - 1] + (dx[i] + 2 * dx[i - 1]) / m[i]);
    let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
    for (let i = 0; i < n - 1; i++) { const h = dx[i] / 3; d += `C${(pts[i][0] + h).toFixed(1)},${(pts[i][1] + t[i] * h).toFixed(1)} ${(pts[i + 1][0] - h).toFixed(1)},${(pts[i + 1][1] - t[i + 1] * h).toFixed(1)} ${pts[i + 1][0].toFixed(1)},${pts[i + 1][1].toFixed(1)}`; }
    return d;
  }
  function chart(x, pts, W, H = 180) {
    const pad = { l: 4, r: 4, t: 14, b: 22 };
    const vs = pts.map((p) => p.v); let lo = Math.min(...vs), hi = Math.max(...vs);
    if (hi - lo < 1e-9) { lo -= x.kind === 'score' ? 0.05 : 1; hi += x.kind === 'score' ? 0.05 : 1; }
    const span = hi - lo; lo -= span * 0.08; hi += span * 0.12;
    const t0 = pts[0].t, t1 = pts[pts.length - 1].t || t0 + 1;
    const X = (t) => pad.l + ((t - t0) / Math.max(1, t1 - t0)) * (W - pad.l - pad.r), Y = (v) => pad.t + (1 - (v - lo) / (hi - lo)) * (H - pad.t - pad.b);
    const xy = pts.map((p) => [X(p.t), Y(p.v)]);
    const line = monotone(xy); const gid = 'g' + Math.random().toString(36).slice(2, 7);
    const last = xy[xy.length - 1];
    const grid = [0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${W}" y1="${pad.t + f * (H - pad.t - pad.b)}" y2="${pad.t + f * (H - pad.t - pad.b)}" class="gl"/>`).join('');
    return `<svg class="chart" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" data-w="${W}"><defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--primary)" stop-opacity=".38"/><stop offset="1" stop-color="var(--primary)" stop-opacity="0"/></linearGradient></defs>${grid}
      <path d="${line}L${last[0]},${H - pad.b}L${xy[0][0]},${H - pad.b}Z" fill="url(#${gid})"/><path d="${line}" class="ln"/>
      <circle cx="${last[0]}" cy="${last[1]}" r="5" class="dot"/><text x="2" y="${H - 4}" class="ax">${fmt.dateShort(t0)}</text><text x="${W - 2}" y="${H - 4}" class="ax" text-anchor="end">${fmt.dateShort(t1)}</text>
      <g class="scrub" visibility="hidden"><line y1="${pad.t - 6}" y2="${H - pad.b}" class="sl"/><circle r="6" class="sd"/></g></svg>`;
  }
  V2.screens.trends = (ctx) => {
    const s = WNA.story(ctx.params.id); if (!s) return missing(ctx);
    const page = ctx.el; V2.theme(page, s);
    const all = WNA.trendSeries(s.id); const snaps = (WNA.data.metrics[s.id] || []);
    const st = { range: 0, group: 'all' };
    page.innerHTML = `${V2.bar({ title: 'Trends', sub: s.title })}<div class="scroll"><div class="maxw">
      ${V2.large('Trends', s.title)}
      <div class="wrap"><p class="t-bs muted tr-meta">by ${esc(s.author)} · ${snaps.length ? `${snaps.length} snapshots · since ${fmt.date(snaps[0].at)} · updated ${fmt.date(snaps[snaps.length - 1].at)}` : 'estimated history'}</p>
        <div class="seg tr-range">${RANGES.map(([l], i) => `<button data-r="${i}" class="${i === RANGES.length - 1 ? 'sel' : ''}">${ic('check')}${l}</button>`).join('')}</div>
        <div class="chips scroller tr-groups" style="margin-top:12px"></div></div>
      <div class="wrap tr-cards"></div></div></div>`;
    st.range = RANGES.length - 1;
    V2.collapse(page);
    const cards = page.querySelector('.tr-cards');
    function render() {
      page.querySelector('.tr-groups').innerHTML = GROUPS.filter(([k]) => k === 'all' || all.some((x) => groupOf(x) === k)).map(([k, l]) => `<button class="chip ${st.group === k ? 'sel' : ''}" data-g="${k}">${st.group === k ? ic('check', 'chk') : ''}${l}</button>`).join('');
      const list = all.filter((x) => st.group === 'all' || groupOf(x) === st.group);
      if (!list.length) { cards.innerHTML = `<div class="empty"><div class="blob">${ic('monitoring')}</div><h3>No trend data yet</h3><p>Trends appear after a few syncs.</p></div>`; return; }
      const days = RANGES[st.range][1]; const from = days ? WNA.NOW - days * 86400000 : 0;
      cards.innerHTML = list.map((x, i) => {
        const pts = x.points.filter((p) => p.t >= from); const cur = x.points[x.points.length - 1].v; const prev = x.points.length > 1 ? x.points[x.points.length - 2].v : cur;
        const m30 = x.points.filter((p) => p.t >= WNA.NOW - 30 * 86400000); const d30 = m30.length > 1 ? cur - m30[0].v : 0;
        const vs = pts.map((p) => p.v);
        return `<section class="card tr-card" data-k="${i}"><div class="row"><h3 class="grow">${esc(x.label)}</h3><span class="pill ${cur - prev >= 0 ? 'ongoing' : 'bad'}">${ic(cur - prev >= 0 ? 'arrow_upward' : 'arrow_downward')}${fd(x, cur - prev)}</span></div>
          <div class="tr-cur">${fv(x, cur)}<small>current · ${fd(x, cur - prev)} since last sync</small></div>
          <p class="t-bs muted">${fd(x, d30)} in the last 30 days${vs.length ? ` · range ${fv(x, Math.min(...vs))}–${fv(x, Math.max(...vs))}` : ''}</p>
          <div class="tr-chart" data-k="${i}">${pts.length > 1 ? '' : `<div class="tr-none">${ic('timeline')}Not enough data in this range</div>`}</div><div class="tr-tip t-lm"></div></section>`;
      }).join('');
      requestAnimationFrame(() => cards.querySelectorAll('.tr-chart').forEach((c) => {
        const x = list[+c.dataset.k]; const pts = x.points.filter((p) => p.t >= from); if (pts.length < 2) return;
        c.innerHTML = chart(x, pts, Math.max(200, c.clientWidth)); c._pts = pts; c._x = x;
        const ln = c.querySelector('.ln'); const L = ln.getTotalLength(); ln.style.strokeDasharray = L; ln.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], { duration: 900, easing: 'cubic-bezier(.2,0,0,1)' });
      }));
    }
    function scrub(c, e) {
      const svg = c.querySelector('svg'); if (!svg || !c._pts) return; const r = svg.getBoundingClientRect(); const W = +svg.dataset.w;
      const px = V2.clamp(e.clientX - r.left, 0, W); const pts = c._pts; const t0 = pts[0].t, t1 = pts[pts.length - 1].t;
      const tt = t0 + ((px - 4) / (W - 8)) * (t1 - t0); let best = pts[0]; pts.forEach((p) => { if (Math.abs(p.t - tt) < Math.abs(best.t - tt)) best = p; });
      const path = svg.querySelector('.ln'); const bx = 4 + ((best.t - t0) / Math.max(1, t1 - t0)) * (W - 8);
      // find y on the drawn path near bx
      const L = path.getTotalLength(); let lo = 0, hi = L; for (let k = 0; k < 18; k++) { const mid = (lo + hi) / 2; path.getPointAtLength(mid).x < bx ? (lo = mid) : (hi = mid); }
      const pt = path.getPointAtLength(lo);
      const g = svg.querySelector('.scrub'); g.setAttribute('visibility', 'visible'); g.querySelector('line').setAttribute('x1', bx); g.querySelector('line').setAttribute('x2', bx); g.querySelector('circle').setAttribute('cx', bx); g.querySelector('circle').setAttribute('cy', pt.y);
      const tip = c.nextElementSibling; tip.textContent = `${fv(c._x, best.v)} · ${fmt.date(best.t)}`; tip.classList.add('on');
    }
    cards.addEventListener('pointerdown', (e) => { const c = e.target.closest('.tr-chart'); if (c) scrub(c, e); });
    cards.addEventListener('pointermove', (e) => { const c = e.target.closest('.tr-chart'); if (c && e.buttons) scrub(c, e); });
    page.addEventListener('click', (e) => {
      const r = e.target.closest('[data-r]'); if (r) { st.range = +r.dataset.r; page.querySelectorAll('[data-r]').forEach((b) => b.classList.toggle('sel', b === r)); render(); return; }
      const g = e.target.closest('[data-g]'); if (g) { st.group = g.dataset.g; render(); }
    });
    render();
  };

  // ---------- AI Controls ----------
  const aiUI = {}; // storyId -> {tab, prompt, ctxCh, seenCover, seenDesc, writing}
  V2.screens.ai = (ctx) => {
    const s = WNA.story(ctx.params.id); if (!s) return missing(ctx);
    const page = ctx.el; V2.theme(page, s);
    const u = (aiUI[s.id] = aiUI[s.id] || { tab: ctx.query.tab || 'cover', prompt: '', ctxCh: 'Automatic', descCh: 'Automatic', writing: false });
    page.innerHTML = `${V2.bar({ title: 'AI controls', sub: s.title })}<div class="scroll"><div class="maxw">
      ${V2.large('AI controls', s.title)}
      <div class="wrap"><div class="seg l ai-tabs"></div></div><div class="ai-body"></div></div></div>`;
    V2.collapse(page);
    const body = page.querySelector('.ai-body');
    const M = WNA.ai.models;
    function render() {
      const a = WNA.ai.state(s.id);
      page.querySelector('.ai-tabs').innerHTML = `<button data-t="cover" class="${u.tab === 'cover' ? 'sel' : ''}">${ic('check')}Cover${a.pending && u.tab !== 'cover' ? '<i class="bdot"></i>' : ''}</button><button data-t="desc" class="${u.tab === 'desc' ? 'sel' : ''}">${ic('check')}Description${a.draft && u.tab !== 'desc' ? '<i class="bdot"></i>' : ''}</button>`;
      const job = a.job;
      const jobCard = (kind) => job && job.kind === kind ? `<div class="ai-job"><span class="loader accent"></span><div class="grow"><b class="t-tm">${esc(job.message)}</b>${V2.wavy(kind === 'cover' ? (job.message.startsWith('Painting') ? 0.62 : 0.24) : 0.5)}</div></div>` : '';
      if (u.tab === 'cover') {
        const cur = a.versions.find((v) => v.id === a.inUse) || a.versions[0]; const nv = a.pending && a.versions.find((v) => v.id === a.pending);
        const img = (v, w) => v.url ? `<div class="cov" style="width:${w}px;height:${w * 1.5}px"><img src="${v.url}" alt="" loading="lazy"></div>` : V2.cover(s, { w });
        body.innerHTML = `
          ${nv ? `<div class="wrap"><section class="card pcard ai-new"><div class="row"><span class="pat-ic">${ic('auto_awesome', 'fill s20')}</span><h3 class="grow">New cover ready</h3></div>
            <div class="ai-cmp"><figure>${img(cur, 104)}<figcaption>Current</figcaption></figure>${ic('arrow_forward', 's28')}<figure>${img(nv, 104)}<figcaption>New</figcaption></figure></div>
            <p class="t-bs">OpenRouter: ${esc(a.cost || '$0.04 · 2 calls')}</p><div class="row"><button class="btn filled grow" data-a="use-new">Use new cover</button><button class="btn text" data-a="keep">Keep current</button></div></section></div>` : ''}
          <div class="sec-h"><h2>Covers</h2><span class="t-lm dim">${a.versions.length} versions</span></div>
          <div class="ai-car">${a.versions.map((v) => `<button class="ai-v ${v.id === a.inUse ? 'use' : ''}" data-v="${v.id}">${img(v, 132)}${v.id === a.inUse ? `<span class="ai-chk">${ic('check')}</span>` : ''}${v.isNew ? '<span class="pill gold ai-newb">New</span>' : ''}<span class="t-lm">${esc(v.label)}</span></button>`).join('')}</div>
          <p class="sec-hint">Tap a cover to use it. Long-press to see its prompt.</p>
          <div class="wrap"><section class="card ai-make"><h3>Make a new cover</h3>
            <button class="li ctxrow" data-a="ctxch"><span class="lead plain">${ic('library_books')}</span><span class="tx"><b>Cover context chapters</b><span>${esc(u.ctxCh)}</span></span>${ic('arrow_drop_down')}</button>
            <label class="tf"><label>Image prompt (optional)</label><textarea data-prompt rows="4" placeholder="Describe the scene, mood and style…">${esc(u.prompt)}</textarea></label>
            ${jobCard('cover') || `<div class="row wrapbtns"><button class="btn tonal grow" data-a="write" ${u.writing ? 'disabled' : ''}>${u.writing ? '<span class="loader" style="width:20px;height:20px"></span>Writing…' : `${ic('edit_note')}Write prompt with AI`}</button><button class="btn filled grow" data-a="gen" ${job ? 'disabled' : ''}>${ic('auto_awesome')}Generate cover</button></div>`}
          </section></div>
          <p class="ai-foot t-bs dim">Image: ${esc(M.image)} · Prompt: ${esc(M.description)} · <button class="lnk" data-a="settings">Change in AI Settings</button></p>`;
      } else {
        body.innerHTML = `<div class="wrap col" style="gap:14px;padding-top:16px">
          <button class="li ctxrow card mid" data-a="descch"><span class="lead plain">${ic('library_books')}</span><span class="tx"><b>Description context chapters</b><span>${esc(u.descCh)}</span></span>${ic('arrow_drop_down')}</button>
          ${a.desc ? `<section class="card"><div class="row"><h3 class="grow">AI description</h3><span class="pill gold">${ic('auto_awesome', 's16')}AI-generated</span></div><p class="ai-desc">${esc(a.desc)}</p>
             <button class="row swrow" data-a="show"><span class="grow">Show AI description in Details</span><span class="switch ${a.showAiDesc ? 'on' : ''}"></span></button></section>` : `<section class="card outline"><h3>Current description</h3><p class="ai-desc clamp3">${esc(s.desc || 'No description from the source.')}</p><p class="t-bs dim" style="margin-top:8px">From ${esc(s.source)}</p></section>`}
          ${a.draft ? `<section class="card pcard"><div class="row"><span class="pat-ic">${ic('draft', 'fill s20')}</span><h3 class="grow">Draft</h3><span class="t-bs">${esc(a.cost || '')}</span></div><p class="ai-desc" style="color:inherit">${esc(a.draft)}</p><div class="row" style="margin-top:12px"><button class="btn filled grow" data-a="apply">Apply</button><button class="btn text" data-a="discard">Discard</button></div></section>` : ''}
          ${jobCard('description') ? `<section class="card">${jobCard('description')}</section>` : `<button class="btn filled l block" data-a="gendesc" ${job ? 'disabled' : ''}>${ic('auto_awesome')}Generate description with AI</button>`}
          <p class="t-bs dim">Model: ${esc(M.description)} · <button class="lnk" data-a="settings">Change in AI Settings</button></p></div>`;
      }
    }
    ctx.sub(WNA.ai.on(() => { if (page.isConnected) render(); }));
    V2.longPress(body, '[data-v]', (b) => {
      const v = WNA.ai.state(s.id).versions.find((x) => x.id === b.dataset.v);
      V2.sheet({ title: `${v.label} prompt`, body: `<div class="pad"><p class="ai-desc">${v.prompt ? esc(v.prompt) : 'This is the cover from the source site. It has no prompt.'}</p></div>`, foot: v.prompt ? `<button class="btn tonal" onclick="V2.copy(this.dataset.p,'Prompt copied')" data-p="${esc(v.prompt)}">${ic('content_copy')}Copy</button>` : '' });
    });
    function useCover(vid) {
      const a = WNA.ai.state(s.id); const prev = a.inUse; const v = a.versions.find((x) => x.id === vid);
      WNA.ai.useCover(s.id, vid);
      if (vid === 'src') delete V2.aiCovers[s.id]; else V2.aiCovers[s.id] = v.url;
      V2.forgetPalette(s.id); V2.theme(page, s);
      V2.snack(`Using ${v.label} cover`, { action: 'Undo', onAction: () => useCover(prev) });
    }
    page.addEventListener('input', (e) => { if (e.target.matches('[data-prompt]')) u.prompt = e.target.value; });
    page.addEventListener('click', async (e) => {
      const t = e.target.closest('[data-t]'); if (t) { u.tab = t.dataset.t; render(); return; }
      const v = e.target.closest('[data-v]'); if (v) { if (v.dataset.v !== WNA.ai.state(s.id).inUse) useCover(v.dataset.v); return; }
      const sw = e.target.closest('[data-a]'); if (!sw) return;
      const opts = ['Automatic', 'First 3 chapters', 'Latest 3 chapters', 'Around my bookmark'].map((x) => ({ value: x, label: x }));
      switch (sw.dataset.a) {
        case 'use-new': useCover(WNA.ai.state(s.id).pending); break;
        case 'keep': WNA.ai.keepCurrent(s.id); break;
        case 'ctxch': { const r = await V2.choose({ title: 'Cover context chapters', sub: 'Chapters the AI reads to understand the story.', options: opts, value: u.ctxCh }); if (r) { u.ctxCh = r; render(); } break; }
        case 'descch': { const r = await V2.choose({ title: 'Description context chapters', options: opts, value: u.descCh }); if (r) { u.descCh = r; render(); } break; }
        case 'write': u.writing = true; render(); await V2.wait(1700); u.writing = false; u.prompt = `A painted cover for "${V2.shortTitle(s.title)}": ${s.tags.slice(0, 3).join(', ').toLowerCase()} mood, a lone figure framed by dramatic light, rich texture, space for the title at the top, no text.`; if (page.isConnected) render(); break;
        case 'gen': WNA.ai.generateCover(s.id, u.prompt.trim() || null); V2.aiWatch(s.id); break;
        case 'gendesc': WNA.ai.generateDescription(s.id); V2.aiWatch(s.id); break;
        case 'apply': WNA.ai.applyDraft(s.id); V2.snack('Description updated'); break;
        case 'discard': WNA.ai.discardDraft(s.id); V2.snack('Draft discarded'); break;
        case 'show': WNA.ai.setShowAi(s.id, !WNA.ai.state(s.id).showAiDesc); break;
        case 'settings': V2.go('/settings/ai'); break;
      }
    });
    render();
  };
})();
