/* v0 Downloads (queue). */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon, D = W.downloads;
  let finishedOpen = true;

  A.register('queue', (route, ctx) => {
    const el = W.el(`<div><div data-bar></div><div class="scroll pb-mini" data-keep data-sc></div></div>`);
    const sc = el.querySelector('[data-sc]');
    function statusClass(it) { if (it.status === 'finished') return it.failed ? 'red' : 'gold'; if (it.status === 'paused') return 'muted'; if (it.note === 'pacing') return 'muted'; return it.status === 'downloading' ? 'gold' : 'muted'; }
    function card(it, drag) {
      const pct = (it.done / Math.max(1, it.total)) * 100;
      const status = it.status === 'downloading' && it.note === 'slow' ? 'Site asked to slow down · resuming in 30 seconds' : D.statusLine(it);
      const btn = it.status === 'downloading' || it.status === 'queued' ? ['pause', 'Pause'] : it.status === 'paused' ? ['play_arrow', 'Resume'] : it.failed ? ['refresh', 'Retry'] : null;
      return `<div class="card q-card" data-sid="${it.storyId}">
        <div class="q-top">${drag ? `<span class="q-drag">${ic('drag_indicator')}</span>` : ''}<button class="q-main" data-exp="${it.storyId}"><b>${esc(it.title)}</b><span class="muted">${esc(it.source)} · ${it.done} of ${it.total} chapters</span><span class="${statusClass(it)}">${esc(status)}</span></button>
          ${btn ? `<button class="icon-btn gold" data-ib="${btn[1]}" data-id="${it.storyId}" aria-label="${btn[1]}">${ic(btn[0])}</button>` : ''}<button class="icon-btn" data-qm="${it.storyId}" aria-label="More">${ic('more_vert')}</button></div>
        <div class="bar" style="margin-top:10px"><i style="width:${pct}%;${it.status === 'paused' ? 'background:var(--on-surface-v)' : ''}"></i></div>
        ${it.expanded ? `<div class="q-chs">${it.chapters.map((c) => `<div class="q-ch"><span class="dot ${c.status}"></span><span class="q-ct">${c.index}. ${esc(c.title)}</span><span class="${c.status === 'failed' ? 'red' : c.status === 'downloading' ? 'gold' : 'muted'}">${c.status === 'done' ? 'Done' : c.status === 'failed' ? 'Failed' : c.status === 'downloading' ? 'Downloading' : c.status === 'paused' ? 'Paused' : 'Queued'}</span>${c.status === 'failed' ? `<button class="icon-btn" data-retry="${it.storyId}" aria-label="Retry">${ic('refresh')}</button>` : ''}</div>`).join('')}</div>` : ''}
      </div>`;
    }
    function draw() {
      const st = D.state();
      const anyActive = st.downloading.length || st.upNext.length;
      el.querySelector('[data-bar]').innerHTML = A.appBar({ title: 'Downloads', actions: [
        ...(st.items.length ? [anyActive && !st.paused ? { id: 'pauseall', icon: 'pause', label: 'Pause all' } : st.pausedItems.length ? { id: 'resumeall', icon: 'play_arrow', label: 'Resume all' } : null].filter(Boolean) : []),
        { id: 'dlset', icon: 'settings', label: 'Download Settings' }, ...(st.items.length ? [{ id: 'qmore', icon: 'more_vert', label: 'More' }] : [])] });
      if (!st.items.length && !st.blocked) { sc.innerHTML = `<div class="empty" style="min-height:calc(100% - 100px)"><div class="disc">${ic('download')}</div><b>Nothing downloading</b><span>Chapters you download will appear here while they download.</span></div>`; return; }
      const sec = (t, list, extra = '', drag = false) => list.length ? `<div class="sec-label q-sec">${t}${extra}</div>${list.map((i) => card(i, drag)).join('')}` : '';
      sc.innerHTML = `<div class="pad capped">
        <div class="muted" style="margin-bottom:12px">${st.summary.novels} novel${st.summary.novels === 1 ? '' : 's'} · ${st.summary.done} of ${st.summary.total} chapters</div>
        ${st.blocked ? `<div class="card q-alert">${st.blocked.state === 'verified' ? `<h3>${ic('check_circle')} ${esc(st.blocked.site)} verified</h3><div class="card-sub">Downloads are resuming.</div>` : `<div class="gold" style="font-weight:700;font-size:13px;letter-spacing:.5px">Verification needed</div><h3>${esc(st.blocked.site)} needs a quick check</h3><div class="card-sub">${st.blocked.chapters} chapters from ${st.blocked.novels} novel are waiting until you open the site once.</div><div class="btn-row" style="margin-top:12px"><button class="btn filled sm" data-a="verify">Verify now</button><button class="btn text sm" data-a="trouble">Having trouble?</button></div>`}</div>` : ''}
        ${st.failedChapters ? `<div class="card q-fail"><h3 class="red">${st.failedChapters} chapter${st.failedChapters === 1 ? '' : 's'} couldn't download</h3><div class="card-sub">The site was unreachable or limited requests.</div><button class="btn text sm" data-a="retryall" style="padding:0;margin-top:6px">Retry all</button></div>` : ''}
        ${sec('Downloading now', st.downloading)}
        ${sec('Up next', st.upNext, '<span class="q-hint">Drag to reorder</span>', true)}
        ${sec('Paused', st.pausedItems)}
        ${st.finished.length ? `<div class="sec-label q-sec"><button class="q-fin" data-a="fintoggle">Finished · ${st.finished.length} novel${st.finished.length === 1 ? '' : 's'} ${ic(finishedOpen ? 'expand_less' : 'expand_more')}</button><button class="btn text sm" data-a="clearfin">Clear</button></div>${finishedOpen ? st.finished.map((i) => card(i)).join('') : ''}` : ''}
      </div>`;
    }
    // Drag an "Up next" card by its handle; dropping it higher moves it to the front of the line.
    let drag = null;
    ctx.sub(D.on(() => { if (!drag) draw(); }));
    el.addEventListener('pointerdown', (e) => {
      const h = e.target.closest('.q-drag'); if (!h) return;
      const card = h.closest('.q-card'); drag = { card, id: card.dataset.sid, y: e.clientY, dy: 0 };
      card.style.zIndex = 3; card.style.position = 'relative'; card.style.boxShadow = '0 6px 18px rgba(0,0,0,.5)'; h.setPointerCapture(e.pointerId);
    });
    el.addEventListener('pointermove', (e) => { if (!drag) return; drag.dy = e.clientY - drag.y; drag.card.style.transform = `translateY(${drag.dy}px)`; });
    const drop = () => { if (!drag) return; const d = drag; drag = null; if (d.dy < -40) { D.moveFirst(d.id); A.toast('Moved up'); } draw(); };
    el.addEventListener('pointerup', drop); el.addEventListener('pointercancel', drop);
    el.addEventListener('click', (e) => {
      const t = (q) => e.target.closest(q); let x;
      if ((x = t('[data-ib]'))) { const id = x.dataset.id; const k = x.dataset.ib; if (k === 'Pause') D.pause(id); if (k === 'Resume') D.resume(id); if (k === 'Retry') D.retryFailed(id); return; }
      if ((x = t('[data-retry]'))) { D.retryFailed(x.dataset.retry); return; }
      if ((x = t('[data-exp]'))) { D.toggleExpanded(x.dataset.exp); return; }
      if ((x = t('[data-qm]'))) {
        const id = x.dataset.qm; const it = D.state().items.find((i) => i.storyId === id);
        A.menu({ title: it.title, wide: false, sections: [{ items: [
          { label: 'Open novel', fn: () => W.go('/story/' + id) },
          ...(it.status === 'queued' ? [{ label: 'Download next', fn: () => D.moveFirst(id) }] : []),
          ...(it.failed ? [{ label: 'Retry failed chapters', fn: () => D.retryFailed(id) }] : []),
          { label: it.expanded ? 'Hide chapters' : 'Show chapters', fn: () => D.toggleExpanded(id) },
          { label: it.status === 'finished' ? 'Remove from list' : 'Cancel download', danger: it.status !== 'finished', fn: () => D.remove(id) },
        ] }] });
        return;
      }
      if ((x = t('[data-a]'))) {
        const k = x.dataset.a;
        if (k === 'pauseall') { D.pauseAll(); A.toast('Downloads paused'); }
        if (k === 'resumeall') { D.resumeAll(); A.toast('Downloads resumed'); }
        if (k === 'dlset') W.go('/settings/downloads');
        if (k === 'verify') { D.verify(); A.toast('Opening SpaceBattles to verify…'); }
        if (k === 'trouble') A.dialog({ title: 'Having trouble?', body: '<p style="margin:0">Open the site in the in-app browser, complete the check, then come back. If it keeps asking, reset the source web session in Data &amp; Backup.</p>', actions: [{ label: 'Close' }, { label: 'Data & Backup', fn: () => W.go('/settings/data') }] });
        if (k === 'retryall') { D.retryFailed(); A.toast('Retrying failed chapters'); }
        if (k === 'fintoggle') { finishedOpen = !finishedOpen; draw(); }
        if (k === 'clearfin') D.clearFinished();
        if (k === 'qmore') A.menu({ sections: [{ items: [{ label: 'Retry failed chapters', fn: () => D.retryFailed() }, { label: 'Clear finished', fn: () => D.clearFinished() }] }, { divider: true, items: [{ label: 'Cancel all downloads', danger: true, fn: () => A.confirm({ title: 'Cancel all downloads?', message: 'Chapters already downloaded are kept.', ok: 'Cancel all', danger: true, fn: () => D.cancelAll() }) }] }] });
      }
    });
    draw();
    return { el };
  });
})();
