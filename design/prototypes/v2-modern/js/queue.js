/* Downloads queue. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const Q = WNA.downloads;
  const ui = { finishedOpen: true, order: null };

  const statusCls = (it, line) => (it.status === 'paused' ? 'muted' : it.status === 'finished' ? (it.failed ? 'err' : 'ok') : it.note === 'pacing' ? 'warn' : it.status === 'downloading' ? 'accent' : 'muted');
  function card(it, { drag } = {}) {
    const s = WNA.story(it.storyId); const line = Q.statusLine(it); const p = it.done / Math.max(1, it.total);
    const act = it.status === 'downloading' ? ['pause', 'Pause'] : it.status === 'paused' ? ['play_arrow', 'Resume'] : it.failed ? ['replay', 'Retry failed'] : it.status === 'queued' ? ['vertical_align_top', 'Move to top'] : null;
    return `<section class="dq ${it.status} ${it.expanded ? 'open' : ''}" data-id="${it.storyId}">
      <div class="dq-h">${drag ? `<span class="dq-drag" data-drag aria-label="Drag to reorder">${ic('drag_indicator')}</span>` : ''}
        <button class="dq-main" data-x="toggle">${V2.cover(s, { w: 44, h: 66 })}<span class="grow"><b class="clamp2">${esc(it.title)}</b><span class="t-bs muted">${esc(it.source)} · ${it.done} of ${it.total} chapters</span><span class="t-bs ${statusCls(it, line)} dq-line">${it.note === 'pacing' && it.status === 'downloading' ? ic('hourglass_top', 's16') : ''}${esc(it.note === 'pacing' && it.status === 'downloading' && WNA.hash(it.storyId) % 2 ? 'Site asked to slow down · resuming in 30 seconds' : line)}</span></span></button>
        ${act ? `<button class="ib ${it.status === 'downloading' ? 'tonal' : ''}" data-x="act" aria-label="${act[1]}">${ic(act[0], 'fill')}</button>` : ''}
        <button class="ib" data-x="menu" aria-label="More">${ic('more_vert')}</button></div>
      ${it.status === 'finished' ? '' : `<div class="dq-p">${V2.wavy(p, it.status === 'downloading' ? '' : 'flat')}</div>`}
      ${it.expanded ? `<div class="dq-chs">${it.chapters.map((c) => `<div class="dq-ch ${c.status}"><span class="ch-dot ${c.status === 'done' ? 'ok' : c.status === 'downloading' ? 'busy' : c.status === 'failed' ? 'fail' : 'queued'}"></span><span class="grow"><b class="nowrap">${c.index}. ${esc(c.title)}</b><span class="t-bs ${c.status === 'failed' ? 'err' : 'muted'}">${c.status === 'done' ? 'Downloaded' : c.status === 'downloading' ? 'Downloading…' : c.status === 'failed' ? `Failed · ${esc(c.error || 'unknown error')}` : c.status === 'paused' ? 'Paused' : 'Waiting'}</span></span>${c.status === 'failed' ? `<button class="ib" data-x="retry" aria-label="Retry">${ic('replay')}</button>` : ''}</div>`).join('')}</div>` : ''}
    </section>`;
  }

  V2.screens.queue = (ctx) => {
    const page = ctx.el;
    page.innerHTML = `${V2.bar({ title: 'Downloads', acts: '<span class="qa"></span>' + V2.ib('tune', 'settings', 'Download settings') + V2.ib('more_vert', 'more', 'More') })}<div class="scroll"><div class="maxw">${V2.large('Downloads')}<div class="dq-body"></div></div></div>`;
    V2.collapse(page);
    const body = page.querySelector('.dq-body');
    let dragging = false;
    function render(st) {
      if (dragging) return;
      page.querySelector('.qa').innerHTML = st.items.length ? V2.ib(st.paused ? 'play_arrow' : 'pause', st.paused ? 'resumeall' : 'pauseall', st.paused ? 'Resume all' : 'Pause all') : '';
      page.querySelector('.bar-large').innerHTML = `<h1>Downloads</h1><p>${st.items.length ? `${V2.plural(st.summary.novels, 'novel')} · ${fmt.int(st.summary.done)} of ${fmt.int(st.summary.total)} chapters` : 'All quiet'}</p>`;
      if (!st.items.length && !st.blocked) { body.innerHTML = `<div class="empty"><div class="blob">${ic('download_done')}</div><h3>Nothing downloading</h3><p>Chapters you download will appear here while they download.</p></div>`; return; }
      let up = st.upNext;
      if (ui.order) up = [...up].sort((a, b) => (ui.order.indexOf(a.storyId) + 1 || 999) - (ui.order.indexOf(b.storyId) + 1 || 999));
      const sec = (title, items, extra = '', opts = {}) => items.length ? `<div class="sec-h"><h2>${title}</h2>${extra}</div>${opts.hint ? `<p class="sec-hint">${opts.hint}</p>` : ''}<div class="wrap dq-list" ${opts.list ? `data-list="${opts.list}"` : ''}>${items.map((it) => card(it, opts)).join('')}</div>` : '';
      body.innerHTML = `<div class="wrap"><div class="dq-sum"><div class="row t-bs muted"><span class="grow">${st.paused ? 'Paused' : st.downloading.length ? 'Downloading' : 'Waiting'}</span><span>${Math.round((st.summary.done / Math.max(1, st.summary.total)) * 100)}%</span></div>${V2.wavy(st.summary.done / Math.max(1, st.summary.total), st.paused || !st.downloading.length ? 'flat' : '')}</div></div>
        ${st.blocked ? `<div class="wrap"><section class="card warn site">${st.blocked.state === 'verified' ? `<div class="row"><span class="hint-ic ok-ic">${ic('verified_user', 'fill')}</span><div class="grow"><b class="t-tm">Verified</b><p class="t-bs muted">${esc(st.blocked.site)} is back · resuming downloads</p></div></div>` : `<div class="row"><span class="hint-ic warn-ic">${ic('shield_person', 'fill')}</span><div class="grow"><span class="t-lm" style="color:var(--warn)">VERIFICATION NEEDED</span><b class="t-tm">${esc(st.blocked.site)} needs a quick check</b><p class="t-bs muted">${V2.plural(st.blocked.chapters, 'chapter')} from ${V2.plural(st.blocked.novels, 'novel')} are waiting.</p></div></div><div class="row"><button class="btn filled" data-a="verify">${ic('open_in_browser')}Verify now</button><button class="btn text" data-a="trouble">Having trouble?</button></div>`}</section></div>` : ''}
        ${st.failedChapters ? `<div class="wrap"><section class="card bad failc"><span class="hint-ic bad-ic">${ic('error', 'fill')}</span><div class="grow"><b class="t-tm">${V2.plural(st.failedChapters, 'chapter')} couldn’t download</b><p class="t-bs">Usually a network hiccup. Retrying often works.</p></div><button class="btn danger s" data-a="retryall">Retry all</button></section></div>` : ''}
        ${sec('Downloading now', st.downloading)}
        ${sec('Up next', up, '', { drag: true, hint: 'Drag to reorder.', list: 'up' })}
        ${sec('Paused', st.pausedItems)}
        ${st.finished.length ? `<div class="sec-h"><button class="row grow fin-t" data-a="fin">${ic(ui.finishedOpen ? 'expand_less' : 'expand_more')}<h2>Finished · ${V2.plural(st.finished.length, 'novel')}</h2></button><button class="btn text s" data-a="clearfin">Clear</button></div>${ui.finishedOpen ? `<div class="wrap dq-list">${st.finished.map((it) => card(it)).join('')}</div>` : ''}` : ''}`;
    }
    ctx.sub(Q.on((st) => { if (page.isConnected) render(st); }));
    render(Q.state());

    function itemMenu(it) {
      V2.menu({ title: it.title, sub: `${esc(it.source)} · ${it.done} of ${it.total} chapters`, items: [
        it.status === 'paused' ? { icon: 'play_arrow', label: 'Resume', onClick: () => Q.resume(it.storyId) } : it.status !== 'finished' && { icon: 'pause', label: 'Pause', onClick: () => Q.pause(it.storyId) },
        it.status === 'queued' && { icon: 'vertical_align_top', label: 'Move to top', onClick: () => { ui.order = null; Q.moveFirst(it.storyId); } },
        it.failed && { icon: 'replay', label: `Retry ${V2.plural(it.failed, 'failed chapter')}`, onClick: () => Q.retryFailed(it.storyId) },
        { icon: it.expanded ? 'unfold_less' : 'unfold_more', label: it.expanded ? 'Hide chapters' : 'Show chapters', onClick: () => Q.toggleExpanded(it.storyId) },
        { icon: 'menu_book', label: 'Open story', onClick: () => V2.go('/story/' + it.storyId) },
        { icon: 'close', label: it.status === 'finished' ? 'Remove from list' : 'Cancel download', danger: true, onClick: () => { Q.remove(it.storyId); V2.snack(`Removed ${V2.shortTitle(it.title)}`); } },
      ].filter(Boolean) });
    }
    page.addEventListener('click', async (e) => {
      const card = e.target.closest('[data-id]'); const x = e.target.closest('[data-x]');
      if (card && x) {
        const it = Q.state().items.find((i) => i.storyId === card.dataset.id);
        if (x.dataset.x === 'toggle') Q.toggleExpanded(it.storyId);
        if (x.dataset.x === 'menu') itemMenu(it);
        if (x.dataset.x === 'retry') Q.retryFailed(it.storyId);
        if (x.dataset.x === 'act') { if (it.status === 'downloading') Q.pause(it.storyId); else if (it.status === 'paused') Q.resume(it.storyId); else if (it.failed) Q.retryFailed(it.storyId); else { ui.order = null; Q.moveFirst(it.storyId); } }
        return;
      }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const st = Q.state();
      switch (a.dataset.a) {
        case 'pauseall': Q.pauseAll(); V2.snack('All downloads paused', { action: 'Resume', onAction: () => Q.resumeAll() }); break;
        case 'resumeall': Q.resumeAll(); break;
        case 'settings': V2.go('/settings/downloads'); break;
        case 'verify': V2.snack(`Opening ${st.blocked.site}…`); await V2.wait(900); Q.verify(); break;
        case 'trouble': V2.sheet({ title: 'Having trouble?', body: `<div class="pad col" style="gap:12px"><p class="muted">${esc(st.blocked.site)} sometimes shows a check page to apps. Verifying opens it in a browser view so you can pass the check once.</p><div class="seglist" style="margin:0"><div class="li"><span class="lead">${ic('cookie')}</span><span class="tx"><b>Reset the site session</b><span>Clears cookies for ${esc(st.blocked.site)}</span></span></div><div class="li"><span class="lead">${ic('timer')}</span><span class="tx"><b>Slow down requests</b><span>Raise the delay in Download settings</span></span></div><div class="li"><span class="lead">${ic('description')}</span><span class="tx"><b>Share source access logs</b><span>From Settings → Data &amp; backup</span></span></div></div></div>` }); break;
        case 'retryall': Q.retryFailed(); V2.snack('Retrying failed chapters'); break;
        case 'fin': ui.finishedOpen = !ui.finishedOpen; render(Q.state()); break;
        case 'clearfin': Q.clearFinished(); V2.snack('Finished downloads cleared'); break;
        case 'more': V2.menu({ title: 'Downloads', items: [{ icon: 'replay', label: 'Retry failed chapters', disabled: !st.failedChapters, onClick: () => Q.retryFailed() }, { icon: 'done_all', label: 'Clear finished', disabled: !st.finished.length, onClick: () => Q.clearFinished() }, { icon: 'cancel', label: 'Cancel all downloads', danger: true, disabled: !st.items.length, onClick: async () => { if (await V2.confirm({ title: 'Cancel all downloads?', text: 'Chapters already downloaded stay on your phone.', ok: 'Cancel all', danger: true, icon: 'cancel' })) Q.cancelAll(); } }] }); break;
      }
    });

    // drag to reorder "Up next"
    let d = null;
    page.addEventListener('pointerdown', (e) => {
      const h = e.target.closest('[data-drag]'); if (!h) return;
      const node = h.closest('.dq'); const list = node.parentNode;
      e.preventDefault(); dragging = true; h.setPointerCapture(e.pointerId);
      const r = node.getBoundingClientRect();
      d = { node, list, y0: e.clientY, h: r.height + 10, items: [...list.children], from: [...list.children].indexOf(node), to: 0 };
      d.to = d.from; node.classList.add('lift');
    });
    page.addEventListener('pointermove', (e) => {
      if (!d) return; const dy = e.clientY - d.y0; d.node.style.transform = `translateY(${dy}px) scale(1.02)`;
      d.to = V2.clamp(d.from + Math.round(dy / d.h), 0, d.items.length - 1);
      d.items.forEach((n, i) => { if (n === d.node) return; let sh = 0; if (d.from < d.to && i > d.from && i <= d.to) sh = -d.h; if (d.from > d.to && i < d.from && i >= d.to) sh = d.h; n.style.transform = sh ? `translateY(${sh}px)` : ''; });
    });
    const drop = () => {
      if (!d) return; const { items, from, to } = d;
      items.forEach((n) => { n.style.transform = ''; n.classList.remove('lift'); });
      const ids = items.map((n) => n.dataset.id); const [m] = ids.splice(from, 1); ids.splice(to, 0, m);
      ui.order = ids; d = null; dragging = false;
      if (to === 0 && from !== 0) Q.moveFirst(m);
      render(Q.state()); if (from !== to) V2.snack(`Moved ${V2.shortTitle(WNA.story(m).title)}`);
    };
    page.addEventListener('pointerup', drop); page.addEventListener('pointercancel', drop);
  };
})();
