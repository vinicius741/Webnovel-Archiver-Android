/* v6 — Secondary settings pages + Add Story. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  const S = W.settings;
  const P = (V6.settingsPages = {});
  const swRow = (k, l, sub, on) => `<div class="row" data-tg="${k}"><span class="rt"><b>${l}</b>${sub ? `<small>${sub}</small>` : ''}</span>${V6.sw(on)}</div>`;
  const toggles = (root, state, cb) => root.addEventListener('click', (e) => { const r = e.target.closest('[data-tg]'); if (!r) return; state[r.dataset.tg] = !state[r.dataset.tg]; r.querySelector('.sw').classList.toggle('on', state[r.dataset.tg]); cb && cb(r.dataset.tg, state[r.dataset.tg]); });

  // ---------- AI Settings ----------
  P.ai = ({ root }) => {
    const m = { ...W.ai.models }; const sp = W.ai.spend; let key = 'sk-or-v1-••••••••••••3f9a', usage = '$4.12 of $10.00';
    const model = (k, l) => `<button class="srow" data-m="${k}">${ic('neurology')}<span class="rt"><b>${l}</b><small class="mono">${esc(m[k])}</small></span><span class="tag">Reasoning: ${m.reasoning}</span></button>`;
    function draw() {
      root.innerHTML = `${V6.topbar('AI Settings', 'OpenRouter')}
        ${V6.sec('01', 'API key')}<div class="pad"><label class="fld"><span>OpenRouter API key</span><input type="password" value="${esc(key)}" data-key></label></div>
        ${V6.sec('02', 'Models')}${model('description', 'Description model')}${model('image', 'Cover image model')}${model('decision', 'Chapter selection model')}
        <div class="pad">${V6.slider('scan', 'Chapter scan limit', 12, 3, 40, 1, 'ch')}</div>
        ${V6.sec('03', 'AI spend')}
        <div class="stor three"><div><span class="num">$${sp.today.toFixed(2)}</span><small class="mono">Today</small></div><div><span class="num">$${sp.month.toFixed(2)}</span><small class="mono">This month</small></div><div><span class="num">$${sp.allTime.toFixed(2)}</span><small class="mono">All time</small></div></div>
        <div class="sh-sec">Recent requests</div>
        ${sp.recent.map((r) => `<div class="row">${ic(r.feature.startsWith('Cover') ? 'image' : 'notes')}<span class="rt"><b>${r.feature}</b><small class="mono">${r.model} · ${W.fmt.ago(r.at)} · ${W.fmt.int(r.tokens)} tok</small></span><em>$${r.cost.toFixed(3)}</em></div>`).join('')}
        <div class="row">${ic('key')}<span class="rt"><b>Current OpenRouter key usage</b><small data-usage>${usage}</small></span><button class="btn sm ghost" data-a="refresh">Refresh</button></div>
        <div class="sticky-go"><button class="btn acc wide" data-a="save">${ic('save')} Save</button></div>`;
    }
    root.addEventListener('click', (e) => {
      const mm = e.target.closest('[data-m]'); if (mm) { const k = mm.dataset.m; const opts = k === 'image' ? ['google/gemini-2.5-flash-image', 'openai/gpt-image-1', 'black-forest-labs/flux-1.1-pro'] : ['google/gemini-2.5-flash', 'openai/gpt-5-mini', 'anthropic/claude-haiku-4.5', 'deepseek/deepseek-chat']; return V6.sheet({ title: 'Choose model', items: opts.map((o) => ({ label: o, on: m[k] === o, run: () => { m[k] = o; draw(); } })) }); }
      const a = e.target.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'refresh') { a.innerHTML = ic('progress_activity', 'spin'); setTimeout(() => { usage = '$4.16 of $10.00 · just now'; draw(); }, 900); }
      if (a.dataset.a === 'save') { Object.assign(W.ai.models, m); key = root.querySelector('[data-key]').value; V6.toast('AI settings saved', { icon: 'save' }); }
    });
    root.addEventListener('input', (e) => { const r = e.target.closest('input[type=range]'); if (r) { r.closest('.sl').querySelector('[data-v]').textContent = r.value; r.style.setProperty('--f', (r.value - r.min) / (r.max - r.min)); } });
    draw();
  };

  // ---------- Data & Backup ----------
  P.data = ({ root }) => {
    const act = (icon, l, sub, k, cls = '') => `<button class="srow ${cls}" data-a="${k}">${ic(icon)}<span class="rt"><b>${l}</b>${sub ? `<small>${sub}</small>` : ''}</span>${ic('chevron_right')}</button>`;
    function draw() {
      root.innerHTML = `${V6.topbar('Data & Backup', '')}
        ${V6.sec('01', 'Backup')}
        ${act('upload', 'Export Backup', 'Library, tabs and settings as JSON', 'export')}${act('download', 'Import Backup', 'Merge a JSON backup into this library', 'import')}
        ${act('inventory_2', 'Create Full Backup', 'Everything, including chapters · ~412 MB', 'full')}${act('settings_backup_restore', 'Restore Full Backup', '', 'restore')}${act('healing', 'Recover Chapters from Backup', 'Re-adds chapters missing on this phone', 'recover')}
        ${V6.sec('02', 'Backup files')}
        ${S.backups.map((b, i) => `<section class="card"><div class="card-p row-c">${ic('folder_zip')}<span class="rt"><b>${esc(b.label)}</b><small class="mono dim">${b.size} · ${W.fmt.date(b.at)}</small></span><button class="ib" data-share="${i}" aria-label="Share">${ic('share')}</button><button class="ib" data-del="${i}" aria-label="Delete">${ic('delete')}</button></div></section>`).join('') || '<p class="pad dim">No backup files.</p>'}
        ${V6.sec('03', 'Sources & storage')}
        <div class="row">${ic('travel_explore')}<span class="rt"><b>Source access status</b><small class="acc-t">Ready • WebView 131 · cookies kept</small></span></div>
        ${act('description', 'Share Activity Logs', '', 'logs')}${act('lan', 'Share Source Access Logs', '', 'slogs')}${act('volunteer_activism', 'Patreon Account', 'Signed in · used for paid-member counts', 'patreon')}${act('restart_alt', 'Reset Source Web Session', 'Signs you out of every source site', 'reset')}
        ${V6.sec('!!', 'Danger zone')}
        ${act('delete_forever', 'Clear Local Storage', 'Deletes every novel, chapter and audio file', 'clear', 'danger')}`;
    }
    root.addEventListener('click', async (e) => {
      const sh = e.target.closest('[data-share]'); if (sh) return V6.toast('Sharing backup…', { icon: 'share' });
      const dl = e.target.closest('[data-del]'); if (dl) { if (await V6.dialog({ title: 'Delete backup file?', ok: 'Delete', danger: true })) { S.backups.splice(+dl.dataset.del, 1); draw(); } return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const k = a.dataset.a;
      const msgs = { export: 'Backup exported · wna-backup-2026-11-12.json', import: 'Choose a backup file…', recover: 'Recovered 14 chapters from backup', logs: 'Sharing activity logs…', slogs: 'Sharing source access logs…', patreon: 'Patreon account · signed in', restore: 'Choose a full backup…' };
      if (k === 'full') { a.querySelector('small').textContent = 'Creating… 0%'; let p = 0; const t = setInterval(() => { p += 17; if (p >= 100) { clearInterval(t); S.backups.unshift({ label: 'Full backup', size: '412 MB', at: Date.now() }); draw(); V6.toast('Full backup created', { icon: 'inventory_2' }); } else a.querySelector('small').textContent = `Creating… ${p}%`; }, 400); return; }
      if (k === 'reset') { if (await V6.dialog({ title: 'Reset source web session?', body: 'You will need to pass site checks again.', ok: 'Reset', danger: true })) V6.toast('Web session reset', { icon: 'restart_alt' }); return; }
      if (k === 'clear') { if (await V6.dialog({ title: 'Clear local storage?', body: 'This deletes all 282 novels from this phone. Make a full backup first.', ok: 'Delete everything', danger: true })) V6.toast('Not in the prototype — your library is safe', { icon: 'shield' }); return; }
      V6.toast(msgs[k] || 'Done', { icon: 'check' });
    });
    draw();
  };

  // ---------- Manage Tabs ----------
  P.tabs = ({ root }) => {
    const tabs = W.tabs();
    function draw() {
      root.innerHTML = `${V6.topbar('Manage Tabs', `${tabs.length} tabs`)}
        <p class="hint">Tabs group your library. The deck and the grid both follow them. Novels in a deleted tab become Unassigned.</p>
        <div class="pad row-c gap"><input class="inp" placeholder="New tab name" data-n><button class="btn acc" data-a="add">Add</button></div>
        ${tabs.map((t, i) => `<section class="card tabc"><div class="card-p row-c"><span class="num tab-n">${String(i + 1).padStart(2, '0')}</span><span class="rt"><b>${esc(t.name)}</b><small class="mono dim">${V6.stories().filter((s) => s.tab === t.id).length} novels</small></span>
          <button class="ib" data-up="${i}" ${i ? '' : 'disabled'} aria-label="Move up">${ic('arrow_upward')}</button><button class="ib" data-dn="${i}" ${i < tabs.length - 1 ? '' : 'disabled'} aria-label="Move down">${ic('arrow_downward')}</button><button class="ib" data-rn="${i}" aria-label="Rename">${ic('edit')}</button><button class="ib" data-rm="${i}" aria-label="Delete">${ic('delete')}</button></div></section>`).join('')}`;
    }
    root.addEventListener('click', async (e) => {
      const g = (k) => e.target.closest(`[data-${k}]`);
      if (g('a')) { const v = root.querySelector('[data-n]').value.trim(); if (!v) return V6.toast('Type a name first', { icon: 'edit' }); tabs.push({ id: 'tab_' + Date.now(), name: v }); draw(); return V6.toast(`Added “${esc(v)}”`, { icon: 'tab' }); }
      if (g('up')) { const i = +g('up').dataset.up; [tabs[i - 1], tabs[i]] = [tabs[i], tabs[i - 1]]; return draw(); }
      if (g('dn')) { const i = +g('dn').dataset.dn; [tabs[i + 1], tabs[i]] = [tabs[i], tabs[i + 1]]; return draw(); }
      if (g('rn')) { const i = +g('rn').dataset.rn; const v = await V6.dialog({ title: 'Rename tab', fields: [{ label: 'Name', value: tabs[i].name }], ok: 'Rename' }); if (v && v[0]) { tabs[i].name = v[0]; draw(); } return; }
      if (g('rm')) { const i = +g('rm').dataset.rm; if (await V6.dialog({ title: `Delete “${esc(tabs[i].name)}”?`, body: 'Its novels become Unassigned.', ok: 'Delete', danger: true })) { tabs.splice(i, 1); draw(); } }
    });
    draw();
  };

  // ---------- Text Cleanup ----------
  P.cleanup = ({ root }) => {
    const c = S.cleanup; let mode = 'sentences';
    function draw() {
      root.innerHTML = `${V6.topbar('Text Cleanup', 'Applies to read-aloud and downloaded text')}
        <div class="pad"><div class="seg wide"><button data-m="sentences" class="${mode === 'sentences' ? 'on' : ''}">Full sentences</button><button data-m="regex" class="${mode === 'regex' ? 'on' : ''}">Regex rules</button></div></div>
        ${mode === 'sentences' ? `<div class="pad"><label class="fld"><span>Sentence to remove</span><textarea rows="3" data-s placeholder="Paste a sentence that should never be read"></textarea></label><div class="row2"><button class="btn ghost" data-a="paste">${ic('content_paste')} Paste</button><button class="btn acc" data-a="add">Add</button></div></div>
          ${V6.sec(String(c.sentences.length), 'Saved sentences')}${c.sentences.map((s, i) => `<div class="row"><span class="rt"><small class="cl-s">${esc(s)}</small></span><button class="ib" data-rs="${i}" aria-label="Remove">${ic('close')}</button></div>`).join('')}`
        : `${c.regex.map((r, i) => `<section class="card"><div class="card-p"><div class="row-c"><span class="rt"><b>${esc(r.name)}</b><small class="${r.enabled ? 'acc-t' : 'dim'}">${r.enabled ? 'Enabled' : 'Disabled'} · Read aloud and downloaded text</small></span></div><code class="rx">/${esc(r.pattern)}/${r.flags}</code><div class="row3"><button class="btn sm ghost" data-ed="${i}">Edit</button><button class="btn sm ghost" data-tg="${i}">${r.enabled ? 'Disable' : 'Enable'}</button><button class="btn sm ghost" data-dr="${i}">Delete</button></div></div></section>`).join('')}
          <div class="pad row2"><button class="btn ghost" data-a="preset">${ic('library_add')} Add preset</button><button class="btn acc" data-a="new">${ic('add')} New rule</button></div>`}`;
    }
    root.addEventListener('click', async (e) => {
      const t = e.target;
      const m = t.closest('[data-m]'); if (m) { mode = m.dataset.m; return draw(); }
      const rs = t.closest('[data-rs]'); if (rs) { c.sentences.splice(+rs.dataset.rs, 1); return draw(); }
      const tg = t.closest('[data-tg]'); if (tg) { const r = c.regex[+tg.dataset.tg]; r.enabled = !r.enabled; return draw(); }
      const dr = t.closest('[data-dr]'); if (dr) { if (await V6.dialog({ title: 'Delete rule?', ok: 'Delete', danger: true })) { c.regex.splice(+dr.dataset.dr, 1); draw(); } return; }
      const ed = t.closest('[data-ed]'); if (ed) { const r = c.regex[+ed.dataset.ed]; const v = await V6.dialog({ title: 'Edit rule', fields: [{ label: 'Name', value: r.name }, { label: 'Pattern', value: r.pattern }, { label: 'Flags', value: r.flags }], ok: 'Save' }); if (v) { [r.name, r.pattern, r.flags] = v; draw(); } return; }
      const a = t.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'paste') root.querySelector('[data-s]').value = 'Enjoying the story? Read ahead on my Patreon!';
      if (a.dataset.a === 'add') { const v = root.querySelector('[data-s]').value.trim(); if (v) { c.sentences.unshift(v); draw(); V6.toast('Sentence added', { icon: 'cleaning_services' }); } }
      if (a.dataset.a === 'preset') V6.sheet({ title: 'Add preset', items: ['Remove “Read on Patreon” lines', 'Remove translator notes', 'Collapse repeated punctuation'].map((n) => ({ label: n, run: () => { c.regex.push({ name: n, pattern: n.includes('Patreon') ? '^.*patreon.*$' : n.includes('translator') ? '\\(TL: .*?\\)' : '([!?.])\\1{2,}', flags: 'gim', enabled: true }); draw(); } })) });
      if (a.dataset.a === 'new') { const v = await V6.dialog({ title: 'New rule', fields: [{ label: 'Name', placeholder: 'Remove author notes' }, { label: 'Pattern', placeholder: '^A/N:.*$' }, { label: 'Flags', value: 'gm' }], ok: 'Add' }); if (v && v[1]) { c.regex.push({ name: v[0] || 'New rule', pattern: v[1], flags: v[2], enabled: true }); draw(); } }
    });
    draw();
  };

  // ---------- Notifications ----------
  P.notifications = ({ root }) => {
    const st = { downloads: true, tts: true, narration: true, ai: true };
    root.innerHTML = `${V6.topbar('Notifications', '')}
      <section class="card"><div class="card-p row-c">${ic('verified', 'acc-t')}<span class="rt"><b>App permission</b><small class="acc-t">Allowed</small></span><button class="btn sm ghost" data-a="sys">System settings</button></div></section>
      ${V6.sec('01', 'Categories')}
      ${swRow('downloads', 'Downloads', 'Progress and finished downloads', st.downloads)}${swRow('tts', 'Text to Speech', 'Media controls while listening', st.tts)}${swRow('narration', 'Narration audio', 'Audio generation progress', st.narration)}${swRow('ai', 'AI Generation', 'Covers and descriptions ready', st.ai)}`;
    toggles(root, st, (k, v) => V6.toast(`${v ? 'Enabled' : 'Disabled'}`, { icon: v ? 'notifications_active' : 'notifications_off' }));
    root.querySelector('[data-a]').onclick = () => V6.toast('Opening Android settings…', { icon: 'settings' });
  };

  // ---------- Download Settings ----------
  P.downloads = ({ root }) => {
    const d = S.downloads;
    const ov = [['SpaceBattles', 1, '2000–5000 ms'], ['Sufficient Velocity', 1, '1500–4000 ms']];
    root.innerHTML = `${V6.topbar('Download Settings', '')}
      ${V6.sec('01', 'Pace')}
      <div class="pad">${V6.slider('parallel', 'Parallel sources', d.parallel, 1, 5, 1, '')}${V6.slider('delayMin', 'Delay min', d.delayMin, 0, 5000, 100, 'ms')}${V6.slider('delayMax', 'Delay max', d.delayMax, 0, 10000, 100, 'ms')}</div>
      ${V6.sec('02', 'EPUB')}<div class="pad">${V6.slider('maxPerEpub', 'Max chapters per EPUB', d.maxPerEpub, 25, 500, 25, 'ch')}</div>
      ${V6.sec('03', 'Per-source overrides')}
      ${ov.map(([n, p, del]) => `<button class="srow" data-ov="${n}">${ic('public')}<span class="rt"><b>${n}</b><small class="mono">${p} at a time · ${del}</small></span>${ic('edit')}</button>`).join('')}
      <button class="srow" data-ov="new">${ic('add')}<span class="rt"><b>Add override</b></span></button>`;
    root.addEventListener('input', (e) => { const r = e.target.closest('input[type=range]'); if (!r) return; d[r.dataset.k] = +r.value; r.closest('.sl').querySelector('[data-v]').textContent = r.value; r.style.setProperty('--f', (r.value - r.min) / (r.max - r.min)); });
    root.addEventListener('change', (e) => { if (e.target.matches('input[type=range]')) V6.toast('Saved', { icon: 'check', ms: 1200 }); });
    root.addEventListener('click', async (e) => { const o = e.target.closest('[data-ov]'); if (!o) return; const v = await V6.dialog({ title: o.dataset.ov === 'new' ? 'Add override' : o.dataset.ov, fields: [{ label: 'Source', value: o.dataset.ov === 'new' ? 'Scribble Hub' : o.dataset.ov }, { label: 'Parallel', value: '1', type: 'number' }, { label: 'Delay (ms)', value: '2000–5000' }], ok: 'Save' }); if (v) V6.toast('Override saved', { icon: 'check' }); });
  };

  // ---------- App Updates ----------
  P.update = ({ root }) => {
    const a = S.app; let checking = false;
    function draw() {
      root.innerHTML = `${V6.topbar('App Updates', '')}
        <div class="upd"><span class="kick">Installed version</span><span class="num upd-v">${a.version.replace('-native', '')}</span><span class="mono dim">native</span>
        <p class="${checking ? 'mut' : 'acc-t'}">${checking ? `${ic('progress_activity', 'spin')} Checking GitHub releases…` : `${ic('verified')} You have the latest version · Checked ${a.checkedAgo}`}</p>
        <button class="btn acc" data-a="check" ${checking ? 'disabled' : ''}>${ic('refresh')} Check for updates</button></div>
        ${swRow('autoCheck', 'Check automatically', 'Once a day, on Wi-Fi', a.autoCheck)}`;
    }
    root.addEventListener('click', (e) => { if (e.target.closest('[data-a="check"]')) { checking = true; draw(); setTimeout(() => { checking = false; a.checkedAgo = 'just now'; draw(); }, 1500); } });
    toggles(root, a);
    draw();
  };

  // ---------- Add Story ----------
  V6.screens.add = (ctx) => {
    const { root, route } = ctx;
    let tab = V6.state.tab !== 'all' ? V6.state.tab : (W.tabs()[0] || {}).id;
    let busy = false;
    root.innerHTML = `${V6.topbar('Add Story', 'Paste a story URL to import')}
      <div class="add">
        <h1 class="add-h">Paste<br>a link<span class="acc-t">.</span></h1>
        <div class="add-in"><input class="inp" type="url" inputmode="url" placeholder="Story URL" data-u value="${esc(route.query.url || '')}"><button class="btn ghost" data-a="paste" aria-label="Paste">${ic('content_paste')}</button></div>
        <button class="srow" data-a="tab">${ic('tab')}<span class="rt"><b>Save to tab</b><small data-tab></small></span>${ic('unfold_more')}</button>
        <button class="btn acc wide" data-a="fetch">${ic('cloud_download')} Fetch story</button>
        <div class="add-st" data-st hidden></div>
      </div>
      ${V6.sec('→', 'Or browse a source')}
      ${W.sources.map((s) => `<button class="srow" data-src="${s.host}">${ic('public')}<span class="rt"><b>${s.name}</b><small class="mono">${s.host}</small></span><span class="kick">Open ${ic('arrow_forward')}</span></button>`).join('')}`;
    const tabName = () => (W.tabs().find((t) => t.id === tab) || { name: 'Unassigned' }).name;
    root.querySelector('[data-tab]').textContent = tabName();
    root.addEventListener('click', (e) => {
      const src = e.target.closest('[data-src]'); if (src) return V6.toast(`Opening ${src.dataset.src} in the in-app browser`, { icon: 'public' });
      const a = e.target.closest('[data-a]'); if (!a || busy) return;
      const u = root.querySelector('[data-u]');
      if (a.dataset.a === 'paste') { u.value = 'https://www.royalroad.com/fiction/164525'; u.focus(); }
      if (a.dataset.a === 'tab') V6.sheet({ title: 'Save to tab', items: [{ label: 'Unassigned', on: !tab, run: () => { tab = null; root.querySelector('[data-tab]').textContent = tabName(); } }, ...W.tabs().map((t) => ({ label: t.name, on: t.id === tab, run: () => { tab = t.id; root.querySelector('[data-tab]').textContent = tabName(); } }))] });
      if (a.dataset.a === 'fetch') {
        const url = u.value.trim();
        if (!/^https?:\/\/\S+\.\S+/.test(url)) { u.classList.add('err'); setTimeout(() => u.classList.remove('err'), 600); return V6.toast('Paste a story URL first', { icon: 'link_off' }); }
        const m = url.match(/fiction\/(\d+)/); const id = m && W.story('rr_' + m[1]) ? 'rr_' + m[1] : 'rr_164525';
        const s = W.story(id) || W.stories()[0];
        busy = true; a.disabled = true; a.innerHTML = `${ic('progress_activity', 'spin')} Fetching…`;
        const st = root.querySelector('[data-st]'); st.hidden = false;
        const steps = ['Starting…', 'Opening the story page…', `Found “${s.title}”`, `Reading ${s.total} chapters…`, 'Saving the cover…', 'Done'];
        steps.forEach((m2, i) => setTimeout(() => { st.innerHTML = `<div class="add-steps">${steps.slice(0, i + 1).map((x, j) => `<div class="${j === i ? 'cur' : 'done'}">${ic(j === i && i < steps.length - 1 ? 'progress_activity' : 'check', j === i && i < steps.length - 1 ? 'spin' : '')}${esc(x)}</div>`).join('')}</div>${V6.bar((i + 1) / steps.length, 'thick')}`; }, i * 650));
        setTimeout(() => { if (tab) s.tab = tab; V6.toast(`Added to ${esc(tabName())}`, { icon: 'library_add_check' }); W.go('/story/' + s.id, { replace: true }); }, steps.length * 650 + 200);
      }
    });
  };
})();
