/* v3 Restructured — Downloads destination: live queue with sections, site check, failures, expandable chapters. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;

  A.screens.queue = {
    persistent: true, navId: 'downloads',
    render() {
      const el = A.view('dlv');
      let finishedOpen = false;
      const statusCls = (it) => (it.status === 'paused' ? 'muted' : it.status === 'finished' ? (it.failed ? 'red' : 'sage') : it.note === 'pacing' ? 'copper' : it.status === 'queued' ? 'muted' : 'gold');
      function card(it, sect) {
        const s = W.story(it.storyId);
        const line = W.downloads.statusLine(it);
        const btn = it.status === 'downloading' ? ['pause', 'Pause'] : it.status === 'paused' || it.status === 'queued' ? ['play_arrow', 'Resume'] : it.failed ? ['refresh', 'Retry'] : null;
        return `<div class="card dcard ${it.expanded ? 'open' : ''}" data-d="${it.storyId}">
          <div class="drow">${sect === 'next' ? `<span class="handle" data-h="${it.storyId}" aria-label="Drag to reorder">${A.icon('drag_indicator')}</span>` : ''}
            <button class="dmain" data-x="exp">${A.coverHtml(s, '', '', `data-zoom="${s.id}" role="button" aria-label="View cover"`)}<div class="grow"><div class="t nowrap">${esc(it.title)}</div><div class="s">${esc(it.source)} · ${it.done} of ${it.total} chapters</div><div class="s ${statusCls(it)}">${it.status === 'downloading' && it.note !== 'pacing' ? '<span class="pulse-dot"></span>' : ''}${esc(line)}</div></div></button>
            ${btn ? `<button class="ib" data-x="${btn[1].toLowerCase()}" aria-label="${btn[1]}">${A.icon(btn[0])}</button>` : ''}<button class="ib" data-x="menu" aria-label="More">${A.icon('more_vert')}</button></div>
          ${A.prog(it.done / Math.max(1, it.total), it.status === 'paused' ? 'copper' : '')}
          ${it.expanded ? `<div class="dchs">${it.chapters.map((c) => `<div class="dch"><i class="cdot ${c.status === 'done' ? 'on' : ''} ${c.status === 'failed' ? 'fail' : ''} ${c.status === 'downloading' ? 'live' : ''}"></i><span class="grow nowrap">${c.index}. ${esc(c.title.replace(/^Chapter \d+:?\s*/, '') || c.title)}</span><span class="s ${c.status === 'failed' ? 'red' : c.status === 'downloading' ? 'gold' : 'muted'}">${c.status === 'failed' ? esc(c.error || 'Failed') : c.status === 'done' ? 'Done' : c.status === 'downloading' ? 'Downloading' : c.status === 'paused' ? 'Paused' : 'Queued'}</span>${c.status === 'failed' ? `<button class="ib" data-x="retry" aria-label="Retry">${A.icon('refresh', 'sm')}</button>` : ''}</div>`).join('')}</div>` : ''}
        </div>`;
      }
      function render() {
        const st = W.downloads.state();
        const sc = el.scrollTop;
        const sec = (title, items, key, aside = '') => (items.length ? `<div class="section-label">${title}<span class="aside">${aside}</span></div>${items.map((it) => card(it, key)).join('')}` : '');
        const empty = !st.items.length;
        const b = st.blocked;
        el.innerHTML = `${A.appbar({ title: 'Downloads', sub: empty ? 'Nothing in the queue' : `${st.summary.novels} novels · ${st.summary.done} of ${st.summary.total} chapters`, big: true,
          actions: (empty ? '' : A.ab(st.paused ? 'play_arrow' : 'pause', st.paused ? 'resumeall' : 'pauseall', st.paused ? 'Resume all' : 'Pause all')) + A.ab('tune', 'dlsettings', 'Download settings') + A.ab('more_vert', 'more', 'More') })}
          <div class="pad wrap">
          ${empty ? `<div class="empty">${A.icon('download_done')}<h3>Nothing downloading</h3><p>Chapters you download will appear here while they download.</p><button class="btn tonal" data-x="updates">${A.icon('new_releases')}Find new chapters</button></div>` : `
          ${!empty ? `<div class="dsum card pad16"><div class="row"><div class="grow"><div class="big">${Math.round((st.summary.done / Math.max(1, st.summary.total)) * 100)}%</div><div class="muted" style="font-size:12.5px">${st.summary.total - st.summary.done} chapters left · ${st.downloading.length} active</div></div>${st.paused ? '<span class="pillb copper">Paused</span>' : '<span class="pillb gold"><span class="pulse-dot"></span>Live</span>'}</div>${A.prog(st.summary.done / Math.max(1, st.summary.total), 'thick')}</div>` : ''}
          ${b ? `<div class="card ${b.state === 'verified' ? '' : 'tint'} pad16 sitecheck">${b.state === 'verified' ? `<div class="row">${A.icon('verified', 'sage')}<div class="grow"><b>${esc(b.site)} verified</b><div class="muted" style="font-size:12.5px">Resuming ${b.chapters} chapters…</div></div></div>` : `<div class="row" style="align-items:flex-start">${A.icon('shield_person', 'gold')}<div class="grow"><div class="eyebrow">Verification needed</div><b>${esc(b.site)} needs a quick check</b><div class="muted" style="font-size:12.5px">${b.novels} novel · ${b.chapters} chapters waiting</div></div></div><div class="btnrow" style="margin-top:12px"><button class="btn sm fill" data-x="verify">Verify now</button><button class="btn sm text" data-x="trouble">Having trouble?</button></div>`}</div>` : ''}
          ${st.failedChapters ? `<div class="card red pad16 failcard"><div class="row">${A.icon('error', 'red')}<div class="grow"><b>${st.failedChapters} chapter${st.failedChapters > 1 ? 's' : ''} couldn’t download</b><div class="muted" style="font-size:12.5px">Usually a network hiccup or the site limiting requests.</div></div><button class="btn sm danger" data-x="retryall">Retry all</button></div></div>` : ''}
          ${sec('Downloading now', st.downloading, 'now')}
          ${sec('Up next', st.upNext, 'next', `${A.icon('drag_indicator', 'xs')} Drag to reorder`)}
          ${sec('Paused', st.pausedItems, 'paused')}
          ${st.finished.length ? `<button class="section-label fin" data-x="fin">Finished · ${st.finished.length} novel${st.finished.length > 1 ? 's' : ''}${A.icon(finishedOpen ? 'expand_less' : 'expand_more', 'sm')}<span class="aside"><span data-x="clear" class="gold" style="padding:8px">Clear</span></span></button>${finishedOpen ? st.finished.map((it) => card(it, 'fin')).join('') : ''}` : ''}`}
          </div>`;
        el.scrollTop = sc;
      }
      function menu(it) {
        A.menu({ title: it.title, sub: `${it.done} of ${it.total} chapters`, sections: [{ items: [
          it.status === 'downloading' ? { icon: 'pause', label: 'Pause', fn: () => W.downloads.pause(it.storyId) } : it.status !== 'finished' && { icon: 'play_arrow', label: 'Resume', fn: () => W.downloads.resume(it.storyId) },
          it.status === 'queued' && { icon: 'vertical_align_top', label: 'Move to top', fn: () => { W.downloads.moveFirst(it.storyId); A.toast('Moved to the top'); } },
          it.failed && { icon: 'refresh', label: 'Retry failed chapters', fn: () => W.downloads.retryFailed(it.storyId) },
          { icon: 'menu_book', label: 'Open novel', fn: () => W.go('/story/' + it.storyId) },
          { icon: 'close', label: it.status === 'finished' ? 'Remove from list' : 'Cancel download', danger: it.status !== 'finished', fn: () => { W.downloads.remove(it.storyId); A.toast('Removed'); } },
        ] }] });
      }
      W.on(el, 'click', '[data-act]', async (e, b) => {
        const a = b.dataset.act;
        if (a === 'pauseall') { W.downloads.pauseAll(); A.toast('All downloads paused'); }
        else if (a === 'resumeall') { W.downloads.resumeAll(); A.toast('Resumed'); }
        else if (a === 'dlsettings') W.go('/settings/downloads');
        else if (a === 'more') A.menu({ title: 'Downloads', sections: [{ items: [
          { icon: 'refresh', label: 'Retry failed chapters', fn: () => { W.downloads.retryFailed(); A.toast('Retrying failed chapters'); } },
          { icon: 'done_all', label: 'Clear finished', fn: () => W.downloads.clearFinished() },
          { icon: 'cancel', label: 'Cancel all downloads', danger: true, fn: async () => { if (await A.confirm('Cancel all downloads?', 'Chapters already downloaded stay on your phone.', 'Cancel all', true)) W.downloads.cancelAll(); } },
        ] }] });
      });
      W.on(el, 'click', '[data-x]', (e, b) => {
        const x = b.dataset.x; const d = b.closest('[data-d]'); const id = d && d.dataset.d;
        const it = id && W.downloads.state().items.find((i) => i.storyId === id);
        if (x === 'exp') W.downloads.toggleExpanded(id);
        else if (x === 'pause') W.downloads.pause(id);
        else if (x === 'resume') W.downloads.resume(id);
        else if (x === 'retry') W.downloads.retryFailed(id);
        else if (x === 'menu') menu(it);
        else if (x === 'verify') { A.toast('Opening a quick check…'); setTimeout(() => W.downloads.verify(), 900); }
        else if (x === 'trouble') A.sheet({ title: 'Having trouble?', body: `<p class="muted">Some sites ask for a quick human check before chapters can download. Open the site, finish the check, then come back.</p><div class="list">${[['open_in_browser', 'Open the site in the browser'], ['restart_alt', 'Reset the source web session'], ['ios_share', 'Share source access logs']].map(([i, l]) => `<button class="li" data-y="1"><span class="lead">${A.icon(i)}</span><div class="grow t">${l}</div></button>`).join('')}</div>`, onMount: (sh, close) => W.on(sh, 'click', '[data-y]', () => { close(); A.toast('Done'); }) });
        else if (x === 'retryall') { W.downloads.retryFailed(); A.toast('Retrying failed chapters'); }
        else if (x === 'fin') { if (e.target.closest('[data-x=clear]')) return; finishedOpen = !finishedOpen; render(); }
        else if (x === 'clear') { e.stopPropagation(); W.downloads.clearFinished(); A.toast('Cleared finished'); }
        else if (x === 'updates') W.go('/updates');
      });
      // drag an "Up next" card upward to move it to the top
      let drag = null;
      el.addEventListener('pointerdown', (e) => { const h = e.target.closest('[data-h]'); if (!h) return; const c = h.closest('.dcard'); drag = { id: h.dataset.h, c, y: e.clientY }; c.classList.add('dragging'); h.setPointerCapture(e.pointerId); e.preventDefault(); });
      el.addEventListener('pointermove', (e) => { if (!drag) return; drag.dy = e.clientY - drag.y; drag.c.style.transform = `translateY(${drag.dy}px)`; });
      const end = () => { if (!drag) return; const { c, dy, id } = drag; drag = null; c.classList.remove('dragging'); c.style.transform = ''; if (dy < -40) { W.downloads.moveFirst(id); A.toast('Moved to the top of Up next'); } };
      el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
      W.downloads.on(() => { if (!el.hidden && !drag) render(); });
      render();
      return { el, update: render };
    },
  };
})();
