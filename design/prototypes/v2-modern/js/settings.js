/* Settings hub + Voice & Speech; shared slider/switch helpers; settings sub-page dispatcher. */
(function () {
  const { esc, el, fmt } = WNA;
  const ic = V2.ic;
  const S = WNA.settings;

  // ---------- helpers ----------
  V2.sliderHtml = (key, v, min, max) => `<div class="slider" data-sl="${key}" style="--v:${(v - min) / (max - min)}"><div class="trk"><i class="a"></i><i class="h"></i><i class="b"></i></div><span class="tip"></span></div>`;
  V2.bindSliders = (root, defs) => {
    root.addEventListener('pointerdown', (e) => {
      const sl = e.target.closest('[data-sl]'); if (!sl) return; const d = defs[sl.dataset.sl]; if (!d) return;
      sl.setPointerCapture(e.pointerId); sl.classList.add('drag');
      const set = (ev) => {
        const r = sl.getBoundingClientRect(); let f = V2.clamp((ev.clientX - r.left) / r.width, 0, 1);
        let v = d.min + f * (d.max - d.min); v = Math.round(v / d.step) * d.step; v = V2.clamp(+v.toFixed(3), d.min, d.max);
        sl.style.setProperty('--v', (v - d.min) / (d.max - d.min)); sl.querySelector('.tip').textContent = d.fmt(v); d.set(v);
      };
      set(e);
      const mv = (ev) => set(ev); const up = () => { sl.classList.remove('drag'); sl.removeEventListener('pointermove', mv); sl.removeEventListener('pointerup', up); sl.removeEventListener('pointercancel', up); if (d.done) d.done(); };
      sl.addEventListener('pointermove', mv); sl.addEventListener('pointerup', up); sl.addEventListener('pointercancel', up);
    });
  };
  V2.swRow = (key, title, sub, on, icon) => `<button class="li" data-sw="${key}">${icon ? `<span class="lead">${ic(icon)}</span>` : ''}<span class="tx"><b>${esc(title)}</b>${sub ? `<span>${esc(sub)}</span>` : ''}</span><span class="switch ${on ? 'on' : ''}"></span></button>`;
  V2.navRow = (to, icon, title, sub) => `<button class="li" data-to="${to}"><span class="lead">${ic(icon)}</span><span class="tx"><b>${esc(title)}</b>${sub ? `<span>${esc(sub)}</span>` : ''}</span>${ic('chevron_right')}</button>`;
  V2.subPage = (ctx, title, sub, inner) => {
    ctx.el.innerHTML = `${V2.bar({ title, sub })}<div class="scroll"><div class="maxw set">${V2.large(title, sub)}${inner}</div></div>`;
    V2.collapse(ctx.el);
    ctx.el.addEventListener('click', (e) => { const t = e.target.closest('[data-to]'); if (t) V2.go(t.dataset.to); });
  };

  // ---------- hub ----------
  V2.screens.settings = (ctx) => {
    const t = S.tts; const cl = S.cleanup || { regex: [], sentences: [] };
    V2.subPage(ctx, 'Settings', '', `
      <div class="wrap"><section class="card set-hero"><span class="set-logo">${ic('auto_stories', 'fill s28')}</span><div class="grow"><b class="t-tm">Webnovel Archiver</b><span class="t-bs muted">${fmt.int(WNA.stats.stories)} novels · ${fmt.int(WNA.stats.downloaded)} chapters offline</span></div></section></div>
      <div class="sec-h"><h2>Appearance</h2></div>
      <div class="wrap"><section class="card"><b class="t-tm">Large screen layout</b><p class="t-bs muted" style="margin:4px 0 14px">How the app uses the Fold’s inner screen.</p>
        <div class="seg l">${[['auto', 'Auto'], ['cover', 'Cover'], ['inner', 'Inner']].map(([k, l]) => `<button class="${S.layout === k ? 'sel' : ''}" data-lay="${k}">${ic('check')}${l}</button>`).join('')}</div></section></div>
      <div class="sec-h"><h2>Reading &amp; audio</h2></div>
      <div class="seglist">${V2.navRow('/settings/tts', 'record_voice_over', 'Voice & speech', `${t.engine.replace(' (built in)', '')} · ${t.voiceLabel.split(' ')[0]} · ${t.rate}×`)}${V2.navRow('/settings/cleanup', 'cleaning_services', 'Text cleanup rules', `${cl.regex.length} rules · ${cl.sentences.length} sentences`)}${V2.navRow('/settings/ai', 'auto_awesome', 'AI settings', `OpenRouter · $${WNA.ai.spend.month.toFixed(2)} this month`)}</div>
      <div class="sec-h"><h2>Library</h2></div>
      <div class="seglist">${V2.navRow('/settings/downloads', 'download', 'Download settings', `${S.downloads.parallel} sources at once · ${S.downloads.maxPerEpub} chapters per EPUB`)}${V2.navRow('/settings/tabs', 'tab', 'Manage tabs', WNA.tabs().map((x) => x.name).join(', '))}${V2.navRow('/settings/data', 'backup', 'Data & backup', `Last backup ${fmt.ago(S.backups[0].at)}`)}</div>
      <div class="sec-h"><h2>System</h2></div>
      <div class="seglist">${V2.navRow('/settings/notifications', 'notifications', 'Notifications', 'All categories on')}${V2.navRow('/settings/update', 'system_update', 'App updates', `Version ${S.app.version}`)}</div>`);
    ctx.el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-lay]'); if (!b) return; S.layout = b.dataset.lay;
      ctx.el.querySelectorAll('[data-lay]').forEach((x) => x.classList.toggle('sel', x === b));
      V2.snack(b.dataset.lay === 'auto' ? 'Layout follows the screen you’re using' : `Always use the ${b.dataset.lay} screen layout`);
    });
  };

  // ---------- voice & speech ----------
  const VOICES = [['Bella', 'American, female'], ['Sarah', 'American, female'], ['Nicole', 'American, female, whispery'], ['Sky', 'American, female'], ['Adam', 'American, male'], ['Michael', 'American, male'], ['Emma', 'British, female'], ['Isabella', 'British, female'], ['George', 'British, male'], ['Lewis', 'British, male']];
  const ENGINES = ['Kokoro (built in)', 'Google Speech Services', 'Samsung text-to-speech'];
  const SAMPLE = 'The rain had stopped by the time she reached the tower. Somewhere above, a bell rang twice — and then, impossibly, a third time.';
  const tui = { sessionCleared: false, sample: SAMPLE, dirty: false, saved: null };
  V2.screens.settingsPage = (ctx) => {
    const fn = V2.settingsPages[ctx.params.page];
    if (fn) return fn(ctx);
    V2.subPage(ctx, 'Settings', '', `<div class="empty"><div class="blob">${ic('settings')}</div><h3>Unknown settings page</h3><button class="btn tonal" data-to="/settings">All settings</button></div>`);
  };
  V2.settingsPages = {};
  V2.settingsPages.tts = (ctx) => {
    const t = S.tts; const page = ctx.el;
    if (!tui.saved) tui.saved = JSON.stringify(t);
    const ses = WNA.session;
    const kokoro = /kokoro/i.test(t.engine);
    const pauses = [['phrasePauseMs', 'Extra at commas and phrases', 0, 400], ['sentencePauseMs', 'Between sentences', 0, 1000], ['paragraphPauseMs', 'Between paragraphs', 0, 1500], ['scenePauseMs', 'At scene breaks', 0, 2000]];
    V2.subPage(ctx, 'Voice & speech', '', `
      ${ses && !tui.sessionCleared ? `<div class="wrap"><section class="card pcard tts-ses"><span class="t-lm">SAVED TTS SESSION</span><b class="t-tm">${esc(ses.chapterTitle)} (sentence ${ses.index + 1})</b><span class="t-bs">${esc(ses.storyTitle)}</span><div class="row" style="margin-top:10px"><button class="btn filled" data-a="resume">${ic('play_arrow', 'fill')}Resume TTS</button><button class="btn text" data-a="clearses" style="color:inherit">Clear session</button></div></section></div>` : ''}
      <div class="wrap" style="margin-top:12px"><section class="card"><div class="row"><span class="pat-ic">${ic('tune')}</span><h3 class="grow">Profile</h3></div><p class="t-bs muted prof-st" style="margin:8px 0 12px">${esc(t.profileStatus)}</p>
        <div class="row" style="flex-wrap:wrap"><button class="btn tonal s" data-a="pswitch">${ic('swap_horiz')}Switch</button><button class="btn tonal s" data-a="psave">${ic('save')}Save as profile</button><button class="btn text s" data-a="pdel" style="color:var(--error)">Delete</button></div></section></div>
      <div class="sec-h"><h2>Voice</h2></div>
      <div class="wrap"><section class="card rate-card"><div class="row"><div class="grow"><b class="t-tm">Speech rate</b><p class="t-bs muted">How fast the voice reads</p></div><button class="ib tonal" data-a="rmin">${ic('remove')}</button><b class="rate-v">${t.rate.toFixed(2).replace(/0$/, '')}×</b><button class="ib tonal" data-a="rplus">${ic('add')}</button></div>${V2.sliderHtml('rate', t.rate, 0.5, 3)}
        ${kokoro ? '' : `<div class="row" style="margin-top:12px"><b class="t-tm grow">Pitch</b><b class="pitch-v">${t.pitch.toFixed(1)}</b></div>${V2.sliderHtml('pitch', t.pitch, 0.5, 2)}`}</section></div>
      <div class="seglist" style="margin-top:12px">
        <button class="li" data-a="engine"><span class="lead">${ic('memory')}</span><span class="tx"><b>Engine</b><span>${esc(t.engine)}</span></span>${ic('arrow_drop_down')}</button>
        <button class="li" data-a="voice"><span class="lead">${ic('record_voice_over')}</span><span class="tx"><b>Voice</b><span>${esc(t.voiceLabel)}</span></span>${ic('arrow_drop_down')}</button></div>
      ${kokoro ? `<div class="wrap" style="margin-top:12px"><section class="card kok"><div class="row"><span class="pat-ic">${ic('download_done')}</span><div class="grow"><b class="t-tm">Kokoro voices</b><p class="t-bs muted kok-st">${t.kokoroInstalledMb ? `Installed. Uses ${t.kokoroInstalledMb} MB on this phone.` : 'Not installed.'}</p></div></div>
        <div class="kok-run"></div><div class="row" style="flex-wrap:wrap;margin-top:10px">${t.kokoroInstalledMb ? `<button class="btn tonal s" data-a="ktest">${ic('speed')}Test speed</button><button class="btn tonal s" data-a="kres">Last results</button><button class="btn text s" data-a="krm" style="color:var(--error)">Remove</button>` : `<button class="btn filled s" data-a="kinst">${ic('download')}Install · 369 MB</button>`}</div></section></div>` : ''}
      <div class="sec-h"><h2>Behaviour</h2></div>
      <div class="seglist">${V2.swRow('replayAfterBreaks', 'Replay a little after long breaks', 'Repeats the last sentence when you resume after a while', t.replayAfterBreaks)}${V2.swRow('sayTitles', 'Say chapter titles', 'Announce each chapter before reading it', t.sayTitles)}${V2.swRow('skipWholeChapters', 'Headphone and lock-screen skips move whole chapters', 'Instead of jumping 15 and 30 seconds', t.skipWholeChapters)}</div>
      <div class="sec-h"><h2>Pauses</h2></div>
      <div class="wrap"><section class="card pauses">${pauses.map(([k, l]) => `<div class="pz"><div class="row"><span class="grow t-bm">${l}</span><b class="t-ll pz-v" data-pv="${k}">${t[k]} ms</b></div>${V2.sliderHtml(k, t[k], 0, pauses.find((p) => p[0] === k)[3])}</div>`).join('')}</section></div>
      <div class="sec-h"><h2>Try it</h2></div>
      <div class="wrap"><section class="card try"><label class="tf"><label>Sample text</label><textarea data-sample rows="3">${esc(tui.sample)}</textarea></label><div class="try-run"></div>
        <div class="row" style="margin-top:12px"><button class="btn filled" data-a="sample">${ic('play_arrow', 'fill')}Play sample</button><button class="btn text" data-a="resetsample">Reset text</button></div></section></div>
      <div class="tts-save"><button class="btn filled l block" data-a="save" ${tui.dirty ? '' : 'disabled'}>${ic('save')}Save TTS</button></div>`);
    page.querySelector('.scroll').style.paddingBottom = 'calc(var(--mini-space) + 110px)';
    const dirty = () => { tui.dirty = JSON.stringify(t) !== tui.saved; page.querySelector('[data-a="save"]').disabled = !tui.dirty; if (tui.dirty) t.profileStatus = 'Based on "Google", with changes not saved to it.'; };
    const rateTxt = (v) => `${(+v).toFixed(2).replace(/0$/, '')}×`;
    const defs = {
      rate: { min: 0.5, max: 3, step: 0.05, fmt: rateTxt, set: (v) => { t.rate = v; page.querySelector('.rate-v').textContent = rateTxt(v); dirty(); }, done: () => WNA.player.setRate && WNA.player.state().loaded && WNA.player.setRate(Math.round(t.rate * 100) / 100) },
      pitch: { min: 0.5, max: 2, step: 0.05, fmt: (v) => v.toFixed(2), set: (v) => { t.pitch = v; const p = page.querySelector('.pitch-v'); if (p) p.textContent = v.toFixed(2); dirty(); } },
    };
    pauses.forEach(([k, , mn, mx]) => (defs[k] = { min: mn, max: mx, step: 25, fmt: (v) => `${v} ms`, set: (v) => { t[k] = v; page.querySelector(`[data-pv="${k}"]`).textContent = `${v} ms`; dirty(); } }));
    V2.bindSliders(page, defs);
    const setRate = (v) => { v = V2.clamp(Math.round(v * 20) / 20, 0.5, 3); t.rate = v; const sl = page.querySelector('[data-sl="rate"]'); sl.style.setProperty('--v', (v - 0.5) / 2.5); page.querySelector('.rate-v').textContent = rateTxt(v); dirty(); };
    page.addEventListener('input', (e) => { if (e.target.matches('[data-sample]')) tui.sample = e.target.value; });
    page.addEventListener('click', async (e) => {
      const sw = e.target.closest('[data-sw]');
      if (sw) { const k = sw.dataset.sw; t[k] = !t[k]; sw.querySelector('.switch').classList.toggle('on', t[k]); dirty(); return; }
      const a = e.target.closest('[data-a]'); if (!a) return;
      switch (a.dataset.a) {
        case 'resume': WNA.player.load(ses.storyId, ses.chapterId, { index: ses.index }); V2.go('/player'); break;
        case 'clearses': tui.sessionCleared = true; a.closest('.wrap').remove(); V2.snack('Session cleared', { action: 'Undo', onAction: () => { tui.sessionCleared = false; V2.replace('/settings/tts?r=' + Date.now() % 997); } }); break;
        case 'rmin': setRate(t.rate - 0.05); break;
        case 'rplus': setRate(t.rate + 0.05); break;
        case 'pswitch': { const v = await V2.choose({ title: 'Switch profile', options: [...t.profiles.map((p) => ({ value: p.id, label: p.name, sub: `${p.rate}× · ${p.voiceIdentifier}` })), { value: 'none', label: 'No profile', sub: 'Keep current settings' }] }); if (v && v !== 'none') { const p = t.profiles.find((x) => x.id === v); Object.assign(t, { rate: p.rate, pitch: p.pitch, sentencePauseMs: p.sentencePauseMs, paragraphPauseMs: p.paragraphPauseMs, scenePauseMs: p.scenePauseMs, phrasePauseMs: p.phrasePauseMs, profileStatus: `Using "${p.name}".` }); V2.snack(`Switched to ${p.name}`); V2.replace('/settings/tts?r=' + Date.now() % 997); } break; }
        case 'psave': { const v = await V2.prompt({ title: 'Save as profile', fields: [{ label: 'Profile name', placeholder: 'Night listening' }] }); if (v && v[0]) { t.profiles.push({ id: 'p' + Date.now(), name: v[0], rate: t.rate, pitch: t.pitch, sentencePauseMs: t.sentencePauseMs, paragraphPauseMs: t.paragraphPauseMs, scenePauseMs: t.scenePauseMs, phrasePauseMs: t.phrasePauseMs, voiceIdentifier: t.voiceIdentifier }); t.profileStatus = `Using "${v[0]}".`; page.querySelector('.prof-st').textContent = t.profileStatus; V2.snack(`Saved profile “${v[0]}”`); } break; }
        case 'pdel': if (await V2.confirm({ title: 'Delete profile?', text: 'Your current settings stay as they are.', ok: 'Delete', danger: true, icon: 'delete' })) { t.profiles.splice(0, 1); t.profileStatus = 'No profile. Settings are saved on their own.'; page.querySelector('.prof-st').textContent = t.profileStatus; V2.snack('Profile deleted'); } break;
        case 'engine': { const v = await V2.choose({ title: 'Engine', value: t.engine, options: ENGINES.map((x) => ({ value: x, label: x, sub: /Kokoro/.test(x) ? 'Natural voices, runs on this phone' : 'Uses the system voice' })) }); if (v && v !== t.engine) { t.engine = v; t.voiceLabel = /Kokoro/.test(v) ? 'Bella (American, female)' : 'English (United States) · sfg'; dirty(); V2.replace('/settings/tts?r=' + Date.now() % 997); } break; }
        case 'voice': { const kok = /Kokoro/.test(t.engine); const v = await V2.choose({ title: 'Voice', sub: kok ? 'Kokoro voices' : 'System voices', value: t.voiceLabel, options: (kok ? VOICES.map(([n, d]) => `${n} (${d})`) : ['English (United States) · sfg', 'English (United States) · iob', 'English (United Kingdom) · gba']).map((x) => ({ value: x, label: x.split(' (')[0], sub: x.includes('(') ? x.split('(')[1].replace(')', '') : '' })) }); if (v) { t.voiceLabel = v; a.querySelector('.tx span').textContent = v; dirty(); } break; }
        case 'ktest': { const run = page.querySelector('.kok-run'); run.innerHTML = `<div class="ai-job"><span class="loader accent"></span><div class="grow"><b class="t-ts">Testing speed…</b>${V2.wavy(0.1)}</div></div>`; for (let i = 1; i <= 5; i++) { await V2.wait(500); if (!page.isConnected) return; V2.setWavy(run.querySelector('.wavy'), i / 5); } run.innerHTML = `<p class="t-bs accent" style="margin-top:8px">${ic('check_circle', 's16')} 4.1× real time on this phone · fast enough to keep up at ${t.rate}×</p>`; break; }
        case 'kres': V2.sheet({ title: 'Last results', sub: 'Kokoro speed test', body: `<div class="seglist">${[['This phone', '4.1× real time', 'Today'], ['Gaming PC', '9.6× real time', '3 days ago'], ['MacBook', '6.8× real time', 'Last week']].map(([a1, b1, c1]) => `<div class="li"><span class="lead">${ic(a1 === 'This phone' ? 'smartphone' : 'computer')}</span><span class="tx"><b>${a1}</b><span>${c1}</span></span><b class="t-ll accent">${b1}</b></div>`).join('')}</div>` }); break;
        case 'krm': if (await V2.confirm({ title: 'Remove Kokoro voices?', text: 'Frees 369 MB. Generated audio stays, but new chapters can’t be generated on this phone.', ok: 'Remove', danger: true, icon: 'delete' })) { t.kokoroInstalledMb = 0; V2.replace('/settings/tts?r=' + Date.now() % 997); setTimeout(() => V2.snack('Kokoro voices removed'), 300); } break;
        case 'kinst': t.kokoroInstalledMb = 369; V2.snack('Installing Kokoro voices…'); V2.replace('/settings/tts?r=' + Date.now() % 997); break;
        case 'sample': { const run = page.querySelector('.try-run'); if (a.dataset.on) return; a.dataset.on = 1; a.innerHTML = `${ic('graphic_eq')}Playing…`; const words = tui.sample.split(/\s+/); run.innerHTML = `<p class="try-p">${words.map((w, i) => `<span data-w="${i}">${esc(w)}</span>`).join(' ')}</p>`; for (let i = 0; i < words.length; i++) { const n = run.querySelector(`[data-w="${i}"]`); if (!n) return; n.classList.add('on'); await V2.wait(Math.max(90, 300 / t.rate) + (/[,.—]$/.test(words[i]) ? t.phrasePauseMs : 0)); } delete a.dataset.on; a.innerHTML = `${ic('play_arrow', 'fill')}Play sample`; break; }
        case 'resetsample': tui.sample = SAMPLE; page.querySelector('[data-sample]').value = SAMPLE; break;
        case 'save': tui.saved = JSON.stringify(t); tui.dirty = false; a.disabled = true; V2.snack('Voice settings saved'); break;
      }
    });
  };
})();
