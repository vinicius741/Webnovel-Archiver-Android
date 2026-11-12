/* v5-feed Downloads tab: live queue with sections, site check, failures, expandable chapters, drag to reorder. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc;
  const D = W.downloads;
  let finishedOpen = false;

  function statusCls(it) { if (it.status === 'paused') return 'muted'; if (it.status === 'finished') return it.failed ? 'bad' : 'ok'; if (it.status === 'queued') return 'muted'; if (it.note === 'pacing') return 'warn'; return 'acc'; }
  function card(it, section) {
    const s = W.story(it.storyId);
    const pct = it.total ? it.done / it.total : 0;
    const act = it.status === 'downloading' ? ['pause', 'pause', 'Pause'] : it.status === 'paused' ? ['resume', 'play_arrow', 'Resume'] : it.failed ? ['retry', 'refresh', 'Retry'] : it.status === 'queued' ? ['resume', 'play_arrow', 'Start now'] : null;
    return `<div class="dc ${it.expanded ? 'open' : ''} dc-${it.status}" data-id="${it.storyId}">
      <div class="dc-row">${section === 'next' ? `<span class="drag" data-drag aria-label="Drag to reorder">${A.ic('drag_indicator')}</span>` : ''}
        <button class="dc-main" data-exp>${A.cover(s, 'dc-cv')}<span class="dc-t"><b>${esc(A.short(it.title))}</b><small>${esc(it.source)} · ${it.done} of ${it.total} chapters</small><em class="s-${statusCls(it)}">${it.status === 'downloading' && it.note !== 'pacing' ? '<i class="dot d-dl"></i>' : ''}${esc(it.note === 'pacing' && it.status === 'downloading' ? 'Site asked to slow down · next chapter in 12 seconds' : D.statusLine(it))}</em></span></button>
        ${act ? `<button class="ibtn tonal sm" data-${act[0]} aria-label="${act[2]}">${A.ic(act[1], 'f')}</button>` : ''}<button class="ibtn sm" data-menu aria-label="More">${A.ic('more_vert')}</button></div>
      ${A.bar(pct, it.status === 'downloading' ? 'live' : '')}
      ${it.expanded ? `<div class="dc-chs">${it.chapters.map((c) => `<div class="dch"><i class="dot ${{ done: 'd-ok', downloading: 'd-dl', failed: 'd-bad', paused: 'd-no', queued: 'd-q' }[c.status] || 'd-no'}"></i><span><b>${c.index}. ${esc(c.title.replace(/^Chapter \d+[:.]?\s*/, '') || c.title)}</b><small class="${c.status === 'failed' ? 's-bad' : ''}">${{ done: 'Downloaded', downloading: 'Downloading…', failed: 'Failed · ' + (c.error || 'error'), paused: 'Paused', queued: 'Waiting' }[c.status]}</small></span>${c.status === 'failed' ? `<button class="btn ghost sm" data-retry>Retry</button>` : ''}</div>`).join('')}</div>` : ''}</div>`;
  }
  function body(d) {
    if (!d.items.length && !d.blocked) return A.empty('download_done', 'Nothing downloading', 'Chapters you download will appear here while they download.', '<button class="btn tonal" data-go="/library">Browse library</button>');
    const pct = d.summary.total ? d.summary.done / d.summary.total : 0;
    let h = `<div class="qsum"><div class="qsum-ring">${A.ring(pct, 76, 6)}<b>${Math.round(pct * 100)}%</b></div><div><b class="qsum-n">${d.summary.done} <span>of ${d.summary.total}</span></b><small>chapters · ${d.summary.novels} novels${d.paused ? ' · paused' : ''}</small></div></div>`;
    if (d.blocked) h += d.blocked.state === 'verified'
      ? `<div class="alert ok">${A.ic('verified_user', 'f')}<div><b>${esc(d.blocked.site)} verified</b><small>Downloads resume in a moment.</small></div></div>`
      : `<div class="alert warn">${A.ic('shield_person')}<div><small class="eyebrow">Verification needed</small><b>${esc(d.blocked.site)} needs a quick check</b><small>${d.blocked.chapters} chapters from ${d.blocked.novels} novel are waiting.</small><div class="alert-a"><button class="btn primary sm" data-verify>Verify now</button><button class="btn ghost sm" data-trouble>Having trouble?</button></div></div></div>`;
    if (d.failedChapters) h += `<div class="alert bad">${A.ic('error')}<div><b>${d.failedChapters} chapter${d.failedChapters > 1 ? 's' : ''} couldn’t download</b><small>Usually a network hiccup or the site limiting requests.</small><div class="alert-a"><button class="btn danger sm" data-retryall>Retry all</button></div></div></div>`;
    const sec = (title, sub, list, key, extra = '') => list.length ? `<div class="sec-h"><div><h2>${title}</h2>${sub ? `<p>${sub}</p>` : ''}</div>${extra}</div><div class="dcs" data-sec="${key}">${list.map((it) => card(it, key)).join('')}</div>` : '';
    h += sec('Downloading now', '', d.downloading, 'now');
    h += sec('Up next', d.upNext.length > 1 ? 'Drag to reorder' : '', d.upNext, 'next');
    h += sec('Paused', '', d.pausedItems, 'paused');
    if (d.finished.length) h += `<div class="sec-h"><button class="fin-t" data-fin><h2>Finished · ${d.finished.length} ${d.finished.length === 1 ? 'novel' : 'novels'}</h2>${A.ic(finishedOpen ? 'expand_less' : 'expand_more')}</button><button class="link" data-clearfin>Clear</button></div>${finishedOpen ? `<div class="dcs">${d.finished.map((it) => card(it, 'fin')).join('')}</div>` : ''}`;
    return h;
  }

  A.screens.queue = (ctx) => {
    A.tint(ctx.el, W.session ? W.story(W.session.storyId) : null);
    ctx.el.innerHTML = `<div class="sc"><div class="lib-title" style="padding-top:calc(16px + var(--sat))"><h1>Downloads</h1>
        <button class="ibtn" data-pauseall aria-label="Pause or resume all"></button><button class="ibtn" data-go="/settings/downloads" aria-label="Download settings">${A.ic('settings')}</button><button class="ibtn" data-qmenu aria-label="More">${A.ic('more_vert')}</button></div>
      <div class="wrap q-body"></div></div>`;
    const qb = ctx.el.querySelector('.q-body'), pa = ctx.el.querySelector('[data-pauseall]');
    const render = () => { if (dragging) return; const d = D.state(); qb.innerHTML = body(d); pa.innerHTML = A.ic(d.paused ? 'play_circle' : 'pause_circle'); pa.setAttribute('aria-label', d.paused ? 'Resume all' : 'Pause all'); };
    let dragging = null;
    render();
    ctx.on(D.on(render));
    // drag to reorder (Up next)
    qb.addEventListener('pointerdown', (e) => {
      const h = e.target.closest('[data-drag]'); if (!h) return;
      const cardEl = h.closest('.dc'); e.preventDefault();
      dragging = { el: cardEl, y0: e.clientY, id: cardEl.dataset.id }; cardEl.classList.add('lift'); h.setPointerCapture(e.pointerId);
    });
    qb.addEventListener('pointermove', (e) => { if (!dragging) return; dragging.dy = e.clientY - dragging.y0; dragging.el.style.transform = `translateY(${dragging.dy}px) scale(1.02)`; });
    const drop = () => {
      if (!dragging) return; const g = dragging; dragging = null;
      g.el.classList.remove('lift'); g.el.style.transform = '';
      if ((g.dy || 0) < -40) { D.moveFirst(g.id); A.toast('Moved to the top of Up next', { icon: 'drag_indicator' }); } else render();
    };
    qb.addEventListener('pointerup', drop); qb.addEventListener('pointercancel', drop);

    ctx.el.addEventListener('click', (e) => {
      const t = e.target, d = D.state();
      const c = t.closest('.dc'), id = c && c.dataset.id;
      if (t.closest('[data-pauseall]')) { if (d.paused) { D.resumeAll(); A.toast('Downloads resumed', { icon: 'play_arrow' }); } else { D.pauseAll(); A.toast('All downloads paused', { icon: 'pause' }); } return; }
      if (t.closest('[data-qmenu]')) return A.menu({ title: 'Downloads', items: [{ icon: 'refresh', label: 'Retry failed chapters', disabled: !d.failedChapters, fn: () => D.retryFailed() }, { icon: 'clear_all', label: 'Clear finished', fn: () => D.clearFinished() }, { icon: 'tune', label: 'Download settings', fn: () => A.go('/settings/downloads') }, { divider: true }, { icon: 'cancel', label: 'Cancel all downloads', danger: true, fn: () => A.confirm({ title: 'Cancel all downloads?', body: 'Chapters already downloaded stay on your phone.', ok: 'Cancel all', danger: true }).then((ok) => ok && (D.cancelAll(), A.toast('All downloads cancelled'))) }] });
      if (t.closest('[data-verify]')) { A.toast(`Opening ${d.blocked.site} check…`, { icon: 'shield_person' }); setTimeout(() => D.verify(), 900); return; }
      if (t.closest('[data-trouble]')) return A.sheet({ title: 'Having trouble?', html: `<div style="padding:0 8px 12px;display:grid;gap:12px;color:var(--t2);line-height:1.55"><p>Some sites show a “check you’re human” page. Webnovel Archiver opens it in a browser window so you can complete it once.</p><p>If it keeps coming back, reset the source web session in Data &amp; Backup, then try again.</p><button class="btn tonal" data-go="/settings/data">Open Data &amp; Backup</button></div>` });
      if (t.closest('[data-retryall]')) { D.retryFailed(); A.toast('Retrying failed chapters', { icon: 'refresh' }); return; }
      if (t.closest('[data-fin]')) { finishedOpen = !finishedOpen; render(); return; }
      if (t.closest('[data-clearfin]')) { D.clearFinished(); A.toast('Finished downloads cleared'); return; }
      if (!id) return;
      if (t.closest('[data-pause]')) return D.pause(id);
      if (t.closest('[data-resume]')) return D.resume(id);
      if (t.closest('[data-retry]')) { D.retryFailed(id); A.toast('Retrying', { icon: 'refresh' }); return; }
      if (t.closest('[data-exp]')) return D.toggleExpanded(id);
      if (t.closest('[data-menu]')) {
        const it = d.items.find((x) => x.storyId === id);
        return A.menu({ title: A.short(it.title), sub: `${it.done} of ${it.total} chapters`, tint: W.story(id), items: [
          ...(it.status === 'queued' ? [{ icon: 'vertical_align_top', label: 'Move to top', fn: () => D.moveFirst(id) }] : []),
          ...(it.status === 'downloading' ? [{ icon: 'pause', label: 'Pause', fn: () => D.pause(id) }] : it.status !== 'finished' ? [{ icon: 'play_arrow', label: 'Resume', fn: () => D.resume(id) }] : []),
          ...(it.failed ? [{ icon: 'refresh', label: 'Retry failed', fn: () => D.retryFailed(id) }] : []),
          { icon: it.expanded ? 'unfold_less' : 'unfold_more', label: it.expanded ? 'Hide chapters' : 'Show chapters', fn: () => D.toggleExpanded(id) },
          { icon: 'menu_book', label: 'Open story', fn: () => A.go('/story/' + id) },
          { icon: 'remove_circle', label: 'Remove from queue', danger: true, fn: () => { D.remove(id); A.toast('Removed from downloads'); } },
        ] });
      }
    });
  };
})();
