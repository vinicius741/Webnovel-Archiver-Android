/* v4 Editorial — Select Chapters, Trends (SVG charts), AI Controls. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon, S = A.state;
  const chName = (c) => esc(A.chParts(c.title, c.index).name || c.title);

  // ---------- Select Chapters ----------
  A.screens.select = (page, r) => {
    const s = W.story(r.params.id);
    if (!s) { page.innerHTML = A.bar({ title: 'Not found' }); return; }
    const list = W.chapters(s.id).filter((c) => !c.downloaded);
    const sel = new Set(); let range = null; // null | 'start' | {from}
    page.innerHTML = `${A.bar({ title: 'Select Chapters', sub: esc(A.shortTitle(s.title)) })}
      <div class="set-wrap">
        ${list.length ? `<p class="sec-hint sel-hint" style="margin-top:14px">Tap chapters individually, or long-press one and drag to select or clear a range.</p>
        <div class="act-row" style="flex-wrap:wrap"><button class="btn sm tonal" data-act="range">${I('linear_scale', 'sm')}Select range</button><button class="btn sm ghost" data-act="all">All</button><button class="btn sm ghost" data-act="none">Clear</button></div>
        <div class="ledger sel-list" style="margin-top:16px"></div>
        <div class="save-bar"><button class="btn block" data-act="go" disabled>${I('download')}<span class="lbl">Download 0 selected</span></button></div>`
        : `<div class="empty"><div class="ornament">❦</div><h3>All caught up</h3><p>Every chapter of this novel is already on your phone.</p><button class="btn ghost" data-back>Back to the story</button></div>`}
      </div>`;
    if (!list.length) return;
    const $ = (q) => page.querySelector(q);
    const hint = $('.sel-hint');
    function rows() {
      $('.sel-list').innerHTML = list.map((c) => `<div class="arow ${sel.has(c.id) ? 'sel' : ''}" data-c="${c.id}"><span class="check ${sel.has(c.id) ? 'on' : ''}">${I('check')}</span><span class="no">${c.index}</span><div class="grow"><b>${chName(c)}</b></div></div>`).join('');
      upd();
    }
    function upd() {
      page.querySelectorAll('.arow').forEach((n) => { const on = sel.has(n.dataset.c); n.classList.toggle('sel', on); n.querySelector('.check').classList.toggle('on', on); });
      const b = $('[data-act=go]'); b.disabled = !sel.size; b.querySelector('.lbl').textContent = `Download ${sel.size} selected`;
    }
    rows();
    const setRange = (a, b, on) => { const i0 = Math.min(a, b), i1 = Math.max(a, b); for (let i = i0; i <= i1; i++) on ? sel.add(list[i].id) : sel.delete(list[i].id); };
    // long-press + drag range
    let drag = null;
    A.longPress(page, '.arow', (t) => { const i = list.findIndex((c) => c.id === t.dataset.c); drag = { a: i, on: !sel.has(t.dataset.c), base: new Set(sel) }; setRange(i, i, drag.on); upd(); });
    page.addEventListener('pointermove', (e) => {
      if (!drag) return; const el = document.elementFromPoint(e.clientX, e.clientY); const row = el && el.closest('.arow'); if (!row) return;
      const i = list.findIndex((c) => c.id === row.dataset.c); sel.clear(); drag.base.forEach((x) => sel.add(x)); setRange(drag.a, i, drag.on); upd();
    });
    const endDrag = () => { if (drag) { drag = null; } };
    page.addEventListener('pointerup', endDrag); page.addEventListener('pointercancel', endDrag);
    page.querySelector('.sel-list').style.touchAction = 'pan-y';
    page.addEventListener('click', (e) => {
      const row = e.target.closest('.arow'); if (!row) return;
      const i = list.findIndex((c) => c.id === row.dataset.c);
      if (range === 'start') { range = { from: i }; hint.innerHTML = `<b class="copper">Chapter ${list[i].index}</b> is the start. Tap the last chapter.`; sel.add(row.dataset.c); upd(); return; }
      if (range && range.from != null) { setRange(range.from, i, true); range = null; hint.textContent = 'Range selected. Tap chapters to adjust.'; upd(); return; }
      sel.has(row.dataset.c) ? sel.delete(row.dataset.c) : sel.add(row.dataset.c); upd();
    });
    A.acts(page, {
      range: () => { range = 'start'; hint.innerHTML = '<span class="copper">Tap the first chapter in your range.</span>'; },
      all: () => { list.forEach((c) => sel.add(c.id)); upd(); },
      none: () => { sel.clear(); range = null; hint.textContent = 'Tap chapters individually, or long-press one and drag to select or clear a range.'; upd(); },
      go: () => { W.downloads.enqueue(s.id, [...sel]); A.toast(`Queued ${sel.size} chapters`, { action: 'View', onAction: () => W.go('/queue') }); W.back('/story/' + s.id); },
    });
    if (r.query.demo) { list.slice(0, 6).forEach((c) => sel.add(c.id)); upd(); }
  };

  // ---------- Trends ----------
  const RANGES = [['7d', '7D', 7], ['30d', '30D', 30], ['90d', '90D', 90], ['1y', '1Y', 365], ['all', 'All time', 1e5]];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['engagement', 'Engagement']];
  const groupOf = (x) => (x.kind === 'score' ? 'score' : /^patreon/.test(x.key) ? 'patreon' : 'engagement');
  const fv = (x, v) => (x.kind === 'score' ? v.toFixed(2) : x.kind === 'usd' ? '$' + W.fmt.num(Math.round(v)) : W.fmt.num(Math.round(v)));
  const sign = (x, d) => (d >= 0 ? '+' : '−') + fv(x, Math.abs(d)).replace(/^\$?/, x.kind === 'usd' ? '$' : '');

  function chart(x, pts) {
    const ts = pts.map((p) => p.t), vs = pts.map((p) => p.v);
    let lo = Math.min(...vs), hi = Math.max(...vs); if (hi - lo < 1e-9) { hi += x.kind === 'score' ? 0.05 : 1; lo -= x.kind === 'score' ? 0.05 : 1; }
    const pad = (hi - lo) * 0.12; lo -= pad; hi += pad;
    const t0 = ts[0], t1 = ts[ts.length - 1] === t0 ? t0 + 1 : ts[ts.length - 1];
    const X = (t) => ((t - t0) / (t1 - t0)) * 100, Y = (v) => (1 - (v - lo) / (hi - lo)) * 100;
    const line = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p.t).toFixed(2)},${Y(p.v).toFixed(2)}`).join(' ');
    const area = `${line} L100,100 L0,100 Z`;
    const last = pts[pts.length - 1];
    const gid = 'g' + x.key.replace(/\W/g, '');
    return `<div class="chart">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#d08a5c" stop-opacity=".32"/><stop offset="1" stop-color="#d08a5c" stop-opacity="0"/></linearGradient></defs>
        ${[25, 50, 75].map((g) => `<line x1="0" x2="100" y1="${g}" y2="${g}" stroke="rgba(236,224,202,.08)" stroke-dasharray="1 1.4" vector-effect="non-scaling-stroke"/>`).join('')}
        <path d="${area}" fill="url(#${gid})"/>
        <path d="${line}" fill="none" stroke="#e9ac80" stroke-width="1.6" vector-effect="non-scaling-stroke" stroke-linejoin="round" stroke-linecap="round"/>
      </svg>
      <span class="c-dot" style="left:${X(last.t)}%;top:${Y(last.v)}%"></span>
      <span class="c-y hi">${fv(x, hi - pad)}</span><span class="c-y lo">${fv(x, lo + pad)}</span>
      <span class="c-x l">${W.fmt.dateShort(t0)}</span><span class="c-x r">${W.fmt.dateShort(ts[ts.length - 1])}</span>
    </div>`;
  }

  A.screens.trends = (page, r) => {
    const s = W.story(r.params.id);
    if (!s) { page.innerHTML = A.bar({ title: 'Not found' }); return; }
    const series = W.trendSeries(s.id);
    const ui = { range: 'all', group: r.query.group || 'all' };
    const snaps = (W.data.metrics[s.id] || []);
    const render = () => {
      const days = RANGES.find((x) => x[0] === ui.range)[2];
      const since = Date.now() - days * 86400000;
      const shown = series.filter((x) => ui.group === 'all' || groupOf(x) === ui.group);
      page.innerHTML = `${A.bar({ title: 'Trends', sub: esc(A.shortTitle(s.title)) })}
        <div class="set-wrap">
          <div class="set-h"><div class="kicker">Trends · ${esc(s.source)}</div><h1 class="disp">${A.titleHTML(s.title)}</h1><p class="dek">by ${esc(s.author || 'Unknown')}</p>
          <p class="meta dim" style="margin-top:8px">${snaps.length} snapshot${snaps.length === 1 ? '' : 's'}${snaps.length ? ` · since ${W.fmt.date(snaps[0].at)} · updated ${W.fmt.date(snaps[snaps.length - 1].at)}` : ''}</p></div>
          <div class="chips" style="margin-top:18px">${RANGES.map(([k, l]) => `<button class="chip ${ui.range === k ? 'on' : ''}" data-act="range" data-v="${k}">${l}</button>`).join('')}</div>
          <div class="seg" style="margin-top:10px;overflow-x:auto;scrollbar-width:none">${GROUPS.map(([k, l]) => `<button class="${ui.group === k ? 'on' : ''}" data-act="group" data-v="${k}" style="white-space:nowrap">${l}</button>`).join('')}</div>
          <div class="trend-grid">
          ${!series.length ? '<div class="empty"><div class="ornament">⁂</div><h3>No trend data yet</h3><p>Sync this story a few times and its numbers will chart here.</p></div>' : shown.length ? shown.map((x) => {
            const pts = x.points.filter((p) => p.t >= since);
            const all = x.points; const cur = all[all.length - 1].v; const prev = all.length > 1 ? all[all.length - 2].v : cur;
            const m30 = all.filter((p) => p.t >= Date.now() - 30 * 86400000); const d30 = m30.length ? cur - m30[0].v : 0;
            const vs = pts.map((p) => p.v);
            return `<section class="series">
              <div class="label">${esc(x.label)}</div>
              <div class="s-cur"><b>${fv(x, cur)}</b><span>${sign(x, cur - prev)} since last sync</span></div>
              <p class="meta dim">${m30.length ? `${sign(x, d30)} in the last 30 days` : 'No change recorded in 30 days'}${vs.length ? ` · range ${fv(x, Math.min(...vs))}–${fv(x, Math.max(...vs))}` : ''}</p>
              ${pts.length >= 2 ? chart(x, pts) : '<div class="chart nodata"><span>Not enough data in this range</span></div>'}
            </section>`;
          }).join('') : '<div class="empty sm"><h3>Nothing in this group</h3><p>This story has no metrics of this kind.</p></div>'}
          </div>
        </div>`;
    };
    render();
    A.acts(page, { range: (t) => { ui.range = t.dataset.v; render(); }, group: (t) => { ui.group = t.dataset.v; render(); } });
  };

  // ---------- AI Controls ----------
  A.screens.ai = (page, r) => {
    const s = W.story(r.params.id);
    if (!s) { page.innerHTML = A.bar({ title: 'Not found' }); return; }
    const id = s.id;
    const ui = { tab: r.query.tab === 'description' ? 'desc' : 'cover', prompt: '', ctx: 'Automatic', writing: false, seenDesc: true };
    const m = W.ai.models;
    const render = () => {
      const st = W.ai.state(id);
      const job = st.job;
      const inUse = st.versions.find((v) => v.id === st.inUse) || st.versions[0];
      const pend = st.pending && st.versions.find((v) => v.id === st.pending);
      const descDot = st.draft && ui.tab !== 'desc';
      page.innerHTML = `${A.bar({ title: 'AI Controls', sub: esc(A.shortTitle(s.title)) })}
        <div class="set-wrap">
          <div class="seg" style="margin-top:6px"><button class="${ui.tab === 'cover' ? 'on' : ''}" data-act="tab" data-v="cover">Cover${pend && ui.tab !== 'cover' ? '<span class="dotn"></span>' : ''}</button><button class="${ui.tab === 'desc' ? 'on' : ''}" data-act="tab" data-v="desc">Description${descDot ? '<span class="dotn"></span>' : ''}</button></div>
          ${ui.tab === 'cover' ? `
            ${pend ? `<div class="card accent newcover"><span class="kicker">New cover ready</span>
              <div class="nc-pair"><figure>${img(inUse, s)}<figcaption>Current</figcaption></figure><span class="ms" style="color:var(--copper)">arrow_forward</span><figure>${img(pend, s)}<figcaption>New</figcaption></figure></div>
              <p class="meta">OpenRouter: ${esc(st.cost || '$0.04 · 2 calls')}</p>
              <div class="acts"><button class="btn sm" data-act="use" data-v="${pend.id}">Use new cover</button><button class="btn sm ghost" data-act="keep">Keep current</button></div></div>` : ''}
            ${A.secHead('', 'Covers')}
            <div class="covers">${st.versions.map((v) => `<button class="cv ${v.id === st.inUse ? 'on' : ''}" data-act="use" data-v="${v.id}" data-p="${v.id}">${img(v, s)}${v.id === st.inUse ? `<span class="cv-check">${I('check')}</span>` : ''}${v.isNew && st.pending === v.id ? '<span class="cv-new">New</span>' : ''}<span class="cv-l">${esc(v.label)}</span></button>`).join('')}</div>
            <p class="sec-hint" style="margin-top:10px">Tap a cover to use it. Long-press to see its prompt.</p>
            ${A.secHead('', 'Make a new cover')}
            <button class="idx-row" data-act="ctx" style="border-top:1px solid var(--rule)">${I('menu_book', 'lead')}<span class="leaders"><span class="l"><b>Cover context chapters</b></span><span class="d"></span><span class="v">${esc(ui.ctx)}</span></span>${I('expand_more')}</button>
            <label class="field" style="margin-top:16px"><span class="lab">Image prompt (optional)</span><textarea class="input" id="ip" placeholder="Leave empty and the AI writes one from the chapters">${esc(ui.prompt)}</textarea></label>
            <div class="stack-btns"><button class="btn ghost" data-act="write" ${ui.writing || job ? 'disabled' : ''}>${ui.writing ? '<span class="spin"></span>Writing…' : I('edit_note') + 'Write prompt with AI'}</button>
              <button class="btn" data-act="gen" ${job ? 'disabled' : ''}>${job && job.kind === 'cover' ? `<span class="spin"></span>${esc(job.message)}` : I('auto_awesome') + 'Generate cover'}</button></div>
            ${job && job.kind === 'cover' ? '<div class="prog indet" style="margin-top:12px"><i></i></div>' : ''}
            <p class="meta dim" style="margin-top:18px">Image: ${esc(m.image)} · Prompt: ${esc(m.description)} · <button class="link" data-go="/settings/ai">Change in AI Settings</button></p>`
          : `
            <button class="idx-row" data-act="ctx" style="margin-top:12px;border-top:1px solid var(--rule)">${I('menu_book', 'lead')}<span class="leaders"><span class="l"><b>Context chapters</b></span><span class="d"></span><span class="v">${esc(ui.ctx)}</span></span>${I('expand_more')}</button>
            ${A.secHead('', 'Current description')}
            ${st.desc ? `<span class="reason" style="color:var(--copper-hi)">${I('auto_awesome', 'xs')} AI-generated</span><p class="ai-desc">${esc(st.desc)}</p>` : `<p class="ai-desc faint">${esc((s.desc || '').slice(0, 260))}…</p><p class="meta dim">From ${esc(s.source)}</p>`}
            <button class="srow" data-act="showAi"><span class="check ${st.showAiDesc ? 'on' : ''}">${I('check')}</span><span class="grow"><b>Show AI description in Details</b></span></button>
            <button class="btn block" style="margin-top:16px" data-act="gendesc" ${job ? 'disabled' : ''}>${job && job.kind === 'description' ? '<span class="spin"></span>Generating…' : I('auto_awesome') + 'Generate Description with AI'}</button>
            ${st.draft ? `<div class="card accent" style="margin-top:18px"><span class="kicker">Draft</span><p class="ai-desc" style="color:var(--bone)">${esc(st.draft)}</p><div class="acts"><button class="btn sm" data-act="apply">Apply</button><button class="btn sm ghost" data-act="discard">Discard</button></div></div>` : ''}`}
        </div>`;
      const ip = page.querySelector('#ip'); if (ip) ip.addEventListener('input', () => { ui.prompt = ip.value; });
    };
    const img = (v, s0) => (v.url ? `<div class="book"><img src="${v.url}" alt="" loading="lazy" decoding="async"></div>` : A.book(s0));
    render();
    A.listen(W.ai, (st) => { if (st && (st.job ? st.job.storyId === id : true)) render(); });
    A.longPress(page, '.cv', (t) => { const v = W.ai.state(id).versions.find((x) => x.id === t.dataset.p); A.dialog({ title: esc(v.label) + ' · prompt', body: `<p class="it">${v.prompt ? esc(v.prompt) : 'The source cover from ' + esc(s.source) + '. No prompt.'}</p>`, actions: [{ label: 'Close' }, v.prompt ? { label: 'Reuse prompt', cls: 'text', act: () => { ui.prompt = v.prompt; render(); } } : null].filter(Boolean) }); });
    A.acts(page, {
      tab: (t) => { ui.tab = t.dataset.v; render(); },
      use: (t) => { const st = W.ai.state(id); const v = st.versions.find((x) => x.id === t.dataset.v); W.ai.useCover(id, v.id); if (v.id === 'src') delete S.aiCover[id]; else S.aiCover[id] = v.url; A.toast(`Using ${esc(v.label)} cover`); },
      keep: () => { W.ai.keepCurrent(id); A.toast('Kept the current cover'); },
      ctx: () => A.menu({ title: 'Context <i>chapters</i>', sub: 'Which chapters the AI reads first', groups: [{ items: ['Automatic', 'First 3 chapters', 'Around my bookmark', 'Latest 3 chapters'].map((x) => ({ label: x, on: ui.ctx === x, act: () => { ui.ctx = x; render(); } })) }] }),
      write: () => { ui.writing = true; render(); setTimeout(() => { ui.writing = false; ui.prompt = `A painted cover for “${A.shortTitle(s.title)}”: a cloaked heroine with twin pistols on a sun-baked marble stage, crowds blurred behind her, warm late light, no text.`; if (page.isConnected) render(); }, 1600); },
      gen: () => { W.ai.generateCover(id, ui.prompt.trim() || null); A.toast(ui.prompt.trim() ? 'Painting your cover…' : 'Writing a prompt, then painting…'); },
      showAi: () => { const st = W.ai.state(id); W.ai.setShowAi(id, !st.showAiDesc); },
      gendesc: () => W.ai.generateDescription(id),
      apply: () => { W.ai.applyDraft(id); A.toast('Description applied'); },
      discard: () => { W.ai.discardDraft(id); A.toast('Draft discarded'); },
    });
  };
})();
