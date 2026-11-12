/* v1 Refined — Downloads queue. */
(function () {
  const V = window.V1;
  const { esc } = WNA;
  const D = WNA.downloads;

  V.screens.queue = () => {
    const el = WNA.el(`<div class="screen">${V.appBar({ title: 'Downloads', subtitle: ' ', actions: '<span class="qacts" style="display:flex"></span>' })}<div class="wrap body"></div><div class="pad-bottom"></div></div>`);
    const $ = (q) => el.querySelector(q);
    let finishedOpen = false, verifying = false;

    const tone = (it) => {
      if (it.status === 'paused') return 'muted';
      if (it.status === 'finished') return it.failed ? 'bad' : 'ok';
      if (it.status === 'queued') return 'muted';
      if (it.note === 'pacing') return 'wait';
      return 'live';
    };
    const chRow = (c) => `<div class="qch ${c.status}"><i class="d"></i><span class="grow ell">${c.index}. ${esc(c.title.replace(/^Chapter \d+:\s*/, ''))}</span><span class="qs">${c.status === 'done' ? 'Done' : c.status === 'downloading' ? 'Downloading…' : c.status === 'failed' ? `Failed · ${esc(c.error || 'error')}` : c.status === 'paused' ? 'Paused' : 'Queued'}</span>${c.status === 'failed' ? `<button class="ib sm" data-act="retry1" data-id="${'x'}" aria-label="Retry">${V.ic('refresh')}</button>` : ''}</div>`;
    function card(it, { drag = false } = {}) {
      const s = WNA.story(it.storyId);
      const p = it.done / Math.max(1, it.total);
      const ctl = it.status === 'downloading' || it.status === 'queued' ? V.ib('pause', 'pause1', 'Pause', { cls: 'sm', data: `data-id="${it.storyId}"` })
        : it.status === 'paused' ? V.ib('play_arrow', 'resume1', 'Resume', { cls: 'sm gold', data: `data-id="${it.storyId}"` })
          : it.failed ? V.ib('refresh', 'retry', 'Retry failed', { cls: 'sm gold', data: `data-id="${it.storyId}"` }) : '';
      return `<div class="card qn ${it.status}" data-id="${it.storyId}">
        <div class="qn-top" data-act="expand" data-id="${it.storyId}">
          ${drag ? `<span class="drag" aria-hidden="true">${V.ic('drag_indicator')}</span>` : ''}
          ${V.cover(s, 36)}
          <div class="grow"><b class="ell">${esc(it.title)}</b>
            <div class="qmeta tnum">${esc(it.source)} · ${it.done} of ${it.total} chapters${V.ic(it.expanded ? 'expand_less' : 'expand_more', 'xs')}</div>
            <div class="qstat ${tone(it)}">${it.status === 'downloading' && it.note !== 'pacing' ? '<span class="spin"></span>' : ''}${esc(D.statusLine(it))}${it.failed && it.status !== 'finished' ? ` · <span class="err">${it.failed} failed</span>` : ''}</div>
          </div>
          ${ctl}
          ${V.ib('more_vert', 'more1', 'More', { cls: 'sm', data: `data-id="${it.storyId}"` })}
        </div>
        <div class="prog ${tone(it)}"><i style="width:${V.pct(p)}"></i></div>
        ${it.expanded ? `<div class="qchs">${it.chapters.slice(0, 40).map(chRow).join('')}${it.chapters.length > 40 ? `<div class="faint" style="font:var(--t-xs);padding:6px 0">and ${it.chapters.length - 40} more</div>` : ''}</div>` : ''}
      </div>`;
    }
    const section = (title, items, opts = {}) => (items.length ? `<section class="section"><div class="sec-h"><h2>${title}</h2>${opts.right || ''}</div>${opts.help ? `<p class="sec-help">${opts.help}</p>` : ''}<div class="col" style="gap:10px">${items.map((i) => card(i, opts)).join('')}</div></section>` : '');

    function render() {
      const st = D.state();
      const sub = st.items.length ? `${V.plural(st.summary.novels, 'novel')} · ${st.summary.done} of ${st.summary.total} chapters` : 'Nothing queued';
      $('.appbar .st').textContent = sub;
      $('.qacts').innerHTML = (st.items.some((i) => i.status === 'downloading' || i.status === 'queued') ? V.ib('pause_circle', 'pauseall', 'Pause all') : st.pausedItems.length ? V.ib('play_circle', 'resumeall', 'Resume all', { cls: 'gold' }) : '') + V.ib('tune', 'dset', 'Download settings') + V.ib('more_vert', 'more', 'More');
      if (!st.items.length && !st.blocked) {
        $('.body').innerHTML = V.empty({ icon: 'download_done', title: 'Nothing downloading', body: 'Chapters you download will appear here while they download.', action: V.btn('Back to library', 'lib', { kind: 'tonal' }) });
        return;
      }
      const bl = st.blocked;
      $('.body').innerHTML = `
        ${bl ? `<div class="card qalert ${bl.state === 'verified' ? 'ok' : ''}">${V.ic(bl.state === 'verified' ? 'verified_user' : 'shield_person')}<div class="grow">
          ${bl.state === 'verified' ? `<b>${esc(bl.site)} verified</b><p>Resuming ${V.plural(bl.chapters, 'chapter')}…</p>` : `<span class="gb-l">Verification needed</span><b>${esc(bl.site)} needs a quick check</b><p>${V.plural(bl.novels, 'novel')} · ${V.plural(bl.chapters, 'chapter')} are waiting. Solve it once in the built-in browser.</p>
          <div class="btn-row">${verifying ? `<button class="btn filled sm" disabled><span class="spin"></span>Opening…</button>` : V.btn('Verify now', 'verify', { kind: 'filled', cls: 'sm' })}${V.btn('Having trouble?', 'trouble', { kind: 'text', cls: 'sm' })}</div>`}
        </div></div>` : ''}
        ${st.failedChapters ? `<div class="card qalert bad">${V.ic('error')}<div class="grow"><b>${V.plural(st.failedChapters, 'chapter')} couldn’t download</b><p>Usually a network hiccup or the site limiting requests.</p></div>${V.btn('Retry all', 'retry', { kind: 'tonal', cls: 'sm' })}</div>` : ''}
        ${section('Downloading now', st.downloading)}
        ${section('Up next', st.upNext, { drag: true, help: 'Drag to reorder, or use ⋮ → Download first.' })}
        ${section('Paused', st.pausedItems)}
        ${st.finished.length ? `<section class="section"><div class="sec-h"><button class="fin-t" data-act="fin">${V.ic(finishedOpen ? 'expand_less' : 'expand_more')}<h2>Finished · ${V.plural(st.finished.length, 'novel')}</h2></button>${V.btn('Clear', 'clearfin', { kind: 'text' })}</div>${finishedOpen ? `<div class="col" style="gap:10px">${st.finished.map((i) => card(i)).join('')}</div>` : ''}</section>` : ''}`;
    }
    render();

    V.bind(el, {
      lib: () => WNA.go('/library'),
      pauseall() { D.pauseAll(); V.toast('All downloads paused'); },
      resumeall() { D.resumeAll(); V.toast('Downloads resumed'); },
      dset: () => WNA.go('/settings/downloads'),
      more() {
        const st = D.state();
        V.menu({ title: 'Downloads', items: [
          { act: 'retry', icon: 'refresh', label: 'Retry failed chapters', disabled: !st.failedChapters },
          { act: 'clear', icon: 'done_all', label: 'Clear finished', disabled: !st.finished.length },
          { act: 'cancel', icon: 'cancel', label: 'Cancel all downloads', danger: true, disabled: !st.items.length },
        ], async onPick(a) {
          if (a === 'retry') { D.retryFailed(); V.toast('Retrying failed chapters'); }
          if (a === 'clear') D.clearFinished();
          if (a === 'cancel' && await V.confirm('Cancel all downloads?', 'Chapters already downloaded are kept.', 'Cancel all')) { D.cancelAll(); V.toast('Downloads cancelled'); }
        } });
      },
      verify() { verifying = true; render(); setTimeout(() => { verifying = false; D.verify(); V.toast('Verified · downloads resume'); }, 1400); },
      trouble: () => V.dialog({ icon: 'help', title: 'Having trouble?', body: 'Open the site in the built-in browser, finish its check, then come back. If it keeps asking, wait a few minutes or lower Parallel sources in Download Settings.', actions: [{ label: 'Download settings', value: 'set' }, { label: 'OK', value: null, kind: 'filled' }] }).then((r) => r.value === 'set' && WNA.go('/settings/downloads')),
      retry(t) { D.retryFailed(t.dataset.id); V.toast('Retrying'); },
      retry1() { D.retryFailed(); },
      pause1: (t) => D.pause(t.dataset.id),
      resume1: (t) => D.resume(t.dataset.id),
      expand: (t) => D.toggleExpanded(t.dataset.id),
      fin() { finishedOpen = !finishedOpen; render(); },
      clearfin: () => { D.clearFinished(); V.toast('Cleared finished'); },
      more1(t) {
        const id = t.dataset.id; const it = D.state().items.find((x) => x.storyId === id);
        V.menu({ title: it.title, sub: D.statusLine(it), items: [
          { act: 'open', icon: 'menu_book', label: 'Open story' },
          it.status === 'queued' ? { act: 'first', icon: 'vertical_align_top', label: 'Download first' } : null,
          it.status === 'paused' ? { act: 'resume', icon: 'play_arrow', label: 'Resume' } : it.status !== 'finished' ? { act: 'pause', icon: 'pause', label: 'Pause' } : null,
          it.failed ? { act: 'retry', icon: 'refresh', label: 'Retry failed chapters' } : null,
          { act: 'rm', icon: 'close', label: it.status === 'finished' ? 'Remove from list' : 'Cancel download', danger: it.status !== 'finished' },
        ].filter(Boolean), onPick(a) {
          if (a === 'open') WNA.go('/story/' + id);
          if (a === 'first') { D.moveFirst(id); V.toast('Moved to the top'); }
          if (a === 'pause') D.pause(id);
          if (a === 'resume') D.resume(id);
          if (a === 'retry') D.retryFailed(id);
          if (a === 'rm') { D.remove(id); V.toast(it.status === 'finished' ? 'Removed' : 'Download cancelled'); }
        } });
      },
    });

    // drag to reorder within "Up next" (simple: drop above the hovered card moves it first among queued)
    let drag = null;
    el.addEventListener('pointerdown', (e) => { const h = e.target.closest('.drag'); if (!h) return; const c = h.closest('.qn'); drag = { id: c.dataset.id, el: c, y: e.clientY }; c.classList.add('dragging'); h.setPointerCapture(e.pointerId); e.preventDefault(); });
    el.addEventListener('pointermove', (e) => { if (!drag) return; drag.el.style.transform = `translateY(${e.clientY - drag.y}px)`; });
    el.addEventListener('pointerup', (e) => { if (!drag) return; const dy = e.clientY - drag.y; const id = drag.id; drag.el.style.transform = ''; drag.el.classList.remove('dragging'); drag = null; if (dy < -30) { D.moveFirst(id); V.toast('Moved up'); } });

    const off = D.on(() => { if (!drag) render(); });
    V.watchScroll(el);
    return { el, title: 'Downloads', destroy: off };
  };
})();
