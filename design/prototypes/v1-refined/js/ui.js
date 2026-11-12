/* v1 Refined — overlays: bottom sheets, menus, dialogs, snackbars. */
(function () {
  const V = window.V1;
  const { esc } = WNA;
  const chrome = () => document.getElementById('chrome');
  const overlays = []; // stack of {close}

  V.closeOverlays = () => overlays.slice().reverse().forEach((o) => o.close());
  V.hasOverlay = () => overlays.length > 0;

  function mountScrim(onClose) {
    const scrim = WNA.el('<div class="scrim"></div>');
    scrim.addEventListener('click', onClose);
    chrome().appendChild(scrim);
    return scrim;
  }

  // ---------- sheet ----------
  V.sheet = ({ title = '', sub = '', body = '', footer = '', bodyPad = false, onMount, onClose } = {}) => {
    let closed = false;
    const scrim = mountScrim(() => close());
    const el = WNA.el(`
      <div class="sheet" role="dialog" aria-modal="true" aria-label="${esc(title)}">
        <div class="grab"></div>
        ${title ? `<div class="sh-h"><h3>${esc(title)}</h3>${sub ? `<p>${esc(sub)}</p>` : ''}</div>` : ''}
        <div class="sh-b ${bodyPad ? 'pad' : ''}">${body}</div>
        ${footer ? `<div class="sh-f">${footer}</div>` : ''}
      </div>`);
    chrome().appendChild(el);
    const rec = { close };
    overlays.push(rec);
    // drag the grab handle down to dismiss
    let y0 = null;
    const grab = el.querySelector('.grab');
    const hdr = el.querySelector('.sh-h');
    [grab, hdr].filter(Boolean).forEach((h) => {
      h.addEventListener('pointerdown', (e) => { y0 = e.clientY; el.style.transition = 'none'; h.setPointerCapture(e.pointerId); });
      h.addEventListener('pointermove', (e) => { if (y0 == null) return; const dy = Math.max(0, e.clientY - y0); el.style.transform = `translateY(${dy}px)`; });
      h.addEventListener('pointerup', (e) => { if (y0 == null) return; const dy = e.clientY - y0; y0 = null; el.style.transition = 'transform 200ms var(--ease)'; if (dy > 90) close(); else el.style.transform = ''; });
    });
    function close(result) {
      if (closed) return; closed = true;
      overlays.splice(overlays.indexOf(rec), 1);
      el.style.transform = ''; el.style.transition = '';
      el.classList.add('out'); scrim.classList.add('out');
      setTimeout(() => { el.remove(); scrim.remove(); }, 230);
      if (onClose) onClose(result);
    }
    if (onMount) onMount(el, close);
    return close;
  };

  // ---------- menu sheet ----------
  // groups: [{label, items:[{act, icon, label, sub, danger, on, trailing, disabled}]}]
  V.menu = ({ title = '', sub = '', groups = [], items = null, onPick }) => {
    if (items) groups = [{ items }];
    const body = groups.map((g) => `
      ${g.label ? `<div class="grp">${esc(g.label)}</div>` : ''}
      ${g.items.map((it) => `
        <button class="mi ${it.danger ? 'danger' : ''} ${it.on ? 'on' : ''}" data-pick="${esc(it.act)}" ${it.disabled ? 'disabled' : ''}>
          ${it.icon ? V.ic(it.icon) : ''}
          <span class="grow">${esc(it.label)}${it.sub ? `<span class="mi-s">${esc(it.sub)}</span>` : ''}</span>
          ${it.on ? V.ic('check', 'chk') : it.trailing ? `<span class="tr">${esc(it.trailing)}</span>` : ''}
        </button>`).join('')}`).join('');
    return V.sheet({
      title, sub, body,
      onMount(el, close) {
        WNA.on(el, 'click', '[data-pick]', (e, t) => { const a = t.dataset.pick; close(); setTimeout(() => onPick && onPick(a), 60); });
      },
    });
  };

  // ---------- dialog ----------
  // actions: [{label, value, kind}] — resolves with {value, form}
  V.dialog = ({ icon = '', title = '', body = '', html = '', actions = [{ label: 'OK', value: true, kind: 'text' }] } = {}) =>
    new Promise((resolve) => {
      let closed = false;
      const scrim = mountScrim(() => close(null));
      const el = WNA.el(`
        <div class="dialog" role="alertdialog" aria-modal="true">
          ${icon ? `<div class="dic">${V.ic(icon)}</div>` : ''}
          <h3>${esc(title)}</h3>
          ${body ? `<div class="db">${esc(body)}</div>` : ''}
          ${html ? `<div class="db">${html}</div>` : ''}
          <div class="df">${actions.map((a, i) => `<button class="btn ${a.kind || 'text'}" data-i="${i}">${esc(a.label)}</button>`).join('')}</div>
        </div>`);
      chrome().appendChild(el);
      const rec = { close: () => close(null) };
      overlays.push(rec);
      const first = el.querySelector('input,textarea');
      if (first) setTimeout(() => first.focus(), 260);
      WNA.on(el, 'click', '[data-i]', (e, t) => close(actions[+t.dataset.i].value));
      el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') { const p = actions.find((a) => a.kind === 'filled') || actions[actions.length - 1]; close(p.value); } });
      function close(value) {
        if (closed) return; closed = true;
        overlays.splice(overlays.indexOf(rec), 1);
        const form = {};
        el.querySelectorAll('[name]').forEach((f) => (form[f.name] = f.value));
        el.classList.add('out'); scrim.classList.add('out');
        setTimeout(() => { el.remove(); scrim.remove(); }, 200);
        resolve({ value, form });
      }
    });

  V.confirm = async (title, body, ok = 'Delete', { danger = true, icon = '' } = {}) => {
    const r = await V.dialog({ icon, title, body, actions: [{ label: 'Cancel', value: false }, { label: ok, value: true, kind: danger ? 'danger' : 'filled' }] });
    return r.value === true;
  };

  V.field = (name, label, value = '', { placeholder = '', type = 'text', multiline = false, help = '' } = {}) => `
    <label class="field"><span class="lab">${esc(label)}</span>
      <span class="input ${multiline ? 'ta' : ''}">${multiline ? `<textarea name="${name}" rows="3" placeholder="${esc(placeholder)}">${esc(value)}</textarea>` : `<input name="${name}" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder)}" autocomplete="off">`}</span>
      ${help ? `<span class="help">${esc(help)}</span>` : ''}
    </label>`;

  // ---------- snackbar ----------
  let snackEl = null, snackT = 0;
  V.toast = (msg, { action = '', onAction = null, ms = 3200 } = {}) => {
    if (snackEl) { const old = snackEl; old.classList.add('out'); setTimeout(() => old.remove(), 200); }
    clearTimeout(snackT);
    const el = WNA.el(`<div class="snack" role="status"><span class="grow">${esc(msg)}</span>${action ? `<button class="btn text">${esc(action)}</button>` : ''}</div>`);
    if (action) el.querySelector('button').addEventListener('click', () => { hide(); onAction && onAction(); });
    chrome().appendChild(el);
    snackEl = el;
    function hide() { if (snackEl !== el) return; el.classList.add('out'); setTimeout(() => el.remove(), 220); snackEl = null; }
    snackT = setTimeout(hide, ms);
  };

  // simple controls
  V.sw = (on, act, data = '') => `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${on}" data-act="${act}" ${data}></button>`;
  V.slider = (name, min, max, step, val) => {
    const p = ((val - min) / (max - min)) * 100;
    return `<input class="slider" type="range" name="${name}" min="${min}" max="${max}" step="${step}" value="${val}" style="--p:${p}%">`;
  };
  V.syncSlider = (inp) => { const p = ((inp.value - inp.min) / (inp.max - inp.min)) * 100; inp.style.setProperty('--p', p + '%'); };
})();
