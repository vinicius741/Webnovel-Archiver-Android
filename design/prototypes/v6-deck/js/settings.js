/* v6 — Settings hub (browsable + searchable) and Voice & Speech. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  const S = W.settings;

  V6.slider = (key, label, val, min, max, step, unit = '') => `<div class="sl" data-sl="${key}"><div class="sl-h"><span>${label}</span><b class="num"><span data-v>${val}</span><em>${unit}</em></b></div><input type="range" min="${min}" max="${max}" step="${step}" value="${val}" data-k="${key}" style="--f:${(val - min) / (max - min)}"></div>`;
  V6.focusFlash = (root, key) => { if (!key) return; const el = root.querySelector(`[data-focus="${key}"]`); if (!el) return; setTimeout(() => { root.scrollTop += el.getBoundingClientRect().top - 120; el.classList.add('flash'); setTimeout(() => el.classList.remove('flash'), 1600); }, 80); };

  // ---------- Hub ----------
  V6.screens.settings = (ctx) => {
    const { root, route } = ctx;
    const t = S.tts;
    const rows = [
      ['02', 'Reading & Audio', [
        ['Voice & Speech', `${t.voiceLabel.split(' ')[0]} · Kokoro · ${t.rate}×`, 'record_voice_over', '/settings/tts', 'tts voice rate speed pitch engine kokoro pauses'],
        ['Text Cleanup Rules', `${S.cleanup.regex.length} rules · ${S.cleanup.sentences.length} sentences`, 'cleaning_services', '/settings/cleanup', 'regex remove sentences'],
        ['AI Settings', `OpenRouter · $${W.ai.spend.month.toFixed(2)} this month`, 'auto_awesome', '/settings/ai', 'openrouter key model spend cover description'],
      ]],
      ['03', 'App', [
        ['Notifications', 'All categories on', 'notifications', '/settings/notifications', 'alerts permission'],
        ['Manage Tabs', `${W.tabs().length} tabs`, 'tab', '/settings/tabs', 'tabs rename order'],
        ['Download Settings', `${S.downloads.parallel} sources at once · ${S.downloads.maxPerEpub} per EPUB`, 'download', '/settings/downloads', 'parallel delay epub'],
        ['Data & Backup', `Last backup ${W.fmt.ago(S.backups[0].at)}`, 'backup', '/settings/data', 'export import restore storage logs patreon'],
        ['App Updates', `Version ${S.app.version}`, 'system_update', '/settings/update', 'version check'],
      ]],
    ];
    root.innerHTML = `${V6.topbar('Settings', '')}
      <div class="set-h"><h1>Settings</h1><label class="search">${ic('search')}<input type="search" placeholder="Find a setting" data-sq></label></div>
      <div data-list>
        <div data-grp="Appearance large screen layout fold">${V6.sec('01', 'Appearance')}
        <div class="row nob" data-focus="layout"><span class="rt"><b>Large screen layout</b><small>How the app uses the Fold’s inner screen</small></span></div>
        <div class="pad"><div class="seg wide" data-layout>${['auto', 'cover', 'inner'].map((k) => `<button data-l="${k}" class="${S.layout === k ? 'on' : ''}">${k}</button>`).join('')}</div></div></div>
        ${rows.map(([n, title, items]) => `<div data-grp="${title}">${V6.sec(n, title)}${items.map(([l, sub, icon, go, kw]) => `<button class="srow" data-go="${go}" data-kw="${esc(l + ' ' + kw)}">${ic(icon)}<span class="rt"><b>${l}</b><small>${esc(sub)}</small></span>${ic('arrow_forward')}</button>`).join('')}</div>`).join('')}
      </div>
      <div class="card acc set-tip"><div class="card-p row-c">${ic('bolt', 'acc-t')}<span class="rt"><b>Every setting is a command</b><small class="mut">Type “speed 1.5”, “say chapter titles” or “backup” in the command bar.</small></span></div></div>
      <p class="mono dim pad center">Webnovel Archiver · ${S.app.version}</p>`;
    root.querySelector('[data-sq]').addEventListener('input', (e) => {
      const q = e.target.value.trim();
      root.querySelectorAll('.srow').forEach((r) => { const m = !q || V6.fuzzy(q, r.dataset.kw); r.hidden = !m; const b = r.querySelector('b'); b.innerHTML = q && V6.fuzzy(q, b.textContent) ? V6.hl(b.textContent, V6.fuzzy(q, b.textContent).idx) : esc(b.textContent); });
      root.querySelectorAll('[data-grp]').forEach((g) => { g.hidden = !!q && !g.querySelector('.srow:not([hidden])') && !V6.fuzzy(q, g.dataset.grp); });
    });
    root.querySelector('[data-layout]').addEventListener('click', (e) => { const b = e.target.closest('[data-l]'); if (!b) return; S.layout = b.dataset.l; root.querySelectorAll('[data-l]').forEach((x) => x.classList.toggle('on', x === b)); V6.toast(`Layout: ${b.dataset.l}`, { icon: 'devices_fold' }); });
    V6.focusFlash(root, route.query.focus);
  };

  // ---------- Voice & Speech ----------
  const VOICES = [['Bella', 'American, female'], ['Sarah', 'American, female'], ['Nicole', 'American, female · whisper'], ['Sky', 'American, female'], ['Adam', 'American, male'], ['Michael', 'American, male'], ['Emma', 'British, female'], ['Isabella', 'British, female'], ['George', 'British, male'], ['Lewis', 'British, male']];
  const ENGINES = ['Kokoro (built in)', 'Speech Services by Google', 'Samsung text-to-speech engine'];
  const SAMPLE = 'Hecate checked the chamber, then the sky. Rain again. She sighed, holstered the gun, and stepped out into the square where the whole village was waiting to watch her fail.';

  V6.screens.settingsPage = (ctx) => {
    const page = ctx.route.params.page;
    if (page === 'tts') return tts(ctx);
    const fn = V6.settingsPages && V6.settingsPages[page];
    if (fn) return fn(ctx);
    ctx.root.innerHTML = V6.topbar('Settings', '') + V6.empty('help', 'Unknown settings page', esc(page), '<button class="btn acc" data-go="/settings">All settings</button>');
  };

  function tts(ctx) {
    const { root, route } = ctx;
    const t = S.tts;
    const d = { ...t, kokoro: true, sample: SAMPLE, dirty: false, testing: false, results: null };
    const ses = W.session;
    const kokoro = () => d.engine.startsWith('Kokoro');
    function draw() {
      root.innerHTML = `${V6.topbar('Voice & Speech', `${d.voiceLabel.split(' ')[0]} · ${kokoro() ? 'Kokoro' : d.engine}`)}
        ${ses ? `<section class="card"><div class="card-p"><span class="kick">Saved TTS session</span><b class="tts-ses">${esc(ses.chapterTitle)} <span class="mut">(sentence ${ses.index + 1})</span></b><small class="mut">${esc(ses.storyTitle)}</small><div class="row2"><button class="btn sm acc" data-a="resume">${ic('play_arrow')} Resume TTS</button><button class="btn sm ghost" data-a="clearses">Clear session</button></div></div></section>` : ''}
        <section class="card"><div class="card-p"><span class="kick">Profile</span><b>${esc(d.profileStatus)}</b><div class="row3"><button class="btn sm ghost" data-a="switch">Switch</button><button class="btn sm ghost" data-a="saveprof">Save as profile</button><button class="btn sm ghost" data-a="delprof">Delete</button></div></div></section>
        ${V6.sec('01', 'Rate')}
        <div class="rate" data-focus="rate"><button class="btn ghost" data-a="r-" aria-label="Slower">−</button><span class="num rate-v" data-rv>${d.rate.toFixed(2).replace(/0$/, '')}<em>×</em></span><button class="btn ghost" data-a="r+" aria-label="Faster">+</button></div>
        <div class="pad">${V6.slider('rate', 'Speech rate', d.rate, 0.5, 3, 0.05, '×')}${kokoro() ? '<p class="mono dim">Pitch is fixed for Kokoro voices.</p>' : V6.slider('pitch', 'Pitch', d.pitch, 0.5, 2, 0.05, '')}</div>
        ${V6.sec('02', 'Voice')}
        <button class="srow" data-a="engine">${ic('memory')}<span class="rt"><b>Engine</b><small>${esc(d.engine)}</small></span>${ic('unfold_more')}</button>
        <button class="srow" data-a="voice" data-focus="voice">${ic('record_voice_over')}<span class="rt"><b>Voice</b><small>${esc(d.voiceLabel)}</small></span>${ic('unfold_more')}</button>
        ${kokoro() ? `<section class="card" data-focus="kokoro"><div class="card-p"><span class="kick">Kokoro voices</span><b>${S.tts.kokoroInstalledMb ? `Installed. Uses ${S.tts.kokoroInstalledMb} MB on this phone.` : 'Not installed.'}</b>
          ${d.testing ? `<div class="pad-v">${V6.bar(d.testing, 'thick')}<small class="mono dim">Testing speed… ${Math.round(d.testing * 100)}%</small></div>` : d.results ? `<p class="mono acc-t pad-v">${d.results}</p>` : ''}
          <div class="row3">${S.tts.kokoroInstalledMb ? `<button class="btn sm ghost" data-a="test" ${d.testing ? 'disabled' : ''}>Test speed</button><button class="btn sm ghost" data-a="last">Last results</button><button class="btn sm ghost" data-a="remove">Remove</button>` : `<button class="btn sm acc" data-a="install">${ic('download')} Install (369 MB)</button>`}</div></div></section>` : ''}
        ${V6.sec('03', 'Behaviour')}
        <div data-focus="switches">${[['replayAfterBreaks', 'Replay a little after long breaks', 'Repeats the last sentence when you come back'], ['sayTitles', 'Say chapter titles', 'Announces each chapter before reading'], ['skipWholeChapters', 'Headphone and lock-screen skips move whole chapters', 'Instead of 15 / 30 seconds']].map(([k, l, sub]) => `<div class="row" data-sw="${k}"><span class="rt"><b>${l}</b><small>${sub}</small></span>${V6.sw(d[k])}</div>`).join('')}</div>
        ${V6.sec('04', 'Pauses')}
        <div class="pad" data-focus="pauses">${V6.slider('phrasePauseMs', 'Extra at commas and phrases', d.phrasePauseMs, 0, 500, 10, 'ms')}${V6.slider('sentencePauseMs', 'Between sentences', d.sentencePauseMs, 0, 1500, 10, 'ms')}${V6.slider('paragraphPauseMs', 'Between paragraphs', d.paragraphPauseMs, 0, 2000, 10, 'ms')}${V6.slider('scenePauseMs', 'At scene breaks', d.scenePauseMs, 0, 3000, 10, 'ms')}</div>
        ${V6.sec('05', 'Try it')}
        <div class="pad"><div class="try" data-try contenteditable="true" spellcheck="false">${esc(d.sample)}</div><div class="row2"><button class="btn acc" data-a="sample">${ic('play_arrow')} Play sample</button><button class="btn ghost" data-a="reset">Reset text</button></div></div>
        <div class="sticky-go"><button class="btn ${d.dirty ? 'acc' : 'ghost'} wide" data-a="save">${ic('save')} ${d.dirty ? 'Save TTS · unsaved changes' : 'Save TTS'}</button></div>`;
    }
    const dirty = () => { if (!d.dirty) { d.dirty = true; const b = root.querySelector('[data-a="save"]'); b.className = 'btn acc wide'; b.innerHTML = `${ic('save')} Save TTS · unsaved changes`; } };
    root.addEventListener('input', (e) => {
      const r = e.target.closest('input[type=range]'); if (r) {
        const k = r.dataset.k, v = parseFloat(r.value); d[k] = v; r.style.setProperty('--f', (v - r.min) / (r.max - r.min));
        r.closest('.sl').querySelector('[data-v]').textContent = k === 'rate' ? v.toFixed(2).replace(/0$/, '') : v;
        if (k === 'rate') root.querySelector('[data-rv]').innerHTML = `${v.toFixed(2).replace(/0$/, '')}<em>×</em>`;
        dirty();
      }
      if (e.target.matches('[data-try]')) d.sample = e.target.textContent;
    });
    let playT = null;
    root.addEventListener('click', async (e) => {
      const sw = e.target.closest('[data-sw]'); if (sw) { const k = sw.dataset.sw; d[k] = !d[k]; sw.querySelector('.sw').classList.toggle('on', d[k]); dirty(); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const k = a.dataset.a;
      if (k === 'r-' || k === 'r+') { d.rate = Math.max(0.5, Math.min(3, Math.round((d.rate + (k === 'r+' ? 0.1 : -0.1)) * 20) / 20)); dirty(); return draw(); }
      if (k === 'resume') { W.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); V6.toast('Resumed', { icon: 'play_arrow' }); }
      if (k === 'clearses') { if (await V6.dialog({ title: 'Clear saved session?', body: 'The “Continue listening” card will disappear.', ok: 'Clear', danger: true })) { V6.state.continueDismissed = true; V6.toast('Session cleared', { icon: 'delete' }); } }
      if (k === 'switch') V6.sheet({ title: 'Switch profile', items: (t.profiles || []).map((p) => ({ label: p.name, sub: `${p.rate}× · ${p.voiceIdentifier}`, run: () => { Object.assign(d, { rate: p.rate, sentencePauseMs: p.sentencePauseMs, paragraphPauseMs: p.paragraphPauseMs, scenePauseMs: p.scenePauseMs, phrasePauseMs: p.phrasePauseMs, profileStatus: `Using “${p.name}”.` }); draw(); } })) });
      if (k === 'saveprof') { const v = await V6.dialog({ title: 'Save as profile', fields: [{ label: 'Profile name', value: 'Night listening' }], ok: 'Save' }); if (v && v[0]) { d.profileStatus = `Using “${v[0]}”.`; (t.profiles = t.profiles || []).push({ name: v[0], rate: d.rate, voiceIdentifier: 'kokoro:af_bella' }); draw(); V6.toast('Profile saved', { icon: 'save' }); } }
      if (k === 'delprof') { if (await V6.dialog({ title: 'Delete profile “Google”?', ok: 'Delete', danger: true })) { d.profileStatus = 'No profile. Settings are saved directly.'; draw(); } }
      if (k === 'engine') V6.sheet({ title: 'Engine', items: ENGINES.map((en) => ({ label: en, on: d.engine === en, run: () => { d.engine = en; if (!en.startsWith('Kokoro')) d.voiceLabel = 'English (United States) · sfg'; else d.voiceLabel = 'Bella (American, female)'; dirty(); draw(); } })) });
      if (k === 'voice') V6.sheet({ title: 'Voice', sub: kokoro() ? 'Kokoro · built in' : d.engine, items: (kokoro() ? VOICES : [['sfg', 'United States'], ['iob', 'United States'], ['gba', 'United Kingdom']]).map(([n, sub]) => { const l = kokoro() ? `${n} (${sub})` : `English · ${n} (${sub})`; return { label: l, on: d.voiceLabel === l, run: () => { d.voiceLabel = l; dirty(); draw(); V6.toast(`Voice: ${n}`, { icon: 'record_voice_over' }); } }; }) });
      if (k === 'test') { d.testing = 0.02; draw(); const tick = () => { d.testing = Math.min(1, d.testing + 0.12); if (d.testing >= 1) { d.testing = false; d.results = '3.2× real time · 41 sentences · on this phone'; } draw(); if (d.testing) setTimeout(tick, 350); }; setTimeout(tick, 350); }
      if (k === 'last') V6.sheet({ title: 'Last results', html: '<div class="sh-pad"><div class="stor"><div><span class="num">3.2<em>×</em></span><small class="mono">real time</small></div><div><span class="num">41</span><small class="mono">sentences</small></div></div><p class="mut">Tested Nov 10 on this phone. Generating on a computer is usually 3× faster.</p></div>' });
      if (k === 'remove') { if (await V6.dialog({ title: 'Remove Kokoro voices?', body: 'Frees 369 MB. Generated audio is kept.', ok: 'Remove', danger: true })) { S.tts.kokoroInstalledMb = 0; draw(); } }
      if (k === 'install') { S.tts.kokoroInstalledMb = 369; V6.toast('Kokoro voices installed', { icon: 'download_done' }); draw(); }
      if (k === 'reset') { d.sample = SAMPLE; draw(); }
      if (k === 'sample') {
        clearTimeout(playT); const box = root.querySelector('[data-try]'); const words = d.sample.split(/(\s+)/); let i = 0;
        box.innerHTML = words.map((w, j) => `<span data-w="${j}">${esc(w)}</span>`).join('');
        const step = () => { box.querySelectorAll('.w-on').forEach((x) => x.classList.remove('w-on')); const el = box.querySelector(`[data-w="${i}"]`); if (!el) { box.textContent = d.sample; return; } el.classList.add('w-on'); i += 2; playT = setTimeout(step, 260 / d.rate); };
        step();
      }
      if (k === 'save') { Object.assign(t, { rate: d.rate, pitch: d.pitch, engine: d.engine, voiceLabel: d.voiceLabel, replayAfterBreaks: d.replayAfterBreaks, sayTitles: d.sayTitles, skipWholeChapters: d.skipWholeChapters, phrasePauseMs: d.phrasePauseMs, sentencePauseMs: d.sentencePauseMs, paragraphPauseMs: d.paragraphPauseMs, scenePauseMs: d.scenePauseMs, profileStatus: d.profileStatus }); W.player.setRate(d.rate); d.dirty = false; draw(); V6.toast('TTS settings saved', { icon: 'save' }); }
    });
    ctx.sub(() => clearTimeout(playT));
    draw();
    V6.focusFlash(root, route.query.focus);
  }
})();
