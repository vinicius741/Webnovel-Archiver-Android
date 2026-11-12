/* v4 Editorial — secondary Settings pages and the /settings/{page} dispatcher. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon;
  const ST = W.settings;
  const head = (kicker, title, dek) => `<div class="set-h"><div class="kicker">${kicker}</div><h1 class="disp">${title}</h1>${dek ? `<p class="dek">${dek}</p>` : ''}</div>`;

  const pages = {
    tts: (p, r) => A.screens.settingsTts(p, r),

    ai(page) {
      const m = W.ai.models; const sp = W.ai.spend;
      const ui = { show: false, scan: 40, usage: '$6.37 of $20.00 limit' };
      const MODELS = ['google/gemini-2.5-flash', 'google/gemini-2.5-pro', 'openai/gpt-5-mini', 'anthropic/claude-haiku-5-5', 'google/gemini-2.5-flash-image', 'openai/gpt-image-1'];
      const R = ['Default', 'Off', 'Low', 'High'];
      const reason = { description: 'Default', image: 'Off', decision: 'Low' };
      const render = () => {
        page.innerHTML = `${A.bar({ title: 'AI Settings', sub: 'OpenRouter' })}<div class="set-wrap">
          ${head('Settings · AI', 'The <i>scriptorium</i>', 'Covers and descriptions are drafted by models you choose, billed to your OpenRouter key.')}
          ${A.secHead('i', 'OpenRouter API key')}
          <div class="input-row"><input class="input" type="${ui.show ? 'text' : 'password'}" value="sk-or-v1-6f1c0e2b8d4a9e71c3" aria-label="API key" style="font-family:ui-monospace,monospace;font-size:14px"><button class="btn tonal" data-act="eye" aria-label="Show key">${I(ui.show ? 'visibility_off' : 'visibility')}</button></div>
          ${A.secHead('ii', 'Models')}
          <div class="idx">${[['description', 'Description model', m.description], ['image', 'Cover image model', m.image], ['decision', 'Chapter selection model', m.decision]].map(([k, l, v]) => `<div class="idx-row"><button class="grow" data-act="model" data-k="${k}" style="text-align:left;min-width:0"><span class="l" style="display:block"><b>${l}</b><small>${esc(v)}</small></span></button><button class="reason" data-act="reason" data-k="${k}" aria-label="Reasoning">${I('psychology', 'xs')} ${reason[k]}</button></div>`).join('')}</div>
          <div class="ctl"><div class="top"><b>Chapter scan limit</b><div class="stepper"><button data-act="scan" data-d="-10">${I('remove')}</button><b>${ui.scan}</b><button data-act="scan" data-d="10">${I('add')}</button></div></div><small>How many chapters the AI may read to pick context.</small></div>
          ${A.secHead('iii', 'AI spend')}
          <div class="spend"><div><b>$${sp.today.toFixed(2)}</b><span>Today</span></div><div><b>$${sp.month.toFixed(2)}</b><span>This month</span></div><div><b>$${sp.allTime.toFixed(2)}</b><span>All time</span></div></div>
          <div class="label" style="margin:20px 0 4px">Recent requests</div>
          <div class="ledger">${sp.recent.map((x) => `<div class="lrow"><div class="grow"><b>${esc(x.feature)}</b><small>${esc(x.model)} · ${x.tokens.toLocaleString('en-US')} tokens · ${W.fmt.ago(x.at)}</small></div><span class="st q">$${x.cost.toFixed(3)}</span></div>`).join('')}</div>
          <div class="card"><span class="label">Current OpenRouter key usage</span><h4 style="margin-top:8px" class="usage">${ui.usage}</h4><div class="acts"><button class="btn sm ghost" data-act="refresh">${I('refresh', 'sm')}Refresh</button></div></div>
          <div class="save-bar"><button class="btn block" data-act="save">${I('check')}Save</button></div></div>`;
      };
      render();
      A.acts(page, {
        eye: () => { ui.show = !ui.show; render(); },
        model: (t) => A.menu({ title: 'Choose a <i>model</i>', groups: [{ items: MODELS.map((x) => ({ label: esc(x), on: m[t.dataset.k] === x, act: () => { m[t.dataset.k] = x; render(); } })) }] }),
        reason: (t) => A.menu({ title: 'Reasoning', groups: [{ items: R.map((x) => ({ label: x, on: reason[t.dataset.k] === x, act: () => { reason[t.dataset.k] = x; render(); } })) }] }),
        scan: (t) => { ui.scan = Math.max(10, Math.min(200, ui.scan + +t.dataset.d)); render(); },
        refresh: (t) => { t.innerHTML = '<span class="spin"></span>Checking…'; setTimeout(() => { ui.usage = '$6.41 of $20.00 limit · just now'; render(); }, 1200); },
        save: () => A.toast('AI settings saved'),
      });
    },

    data(page) {
      const run = (label, done) => { A.toast(label + '…'); setTimeout(() => A.toast(done), 1800); };
      const render = () => {
        page.innerHTML = `${A.bar({ title: 'Data &amp; Backup' })}<div class="set-wrap">
          ${head('Settings · Archive', 'Data &amp; <i>backup</i>', `${W.stats.stories} novels · ${W.stats.downloaded.toLocaleString('en-US')} chapters on this phone`)}
          ${A.secHead('i', 'Backup')}
          <div class="idx">${[['upload_file', 'Export Backup', 'Library, tabs and settings as JSON', 'exp'], ['download', 'Import Backup', 'Merge a JSON backup into this library', 'imp'], ['inventory_2', 'Create Full Backup', 'Everything, including chapters and audio', 'full'], ['settings_backup_restore', 'Restore Full Backup', 'Replace this library with a full backup', 'restore'], ['healing', 'Recover Chapters from Backup', 'Fill in chapters the source removed', 'recover']].map(([ic, l, s, k]) => `<button class="idx-row" data-act="${k}">${I(ic, 'lead')}<span class="leaders"><span class="l"><b>${l}</b><small>${s}</small></span></span>${I('chevron_right')}</button>`).join('')}</div>
          ${A.secHead('ii', 'Backup files')}
          ${ST.backups.length ? ST.backups.map((b, i) => `<div class="card"><h4>${esc(b.label)}</h4><p class="meta">${esc(b.size)} · ${W.fmt.dateTime(b.at)}</p><div class="acts"><button class="btn sm ghost" data-act="share">${I('share', 'sm')}Share</button><button class="btn sm text" style="color:var(--err)" data-act="delb" data-i="${i}">Delete</button></div></div>`).join('') : '<p class="sec-hint">No backup files yet.</p>'}
          ${A.secHead('iii', 'Sources &amp; storage')}
          <div class="idx">
            <div class="idx-row">${I('travel_explore', 'lead')}<span class="leaders"><span class="l"><b>Source Access Status</b><small>Ready • WebView 131 · cookies stored for 3 sites</small></span></span></div>
            <button class="idx-row" data-act="share">${I('receipt_long', 'lead')}<span class="leaders"><span class="l"><b>Share Activity Logs</b></span></span>${I('share')}</button>
            <button class="idx-row" data-act="share">${I('lan', 'lead')}<span class="leaders"><span class="l"><b>Share Source Access Logs</b></span></span>${I('share')}</button>
            <button class="idx-row" data-act="patreon">${I('volunteer_activism', 'lead')}<span class="leaders"><span class="l"><b>Patreon Account</b><small>Signed in · used for early-access chapters</small></span></span>${I('chevron_right')}</button>
            <button class="idx-row" data-act="resetWeb">${I('restart_alt', 'lead')}<span class="leaders"><span class="l"><b>Reset Source Web Session</b><small>Clears site cookies and verification</small></span></span></button>
          </div>
          <div class="card danger-zone" style="margin-top:34px"><h4>Danger zone</h4><p>Clear Local Storage deletes every novel, chapter, audio file and setting on this phone.</p><div class="acts"><button class="btn sm danger" data-act="wipe">Clear Local Storage</button></div></div>
        </div>`;
      };
      render();
      A.acts(page, {
        exp: () => run('Exporting backup', 'Backup saved to Downloads · 1.8 MB'),
        imp: () => A.confirm('Import a backup?', 'Novels in the backup are merged with your library. Nothing is deleted.', 'Choose file', () => run('Importing', 'Imported 3 novels · 279 already here')),
        full: () => run('Creating full backup', 'Full backup created · 412 MB'),
        restore: () => A.confirm('Restore a full backup?', 'This replaces everything on this phone with the backup.', 'Choose backup', () => run('Restoring', 'Restore complete'), true),
        recover: () => run('Scanning backups for missing chapters', 'Recovered 12 chapters in 2 novels'),
        share: () => A.toast('Opening the share sheet…'),
        delb: (t) => { const i = +t.dataset.i; const [b] = ST.backups.splice(i, 1); render(); A.toast('Backup deleted', { action: 'Undo', onAction: () => { ST.backups.splice(i, 0, b); render(); } }); },
        patreon: () => A.toast('Patreon: signed in'),
        resetWeb: () => A.confirm('Reset the web session?', 'You may need to verify some sites again.', 'Reset', () => A.toast('Web session reset')),
        wipe: () => A.confirm('Clear all local storage?', 'This cannot be undone. Make a full backup first.', 'Clear everything', () => A.toast('Prototype: storage left intact'), true),
      });
    },

    tabs(page) {
      const tabs = W.tabs();
      const render = () => {
        page.innerHTML = `${A.bar({ title: 'Manage Tabs' })}<div class="set-wrap">
          ${head('Settings · Library', 'Your <i>shelves</i>', 'Tabs group your novels. Their order here is the order in the library.')}
          <div class="input-row" style="margin-top:22px"><input class="input" id="nt" placeholder="New tab name" aria-label="New tab name"><button class="btn" data-act="add">Add</button></div>
          <div style="margin-top:18px;border-top:1px solid var(--rule-2)">${tabs.map((t, i) => `<div class="tabcard"><div class="grow"><b>${esc(t.name)}</b><br><small>${W.storiesInTab(t.id).filter(A.alive).length} novels</small></div>
            <button class="ib" data-act="up" data-i="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Move up">${I('arrow_upward', 'sm')}</button><button class="ib" data-act="down" data-i="${i}" ${i === tabs.length - 1 ? 'disabled' : ''} aria-label="Move down">${I('arrow_downward', 'sm')}</button><button class="ib" data-act="ren" data-i="${i}" aria-label="Rename">${I('edit', 'sm')}</button><button class="ib" data-act="del" data-i="${i}" aria-label="Delete">${I('delete', 'sm')}</button></div>`).join('')}</div></div>`;
      };
      render();
      A.acts(page, {
        add: () => { const v = page.querySelector('#nt').value.trim(); if (!v) { A.toast('Type a name first'); return; } tabs.push({ id: 'tab_' + Date.now(), name: v }); render(); A.toast(`Added “${esc(v)}”`); },
        up: (t) => { const i = +t.dataset.i; [tabs[i - 1], tabs[i]] = [tabs[i], tabs[i - 1]]; render(); },
        down: (t) => { const i = +t.dataset.i; [tabs[i + 1], tabs[i]] = [tabs[i], tabs[i + 1]]; render(); },
        ren: (t) => { const tb = tabs[+t.dataset.i]; A.dialog({ title: 'Rename tab', body: `<input class="input" id="rn" value="${esc(tb.name)}">`, actions: [{ label: 'Cancel' }, { label: 'Rename', act: (n) => { tb.name = n.querySelector('#rn').value.trim() || tb.name; render(); } }] }); },
        del: (t) => { const i = +t.dataset.i; const tb = tabs[i]; A.confirm(`Delete “${esc(tb.name)}”?`, 'Its novels move to Unassigned. Nothing is deleted.', 'Delete', () => { tabs.splice(i, 1); if (A.state.tab === tb.id) A.state.tab = 'all'; render(); A.toast('Tab deleted'); }, true); },
      });
    },

    cleanup(page) {
      const cl = ST.cleanup;
      const ui = { mode: 'sentences' };
      const PRESETS = [['Remove “Support me on Patreon” lines', '^.*patreon\\.com.*$', 'gim'], ['Remove separator lines (3+ symbols)', '^\\h*[\\p{P}\\p{S}]{3,}\\h*$', 'gm'], ['Collapse repeated blank lines', '\\n{3,}', 'g']];
      const render = () => {
        page.innerHTML = `${A.bar({ title: 'Text Cleanup', sub: `${cl.sentences.length} sentences · ${cl.regex.length} rules` })}<div class="set-wrap">
          ${head('Settings · Reading', 'Text <i>cleanup</i>', 'Strip piracy notices and junk before chapters are read or read aloud.')}
          <div class="seg" style="margin-top:20px"><button class="${ui.mode === 'sentences' ? 'on' : ''}" data-act="mode" data-v="sentences">Full sentences</button><button class="${ui.mode === 'regex' ? 'on' : ''}" data-act="mode" data-v="regex">Regex rules</button></div>
          ${ui.mode === 'sentences' ? `
            <textarea class="input" id="ns" style="margin-top:16px;min-height:80px" placeholder="Paste a sentence to remove wherever it appears"></textarea>
            <div class="act-row" style="margin-top:10px"><button class="btn ghost" data-act="paste">${I('content_paste', 'sm')}Paste</button><button class="btn grow" data-act="addS">Add sentence</button></div>
            <div class="label" style="margin:24px 0 4px">Saved sentences · ${cl.sentences.length}</div>
            <div>${cl.sentences.map((s, i) => `<div class="sent"><span class="grow">“${esc(s)}”</span><button class="ib" data-act="delS" data-i="${i}" aria-label="Delete">${I('close', 'sm')}</button></div>`).join('')}</div>`
          : `${cl.regex.map((x, i) => `<div class="rule-card ${x.enabled ? '' : 'off'}"><b>${esc(x.name)}</b><small class="meta dim">${x.enabled ? 'Enabled' : 'Disabled'} · Read aloud and downloaded text</small><code>/${esc(x.pattern)}/${esc(x.flags)}</code><div class="acts"><button class="btn text sm" data-act="editR" data-i="${i}">Edit</button><button class="btn text sm" data-act="togR" data-i="${i}">${x.enabled ? 'Disable' : 'Enable'}</button><button class="btn text sm" style="color:var(--err)" data-act="delR" data-i="${i}">Delete</button></div></div>`).join('')}
            <button class="btn ghost block" data-act="preset" style="margin-top:16px">${I('add')}Add preset</button>`}
        </div>`;
      };
      render();
      A.acts(page, {
        mode: (t) => { ui.mode = t.dataset.v; render(); },
        paste: () => { page.querySelector('#ns').value = 'If you spot this story on Amazon, know that it has been stolen. Report the violation.'; },
        addS: () => { const v = page.querySelector('#ns').value.trim(); if (!v) { A.toast('Paste a sentence first'); return; } cl.sentences.unshift(v); render(); A.toast('Sentence added'); },
        delS: (t) => { const i = +t.dataset.i; const [s] = cl.sentences.splice(i, 1); render(); A.toast('Sentence removed', { action: 'Undo', onAction: () => { cl.sentences.splice(i, 0, s); render(); } }); },
        togR: (t) => { const x = cl.regex[+t.dataset.i]; x.enabled = !x.enabled; render(); },
        delR: (t) => { cl.regex.splice(+t.dataset.i, 1); render(); A.toast('Rule deleted'); },
        editR: (t) => { const x = cl.regex[+t.dataset.i]; A.dialog({ title: 'Edit rule', body: `<label class="field"><span class="lab">Name</span><input class="input" id="rn" value="${esc(x.name)}"></label><label class="field" style="margin-top:12px"><span class="lab">Pattern</span><input class="input" id="rp" value="${esc(x.pattern)}" style="font-family:ui-monospace,monospace;font-size:14px"></label>`, actions: [{ label: 'Cancel' }, { label: 'Save', act: (n) => { x.name = n.querySelector('#rn').value; x.pattern = n.querySelector('#rp').value; render(); } }] }); },
        preset: () => A.menu({ title: 'Add a <i>preset</i>', groups: [{ items: PRESETS.map(([n, p, f]) => ({ icon: 'rule', label: esc(n), sub: `/${esc(p)}/${f}`, act: () => { cl.regex.push({ id: 'r' + Date.now(), name: n, pattern: p, flags: f, enabled: true, appliesTo: 'both' }); render(); A.toast('Rule added'); } })) }] }),
      });
    },

    notifications(page) {
      const cats = [['dl', 'Downloads', 'Progress and failures', true], ['tts', 'Text to Speech', 'Playback controls on the lock screen', true], ['nar', 'Narration audio', 'Generation progress', true], ['ai', 'AI Generation', 'When a cover or description is ready', true]];
      page.innerHTML = `${A.bar({ title: 'Notifications' })}<div class="set-wrap">
        ${head('Settings · Library', '<i>Notices</i>', '')}
        <div class="card accent"><span class="kicker">App permission</span><h4 style="margin-top:8px">Allowed</h4><p>Webnovel Archiver can post notifications.</p><div class="acts"><button class="btn sm ghost" data-act="sys">Open system settings</button></div></div>
        ${A.secHead('', 'Categories')}
        <div style="border-top:1px solid var(--rule-2)">${cats.map(([k, l, s, on]) => A.srow(k, l, `${s} · <span class="st-l">${on ? 'Enabled' : 'Disabled'}</span>`, on)).join('')}</div></div>`;
      A.acts(page, {
        sys: () => A.toast('Opening Android settings…'),
        sw: (b) => { const sw = b.querySelector('.switch'); const on = !sw.classList.contains('on'); sw.classList.toggle('on', on); b.querySelector('.st-l').textContent = on ? 'Enabled' : 'Disabled'; },
      });
    },

    downloads(page) {
      const d = ST.downloads;
      const over = [['RoyalRoad', 'Default pace'], ['SpaceBattles', '2,000–5,000 ms'], ['Scribble Hub', 'Default pace']];
      const step = (k, label, sub, dd, mn, mx, unit = '') => `<div class="ctl"><div class="top"><b>${label}</b><div class="stepper"><button data-act="st" data-k="${k}" data-d="${-dd}" data-mn="${mn}" data-mx="${mx}">${I('remove')}</button><b>${d[k].toLocaleString('en-US')}${unit}</b><button data-act="st" data-k="${k}" data-d="${dd}" data-mn="${mn}" data-mx="${mx}">${I('add')}</button></div></div>${sub ? `<small>${sub}</small>` : ''}</div>`;
      const render = () => {
        page.innerHTML = `${A.bar({ title: 'Download Settings' })}<div class="set-wrap">
          ${head('Settings · Downloads', 'Pace &amp; <i>manners</i>', 'Gentle pacing keeps sources from blocking you.')}
          <div style="margin-top:18px;border-top:1px solid var(--rule-2)">
            ${step('parallel', 'Parallel sources', 'Different sites download at the same time', 1, 1, 5)}
            ${step('delayMin', 'Delay min (ms)', 'Shortest wait between chapters', 100, 0, 10000)}
            ${step('delayMax', 'Delay max (ms)', 'Longest wait between chapters', 100, 0, 20000)}
            ${step('maxPerEpub', 'Max chapters per EPUB', 'Longer novels are split into volumes', 10, 10, 1000)}
          </div>
          ${A.secHead('', 'Per-source overrides')}
          <div class="idx">${over.map(([n, v]) => `<button class="idx-row" data-act="ov" data-n="${n}">${I('language', 'lead')}<span class="leaders"><span class="l"><b>${n}</b></span><span class="d"></span><span class="v">${v}</span></span>${I('chevron_right')}</button>`).join('')}</div></div>`;
      };
      render();
      A.acts(page, {
        st: (t) => { const k = t.dataset.k; d[k] = Math.max(+t.dataset.mn, Math.min(+t.dataset.mx, d[k] + +t.dataset.d)); if (d.delayMin > d.delayMax) d.delayMax = d.delayMin; render(); },
        ov: (t) => A.menu({ title: esc(t.dataset.n), sub: 'Pace for this source', groups: [{ items: ['Default pace', 'Gentle · 2,000–5,000 ms', 'Very gentle · 5,000–12,000 ms'].map((x) => ({ label: x, act: () => { t.querySelector('.v').textContent = x.split(' · ').pop(); A.toast('Override saved'); } })) }] }),
      });
    },

    update(page) {
      const a = ST.app;
      const render = (checking) => {
        page.innerHTML = `${A.bar({ title: 'App Updates' })}<div class="set-wrap">
          ${head('Settings · About', 'Edition <i>' + esc(a.version) + '</i>', '')}
          <dl class="colophon" style="margin-top:18px;border-top:1px solid var(--rule-2);padding-top:8px">${[['Installed version', esc(a.version)], ['Channel', 'Stable'], ['Last checked', esc(a.checkedAgo)]].map(([k, v]) => `<div class="leaders"><dt class="l">${k}</dt><span class="d"></span><dd class="v">${v}</dd></div>`).join('')}</dl>
          <div class="card"><h4>${checking ? 'Checking GitHub Releases…' : 'You have the latest version'}</h4><p>${checking ? 'One moment.' : `Checked ${esc(a.checkedAgo)}`}</p><div class="acts"><button class="btn sm" data-act="check" ${checking ? 'disabled' : ''}>${checking ? '<span class="spin"></span>' : I('refresh', 'sm')}Check for updates</button></div></div>
          <div style="margin-top:14px;border-top:1px solid var(--rule)">${A.srow('auto', 'Check automatically', 'Once a day, on Wi-Fi', a.autoCheck)}</div></div>`;
      };
      render(false);
      A.acts(page, {
        check: () => { render(true); setTimeout(() => { a.checkedAgo = 'just now'; if (A.route.name === 'settingsPage') render(false); A.toast('You’re up to date'); }, 1600); },
        sw: (b) => { a.autoCheck = !a.autoCheck; b.querySelector('.switch').classList.toggle('on', a.autoCheck); },
      });
    },
  };

  A.screens.settingsPage = (page, r) => {
    const fn = pages[r.params.page];
    if (!fn) { page.innerHTML = `${A.bar({ title: 'Settings' })}<div class="empty"><h3>Unknown page</h3><button class="btn ghost" data-go="/settings">All settings</button></div>`; return; }
    return fn(page, r);
  };
})();
