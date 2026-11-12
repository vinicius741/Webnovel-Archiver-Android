/* v4 Editorial — Downloads queue as a ruled ledger. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon;
  const D = W.downloads;
  const ui = { finishedOpen: false, order: [] };

  A.screens.queue = (page) => {
    let dragging = false;
    function stCls(it) {
      if (it.status === 'paused') return 'none';
      if (it.status === 'finished') return it.failed ? 'fail' : 'ready';
      if (it.status === 'queued') return 'q';
      return 'gen';
    }
    function card(it, { drag = false } = {}) {
      const s = W.story(it.storyId);
      const pct = it.done / Math.max(1, it.total) * 100;
      const line = D.statusLine(it);
      const inline = it.status === 'paused' ? ['resume', 'play_arrow', 'Resume'] : it.status === 'finished' ? (it.failed ? ['retry', 'refresh', 'Retry'] : null) : ['pause', 'pause', 'Pause'];
      return `<div class="dl ${it.expanded ? 'open' : ''}" data-s="${it.storyId}">
        <div class="dl-row">
          ${drag ? `<span class="drag" aria-label="Drag to reorder">${I('drag_indicator')}</span>` : ''}
          <span style="width:40px;flex:none;margin-right:10px">${A.book(s)}</span>
          <button class="dl-main" data-act="exp"><b>${esc(A.shortTitle(it.title))}</b><small>${esc(it.source)} · ${it.done} of ${it.total} chapters ${I(it.expanded ? 'expand_less' : 'expand_more', 'xs')}</small><span class="st ${stCls(it)}">${esc(line)}${it.failed && it.status !== 'finished' ? ` · <span class="err">${it.failed} failed</span>` : ''}</span>${it.status !== 'finished' ? `<div class="prog"><i style="width:${pct.toFixed(1)}%"></i></div>` : ''}</button>
          ${inline ? `<button class="ib" data-act="${inline[0]}" aria-label="${inline[2]}">${I(inline[1])}</button>` : ''}
          <button class="ib" data-act="menu" aria-label="More">${I('more_vert')}</button>
        </div>
        ${it.expanded ? `<ul class="dl-chs">${it.chapters.map((c) => `<li><span class="ch-dot ${c.status === 'done' ? 'ok' : c.status === 'downloading' ? 'busy' : c.status === 'failed' ? 'bad' : 'wait'}"></span><span class="t">${c.index}. ${esc(A.chParts(c.title, c.index).name || c.title)}</span><span class="st ${c.status === 'failed' ? 'fail' : c.status === 'downloading' ? 'gen' : 'q'}">${{ done: 'Done', downloading: 'Downloading…', queued: 'Queued', paused: 'Paused', failed: 'Failed · ' + esc(c.error || '') }[c.status] || ''}</span>${c.status === 'failed' ? `<button class="ib" data-act="retry" aria-label="Retry">${I('refresh', 'sm')}</button>` : ''}</li>`).join('')}</ul>` : ''}
      </div>`;
    }
    function render() {
      const st = D.state();
      const up = st.upNext.slice().sort((a, b) => { const ia = ui.order.indexOf(a.storyId), ib = ui.order.indexOf(b.storyId); return (ia < 0 ? 1e9 : ia) - (ib < 0 ? 1e9 : ib); });
      const b = st.blocked;
      page.innerHTML = `${A.rootTop(`${A.ib(st.paused ? 'play_arrow' : 'pause', 'all', st.paused ? 'Resume all' : 'Pause all')}<button class="ib" data-go="/settings/downloads" aria-label="Download settings">${I('settings')}</button>${A.ib('more_vert', 'more', 'More')}`)}
        <div class="set-wrap">
          <div class="ledger-h"><div class="kicker">Downloads</div><h1 class="disp">The <i>queue</i></h1>
            <p class="dek">${st.items.length ? `${st.summary.novels} novel${st.summary.novels === 1 ? '' : 's'} · ${st.summary.done} of ${st.summary.total} chapters${st.paused ? ' · <span class="copper">all paused</span>' : ''}` : 'Nothing in the queue'}</p></div>
          ${st.items.length ? `<div class="dlsum"><div><b>${st.downloading.length}</b><span>Now</span></div><div><b>${st.upNext.length + st.pausedItems.length}</b><span>Waiting</span></div><div><b class="${st.failedChapters ? 'err' : ''}">${st.failedChapters}</b><span>Failed</span></div></div>` : ''}
          ${b ? (b.state === 'verified'
            ? `<div class="notice ok"><span class="ms">verified_user</span><div class="grow"><b>${esc(b.site)} verified</b><p>Resuming ${b.chapters} chapters…</p></div></div>`
            : `<div class="notice"><span class="ms">shield_person</span><div class="grow"><span class="kicker">Verification needed</span><b style="margin-top:6px">${esc(b.site)} needs a quick check</b><p>${b.chapters} chapters from ${b.novels} novel are waiting until you confirm you’re not a robot.</p><div class="acts"><button class="btn sm" data-act="verify">Verify now</button><button class="btn text sm" data-act="trouble">Having trouble?</button></div></div></div>`) : ''}
          ${st.failedChapters ? `<div class="notice bad"><span class="ms">error</span><div class="grow"><b>${st.failedChapters} chapter${st.failedChapters === 1 ? '' : 's'} couldn’t download</b><p>Usually the site was busy. Retrying often works.</p><div class="acts"><button class="btn text sm" data-act="retryAll">Retry all</button></div></div></div>` : ''}
          ${!st.items.length ? `<div class="empty"><div class="ornament">❦</div><h3>Nothing downloading</h3><p>Chapters you download will appear here while they download.</p><button class="btn ghost" data-go="/library">Browse the library</button></div>` : ''}
          ${st.downloading.length ? A.secHead('i', 'Downloading now') + `<div class="ledger">${st.downloading.map((it) => card(it)).join('')}</div>` : ''}
          ${up.length ? A.secHead('ii', 'Up next', '<span class="a" style="color:var(--bone-3)">Drag to reorder</span>') + `<div class="ledger upnext">${up.map((it) => card(it, { drag: true })).join('')}</div>` : ''}
          ${st.pausedItems.length ? A.secHead('iii', 'Paused') + `<div class="ledger">${st.pausedItems.map((it) => card(it)).join('')}</div>` : ''}
          ${st.finished.length ? `<div class="sec-head"><span class="n">iv</span><button class="t collapse-h" data-act="fin">Finished · ${st.finished.length} novel${st.finished.length === 1 ? '' : 's'} ${I(ui.finishedOpen ? 'expand_less' : 'expand_more', 'sm')}</button><span class="r"></span><button class="a" data-act="clear">Clear</button></div>${ui.finishedOpen ? `<div class="ledger">${st.finished.map((it) => card(it)).join('')}</div>` : ''}` : ''}
        </div>`;
    }
    render();
    A.listen(D, () => { if (!dragging) render(); });

    // drag to reorder "Up next"
    page.addEventListener('pointerdown', (e) => {
      const h = e.target.closest('.drag'); if (!h) return;
      const row = h.closest('.dl'); const list = row.parentNode; const rows = [...list.children];
      if (rows.length < 2) { A.toast('Only one novel is waiting'); return; }
      e.preventDefault(); dragging = true; row.classList.add('dragging');
      const y0 = e.clientY; const hgt = row.offsetHeight; let target = rows.indexOf(row);
      const move = (ev) => { const dy = ev.clientY - y0; row.style.transform = `translateY(${dy}px)`; target = Math.max(0, Math.min(rows.length - 1, rows.indexOf(row) + Math.round(dy / hgt))); };
      const up = () => {
        window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up);
        const ids = rows.map((r) => r.dataset.s); const [m] = ids.splice(rows.indexOf(row), 1); ids.splice(target, 0, m); ui.order = ids;
        if (target === 0) D.moveFirst(m);
        dragging = false; render(); A.toast('Order updated');
      };
      window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
    });

    const sid = (t) => t.closest('[data-s]').dataset.s;
    A.acts(page, {
      contents: () => A.contents(),
      all: () => { const p = D.state().paused; p ? D.resumeAll() : D.pauseAll(); A.toast(p ? 'Resumed all downloads' : 'Paused all downloads'); },
      more: () => A.menu({ title: 'Downloads', groups: [{ items: [
        { icon: 'refresh', label: 'Retry failed chapters', act: () => { D.retryFailed(); A.toast('Retrying failed chapters'); } },
        { icon: 'done_all', label: 'Clear finished', act: () => { D.clearFinished(); A.toast('Cleared finished novels'); } },
        { icon: 'cancel', label: 'Cancel all downloads', danger: true, act: () => A.confirm('Cancel all downloads?', 'Chapters already downloaded stay on this phone.', 'Cancel all', () => { D.cancelAll(); A.toast('All downloads cancelled'); }, true) }] }] }),
      verify: () => { A.toast(`Opening ${esc(D.state().blocked.site)}…`); setTimeout(() => D.verify(), 900); },
      trouble: () => A.dialog({ title: 'Having trouble?', body: '<p>Open the site in the in-app browser, finish its check, then come back. If it keeps asking, reset the source web session in Data &amp; Backup.</p>', actions: [{ label: 'Close' }, { label: 'Data & Backup', cls: 'text', act: () => W.go('/settings/data') }] }),
      retryAll: () => { D.retryFailed(); A.toast('Retrying'); },
      exp: (t) => D.toggleExpanded(sid(t)),
      pause: (t) => { D.pause(sid(t)); A.toast('Paused'); },
      resume: (t) => { D.resume(sid(t)); A.toast('Resumed'); },
      retry: (t) => { D.retryFailed(sid(t)); A.toast('Retrying'); },
      fin: () => { ui.finishedOpen = !ui.finishedOpen; render(); },
      clear: () => { D.clearFinished(); A.toast('Cleared finished novels'); },
      menu: (t) => {
        const id = sid(t); const it = D.state().items.find((x) => x.storyId === id);
        A.menu({ title: esc(A.shortTitle(it.title)), sub: `${it.done} of ${it.total} chapters`, groups: [{ items: [
          it.status === 'queued' && { icon: 'vertical_align_top', label: 'Download first', act: () => { D.moveFirst(id); A.toast('Moved to the top'); } },
          it.status === 'paused' ? { icon: 'play_arrow', label: 'Resume', act: () => D.resume(id) } : it.status !== 'finished' && { icon: 'pause', label: 'Pause', act: () => D.pause(id) },
          it.failed && { icon: 'refresh', label: 'Retry failed', act: () => D.retryFailed(id) },
          { icon: 'menu_book', label: 'Open story', act: () => W.go('/story/' + id) },
          { icon: 'close', label: 'Remove from queue', danger: true, act: () => { D.remove(id); A.toast('Removed from the queue'); } }] }] });
      },
    });
  };
})();
