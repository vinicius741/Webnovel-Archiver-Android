/* v5-feed secondary settings: AI, Data & Backup, Manage Tabs, Text Cleanup, Notifications, Downloads, App Updates. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc, fmt = W.fmt;
  const S = W.settings;
  function page(ctx, title, sub, inner, back = '/settings') {
    A.tint(ctx.el, W.session ? W.story(W.session.storyId) : null);
    const old = ctx.el.querySelector('.sc'), y = old ? old.scrollTop : 0;
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title, sub, back })}<div class="set-wrap rise">${inner}</div></div>`;
    ctx.el.querySelector('.sc').scrollTop = y;
    if (y) ctx.el.querySelector('.set-wrap').classList.remove('rise');
    A.bindTopbar(ctx);
    return ctx.el.querySelector('.set-wrap');
  }

  // ---------- AI settings ----------
  A.screens.settings_ai = (ctx) => {
    const m = { ...W.ai.models }, sp = W.ai.spend;
    let scan = 12;
    const models = [['description', 'Description model', 'Writes descriptions and image prompts'], ['image', 'Cover image model', 'Paints new covers'], ['decision', 'Chapter selection model', 'Picks the chapters the AI reads']];
    const draw = () => page(ctx, 'AI Settings', 'OpenRouter models and spend', `
      <section class="set-sec"><h4>OpenRouter</h4><div class="set-card"><label class="field"><span>API key</span><input type="password" value="sk-or-v1-••••••••••••••••3f9a" autocomplete="off"></label><p class="faint" style="font-size:12.5px">Stored on this phone only.</p></div></section>
      <section class="set-sec"><h4>Models</h4><div class="group">${models.map(([k, l, sub]) => `<button class="row" data-model="${k}">${A.ic(k === 'image' ? 'image' : k === 'decision' ? 'checklist' : 'notes')}<span class="rt"><b>${l}</b><small>${esc(m[k])} · ${sub}</small></span><span class="pill">${A.ic('psychology')}${esc(m.reasoning)}</span></button>`).join('')}</div></section>
      <section class="set-sec"><h4>Chapter scan limit</h4><div class="set-card"><div><div class="set-lbl"><b>Chapters scanned when choosing context</b><span class="v">${scan}</span></div>${A.range(scan, 3, 40, 1, 'data-scan')}</div></div></section>
      <section class="set-sec"><h4>AI spend</h4><div class="set-card"><div class="spend"><div><small>Today</small><b>$${sp.today.toFixed(2)}</b></div><div><small>This month</small><b>$${sp.month.toFixed(2)}</b></div><div><small>All time</small><b>$${sp.allTime.toFixed(2)}</b></div></div>
        <div><p class="faint" style="font:700 11px/1 var(--display);letter-spacing:.1em;text-transform:uppercase;margin:6px 0 4px">Recent requests</p>${sp.recent.map((r) => `<div class="set-row"><span><b>${esc(r.feature)}</b><small>${esc(r.model)} · ${fmt.ago(r.at)} · ${fmt.num(r.tokens)} tokens</small></span><b>$${r.cost.toFixed(3)}</b></div>`).join('')}</div>
        <div class="set-row"><span><b>Current OpenRouter key usage</b><small class="keyuse">$6.37 of $20.00 limit · checked 2 h ago</small></span><button class="btn glassy sm" data-refresh>${A.ic('refresh')}Refresh</button></div></div></section>
      <div class="savebar"><button class="btn primary" data-save>${A.ic('check')}Save</button></div>`);
    draw();
    ctx.el.addEventListener('input', (e) => { if (e.target.matches('[data-scan]')) { scan = +e.target.value; ctx.el.querySelector('.set-lbl .v').textContent = scan; } });
    ctx.el.addEventListener('click', (e) => {
      const mo = e.target.closest('[data-model]');
      if (mo) { const k = mo.dataset.model; const opts = k === 'image' ? ['google/gemini-2.5-flash-image', 'openai/gpt-image-1', 'black-forest-labs/flux-1.1-pro'] : ['google/gemini-2.5-flash', 'openai/gpt-5-mini', 'anthropic/claude-haiku-4.5', 'deepseek/deepseek-chat']; return A.menu({ title: 'Choose model', items: [...opts.map((o) => ({ icon: 'smart_toy', label: o, on: m[k] === o, fn: () => { m[k] = o; draw(); } })), { section: 'Reasoning' }, ...['Default', 'Low', 'High', 'Off'].map((r) => ({ icon: 'psychology', label: r, on: m.reasoning === r, fn: () => { m.reasoning = r; draw(); } }))] }); }
      if (e.target.closest('[data-refresh]')) { const el = ctx.el.querySelector('.keyuse'); el.textContent = 'Checking…'; setTimeout(() => { el.textContent = '$6.37 of $20.00 limit · checked just now'; }, 1100); return; }
      if (e.target.closest('[data-save]')) { Object.assign(W.ai.models, m); A.toast('AI settings saved', { icon: 'check' }); }
    });
  };

  // ---------- Data & Backup ----------
  A.screens.settings_data = (ctx) => {
    const act = (k, icon, label, sub) => `<button class="row" data-act="${k}">${A.ic(icon)}<span class="rt"><b>${label}</b><small>${sub}</small></span>${A.ic('chevron_right', 'chev')}</button>`;
    const draw = () => page(ctx, 'Data & Backup', 'Keep your library safe', `
      <section class="set-sec"><h4>Backup</h4><div class="group">${act('export', 'upload', 'Export Backup', 'Library, tabs and settings as JSON')}${act('import', 'download', 'Import Backup', 'Merge a JSON backup into this library')}${act('full', 'inventory_2', 'Create Full Backup', 'Everything, including chapters and covers')}${act('restore', 'settings_backup_restore', 'Restore Full Backup', 'Replace this library with a full backup')}${act('recover', 'healing', 'Recover Chapters from Backup', 'Bring back chapters missing on this phone')}</div></section>
      <section class="set-sec"><h4>Backup files</h4>${S.backups.length ? `<div class="group">${S.backups.map((b, i) => `<div class="row">${A.ic('folder_zip')}<span class="rt"><b>${esc(b.label)}</b><small>${b.size} · ${fmt.date(b.at)}</small></span><button class="ibtn sm" data-share="${i}" aria-label="Share">${A.ic('ios_share')}</button><button class="ibtn sm" data-del="${i}" aria-label="Delete">${A.ic('delete')}</button></div>`).join('')}</div>` : '<p class="faint">No backup files yet.</p>'}</section>
      <section class="set-sec"><h4>Sources & storage</h4><div class="group"><div class="row">${A.ic('verified', 'f')}<span class="rt"><b>Source Access Status</b><small>Ready • WebView 131 • cookies saved for 4 sites</small></span></div>${act('logs', 'bug_report', 'Share Activity Logs', 'For troubleshooting')}${act('srclogs', 'travel_explore', 'Share Source Access Logs', 'Requests made to story sites')}${act('patreon', 'volunteer_activism', 'Patreon Account', 'Signed in · used for early-access chapters')}${act('reset', 'restart_alt', 'Reset Source Web Session', 'Clears site cookies and checks')}</div></section>
      <section class="set-sec"><h4>Danger zone</h4><div class="set-card" style="border-color:rgba(255,138,134,.25)"><div class="set-row"><span><b>Clear Local Storage</b><small>Deletes every novel, chapter and setting on this phone.</small></span><button class="btn danger sm" data-clear>Clear</button></div></div></section>`);
    draw();
    ctx.el.addEventListener('click', (e) => {
      const a = e.target.closest('[data-act]');
      if (a) {
        const k = a.dataset.act;
        if (k === 'full') { A.toast('Creating full backup…', { icon: 'inventory_2' }); setTimeout(() => { S.backups.unshift({ label: 'Full backup', size: '418 MB', at: Date.now() }); draw(); A.toast('Full backup created', { icon: 'check', action: 'Share', fn: () => A.toast('Share sheet opened') }); }, 2000); return; }
        if (k === 'restore') return A.confirm({ title: 'Restore full backup?', body: 'Your current library is replaced by the backup from ' + fmt.date(S.backups[0] ? S.backups[0].at : Date.now()) + '.', ok: 'Restore', danger: true }).then((ok) => ok && A.toast('Restoring… the app restarts when done', { icon: 'settings_backup_restore' }));
        if (k === 'reset') return A.confirm({ title: 'Reset source web session?', body: 'You may need to pass site checks again.', ok: 'Reset' }).then((ok) => ok && A.toast('Source session reset', { icon: 'restart_alt' }));
        const msg = { export: 'Backup exported to Downloads', import: 'Choose a backup file…', recover: 'Scanning backups for missing chapters…', logs: 'Share sheet: activity logs', srclogs: 'Share sheet: source access logs', patreon: 'Patreon: signed in' }[k];
        A.toast(msg, { icon: a.querySelector('.ms').textContent }); return;
      }
      const sh = e.target.closest('[data-share]'); if (sh) return A.toast('Share sheet: ' + S.backups[+sh.dataset.share].label, { icon: 'ios_share' });
      const dl = e.target.closest('[data-del]'); if (dl) { const i = +dl.dataset.del; const b = S.backups[i]; A.confirm({ title: 'Delete backup file?', body: `${b.label} · ${b.size}`, ok: 'Delete', danger: true }).then((ok) => { if (ok) { S.backups.splice(i, 1); draw(); A.toast('Backup deleted'); } }); return; }
      if (e.target.closest('[data-clear]')) A.confirm({ title: 'Clear local storage?', body: `This permanently deletes ${A.stories().length} novels and all settings from this phone. Make a full backup first.`, ok: 'Clear everything', danger: true }).then((ok) => ok && A.toast('Prototype: nothing was deleted', { icon: 'shield' }));
    });
  };

  // ---------- Manage tabs ----------
  A.screens.settings_tabs = (ctx) => {
    const tabs = W.tabs();
    const draw = () => page(ctx, 'Manage Tabs', 'Group your library your way', `
      <p class="muted" style="font-size:14px;line-height:1.5">Tabs appear at the top of the Library and in Home’s Up next. Novels can live in one tab, or stay unassigned.</p>
      <div class="set-card"><div style="display:flex;gap:8px;align-items:flex-end"><label class="field" style="flex:1"><span>New tab name</span><input data-name placeholder="e.g. Finished"></label><button class="btn primary" data-add style="min-height:50px">${A.ic('add')}Add</button></div></div>
      <div class="group">${tabs.map((t, i) => `<div class="tabcard"><span><b>${esc(t.name)}</b><small>${A.filter({ tab: t.id }).length} novels</small></span>
        <button class="ibtn sm" data-up="${i}" ${i ? '' : 'disabled'} aria-label="Move up">${A.ic('arrow_upward')}</button><button class="ibtn sm" data-down="${i}" ${i < tabs.length - 1 ? '' : 'disabled'} aria-label="Move down">${A.ic('arrow_downward')}</button>
        <button class="ibtn sm" data-ren="${i}" aria-label="Rename">${A.ic('edit')}</button><button class="ibtn sm" data-del="${i}" aria-label="Delete">${A.ic('delete')}</button></div>`).join('')}</div>`);
    draw();
    ctx.el.addEventListener('click', (e) => {
      const t = e.target, g = (k) => { const b = t.closest(`[data-${k}]`); return b ? +b.dataset[k] : null; };
      if (t.closest('[data-add]')) { const inp = ctx.el.querySelector('[data-name]'); const n = inp.value.trim(); if (!n) { A.toast('Type a tab name first', { icon: 'error' }); inp.focus(); return; } tabs.push({ id: 'tab_' + Date.now(), name: n }); draw(); A.toast(`Tab “${n}” added`, { icon: 'tab' }); return; }
      let i;
      if ((i = g('up')) != null) { [tabs[i - 1], tabs[i]] = [tabs[i], tabs[i - 1]]; draw(); return; }
      if ((i = g('down')) != null) { [tabs[i + 1], tabs[i]] = [tabs[i], tabs[i + 1]]; draw(); return; }
      if ((i = g('ren')) != null) { A.form({ title: 'Rename tab', fields: [{ key: 'name', label: 'Tab name', value: tabs[i].name }] }).then((v) => { if (v && v.name.trim()) { tabs[i].name = v.name.trim(); draw(); } }); return; }
      if ((i = g('del')) != null) { const tb = tabs[i]; A.confirm({ title: `Delete “${tb.name}”?`, body: `Its ${A.filter({ tab: tb.id }).length} novels move to Unassigned. No novels are deleted.`, ok: 'Delete', danger: true }).then((ok) => { if (ok) { tabs.splice(i, 1); draw(); A.toast('Tab deleted'); } }); }
    });
  };

  // ---------- Text cleanup ----------
  A.screens.settings_cleanup = (ctx) => {
    const c = S.cleanup; let mode = 'sent', all = false;
    const draw = () => page(ctx, 'Text Cleanup', 'Remove junk lines from chapters', `
      ${A.seg([['sent', 'Full sentences'], ['regex', 'Regex rules']], mode, 'data-mode')}
      ${mode === 'sent' ? `<div class="set-card"><label class="field"><span>Sentence to remove</span><textarea data-sent placeholder="Paste a sentence that sites insert into chapters"></textarea></label><div class="acts"><button class="btn glassy sm" data-paste>${A.ic('content_paste')}Paste</button><button class="btn primary sm" data-addsent>${A.ic('add')}Add</button></div></div>
        <section class="set-sec"><h4>Saved sentences · ${c.sentences.length}</h4><div class="group">${c.sentences.slice(0, all ? 999 : 12).map((x, i) => `<div class="sent"><span>${esc(x)}</span><button class="ibtn sm" data-rms="${i}" aria-label="Remove">${A.ic('close')}</button></div>`).join('')}</div>${!all && c.sentences.length > 12 ? `<button class="btn outline block" data-all style="margin-top:10px">Show all ${c.sentences.length}</button>` : ''}</section>`
      : `<div class="group">${c.regex.map((r, i) => `<div class="rule ${r.enabled ? '' : 'off'}"><b style="font:700 15px/1.3 var(--display)">${esc(r.name)}</b><small class="muted">${r.enabled ? 'Enabled' : 'Disabled'} · ${r.appliesTo === 'both' ? 'Read aloud and downloaded text' : esc(r.appliesTo)}</small><code>/${esc(r.pattern)}/${esc(r.flags)}</code>
          <div class="acts"><button class="btn ghost sm" data-edit="${i}">${A.ic('edit')}Edit</button><button class="btn ghost sm" data-tog="${i}">${A.ic(r.enabled ? 'toggle_off' : 'toggle_on')}${r.enabled ? 'Disable' : 'Enable'}</button><button class="btn ghost sm" data-rmr="${i}">${A.ic('delete')}Delete</button></div></div>`).join('')}</div>
        <button class="btn tonal" data-preset>${A.ic('library_add')}Add preset</button>`}`);
    draw();
    ctx.el.addEventListener('click', (e) => {
      const t = e.target, g = (k) => { const b = t.closest(`[data-${k}]`); return b ? +b.dataset[k] : null; };
      const m = t.closest('[data-mode]'); if (m) { mode = m.dataset.mode; draw(); return; }
      if (t.closest('[data-all]')) { all = true; draw(); return; }
      if (t.closest('[data-paste]')) { ctx.el.querySelector('[data-sent]').value = 'If you discover this narrative on Amazon, be aware that it has been stolen.'; return; }
      if (t.closest('[data-addsent]')) { const v = ctx.el.querySelector('[data-sent]').value.trim(); if (!v) { A.toast('Paste or type a sentence first', { icon: 'error' }); return; } c.sentences.unshift(v); draw(); A.toast('Sentence added', { icon: 'check' }); return; }
      let i;
      if ((i = g('rms')) != null) { const [x] = c.sentences.splice(i, 1); draw(); A.toast('Sentence removed', { action: 'Undo', fn: () => { c.sentences.splice(i, 0, x); draw(); } }); return; }
      if ((i = g('tog')) != null) { c.regex[i].enabled = !c.regex[i].enabled; draw(); return; }
      if ((i = g('rmr')) != null) { const r = c.regex[i]; A.confirm({ title: 'Delete rule?', body: r.name, ok: 'Delete', danger: true }).then((ok) => { if (ok) { c.regex.splice(i, 1); draw(); } }); return; }
      if ((i = g('edit')) != null) { const r = c.regex[i]; A.form({ title: 'Edit rule', fields: [{ key: 'name', label: 'Name', value: r.name }, { key: 'pattern', label: 'Pattern', value: r.pattern }, { key: 'flags', label: 'Flags', value: r.flags }] }).then((v) => { if (v) { Object.assign(r, v); draw(); A.toast('Rule saved'); } }); return; }
      if (t.closest('[data-preset]')) A.menu({ title: 'Add preset', items: [['Remove “Support me on Patreon” lines', '^.*patreon\\.com.*$'], ['Collapse blank lines', '\\n{3,}'], ['Remove translator notes', '^\\s*\\(?TL[:;].*$']].map(([n, p]) => ({ icon: 'rule', label: n, sub: '/' + p + '/gm', fn: () => { c.regex.push({ name: n, pattern: p, flags: 'gm', enabled: true, appliesTo: 'both', id: 'r' + Date.now() }); draw(); A.toast('Preset added'); } })) });
    });
  };

  // ---------- Notifications ----------
  A.screens.settings_notifications = (ctx) => {
    const cats = [['Downloads', 'download', 'Progress and finished downloads'], ['Text to Speech', 'record_voice_over', 'Playback controls on the lock screen'], ['Narration audio', 'graphic_eq', 'Audio generation progress'], ['AI Generation', 'auto_awesome', 'Cover and description jobs']];
    page(ctx, 'Notifications', 'What the app may tell you', `
      <div class="set-card"><div class="set-row"><span><b>App permission</b><small>Allowed in Android settings</small></span><span class="pill p-ok">${A.ic('check')}Allowed</span></div><button class="btn glassy sm" data-sys style="justify-self:start">${A.ic('open_in_new')}Open system settings</button></div>
      <section class="set-sec"><h4>Categories</h4><div class="set-card">${cats.map(([n, ic, sub]) => `<div class="set-row"><span style="display:flex;gap:14px;align-items:center">${A.ic(ic)}<span><b>${n}</b><small class="cat-s">Enabled · ${sub}</small></span></span>${A.sw(true)}</div>`).join('')}</div></section>`);
    ctx.el.addEventListener('sw', (e) => { e.target.closest('.set-row').querySelector('.cat-s').textContent = (e.detail ? 'Enabled' : 'Disabled') + ' · ' + e.target.closest('.set-row').querySelector('.cat-s').textContent.split(' · ')[1]; });
    ctx.el.addEventListener('click', (e) => { if (e.target.closest('[data-sys]')) A.toast('Opening Android notification settings', { icon: 'open_in_new' }); });
  };

  // ---------- Download settings ----------
  A.screens.settings_downloads = (ctx) => {
    const d = S.downloads;
    const ov = [['RoyalRoad', 1, 1200], ['SpaceBattles', 1, 3000], ['Scribble Hub', 2, 800]];
    page(ctx, 'Download Settings', 'Be gentle with story sites', `
      <div class="set-card">
        <div><div class="set-lbl"><b>Parallel sources</b><span class="v" data-v="parallel">${d.parallel}</span></div>${A.range(d.parallel, 1, 5, 1, 'data-k="parallel"')}<p class="faint" style="font-size:12.5px">Sites downloaded at the same time. Each site still gets one chapter at a time.</p></div>
        <div><div class="set-lbl"><b>Delay between chapters · min</b><span class="v" data-v="delayMin">${d.delayMin} ms</span></div>${A.range(d.delayMin, 0, 5000, 100, 'data-k="delayMin"')}</div>
        <div><div class="set-lbl"><b>Delay between chapters · max</b><span class="v" data-v="delayMax">${d.delayMax} ms</span></div>${A.range(d.delayMax, 0, 10000, 100, 'data-k="delayMax"')}</div>
        <div><div class="set-lbl"><b>Max chapters per EPUB</b><span class="v" data-v="maxPerEpub">${d.maxPerEpub}</span></div>${A.range(d.maxPerEpub, 50, 500, 10, 'data-k="maxPerEpub"')}</div></div>
      <section class="set-sec"><h4>Per-source overrides</h4><div class="group">${ov.map(([n, p, ms]) => `<button class="row" data-ov="${n}">${A.ic('public')}<span class="rt"><b>${n}</b><small>${p} at a time · ${ms} ms minimum delay</small></span>${A.ic('chevron_right', 'chev')}</button>`).join('')}
        <button class="row" data-ov="+">${A.ic('add_circle')}<span class="rt"><b>Add override</b><small>Slow down or speed up one site</small></span></button></div></section>`);
    ctx.el.addEventListener('input', (e) => { const k = e.target.dataset.k; if (!k) return; d[k] = +e.target.value; ctx.el.querySelector(`[data-v="${k}"]`).textContent = d[k] + (k.startsWith('delay') ? ' ms' : ''); });
    ctx.el.addEventListener('click', (e) => { const o = e.target.closest('[data-ov]'); if (o) A.form({ title: o.dataset.ov === '+' ? 'Add override' : o.dataset.ov, fields: [...(o.dataset.ov === '+' ? [{ key: 's', label: 'Source', placeholder: 'FanFiction.net' }] : []), { key: 'p', label: 'Chapters at a time', value: '1', type: 'number' }, { key: 'ms', label: 'Minimum delay (ms)', value: '1500', type: 'number' }] }).then((v) => v && A.toast('Override saved', { icon: 'check' })); });
  };

  // ---------- App updates ----------
  A.screens.settings_update = (ctx) => {
    const a = S.app;
    page(ctx, 'App Updates', 'From GitHub Releases', `
      <div class="set-card" style="text-align:center;justify-items:center;padding:28px 18px"><span class="voice-av" style="width:72px;height:72px;border-radius:24px">${A.ic('system_update', 'f')}</span>
        <div><p class="eyebrow" style="color:var(--t3)">Installed version</p><h2 style="font-size:28px;margin-top:6px">${esc(a.version)}</h2></div>
        <p class="upd-s muted">${A.ic('check_circle')} You have the latest version · Checked ${esc(a.checkedAgo)}</p>
        <button class="btn primary" data-check>${A.ic('refresh')}Check for updates</button></div>
      <div class="set-card"><div class="set-row"><span><b>Check automatically</b><small>Once a day, on Wi-Fi</small></span>${A.sw(a.autoCheck, 'data-auto')}</div></div>`);
    ctx.el.addEventListener('sw', (e) => { a.autoCheck = e.detail; });
    ctx.el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-check]'); if (!b) return;
      b.innerHTML = '<span class="spin"></span>Checking…'; b.disabled = true;
      setTimeout(() => { b.innerHTML = `${A.ic('refresh')}Check for updates`; b.disabled = false; a.checkedAgo = 'just now'; ctx.el.querySelector('.upd-s').innerHTML = `${A.ic('check_circle')} You have the latest version · Checked just now`; A.toast('You’re up to date', { icon: 'check' }); }, 1500);
    });
  };
})();
