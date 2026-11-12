/* v6 — Select Chapters, Trends (SVG charts), AI Controls, Author Novels. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  const missing = (ctx) => { ctx.root.innerHTML = V6.topbar('Not found', '') + V6.empty('help', 'Novel not found', ''); };

  // ---------- Select Chapters ----------
  V6.screens.select = (ctx) => {
    const { root, route } = ctx; const s = W.story(route.params.id); if (!s) return missing(ctx);
    const list = W.chapters(s.id).filter((c) => !c.downloaded);
    const sel = new Set(); let range = null; // null | 'start' | {from}
    root.innerHTML = `${V6.topbar('Select Chapters', esc(s.title))}
      ${list.length ? `<p class="hint">${ic('touch_app')} Tap chapters one by one, or long-press one and drag to select or clear a range.</p>
      <div class="sel-tools"><button class="btn ghost sm" data-r>${ic('linear_scale')} Select range</button><button class="btn ghost sm" data-all>All</button><button class="btn ghost sm" data-clr>Clear</button></div>
      <div class="range-msg mono" data-rm hidden></div>
      <div class="sel-list" data-list>${list.map((c) => `<button class="sel-row" data-c="${c.id}" data-i="${c.index}"><span class="ckb">${ic('check')}</span><span class="num sel-i">${c.index}</span><span class="sel-t">${esc(c.title)}</span></button>`).join('')}</div>
      <div class="sticky-go"><button class="btn acc wide" data-go-dl disabled>${ic('download')} Pick chapters to download</button></div>`
      : V6.empty('task_alt', 'All caught up', 'Every chapter of this novel is already on your phone.', `<button class="btn ghost" data-back>Back to the novel</button>`)}`;
    if (!list.length) return;
    const listEl = root.querySelector('[data-list]'), rm = root.querySelector('[data-rm]');
    const upd = () => {
      listEl.querySelectorAll('[data-c]').forEach((b) => b.classList.toggle('on', sel.has(b.dataset.c)));
      const go = root.querySelector('[data-go-dl]'); go.disabled = !sel.size; go.innerHTML = sel.size ? `${ic('download')} Download ${sel.size} selected` : 'Pick chapters to download';
      rm.hidden = !range; if (range) rm.textContent = range === 'start' ? 'Tap the first chapter in your range.' : `Chapter ${range.from} is the start. Tap the last chapter.`;
    };
    const ids = list.map((c) => c.id);
    const setRange = (a, b, on) => { const [i, j] = [ids.indexOf(a), ids.indexOf(b)].sort((x, y) => x - y); ids.slice(i, j + 1).forEach((id) => (on ? sel.add(id) : sel.delete(id))); };
    root.addEventListener('click', (e) => {
      const t = e.target;
      if (t.closest('[data-r]')) { range = 'start'; return upd(); }
      if (t.closest('[data-all]')) { ids.forEach((id) => sel.add(id)); return upd(); }
      if (t.closest('[data-clr]')) { sel.clear(); range = null; return upd(); }
      if (t.closest('[data-go-dl]')) { W.downloads.enqueue(s.id, [...sel]); V6.toast(`Queued ${sel.size} chapters · ${esc(V6.short(s))}`, { icon: 'download', action: 'View', onAction: () => W.go('/queue') }); return W.back(); }
      const r = t.closest('[data-c]'); if (!r) return;
      const id = r.dataset.c;
      if (range === 'start') { range = { from: r.dataset.i, id }; sel.add(id); return upd(); }
      if (range && range.id) { setRange(range.id, id, true); range = null; return upd(); }
      sel.has(id) ? sel.delete(id) : sel.add(id); upd();
    });
    // long-press then drag for ranges
    let anchor = null, mode = true;
    V6.longPress(listEl, '[data-c]', (b) => { anchor = b.dataset.c; mode = !sel.has(anchor); sel[mode ? 'add' : 'delete'](anchor); listEl.classList.add('dragsel'); upd(); });
    listEl.addEventListener('pointermove', (e) => {
      if (!anchor) return; e.preventDefault();
      const el = document.elementFromPoint(e.clientX, e.clientY); const r = el && el.closest('[data-c]');
      if (r) { setRange(anchor, r.dataset.c, mode); upd(); }
    });
    const stop = () => { anchor = null; listEl.classList.remove('dragsel'); };
    listEl.addEventListener('pointerup', stop); listEl.addEventListener('pointercancel', stop);
  };

  // ---------- Trends ----------
  const fmtV = (kind, v) => (kind === 'score' ? v.toFixed(2) : kind === 'usd' ? '$' + W.fmt.num(v) : W.fmt.num(v));
  function chart(pts, kind) {
    const w = 340, h = 150, pad = 4;
    if (pts.length < 2) return '<div class="ch-none mono">Only one snapshot</div>';
    const xs = pts.map((p) => p.t), ys = pts.map((p) => p.v);
    const x0 = Math.min(...xs), x1 = Math.max(...xs); let y0 = Math.min(...ys), y1 = Math.max(...ys);
    if (y1 - y0 < 1e-9) { y0 -= 1; y1 += 1; } const yp = (y1 - y0) * 0.12; y0 -= yp; y1 += yp;
    const X = (t) => pad + ((t - x0) / Math.max(1, x1 - x0)) * (w - pad * 2), Y = (v) => h - pad - ((v - y0) / (y1 - y0)) * (h - pad * 2);
    const d = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p.t).toFixed(1)},${Y(p.v).toFixed(1)}`).join('');
    const last = pts[pts.length - 1];
    return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" class="tchart" data-pts='${JSON.stringify(pts.map((p) => [p.t, p.v]))}' data-kind="${kind}">
      ${[0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${w}" y1="${h * f}" y2="${h * f}" class="tg"/>`).join('')}
      <path d="${d}L${X(last.t)},${h}L${X(pts[0].t)},${h}Z" class="ta"/><path d="${d}" class="tl" vector-effect="non-scaling-stroke"/>
      <line class="tx" x1="0" x2="0" y1="0" y2="${h}" vector-effect="non-scaling-stroke" style="display:none"/>
      </svg><div class="t-ax mono"><span>${W.fmt.dateShort(pts[0].t)}</span><span>${fmtV(kind, Math.min(...ys))} – ${fmtV(kind, Math.max(...ys))}</span><span>${W.fmt.dateShort(last.t)}</span></div>`;
  }
  V6.screens.trends = (ctx) => {
    const { root, route } = ctx; const s = W.story(route.params.id); if (!s) return missing(ctx);
    const all = W.trendSeries(s.id);
    const ui = { range: 'all', group: 'all' };
    const RANGES = [['7d', '7D', 7], ['30d', '30D', 30], ['90d', '90D', 90], ['1y', '1Y', 365], ['all', 'All time', 0]];
    const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['eng', 'Engagement']];
    const snaps = (W.data.metrics[s.id] || []);
    root.innerHTML = `${V6.topbar('Trends', esc(s.title))}
      <div class="tr-h"><h1>${esc(s.title)}</h1><p>by ${esc(s.author)}</p><p class="mono dim">${snaps.length} snapshots · since ${snaps[0] ? W.fmt.date(snaps[0].at) : '—'} · updated ${W.fmt.date(s.lastSync)}</p></div>
      <div class="chips" data-rg></div><div class="chips tr-g" data-gp></div><div data-series></div>`;
    function draw() {
      root.querySelector('[data-rg]').innerHTML = RANGES.map(([k, l]) => `<button class="chip ${ui.range === k ? 'on' : ''}" data-r="${k}">${l}</button>`).join('');
      root.querySelector('[data-gp]').innerHTML = GROUPS.map(([k, l]) => `<button class="chip acc ${ui.group === k ? 'on' : ''}" data-g="${k}">${l}</button>`).join('');
      const days = RANGES.find((r) => r.k === ui.range || r[0] === ui.range)[2];
      const from = days ? W.NOW - days * 86400000 : 0;
      const ser = all.filter((x) => ui.group === 'all' || (ui.group === 'score' && x.kind === 'score') || (ui.group === 'patreon' && x.key.startsWith('patreon')) || (ui.group === 'eng' && x.kind === 'count'));
      const out = ser.map((x) => {
        let pts = x.points.filter((p) => p.t >= from); if (pts.length < 2) pts = x.points.slice(-2);
        const cur = x.points[x.points.length - 1].v, prev = x.points.length > 1 ? x.points[x.points.length - 2].v : cur;
        const d30 = x.points.filter((p) => p.t >= W.NOW - 30 * 86400000); const g30 = d30.length ? cur - d30[0].v : 0;
        const sign = (v) => (v >= 0 ? '+' : '−') + fmtV(x.kind, x.kind === 'score' ? Math.abs(v) : Math.round(Math.abs(v)));
        return `<section class="card tr-card"><div class="tr-top"><span class="kick">${esc(x.label)}</span><span class="tr-d ${cur - prev >= 0 ? 'up' : 'down'}">${sign(cur - prev)} since last sync</span></div>
          <div class="tr-big num" data-read>${fmtV(x.kind, cur)}</div>
          <p class="mono dim">${sign(g30)} in the last 30 days · range ${fmtV(x.kind, Math.min(...pts.map((p) => p.v)))}–${fmtV(x.kind, Math.max(...pts.map((p) => p.v)))}</p>
          <div class="tr-ch">${chart(pts, x.kind)}</div></section>`;
      }).join('');
      root.querySelector('[data-series]').innerHTML = out || V6.empty('show_chart', 'No trend data yet', 'Sync the novel a few times and trends will appear here.');
    }
    root.addEventListener('click', (e) => {
      const r = e.target.closest('[data-r]'); if (r) { ui.range = r.dataset.r; draw(); }
      const g = e.target.closest('[data-g]'); if (g) { ui.group = g.dataset.g; draw(); }
    });
    // scrub a chart to read values
    root.addEventListener('pointermove', (e) => readout(e)); root.addEventListener('pointerdown', (e) => readout(e));
    root.addEventListener('pointerleave', () => root.querySelectorAll('.tx').forEach((l) => (l.style.display = 'none')));
    function readout(e) {
      const svg = e.target.closest('.tchart'); if (!svg) return;
      const pts = JSON.parse(svg.dataset.pts); const r = svg.getBoundingClientRect(); const f = (e.clientX - r.left) / r.width;
      const t = pts[0][0] + f * (pts[pts.length - 1][0] - pts[0][0]); let best = pts[0]; pts.forEach((p) => { if (Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p; });
      const ln = svg.querySelector('.tx'); const x = 4 + ((best[0] - pts[0][0]) / Math.max(1, pts[pts.length - 1][0] - pts[0][0])) * 332; ln.setAttribute('x1', x); ln.setAttribute('x2', x); ln.style.display = '';
      const big = svg.closest('.tr-card').querySelector('[data-read]'); big.innerHTML = `${fmtV(svg.dataset.kind, best[1])}<small class="mono"> ${W.fmt.dateShort(best[0])}</small>`;
    }
    draw();
  };

  // ---------- AI Controls ----------
  V6.screens.ai = (ctx) => {
    const { root, route } = ctx; const s = W.story(route.params.id); if (!s) return missing(ctx);
    V6.watchAi(s.id);
    const ui = { tab: route.query.tab || 'cover', ctxCh: 'Automatic', prompt: '', seenDraft: false };
    root.innerHTML = `${V6.topbar('AI Controls', esc(s.title))}<div class="seg wide ai-tabs" data-tabs></div><div data-body></div>`;
    function draw() {
      const st = W.ai.state(s.id);
      root.querySelector('[data-tabs]').innerHTML = `<button data-t="cover" class="${ui.tab === 'cover' ? 'on' : ''}">${ic('image')} Cover${st.pending && ui.tab !== 'cover' ? ' •' : ''}</button><button data-t="desc" class="${ui.tab === 'desc' ? 'on' : ''}">${ic('notes')} Description${st.draft && ui.tab !== 'desc' ? ' •' : ''}</button>`;
      const job = st.job;
      const b = root.querySelector('[data-body]');
      if (ui.tab === 'cover') {
        const pend = st.pending && st.versions.find((v) => v.id === st.pending);
        const cur = st.versions.find((v) => v.id === st.inUse);
        const img = (v) => (v && v.url ? `<img src="${v.url}" alt="" loading="lazy">` : `<div class="cov-fb" style="background:${W.coverFallback(s)};height:100%"></div>`);
        b.innerHTML = `
          ${pend ? `<section class="card acc ai-new"><div class="sec"><span class="sec-n">NEW</span><h2>New cover ready</h2></div><div class="ai-vs"><figure>${img(cur)}<figcaption class="mono">Current</figcaption></figure><span class="num">→</span><figure class="new">${img(pend)}<figcaption class="mono">New</figcaption></figure></div><p class="mono dim ai-cost">OpenRouter: ${esc(st.cost || '$0.04 · 2 calls')}</p><div class="row2 pad"><button class="btn ghost" data-k>Keep current</button><button class="btn acc" data-u="${pend.id}">Use new cover</button></div></section>` : ''}
          ${V6.sec('01', 'Covers')}
          <div class="ai-strip">${st.versions.map((v) => `<button class="ai-v ${v.id === st.inUse ? 'in' : ''}" data-v="${v.id}">${img(v)}${v.isNew ? '<span class="tag solid">New</span>' : ''}${v.id === st.inUse ? `<span class="ai-ck">${ic('check')}</span>` : ''}<small class="mono">${v.label}</small></button>`).join('')}</div>
          <p class="hint">Tap a cover to use it. Long-press to see its prompt.</p>
          ${V6.sec('02', 'Make a new cover')}
          <button class="row" data-ctx>${ic('format_list_numbered')}<span class="rt"><b>Cover context chapters</b><small>${ui.ctxCh}</small></span>${ic('chevron_right')}</button>
          <div class="pad"><label class="fld"><span>Image prompt (optional)</span><textarea rows="4" data-p placeholder="Describe the cover, or leave empty and AI writes the prompt">${esc(ui.prompt)}</textarea></label>
          <div class="row2"><button class="btn ghost" data-wp ${job ? 'disabled' : ''}>${ic('edit_note')} Write prompt with AI</button><button class="btn acc" data-gen ${job ? 'disabled' : ''}>${ic(job && job.kind === 'cover' ? 'progress_activity' : 'auto_awesome', job && job.kind === 'cover' ? 'spin' : '')} ${job && job.kind === 'cover' ? esc(job.message) : 'Generate cover'}</button></div>
          ${job && job.kind === 'cover' ? `<div class="ai-prog">${V6.bar(0.5, 'indet')}</div>` : ''}
          <p class="mono dim ai-foot">Image: ${W.ai.models.image} · Prompt: ${W.ai.models.description} · <a href="#/settings/ai">Change in AI Settings</a></p></div>`;
      } else {
        b.innerHTML = `
          <button class="row" data-ctx>${ic('format_list_numbered')}<span class="rt"><b>Context chapters</b><small>${ui.ctxCh}</small></span>${ic('chevron_right')}</button>
          ${st.draft ? `<section class="card acc"><div class="card-p"><div class="kick acc-t">Draft · ${esc(st.cost || '$0.003 · 1 call')}</div><p class="ai-draft">${esc(st.draft)}</p><div class="row2"><button class="btn ghost" data-dd>Discard</button><button class="btn acc" data-da>Apply</button></div></div></section>` : ''}
          ${V6.sec('01', 'Description')}
          <div class="pad">${st.desc ? `<span class="tag acc">${ic('auto_awesome')} AI-generated</span><p class="ai-desc">${esc(st.desc)}</p>` : `<p class="ai-desc dim">${esc((s.desc || '').slice(0, 280))}…</p><p class="mono dim">Source description</p>`}
          <label class="ck-row"><button class="ckb ${st.showAiDesc ? 'on' : ''}" data-show ${st.desc ? '' : 'disabled'}>${ic('check')}</button><span>Show AI description in Details</span></label>
          <button class="btn acc wide" data-gd ${job ? 'disabled' : ''}>${ic(job && job.kind === 'description' ? 'progress_activity' : 'auto_awesome', job && job.kind === 'description' ? 'spin' : '')} ${job && job.kind === 'description' ? 'Generating…' : 'Generate description with AI'}</button></div>`;
      }
    }
    root.addEventListener('input', (e) => { if (e.target.matches('[data-p]')) ui.prompt = e.target.value; });
    root.addEventListener('click', (e) => {
      const t = e.target;
      const tb = t.closest('[data-t]'); if (tb) { ui.tab = tb.dataset.t; return draw(); }
      const v = t.closest('[data-v]'); if (v) { W.ai.useCover(s.id, v.dataset.v); return V6.toast('Cover updated', { icon: 'image' }); }
      const u = t.closest('[data-u]'); if (u) { W.ai.useCover(s.id, u.dataset.u); return V6.toast('Using the new cover', { icon: 'image' }); }
      if (t.closest('[data-k]')) return W.ai.keepCurrent(s.id);
      if (t.closest('[data-gen]')) return W.ai.generateCover(s.id, ui.prompt.trim() || null);
      if (t.closest('[data-wp]')) { const btn = t.closest('[data-wp]'); btn.disabled = true; btn.innerHTML = `${ic('progress_activity', 'spin')} Writing…`; return setTimeout(() => { ui.prompt = `A lone witch in a long coat holding a smoking flintlock, ${s.tags.slice(0, 2).join(' and ').toLowerCase()} mood, storm-lit village behind her, painterly, bold silhouette, title space at the top, no text.`; draw(); }, 1300); }
      if (t.closest('[data-ctx]')) return V6.sheet({ title: 'Context chapters', items: ['Automatic', 'First 3 chapters', 'First 10 chapters', 'Around the bookmark', 'Latest 5 chapters'].map((l) => ({ label: l, on: l === ui.ctxCh, run: () => { ui.ctxCh = l; draw(); } })) });
      if (t.closest('[data-gd]')) return W.ai.generateDescription(s.id);
      if (t.closest('[data-da]')) { W.ai.applyDraft(s.id); return V6.toast('Description applied', { icon: 'check' }); }
      if (t.closest('[data-dd]')) return W.ai.discardDraft(s.id);
      if (t.closest('[data-show]')) return W.ai.setShowAi(s.id, !W.ai.state(s.id).showAiDesc);
    });
    V6.longPress(root, '[data-v]', (b) => { const v = W.ai.state(s.id).versions.find((x) => x.id === b.dataset.v); V6.sheet({ title: v.label, sub: v.prompt ? 'Image prompt' : 'Original cover from the source', html: `<p class="sh-pad">${esc(v.prompt || 'This cover came from ' + s.source + '.')}</p>` }); });
    ctx.sub(W.ai.on(draw));
    draw();
  };

  // ---------- Author ----------
  V6.screens.author = (ctx) => {
    const { root, route } = ctx; const s = W.story(route.params.id); if (!s) return missing(ctx);
    const others = W.authorNovels(s.id).filter((x) => !V6.state.deleted.has(x.id));
    const row = (x) => `<button class="au-row" data-go="/story/${x.id}">${V6.cover(x)}<span><b>${esc(x.title)}</b><small>${esc(x.source)} · ${x.downloaded}/${x.total} ch${x.score ? ` · ★ ${parseFloat(x.score).toFixed(2)}` : ''}</small>${V6.coverageBar(x)}</span></button>`;
    root.innerHTML = `${V6.topbar('Author', '')}
      <div class="au-h"><span class="kick">Author</span><h1>${esc(s.author || 'Unknown')}</h1><p><span class="num acc-t">${others.length}</span> other novel${others.length === 1 ? '' : 's'} in your library</p></div>
      ${V6.sec('01', 'In your library')}${row(s)}
      ${others.length ? V6.sec('02', 'Also by them') + others.map(row).join('') : `<div class="pad"><p class="dim">No other novels by this author yet.</p></div>`}`;
  };
})();
