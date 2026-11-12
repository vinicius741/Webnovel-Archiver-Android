/* v1 Refined — Select Chapters, Trends, Author Novels, Add Story. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;

  // ---------- Select Chapters ----------
  V.screens.select = (r) => {
    const s = WNA.story(r.params.id);
    const id = s.id;
    const pending = () => WNA.chapters(id).filter((c) => !c.downloaded);
    const sel = new Set();
    let rangeMode = false, anchor = null;

    const el = WNA.el(`
      <div class="screen">
        ${V.appBar({ title: 'Select Chapters', subtitle: s.title })}
        <div class="wrap">
          <div class="hint-slot"></div>
          <div class="btn-row sel-tools" style="margin:4px 0 8px">
            ${V.btn('Select range', 'range', { kind: 'tonal', icon: 'linear_scale', cls: 'sm' })}
            ${V.btn('All', 'all', { kind: 'outlined', cls: 'sm' })}
            ${V.btn('Clear', 'clear', { kind: 'outlined', cls: 'sm' })}
          </div>
          <div class="rows"></div>
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);
    const list = pending();

    function hint() {
      let h;
      if (rangeMode && anchor == null) h = `<div class="selhint range">${V.ic('touch_app')}<span>Tap the first chapter in your range.</span></div>`;
      else if (rangeMode) h = `<div class="selhint range">${V.ic('touch_app')}<span>Chapter ${anchor} is the start. Tap the last chapter.</span></div>`;
      else h = `<div class="selhint">${V.ic('info')}<span>Tap chapters one by one, or long-press one and drag to select or clear a range.</span></div>`;
      $('.hint-slot').innerHTML = h;
      $('[data-act="range"]').classList.toggle('filled', rangeMode);
      $('[data-act="range"]').classList.toggle('tonal', !rangeMode);
    }
    function bar() {
      let b = el.querySelector('.botbar');
      if (!b) { b = WNA.el('<div class="botbar"></div>'); el.appendChild(b); }
      b.innerHTML = V.btn(sel.size ? `Download ${V.plural(sel.size, 'chapter')}` : 'Select chapters to download', 'go', { kind: 'filled', icon: 'download', cls: 'block lg', disabled: !sel.size });
    }
    function rows() {
      if (!list.length) {
        $('.sel-tools').hidden = true;
        $('.rows').innerHTML = V.empty({ icon: 'task_alt', title: 'All caught up', body: 'Every chapter of this story is already on your phone.', action: V.btn('Back to story', 'back', { kind: 'tonal' }) });
        return;
      }
      $('.rows').innerHTML = list.map((c) => `<div class="sr-row ${sel.has(c.id) ? 'on' : ''} ${anchor === c.index ? 'anchor' : ''}" data-cid="${esc(c.id)}" data-i="${c.index}"><span class="check ${sel.has(c.id) ? 'on' : ''}">${V.ic('check')}</span><span class="idx">${c.index}</span><span class="t ell">${esc(c.title)}</span></div>`).join('');
    }
    function paint() {
      el.querySelectorAll('.sr-row').forEach((rw) => { const on = sel.has(rw.dataset.cid); rw.classList.toggle('on', on); rw.querySelector('.check').classList.toggle('on', on); rw.classList.toggle('anchor', anchor === +rw.dataset.i); });
      bar();
    }
    const setRange = (a, b, on) => { const lo = Math.min(a, b), hi = Math.max(a, b); list.forEach((c) => { if (c.index >= lo && c.index <= hi) on ? sel.add(c.id) : sel.delete(c.id); }); };

    hint(); rows(); if (list.length) bar();

    V.bind(el, {
      range() { rangeMode = !rangeMode; anchor = null; hint(); paint(); },
      all() { list.forEach((c) => sel.add(c.id)); paint(); },
      clear() { sel.clear(); anchor = null; paint(); },
      go() {
        const n = sel.size;
        WNA.downloads.enqueue(id, [...sel]);
        WNA.back();
        setTimeout(() => V.toast(`Downloading ${V.plural(n, 'chapter')}`, { action: 'View', onAction: () => WNA.go('/queue') }), 250);
      },
    });
    WNA.on(el, 'click', '.sr-row', (e, t) => {
      const i = +t.dataset.i;
      if (rangeMode) {
        if (anchor == null) { anchor = i; sel.add(t.dataset.cid); }
        else { setRange(anchor, i, true); anchor = null; rangeMode = false; }
        hint(); paint(); return;
      }
      sel.has(t.dataset.cid) ? sel.delete(t.dataset.cid) : sel.add(t.dataset.cid);
      paint();
    });
    // long-press + drag range
    let drag = null;
    V.longPress(el, '.sr-row', (t) => { const i = +t.dataset.i; drag = { from: i, on: !sel.has(t.dataset.cid), base: new Set(sel) }; setRange(i, i, drag.on); paint(); });
    el.addEventListener('touchmove', (e) => { if (drag) e.preventDefault(); }, { passive: false });
    el.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const t = document.elementFromPoint(e.clientX, e.clientY);
      const rw = t && t.closest('.sr-row');
      if (!rw) return;
      sel.clear(); drag.base.forEach((x) => sel.add(x));
      setRange(drag.from, +rw.dataset.i, drag.on); paint();
    });
    ['pointerup', 'pointercancel'].forEach((ev) => el.addEventListener(ev, () => { drag = null; }));
    V.watchScroll(el);
    return { el, title: 'Select Chapters', noMini: true };
  };

  // ---------- Trends ----------
  const RANGES = [['7d', '7D', 7], ['30d', '30D', 30], ['90d', '90D', 90], ['1y', '1Y', 365], ['all', 'All time', 0]];
  const GROUPS = [['all', 'All metrics'], ['score', 'Score'], ['patreon', 'Patreon'], ['eng', 'Engagement']];
  const fmtV = (kind, v) => (kind === 'score' ? v.toFixed(2) : kind === 'usd' ? '$' + fmt.num(Math.round(v)) : fmt.num(Math.round(v)));
  const signed = (kind, d) => (d >= 0 ? '+' : '−') + fmtV(kind, Math.abs(d));

  function drawChart(box, pts, kind) {
    const w = box.clientWidth, h = box.clientHeight;
    if (!w || pts.length < 2) return;
    const padR = 40, padB = 18, padT = 8;
    const t0 = pts[0].t, t1 = pts[pts.length - 1].t;
    let lo = Math.min(...pts.map((p) => p.v)), hi = Math.max(...pts.map((p) => p.v));
    if (hi === lo) { hi += kind === 'score' ? 0.05 : 1; lo -= kind === 'score' ? 0.05 : 1; }
    const pad = (hi - lo) * 0.12; lo -= pad; hi += pad;
    const X = (t) => ((t - t0) / Math.max(1, t1 - t0)) * (w - padR);
    const Y = (v) => padT + (1 - (v - lo) / (hi - lo)) * (h - padT - padB);
    const line = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p.t).toFixed(1)},${Y(p.v).toFixed(1)}`).join('');
    const area = `${line}L${X(t1).toFixed(1)},${h - padB}L0,${h - padB}Z`;
    const gid = 'g' + Math.random().toString(36).slice(2, 7);
    const grid = [0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${w - padR}" y1="${padT + f * (h - padT - padB)}" y2="${padT + f * (h - padT - padB)}" stroke="rgba(236,230,221,.06)" />`).join('');
    const last = pts[pts.length - 1];
    box.innerHTML = `<svg viewBox="0 0 ${w} ${h}" aria-hidden="true">
      <defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c9a84c" stop-opacity=".32"/><stop offset="1" stop-color="#c9a84c" stop-opacity="0"/></linearGradient></defs>
      ${grid}
      <path d="${area}" fill="url(#${gid})"/>
      <path d="${line}" fill="none" stroke="#c9a84c" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
      <circle cx="${X(last.t)}" cy="${Y(last.v)}" r="4" fill="#e2c46e" stroke="#191815" stroke-width="2"/>
      <text class="ax" x="${w - padR + 6}" y="${padT + 4}">${fmtV(kind, hi - pad)}</text>
      <text class="ax" x="${w - padR + 6}" y="${h - padB}">${fmtV(kind, lo + pad)}</text>
      <text class="ax" x="0" y="${h - 2}">${fmt.dateShort(t0)}</text>
      <text class="ax" x="${w - padR}" y="${h - 2}" text-anchor="end">${fmt.dateShort(t1)}</text>
      <line class="scrub" x1="0" x2="0" y1="${padT}" y2="${h - padB}" stroke="rgba(236,230,221,.35)" stroke-dasharray="3 3" visibility="hidden"/>
      <circle class="sdot" r="5" fill="#e2c46e" stroke="#191815" stroke-width="2" visibility="hidden"/>
    </svg><div class="tip" hidden></div>`;
    const svg = box.querySelector('svg'), sc = svg.querySelector('.scrub'), sd = svg.querySelector('.sdot'), tip = box.querySelector('.tip');
    const show = (e) => {
      const rx = e.clientX - box.getBoundingClientRect().left;
      let best = pts[0]; pts.forEach((p) => { if (Math.abs(X(p.t) - rx) < Math.abs(X(best.t) - rx)) best = p; });
      const x = X(best.t), y = Y(best.v);
      sc.setAttribute('x1', x); sc.setAttribute('x2', x); sc.setAttribute('visibility', 'visible');
      sd.setAttribute('cx', x); sd.setAttribute('cy', y); sd.setAttribute('visibility', 'visible');
      tip.hidden = false; tip.style.left = Math.max(40, Math.min(w - 50, x)) + 'px';
      tip.innerHTML = `${fmtV(kind, best.v)}<small>${fmt.dateShort(best.t)}</small>`;
    };
    const hide = () => { sc.setAttribute('visibility', 'hidden'); sd.setAttribute('visibility', 'hidden'); tip.hidden = true; };
    box.onpointerdown = show; box.onpointermove = (e) => { if (e.buttons || e.pointerType !== 'mouse') show(e); };
    box.onpointerup = box.onpointerleave = () => setTimeout(hide, 900);
  }

  V.screens.trends = (r) => {
    const s = WNA.story(r.params.id);
    const id = s.id;
    const all = WNA.trendSeries(id);
    const st = { range: r.query.range || 'all', group: 'all' };
    const snaps = (WNA.data.metrics[id] || []);
    const el = WNA.el(`
      <div class="screen">
        ${V.appBar({ title: 'Trends', subtitle: s.title })}
        <div class="wrap">
          <div class="tr-head" style="padding:8px 4px 16px">
            <h1>${esc(s.title)}</h1>
            <p>by ${esc(s.author || 'Unknown')}</p>
            <p class="faint">${snaps.length ? `${V.plural(snaps.length, 'snapshot')} · since ${fmt.date(snaps[0].at)} · updated ${fmt.date(snaps[snaps.length - 1].at)}` : 'Snapshots are taken every time you sync'}</p>
          </div>
          <div class="chip-scroll rng" style="padding-bottom:8px"></div>
          <div class="chip-scroll grp" style="padding-bottom:16px"></div>
          <div class="trends-grid"></div>
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);
    const ros = [];
    function chips() {
      $('.rng').innerHTML = RANGES.map(([k, l]) => `<button class="chip ${st.range === k ? 'on' : ''}" data-act="rng" data-k="${k}">${l}</button>`).join('');
      $('.grp').innerHTML = GROUPS.map(([k, l]) => `<button class="chip ${st.group === k ? 'on' : ''}" data-act="grp" data-k="${k}">${st.group === k ? V.ic('check') : ''}${l}</button>`).join('');
    }
    function cards() {
      ros.splice(0).forEach((o) => o.disconnect());
      const days = RANGES.find((x) => x[0] === st.range)[2];
      const cut = days ? WNA.NOW - days * 86400000 : 0;
      const inGroup = (x) => st.group === 'all' || (st.group === 'score' && x.kind === 'score') || (st.group === 'patreon' && x.key.startsWith('patreon')) || (st.group === 'eng' && x.kind === 'count');
      const list = all.filter(inGroup);
      const grid = $('.trends-grid');
      if (!list.length) { grid.innerHTML = V.empty({ icon: 'monitoring', title: 'No trend data yet', body: 'Sync this story a few times and its numbers will chart here.' }); return; }
      grid.innerHTML = list.map((x, i) => {
        const pts = x.points.filter((p) => p.t >= cut);
        const P = pts.length >= 2 ? pts : x.points.slice(-2);
        const cur = x.points[x.points.length - 1].v;
        const prev = x.points.length > 1 ? x.points[x.points.length - 2].v : cur;
        const d30 = cur - (x.points.find((p) => p.t >= WNA.NOW - 30 * 86400000) || x.points[0]).v;
        const lo = Math.min(...P.map((p) => p.v)), hi = Math.max(...P.map((p) => p.v));
        const dCls = cur - prev > 0 ? 'up' : cur - prev < 0 ? 'down' : '';
        return `<div class="card tcard"><h3>${esc(x.label)}</h3>
          <div class="cur"><b>${fmtV(x.kind, cur)}</b><span class="d ${dCls}">${signed(x.kind, cur - prev)}</span><span class="faint" style="font:var(--t-xs)">since last sync</span></div>
          <div class="sub">${signed(x.kind, d30)} in the last 30 days · range ${fmtV(x.kind, lo)}–${fmtV(x.kind, hi)}</div>
          <div class="chart" data-i="${i}"></div></div>`;
      }).join('');
      grid.querySelectorAll('.chart').forEach((box) => {
        const x = list[+box.dataset.i];
        const pts = x.points.filter((p) => p.t >= cut);
        const ro = new ResizeObserver(() => drawChart(box, pts.length >= 2 ? pts : x.points.slice(-2), x.kind));
        ro.observe(box); ros.push(ro);
      });
    }
    chips(); cards();
    V.bind(el, {
      rng(t) { st.range = t.dataset.k; chips(); cards(); },
      grp(t) { st.group = t.dataset.k; chips(); cards(); },
    });
    V.watchScroll(el);
    return { el, title: 'Trends', destroy: () => ros.forEach((o) => o.disconnect()) };
  };

  // ---------- Author Novels ----------
  V.screens.author = (r) => {
    const s = WNA.story(r.params.id);
    const list = WNA.authorNovels(s.id).filter((x) => !V.state.removed.has(x.id));
    const el = WNA.el(`
      <div class="screen">
        ${V.appBar({ title: s.author || 'Author', subtitle: list.length ? `${V.plural(list.length, 'other novel')} in your library` : 'No other novels in your library' })}
        <div class="wrap wide">
          <div class="section"><div class="sec-h"><h2>Also in your library</h2></div>
          <div class="grid list">${list.map((x) => V.listCard(x)).join('') || V.empty({ icon: 'person_search', title: 'Nothing else yet', body: `You haven’t archived other novels by ${s.author}.` })}</div></div>
          <div class="section"><div class="sec-h"><h2>This novel</h2></div><div class="grid list">${V.listCard(s)}</div></div>
          ${V.btn(`Find more by ${s.author} on ${s.source}`, 'web', { kind: 'outlined', icon: 'open_in_new', cls: 'block', data: 'style="margin-top:16px"' })}
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    V.bind(el, { web: () => V.toast(`Opening ${s.source} author page…`) });
    WNA.on(el, 'click', '.lc[data-id]', (e, t) => WNA.go('/story/' + t.dataset.id));
    V.watchScroll(el);
    return { el, title: s.author };
  };

  // ---------- Add Story ----------
  V.screens.add = () => {
    let tab = V.state.tab && V.state.tab !== 'all' && V.state.tab !== 'unassigned' ? V.state.tab : (WNA.tabs()[0] || {}).id;
    let busy = false;
    const el = WNA.el(`
      <div class="screen">
        ${V.appBar({ title: 'Add Story', subtitle: 'Paste a story URL to import' })}
        <div class="wrap">
          <div class="add-hero"><div class="eic">${V.ic('add_link')}</div><p>Works with RoyalRoad, Scribble Hub, SpaceBattles,<br>Sufficient Velocity, FanFiction.net and AO3.</p></div>
          <div class="card pad" style="display:grid;gap:16px;margin-top:12px">
            <label class="field"><span class="lab">Story URL</span>
              <span class="input url">${V.ic('link')}<input type="url" inputmode="url" placeholder="https://www.royalroad.com/fiction/…" aria-label="Story URL">
              <button class="btn text sm" data-act="paste" style="margin-right:-8px">${V.ic('content_paste')}Paste</button></span>
              <span class="help urlhelp" hidden></span>
            </label>
            <div class="field"><span class="lab">Save to tab</span>
              <button class="input" data-act="tab" style="width:100%;text-align:left">${V.ic('folder')}<span class="grow tabname" style="font:var(--t-body);color:var(--text)"></span>${V.ic('arrow_drop_down')}</button>
            </div>
            ${V.btn('Fetch Story', 'fetch', { kind: 'filled', icon: 'cloud_download', cls: 'block lg' })}
            <div class="fetch-slot"></div>
          </div>
          <div class="section"><div class="sec-h"><h2>Or browse a source</h2></div>
            <div class="list">${WNA.sources.map((x) => `<button class="li has-ic" data-act="browse" data-host="${x.host}"><span class="srcdisc">${V.ic('public')}</span><div class="grow"><div class="lt">${esc(x.name)}</div><div class="ls">${esc(x.host)}</div></div><span class="btn text sm" style="pointer-events:none">Open${V.ic('arrow_forward')}</span></button>`).join('')}</div>
          </div>
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);
    const input = $('.url input');
    const setTab = () => { $('.tabname').textContent = tab ? V.tabName(tab) : 'Unassigned'; };
    setTab();
    const err = (m) => { const h = $('.urlhelp'); h.hidden = !m; h.textContent = m || ''; h.classList.toggle('err', !!m); $('.url').classList.toggle('err', !!m); };
    input.addEventListener('input', () => err(''));

    V.bind(el, {
      paste() { input.value = 'https://www.royalroad.com/fiction/164525'; err(''); V.toast('Pasted from clipboard'); },
      tab() { V.menu({ title: 'Save to tab', items: [{ act: '', icon: 'inbox', label: 'Unassigned', on: !tab }, ...WNA.tabs().map((t) => ({ act: t.id, icon: 'folder', label: t.name, on: t.id === tab }))], onPick(a) { tab = a || null; setTab(); } }); },
      browse(t) { V.toast(`Opening ${t.dataset.host} in the browser…`); },
      fetch() {
        if (busy) return;
        const v = input.value.trim();
        if (!v) { err('Paste a story link first.'); input.focus(); return; }
        const host = WNA.sources.find((x) => v.includes(x.host.replace(/^forums\./, '')));
        if (!host) { err('This site isn’t supported yet. Try one of the sources below.'); return; }
        busy = true;
        const num = (v.match(/(\d{4,})/) || [])[1];
        const target = (num && WNA.stories().find((x) => x.id.endsWith('_' + num))) || WNA.story('rr_164525') || WNA.stories()[0];
        const steps = ['Starting…', `Reading the story page on ${host.name}`, `Found ${fmt.int(target.total)} chapters`, 'Saving cover and details', `Adding to ${tab ? V.tabName(tab) : 'Unassigned'}`];
        const b = $('[data-act="fetch"]'); b.disabled = true; b.innerHTML = '<span class="spin"></span><span>Fetching…</span>';
        let i = 0;
        const paint = () => { $('.fetch-slot').innerHTML = `<div class="fetch" style="margin-top:-4px"><div class="prog"><i style="width:${V.pct((i + 1) / steps.length)}"></i></div><div class="steps">${steps.map((x, k) => `<div class="step ${k < i ? 'done' : k === i ? 'now' : ''}">${k < i ? V.ic('check_circle', 'sm') : k === i ? '<span class="spin"></span>' : V.ic('radio_button_unchecked', 'sm')}${esc(x)}</div>`).join('')}</div></div>`; };
        paint();
        const t = setInterval(() => {
          i++;
          if (i >= steps.length) { clearInterval(t); if (tab !== undefined) target.tab = tab; V.libDirty = true; WNA.go(`/story/${target.id}?new=1`, { replace: true }); return; }
          paint();
        }, 750);
      },
    });
    V.watchScroll(el);
    return { el, title: 'Add Story' };
  };
})();
