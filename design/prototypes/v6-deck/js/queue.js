/* v6 — Downloads queue: big summary, site-check + failed cards, sections, swipe rows (pause/remove), drag reorder. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;

  V6.screens.queue = (ctx) => {
    const { root } = ctx;
    const ui = { finOpen: true };
    root.innerHTML = `${V6.topbar('Downloads', '', `<span data-pa></span>${V6.iconBtn('tune', 'data-go="/settings/downloads"', 'Download settings')}${V6.iconBtn('more_vert', 'data-k="menu"', 'More')}`)}<div data-b></div>`;
    const b = root.querySelector('[data-b]');
    const order = () => V6.state.queueOrder;

    const statusCls = (it) => (it.status === 'paused' ? 'mut' : it.status === 'finished' ? (it.failed ? 'bad' : 'ok') : it.note === 'pacing' ? 'mut' : it.status === 'downloading' ? 'acc' : 'mut');
    function card(it, { drag = false } = {}) {
      const s = W.story(it.storyId);
      const line = W.downloads.statusLine(it);
      const act = it.status === 'paused' ? ['play_arrow', 'resume', 'Resume'] : it.status === 'finished' ? (it.failed ? ['refresh', 'retry', 'Retry'] : null) : ['pause', 'pause', 'Pause'];
      return `<div class="qi ${it.status}" data-id="${it.storyId}">
        <div class="sw-under"></div>
        <div class="sw-card">
          <div class="qi-row">
            ${drag ? `<span class="drag" aria-label="Drag to reorder">${ic('drag_indicator')}</span>` : ''}
            <button class="qi-cov" data-go="/story/${it.storyId}">${s ? V6.cover(s) : ''}</button>
            <button class="qi-t" data-k="exp"><b>${esc(it.title)}</b><small>${esc(it.source)} · ${it.done} of ${it.total} chapters</small><small class="${statusCls(it)} qi-st">${it.status === 'downloading' && it.note !== 'pacing' ? '<i class="pulse"></i>' : ''}${esc(line)}</small></button>
            <span class="num qi-n">${Math.round((it.done / Math.max(1, it.total)) * 100)}<em>%</em></span>
            ${act ? `<button class="ib" data-k="${act[1]}" aria-label="${act[2]}">${ic(act[0])}</button>` : ''}
            <button class="ib" data-k="item" aria-label="More">${ic('more_vert')}</button>
          </div>
          ${V6.bar(it.done / Math.max(1, it.total), it.failed && it.status === 'finished' ? 'hot' : '')}
          ${it.expanded ? `<div class="qi-chs">${it.chapters.map((c) => `<div class="qc ${c.status}"><span class="dot"></span><span class="qc-t">${c.index}. ${esc(c.title)}</span><small>${c.status === 'failed' ? esc(c.error || 'failed') : c.status === 'done' ? 'Done' : c.status === 'downloading' ? 'Downloading…' : c.status === 'paused' ? 'Paused' : 'Queued'}</small>${c.status === 'failed' ? `<button class="ib" data-k="retry" aria-label="Retry">${ic('refresh')}</button>` : ''}</div>`).join('')}</div>` : ''}
        </div></div>`;
    }
    function sortUpNext(list) { const o = order(); return list.slice().sort((a, c) => { const i = o.indexOf(a.storyId), j = o.indexOf(c.storyId); return (i < 0 ? 999 : i) - (j < 0 ? 999 : j); }); }
    function draw() {
      const st = W.downloads.state();
      root.querySelector('[data-pa]').innerHTML = V6.iconBtn(st.paused ? 'play_arrow' : 'pause', `data-k="${st.paused ? 'resumeAll' : 'pauseAll'}"`, st.paused ? 'Resume all' : 'Pause all');
      if (!st.items.length) { b.innerHTML = V6.empty('download_done', 'Nothing downloading', 'Chapters you download will appear here while they download.', '<button class="btn ghost" data-go="/library">Back to the deck</button>'); return; }
      const bl = st.blocked;
      b.innerHTML = `
        <div class="q-sum"><div><span class="num">${st.summary.done}<em>/${st.summary.total}</em></span><small class="mono">chapters</small></div><div><span class="num">${st.summary.novels}</span><small class="mono">novels</small></div>${V6.bar(st.summary.done / Math.max(1, st.summary.total), 'thick')}</div>
        ${bl ? `<section class="card hot q-block"><div class="card-p"><span class="kick hot-t">${ic('verified_user')} ${bl.state === 'verified' ? 'Verified' : 'Verification needed'}</span><b>${bl.state === 'verified' ? `${esc(bl.site)} is working again` : `${esc(bl.site)} needs a quick check`}</b><p class="mut">${bl.state === 'verified' ? 'Resuming downloads…' : `${bl.chapters} chapters from ${bl.novels} novel are waiting. Open the site once to pass its check.`}</p>${bl.state === 'verified' ? '' : `<div class="row2"><button class="btn sm acc" data-k="verify">Verify now</button><button class="btn sm ghost" data-k="help">Having trouble?</button></div>`}</div></section>` : ''}
        ${st.failedChapters ? `<section class="card q-fail"><div class="card-p row-c"><span class="num hot-t">${st.failedChapters}</span><span class="rt"><b>${st.failedChapters === 1 ? 'chapter couldn’t' : 'chapters couldn’t'} download</b><small class="mut">Usually the site was busy. Retrying often works.</small></span><button class="btn sm solid" data-k="retryAll">Retry all</button></div></section>` : ''}
        <p class="hint q-hint">${ic('swipe')} Swipe a novel right to pause or resume, left to remove it.</p>
        ${st.downloading.length ? V6.sec('01', 'Downloading now') + st.downloading.map((i) => card(i)).join('') : ''}
        ${st.upNext.length ? V6.sec('02', 'Up next', '<span class="mono dim">Drag to reorder</span>') + `<div data-upnext>${sortUpNext(st.upNext).map((i) => card(i, { drag: true })).join('')}</div>` : ''}
        ${st.pausedItems.length ? V6.sec('03', 'Paused') + st.pausedItems.map((i) => card(i)).join('') : ''}
        ${st.finished.length ? V6.sec('04', `Finished · ${st.finished.length}`, `<button class="btn sm ghost" data-k="clearFin">Clear</button><button class="ib" data-k="fin" aria-label="Collapse">${ic(ui.finOpen ? 'expand_less' : 'expand_more')}</button>`) + (ui.finOpen ? st.finished.map((i) => card(i)).join('') : '') : ''}`;
    }
    // patch progress in place between structural changes so taps never land on a replaced node
    const sig = (st) => JSON.stringify([st.items.map((i) => i.storyId + i.status + i.expanded + (i.expanded ? i.chapters.map((c) => c.status).join('') : '') + i.failed), st.paused, st.blocked && st.blocked.state, ui.finOpen, order()]);
    let last = '';
    function onState(st) {
      const k = sig(st); if (k !== last) { last = k; return draw(); }
      st.items.forEach((it) => {
        const el = b.querySelector(`.qi[data-id="${it.storyId}"]`); if (!el) return;
        el.querySelector('.qi-t small').textContent = `${it.source} · ${it.done} of ${it.total} chapters`;
        el.querySelector('.qi-st').lastChild.textContent = W.downloads.statusLine(it);
        el.querySelector('.qi-n').innerHTML = `${Math.round((it.done / Math.max(1, it.total)) * 100)}<em>%</em>`;
        el.querySelector('.sw-card > .pbar i').style.width = (it.done / Math.max(1, it.total)) * 100 + '%';
      });
      const sm = b.querySelector('.q-sum .num'); if (sm) sm.innerHTML = `${st.summary.done}<em>/${st.summary.total}</em>`;
      const sb = b.querySelector('.q-sum .pbar i'); if (sb) sb.style.width = (st.summary.done / Math.max(1, st.summary.total)) * 100 + '%';
    }

    root.addEventListener('click', async (e) => {
      const k = e.target.closest('[data-k]'); if (!k) return;
      const id = (k.closest('[data-id]') || {}).dataset ? k.closest('[data-id]') && k.closest('[data-id]').dataset.id : null;
      const D = W.downloads; const a = k.dataset.k;
      const s = id && W.story(id);
      if (a === 'pauseAll') { D.pauseAll(); V6.toast('All downloads paused', { icon: 'pause' }); }
      if (a === 'resumeAll') { D.resumeAll(); V6.toast('Downloads resumed', { icon: 'play_arrow' }); }
      if (a === 'pause') D.pause(id);
      if (a === 'resume') D.resume(id);
      if (a === 'retry') D.retryFailed(id);
      if (a === 'retryAll') { D.retryFailed(); V6.toast('Retrying failed chapters', { icon: 'refresh' }); }
      if (a === 'exp') D.toggleExpanded(id);
      if (a === 'verify') { V6.toast(`Opening ${esc(D.state().blocked.site)}…`, { icon: 'verified_user' }); setTimeout(() => D.verify(), 900); }
      if (a === 'help') V6.sheet({ title: 'Having trouble?', html: '<div class="sh-pad"><p class="mut">Some sites show a “checking your browser” page. Open the site once in the in-app browser, wait for it to load, then come back. If it keeps failing, reset the source web session in Data &amp; Backup.</p><button class="btn ghost wide" data-go="/settings/data">Data &amp; Backup</button></div>' });
      if (a === 'clearFin') { D.clearFinished(); V6.toast('Cleared finished', { icon: 'clear_all' }); }
      if (a === 'fin') { ui.finOpen = !ui.finOpen; draw(); }
      if (a === 'menu') V6.sheet({ title: 'Downloads', items: [
        { icon: 'refresh', label: 'Retry failed chapters', run: () => D.retryFailed() },
        { icon: 'clear_all', label: 'Clear finished', run: () => D.clearFinished() },
        { icon: 'cancel', label: 'Cancel all downloads', danger: true, run: async () => { if (await V6.dialog({ title: 'Cancel all downloads?', body: 'Chapters already downloaded stay on your phone.', ok: 'Cancel all', cancel: 'Keep', danger: true })) D.cancelAll(); } },
      ] });
      if (a === 'item' && s) V6.sheet({ title: esc(s.title), items: [
        { icon: 'vertical_align_top', label: 'Download first', run: () => { D.moveFirst(id); V6.state.queueOrder = [id, ...order().filter((x) => x !== id)]; } },
        { icon: 'unfold_more', label: 'Show chapters', run: () => D.toggleExpanded(id) },
        { icon: 'open_in_full', label: 'Open novel', run: () => W.go('/story/' + id) },
        { icon: 'close', label: 'Remove from queue', danger: true, run: () => removeWithUndo(id) },
      ] });
    });
    function removeWithUndo(id) {
      const s = W.story(id); W.downloads.remove(id);
      V6.toast(`Removed · ${esc(V6.short(s))}`, { icon: 'close', action: 'Undo', onAction: () => W.downloads.enqueue(id) });
    }
    V6.swipeRows(b, '.qi', (row) => {
      const it = W.downloads.state().items.find((x) => x.storyId === row.dataset.id) || {};
      return {
        right: it.status === 'finished' ? null : { label: it.status === 'paused' ? 'Resume' : 'Pause', icon: it.status === 'paused' ? 'play_arrow' : 'pause', cls: 'acc', run: () => (it.status === 'paused' ? W.downloads.resume(it.storyId) : W.downloads.pause(it.storyId)) },
        left: { label: 'Remove', icon: 'delete', cls: 'hot', run: () => removeWithUndo(it.storyId) },
      };
    });
    // drag to reorder "Up next"
    b.addEventListener('pointerdown', (e) => {
      const h = e.target.closest('.drag'); if (!h) return;
      const row = h.closest('.qi'); const box = row.parentElement; e.preventDefault();
      const rows = [...box.children]; const y0 = e.clientY; const hgt = row.offsetHeight; let idx = rows.indexOf(row), to = idx;
      row.classList.add('lift'); h.setPointerCapture(e.pointerId);
      const mv = (ev) => {
        const dy = ev.clientY - y0; row.style.transform = `translateY(${dy}px)`;
        to = Math.max(0, Math.min(rows.length - 1, idx + Math.round(dy / hgt)));
        rows.forEach((r, i) => { if (r === row) return; let sh = 0; if (i > idx && i <= to) sh = -hgt; if (i < idx && i >= to) sh = hgt; r.style.transform = sh ? `translateY(${sh}px)` : ''; });
      };
      const up = () => {
        h.removeEventListener('pointermove', mv); h.removeEventListener('pointerup', up); h.removeEventListener('pointercancel', up);
        rows.forEach((r) => (r.style.transform = '')); row.classList.remove('lift');
        const ids = rows.map((r) => r.dataset.id); ids.splice(to, 0, ...ids.splice(idx, 1));
        V6.state.queueOrder = ids; if (to === 0) W.downloads.moveFirst(ids[0]);
        if (to !== idx) V6.toast('Queue reordered', { icon: 'swap_vert' });
        last = ''; onState(W.downloads.state());
      };
      h.addEventListener('pointermove', mv); h.addEventListener('pointerup', up); h.addEventListener('pointercancel', up);
    });
    ctx.sub(W.downloads.on(onState));
    onState(W.downloads.state());
  };
})();
