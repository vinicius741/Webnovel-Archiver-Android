/* v6 — gesture helpers: long-press, drag-to-dismiss, horizontal swipe on list items, scrubbing. */
(() => {
  const V6 = window.V6;

  // Delegated long-press (pointer based, ~450 ms). Suppresses the click that follows.
  V6.longPress = (root, selector, fn, ms = 450) => {
    let t = null, sx = 0, sy = 0, target = null, fired = false;
    root.addEventListener('pointerdown', (e) => {
      const el = e.target.closest(selector); if (!el || !root.contains(el)) return;
      target = el; sx = e.clientX; sy = e.clientY; fired = false;
      el.classList.add('pressing');
      t = setTimeout(() => { fired = true; el.classList.remove('pressing'); navigator.vibrate && navigator.vibrate(12); fn(el, e); }, ms);
    });
    const cancel = () => { clearTimeout(t); t = null; if (target) target.classList.remove('pressing'); };
    root.addEventListener('pointermove', (e) => { if (t && Math.hypot(e.clientX - sx, e.clientY - sy) > 9) cancel(); });
    root.addEventListener('pointerup', cancel); root.addEventListener('pointercancel', cancel);
    root.addEventListener('contextmenu', (e) => { if (e.target.closest(selector)) e.preventDefault(); });
    root.addEventListener('click', (e) => { if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
  };

  // Drag a handle down to dismiss an element (sheets, the player).
  V6.dragDismiss = (handle, target, onDismiss, { threshold = 110, area = null } = {}) => {
    if (!handle) return;
    let y0 = null, dy = 0, t0 = 0;
    const start = (e) => {
      if (area && area.scrollTop > 0) return;
      y0 = e.clientY; dy = 0; t0 = performance.now(); target.style.transition = 'none';
      handle.setPointerCapture && handle.setPointerCapture(e.pointerId);
    };
    handle.addEventListener('pointerdown', start);
    handle.addEventListener('pointermove', (e) => {
      if (y0 == null) return; dy = Math.max(0, e.clientY - y0);
      target.style.transform = `translateY(${dy}px)`;
      target.style.opacity = String(1 - Math.min(0.4, dy / 900));
    });
    const end = () => {
      if (y0 == null) return; y0 = null;
      const v = dy / Math.max(1, performance.now() - t0);
      target.style.transition = '';
      if (dy > threshold || (v > 0.7 && dy > 30)) { target.style.transform = 'translateY(110%)'; onDismiss(); }
      else { target.style.transform = ''; target.style.opacity = ''; }
    };
    handle.addEventListener('pointerup', end); handle.addEventListener('pointercancel', end);
  };

  // Horizontal swipe on list rows: reveal left/right action, commit past threshold.
  // opts: { left: {label, icon, cls, run(el)}, right: {...} }
  V6.swipeRows = (root, selector, getOpts) => {
    let el = null, x0 = 0, y0 = 0, dx = 0, mode = null, opts = null;
    root.addEventListener('pointerdown', (e) => {
      if (e.target.closest('input,textarea,.drag,.qi-chs')) return;
      const r = e.target.closest(selector); if (!r) return;
      el = r; x0 = e.clientX; y0 = e.clientY; dx = 0; mode = null; opts = getOpts(r);
    });
    root.addEventListener('pointermove', (e) => {
      if (!el) return;
      const mx = e.clientX - x0, my = e.clientY - y0;
      if (!mode) { if (Math.abs(mx) > 10 && Math.abs(mx) > Math.abs(my) * 1.4) { mode = 'x'; el.setPointerCapture && el.setPointerCapture(e.pointerId); el.classList.add('swiping'); } else if (Math.abs(my) > 10) { el = null; return; } }
      if (mode !== 'x') return;
      dx = mx;
      const side = dx > 0 ? opts.right : opts.left;
      if (!side) dx = dx * 0.15;
      const card = el.querySelector('.sw-card') || el;
      card.style.transform = `translateX(${dx}px)`;
      el.dataset.side = dx > 0 ? 'right' : 'left';
      el.classList.toggle('armed', Math.abs(dx) > 96 && !!side);
      const lab = el.querySelector('.sw-under');
      if (lab && side) lab.innerHTML = `<span class="sw-u ${side.cls || ''} ${dx > 0 ? 'l' : 'r'}">${V6.ic(side.icon)}${side.label}</span>`;
    });
    const end = () => {
      if (!el) return;
      const card = el.querySelector('.sw-card') || el;
      const side = dx > 0 ? opts.right : opts.left;
      const r = el; el = null;
      r.classList.remove('swiping', 'armed');
      if (mode === 'x' && side && Math.abs(dx) > 96) {
        card.style.transition = 'transform .22s ease'; card.style.transform = `translateX(${dx > 0 ? 110 : -110}%)`;
        setTimeout(() => { side.run(r); card.style.transition = 'none'; card.style.transform = ''; requestAnimationFrame(() => (card.style.transition = '')); }, 200);
        r.addEventListener('click', (ev) => ev.stopPropagation(), { capture: true, once: true });
      } else {
        card.style.transition = 'transform .25s cubic-bezier(.2,1.4,.4,1)'; card.style.transform = '';
        setTimeout(() => (card.style.transition = ''), 260);
        if (mode === 'x') r.addEventListener('click', (ev) => ev.stopPropagation(), { capture: true, once: true });
      }
      mode = null;
    };
    root.addEventListener('pointerup', end); root.addEventListener('pointercancel', end);
  };

  // Scrub a track: calls onMove(fraction) while dragging and onEnd(fraction).
  V6.scrub = (track, onMove, onEnd, axis = 'x') => {
    let on = false;
    const f = (e) => { const r = track.getBoundingClientRect(); return axis === 'x' ? Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) : Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)); };
    track.addEventListener('pointerdown', (e) => { on = true; track.setPointerCapture(e.pointerId); track.classList.add('scrubbing'); onMove(f(e)); e.preventDefault(); e.stopPropagation(); });
    track.addEventListener('pointermove', (e) => { if (on) onMove(f(e)); });
    const end = (e) => { if (!on) return; on = false; track.classList.remove('scrubbing'); onEnd && onEnd(f(e)); };
    track.addEventListener('pointerup', end); track.addEventListener('pointercancel', end);
    track.addEventListener('click', (e) => e.stopPropagation());
  };
})();
