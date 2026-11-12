/* v3 Restructured — Updates destination: release forecasts, new chapters likely out, catch-up downloads, recent syncs. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;

  const hoursOf = (f) => { const m = /In (\d+) (h|day)/.exec(f.short || ''); return m ? (+m[1]) * (m[2] === 'h' ? 1 : 24) : 9999; };
  let batch = null; // {total, done, found, novels}

  function syncBatch(list) {
    if (batch) return;
    batch = { total: list.length, done: 0, found: 0, novels: 0, ids: [] };
    A.emit();
    let i = 0;
    const step = () => {
      const chunk = list.slice(i, i + 3); i += 3;
      chunk.forEach((s) => (A.state.syncing[s.id] = true));
      A.emit();
      setTimeout(() => {
        chunk.forEach((s) => {
          delete A.state.syncing[s.id]; A.state.synced[s.id] = Date.now(); s.lastSync = Date.now();
          const f = W.forecast(s);
          const n = f.state === 'due' ? W.hash(s.id) % 3 : 0;
          if (n) { s.total += n; batch.found += n; batch.novels++; batch.ids.push(s.id); const ls = W.chapters(s.id); for (let k = 0; k < n; k++) ls.push({ id: `${s.id}-new${ls.length + 1}`, title: `Chapter ${ls.length + 1}`, downloaded: false, publishedAt: Date.now(), index: ls.length + 1, audio: false }); }
          batch.done++;
        });
        A.emit();
        if (i < list.length) setTimeout(step, 90);
        else {
          const b = batch; batch = null; A.emit();
          A.toast(b.found ? `${b.found} new chapters in ${b.novels} novels` : 'Everything is up to date', b.found ? 'Download all' : '', () => { b.ids.forEach((id) => W.downloads.enqueue(id)); A.toast('Added to downloads'); });
        }
      }, 260);
    };
    step();
  }
  A.syncBatch = syncBatch;

  const FILTERS = [
    ['all', 'Overview'], ['due', 'May be out'], ['missing', 'To download'], ['soon', 'Expected'], ['late', 'Running late'], ['synced', 'Recently synced'],
  ];

  A.screens.updates = {
    persistent: true, navId: 'updates',
    render(route) {
      const el = A.view('updv');
      let filter = route.query.f || 'all';
      const groups = () => {
        const list = A.stories();
        const withF = list.map((s) => ({ s, f: W.forecast(s) }));
        return {
          due: withF.filter((x) => x.f.state === 'due' && !A.state.synced[x.s.id]).sort((a, b) => (b.s.updated || 0) - (a.s.updated || 0)),
          late: withF.filter((x) => x.f.state === 'late').sort((a, b) => (b.s.updated || 0) - (a.s.updated || 0)),
          soon: withF.filter((x) => x.f.state === 'expected').sort((a, b) => hoursOf(a.f) - hoursOf(b.f)),
          missing: list.filter((s) => s.total > s.downloaded).sort((a, b) => (b.updated || 0) - (a.updated || 0)).map((s) => ({ s, f: W.forecast(s) })),
          synced: list.slice().sort((a, b) => (A.state.synced[b.id] || b.lastSync || 0) - (A.state.synced[a.id] || a.lastSync || 0)).slice(0, 40).map((s) => ({ s, f: W.forecast(s) })),
        };
      };
      const row = ({ s, f }, kind) => {
        const syncing = A.state.syncing[s.id];
        const miss = s.total - s.downloaded;
        const dl = W.downloads.forStory(s.id);
        let sub, trail;
        if (kind === 'missing') {
          sub = dl ? `<span class="gold">Downloading · ${dl.done} of ${dl.total}</span>` : `${miss} chapter${miss > 1 ? 's' : ''} not downloaded · ${fmt.int(s.downloaded)} / ${fmt.int(s.total)}`;
          trail = dl ? `<span class="spinner gold"></span>` : `<button class="btn sm tonal" data-dl="${s.id}">${A.icon('download', 'sm')}${miss}</button>`;
        } else if (kind === 'synced') {
          sub = `Synced ${fmt.ago(A.state.synced[s.id] || s.lastSync)} · ${fmt.int(s.total)} chapters`;
          trail = `<button class="ib" data-sync="${s.id}" aria-label="Sync">${syncing ? '<span class="spinner gold"></span>' : A.icon('sync')}</button>`;
        } else {
          const cls = f.state === 'due' ? 'gold' : f.state === 'late' ? 'copper' : 'muted';
          sub = `<span class="${cls}">${esc(f.state === 'expected' ? f.text.replace('Next chapter expected', 'Next chapter') : f.text)}</span>`;
          trail = `<button class="ib" data-sync="${s.id}" aria-label="Sync">${syncing ? '<span class="spinner gold"></span>' : A.icon('sync')}</button>`;
        }
        if (A.state.synced[s.id] && kind === 'due') sub = '<span class="sage">Synced just now</span>';
        return `<div class="urow" data-id="${s.id}">${A.coverHtml(s)}<div class="grow"><div class="t nowrap">${esc(s.title)}</div><div class="s">${sub}</div></div>${trail}</div>`;
      };
      const calendar = (soon, due) => {
        const days = Array.from({ length: 7 }, (_, d) => ({ d, n: 0 }));
        days[0].n = due.length;
        soon.forEach((x) => { const h = hoursOf(x.f); const d = Math.min(6, Math.floor(h / 24)); if (h < 168) days[d].n++; });
        const max = Math.max(...days.map((x) => x.n), 1);
        const DN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
        return `<div class="cal">${days.map((x, i) => { const dt = new Date(Date.now() + i * 86400000); return `<div class="cday ${i === 0 ? 'today' : ''}"><div class="cbar"><i style="height:${Math.max(6, (x.n / max) * 100)}%"></i></div><b>${x.n}</b><span>${i === 0 ? 'Now' : DN[dt.getDay()]}</span></div>`; }).join('')}</div>`;
      };
      function render() {
        const g = groups();
        const sc = el.scrollTop;
        const counts = { due: g.due.length, late: g.late.length, soon: g.soon.length, missing: g.missing.length, synced: '' };
        const heroBusy = batch ? `<div class="row" style="margin-top:12px"><span class="spinner gold"></span><span>Checking ${batch.done} of ${batch.total} sources…</span></div>${A.prog(batch.done / batch.total, 'thick')}` : '';
        const section = (key, title, sub, n = 4) => {
          const items = g[key];
          if (!items.length) return '';
          return `<section class="usec"><div class="section-label">${title}<span class="aside"><button data-f="${key}">See all ${items.length}</button></span></div>
            ${sub ? `<div class="upd-hint">${sub}</div>` : ''}<div class="list ulist">${items.slice(0, n).map((x) => row(x, key)).join('')}</div></section>`;
        };
        let body;
        if (filter === 'all') {
          body = `<div class="card tint pad16 uhero">
              <div class="row"><div class="grow"><div class="eyebrow">Release forecast</div><h2>${g.due.length ? `${g.due.length} novels may have new chapters` : 'You’re all caught up'}</h2>
              <div class="muted">${g.soon.filter((x) => hoursOf(x.f) <= 48).length} more expected in the next 2 days · ${g.late.length} running late</div></div></div>
              ${calendar(g.soon, g.due)}
              ${batch ? heroBusy : `<div class="btnrow" style="margin-top:14px"><button class="btn fill" data-act="syncdue" ${g.due.length ? '' : 'disabled'}>${A.icon('sync')}Sync ${g.due.length}</button><button class="btn surface" data-f="missing">${A.icon('download')}${g.missing.length} to download</button></div>`}
            </div>
            ${section('due', 'May be out', 'Based on each novel’s usual release rhythm. Sync to check.')}
            ${section('missing', 'Chapters to download', '', 3)}
            ${section('soon', 'Expected soon', '', 4)}
            ${section('late', 'Running late', 'Longer than usual since the last chapter.', 3)}
            ${section('synced', 'Recently synced', '', 3)}`;
        } else {
          const items = g[filter] || [];
          const lead = filter === 'due' && items.length ? `<button class="btn tonal block" data-act="syncdue" style="margin:4px 0 12px" ${batch ? 'disabled' : ''}>${batch ? `<span class="spinner"></span>Checking ${batch.done} of ${batch.total}…` : `${A.icon('sync')}Sync all ${items.length}`}</button>`
            : filter === 'missing' && items.length ? `<button class="btn tonal block" data-act="dlall" style="margin:4px 0 12px">${A.icon('download')}Download all missing chapters</button>` : '';
          body = items.length ? `${lead}<div class="list ulist">${items.map((x) => row(x, filter)).join('')}</div>`
            : `<div class="empty">${A.icon(filter === 'due' ? 'done_all' : 'event_available')}<h3>${filter === 'due' ? 'Nothing waiting' : 'Nothing here'}</h3><p>${filter === 'due' ? 'No novel looks overdue right now. Synced novels drop off this list.' : 'Check back after your next sync.'}</p></div>`;
        }
        el.innerHTML = `${A.appbar({ title: 'Updates', sub: `Forecasts for ${A.stories().length} novels`, big: true, actions: A.ab('sync', 'syncdue', 'Sync novels that may have updates') + A.ab('download', 'queue', 'Downloads') })}
          <div class="chips scroll ufilters" style="padding-left:16px;margin:0">${FILTERS.map(([k, l]) => `<button class="chip ${filter === k ? 'on' : ''}" data-f="${k}">${esc(l)}${counts[k] !== undefined && counts[k] !== '' ? ` <span class="n">${counts[k]}</span>` : ''}</button>`).join('')}</div>
          <div class="pad wrap updbody ${filter === 'all' ? 'overview' : ''}">${body}</div>`;
        el.scrollTop = sc;
      }
      W.on(el, 'click', '[data-f]', (e, b) => { filter = b.dataset.f; render(); el.scrollTo({ top: 0 }); });
      W.on(el, 'click', '[data-sync]', (e, b) => { e.preventDefault(); A.sync(W.story(b.dataset.sync)); });
      W.on(el, 'click', '[data-dl]', (e, b) => { e.preventDefault(); A.downloadRemaining(W.story(b.dataset.dl)); render(); });
      W.on(el, 'click', '.urow', (e, r) => { if (!e.defaultPrevented) W.go('/story/' + r.dataset.id); });
      W.on(el, 'click', '[data-act]', (e, b) => {
        const a = b.dataset.act;
        if (a === 'syncdue') { const g = groups(); if (g.due.length) syncBatch(g.due.map((x) => x.s)); else A.toast('Nothing looks overdue right now'); }
        else if (a === 'queue') W.go('/queue');
        else if (a === 'dlall') { groups().missing.forEach((x) => W.downloads.enqueue(x.s.id)); A.toast('Queued every missing chapter', 'View', () => W.go('/queue')); render(); }
      });
      const rr = W.debounce(() => { if (!el.hidden) render(); }, 120);
      A.onChange(rr);
      W.downloads.on(W.debounce(() => { if (!el.hidden && filter === 'missing') render(); }, 600));
      render();
      return { el, update(r) { if (r.query.f) filter = r.query.f; render(); } };
    },
  };
})();
