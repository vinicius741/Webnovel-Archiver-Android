/* v3 Restructured — secondary settings pages: AI, Data & Backup, Manage Tabs, Text Cleanup, Notifications, Downloads, App Updates. */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  const P = (A.settingsPages = A.settingsPages || {});
  const bar = (title, sub) => A.appbar({ title, sub, back: true });

  // ---------- AI ----------
  P.ai = (el) => {
    const m = W.ai.models; const sp = W.ai.spend;
    let keyShown = false, refreshing = false, scan = 40, dirty = false;
    const MODELS = { description: ['google/gemini-2.5-flash', 'openai/gpt-5-mini', 'anthropic/claude-haiku'], image: ['google/gemini-2.5-flash-image', 'openai/gpt-image-1'], decision: ['openai/gpt-5-mini', 'google/gemini-2.5-flash'] };
    function render() {
      el.innerHTML = `${bar('AI settings', 'OpenRouter')}<div class="pad narrow">
        <div class="section-label">API key</div>
        <div class="field">${A.icon('key')}<input value="${keyShown ? 'sk-or-v1-8f3c…d21a' : '••••••••••••••••••••'}" readonly><button class="ib" data-a="eye" aria-label="Show key">${A.icon(keyShown ? 'visibility_off' : 'visibility')}</button></div>
        <div class="section-label">Models</div>
        <div class="list">${[['description', 'Description model', 'notes'], ['image', 'Cover image model', 'image'], ['decision', 'Chapter selection model', 'alt_route']].map(([k, l, i]) => `<button class="li" data-model="${k}"><span class="lead">${A.icon(i)}</span><div class="grow"><div class="s">${l}</div><div class="t">${esc(m[k])}</div></div><span class="pillb">Reasoning · ${esc(m.reasoning)}</span></button>`).join('')}</div>
        <div class="section-label">Chapter scan limit</div>
        <div class="list">${A.stepRow('scan', scan, { step: 5, min: 5, max: 120 }, 'Chapters per job', 'How many chapters the AI may read')}</div>
        <div class="section-label">AI spend</div>
        <div class="card pad16"><div class="statgrid"><div><b>$${sp.today.toFixed(2)}</b><span>Today</span></div><div><b>$${sp.month.toFixed(2)}</b><span>This month</span></div><div><b>$${sp.allTime.toFixed(2)}</b><span>All time</span></div></div></div>
        <div class="section-label">Recent requests</div>
        <div class="list">${sp.recent.map((r) => `<div class="li"><span class="lead">${A.icon(r.feature.startsWith('Cover') ? 'image' : 'notes')}</span><div class="grow"><div class="t">${esc(r.feature)}</div><div class="s">${esc(r.model)} · ${fmt.num(r.tokens)} tokens · ${fmt.ago(r.at)}</div></div><span class="val">$${r.cost.toFixed(3)}</span></div>`).join('')}</div>
        <div class="card pad16" style="margin-top:12px"><div class="row"><div class="grow"><b>Current OpenRouter key usage</b><div class="muted" style="font-size:12.5px">$6.37 used · no limit set</div></div><button class="btn sm surface" data-a="refresh">${refreshing ? '<span class="spinner"></span>' : A.icon('refresh', 'sm')}Refresh</button></div></div>
        </div><div class="stickyfoot" ${dirty ? '' : 'hidden'}><button class="btn fill block" data-a="save">${A.icon('save')}Save</button></div>`;
    }
    A.bindSteppers(el, (k, v) => { scan = v; dirty = true; el.querySelector('.stickyfoot').hidden = false; });
    W.on(el, 'click', '[data-model]', (e, b) => { const k = b.dataset.model; A.menu({ title: 'Choose a model', sections: [{ items: MODELS[k].map((x) => ({ icon: 'smart_toy', label: x, on: m[k] === x, fn: () => { m[k] = x; dirty = true; render(); } })) }, { label: 'Reasoning', items: ['Default', 'Low', 'High'].map((x) => ({ icon: 'psychology', label: x, on: m.reasoning === x, fn: () => { m.reasoning = x; dirty = true; render(); } })) }] }); });
    W.on(el, 'click', '[data-a]', (e, b) => {
      const a = b.dataset.a;
      if (a === 'eye') { keyShown = !keyShown; render(); }
      else if (a === 'refresh') { refreshing = true; render(); setTimeout(() => { refreshing = false; render(); A.toast('Usage updated'); }, 1200); }
      else if (a === 'save') { dirty = false; render(); A.toast('AI settings saved'); }
    });
    render();
  };

  // ---------- Data & Backup ----------
  P.data = (el) => {
    const st = W.settings;
    const job = (label, ms = 1800) => { A.toast(`${label}…`); setTimeout(() => A.toast(`${label} finished`), ms); };
    function render() {
      el.innerHTML = `${bar('Data & backup')}<div class="pad narrow">
        <div class="section-label">Backup</div>
        <div class="list">${[['upload_file', 'Export backup', 'Library, tabs and settings as JSON', 'export'], ['download', 'Import backup', 'Merge a JSON backup into this library', 'import'], ['inventory_2', 'Create full backup', 'Everything, including chapters and audio', 'full'], ['settings_backup_restore', 'Restore full backup', 'Replace this library with a full backup', 'restore'], ['healing', 'Recover chapters from backup', 'Bring back chapters a source removed', 'recover']].map(([i, t, s, k]) => `<button class="li" data-b="${k}"><span class="lead">${A.icon(i)}</span><div class="grow"><div class="t">${t}</div><div class="s">${s}</div></div></button>`).join('')}</div>
        <div class="section-label">Backup files</div>
        ${st.backups.map((b, i) => `<div class="card pad16 bfile"><div class="row">${A.icon('folder_zip', 'gold')}<div class="grow"><b>${esc(b.label)}</b><div class="muted" style="font-size:12.5px">${b.size} · ${fmt.dateTime(b.at)}</div></div><button class="ib" data-share="${i}" aria-label="Share">${A.icon('share')}</button><button class="ib" data-del="${i}" aria-label="Delete">${A.icon('delete')}</button></div></div>`).join('') || '<div class="muted pad16">No backup files</div>'}
        <div class="section-label">Sources & storage</div>
        <div class="list"><div class="li"><span class="lead">${A.icon('verified_user')}</span><div class="grow"><div class="t">Source access status</div><div class="s sage">Ready • WebView 142 · cookies saved for 3 sites</div></div></div>
          ${[['description', 'Share activity logs', 'logs'], ['bug_report', 'Share source access logs', 'slogs'], ['volunteer_activism', 'Patreon account', 'patreon'], ['restart_alt', 'Reset source web session', 'reset']].map(([i, t, k]) => `<button class="li" data-b="${k}"><span class="lead">${A.icon(i)}</span><div class="grow t">${t}</div>${A.icon('chevron_right', 'trail')}</button>`).join('')}</div>
        <div class="section-label" style="color:var(--red)">Danger zone</div>
        <div class="card red pad16"><div class="row">${A.icon('delete_forever', 'red')}<div class="grow"><b>Clear local storage</b><div class="muted" style="font-size:12.5px">Deletes every novel, chapter and audio file on this phone.</div></div></div><button class="btn sm danger" data-b="clear" style="margin-top:12px">Clear local storage</button></div>
      </div>`;
    }
    W.on(el, 'click', '[data-share]', () => A.toast('Opening share sheet'));
    W.on(el, 'click', '[data-del]', async (e, b) => { if (await A.confirm('Delete backup file?', 'This cannot be undone.', 'Delete', true)) { st.backups.splice(+b.dataset.del, 1); render(); A.toast('Backup deleted'); } });
    W.on(el, 'click', '[data-b]', async (e, b) => {
      const k = b.dataset.b;
      if (k === 'export') job('Exporting backup');
      else if (k === 'import') A.toast('Choose a backup file');
      else if (k === 'full') { A.toast('Creating full backup…'); setTimeout(() => { st.backups.unshift({ label: 'Full backup', size: '418 MB', at: Date.now() }); render(); A.toast('Full backup saved'); }, 2200); }
      else if (k === 'restore') { if (await A.confirm('Restore full backup?', 'Your current library will be replaced.', 'Restore', true)) job('Restoring', 2600); }
      else if (k === 'recover') job('Scanning backups for removed chapters', 2000);
      else if (k === 'reset') { if (await A.confirm('Reset web session?', 'You may need to pass site checks again.', 'Reset', true)) A.toast('Web session reset'); }
      else if (k === 'clear') { if (await A.confirm('Clear local storage?', 'Every novel, chapter and audio file on this phone will be deleted. Make a backup first.', 'Clear everything', true)) A.toast('Not cleared in the prototype — your library is safe'); }
      else A.toast('Opening…');
    });
    render();
  };

  // ---------- Manage Tabs ----------
  P.tabs = (el) => {
    const tabs = W.tabs();
    function render() {
      el.innerHTML = `${bar('Manage tabs', `${tabs.length} tabs`)}<div class="pad narrow">
        <p class="muted" style="margin:8px 4px 14px">Tabs group your library. Novels in a deleted tab become Unassigned.</p>
        <div class="row" style="gap:8px"><div class="field grow">${A.icon('label')}<input data-r="new" placeholder="New tab name"></div><button class="btn fill" data-a="add">Add</button></div>
        <div style="margin-top:16px">${tabs.map((t, i) => `<div class="card tabcard"><div class="grow"><b>${esc(t.name)}</b><div class="muted" style="font-size:12.5px">${W.storiesInTab(t.id).length} novels</div></div>
          <button class="ib" data-up="${i}" ${i ? '' : 'disabled'} aria-label="Move up">${A.icon('arrow_upward')}</button><button class="ib" data-down="${i}" ${i < tabs.length - 1 ? '' : 'disabled'} aria-label="Move down">${A.icon('arrow_downward')}</button><button class="ib" data-ren="${i}" aria-label="Rename">${A.icon('edit')}</button><button class="ib" data-del="${i}" aria-label="Delete">${A.icon('delete')}</button></div>`).join('')}</div></div>`;
    }
    const sw = (i, j) => { [tabs[i], tabs[j]] = [tabs[j], tabs[i]]; render(); A.emit(); };
    W.on(el, 'click', '[data-up]', (e, b) => sw(+b.dataset.up, +b.dataset.up - 1));
    W.on(el, 'click', '[data-down]', (e, b) => sw(+b.dataset.down, +b.dataset.down + 1));
    W.on(el, 'click', '[data-ren]', async (e, b) => { const t = tabs[+b.dataset.ren]; const v = await A.form('Rename tab', [{ name: 'name', label: 'Tab name', value: t.name }]); if (v && v.name) { t.name = v.name; render(); A.emit(); } });
    W.on(el, 'click', '[data-del]', async (e, b) => { const i = +b.dataset.del; const t = tabs[i]; if (await A.confirm(`Delete “${esc(t.name)}”?`, `${W.storiesInTab(t.id).length} novels will become Unassigned.`, 'Delete', true)) { tabs.splice(i, 1); if (A.state.libTab === t.id) A.state.libTab = 'all'; render(); A.emit(); A.toast('Tab deleted'); } });
    W.on(el, 'click', '[data-a=add]', () => { const inp = el.querySelector('[data-r=new]'); const v = inp.value.trim(); if (!v) { A.toast('Type a name first'); return; } tabs.push({ id: 'tab_' + Date.now(), name: v }); render(); A.emit(); A.toast(`Added “${v}”`); });
    render();
  };

  // ---------- Text Cleanup ----------
  P.cleanup = (el) => {
    const c = W.settings.cleanup; c.sentences = c.sentences || []; c.regex = c.regex || [];
    let mode = 'sentences';
    function render() {
      el.innerHTML = `${bar('Text cleanup', 'Applied to read-aloud and downloaded text')}<div class="pad narrow">
        <div class="seg" style="margin:8px 0 16px"><button data-m="sentences" class="${mode === 'sentences' ? 'on' : ''}">Full sentences <span class="faint">${c.sentences.length}</span></button><button data-m="regex" class="${mode === 'regex' ? 'on' : ''}">Regex rules <span class="faint">${c.regex.length}</span></button></div>
        ${mode === 'sentences' ? `<div class="field"><textarea data-r="sent" placeholder="Paste a sentence to remove wherever it appears"></textarea></div>
          <div class="btnrow" style="margin-top:10px"><button class="btn sm surface" data-a="paste">${A.icon('content_paste', 'sm')}Paste</button><button class="btn sm fill" data-a="addsent">${A.icon('add', 'sm')}Add</button></div>
          <div class="section-label">Saved sentences</div>
          <div class="list">${c.sentences.map((x, i) => `<div class="li"><div class="grow" style="font-size:13.5px">${esc(x)}</div><button class="ib" data-delsent="${i}" aria-label="Delete">${A.icon('delete')}</button></div>`).join('') || '<div class="li muted">No sentences yet</div>'}</div>`
          : `${c.regex.map((r, i) => `<div class="card pad16 rule ${r.enabled ? '' : 'off'}"><div class="row"><b class="grow">${esc(r.name)}</b><span class="pillb ${r.enabled ? 'gold' : ''}">${r.enabled ? 'Enabled' : 'Disabled'}</span></div><div class="muted" style="font-size:12.5px;margin-top:2px">Read aloud and downloaded text</div><code>/${esc(r.pattern)}/${esc(r.flags)}</code>
            <div class="btnrow"><button class="btn sm surface" data-edit="${i}">Edit</button><button class="btn sm surface" data-tog="${i}">${r.enabled ? 'Disable' : 'Enable'}</button><button class="btn sm text danger" data-delrule="${i}">Delete</button></div></div>`).join('')}
            <button class="btn tonal block" data-a="preset" style="margin-top:12px">${A.icon('add')}Add preset</button>`}
      </div>`;
    }
    W.on(el, 'click', '[data-m]', (e, b) => { mode = b.dataset.m; render(); });
    W.on(el, 'click', '[data-delsent]', (e, b) => { c.sentences.splice(+b.dataset.delsent, 1); render(); A.toast('Sentence removed'); });
    W.on(el, 'click', '[data-tog]', (e, b) => { const r = c.regex[+b.dataset.tog]; r.enabled = !r.enabled; render(); });
    W.on(el, 'click', '[data-delrule]', async (e, b) => { if (await A.confirm('Delete rule?', '', 'Delete', true)) { c.regex.splice(+b.dataset.delrule, 1); render(); } });
    W.on(el, 'click', '[data-edit]', async (e, b) => { const r = c.regex[+b.dataset.edit]; const v = await A.form('Edit rule', [{ name: 'name', label: 'Name', value: r.name }, { name: 'pattern', label: 'Pattern', value: r.pattern }, { name: 'flags', label: 'Flags', value: r.flags }]); if (v) { Object.assign(r, v); render(); A.toast('Rule saved'); } });
    W.on(el, 'click', '[data-a]', async (e, b) => {
      const a = b.dataset.a; const ta = el.querySelector('[data-r=sent]');
      if (a === 'paste') { let t = ''; try { t = await navigator.clipboard.readText(); } catch (x) { /* ignore */ } ta.value = t || 'Find the original story and support the author on their official site.'; }
      else if (a === 'addsent') { const v = ta.value.trim(); if (!v) { A.toast('Paste a sentence first'); return; } c.sentences.unshift(v); render(); A.toast('Sentence added'); }
      else if (a === 'preset') A.menu({ title: 'Add preset', sections: [{ items: [['Remove “Previous / Next chapter” links', '^\\s*(previous|next) chapter\\s*$', 'gim'], ['Remove author’s notes markers', '^A/N:.*$', 'gm'], ['Collapse repeated punctuation', '([!?.])\\1{3,}', 'g']].map(([n, p, f]) => ({ icon: 'rule', label: n, sub: `/${p}/${f}`, fn: () => { c.regex.push({ id: 'r' + Date.now(), name: n, pattern: p, flags: f, enabled: true, appliesTo: 'both' }); render(); A.toast('Preset added'); } })) }] });
    });
    render();
  };

  // ---------- Notifications ----------
  P.notifications = (el) => {
    const cats = [['download', 'Downloads', 'Progress and failures', true], ['record_voice_over', 'Text to Speech', 'Playback controls', true], ['graphic_eq', 'Narration audio', 'Generation progress', true], ['auto_awesome', 'AI Generation', 'Covers and descriptions', true]];
    function render() {
      el.innerHTML = `${bar('Notifications')}<div class="pad narrow">
        <div class="card pad16" style="margin-top:8px"><div class="row">${A.icon('notifications_active', 'sage')}<div class="grow"><div class="eyebrow">App permission</div><b>Allowed</b><div class="muted" style="font-size:12.5px">Change it in Android settings.</div></div><button class="btn sm surface" data-a="sys">Open</button></div></div>
        <div class="section-label">Categories</div>
        <div class="list">${cats.map(([i, t, s, on], k) => `<button class="li" data-c="${k}"><span class="lead">${A.icon(i)}</span><div class="grow"><div class="t">${t}</div><div class="s">${on ? 'Enabled' : 'Off'} · ${s}</div></div>${A.sw(on)}</button>`).join('')}</div></div>`;
    }
    W.on(el, 'click', '[data-c]', (e, b) => { const c = cats[+b.dataset.c]; c[3] = !c[3]; render(); });
    W.on(el, 'click', '[data-a=sys]', () => A.toast('Opening Android notification settings'));
    render();
  };

  // ---------- Download settings ----------
  P.downloads = (el) => {
    const d = W.settings.downloads;
    // per-source overrides: null = uses the defaults; otherwise { parallel, delayMin, delayMax } (ms)
    const srcs = W.sources.slice(0, 5).map((s, i) => ({ ...s, ov: i === 2 ? { parallel: 1, delayMin: 2000, delayMax: 5000 } : null }));
    const secs = (ms) => (ms / 1000).toFixed(1).replace(/\.0$/, '') ;
    const ovLine = (o) => `${o.parallel} at a time · ${o.delayMin === o.delayMax ? secs(o.delayMin) : `${secs(o.delayMin)}–${secs(o.delayMax)}`} s delay`;
    const DELAY = { step: 100, min: 0, max: 30000, unit: ' ms' };
    function render() {
      el.innerHTML = `${bar('Download settings')}<div class="pad narrow">
        <div class="section-label">Queue</div>
        <div class="list">${A.stepRow('parallel', d.parallel, { step: 1, min: 1, max: 5 }, 'Parallel sources', 'Sites downloaded at the same time')}${A.stepRow('delayMin', d.delayMin, DELAY, 'Delay min', 'Wait between chapters')}${A.stepRow('delayMax', d.delayMax, DELAY, 'Delay max', 'Randomized up to this value')}</div>
        <div class="section-label">EPUB</div>
        <div class="list">${A.stepRow('maxPerEpub', d.maxPerEpub, { step: 50, min: 50, max: 1000 }, 'Max chapters per EPUB', 'Bigger books are split into volumes')}</div>
        <div class="section-label">Per-source overrides</div>
        <div class="list">${srcs.map((s, i) => `<button class="li" data-src="${i}"><span class="lead">${A.icon(s.ov ? 'tune' : 'public', s.ov ? 'gold' : '')}</span><div class="grow"><div class="t">${esc(s.name)}</div><div class="s ${s.ov ? 'gold' : ''}">${s.ov ? ovLine(s.ov) : 'Uses the defaults'}</div></div>${A.icon('chevron_right', 'trail')}</button>`).join('')}</div>
        <div class="muted" style="font-size:12.5px;margin:10px 4px 0">Slow a site down if it starts limiting requests. Each site can have its own pace.</div></div>`;
    }
    // keep min ≤ max: moving one past the other drags the other along
    A.bindSteppers(el, (k, v) => {
      d[k] = v;
      if (k === 'delayMin' && d.delayMax < v) { d.delayMax = v; A.setStepper(el, v, 'delayMax'); }
      if (k === 'delayMax' && d.delayMin > v) { d.delayMin = v; A.setStepper(el, v, 'delayMin'); }
    });
    W.on(el, 'click', '[data-src]', (e, b) => {
      const s = srcs[+b.dataset.src];
      let custom = !!s.ov;
      const o = { ...(s.ov || { parallel: Math.min(2, d.parallel), delayMin: d.delayMin, delayMax: d.delayMax }) };
      const PRESETS = [['Gentle', 1, 2000, 5000], ['Balanced', 2, 1000, 3000], ['Fast', 3, 300, 1200]];
      const body = () => `
        <button class="li" data-o="toggle" style="margin:0 -4px"><div class="grow"><div class="t">Custom pace for this site</div><div class="s">${custom ? 'These values replace the defaults' : `Uses the defaults · ${d.parallel} sources · ${secs(d.delayMin)}–${secs(d.delayMax)} s`}</div></div>${A.sw(custom)}</button>
        <div class="srcform ${custom ? '' : 'off'}">
          <div class="list">${A.stepRow('parallel', o.parallel, { step: 1, min: 1, max: 5 }, 'Chapters at a time', 'From this site')}${A.stepRow('delayMin', o.delayMin, DELAY, 'Delay min', 'Wait between chapters')}${A.stepRow('delayMax', o.delayMax, DELAY, 'Delay max', 'Randomized up to this value')}</div>
          <div class="field-label" style="margin:14px 4px 8px">Quick fill</div>
          <div class="chips">${PRESETS.map(([l, p, a, z], i) => `<button class="chip ${o.parallel === p && o.delayMin === a && o.delayMax === z ? 'on' : ''}" data-o="p${i}">${l} <span class="faint">${p} · ${secs(a)}–${secs(z)} s</span></button>`).join('')}</div>
        </div>`;
      A.sheet({
        title: s.name, sub: 'Download pace for this site',
        body: `<div data-r="srcbody">${body()}</div>`,
        foot: '<button class="btn surface grow" data-o="cancel">Cancel</button><button class="btn fill grow" data-o="save">Save</button>',
        onMount(sh, close) {
          const box = sh.querySelector('[data-r=srcbody]');
          const paint = () => (box.innerHTML = body());
          A.bindSteppers(box, (k, v) => {
            o[k] = v;
            if (k === 'delayMin' && o.delayMax < v) { o.delayMax = v; A.setStepper(box, v, 'delayMax'); }
            if (k === 'delayMax' && o.delayMin > v) { o.delayMin = v; A.setStepper(box, v, 'delayMin'); }
            box.querySelectorAll('.chip').forEach((c, i) => { const [, p, a, z] = PRESETS[i]; c.classList.toggle('on', o.parallel === p && o.delayMin === a && o.delayMax === z); });
          });
          W.on(sh, 'click', '[data-o]', (ev, t) => {
            const x = t.dataset.o;
            if (x === 'toggle') { custom = !custom; paint(); }
            else if (x[0] === 'p') { const [, p, a, z] = PRESETS[+x.slice(1)]; Object.assign(o, { parallel: p, delayMin: a, delayMax: z }); custom = true; paint(); }
            else if (x === 'cancel') close();
            else if (x === 'save') { s.ov = custom ? { ...o } : null; close(); render(); A.toast(custom ? `${s.name}: ${ovLine(o)}` : `${s.name} uses the defaults`); }
          });
        },
      });
    });
    render();
  };

  // ---------- App updates ----------
  P.update = (el) => {
    const a = W.settings.app;
    let checking = false;
    function render() {
      el.innerHTML = `${bar('App updates')}<div class="pad narrow">
        <div class="card tint pad16 upcard"><div class="appicon">${A.icon('auto_stories', 'lg f')}</div><div class="muted" style="font-size:12.5px;margin-top:12px">Installed version</div><div class="big">${esc(a.version)}</div>
          <div class="row" style="margin-top:8px">${checking ? '<span class="spinner gold"></span><span>Checking GitHub releases…</span>' : `${A.icon('check_circle', 'sage sm')}<span>You have the latest version · Checked ${esc(a.checkedAgo)}</span>`}</div>
          <button class="btn fill block" data-a="check" style="margin-top:16px" ${checking ? 'disabled' : ''}>${A.icon('refresh')}Check for updates</button></div>
        <div class="list" style="margin-top:12px"><button class="li" data-a="auto"><div class="grow"><div class="t">Check automatically</div><div class="s">Once a day, over Wi-Fi</div></div>${A.sw(a.autoCheck)}</button>
        <button class="li" data-a="notes"><div class="grow"><div class="t">Release notes</div><div class="s">What changed in ${esc(a.version)}</div></div>${A.icon('chevron_right', 'trail')}</button></div></div>`;
    }
    W.on(el, 'click', '[data-a]', (e, b) => {
      const k = b.dataset.a;
      if (k === 'check') { checking = true; render(); setTimeout(() => { checking = false; a.checkedAgo = 'just now'; render(); A.toast('You have the latest version'); }, 1600); }
      else if (k === 'auto') { a.autoCheck = !a.autoCheck; render(); }
      else A.sheet({ title: `What’s new in ${a.version}`, body: '<ul class="notes"><li>Side-by-side beta track</li><li>Rust narration server works on macOS</li><li>Memory-aware generation on computers</li></ul>' });
    });
    render();
  };
})();
