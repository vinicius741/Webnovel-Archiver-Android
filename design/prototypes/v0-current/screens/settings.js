/* v0 Settings hub + Voice & Speech. */
(function () {
  const W = window.WNA, A = window.App, esc = W.esc, ic = A.icon, S = W.settings;

  A.register('settings', () => {
    const items = (list) => list.map(([p, icn, t, sub, o]) => `<button class="row set-row" data-go="${p}">${ic(icn, o)}<div class="rt"><b>${t}</b><span>${sub}</span></div></button>`).join('');
    const el = W.el(`<div>${A.appBar({ title: 'Settings' })}<div class="scroll pb-mini" data-keep><div class="capped set-hub">
      <div class="sec-label">Appearance</div>
      <div class="set-block"><b>Large Screen Layout</b><div class="muted">How multi-column layouts behave on large/folded screens. Auto detects the display.</div>
        <div class="chip-row" style="margin-top:8px" data-layout></div></div>
      <div class="divider"></div>
      <div class="sec-label">Reading &amp; Audio</div>
      ${items([['tts', 'volume_up', 'Voice &amp; Speech', 'Voice, rate, pauses, and a sample to try them'], ['cleanup', 'playlist_remove', 'Text Cleanup Rules', 'Manage sentence removal and regex cleanup rules'], ['ai', 'auto_awesome', 'AI Settings', 'OpenRouter API key and models for AI descriptions']])}
      <div class="divider"></div>
      ${items([['notifications', 'notifications', 'Notifications', 'Manage downloads and text-to-speech alerts'], ['tabs', 'tab', 'Manage Tabs', 'Create and organize custom tabs for your library', true], ['data', 'folder', 'Data &amp; Backup', 'Backups, source access, and storage tools'], ['update', 'refresh', 'App Updates', `Version ${esc(S.app.version)}`]])}
    </div></div></div>`);
    const lay = () => { el.querySelector('[data-layout]').innerHTML = [['auto', 'Auto'], ['cover', 'Cover'], ['inner', 'Inner']].map(([k, l]) => `<button class="chip${S.layout === k ? ' sel' : ''}" data-lay="${k}">${S.layout === k ? ic('check') : ''}${l}</button>`).join(''); };
    lay();
    el.addEventListener('click', (e) => { const g = e.target.closest('[data-go]'); if (g) W.go('/settings/' + g.dataset.go); const l = e.target.closest('[data-lay]'); if (l) { S.layout = l.dataset.lay; lay(); } });
    return { el };
  });

  // ---------- Voice & Speech ----------
  const VOICES = [['Bella (American, female)', 'af_bella'], ['Sarah (American, female)', 'af_sarah'], ['Adam (American, male)', 'am_adam'], ['Michael (American, male)', 'am_michael'], ['Emma (British, female)', 'bf_emma'], ['George (British, male)', 'bm_george']];
  const ENGINES = ['Kokoro (built in)', 'Google Speech Services', 'Samsung TTS'];
  const SAMPLE = 'Hecate checked the chamber, counted her bullets twice, and stepped out into the rain. Somewhere below, the bells began to ring.';
  const PAUSES = [['phrasePauseMs', 'Extra at commas and phrases', 0, 400], ['sentencePauseMs', 'Between sentences', 0, 1500], ['paragraphPauseMs', 'Between paragraphs', 0, 2000], ['scenePauseMs', 'At scene breaks', 0, 3000]];
  A.register('settingsPage', (route, ctx) => {
    const page = route.params.page;
    if (page !== 'tts') return A.settingsPage(page, route, ctx);
    const t = S.tts;
    let sample = SAMPLE, sessionCleared = false, sampling = false;
    const el = W.el(`<div>${A.appBar({ title: 'Voice & Speech' })}<div class="scroll pb-mini" data-keep data-sc></div></div>`);
    const sc = el.querySelector('[data-sc]');
    const sw = (k, label, sub) => `<button class="row sw-row" data-sw="${k}"><div class="rt"><b>${label}</b>${sub ? `<span>${sub}</span>` : ''}</div><span class="switch${t[k] ? ' on' : ''}"></span></button>`;
    function draw() {
      const ses = W.session; const kokoro = /Kokoro/.test(t.engine);
      sc.innerHTML = `<div class="pad capped tts">
        <div class="card"><h3>Saved TTS session</h3>${ses && !sessionCleared ? `<div class="card-sub">${esc(ses.chapterTitle)} (sentence ${ses.index + 1})</div>
          <div class="btn-row" style="margin-top:14px"><button class="btn tonal" data-a="resume">${ic('play_arrow')}Resume TTS</button><button class="btn text" data-a="clearses">${ic('delete')}Clear Session</button></div>` : '<div class="card-sub">No saved session.</div>'}</div>
        <div class="card"><h3>Profile</h3><div class="card-sub">${esc(t.profileStatus)}</div>
          <div class="btn-row" style="margin-top:14px"><button class="btn tonal" data-a="switch">${ic('bookmark')}Switch</button><button class="btn text" data-a="saveprof">${ic('check')}Save as profile</button><button class="btn text" data-a="delprof">${ic('delete')}Delete</button></div></div>
        <div class="field-label" style="margin-top:20px">Rate</div><div class="field"><input data-rate value="${t.rate}" inputmode="decimal"></div>
        ${kokoro ? '' : `<div class="field-label">Pitch</div><div class="field"><input data-pitch value="${t.pitch}" inputmode="decimal"></div>`}
        <div class="tts-pick"><span>Engine</span><button class="btn text" data-a="engine">${ic('settings')}${esc(t.engine)}</button></div>
        <div class="tts-pick"><span>Voice</span><button class="btn text" data-a="voice">${ic('volume_up')}${esc(t.voiceLabel)}</button></div>
        ${kokoro ? `<div class="card"><h3>Kokoro voices</h3><div class="card-sub">${t.kokoroInstalledMb ? `Installed. Uses ${t.kokoroInstalledMb} MB on this phone.` : 'Not installed. Download 369 MB to use Kokoro voices.'}</div>
          <div class="btn-row" style="margin-top:10px">${t.kokoroInstalledMb ? `<button class="btn tonal" data-a="test">${ic('show_chart')}Test speed</button><button class="btn text" data-a="results">${ic('share')}Last results</button><button class="btn text" data-a="remove">${ic('delete')}Remove</button>` : `<button class="btn tonal" data-a="install">${ic('download')}Download</button>`}</div></div>` : ''}
        <div style="margin-top:8px">${sw('replayAfterBreaks', 'Replay a little after long breaks', 'Repeats the last sentence when you resume after a while')}${sw('sayTitles', 'Say chapter titles', 'Announces each chapter before reading it')}${sw('skipWholeChapters', 'Headphone and lock-screen skips move whole chapters', 'Otherwise they skip back 15 s and ahead 30 s')}</div>
        <div class="sec-label">Pauses</div>
        ${PAUSES.map(([k, l, lo, hi]) => `<div class="slider"><div class="sl-top"><span>${l}</span><b data-pv="${k}">${t[k]} ms</b></div><input type="range" min="${lo}" max="${hi}" step="10" value="${t[k]}" data-p="${k}" style="--pct:${((t[k] - lo) / (hi - lo)) * 100}%"></div>`).join('')}
        <div class="sec-label">Try it</div>
        <div class="field area"><textarea data-sample>${esc(sample)}</textarea></div>
        <div class="btn-row" style="margin-top:10px"><button class="btn tonal" data-a="sample">${sampling ? '<span class="spinner"></span>Playing…' : `${ic('play_arrow')}Play sample`}</button><button class="btn text" data-a="resetsample">${ic('restart_alt')}Reset text</button></div>
        <button class="btn filled full" data-a="save" style="margin:24px 0 8px">${ic('save')}Save TTS</button>
      </div>`;
    }
    sc.addEventListener('input', (e) => {
      const p = e.target.dataset.p; if (p) { t[p] = +e.target.value; e.target.style.setProperty('--pct', ((t[p] - e.target.min) / (e.target.max - e.target.min)) * 100 + '%'); sc.querySelector(`[data-pv="${p}"]`).textContent = t[p] + ' ms'; }
      if (e.target.matches('[data-sample]')) sample = e.target.value;
      if (e.target.matches('[data-rate]')) { const v = parseFloat(e.target.value); if (v > 0 && v <= 4) t.rate = v; }
    });
    sc.addEventListener('click', (e) => {
      const s = e.target.closest('[data-sw]'); if (s) { t[s.dataset.sw] = !t[s.dataset.sw]; s.querySelector('.switch').classList.toggle('on', t[s.dataset.sw]); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      const k = a.dataset.a;
      if (k === 'resume') { const ses = W.session; W.player.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); A.toast('Resuming'); }
      if (k === 'clearses') A.confirm({ title: 'Clear session?', message: 'The saved listening position will be forgotten.', ok: 'Clear', danger: true, fn: () => { sessionCleared = true; draw(); } });
      if (k === 'switch') A.choose({ title: 'Switch profile', options: (t.profiles || []).map((p) => ({ value: p.id, label: p.name, sub: `Rate ${p.rate} · ${p.voiceIdentifier}` })), value: null, fn: () => { t.profileStatus = 'Using "Google".'; draw(); A.toast('Switched to Google'); } });
      if (k === 'saveprof') A.prompt({ title: 'Save as profile', label: 'Profile name', value: 'Kokoro Bella', fn: (v) => { (t.profiles = t.profiles || []).push({ id: 'p' + Date.now(), name: v, rate: t.rate, voiceIdentifier: t.voiceIdentifier }); t.profileStatus = `Using "${v}".`; draw(); A.toast('Profile saved'); } });
      if (k === 'delprof') A.confirm({ title: 'Delete profile?', message: 'Delete the "Google" profile? Your current settings stay as they are.', ok: 'Delete', danger: true, fn: () => { t.profiles = []; t.profileStatus = 'Not saved to a profile.'; draw(); } });
      if (k === 'engine') A.choose({ title: 'Engine', options: ENGINES.map((x) => ({ value: x, label: x })), value: t.engine, fn: (v) => { t.engine = v; if (!/Kokoro/.test(v)) t.voiceLabel = 'English (United States) · sfg'; else t.voiceLabel = VOICES[0][0]; draw(); } });
      if (k === 'voice') A.choose({ title: 'Voice', options: (/Kokoro/.test(t.engine) ? VOICES.map(([l]) => l) : ['English (United States) · sfg', 'English (United States) · iol', 'English (United Kingdom) · rjs']).map((x) => ({ value: x, label: x })), value: t.voiceLabel, fn: (v) => { t.voiceLabel = v; draw(); A.toast('Voice changed'); } });
      if (k === 'test') { A.toast('Testing speed…'); setTimeout(() => A.dialog({ title: 'Speed test', body: '<p style="margin:0">Kokoro generated 30 s of speech in 9.4 s on this phone.<br><b style="color:var(--on-surface)">3.2× real time</b> · fast enough to keep ahead at 1.8x.</p>', actions: [{ label: 'OK' }] }), 1500); }
      if (k === 'results') A.toast('Sharing the last results…');
      if (k === 'remove') A.confirm({ title: 'Remove Kokoro voices?', message: 'Frees 369 MB. Chapters already generated keep working.', ok: 'Remove', danger: true, fn: () => { t.kokoroInstalledMb = 0; draw(); } });
      if (k === 'install') { a.innerHTML = '<span class="spinner"></span>Downloading…'; setTimeout(() => { t.kokoroInstalledMb = 369; draw(); A.toast('Kokoro voices installed'); }, 2200); }
      if (k === 'sample') { sampling = true; draw(); setTimeout(() => { sampling = false; draw(); }, 3000); }
      if (k === 'resetsample') { sample = SAMPLE; draw(); }
      if (k === 'save') A.toast('TTS settings saved');
    });
    draw();
    return { el };
  });
})();
