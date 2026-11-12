/* v0 secondary settings screens. */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon, S = W.settings;
  const shell = (title, inner, sub) => W.el(`<div>${A.appBar({ title, sub })}<div class="scroll pb-mini" data-keep data-sc><div class="pad capped">${inner}</div></div></div>`);
  const sw = (k, label, sub, on) => `<button class="row sw-row" data-sw="${k}"><div class="rt"><b>${label}</b>${sub ? `<span>${sub}</span>` : ''}</div><span class="switch${on ? ' on' : ''}"></span></button>`;
  const flipSwitches = (el, state) => el.addEventListener('click', (e) => { const s = e.target.closest('[data-sw]'); if (s) { state[s.dataset.sw] = !state[s.dataset.sw]; s.querySelector('.switch').classList.toggle('on', state[s.dataset.sw]); } });

  const pages = {
    ai() {
      const m = W.ai.models, sp = W.ai.spend;
      const model = (k, l) => `<div class="field-label">${l}</div><div class="ai-model"><button class="field dropdown" data-model="${k}"><span>${esc(m[k])}</span>${ic('expand_more')}</button><button class="pill plain" data-reason="${k}" style="height:34px">Reasoning: ${esc(m.reasoning)}</button></div>`;
      const el = shell('AI Settings', `<div class="sec-label" style="margin-top:4px">OpenRouter</div><div class="field-label">OpenRouter API key</div><div class="field"><input type="password" value="sk-or-v1-••••••••••••••••••••" data-key></div>
        <div class="sec-label">Models</div>${model('description', 'Description model')}${model('image', 'Cover image model')}${model('decision', 'Chapter selection model')}
        <div class="field-label">Chapter scan limit</div><div class="field"><input value="40" inputmode="numeric"></div>
        <div class="sec-label">AI spend</div><div class="card"><div class="spend"><div><b>$${sp.today.toFixed(2)}</b><span>Today</span></div><div><b>$${sp.month.toFixed(2)}</b><span>This month</span></div><div><b>$${sp.allTime.toFixed(2)}</b><span>All time</span></div></div>
          <div class="divider"></div><div style="font-weight:700;margin-bottom:6px">Recent requests</div>${sp.recent.map((r) => `<div class="spend-row"><div><b>${esc(r.feature)}</b><span class="muted">${esc(r.model)} · ${W.fmt.ago(r.at)} · ${W.fmt.int(r.tokens)} tokens</span></div><b>$${r.cost.toFixed(3)}</b></div>`).join('')}
          <div class="divider"></div><div class="card-sub" data-usage>Current OpenRouter key usage: $6.37 of $20.00 limit</div><button class="btn text sm" data-a="refresh" style="padding:0">${ic('refresh')}Refresh</button></div>
        <button class="btn filled full" data-a="save" style="margin-top:20px">Save</button>`);
      el.addEventListener('click', (e) => {
        const md = e.target.closest('[data-model]'); const rs = e.target.closest('[data-reason]'); const a = e.target.closest('[data-a]');
        if (md) { const k = md.dataset.model; const opts = k === 'image' ? ['google/gemini-2.5-flash-image', 'openai/gpt-image-1', 'black-forest-labs/flux-1.1-pro'] : ['google/gemini-2.5-flash', 'openai/gpt-5-mini', 'anthropic/claude-haiku-4.5', 'deepseek/deepseek-chat']; A.choose({ title: md.previousElementSibling ? 'Model' : 'Model', options: opts.map((o) => ({ value: o, label: o })), value: m[k], fn: (v) => { m[k] = v; md.querySelector('span').textContent = v; } }); }
        if (rs) A.choose({ title: 'Reasoning', options: ['Default', 'Off', 'Low', 'Medium', 'High'].map((o) => ({ value: o, label: o })), value: m.reasoning, fn: (v) => { m.reasoning = v; el.querySelectorAll('[data-reason]').forEach((b) => (b.textContent = 'Reasoning: ' + v)); } });
        if (a && a.dataset.a === 'refresh') { el.querySelector('[data-usage]').textContent = 'Current OpenRouter key usage: $6.41 of $20.00 limit · just now'; A.toast('Usage refreshed'); }
        if (a && a.dataset.a === 'save') A.toast('AI settings saved');
      });
      return el;
    },
    data() {
      const act = (k, icn, l, sub) => `<button class="row" data-a="${k}" style="padding:8px 0">${ic(icn)}<div class="rt"><b style="font-size:16px">${l}</b>${sub ? `<span>${sub}</span>` : ''}</div></button>`;
      const el = shell('Data & Backup', `<div class="sec-label" style="margin-top:4px">Backup</div>
        ${act('export', 'upload', 'Export Backup', 'Library, tabs, and settings as JSON')}${act('import', 'download', 'Import Backup', 'Merge a JSON backup into this library')}${act('full', 'inventory_2', 'Create Full Backup', 'Everything, including chapters and audio')}${act('restore', 'settings_backup_restore', 'Restore Full Backup')}${act('recover', 'healing', 'Recover Chapters from Backup')}
        <div class="sec-label">Backup Files</div><div data-backups></div>
        <div class="sec-label">Sources &amp; Storage</div>
        <div class="card" style="margin-bottom:8px"><h3>Source Access Status</h3><div class="card-sub">Ready • WebView 142.0 · cookies saved for RoyalRoad, SpaceBattles</div></div>
        ${act('logs', 'share', 'Share Activity Logs')}${act('slogs', 'share', 'Share Source Access Logs')}${act('patreon', 'account_circle', 'Patreon Account', 'Signed in')}${act('reset', 'restart_alt', 'Reset Source Web Session')}
        <div class="sec-label" style="color:var(--error)">Danger Zone</div><button class="btn filled-danger full" data-a="clear">${ic('delete_forever')}Clear Local Storage</button>`);
      const drawB = () => { el.querySelector('[data-backups]').innerHTML = S.backups.length ? S.backups.map((b, i) => `<div class="card bk"><div><b>${esc(b.label)}</b><div class="card-sub">${esc(b.size)} · ${W.fmt.date(b.at)}</div></div><div class="btn-row"><button class="btn text sm" data-share="${i}">Share</button><button class="btn text sm danger" data-del="${i}">Delete</button></div></div>`).join('') : '<div class="sec-help">No backup files yet.</div>'; };
      drawB();
      el.addEventListener('click', (e) => {
        const sh = e.target.closest('[data-share]'), dl = e.target.closest('[data-del]'), a = e.target.closest('[data-a]');
        if (sh) A.toast('Sharing backup…');
        if (dl) A.confirm({ title: 'Delete backup?', message: 'This file will be removed from the device.', ok: 'Delete', danger: true, fn: () => { S.backups.splice(+dl.dataset.del, 1); drawB(); } });
        if (!a) return; const k = a.dataset.a;
        const msgs = { export: 'Backup exported', import: 'Choose a backup file…', logs: 'Sharing activity logs…', slogs: 'Sharing source access logs…', patreon: 'Opening Patreon account…', recover: 'Choose a full backup to recover chapters from…' };
        if (msgs[k]) A.toast(msgs[k]);
        if (k === 'full') { A.toast('Creating full backup…'); setTimeout(() => { S.backups.unshift({ label: 'Full backup', size: '418 MB', at: Date.now() }); drawB(); A.toast('Full backup created'); }, 2000); }
        if (k === 'restore') A.confirm({ title: 'Restore Full Backup', message: 'This replaces your current library with the backup.', ok: 'Restore', danger: true, fn: () => A.toast('Choose a full backup file…') });
        if (k === 'reset') A.confirm({ title: 'Reset Source Web Session', message: 'Signs you out of source sites and clears their cookies.', ok: 'Reset', danger: true, fn: () => A.toast('Web session reset') });
        if (k === 'clear') A.confirm({ title: 'Clear Local Storage?', message: 'This deletes every novel, chapter, audio file, and setting on this device. It cannot be undone.', ok: 'Clear everything', danger: true, fn: () => A.toast('Not cleared in the prototype') });
      });
      return el;
    },
    tabs() {
      const el = shell('Manage Tabs', `<p class="sec-help" style="margin-top:4px">Tabs group your library. Novels can be in one tab; the rest stay Unassigned.</p>
        <div class="add-url"><div class="field"><input data-name placeholder="New tab name"></div><button class="btn filled" data-a="add" style="min-height:56px">Add</button></div><div data-list style="margin-top:16px"></div>`);
      const tabs = W.tabs();
      const draw = () => { el.querySelector('[data-list]').innerHTML = tabs.map((t, i) => `<div class="card tab-card"><div style="flex:1;min-width:0"><b>${esc(t.name)}</b><div class="card-sub">${W.storiesInTab(t.id).length} novels</div></div>
        <button class="icon-btn" data-up="${i}" ${i ? '' : 'disabled'} aria-label="Move up">${ic('arrow_upward')}</button><button class="icon-btn" data-down="${i}" ${i < tabs.length - 1 ? '' : 'disabled'} aria-label="Move down">${ic('arrow_downward')}</button><button class="icon-btn" data-ren="${i}" aria-label="Rename">${ic('edit')}</button><button class="icon-btn" data-delt="${i}" aria-label="Delete">${ic('delete')}</button></div>`).join(''); };
      draw();
      el.addEventListener('click', (e) => {
        const g = (q) => e.target.closest(q); let x;
        if ((x = g('[data-up]'))) { const i = +x.dataset.up; [tabs[i - 1], tabs[i]] = [tabs[i], tabs[i - 1]]; draw(); }
        else if ((x = g('[data-down]'))) { const i = +x.dataset.down; [tabs[i + 1], tabs[i]] = [tabs[i], tabs[i + 1]]; draw(); }
        else if ((x = g('[data-ren]'))) { const t = tabs[+x.dataset.ren]; A.prompt({ title: 'Rename tab', label: 'Tab name', value: t.name, fn: (v) => { if (v.trim()) { t.name = v.trim(); draw(); } } }); }
        else if ((x = g('[data-delt]'))) { const i = +x.dataset.delt; A.confirm({ title: `Delete "${esc(tabs[i].name)}"?`, message: 'Its novels move to Unassigned.', ok: 'Delete', danger: true, fn: () => { tabs.splice(i, 1); draw(); } }); }
        else if (g('[data-a="add"]')) { const inp = el.querySelector('[data-name]'); const v = inp.value.trim(); if (!v) { A.toast('Enter a tab name'); return; } tabs.push({ id: 'tab_' + Date.now(), name: v }); inp.value = ''; draw(); A.toast('Tab added'); }
      });
      return el;
    },
    cleanup() {
      const c = S.cleanup || { regex: [], sentences: [] };
      let mode = 'sentences';
      const el = shell('Text Cleanup Rules', '<div data-body></div>');
      const draw = () => {
        el.querySelector('[data-body]').innerHTML = `<div class="chip-row" style="margin-bottom:14px"><button class="chip${mode === 'sentences' ? ' sel' : ''}" data-mode="sentences">${mode === 'sentences' ? ic('check') : ''}Full sentences</button><button class="chip${mode === 'regex' ? ' sel' : ''}" data-mode="regex">${mode === 'regex' ? ic('check') : ''}Regex rules</button></div>
          ${mode === 'sentences' ? `<p class="sec-help">Sentences listed here are removed wherever they appear, in read-aloud and downloaded text.</p><div class="field area"><textarea data-sent placeholder="Sentence to remove"></textarea></div>
            <div class="btn-row" style="margin-top:10px"><button class="btn text" data-a="paste">${ic('content_paste')}Paste</button><button class="btn tonal" data-a="addsent">${ic('add')}Add</button></div>
            <div class="sec-label">Saved sentences · ${c.sentences.length}</div>${c.sentences.map((s, i) => `<div class="card cl-sent"><div style="flex:1">${esc(s)}</div><button class="icon-btn" data-dels="${i}" aria-label="Delete">${ic('delete')}</button></div>`).join('')}`
          : `${c.regex.map((r, i) => `<div class="card"><h3>${esc(r.name)}</h3><div class="card-sub">${r.enabled ? 'Enabled' : 'Disabled'} · ${r.appliesTo === 'both' ? 'Read aloud and downloaded text' : esc(r.appliesTo)}</div><code class="cl-code">/${esc(r.pattern)}/${esc(r.flags)}</code>
              <div class="btn-row"><button class="btn text sm" data-edit="${i}" style="padding-left:0">Edit</button><button class="btn text sm" data-tog="${i}">${r.enabled ? 'Disable' : 'Enable'}</button><button class="btn text sm danger" data-delr="${i}">Delete</button></div></div>`).join('')}
            <div class="btn-row" style="margin-top:14px"><button class="btn tonal" data-a="newrule">${ic('add')}Add rule</button><button class="btn text" data-a="preset">${ic('library_add')}Add preset</button></div>`}`;
      };
      draw();
      el.addEventListener('click', (e) => {
        const g = (q) => e.target.closest(q); let x;
        if ((x = g('[data-mode]'))) { mode = x.dataset.mode; draw(); }
        else if ((x = g('[data-dels]'))) { c.sentences.splice(+x.dataset.dels, 1); draw(); A.toast('Sentence removed'); }
        else if ((x = g('[data-tog]'))) { const r = c.regex[+x.dataset.tog]; r.enabled = !r.enabled; draw(); }
        else if ((x = g('[data-delr]'))) { const i = +x.dataset.delr; A.confirm({ title: 'Delete rule?', message: esc(c.regex[i].name), ok: 'Delete', danger: true, fn: () => { c.regex.splice(i, 1); draw(); } }); }
        else if ((x = g('[data-edit]'))) { const r = c.regex[+x.dataset.edit]; A.prompt({ title: 'Edit rule', label: 'Pattern', value: r.pattern, fn: (v) => { r.pattern = v; draw(); } }); }
        else if ((x = g('[data-a]'))) {
          const k = x.dataset.a;
          if (k === 'paste') { el.querySelector('[data-sent]').value = 'If you enjoy this story, please consider leaving a review on Royal Road!'; }
          if (k === 'addsent') { const v = el.querySelector('[data-sent]').value.trim(); if (!v) { A.toast('Enter a sentence'); return; } c.sentences.unshift(v); draw(); A.toast('Sentence added'); }
          if (k === 'newrule') A.prompt({ title: 'New regex rule', label: 'Pattern', value: '', ok: 'Add', fn: (v) => { if (v) { c.regex.push({ name: 'Custom rule', pattern: v, flags: 'g', enabled: true, appliesTo: 'both' }); draw(); } } });
          if (k === 'preset') A.choose({ title: 'Add preset', options: ['Remove separator lines (3+ symbols)', 'Shorten any repeated character (keep 5)', 'Remove "Author\'s note" blocks', 'Remove Patreon plugs'].map((o) => ({ value: o, label: o })), value: null, fn: (v) => { c.regex.push({ name: v, pattern: '^Author.?s note.*$', flags: 'gim', enabled: true, appliesTo: 'both' }); draw(); A.toast('Preset added'); } });
        }
      });
      return el;
    },
    notifications() {
      const st = { downloads: true, tts: true, narration: true, ai: true };
      const el = shell('Notifications', `<div class="card"><h3>App Permission</h3><div class="card-sub">Allowed. The app can show notifications.</div><button class="btn text sm" data-a="sys" style="padding:0;margin-top:6px">Open system settings</button></div>
        <div class="sec-label">Categories</div>${sw('downloads', 'Downloads', 'Progress and finished downloads · Enabled', true)}${sw('tts', 'Text to Speech', 'Playback controls · Enabled', true)}${sw('narration', 'Narration audio', 'Chapter audio generation · Enabled', true)}${sw('ai', 'AI Generation', 'Cover and description jobs · Enabled', true)}`);
      flipSwitches(el, st);
      el.addEventListener('click', (e) => { if (e.target.closest('[data-a="sys"]')) A.toast('Opening system notification settings…'); });
      return el;
    },
    downloads() {
      const d = S.downloads;
      const num = (k, l, sub) => `<div class="field-label">${l}</div><div class="field"><input data-n="${k}" value="${d[k]}" inputmode="numeric"></div>${sub ? `<div class="sec-help" style="margin-top:4px">${sub}</div>` : ''}`;
      const el = shell('Download Settings', `${num('parallel', 'Parallel sources', 'How many sites download at the same time.')}${num('delayMin', 'Delay min (ms)')}${num('delayMax', 'Delay max (ms)', 'A random wait between chapters keeps sites from limiting you.')}${num('maxPerEpub', 'Max chapters per EPUB')}
        <div class="sec-label">Per-source overrides</div>${['RoyalRoad', 'SpaceBattles', 'Scribble Hub'].map((s, i) => `<button class="row" data-src="${s}" style="padding:8px 0">${ic('language')}<div class="rt"><b style="font-size:16px">${s}</b><span>${i === 1 ? 'Delay 3000–6000 ms' : 'Uses the defaults'}</span></div>${ic('chevron_right')}</button>`).join('')}
        <button class="btn filled full" data-a="save" style="margin-top:20px">Save</button>`);
      el.addEventListener('input', (e) => { const k = e.target.dataset.n; if (k) d[k] = +e.target.value || 0; });
      el.addEventListener('click', (e) => { const s = e.target.closest('[data-src]'); if (s) A.dialog({ title: s.dataset.src, body: '<div class="field-label">Delay min (ms)</div><div class="field"><input value="3000"></div><div class="field-label">Delay max (ms)</div><div class="field"><input value="6000"></div>', actions: [{ label: 'Use defaults' }, { label: 'Save', fn: () => A.toast('Override saved') }] }); if (e.target.closest('[data-a="save"]')) A.toast('Download settings saved'); });
      return el;
    },
    update() {
      const a = S.app;
      const el = shell('App Updates', `<div class="card"><div class="card-sub">Installed version</div><h3 style="font-size:22px;margin:2px 0 8px">${esc(a.version)}</h3><div data-status class="gold">You have the latest version · Checked ${esc(a.checkedAgo)}</div>
        <button class="btn filled full" data-a="check" style="margin-top:16px">${ic('refresh')}Check for updates</button></div>
        <div style="margin-top:8px">${sw('autoCheck', 'Check automatically', 'Looks for a new release when the app starts', a.autoCheck)}</div>`);
      flipSwitches(el, a);
      el.addEventListener('click', (e) => { const b = e.target.closest('[data-a="check"]'); if (!b) return; b.innerHTML = '<span class="spinner"></span>Checking...'; b.classList.add('disabled'); setTimeout(() => { a.checkedAgo = 'just now'; el.querySelector('[data-status]').textContent = 'You have the latest version · Checked just now'; b.innerHTML = `${ic('refresh')}Check for updates`; b.classList.remove('disabled'); }, 1600); });
      return el;
    },
  };
  A.settingsPage = (page) => {
    const fn = pages[page];
    if (!fn) return { el: W.el(`<div>${A.appBar({ title: 'Settings' })}<div class="empty"><b>Page not found</b></div></div>`) };
    return { el: fn() };
  };
})();
