/* v5-feed Settings hub and Voice & Speech. */
(function () {
  const A = window.V5, W = A.W, esc = A.esc;
  const S = W.settings;

  A.screens.settings = (ctx) => {
    A.tint(ctx.el, W.session ? W.story(W.session.storyId) : null);
    const tts = S.tts;
    const row = (go, icon, title, sub) => `<button class="row" data-go="${go}"><span class="icon-disc">${A.ic(icon)}</span><span class="rt"><b>${title}</b><small>${sub}</small></span>${A.ic('chevron_right', 'chev')}</button>`;
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Settings', back: '/home' })}
      <div class="set-wrap rise">
        <div class="hub-hero"><span class="voice-av">${A.ic('auto_stories', 'f')}</span><div><b>Webnovel Archiver</b><small>${A.stories().length} novels · ${W.fmt.num(W.stats.downloaded)} chapters offline · v${esc(S.app.version)}</small></div></div>
        <section class="set-sec"><h4>Appearance</h4><div class="set-card"><div><div class="set-row" style="min-height:0"><span><b>Large screen layout</b><small>How the app uses the Fold’s inner screen</small></span></div>
          <div style="margin-top:12px">${A.seg([['auto', 'Auto'], ['cover', 'Cover'], ['inner', 'Inner']], S.layout, 'data-layout')}</div></div></div></section>
        <section class="set-sec"><h4>Reading & audio</h4><div class="group">
          ${row('/settings/tts', 'record_voice_over', 'Voice & Speech', `${esc(tts.voiceLabel.split(' (')[0])} · ${esc(tts.engine)} · ${tts.rate}×`)}
          ${row('/settings/cleanup', 'cleaning_services', 'Text Cleanup Rules', `${(S.cleanup.sentences || []).length} sentences · ${(S.cleanup.regex || []).length} regex rules`)}
          ${row('/settings/ai', 'auto_awesome', 'AI Settings', 'OpenRouter · covers and descriptions')}</div></section>
        <section class="set-sec"><h4>Library & app</h4><div class="group">
          ${row('/settings/notifications', 'notifications', 'Notifications', 'Downloads, audio, AI · Allowed')}
          ${row('/settings/tabs', 'tab', 'Manage Tabs', `${W.tabs().length} tabs`)}
          ${row('/settings/downloads', 'download', 'Download Settings', `${S.downloads.parallel} sources at once · ${S.downloads.delayMin}–${S.downloads.delayMax} ms delay`)}
          ${row('/settings/data', 'backup', 'Data & Backup', 'Export, restore, storage and sources')}
          ${row('/settings/update', 'system_update', 'App Updates', `Version ${esc(S.app.version)}`)}</div></section>
        <p class="faint" style="text-align:center;font-size:12px;padding:6px 0 10px">Prototype v5 · Home feed</p></div></div>`;
    A.bindTopbar(ctx);
    ctx.el.addEventListener('click', (e) => { const b = e.target.closest('[data-layout]'); if (!b) return; S.layout = b.dataset.layout; ctx.el.querySelectorAll('[data-layout]').forEach((x) => x.classList.toggle('on', x === b)); A.toast(`Layout: ${b.textContent}`, { icon: 'devices_fold' }); });
  };

  // ---------- Voice & Speech ----------
  const VOICES = ['Bella (American, female)', 'Sarah (American, female)', 'Nicole (American, female)', 'Adam (American, male)', 'Michael (American, male)', 'Emma (British, female)', 'George (British, male)', 'Lewis (British, male)'];
  const ENGINES = ['Kokoro (built in)', 'Google Speech Services', 'Samsung Text-to-speech'];
  const SAMPLE = 'Hecate has never done anything wrong in her entire life. Sure, she was supposed to pick the same class as her mother… but a well-placed bullet is worth a hundred prayers.';
  A.screens.settings_tts = (ctx) => {
    const t = S.tts;
    const d = { rate: t.rate, pitch: t.pitch || 1, phrase: t.phrasePauseMs, sentence: t.sentencePauseMs, para: t.paragraphPauseMs, scene: t.scenePauseMs, voice: t.voiceLabel, engine: t.engine, replay: t.replayAfterBreaks, titles: t.sayTitles, skip: t.skipWholeChapters, sample: SAMPLE, dirty: false, installed: true };
    let session = W.session;
    A.tint(ctx.el, session ? W.story(session.storyId) : null);
    const kokoro = () => /kokoro/i.test(d.engine);
    const slider = (k, label, min, max, step, unit) => `<div><div class="set-lbl"><b>${label}</b><span class="v" data-v="${k}">${d[k]}${unit}</span></div>${A.range(d[k], min, max, step, `data-k="${k}" data-u="${unit}"`)}</div>`;
    const html = () => `
      ${session ? `<div class="set-card sess"><span class="eyebrow">Saved TTS session</span><div><b style="font:750 16px/1.3 var(--display)">${esc(session.chapterTitle)} (sentence ${session.index + 1})</b><p class="muted" style="font-size:13px;margin-top:3px">${esc(A.short(session.storyTitle))}</p></div>
        <div class="acts"><button class="btn primary sm" data-resume>${A.ic('play_arrow', 'f')}Resume TTS</button><button class="btn glassy sm" data-clearsess>Clear Session</button></div></div>` : ''}
      <div class="set-card"><div class="voice-pill"><span class="voice-av">${A.ic('graphic_eq')}</span><span style="flex:1;min-width:0"><b style="display:block;font:750 17px/1.25 var(--display)">${esc(d.voice)}</b><small class="muted">${esc(d.engine)}</small></span></div>
        <div class="group"><button class="row" data-engine>${A.ic('memory')}<span class="rt"><b>Engine</b><small>${esc(d.engine)}</small></span>${A.ic('expand_more', 'chev')}</button><button class="row" data-voice>${A.ic('record_voice_over')}<span class="rt"><b>Voice</b><small>${esc(d.voice)}</small></span>${A.ic('expand_more', 'chev')}</button></div></div>
      <div class="set-card"><h3>Profile</h3><p class="muted" style="font-size:13.5px">${d.dirty ? 'Based on “Google”, with changes not saved to it.' : esc(t.profileStatus)}</p>
        <div class="acts"><button class="btn glassy sm" data-pswitch>${A.ic('swap_horiz')}Switch</button><button class="btn glassy sm" data-psave>${A.ic('save')}Save as profile</button><button class="btn ghost sm" data-pdel>${A.ic('delete')}Delete</button></div></div>
      <div class="set-card">${slider('rate', 'Speech rate', 0.5, 3, 0.1, '×')}${kokoro() ? '<p class="faint" style="font-size:12.5px;margin-top:-6px">Pitch isn’t adjustable for Kokoro voices.</p>' : slider('pitch', 'Pitch', 0.5, 2, 0.1, '×')}</div>
      ${kokoro() ? `<div class="set-card"><h3>Kokoro voices</h3><p class="muted" style="font-size:13.5px">${d.installed ? `Installed. Uses ${S.tts.kokoroInstalledMb} MB on this phone.` : 'Not installed. Download 369 MB to use Kokoro.'}</p>
        <div class="acts">${d.installed ? `<button class="btn glassy sm" data-ktest>${A.ic('speed')}Test speed</button><button class="btn glassy sm" data-kres>Last results</button><button class="btn ghost sm" data-krm>Remove</button>` : `<button class="btn primary sm" data-kinst>${A.ic('download')}Install</button>`}</div></div>` : ''}
      <div class="set-card">
        <div class="set-row"><span><b>Replay a little after long breaks</b><small>Repeat the last sentence when you come back</small></span>${A.sw(d.replay, 'data-sw="replay"')}</div>
        <div class="set-row"><span><b>Say chapter titles</b><small>Announce each chapter before reading</small></span>${A.sw(d.titles, 'data-sw="titles"')}</div>
        <div class="set-row"><span><b>Headphone and lock-screen skips move whole chapters</b><small>Instead of jumping 15 or 30 seconds</small></span>${A.sw(d.skip, 'data-sw="skip"')}</div></div>
      <div class="set-card"><h3>Pauses</h3>${slider('phrase', 'Extra at commas and phrases', 0, 400, 10, ' ms')}${slider('sentence', 'Between sentences', 0, 1500, 50, ' ms')}${slider('para', 'Between paragraphs', 0, 2000, 50, ' ms')}${slider('scene', 'At scene breaks', 0, 3000, 50, ' ms')}</div>
      <div class="set-card"><h3>Try it</h3><textarea class="sample" data-sample>${esc(d.sample)}</textarea>
        <div class="acts"><button class="btn tonal sm" data-play>${A.ic('play_arrow', 'f')}<span>Play sample</span></button><button class="btn ghost sm" data-reset>Reset text</button></div></div>
      <div class="savebar"><button class="btn primary" data-save>${A.ic('check')}Save TTS</button></div>`;
    ctx.el.innerHTML = `<div class="sc">${A.topbar({ title: 'Voice & Speech', sub: 'How chapters sound when read aloud', back: '/settings' })}<div class="set-wrap tts-body"></div></div>`;
    A.bindTopbar(ctx);
    const body = ctx.el.querySelector('.tts-body');
    const draw = () => { body.innerHTML = html(); };
    draw();
    const dirty = () => { if (!d.dirty) { d.dirty = true; const p = body.querySelectorAll('.set-card')[session ? 2 : 1].querySelector('p'); if (p) p.textContent = 'Based on “Google”, with changes not saved to it.'; } };
    ctx.el.addEventListener('input', (e) => {
      const k = e.target.dataset.k;
      if (k) { d[k] = +e.target.value; body.querySelector(`[data-v="${k}"]`).textContent = (k === 'rate' || k === 'pitch' ? (+d[k]).toFixed(1) : d[k]) + e.target.dataset.u; dirty(); }
      if (e.target.matches('[data-sample]')) d.sample = e.target.value;
    });
    ctx.el.addEventListener('sw', (e) => { const k = e.target.dataset.sw; if (k) { d[k] = e.detail; dirty(); } });
    let sampleT = null;
    ctx.on(() => clearTimeout(sampleT));
    ctx.el.addEventListener('click', (e) => {
      const t0 = e.target;
      if (t0.closest('[data-resume]')) { W.player.load(session.storyId, session.chapterId, { index: session.index, play: true }); A.toast('Resuming your session', { icon: 'headphones', action: 'Open player', fn: () => A.go('/player') }); return; }
      if (t0.closest('[data-clearsess]')) { const prev = session; session = null; draw(); A.toast('Session cleared', { action: 'Undo', fn: () => { session = prev; draw(); } }); return; }
      if (t0.closest('[data-engine]')) return A.menu({ title: 'Engine', items: ENGINES.map((x) => ({ icon: 'memory', label: x, on: d.engine === x, fn: () => { d.engine = x; if (!/kokoro/i.test(x)) d.voice = 'English (United States) · sfg'; else d.voice = VOICES[0]; dirty(); draw(); } })) });
      if (t0.closest('[data-voice]')) return A.menu({ title: 'Voice', sub: d.engine, items: (kokoro() ? VOICES : ['English (United States) · sfg', 'English (United States) · iol', 'English (United Kingdom) · rjs']).map((x) => ({ icon: 'record_voice_over', label: x, on: d.voice === x, fn: () => { d.voice = x; dirty(); draw(); A.toast('Voice: ' + x.split(' (')[0], { icon: 'record_voice_over', action: 'Play sample', fn: () => A.toast('Playing sample…', { icon: 'volume_up' }) }); } })) });
      if (t0.closest('[data-pswitch]')) return A.menu({ title: 'Switch profile', items: [...(t.profiles || []).map((p) => ({ icon: 'person', label: p.name, sub: `${p.rate}× · ${p.sentencePauseMs} ms between sentences`, fn: () => { Object.assign(d, { rate: p.rate, sentence: p.sentencePauseMs, para: p.paragraphPauseMs, phrase: p.phrasePauseMs, scene: p.scenePauseMs, dirty: false }); draw(); A.toast(`Profile “${p.name}” loaded`); } })), { icon: 'person_add', label: 'Night listening', sub: '1.4× · longer pauses', fn: () => { Object.assign(d, { rate: 1.4, sentence: 450, para: 700 }); dirty(); draw(); } }] });
      if (t0.closest('[data-psave]')) return A.form({ title: 'Save as profile', fields: [{ key: 'name', label: 'Profile name', value: 'Bella fast' }] }).then((v) => { if (v && v.name) { d.dirty = false; draw(); A.toast(`Profile “${v.name}” saved`, { icon: 'save' }); } });
      if (t0.closest('[data-pdel]')) return A.confirm({ title: 'Delete profile “Google”?', body: 'Your current settings stay as they are.', ok: 'Delete', danger: true }).then((ok) => ok && A.toast('Profile deleted'));
      if (t0.closest('[data-ktest]')) { const b = t0.closest('[data-ktest]'); b.innerHTML = '<span class="spin"></span>Testing…'; setTimeout(() => { b.innerHTML = `${A.ic('speed')}Test speed`; A.toast('Kokoro runs at 3.4× real time on this phone', { icon: 'speed' }); }, 2200); return; }
      if (t0.closest('[data-kres]')) return A.sheet({ title: 'Last speed test', html: `<div style="padding:0 8px 12px;display:grid;gap:10px"><div class="kv"><span>Real-time factor</span><b>3.4×</b></div><div class="kv"><span>First audio after</span><b>0.8 s</b></div><div class="kv"><span>Threads</span><b>4</b></div><div class="kv"><span>Tested</span><b>Nov 9, 2026</b></div></div>` });
      if (t0.closest('[data-krm]')) return A.confirm({ title: 'Remove Kokoro voices?', body: 'Frees 369 MB. Generated audio is kept.', ok: 'Remove', danger: true }).then((ok) => { if (ok) { d.installed = false; draw(); } });
      if (t0.closest('[data-kinst]')) { const b = t0.closest('[data-kinst]'); let p = 0; const iv = setInterval(() => { p += 9; b.innerHTML = `<span class="spin"></span>${Math.min(100, p)}%`; if (p >= 100) { clearInterval(iv); d.installed = true; draw(); A.toast('Kokoro voices installed', { icon: 'check' }); } }, 220); return; }
      if (t0.closest('[data-play]')) {
        const b = t0.closest('[data-play]'), lbl = b.querySelector('span:last-child');
        if (sampleT) { clearTimeout(sampleT); sampleT = null; lbl.textContent = 'Play sample'; return; }
        lbl.textContent = 'Stop'; A.toast(`Playing at ${d.rate.toFixed ? d.rate.toFixed(1) : d.rate}× · ${d.voice.split(' (')[0]}`, { icon: 'volume_up' });
        sampleT = setTimeout(() => { sampleT = null; lbl.textContent = 'Play sample'; }, Math.max(2500, d.sample.split(/\s+/).length * 360 / d.rate)); return;
      }
      if (t0.closest('[data-reset]')) { d.sample = SAMPLE; body.querySelector('[data-sample]').value = SAMPLE; return; }
      if (t0.closest('[data-save]')) {
        Object.assign(t, { rate: d.rate, pitch: d.pitch, phrasePauseMs: d.phrase, sentencePauseMs: d.sentence, paragraphPauseMs: d.para, scenePauseMs: d.scene, voiceLabel: d.voice, engine: d.engine, replayAfterBreaks: d.replay, sayTitles: d.titles, skipWholeChapters: d.skip });
        W.player.setRate(Math.min(2, Math.round(d.rate * 100) / 100));
        A.toast('TTS settings saved', { icon: 'check' });
      }
    });
  };
})();
