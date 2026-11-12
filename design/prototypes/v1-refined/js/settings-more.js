/* v1 Refined — secondary settings: AI, Data & Backup, Tabs, Cleanup, Notifications, Downloads, Updates. */
(function () {
  const V = window.V1;
  const { esc, fmt } = WNA;
  const SET = WNA.settings;
  const page = (title, sub = '') => WNA.el(`<div class="screen">${V.appBar({ title, subtitle: sub })}<div class="wrap body"></div><div class="pad-bottom"></div></div>`);
  const done = (el, title) => { V.watchScroll(el); return { el, title }; };

  // ---------- AI settings ----------
  V.screens.settings_ai = () => {
    const el = page('AI Settings', 'OpenRouter');
    const M = WNA.ai.models, sp = WNA.ai.spend;
    let usage = '$6.37 of $20.00 limit', refreshing = false, keyShown = false;
    const MODELS = ['google/gemini-2.5-flash', 'google/gemini-2.5-flash-image', 'openai/gpt-5-mini', 'anthropic/claude-haiku-5-5', 'openai/gpt-image-1'];
    function render() {
      el.querySelector('.body').innerHTML = `
        ${V.group('OpenRouter API key', `<div class="card pad"><span class="input">${V.ic('key')}<input value="${keyShown ? 'sk-or-v1-4f2a9c71e0b8d3…' : '••••••••••••••••••••••'}" aria-label="API key" readonly>${V.ib(keyShown ? 'visibility_off' : 'visibility', 'eye', 'Show key', { cls: 'sm' })}</span><p class="help faint" style="font:var(--t-xs);margin:8px 4px 0">Stored on this phone only. Used for covers and descriptions.</p></div>`)}
        ${V.group('Models', `<div class="list">
          ${[['description', 'Description model', 'notes'], ['image', 'Cover image model', 'image'], ['decision', 'Chapter selection model', 'checklist']].map(([k, l, ic]) => `<button class="li has-ic" data-act="model" data-k="${k}"><span class="lic">${V.ic(ic)}</span><div class="grow"><div class="lt">${l}</div><div class="ls ell">${esc(M[k])}</div></div><span class="pill">${esc(M.reasoning)}</span>${V.ic('chevron_right', 'chev')}</button>`).join('')}
          <div class="li"><div class="grow"><div class="lt">Chapter scan limit</div><div class="ls">Chapters the AI may read for context</div></div><div class="numf"><button class="ib" data-act="scan" data-d="-5">${V.ic('remove')}</button><b class="scanv">${SET.aiScan || 30}</b><button class="ib" data-act="scan" data-d="5">${V.ic('add')}</button></div></div>
        </div>`)}
        ${V.group('AI spend', `<div class="spend"><div><b>$${sp.today.toFixed(2)}</b><span>Today</span></div><div><b>$${sp.month.toFixed(2)}</b><span>This month</span></div><div><b>$${sp.allTime.toFixed(2)}</b><span>All time</span></div></div>
          <div class="list" style="margin-top:12px">${sp.recent.map((x) => `<div class="li"><div class="grow"><div class="lt">${esc(x.feature)}</div><div class="ls ell">${esc(x.model)} · ${fmt.int(x.tokens)} tokens · ${fmt.ago(x.at)}</div></div><span class="lv tnum">$${x.cost.toFixed(3)}</span></div>`).join('')}
          <div class="li"><div class="grow"><div class="lt">Current key usage</div><div class="ls">${esc(usage)}</div></div>${refreshing ? '<span class="spin" style="color:var(--gold);margin-right:10px"></span>' : V.btn('Refresh', 'refresh', { kind: 'text', cls: 'sm' })}</div></div>`)}
        <div style="margin-top:24px">${V.btn('Save', 'save', { kind: 'filled', cls: 'block lg' })}</div>`;
    }
    render();
    V.bind(el, {
      eye() { keyShown = !keyShown; render(); },
      model(t) { const k = t.dataset.k; V.menu({ title: t.querySelector('.lt').textContent, items: MODELS.map((m) => ({ act: m, label: m, on: M[k] === m })), onPick(m) { M[k] = m; render(); } }); },
      scan(t) { SET.aiScan = Math.max(5, Math.min(200, (SET.aiScan || 30) + +t.dataset.d)); el.querySelector('.scanv').textContent = SET.aiScan; },
      refresh() { refreshing = true; render(); setTimeout(() => { refreshing = false; usage = '$6.37 of $20.00 limit · checked just now'; render(); }, 1200); },
      save: () => { V.toast('AI settings saved'); WNA.back(); },
    });
    return done(el, 'AI Settings');
  };

  // ---------- Data & Backup ----------
  V.screens.settings_data = () => {
    const el = page('Data & Backup');
    const busy = {};
    function render() {
      const act = (k, icon, title, sub) => `<button class="li has-ic" data-act="op" data-k="${k}"><span class="lic">${busy[k] ? '<span class="spin" style="width:18px;height:18px"></span>' : V.ic(icon)}</span><div class="grow"><div class="lt">${title}</div><div class="ls">${busy[k] ? esc(busy[k]) : sub}</div></div>${V.ic('chevron_right', 'chev')}</button>`;
      el.querySelector('.body').innerHTML = `
        ${V.group('Backup', `<div class="list">
          ${act('export', 'upload_file', 'Export backup', 'Library, tabs and settings as JSON')}
          ${act('import', 'download', 'Import backup', 'Merge a JSON backup into this library')}
          ${act('full', 'inventory_2', 'Create full backup', 'Everything, including chapters · ~412 MB')}
          ${act('restore', 'settings_backup_restore', 'Restore full backup', 'Replaces this library')}
          ${act('recover', 'healing', 'Recover chapters from backup', 'Fill in missing chapter text')}
        </div>`)}
        ${V.group('Backup files', SET.backups.length ? `<div class="col" style="gap:8px">${SET.backups.map((b, i) => `<div class="card" style="display:flex;align-items:center;gap:12px;padding:10px 6px 10px 16px">${V.ic('folder_zip', 'gold')}<div class="grow"><div style="font:var(--t-body-m)">${esc(b.label)}</div><div class="faint" style="font:var(--t-xs)">${esc(b.size)} · ${fmt.date(b.at)}</div></div>${V.ib('share', 'share', 'Share', { cls: 'sm', data: `data-i="${i}"` })}${V.ib('delete', 'delb', 'Delete', { cls: 'sm', data: `data-i="${i}"` })}</div>`).join('')}</div>` : '<div class="card pad faint">No backup files yet</div>')}
        ${V.group('Sources & storage', `<div class="list">
          <div class="li has-ic"><span class="lic">${V.ic('verified_user')}</span><div class="grow"><div class="lt">Source access</div><div class="ls">Ready • WebView 142 · cookies for 3 sites</div></div><span class="pill completed">OK</span></div>
          ${V.row({ icon: 'receipt_long', title: 'Share activity logs', act: 'toast', data: 'data-m="Preparing activity logs…"' })}
          ${V.row({ icon: 'lan', title: 'Share source access logs', act: 'toast', data: 'data-m="Preparing source logs…"' })}
          ${V.row({ icon: 'volunteer_activism', title: 'Patreon account', sub: 'Signed in · 2 memberships', act: 'toast', data: 'data-m="Opening Patreon sign-in…"' })}
          ${V.row({ icon: 'restart_alt', title: 'Reset source web session', sub: 'Signs out of sites and clears cookies', act: 'reset' })}
        </div>`)}
        ${V.group('Danger zone', `<div class="list">${V.row({ icon: 'delete_forever', title: 'Clear local storage', sub: 'Deletes every novel, chapter and audio file', act: 'wipe', danger: true })}</div>`)}`;
    }
    render();
    const LABEL = { export: 'Exporting…', import: 'Choosing a file…', full: 'Packing 21,978 chapters…', restore: 'Choosing a backup…', recover: 'Scanning backup…' };
    const DONE = { export: 'Backup exported to Downloads', import: 'Imported 0 new novels · library up to date', full: 'Full backup created · 412 MB', restore: 'Restore cancelled', recover: 'Recovered 12 chapters' };
    V.bind(el, {
      op(t) { const k = t.dataset.k; if (busy[k]) return; busy[k] = LABEL[k]; render(); setTimeout(() => { delete busy[k]; if (k === 'full') SET.backups.unshift({ label: 'Full backup', size: '412 MB', at: Date.now() }); render(); V.toast(DONE[k]); }, 1800); },
      share: () => V.toast('Opening share sheet…'),
      async delb(t) { if (await V.confirm('Delete this backup file?', 'This can’t be undone.', 'Delete')) { SET.backups.splice(+t.dataset.i, 1); render(); } },
      toast: (t) => V.toast(t.dataset.m),
      async reset() { if (await V.confirm('Reset web session?', 'You’ll need to sign in to sites and solve checks again.', 'Reset', { danger: false })) V.toast('Web session reset'); },
      async wipe() { if (await V.confirm('Clear local storage?', 'Every novel, chapter, EPUB and audio file on this phone will be deleted. Make a full backup first.', 'Clear everything')) V.toast('Not in the prototype · nothing was deleted'); },
    });
    return done(el, 'Data & Backup');
  };

  // ---------- Manage Tabs ----------
  V.screens.settings_tabs = () => {
    const el = page('Manage Tabs');
    const tabs = WNA.tabs();
    function render() {
      const count = (id) => V.stories().filter((s) => s.tab === id).length;
      el.querySelector('.body').innerHTML = `
        <p class="sec-help" style="margin-top:8px;padding-top:4px">Tabs organize your library. Novels in a deleted tab become Unassigned.</p>
        <div class="card pad" style="display:flex;gap:8px;align-items:center"><span class="input grow" style="height:44px"><input class="newtab" placeholder="New tab name" aria-label="New tab name"></span>${V.btn('Add', 'add', { kind: 'filled', icon: 'add' })}</div>
        <section class="section"><div class="sec-h"><h2>${V.plural(tabs.length, 'tab')}</h2></div><div class="list">
          ${tabs.map((t, i) => `<div class="li tabcard" style="padding-left:16px">${V.ic('drag_indicator', 'faint')}<div class="grow"><b>${esc(t.name)}</b><span>${V.plural(count(t.id), 'novel')}</span></div>
            ${V.ib('arrow_upward', 'up', 'Move up', { cls: 'sm', data: `data-i="${i}"` }).replace('<button', i === 0 ? '<button disabled' : '<button')}
            ${V.ib('arrow_downward', 'down', 'Move down', { cls: 'sm', data: `data-i="${i}"` }).replace('<button', i === tabs.length - 1 ? '<button disabled' : '<button')}
            ${V.ib('edit', 'ren', 'Rename', { cls: 'sm', data: `data-i="${i}"` })}${V.ib('delete', 'del', 'Delete', { cls: 'sm', data: `data-i="${i}"` })}</div>`).join('')}
        </div></section>`;
    }
    render();
    const swap = (a, b) => { [tabs[a], tabs[b]] = [tabs[b], tabs[a]]; V.libDirty = true; render(); };
    V.bind(el, {
      add() { const inp = el.querySelector('.newtab'); const n = inp.value.trim(); if (!n) { inp.focus(); return; } tabs.push({ id: 'tab_' + Date.now(), name: n }); V.libDirty = true; render(); V.toast(`Added “${n}”`); },
      up: (t) => swap(+t.dataset.i, +t.dataset.i - 1),
      down: (t) => swap(+t.dataset.i, +t.dataset.i + 1),
      async ren(t) { const tb = tabs[+t.dataset.i]; const r = await V.dialog({ title: 'Rename tab', html: V.field('name', 'Name', tb.name), actions: [{ label: 'Cancel', value: null }, { label: 'Rename', value: 'ok', kind: 'filled' }] }); if (r.value && r.form.name.trim()) { tb.name = r.form.name.trim(); V.libDirty = true; render(); } },
      async del(t) { const i = +t.dataset.i; const tb = tabs[i]; if (await V.confirm(`Delete “${tb.name}”?`, 'Its novels move to Unassigned. Nothing is deleted from your library.', 'Delete')) { tabs.splice(i, 1); if (V.state.tab === tb.id) V.state.tab = 'all'; V.libDirty = true; render(); } },
    });
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.classList.contains('newtab')) el.querySelector('[data-act="add"]').click(); });
    return done(el, 'Manage Tabs');
  };

  // ---------- Text Cleanup ----------
  V.screens.settings_cleanup = () => {
    const el = page('Text Cleanup', 'Applied when reading aloud and downloading');
    const C = SET.cleanup || (SET.cleanup = { regex: [], sentences: [] });
    let mode = 'sentences';
    function render() {
      el.querySelector('.body').innerHTML = `
        <div class="seg" style="margin:4px 0 16px"><button class="${mode === 'sentences' ? 'on' : ''}" data-act="mode" data-k="sentences">Full sentences</button><button class="${mode === 'regex' ? 'on' : ''}" data-act="mode" data-k="regex">Regex rules</button></div>
        ${mode === 'sentences' ? `
          <p class="sec-help" style="padding-top:0">Exact sentences removed wherever they appear, like piracy notices.</p>
          <div class="card pad"><span class="input ta"><textarea class="newsent" rows="2" placeholder="Paste a sentence to remove"></textarea></span>
          <div class="btn-row" style="margin-top:10px;justify-content:flex-end">${V.btn('Paste', 'paste', { kind: 'text', icon: 'content_paste', cls: 'sm' })}${V.btn('Add', 'addsent', { kind: 'filled', icon: 'add', cls: 'sm' })}</div></div>
          <section class="section"><div class="sec-h"><h2>Saved sentences · ${C.sentences.length}</h2></div><div class="list">${C.sentences.map((x, i) => `<div class="li"><div class="grow ls clamp2" style="color:var(--text);margin:0">${esc(x)}</div>${V.ib('close', 'rmsent', 'Remove', { cls: 'sm', data: `data-i="${i}"` })}</div>`).join('')}</div></section>`
        : `
          <p class="sec-help" style="padding-top:0">Patterns that rewrite or remove text. Rules run top to bottom.</p>
          <div class="col" style="gap:10px">${C.regex.map((r, i) => `<div class="card rule ${r.enabled ? '' : 'off'}"><div class="rh"><b>${esc(r.name)}</b><span class="pill ${r.enabled ? 'ongoing' : ''}">${r.enabled ? 'Enabled' : 'Disabled'}</span></div>
            <div class="rs">Read aloud and downloaded text</div><code class="mono">/${esc(r.pattern)}/${esc(r.flags)}</code>
            <div class="btn-row" style="margin-top:10px">${V.btn('Edit', 'editr', { kind: 'text', cls: 'sm', data: `data-i="${i}"` })}${V.btn(r.enabled ? 'Disable' : 'Enable', 'tgr', { kind: 'text', cls: 'sm', data: `data-i="${i}"` })}${V.btn('Delete', 'delr', { kind: 'text', cls: 'sm danger', data: `data-i="${i}"` })}</div></div>`).join('')}</div>
          <div style="margin-top:16px" class="btn-row fill">${V.btn('Add rule', 'addr', { kind: 'tonal', icon: 'add' })}${V.btn('Add preset', 'preset', { kind: 'outlined', icon: 'library_add' })}</div>`}`;
    }
    render();
    const editRule = async (r) => {
      const x = await V.dialog({ title: r ? 'Edit rule' : 'New rule', html: V.field('name', 'Name', r ? r.name : '') + V.field('pattern', 'Pattern', r ? r.pattern : '', { placeholder: '\\[T/N:.*?\\]' }) + V.field('flags', 'Flags', r ? r.flags : 'g'), actions: [{ label: 'Cancel', value: null }, { label: 'Save', value: 'ok', kind: 'filled' }] });
      if (!x.value) return;
      if (r) Object.assign(r, x.form); else C.regex.push({ id: 'r' + Date.now(), enabled: true, appliesTo: 'both', ...x.form });
      render();
    };
    V.bind(el, {
      mode(t) { mode = t.dataset.k; render(); },
      paste() { el.querySelector('.newsent').value = 'Read the original on the author’s official site.'; },
      addsent() { const v = el.querySelector('.newsent').value.trim(); if (!v) return; C.sentences.unshift(v); render(); V.toast('Sentence added'); },
      rmsent(t) { const [x] = C.sentences.splice(+t.dataset.i, 1); render(); V.toast('Removed', { action: 'Undo', onAction: () => { C.sentences.splice(+t.dataset.i, 0, x); render(); } }); },
      editr: (t) => editRule(C.regex[+t.dataset.i]),
      tgr(t) { const r = C.regex[+t.dataset.i]; r.enabled = !r.enabled; render(); },
      async delr(t) { if (await V.confirm('Delete rule?', C.regex[+t.dataset.i].name, 'Delete')) { C.regex.splice(+t.dataset.i, 1); render(); } },
      addr: () => editRule(null),
      preset() { V.menu({ title: 'Add preset', items: [{ act: 'tn', label: 'Remove translator notes', sub: '[T/N: …]' }, { act: 'ad', label: 'Remove “support me on Patreon” lines' }, { act: 'sep', label: 'Remove separator lines (3+ symbols)' }], onPick(a) { C.regex.push({ id: 'p' + Date.now(), enabled: true, appliesTo: 'both', name: { tn: 'Remove translator notes', ad: 'Remove Patreon plugs', sep: 'Remove separator lines' }[a], pattern: { tn: '\\[T/?N:.*?\\]', ad: '^.*patreon\\.com.*$', sep: '^\\h*[\\p{P}\\p{S}]{3,}\\h*$' }[a], flags: 'gim' }); render(); V.toast('Preset added'); } }); },
    });
    return done(el, 'Text Cleanup');
  };

  // ---------- Notifications ----------
  V.screens.settings_notifications = () => {
    const el = page('Notifications');
    const cats = SET.notif || (SET.notif = { Downloads: true, 'Text to Speech': true, 'Narration audio': true, 'AI Generation': true });
    const SUB = { Downloads: 'Progress and finished downloads', 'Text to Speech': 'Playback controls on the lock screen', 'Narration audio': 'Audio generation progress', 'AI Generation': 'Covers and descriptions ready' };
    function render() {
      el.querySelector('.body').innerHTML = `
        ${V.group('App permission', `<div class="card pad" style="display:flex;gap:14px;align-items:center">${V.ic('notifications_active', 'gold')}<div class="grow"><div style="font:var(--t-body-m)">Allowed</div><div class="faint" style="font:var(--t-sm)">Android lets this app post notifications.</div></div>${V.btn('System settings', 'sys', { kind: 'text', cls: 'sm' })}</div>`)}
        ${V.group('Categories', `<div class="list">${Object.keys(cats).map((k) => V.swRow({ title: k, sub: `${cats[k] ? 'Enabled' : 'Off'} · ${SUB[k]}`, on: cats[k], act: 'tg', data: `data-k="${esc(k)}"` })).join('')}</div>`)}`;
    }
    render();
    V.bind(el, { tg(t) { const k = t.dataset.k; cats[k] = !cats[k]; render(); }, sys: () => V.toast('Opening Android notification settings…') });
    return done(el, 'Notifications');
  };

  // ---------- Download settings ----------
  V.screens.settings_downloads = () => {
    const el = page('Download Settings');
    const D = SET.downloads;
    const over = SET.srcOver || (SET.srcOver = { SpaceBattles: { parallel: 1, delayMin: 2000 } });
    const num = (k, label, sub, step, min, max, unit = '') => `<div class="li"><div class="grow"><div class="lt">${label}</div>${sub ? `<div class="ls">${sub}</div>` : ''}</div><div class="numf"><button class="ib" data-act="num" data-k="${k}" data-d="${-step}" data-min="${min}" data-max="${max}">${V.ic('remove')}</button><b data-v="${k}">${fmt.int(D[k])}${unit}</b><button class="ib" data-act="num" data-k="${k}" data-d="${step}" data-min="${min}" data-max="${max}">${V.ic('add')}</button></div></div>`;
    function render() {
      el.querySelector('.body').innerHTML = `
        ${V.group('Speed', `<div class="list">
          ${num('parallel', 'Parallel sources', 'Different sites downloading at once', 1, 1, 6)}
          ${num('delayMin', 'Delay min', 'Wait between chapters (ms)', 100, 0, 10000)}
          ${num('delayMax', 'Delay max', 'Randomized up to this (ms)', 100, 0, 20000)}
        </div>`, 'Gentler settings mean fewer “slow down” checks from sites.')}
        ${V.group('EPUB', `<div class="list">${num('maxPerEpub', 'Max chapters per EPUB', 'Longer stories split into volumes', 25, 25, 1000)}</div>`)}
        ${V.group('Per-source overrides', `<div class="list">${WNA.sources.slice(0, 5).map((x) => `<button class="li has-ic" data-act="src" data-n="${esc(x.name)}"><span class="lic">${V.ic('public')}</span><div class="grow"><div class="lt">${esc(x.name)}</div><div class="ls">${over[x.name] ? `${over[x.name].parallel} at a time · ${over[x.name].delayMin} ms min` : 'Uses the defaults'}</div></div>${over[x.name] ? '<span class="pill ongoing">Custom</span>' : ''}${V.ic('chevron_right', 'chev')}</button>`).join('')}</div>`)}`;
    }
    render();
    V.bind(el, {
      num(t) { const k = t.dataset.k; D[k] = Math.max(+t.dataset.min, Math.min(+t.dataset.max, D[k] + +t.dataset.d)); if (k === 'delayMin' && D.delayMin > D.delayMax) D.delayMax = D.delayMin; el.querySelectorAll(`[data-v]`).forEach((b) => (b.textContent = fmt.int(D[b.dataset.v]))); },
      async src(t) {
        const n = t.dataset.n; const o = over[n] || { parallel: D.parallel, delayMin: D.delayMin };
        const r = await V.dialog({ title: n, html: V.field('parallel', 'Chapters at a time', String(o.parallel), { type: 'number' }) + V.field('delayMin', 'Minimum delay (ms)', String(o.delayMin), { type: 'number' }), actions: [{ label: 'Use defaults', value: 'def' }, { label: 'Save', value: 'ok', kind: 'filled' }] });
        if (r.value === 'def') delete over[n]; else if (r.value === 'ok') over[n] = { parallel: +r.form.parallel || 1, delayMin: +r.form.delayMin || 0 };
        render();
      },
    });
    return done(el, 'Download Settings');
  };

  // ---------- App updates ----------
  V.screens.settings_update = () => {
    const el = page('App Updates');
    const A = SET.app;
    let checking = false;
    function render() {
      el.querySelector('.body').innerHTML = `
        <div class="upd-hero"><div class="eic">${checking ? '<span class="spin" style="width:30px;height:30px;border-width:3px"></span>' : V.ic('verified')}</div>
          <h2>${checking ? 'Checking…' : 'You have the latest version'}</h2><p>Installed ${esc(A.version)} · checked ${esc(A.checkedAgo)}</p>
          <div style="margin-top:16px">${V.btn('Check for updates', 'check', { kind: 'tonal', icon: 'refresh', disabled: checking })}</div></div>
        <div class="list">${V.swRow({ title: 'Check automatically', sub: 'Once a day, from GitHub Releases', on: A.autoCheck, act: 'auto' })}
          ${V.row({ icon: 'description', title: 'Release notes', sub: `What’s new in ${A.version}`, act: 'notes' })}</div>`;
    }
    render();
    V.bind(el, {
      check() { checking = true; render(); setTimeout(() => { checking = false; A.checkedAgo = 'just now'; render(); V.toast('No update available'); }, 1500); },
      auto() { A.autoCheck = !A.autoCheck; render(); },
      notes: () => V.dialog({ title: `What’s new in ${A.version}`, html: '<ul style="padding-left:18px;margin:0;display:grid;gap:6px"><li>Narration can run on a computer on your network.</li><li>Memory-aware Kokoro generation.</li><li>A side-by-side beta track.</li></ul>', actions: [{ label: 'OK', value: true, kind: 'filled' }] }),
    });
    return done(el, 'App Updates');
  };
})();
