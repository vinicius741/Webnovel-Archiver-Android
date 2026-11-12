/* Secondary settings: AI, Data & backup, Manage tabs, Text cleanup, Notifications, Download settings, App updates. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const S = WNA.settings;
  const P = V2.settingsPages;
  const rerender = (p) => V2.replace(`/settings/${p}?r=${Date.now() % 997}`);

  // ---------- AI ----------
  const aiu = { key: 'sk-or-v1-8f3c…a91d', show: false, scan: 12, usage: '$6.37 of $20.00 limit' };
  P.ai = (ctx) => {
    const M = WNA.ai.models; const sp = WNA.ai.spend;
    const model = (k, l, v, icon) => `<button class="li" data-model="${k}"><span class="lead">${ic(icon)}</span><span class="tx"><b>${l}</b><span>${esc(v)}</span></span><span class="pill" data-reason="${k}">${esc(M.reasoning)}</span></button>`;
    V2.subPage(ctx, 'AI settings', 'Covers and descriptions through OpenRouter', `
      <div class="wrap"><section class="card col" style="gap:12px"><b class="t-tm">OpenRouter API key</b>
        <label class="tf"><label>API key</label><input data-key type="${aiu.show ? 'text' : 'password'}" value="${esc(aiu.key)}"><span class="trail">${V2.ib(aiu.show ? 'visibility_off' : 'visibility', 'showkey', 'Show key')}</span></label>
        <p class="t-bs muted">Stored only on this phone. Get a key at openrouter.ai.</p></section></div>
      <div class="sec-h"><h2>Models</h2></div>
      <div class="seglist">${model('description', 'Description model', M.description, 'description')}${model('image', 'Cover image model', M.image, 'image')}${model('decision', 'Chapter selection model', M.decision, 'checklist')}</div>
      <div class="wrap" style="margin-top:12px"><section class="card"><div class="row"><div class="grow"><b class="t-tm">Chapter scan limit</b><p class="t-bs muted">Chapters the AI may read for context</p></div><b class="t-tl scan-v">${aiu.scan}</b></div>${V2.sliderHtml('scan', aiu.scan, 1, 40)}</section></div>
      <div class="sec-h"><h2>AI spend</h2></div>
      <div class="wrap"><div class="stats">${[['Today', sp.today], ['This month', sp.month], ['All time', sp.allTime]].map(([l, v]) => `<div class="stat"><span class="v">$${v.toFixed(2)}</span><span class="k">${l}</span></div>`).join('')}</div></div>
      <p class="sec-hint" style="padding-top:16px">Recent requests</p>
      <div class="seglist">${sp.recent.map((r) => `<div class="li"><span class="lead">${ic(r.feature.startsWith('Cover') ? 'image' : 'description')}</span><span class="tx"><b>${esc(r.feature)}</b><span>${esc(r.model)} · ${fmt.int(r.tokens)} tokens · ${fmt.ago(r.at)}</span></span><b class="t-ll">$${r.cost.toFixed(3)}</b></div>`).join('')}</div>
      <div class="wrap" style="margin-top:12px"><section class="card row"><div class="grow"><b class="t-tm">Current OpenRouter key usage</b><p class="t-bs muted usage">${aiu.usage}</p></div><button class="btn tonal s" data-a="refresh">${ic('refresh')}Refresh</button></section>
        <button class="btn filled l block" data-a="save" style="margin-top:20px">Save</button></div>`);
    V2.bindSliders(ctx.el, { scan: { min: 1, max: 40, step: 1, fmt: (v) => `${v} chapters`, set: (v) => { aiu.scan = v; ctx.el.querySelector('.scan-v').textContent = v; } } });
    ctx.el.addEventListener('input', (e) => { if (e.target.matches('[data-key]')) aiu.key = e.target.value; });
    ctx.el.addEventListener('click', async (e) => {
      const m = e.target.closest('[data-model]');
      if (m) {
        const k = m.dataset.model;
        if (e.target.closest('[data-reason]')) { const v = await V2.choose({ title: 'Reasoning', options: ['Default', 'Off', 'Low', 'Medium', 'High'].map((x) => ({ value: x, label: x })), value: M.reasoning }); if (v) { M.reasoning = v; ctx.el.querySelectorAll('[data-reason]').forEach((p) => (p.textContent = v)); } return; }
        const opts = k === 'image' ? ['google/gemini-2.5-flash-image', 'openai/gpt-image-1', 'black-forest-labs/flux-1.1-pro'] : ['google/gemini-2.5-flash', 'openai/gpt-5-mini', 'anthropic/claude-haiku-4.5', 'deepseek/deepseek-chat-v3.1'];
        const v = await V2.choose({ title: m.querySelector('b').textContent, options: opts.map((x) => ({ value: x, label: x })), value: M[k] }); if (v) { M[k] = v; m.querySelector('.tx span').textContent = v; }
        return;
      }
      const a = e.target.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'showkey') { aiu.show = !aiu.show; ctx.el.querySelector('[data-key]').type = aiu.show ? 'text' : 'password'; a.innerHTML = ic(aiu.show ? 'visibility_off' : 'visibility'); }
      if (a.dataset.a === 'refresh') { a.innerHTML = '<span class="loader" style="width:18px;height:18px"></span>Refreshing'; await V2.wait(1100); aiu.usage = `$${(WNA.ai.spend.allTime).toFixed(2)} of $20.00 limit · checked just now`; ctx.el.querySelector('.usage').textContent = aiu.usage; a.innerHTML = `${ic('refresh')}Refresh`; }
      if (a.dataset.a === 'save') V2.snack('AI settings saved');
    });
  };

  // ---------- Data & backup ----------
  P.data = (ctx) => {
    const row = (a, icon, t, sub, cls = '') => `<button class="li ${cls}" data-a="${a}"><span class="lead">${ic(icon)}</span><span class="tx"><b>${t}</b>${sub ? `<span>${sub}</span>` : ''}</span></button>`;
    V2.subPage(ctx, 'Data & backup', '', `
      <div class="sec-h"><h2>Backup</h2></div>
      <div class="seglist">${row('export', 'upload_file', 'Export backup', 'Library, tabs and settings as a small JSON file')}${row('import', 'download', 'Import backup', 'Merge a JSON backup into this library')}${row('full', 'inventory_2', 'Create full backup', 'Everything, including chapters and audio')}${row('restore', 'settings_backup_restore', 'Restore full backup')}${row('recover', 'healing', 'Recover chapters from backup', 'Bring back chapters the source removed')}</div>
      <div class="sec-h"><h2>Backup files</h2></div>
      <div class="wrap col bk-files" style="gap:8px">${S.backups.map((b, i) => `<section class="card mid bk" data-bk="${i}"><span class="pat-ic">${ic(b.label.startsWith('Full') ? 'inventory_2' : 'data_object')}</span><div class="grow"><b class="t-ts">${esc(b.label)}</b><span class="t-bs muted">${b.size} · ${fmt.date(b.at)}</span></div>${V2.ib('share', 'share', 'Share')}${V2.ib('delete', 'delbk', 'Delete')}</section>`).join('')}</div>
      <div class="sec-h"><h2>Sources &amp; storage</h2></div>
      <div class="seglist"><div class="li"><span class="lead">${ic('verified')}</span><span class="tx"><b>Source access status</b><span>Ready • WebView 142 • cookies for 4 sites</span></span></div>${row('logs', 'bug_report', 'Share activity logs')}${row('slogs', 'travel_explore', 'Share source access logs')}${row('patreon', 'volunteer_activism', 'Patreon account', 'Signed in · used for member counts')}${row('reset', 'restart_alt', 'Reset source web session', 'Clears cookies if a site keeps asking to verify')}</div>
      <div class="sec-h"><h2 style="color:var(--error)">Danger zone</h2></div>
      <div class="seglist">${row('clear', 'delete_forever', 'Clear local storage', 'Deletes every novel, chapter and setting on this phone', 'danger')}</div>`);
    ctx.el.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-a]'); if (!a) return; const bk = a.closest('[data-bk]');
      const busy = async (msg, done, ms = 1600) => { V2.snack(msg); await V2.wait(ms); V2.snack(done); };
      switch (a.dataset.a) {
        case 'export': busy('Exporting library…', 'Backup saved to Downloads/webnovel-backup.json'); break;
        case 'import': busy('Reading backup…', 'Imported 3 new novels · 279 already in your library'); break;
        case 'full': busy('Creating full backup… this can take a minute', 'Full backup saved · 412 MB', 2400); S.backups.unshift({ label: 'Full backup', size: '412 MB', at: Date.now() }); setTimeout(() => rerender('data'), 2500); break;
        case 'restore': if (await V2.confirm({ title: 'Restore full backup?', text: 'Your current library will be replaced by the backup.', ok: 'Restore', icon: 'settings_backup_restore' })) busy('Restoring…', 'Restore complete'); break;
        case 'recover': busy('Scanning backups…', 'Recovered 4 chapters in 2 novels'); break;
        case 'share': V2.snack(`Sharing ${S.backups[+bk.dataset.bk].label}…`); break;
        case 'delbk': { const i = +bk.dataset.bk; const [b] = S.backups.splice(i, 1); bk.remove(); V2.snack(`Deleted ${b.label}`, { action: 'Undo', onAction: () => { S.backups.splice(i, 0, b); rerender('data'); } }); break; }
        case 'logs': case 'slogs': V2.snack('Preparing logs to share…'); break;
        case 'patreon': V2.snack('Opening Patreon account…'); break;
        case 'reset': if (await V2.confirm({ title: 'Reset source web session?', text: 'You may need to verify some sites again.', ok: 'Reset', icon: 'restart_alt' })) V2.snack('Web session reset'); break;
        case 'clear': if (await V2.confirm({ title: 'Clear local storage?', text: 'Every novel, chapter, audio file and setting on this phone will be deleted. This can’t be undone.', ok: 'Clear everything', danger: true, icon: 'delete_forever' })) V2.snack('Not cleared — this is a prototype 🙂'); break;
      }
    });
  };

  // ---------- Manage tabs ----------
  P.tabs = (ctx) => {
    const tabs = WNA.tabs();
    V2.subPage(ctx, 'Manage tabs', '', `
      <p class="sec-hint">Tabs group your library. A novel lives in one tab; novels without one show under Unassigned.</p>
      <div class="wrap"><section class="card row new-tab"><label class="tf grow"><label>New tab name</label><input data-new placeholder="Finished"></label><button class="btn filled" data-a="add">${ic('add')}Add</button></section></div>
      <div class="wrap col" style="gap:4px;margin-top:16px">${tabs.map((t, i) => `<section class="card mid tabc" data-t="${i}"><div class="grow"><b class="t-tm">${esc(t.name)}</b><span class="t-bs muted">${V2.plural(WNA.storiesInTab(t.id).length, 'novel')}</span></div>
        <button class="ib" data-a="up" ${i ? '' : 'disabled style="opacity:.3"'} aria-label="Move up">${ic('arrow_upward')}</button><button class="ib" data-a="down" ${i < tabs.length - 1 ? '' : 'disabled style="opacity:.3"'} aria-label="Move down">${ic('arrow_downward')}</button><button class="ib" data-a="ren" aria-label="Rename">${ic('edit')}</button><button class="ib" data-a="del" aria-label="Delete">${ic('delete')}</button></section>`).join('')}</div>`);
    ctx.el.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-a]'); if (!a) return; const c = a.closest('[data-t]'); const i = c ? +c.dataset.t : -1;
      switch (a.dataset.a) {
        case 'add': { const v = ctx.el.querySelector('[data-new]').value.trim(); if (!v) { V2.snack('Type a name first'); return; } tabs.push({ id: 'tab_' + Date.now(), name: v }); V2.snack(`Added “${v}”`); rerender('tabs'); break; }
        case 'up': case 'down': { const j = a.dataset.a === 'up' ? i - 1 : i + 1; [tabs[i], tabs[j]] = [tabs[j], tabs[i]]; rerender('tabs'); break; }
        case 'ren': { const v = await V2.prompt({ title: 'Rename tab', fields: [{ label: 'Tab name', value: tabs[i].name }] }); if (v && v[0]) { tabs[i].name = v[0]; rerender('tabs'); } break; }
        case 'del': { const t = tabs[i]; const n = WNA.storiesInTab(t.id).length; if (await V2.confirm({ title: `Delete “${t.name}”?`, text: n ? `${V2.plural(n, 'novel')} will move to Unassigned.` : 'This tab is empty.', ok: 'Delete', danger: true, icon: 'delete' })) { tabs.splice(i, 1); V2.snack(`Deleted “${t.name}”`); rerender('tabs'); } break; }
      }
    });
  };

  // ---------- Text cleanup ----------
  const cu = { mode: 'sentences' };
  P.cleanup = (ctx) => {
    const C = S.cleanup || (S.cleanup = { regex: [], sentences: [] });
    const PRESETS = [{ name: 'Remove “Next chapter” links', pattern: '^\\s*(next|previous) chapter\\s*$', flags: 'gim' }, { name: 'Strip author notes in brackets', pattern: '^\\[A\\/N:.*\\]$', flags: 'gm' }, { name: 'Collapse blank lines', pattern: '\\n{3,}', flags: 'g' }];
    V2.subPage(ctx, 'Text cleanup', 'Fix downloaded text before reading and narration', `
      <div class="wrap"><div class="seg l"><button class="${cu.mode === 'sentences' ? 'sel' : ''}" data-m="sentences">${ic('check')}Full sentences</button><button class="${cu.mode === 'regex' ? 'sel' : ''}" data-m="regex">${ic('check')}Regex rules</button></div></div>
      ${cu.mode === 'sentences' ? `<p class="sec-hint" style="padding-top:14px">Exact sentences to remove wherever they appear, like anti-piracy notices.</p>
        <div class="wrap"><section class="card col" style="gap:10px"><label class="tf"><label>Sentence to remove</label><textarea data-sent rows="2" placeholder="Paste the full sentence"></textarea></label><div class="row"><button class="btn tonal" data-a="paste">${ic('content_paste')}Paste</button><button class="btn filled" data-a="addsent">${ic('add')}Add</button></div></section></div>
        <div class="sec-h"><h2>Saved sentences · ${C.sentences.length}</h2></div>
        <div class="seglist">${C.sentences.map((x, i) => `<div class="li"><span class="tx"><span class="clamp2" style="color:var(--on-surface)">${esc(x)}</span></span>${V2.ib('delete', 'delsent', 'Delete').replace('data-a="delsent"', `data-a="delsent" data-i="${i}"`)}</div>`).join('')}</div>`
        : `<div class="wrap col" style="gap:10px;padding-top:16px">${C.regex.map((r, i) => `<section class="card mid rx ${r.enabled ? '' : 'off'}"><div class="row"><b class="t-tm grow">${esc(r.name)}</b><span class="pill ${r.enabled ? 'ongoing' : ''}">${r.enabled ? 'Enabled' : 'Disabled'}</span></div><p class="t-bs muted">${r.appliesTo === 'both' ? 'Read aloud and downloaded text' : esc(r.appliesTo)}</p><code>/${esc(r.pattern)}/${esc(r.flags)}</code>
          <div class="row"><button class="btn text s" data-a="editrx" data-i="${i}">Edit</button><button class="btn text s" data-a="togrx" data-i="${i}">${r.enabled ? 'Disable' : 'Enable'}</button><button class="btn danger-text s" data-a="delrx" data-i="${i}">Delete</button></div></section>`).join('')}
          <button class="btn tonal l block" data-a="preset">${ic('library_add')}Add preset</button><button class="btn outlined l block" data-a="newrx">${ic('add')}New rule</button></div>`}`);
    ctx.el.addEventListener('click', async (e) => {
      const m = e.target.closest('[data-m]'); if (m) { cu.mode = m.dataset.m; rerender('cleanup'); return; }
      const a = e.target.closest('[data-a]'); if (!a) return; const i = +a.dataset.i;
      switch (a.dataset.a) {
        case 'paste': ctx.el.querySelector('[data-sent]').value = 'This chapter was stolen from Royal Road. Please read it on the original site.'; break;
        case 'addsent': { const v = ctx.el.querySelector('[data-sent]').value.trim(); if (!v) { V2.snack('Paste or type a sentence first'); return; } C.sentences.unshift(v); V2.snack('Sentence added'); rerender('cleanup'); break; }
        case 'delsent': { const [x] = C.sentences.splice(i, 1); V2.snack('Sentence removed', { action: 'Undo', onAction: () => { C.sentences.splice(i, 0, x); rerender('cleanup'); } }); rerender('cleanup'); break; }
        case 'togrx': C.regex[i].enabled = !C.regex[i].enabled; rerender('cleanup'); break;
        case 'delrx': if (await V2.confirm({ title: 'Delete rule?', text: C.regex[i].name, ok: 'Delete', danger: true, icon: 'delete' })) { C.regex.splice(i, 1); rerender('cleanup'); } break;
        case 'editrx': case 'newrx': { const r = C.regex[i] || { name: '', pattern: '', flags: 'g', enabled: true, appliesTo: 'both' }; const v = await V2.prompt({ title: a.dataset.a === 'newrx' ? 'New rule' : 'Edit rule', fields: [{ label: 'Name', value: r.name }, { label: 'Pattern', value: r.pattern }, { label: 'Flags', value: r.flags }] }); if (v && v[1]) { Object.assign(r, { name: v[0] || 'Untitled rule', pattern: v[1], flags: v[2] }); if (!C.regex.includes(r)) C.regex.push(r); rerender('cleanup'); } break; }
        case 'preset': { const v = await V2.choose({ title: 'Add preset', options: PRESETS.map((p, k) => ({ value: k, label: p.name, sub: `/${p.pattern}/${p.flags}` })) }); if (v != null) { C.regex.push({ ...PRESETS[v], id: 'rule_' + Date.now(), enabled: true, appliesTo: 'both' }); V2.snack('Preset added'); rerender('cleanup'); } break; }
      }
    });
  };

  // ---------- Notifications ----------
  const nt = { downloads: true, tts: true, narration: true, ai: true };
  P.notifications = (ctx) => {
    V2.subPage(ctx, 'Notifications', '', `
      <div class="wrap"><section class="card row"><span class="pat-ic" style="background:color-mix(in oklch,var(--success) 25%,transparent);color:var(--success)">${ic('notifications_active', 'fill s20')}</span><div class="grow"><b class="t-tm">App permission</b><p class="t-bs muted">Allowed · managed in Android settings</p></div><button class="btn text s" data-a="sys">Open</button></section></div>
      <div class="sec-h"><h2>Categories</h2></div>
      <div class="seglist">${V2.swRow('downloads', 'Downloads', 'Progress and finished downloads', nt.downloads, 'download')}${V2.swRow('tts', 'Text to speech', 'Playback controls on the lock screen', nt.tts, 'headphones')}${V2.swRow('narration', 'Narration audio', 'Generating chapters in the background', nt.narration, 'graphic_eq')}${V2.swRow('ai', 'AI generation', 'When a cover or description is ready', nt.ai, 'auto_awesome')}</div>`);
    ctx.el.addEventListener('click', (e) => {
      const sw = e.target.closest('[data-sw]'); if (sw) { const k = sw.dataset.sw; nt[k] = !nt[k]; sw.querySelector('.switch').classList.toggle('on', nt[k]); sw.querySelector('.tx span').textContent = nt[k] ? sw.querySelector('.tx span').textContent.replace(/^Disabled · /, '') : 'Disabled · ' + sw.querySelector('.tx span').textContent; return; }
      if (e.target.closest('[data-a="sys"]')) V2.snack('Opening Android notification settings…');
    });
  };

  // ---------- Download settings ----------
  const srcOv = {};
  P.downloads = (ctx) => {
    const D = S.downloads;
    V2.subPage(ctx, 'Download settings', '', `
      <div class="wrap col" style="gap:12px">
        <section class="card row"><div class="grow"><b class="t-tm">Parallel sources</b><p class="t-bs muted">Different sites downloading at once</p></div><button class="ib tonal" data-a="pmin">${ic('remove')}</button><b class="t-tl par-v" style="min-width:28px;text-align:center">${D.parallel}</b><button class="ib tonal" data-a="pplus">${ic('add')}</button></section>
        <section class="card"><div class="row"><b class="t-tm grow">Delay between chapters</b><b class="t-ll accent dl-v">${D.delayMin}–${D.delayMax} ms</b></div><p class="t-bs muted" style="margin:4px 0 8px">A random wait in this range keeps sites happy.</p>
          <span class="t-lm dim">Minimum</span>${V2.sliderHtml('dmin', D.delayMin, 0, 5000)}<span class="t-lm dim">Maximum</span>${V2.sliderHtml('dmax', D.delayMax, 0, 8000)}</section>
        <section class="card"><div class="row"><b class="t-tm grow">Max chapters per EPUB</b><b class="t-tl ep-v">${D.maxPerEpub}</b></div><p class="t-bs muted" style="margin:4px 0 8px">Longer stories are split into several files.</p>${V2.sliderHtml('epub', D.maxPerEpub, 25, 500)}</section>
      </div>
      <div class="sec-h"><h2>Per-source overrides</h2></div>
      <div class="seglist">${WNA.sources.map((s, i) => `<button class="li" data-src="${i}"><span class="lead src-av" style="--h:${(i * 57 + 30) % 360}">${esc(s.name[0])}</span><span class="tx"><b>${esc(s.name)}</b><span>${srcOv[s.name] ? esc(srcOv[s.name]) : 'Uses the defaults'}</span></span>${ic('chevron_right')}</button>`).join('')}</div>`);
    const upd = () => { ctx.el.querySelector('.dl-v').textContent = `${D.delayMin}–${D.delayMax} ms`; };
    V2.bindSliders(ctx.el, {
      dmin: { min: 0, max: 5000, step: 100, fmt: (v) => `${v} ms`, set: (v) => { D.delayMin = v; if (D.delayMax < v) D.delayMax = v; upd(); } },
      dmax: { min: 0, max: 8000, step: 100, fmt: (v) => `${v} ms`, set: (v) => { D.delayMax = Math.max(v, D.delayMin); upd(); } },
      epub: { min: 25, max: 500, step: 25, fmt: (v) => `${v} chapters`, set: (v) => { D.maxPerEpub = v; ctx.el.querySelector('.ep-v').textContent = v; } },
    });
    ctx.el.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-a]');
      if (a) { D.parallel = V2.clamp(D.parallel + (a.dataset.a === 'pplus' ? 1 : -1), 1, 6); ctx.el.querySelector('.par-v').textContent = D.parallel; return; }
      const s = e.target.closest('[data-src]'); if (!s) return; const src = WNA.sources[+s.dataset.src];
      const v = await V2.choose({ title: src.name, sub: 'Delay between chapters for this site', value: srcOv[src.name] || 'default', options: [{ value: 'default', label: 'Use the defaults' }, { value: 'Slower · 3–6 s between chapters', label: 'Slower', sub: '3–6 s between chapters' }, { value: 'Gentle · 8–15 s between chapters', label: 'Gentle', sub: '8–15 s, for sites that rate-limit' }, { value: 'Faster · 0.3–1 s between chapters', label: 'Faster', sub: '0.3–1 s between chapters' }] });
      if (v) { srcOv[src.name] = v === 'default' ? null : v; s.querySelector('.tx span').textContent = srcOv[src.name] || 'Uses the defaults'; }
    });
  };

  // ---------- App updates ----------
  P.update = (ctx) => {
    const A = S.app;
    V2.subPage(ctx, 'App updates', '', `
      <div class="wrap col" style="gap:12px"><section class="card upd-card"><span class="set-logo big">${ic('auto_stories', 'fill s40')}</span><b class="t-hs">Webnovel Archiver</b><span class="t-bm muted">Installed version ${esc(A.version)}</span>
        <p class="upd-st"><span class="ok">${ic('check_circle', 'fill s18')}</span>You have the latest version · Checked ${esc(A.checkedAgo)}</p><button class="btn filled l" data-a="check">${ic('refresh')}Check for updates</button></section>
        <div class="seglist" style="margin:0">${V2.swRow('auto', 'Check automatically', 'Once a day, over Wi-Fi', A.autoCheck, 'update')}<button class="li" data-a="notes"><span class="lead">${ic('article')}</span><span class="tx"><b>What’s new in ${esc(A.version)}</b><span>Release notes</span></span>${ic('chevron_right')}</button></div></div>`);
    ctx.el.addEventListener('click', async (e) => {
      if (e.target.closest('[data-sw]')) { A.autoCheck = !A.autoCheck; ctx.el.querySelector('.switch').classList.toggle('on', A.autoCheck); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      if (a.dataset.a === 'check') { const st = ctx.el.querySelector('.upd-st'); a.disabled = true; st.innerHTML = '<span class="loader accent" style="width:18px;height:18px"></span>Checking GitHub releases…'; await V2.wait(1800); A.checkedAgo = 'just now'; st.innerHTML = `<span class="ok">${ic('check_circle', 'fill s18')}</span>You have the latest version · Checked just now`; a.disabled = false; }
      if (a.dataset.a === 'notes') V2.sheet({ title: `What’s new in ${A.version}`, body: `<div class="pad col" style="gap:10px">${['A memory-aware narration server for generating on your computer', 'Narration now works with the macOS server', 'A side-by-side beta track for trying new builds', 'Smaller fixes to downloads and the reader'].map((x) => `<p class="row" style="align-items:flex-start">${ic('check', 's20 accent')}<span>${x}</span></p>`).join('')}</div>` });
    });
  };
})();
