/*
 * Prototype overlay: a small edge handle that opens a sheet with the version switcher (keeps the current screen)
 * and a feedback form that saves notes to the LAN server (POST /api/feedback). Rendered in a Shadow DOM so no
 * version's CSS can affect it, and it never affects theirs.
 */
(function () {
  const VERS = window.WNA_VERSIONS || [];
  const ROOT = (window.WNA && window.WNA.base ? window.WNA.base : '../shared/').replace(/shared\/$/, '');
  const here = location.pathname.split('/').filter(Boolean);
  const curId = here.find((p) => VERS.some((v) => v.id === p)) || '';
  const cur = VERS.find((v) => v.id === curId) || { id: curId, short: '?', name: curId };
  const screenKey = () => { const r = window.WNA ? window.WNA.route() : { name: 'unknown', path: location.hash }; return { name: r.name, path: r.path }; };

  const host = document.createElement('div');
  host.id = 'wna-proto-overlay';
  host.style.cssText = 'position:fixed;inset:auto;z-index:2147483000;';
  document.body.appendChild(host);
  const sh = host.attachShadow({ mode: 'open' });
  let handleY = 0.42;
  try { handleY = parseFloat(localStorage.getItem('wnaHandleY')) || 0.42; } catch (e) { /* ignore */ }

  sh.innerHTML = `
  <style>
    :host{all:initial}
    *{box-sizing:border-box;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
    .handle{position:fixed;left:0;width:20px;height:58px;border-radius:0 12px 12px 0;background:rgba(20,20,24,.72);
      backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);border:1px solid rgba(255,255,255,.18);border-left:none;color:#fff;
      font:700 10px/1 system-ui;display:flex;align-items:center;justify-content:center;writing-mode:vertical-rl;letter-spacing:.06em;
      touch-action:none;cursor:pointer;user-select:none;-webkit-user-select:none;box-shadow:0 4px 18px rgba(0,0,0,.45)}
    .scrim{position:fixed;inset:0;background:rgba(0,0,0,.55);opacity:0;pointer-events:none;transition:opacity .2s}
    .scrim.open{opacity:1;pointer-events:auto}
    .sheet{position:fixed;left:50%;bottom:0;width:min(560px,100vw);transform:translate(-50%,105%);transition:transform .25s cubic-bezier(.2,.8,.2,1);
      background:#16161a;color:#f1f1f3;border-radius:20px 20px 0 0;border:1px solid rgba(255,255,255,.1);border-bottom:none;
      max-height:88vh;overflow:auto;padding:10px 16px calc(16px + env(safe-area-inset-bottom));box-shadow:0 -10px 40px rgba(0,0,0,.6)}
    .sheet.open{transform:translate(-50%,0)}
    .grab{width:40px;height:4px;border-radius:2px;background:#555;margin:2px auto 10px}
    h3{margin:0 0 2px;font-size:15px}
    .muted{color:#9a9aa3;font-size:12px}
    .row{display:flex;gap:8px;align-items:center}
    .vers{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:8px;margin:12px 0}
    .v{border:1px solid #2c2c33;background:#1e1e24;color:#eee;border-radius:12px;padding:8px 10px;text-align:left;font-size:12px;cursor:pointer}
    .v b{display:block;font-size:13px}
    .v.cur{border-color:#8ab4ff;background:#1d2840}
    .nav{display:flex;gap:8px;margin-bottom:6px}
    .btn{flex:1;border:none;border-radius:12px;padding:11px 12px;font-size:14px;font-weight:600;background:#2a2a31;color:#eee;cursor:pointer}
    .btn.pri{background:#8ab4ff;color:#0b1730}
    .sec{margin-top:14px;padding-top:12px;border-top:1px solid #26262c}
    .rates{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}
    .chip{border:1px solid #33333b;background:#1e1e24;color:#ddd;border-radius:999px;padding:7px 12px;font-size:13px;cursor:pointer}
    .chip.on{background:#f1f1f3;color:#111;border-color:#f1f1f3}
    textarea{width:100%;min-height:84px;border-radius:12px;border:1px solid #33333b;background:#0f0f12;color:#eee;padding:10px;font-size:14px;resize:vertical}
    .scope{display:flex;gap:6px;margin:8px 0}
    .notes{margin-top:10px;display:flex;flex-direction:column;gap:6px}
    .note{background:#1e1e24;border-radius:10px;padding:8px 10px;font-size:13px}
    .note .muted{font-size:11px}
    .ok{color:#7ddc9a;font-size:13px;margin-top:6px;min-height:16px}
    a{color:#8ab4ff}
  </style>
  <div class="handle" part="handle">${cur.short || 'v?'}</div>
  <div class="scrim"></div>
  <div class="sheet" role="dialog" aria-label="Prototype controls">
    <div class="grab"></div>
    <div class="row" style="justify-content:space-between">
      <div><h3>${cur.short} · ${cur.name}</h3><div class="muted screen"></div></div>
      <a href="${ROOT}index.html" class="muted">All versions</a>
    </div>
    <div class="vers"></div>
    <div class="nav"><button class="btn prev">◀ Prev version</button><button class="btn next">Next version ▶</button></div>
    <div class="sec">
      <h3>Feedback</h3>
      <div class="muted">Saved on the computer for Claude to read. Notes are tied to this version and screen.</div>
      <div class="scope"><button class="chip on" data-scope="screen">This screen</button><button class="chip" data-scope="version">Whole version</button></div>
      <div class="rates">
        <button class="chip" data-r="love">❤️ Love</button><button class="chip" data-r="like">👍 Like</button>
        <button class="chip" data-r="meh">😐 Meh</button><button class="chip" data-r="dislike">👎 Dislike</button>
      </div>
      <textarea placeholder="What works, what doesn't, what to steal for the final design…"></textarea>
      <div class="nav" style="margin-top:8px"><button class="btn pri send">Save note</button></div>
      <div class="ok"></div>
      <div class="notes"></div>
    </div>
  </div>`;

  const $ = (s) => sh.querySelector(s);
  const handle = $('.handle'), sheet = $('.sheet'), scrim = $('.scrim');
  const place = () => { handle.style.top = `calc(${(handleY * 100).toFixed(1)}vh - 29px)`; };
  place();
  let rating = null, scope = 'screen';

  const idx = VERS.findIndex((v) => v.id === curId);
  const goVersion = (v) => { location.href = `${ROOT}${v.id}/index.html${location.hash}`; };
  $('.vers').innerHTML = VERS.map((v) => `<button class="v ${v.id === curId ? 'cur' : ''}" data-id="${v.id}"><b>${v.short}</b>${v.name}</button>`).join('');
  $('.vers').addEventListener('click', (e) => { const b = e.target.closest('.v'); if (b) goVersion(VERS.find((v) => v.id === b.dataset.id)); });
  $('.prev').onclick = () => goVersion(VERS[(idx - 1 + VERS.length) % VERS.length]);
  $('.next').onclick = () => goVersion(VERS[(idx + 1) % VERS.length]);
  sh.querySelectorAll('[data-scope]').forEach((b) => (b.onclick = () => { scope = b.dataset.scope; sh.querySelectorAll('[data-scope]').forEach((x) => x.classList.toggle('on', x === b)); loadNotes(); }));
  sh.querySelectorAll('[data-r]').forEach((b) => (b.onclick = () => { rating = rating === b.dataset.r ? null : b.dataset.r; sh.querySelectorAll('[data-r]').forEach((x) => x.classList.toggle('on', x.dataset.r === rating)); }));

  function open() { $('.screen').textContent = 'Screen: ' + screenKey().name + '  (' + (screenKey().path || '/') + ')'; sheet.classList.add('open'); scrim.classList.add('open'); loadNotes(); }
  function close() { sheet.classList.remove('open'); scrim.classList.remove('open'); }
  scrim.onclick = close;

  // Tap opens; vertical drag repositions the handle.
  let drag = null;
  handle.addEventListener('pointerdown', (e) => { drag = { y: e.clientY, moved: false }; handle.setPointerCapture(e.pointerId); });
  handle.addEventListener('pointermove', (e) => { if (!drag) return; if (Math.abs(e.clientY - drag.y) > 6) drag.moved = true; if (drag.moved) { handleY = Math.min(0.92, Math.max(0.08, e.clientY / innerHeight)); place(); } });
  handle.addEventListener('pointerup', () => { if (drag && !drag.moved) open(); else try { localStorage.setItem('wnaHandleY', handleY); } catch (e) { /* ignore */ } drag = null; });

  async function loadNotes() {
    const box = $('.notes');
    try {
      const res = await fetch(`${ROOT}api/feedback?version=${encodeURIComponent(curId)}`, { cache: 'no-store' });
      const all = (await res.json()).filter((n) => scope === 'version' ? n.scope === 'version' : n.screen === screenKey().name);
      box.innerHTML = all.length ? all.slice(-6).reverse().map((n) => `<div class="note">${n.rating ? `<b>${n.rating}</b> · ` : ''}${(n.note || '').replace(/[<&]/g, (c) => (c === '<' ? '&lt;' : '&amp;'))}<div class="muted">${new Date(n.at).toLocaleString()}</div></div>`).join('') : '<div class="muted">No notes here yet.</div>';
    } catch (e) { box.innerHTML = '<div class="muted">Feedback server not reachable (open this through server.py).</div>'; }
  }
  $('.send').onclick = async () => {
    const note = $('textarea').value.trim();
    if (!note && !rating) { $('.ok').textContent = 'Write a note or pick a rating first.'; return; }
    const body = { version: curId, scope, screen: scope === 'screen' ? screenKey().name : null, path: screenKey().path, rating, note, ua: navigator.userAgent, viewport: `${innerWidth}x${innerHeight}` };
    try {
      const res = await fetch(`${ROOT}api/feedback`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (!res.ok) throw new Error(res.status);
      $('textarea').value = ''; rating = null; sh.querySelectorAll('[data-r]').forEach((x) => x.classList.remove('on'));
      $('.ok').textContent = 'Saved ✓'; setTimeout(() => ($('.ok').textContent = ''), 2500); loadNotes();
    } catch (e) { $('.ok').textContent = 'Could not save (is server.py running?)'; }
  };
})();
