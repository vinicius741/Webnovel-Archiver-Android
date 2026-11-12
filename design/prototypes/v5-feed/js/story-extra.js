/* v5-feed story sub-screens: Select Chapters, Trends, AI Controls. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;

  // ---------- Select chapters ----------
  A.screens.select = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    A.tint(ctx.el, s);
    const all = W.chapters(s.id);
    const queued = new Set(((W.downloads.forStory(s.id) || {}).chapters || []).map((c) => c.id));
    const avail = all.filter((c) => !c.downloaded && !queued.has(c.id));
    const sel = new Set();
    let range = null; // null | 'start' | {start:i}
    document.body.classList.add('has-actbar'); ctx.on(() => document.body.classList.remove('has-actbar'));
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Select chapters', sub: A.short(s.title), back: '/story/' + s.id })}
      ${avail.length ? `<div class="sel-tools"><p class="sel-hint">Tap chapters individually, or long-press one and drag to select or clear a range.</p>
        <div class="sel-btns"><button class="btn tonal sm" data-range>${A.ic('linear_scale')}Select range</button><button class="btn outline sm" data-all>All</button><button class="btn outline sm" data-clear>Clear</button></div></div>
        <div class="sel-list">${avail.map((c, i) => `<button class="sr" data-i="${i}"><span class="cbx">${A.ic('check')}</span><em>${c.index}</em><b>${esc(c.title)}</b></button>`).join('')}</div>`
      : A.empty('task_alt', 'All caught up', queued.size ? 'Every remaining chapter is already in the download queue.' : `All ${all.length} chapters are on this phone.`, `<button class="btn tonal" data-back="/story/${s.id}">Back to story</button>`)}</div>
      ${avail.length ? `<div class="dlbar"><button class="btn primary" data-dl disabled>${A.ic('download')}<span>Select chapters to download</span></button></div>` : ''}`;
    A.bindTopbar(ctx);
    if (!avail.length) return;
    const el = ctx.el, rows = [...el.querySelectorAll('.sr')], hint = el.querySelector('.sel-hint'), dl = el.querySelector('[data-dl]');
    const paint = () => {
      rows.forEach((r, i) => { r.classList.toggle('on', sel.has(i)); r.classList.toggle('anchor', range && range.start === i); });
      dl.disabled = !sel.size; dl.querySelector('span').textContent = sel.size ? `Download ${sel.size} selected` : 'Select chapters to download';
      hint.classList.toggle('range', !!range);
      hint.textContent = !range ? 'Tap chapters individually, or long-press one and drag to select or clear a range.' : range === 'start' ? 'Tap the first chapter in your range.' : `Chapter ${avail[range.start].index} is the start. Tap the last chapter.`;
    };
    if (ctx.query.demo) { for (let i = 0; i < Math.min(5, avail.length); i++) sel.add(i); }
    paint();
    // long-press + drag range
    let drag = null;
    A.longPress(el.querySelector('.sel-list'), '.sr', (r) => { const i = +r.dataset.i; drag = { anchor: i, on: !sel.has(i), snap: new Set(sel) }; apply(i); });
    function apply(j) { const [a, b] = [Math.min(drag.anchor, j), Math.max(drag.anchor, j)]; sel.clear(); drag.snap.forEach((x) => sel.add(x)); for (let k = a; k <= b; k++) drag.on ? sel.add(k) : sel.delete(k); paint(); }
    const move = (x, y, e) => { if (!drag) return; if (e && e.cancelable) e.preventDefault(); const r = document.elementFromPoint(x, y); const row = r && r.closest('.sr'); if (row) apply(+row.dataset.i); };
    el.addEventListener('touchmove', (e) => move(e.touches[0].clientX, e.touches[0].clientY, e), { passive: false });
    el.addEventListener('pointermove', (e) => { if (e.pointerType === 'mouse') move(e.clientX, e.clientY); });
    const end = () => { if (drag) { drag = null; A.toast(`${sel.size} selected`, { icon: 'checklist' }); } };
    el.addEventListener('touchend', end); el.addEventListener('pointerup', end);
    el.addEventListener('click', (e) => {
      const t = e.target, r = t.closest('.sr');
      if (r) {
        const i = +r.dataset.i;
        if (range === 'start') { range = { start: i }; paint(); return; }
        if (range && range.start != null) { const [a, b] = [Math.min(range.start, i), Math.max(range.start, i)]; for (let k = a; k <= b; k++) sel.add(k); range = null; paint(); A.toast(`Selected chapters ${avail[a].index}–${avail[b].index}`, { icon: 'linear_scale' }); return; }
        sel.has(i) ? sel.delete(i) : sel.add(i); paint(); return;
      }
      if (t.closest('[data-range]')) { range = range ? null : 'start'; paint(); return; }
      if (t.closest('[data-all]')) { avail.forEach((_, i) => sel.add(i)); range = null; paint(); return; }
      if (t.closest('[data-clear]')) { sel.clear(); range = null; paint(); return; }
      if (t.closest('[data-dl]')) { const ids = [...sel].sort((a, b) => a - b).map((i) => avail[i].id); W.downloads.enqueue(s.id, ids); A.toast(`Downloading ${ids.length} chapters`, { icon: 'download', action: 'View', fn: () => A.go('/queue') }); A.back('/story/' + s.id); }
    });
  };

  // ---------- Trends ----------
  const RANGES = [['7', '7D'], ['30', '30D'], ['90', '90D'], ['365', '1Y'], ['all', 'All time']];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['eng', 'Engagement']];
  const fv = (kind, v) => (kind === 'score' ? v.toFixed(2) : kind === 'usd' ? fmt.money(v) : fmt.num(Math.round(v)));
  function chart(pts, kind) {
    const Wd = 340, H = 180, pl = 4, pr = 4, pt = 12, pb = 22;
    const t0 = pts[0].t, t1 = pts[pts.length - 1].t || t0 + 1;
    let lo = Math.min(...pts.map((p) => p.v)), hi = Math.max(...pts.map((p) => p.v));
    if (hi === lo) { hi += kind === 'score' ? 0.05 : 1; lo -= kind === 'score' ? 0.05 : 1; }
    const pad = (hi - lo) * 0.12; lo -= pad; hi += pad;
    const x = (t) => pl + ((t - t0) / Math.max(1, t1 - t0)) * (Wd - pl - pr), y = (v) => pt + (1 - (v - lo) / (hi - lo)) * (H - pt - pb);
    const d = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join('');
    const gid = 'g' + Math.random().toString(36).slice(2, 8);
    const grid = [0.25, 0.5, 0.75].map((f) => { const v = hi - (hi - lo) * f; return `<line class="gl" x1="0" x2="${Wd}" y1="${y(v)}" y2="${y(v)}"/><text class="axis" x="${Wd}" y="${y(v) - 4}" text-anchor="end">${fv(kind, v)}</text>`; }).join('');
    const last = pts[pts.length - 1];
    return `<svg viewBox="0 0 ${Wd} ${H}" preserveAspectRatio="none"><defs><linearGradient id="${gid}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="var(--acc)" stop-opacity=".38"/><stop offset="1" stop-color="var(--acc)" stop-opacity="0"/></linearGradient></defs>
      ${grid}<path d="${d}L${x(t1)},${H - pb}L${x(t0)},${H - pb}Z" fill="url(#${gid})"/><path d="${d}" fill="none" stroke="var(--acc)" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>
      <circle cx="${x(last.t)}" cy="${y(last.v)}" r="5" fill="var(--acc)" stroke="var(--bg)" stroke-width="2"/>
      <text class="axis" x="0" y="${H - 4}">${fmt.dateShort(t0)}</text><text class="axis" x="${Wd}" y="${H - 4}" text-anchor="end">${fmt.dateShort(t1)}</text></svg>`;
  }
  A.screens.trends = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    A.tint(ctx.el, s);
    const series = W.trendSeries(s.id);
    const st = { range: ctx.query.range || 'all', group: 'all' };
    const allPts = series.flatMap((x) => x.points.map((p) => p.t));
    const snaps = ((W.data.metrics || {})[s.id] || []).length || (series[0] ? series[0].points.length : 0);
    const body = () => {
      const now = W.NOW;
      const list = series.filter((x) => st.group === 'all' || (st.group === 'score' && x.kind === 'score') || (st.group === 'patreon' && /patreon/.test(x.key)) || (st.group === 'eng' && x.kind === 'count'));
      if (!list.length) return A.empty('monitoring', 'No trend data yet', 'Sync this story a few times to start building history.');
      return list.map((x) => {
        const pts = st.range === 'all' ? x.points : x.points.filter((p) => p.t >= now - (+st.range) * 86400000);
        const cur = x.points[x.points.length - 1].v, prev = x.points.length > 1 ? x.points[x.points.length - 2].v : cur;
        const dlt = cur - prev, cls = dlt > 0 ? 'up' : dlt < 0 ? 'down' : 'flat';
        const m30 = x.points.filter((p) => p.t >= now - 30 * 86400000); const d30 = m30.length > 1 ? cur - m30[0].v : 0;
        const lo = Math.min(...pts.map((p) => p.v)), hi = Math.max(...pts.map((p) => p.v));
        return `<div class="trc"><h3>${esc(x.label)}</h3><div class="big"><b>${fv(x.kind, cur)}</b><span class="delta ${cls}">${dlt > 0 ? '+' : ''}${x.kind === 'score' ? dlt.toFixed(2) : fv(x.kind, dlt)} since last sync</span></div>
          <p>${d30 ? `${d30 > 0 ? '+' : ''}${x.kind === 'score' ? d30.toFixed(2) : fv(x.kind, d30)} in the last 30 days · ` : ''}${pts.length ? `range ${fv(x.kind, lo)}–${fv(x.kind, hi)}` : ''}</p>
          ${pts.length > 1 ? chart(pts, x.kind) : `<p style="padding:40px 0;text-align:center">Not enough snapshots in this range</p>`}</div>`;
      }).join('');
    };
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Trends', sub: A.short(s.title), back: '/story/' + s.id, big: false })}
      <div class="tr-head">${A.cover(s, '', true)}<div><h1>${esc(A.short(s.title))}</h1><p>by ${esc(s.author)} · ${snaps} snapshots${allPts.length ? ` · since ${fmt.date(Math.min(...allPts))} · updated ${fmt.date(Math.max(...allPts))}` : ''}</p></div></div>
      <div class="tr-filters"><div class="tr-r">${A.chips(RANGES, st.range, 'data-r')}</div><div class="tr-g">${A.chips(GROUPS, st.group, 'data-g')}</div></div>
      <div class="tr-cards rise">${body()}</div></div>`;
    A.bindTopbar(ctx);
    ctx.el.addEventListener('click', (e) => {
      const r = e.target.closest('[data-r]'), g = e.target.closest('[data-g]');
      if (r) st.range = r.dataset.r; else if (g) st.group = g.dataset.g; else return;
      ctx.el.querySelector('.tr-r').innerHTML = A.chips(RANGES, st.range, 'data-r');
      ctx.el.querySelector('.tr-g').innerHTML = A.chips(GROUPS, st.group, 'data-g');
      ctx.el.querySelector('.tr-cards').innerHTML = body();
    });
  };

  // ---------- AI Controls ----------
  const CTX_OPTS = ['Automatic', 'First 3 chapters', 'First 10 chapters', 'Around the bookmark'];
  const aiUi = {}; // storyId -> {tab, ctxCover, ctxDesc, prompt, seenDesc}
  A.screens.ai = (ctx) => {
    const s = W.story(ctx.params.id) || A.stories()[0];
    A.tint(ctx.el, s);
    const u = (aiUi[s.id] = aiUi[s.id] || { tab: ctx.query.tab || 'cover', ctxCover: 'Automatic', ctxDesc: 'Automatic', prompt: '', unseenDesc: false });
    if (ctx.query.tab) u.tab = ctx.query.tab;
    const m = W.ai.models;
    function coverTab(x) {
      const inUse = x.versions.find((v) => v.id === x.inUse) || x.versions[0];
      const pend = x.pending && x.versions.find((v) => v.id === x.pending);
      const job = x.job && x.job.kind === 'cover' ? x.job : null;
      return `${pend ? `<div class="newcover"><div><span class="eyebrow">New cover ready</span><p class="faint" style="font-size:12.5px;margin-top:6px">OpenRouter: ${esc(x.cost || '$0.04 · 2 calls')}</p></div>
          <div class="nc-pair"><figure>${coverImg(inUse, s)}<figcaption>Current</figcaption></figure>${A.ic('arrow_forward', 'faint')}<figure>${coverImg(pend, s)}<figcaption>New</figcaption></figure></div>
          <div class="nc-acts"><button class="btn primary" data-use="${pend.id}">Use new cover</button><button class="btn glassy" data-keep>Keep current</button></div></div>` : ''}
        <div><div class="sec-h sm" style="margin:6px 0 10px"><h2>Covers</h2></div>
          <div class="covers">${x.versions.map((v) => `<button class="cvo ${v.id === x.inUse ? 'inuse' : ''}" data-cv="${v.id}">${coverImg(v, s)}${v.id === x.inUse ? `<span class="chk">${A.ic('check')}</span>` : ''}${v.isNew ? '<span class="pill p-acc nb">New</span>' : ''}<b>${esc(v.label)}</b></button>`).join('')}</div>
          <p class="faint" style="font-size:12.5px">Tap a cover to use it. Long-press to see its prompt.</p></div>
        <div class="ai-card"><h3>Make a new cover</h3>
          <button class="row" style="padding:0;min-height:48px" data-ctx="ctxCover">${A.ic('menu_book')}<span class="rt"><b>Cover context chapters</b><small>${esc(u.ctxCover)}</small></span>${A.ic('expand_more', 'chev')}</button>
          <label class="field"><span>Image prompt (optional)</span><textarea data-prompt placeholder="Describe the cover, or leave empty and AI writes the prompt">${esc(u.prompt)}</textarea></label>
          ${job ? `<div class="ai-prog"><span class="spin"></span>${esc(job.message)}</div>` : `<div class="nc-acts"><button class="btn glassy" data-wp>${A.ic('edit_note')}Write prompt with AI</button><button class="btn primary" data-gen>${A.ic('auto_awesome')}Generate cover</button></div>`}
          <p class="ai-foot">Image: ${esc(m.image)} · Prompt: ${esc(m.description)} · <button data-go="/settings/ai">Change in AI Settings</button></p></div>`;
    }
    function descTab(x) {
      const job = x.job && x.job.kind === 'description' ? x.job : null;
      return `<div class="ai-card"><button class="row" style="padding:0;min-height:48px" data-ctx="ctxDesc">${A.ic('menu_book')}<span class="rt"><b>Context chapters</b><small>${esc(u.ctxDesc)}</small></span>${A.ic('expand_more', 'chev')}</button>
          ${x.desc ? `<div><span class="pill p-acc">${A.ic('auto_awesome')}AI-generated</span><p class="ai-desc" style="margin-top:10px">${esc(x.desc)}</p></div>
            <div class="set-row"><span><b>Show AI description in Details</b><small>Otherwise the source description is shown</small></span>${A.sw(x.showAiDesc, 'data-showai')}</div>` : `<p class="muted">No AI description yet. The source description is shown in Details.</p>`}
          ${job ? `<div class="ai-prog"><span class="spin"></span>Generating description…</div>` : `<button class="btn ${x.desc ? 'glassy' : 'primary'}" data-gdesc>${A.ic('auto_awesome')}Generate Description with AI</button>`}</div>
        ${x.draft ? `<div class="draft"><span class="eyebrow">Draft preview</span><p class="ai-desc">${esc(x.draft)}</p><p class="faint" style="font-size:12px">OpenRouter: ${esc(x.cost || '')}</p><div class="nc-acts"><button class="btn primary" data-apply>Apply</button><button class="btn glassy" data-discard>Discard</button></div></div>` : ''}
        <p class="ai-foot">Model: ${esc(m.description)} · <button data-go="/settings/ai">Change in AI Settings</button></p>`;
    }
    const draw = () => {
      const x = W.ai.state(s.id);
      ctx.el.querySelector('.ai-seg').innerHTML = A.seg([['cover', 'Cover'], ['desc', `Description${u.unseenDesc && u.tab !== 'desc' ? ' •' : ''}`]], u.tab, 'data-tab');
      ctx.el.querySelector('.ai-body').innerHTML = u.tab === 'cover' ? coverTab(x) : descTab(x);
    };
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'AI Controls', sub: A.short(s.title), back: '/story/' + s.id })}
      <div class="ai-wrap"><div class="ai-seg"></div><div class="ai-body"></div></div></div>`;
    A.bindTopbar(ctx);
    draw();
    ctx.on(W.ai.on((x) => { if (x.job ? x.job.storyId === s.id : true) { if (x.draft && u.tab !== 'desc') u.unseenDesc = true; draw(); } }));
    A.longPress(ctx.el, '[data-cv]', (b) => { const v = W.ai.state(s.id).versions.find((y) => y.id === b.dataset.cv); A.sheet({ title: v.label + ' prompt', html: `<p class="ai-desc" style="padding:0 8px 12px">${esc(v.prompt || 'Original cover from ' + s.source + '. No prompt.')}</p>`, tint: s }); });
    ctx.el.addEventListener('input', (e) => { if (e.target.matches('[data-prompt]')) u.prompt = e.target.value; });
    ctx.el.addEventListener('sw', (e) => { if (e.target.matches('[data-showai]')) W.ai.setShowAi(s.id, e.detail); });
    ctx.el.addEventListener('click', (e) => {
      const t = e.target;
      const tb = t.closest('[data-tab]'); if (tb) { u.tab = tb.dataset.tab; if (u.tab === 'desc') u.unseenDesc = false; draw(); return; }
      const cv = t.closest('[data-cv]'); if (cv) { W.ai.useCover(s.id, cv.dataset.cv); A.aiCover[s.id] = cv.dataset.cv !== 'src'; A.toast('Cover updated', { icon: 'image' }); return; }
      const use = t.closest('[data-use]'); if (use) { W.ai.useCover(s.id, use.dataset.use); A.aiCover[s.id] = true; A.toast('New cover in use', { icon: 'auto_awesome' }); return; }
      if (t.closest('[data-keep]')) { W.ai.keepCurrent(s.id); A.toast('Kept the current cover'); return; }
      const c = t.closest('[data-ctx]'); if (c) { const k = c.dataset.ctx; A.menu({ title: 'Context chapters', sub: 'Which chapters the AI reads first', items: CTX_OPTS.map((o) => ({ icon: 'menu_book', label: o, on: u[k] === o, fn: () => { u[k] = o; draw(); } })) }); return; }
      if (t.closest('[data-wp]')) { const b = t.closest('[data-wp]'); b.innerHTML = '<span class="spin"></span>Writing…'; b.disabled = true; setTimeout(() => { u.prompt = `A moody painted cover for "${A.short(s.title)}": ${s.tags.slice(0, 3).join(', ').toLowerCase()} themes, a lone figure in dramatic rim light, rich textures, space for the title at the top, no text.`; draw(); A.toast('Prompt written · $0.002', { icon: 'edit_note' }); }, 1300); return; }
      if (t.closest('[data-gen]')) { const ta = ctx.el.querySelector('[data-prompt]'); W.ai.generateCover(s.id, ta && ta.value.trim()); return; }
      if (t.closest('[data-gdesc]')) { W.ai.generateDescription(s.id); return; }
      if (t.closest('[data-apply]')) { W.ai.applyDraft(s.id); A.toast('Description applied', { icon: 'check' }); return; }
      if (t.closest('[data-discard]')) { W.ai.discardDraft(s.id); A.toast('Draft discarded'); }
    });
  };
  function coverImg(v, s) { return v && v.url ? `<div class="cv"><img src="${v.url}" alt="" loading="lazy" decoding="async"></div>` : A.cover(s); }
})();
