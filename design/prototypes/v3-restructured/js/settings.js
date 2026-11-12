/* v3 Restructured — Settings hub (grouped) and Voice & Speech. Other sub-pages are in settings-pages.js (A.settingsPages). */
(function () {
  const W = window.WNA, A = window.APP;
  if (!W || !A) return;
  const { esc, fmt } = W;
  A.settingsPages = A.settingsPages || {};

  const row = (icon, title, sub, path, extra = '') => `<button class="li" ${path ? `data-go="${path}"` : ''}><span class="lead">${A.icon(icon)}</span><div class="grow"><div class="t">${title}</div>${sub ? `<div class="s">${sub}</div>` : ''}</div>${extra || A.icon('chevron_right', 'trail')}</button>`;

  A.screens.settings = {
    persistent: true, navId: 'settings',
    render() {
      const el = A.view('setv');
      function render() {
        const st = W.settings; const tts = st.tts;
        const nar = W.narration.state();
        const rules = (st.cleanup.regex || []).length + (st.cleanup.sentences || []).length;
        el.innerHTML = `${A.appbar({ title: 'Settings', big: true })}
          <div class="pad narrow">
            <div class="card tint pad16 libsum"><div class="eyebrow">On this phone</div>
              <div class="statgrid"><div><b>${A.stories().length}</b><span>novels</span></div><div><b>${fmt.num(W.stats.downloaded)}</b><span>chapters offline</span></div><div><b>${nar.storage.used}</b><span>audio</span></div></div>
              <div class="muted" style="font-size:12.5px;margin-top:10px">Last full backup ${fmt.ago(st.backups[0].at)} · ${st.backups[0].size}</div></div>
            <div class="section-label">Listening & reading</div>
            <div class="list">${row('record_voice_over', 'Voice & Speech', `${tts.engine.replace(' (built in)', '')} · ${tts.voiceLabel.split(' ')[0]} · ${tts.rate}×`, '/settings/tts')}${row('cleaning_services', 'Text cleanup', `${rules} rules and sentences`, '/settings/cleanup')}</div>
            <div class="section-label">Library</div>
            <div class="list">${row('label', 'Manage tabs', `${W.tabs().length} tabs · ${W.tabs().map((t) => t.name).join(', ')}`, '/settings/tabs')}${row('download', 'Downloads', `${st.downloads.parallel} sources at once · ${(st.downloads.delayMin / 1000).toFixed(1)}–${(st.downloads.delayMax / 1000).toFixed(1)} s delay`, '/settings/downloads')}
              <div class="li"><span class="lead">${A.icon('devices_fold')}</span><div class="grow"><div class="t">Large screen layout</div><div class="s">How the inner screen is used</div><div class="seg" style="margin-top:10px">${['auto', 'cover', 'inner'].map((k) => `<button data-layout="${k}" class="${st.layout === k ? 'on' : ''}">${k[0].toUpperCase() + k.slice(1)}</button>`).join('')}</div></div></div></div>
            <div class="section-label">Intelligence</div>
            <div class="list">${row('auto_awesome', 'AI settings', `OpenRouter · $${W.ai.spend.month.toFixed(2)} this month`, '/settings/ai')}</div>
            <div class="section-label">System</div>
            <div class="list">${row('notifications', 'Notifications', 'Allowed · 4 categories on', '/settings/notifications')}${row('backup', 'Data & backup', 'Backups, sources, storage', '/settings/data')}${row('system_update', 'App updates', `Version ${st.app.version}`, '/settings/update')}</div>
            <div class="foot-note">Webnovel Archiver ${st.app.version}<br>Made for offline reading and listening</div>
          </div>`;
      }
      W.on(el, 'click', '[data-go]', (e, b) => W.go(b.dataset.go));
      W.on(el, 'click', '[data-layout]', (e, b) => { W.settings.layout = b.dataset.layout; render(); A.toast(`Layout: ${b.textContent}`); });
      render();
      return { el, update: render };
    },
  };

  A.screens.settingsPage = {
    navId: 'settings',
    render(route) {
      const page = route.params.page;
      const el = A.view('setpage');
      const fn = page === 'tts' ? tts : A.settingsPages[page];
      if (!fn) { el.innerHTML = A.appbar({ title: 'Settings', back: true }) + `<div class="empty">${A.icon('settings')}<h3>Not found</h3></div>`; return { el }; }
      const dispose = fn(el, route);
      return { el, dispose };
    },
  };

  // ---------- Voice & Speech ----------
  const VOICES = { kokoro: ['Bella (American, female)', 'Sarah (American, female)', 'Nicole (American, female)', 'Michael (American, male)', 'Adam (American, male)', 'Emma (British, female)', 'George (British, male)'], google: ['English (US) · sfg', 'English (US) · iol', 'English (UK) · gba'] };
  const SAMPLE = 'Hecate drew the pistol in one smooth motion. “Burn the witch?” she said, and the crowd went very quiet. “Good luck.”';
  function tts(el) {
    const t = W.settings.tts;
    let base = JSON.stringify(t);
    let sample = SAMPLE, samplePlaying = false, kokoro = 'installed';
    const isKokoro = () => t.engine.startsWith('Kokoro');
    const NUM = { rate: { step: 0.1, min: 0.5, max: 3, unit: '×', dp: 1 }, pitch: { step: 0.1, min: 0.5, max: 2, unit: '×', dp: 1 }, phrasePauseMs: { step: 10, min: 0, max: 500, unit: ' ms' }, sentencePauseMs: { step: 25, min: 0, max: 1500, unit: ' ms' }, paragraphPauseMs: { step: 25, min: 0, max: 2000, unit: ' ms' }, scenePauseMs: { step: 50, min: 0, max: 3000, unit: ' ms' } };
    const num = (key, label, sub = '') => A.stepRow(key, t[key], NUM[key], label, sub);
    function render() {
      const sess = W.session;
      const dirty = JSON.stringify(t) !== base;
      el.innerHTML = `${A.appbar({ title: 'Voice & Speech', sub: `${t.engine} · ${t.voiceLabel.split(' (')[0]}`, back: true })}
        <div class="pad narrow tts">
          ${sess ? `<div class="card tint pad16"><div class="eyebrow">Saved TTS session</div><div class="t">${esc(sess.chapterTitle)} <span class="muted">(sentence ${sess.index + 1})</span></div><div class="muted" style="font-size:12.5px">${esc(sess.storyTitle)}</div><div class="btnrow" style="margin-top:12px"><button class="btn sm fill" data-t="resume">${A.icon('play_arrow', 'sm f')}Resume TTS</button><button class="btn sm text" data-t="clearsess">Clear session</button></div></div>` : ''}
          <div class="section-label">Profile</div>
          <div class="card pad16"><div class="row">${A.icon('tune', 'gold')}<div class="grow muted" style="font-size:13.5px">${esc(t.profileStatus)}</div></div><div class="btnrow" style="margin-top:12px"><button class="btn sm surface" data-t="switch">Switch</button><button class="btn sm surface" data-t="saveprof">Save as profile</button><button class="btn sm text danger" data-t="delprof">Delete</button></div></div>
          <div class="section-label">Voice</div>
          <div class="list">
            <button class="li" data-t="engine"><span class="lead">${A.icon('memory')}</span><div class="grow"><div class="s">Engine</div><div class="t">${esc(t.engine)}</div></div>${A.icon('expand_more', 'trail')}</button>
            <button class="li" data-t="voice"><span class="lead">${A.icon('record_voice_over')}</span><div class="grow"><div class="s">Voice</div><div class="t">${esc(t.voiceLabel)}</div></div>${A.icon('expand_more', 'trail')}</button>
            ${num('rate', 'Rate', 'Speaking speed')}${isKokoro() ? '<div class="li"><div class="grow"><div class="t">Pitch</div><div class="s">Not available for Kokoro voices</div></div></div>' : num('pitch', 'Pitch')}
          </div>
          ${isKokoro() ? `<div class="card pad16" style="margin-top:12px"><div class="row">${A.icon(kokoro === 'installed' ? 'download_done' : 'cloud_download', 'gold')}<div class="grow"><b>Kokoro voices</b><div class="muted" style="font-size:12.5px">${kokoro === 'installed' ? `Installed. Uses ${t.kokoroInstalledMb} MB on this phone.` : kokoro === 'busy' ? 'Installing…' : `Not installed · ${t.kokoroInstalledMb} MB download`}</div></div></div>
            ${kokoro === 'busy' ? `<div style="margin-top:12px">${A.prog(0, 'indet')}</div>` : kokoro === 'installed' ? `<div class="btnrow" style="margin-top:12px"><button class="btn sm surface" data-t="test">Test speed</button><button class="btn sm surface" data-t="results">Last results</button><button class="btn sm text danger" data-t="remove">Remove</button></div>` : `<button class="btn sm fill" data-t="install" style="margin-top:12px">Install voices</button>`}</div>` : ''}
          <div class="section-label">Behavior</div>
          <div class="list">${[['replayAfterBreaks', 'Replay a little after long breaks', 'Repeats the last sentence when you come back'], ['sayTitles', 'Say chapter titles', 'Announces each chapter before reading'], ['skipWholeChapters', 'Headphone and lock-screen skips move whole chapters', 'Otherwise they skip 15 / 30 seconds']].map(([k, l, s]) => `<button class="li" data-sw="${k}"><div class="grow"><div class="t">${l}</div><div class="s">${s}</div></div>${A.sw(t[k])}</button>`).join('')}</div>
          <div class="section-label">Pauses</div>
          <div class="list">${num('phrasePauseMs', 'Commas and phrases', 'Extra pause')}${num('sentencePauseMs', 'Between sentences')}${num('paragraphPauseMs', 'Between paragraphs')}${num('scenePauseMs', 'At scene breaks')}</div>
          <div class="section-label">Try it</div>
          <div class="card pad16"><div class="field"><textarea data-r="sample">${esc(sample)}</textarea></div><div class="btnrow" style="margin-top:12px"><button class="btn sm fill" data-t="sample">${samplePlaying ? '<span class="spinner"></span>Playing…' : A.icon('play_arrow', 'sm f') + 'Play sample'}</button><button class="btn sm text" data-t="resetsample">Reset text</button></div></div>
        </div>
        <div class="stickyfoot" ${dirty ? '' : 'hidden'}><button class="btn fill block" data-t="save">${A.icon('save')}Save TTS</button></div>`;
      el.querySelector('[data-r=sample]').addEventListener('input', (e) => (sample = e.target.value));
    }
    A.bindSteppers(el, (k, v) => { t[k] = v; if (k === 'rate') W.player.setRate(v); t.profileStatus = 'Based on "Google", with changes not saved to it.'; el.querySelector('.stickyfoot').hidden = false; });
    W.on(el, 'click', '[data-sw]', (e, b) => { const k = b.dataset.sw; t[k] = !t[k]; render(); });
    W.on(el, 'click', '[data-t]', async (e, b) => {
      const a = b.dataset.t;
      if (a === 'resume') { const s = W.session; W.player.load(s.storyId, s.chapterId, { index: s.index, play: true }); A.toast('Resumed', 'Open player', () => W.go('/player')); }
      else if (a === 'clearsess') { if (await A.confirm('Clear saved session?', 'Continue listening will no longer offer this position.', 'Clear', true)) { A.state.resumeDismissed = true; A.renderDock(); A.toast('Session cleared'); } }
      else if (a === 'switch') A.menu({ title: 'Switch profile', sections: [{ items: (t.profiles || []).map((p) => ({ icon: 'person', label: p.name, sub: `${p.rate}× · ${p.voiceIdentifier}`, fn: () => { Object.assign(t, { rate: p.rate, sentencePauseMs: p.sentencePauseMs, paragraphPauseMs: p.paragraphPauseMs, scenePauseMs: p.scenePauseMs, phrasePauseMs: p.phrasePauseMs }); t.profileStatus = `Using “${p.name}”.`; render(); A.toast(`Switched to ${p.name}`); } })) }] });
      else if (a === 'saveprof') { const v = await A.form('Save as profile', [{ name: 'name', label: 'Profile name', placeholder: 'Night listening' }]); if (v && v.name) { (t.profiles = t.profiles || []).push({ id: 'p' + Date.now(), name: v.name, rate: t.rate, voiceIdentifier: t.voiceIdentifier, sentencePauseMs: t.sentencePauseMs, paragraphPauseMs: t.paragraphPauseMs, scenePauseMs: t.scenePauseMs, phrasePauseMs: t.phrasePauseMs }); t.profileStatus = `Using “${v.name}”.`; render(); A.toast('Profile saved'); } }
      else if (a === 'delprof') { if (await A.confirm('Delete profile?', 'Your current settings stay as they are.', 'Delete', true)) { t.profileStatus = 'Custom settings (no profile).'; render(); } }
      else if (a === 'engine') A.menu({ title: 'Engine', sections: [{ items: ['Kokoro (built in)', 'Google Speech Services'].map((x) => ({ icon: 'memory', label: x, on: t.engine === x, fn: () => { t.engine = x; t.voiceLabel = (x.startsWith('Kokoro') ? VOICES.kokoro : VOICES.google)[0]; render(); } })) }] });
      else if (a === 'voice') A.menu({ title: 'Voice', sub: t.engine, sections: [{ items: (isKokoro() ? VOICES.kokoro : VOICES.google).map((x) => ({ icon: 'record_voice_over', label: x, on: t.voiceLabel === x, fn: () => { t.voiceLabel = x; render(); A.toast(`Voice: ${x.split(' (')[0]}`); } })) }] });
      else if (a === 'test') { b.innerHTML = '<span class="spinner"></span>Testing…'; setTimeout(() => { render(); A.toast('Kokoro runs at 3.2× real time on this phone'); }, 1600); }
      else if (a === 'results') A.sheet({ title: 'Last speed test', body: `<div class="list">${[['Real-time factor', '3.2×'], ['First audio after', '0.8 s'], ['Memory peak', '412 MB'], ['Tested', '2 days ago']].map(([l, v]) => `<div class="li"><div class="grow">${l}</div><span class="val">${v}</span></div>`).join('')}</div>` });
      else if (a === 'remove') { if (await A.confirm('Remove Kokoro voices?', `Frees ${t.kokoroInstalledMb} MB. Generated audio stays.`, 'Remove', true)) { kokoro = 'none'; render(); } }
      else if (a === 'install') { kokoro = 'busy'; render(); setTimeout(() => { kokoro = 'installed'; render(); A.toast('Kokoro voices installed'); }, 2200); }
      else if (a === 'sample') { if (samplePlaying) return; samplePlaying = true; render(); setTimeout(() => { samplePlaying = false; render(); }, 3200); }
      else if (a === 'resetsample') { sample = SAMPLE; render(); }
      else if (a === 'save') { base = JSON.stringify(t); A.toast('Voice settings saved'); el.querySelector('.stickyfoot').hidden = true; }
    });
    render();
  }
})();
