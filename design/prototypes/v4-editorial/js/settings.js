/* v4 Editorial — Settings index (ruled, with leaders) and Voice & Speech. Secondary pages live in settings2.js. */
(function () {
  const A = window.APP, W = A.W, esc = A.esc, I = A.icon;
  const ST = W.settings;

  const idxRow = (go, icon, label, sub, val) => `<button class="idx-row" data-go="${go}">${I(icon, 'lead')}<span class="leaders"><span class="l"><b>${label}</b>${sub ? `<small>${sub}</small>` : ''}</span><span class="d"></span><span class="v">${val || ''}</span></span>${I('chevron_right')}</button>`;
  A.idxRow = idxRow;
  A.sw = (on, k) => `<span class="switch ${on ? 'on' : ''}" data-sw="${k}" role="switch" aria-checked="${on}"></span>`;
  A.srow = (k, label, sub, on) => `<button class="srow" data-act="sw" data-k="${k}"><span class="grow"><b>${label}</b>${sub ? `<small>${sub}</small>` : ''}</span>${A.sw(on, k)}</button>`;

  A.screens.settings = (page) => {
    const tts = ST.tts; const cl = ST.cleanup || { regex: [], sentences: [] };
    const render = () => {
      page.innerHTML = `${A.rootTop('<span style="width:44px"></span>')}
      <div class="set-wrap">
        <div class="set-h"><div class="kicker">Settings</div><h1 class="disp">House <i>rules</i></h1><p class="dek">How the archive looks, reads and listens.</p></div>
        ${A.secHead('i', 'Appearance')}
        <div class="ctl" style="border-top:1px solid var(--rule-2)"><div class="top"><b>Large screen layout</b></div><small>Which layout to use on the Fold’s inner screen.</small>
          <div class="chips wrapy" style="margin-top:12px">${[['auto', 'Auto'], ['cover', 'Cover'], ['inner', 'Inner']].map(([k, l]) => `<button class="chip ${ST.layout === k ? 'on' : ''}" data-act="layout" data-v="${k}">${l}</button>`).join('')}</div></div>
        ${A.secHead('ii', 'Reading &amp; audio')}
        <div class="idx">
          ${idxRow('/settings/tts', 'record_voice_over', 'Voice &amp; Speech', 'Engine, voice, speed and pauses', `${esc(tts.voiceLabel.split(' ')[0])} · ${tts.rate}×`)}
          ${idxRow('/settings/cleanup', 'cleaning_services', 'Text Cleanup Rules', 'Strip notices and junk from chapters', `${cl.regex.length + cl.sentences.length} rules`)}
          ${idxRow('/settings/ai', 'auto_awesome', 'AI Settings', 'OpenRouter key, models and spend', `$${W.ai.spend.month.toFixed(2)}/mo`)}
        </div>
        ${A.secHead('iii', 'Library')}
        <div class="idx">
          ${idxRow('/settings/notifications', 'notifications', 'Notifications', '', '4 on')}
          ${idxRow('/settings/tabs', 'shelves', 'Manage Tabs', 'Your shelves and their order', `${W.tabs().length} tabs`)}
          ${idxRow('/settings/downloads', 'download', 'Download Settings', 'Pace, parallel sources, EPUB size', `${ST.downloads.parallel} at once`)}
          ${idxRow('/settings/data', 'inventory_2', 'Data &amp; Backup', 'Export, restore and storage', `${ST.backups.length} backups`)}
        </div>
        ${A.secHead('iv', 'About')}
        <div class="idx">${idxRow('/settings/update', 'system_update', 'App Updates', '', 'Version ' + ST.app.version)}</div>
        <div class="ornament" style="margin:40px 0 10px">❦</div>
        <p class="it faint" style="text-align:center;margin:0 0 20px;font-size:14px">Webnovel Archiver · ${W.stats.chapters.toLocaleString('en-US')} chapters kept</p>
      </div>`;
    };
    render();
    A.acts(page, {
      contents: () => A.contents(),
      layout: (t) => { ST.layout = t.dataset.v; render(); A.toast(`Layout: ${t.textContent}`); },
    });
  };

  // ---------- Voice & Speech ----------
  const ENGINES = ['Kokoro (built in)', 'Google Speech Services', 'Samsung Text-to-speech'];
  const VOICES = { 'Kokoro (built in)': ['Bella (American, female)', 'Sarah (American, female)', 'Nicole (American, female)', 'Adam (American, male)', 'Michael (American, male)', 'Emma (British, female)', 'George (British, male)'], 'Google Speech Services': ['English (US) · sfg', 'English (US) · iob', 'English (UK) · rjs'], 'Samsung Text-to-speech': ['English (US) · Female 1', 'English (US) · Male 1'] };
  const SAMPLE = 'The amphitheater came into view as we walked on, the crowd thickening with every step. The whole city was here.';
  const PROFILES = ['Google', 'Kokoro · Night', 'Fast commute'];

  A.screens.settingsTts = (page) => {
    const t = ST.tts;
    const P = W.player;
    const ui = { sample: SAMPLE, playing: false, dirty: false };
    let wordTimer = null;
    const pauses = [['phrasePauseMs', 'Extra at commas and phrases', 0, 400], ['sentencePauseMs', 'Between sentences', 0, 1500], ['paragraphPauseMs', 'Between paragraphs', 0, 2000], ['scenePauseMs', 'At scene breaks', 0, 3000]];
    const isKokoro = () => /Kokoro/.test(t.engine);
    function render() {
      const ses = W.session; const st = P.state();
      const sesLine = st.loaded ? `${esc(st.chapter.title)} (sentence ${st.index + 1})` : ses ? `${esc(ses.chapterTitle)} (sentence ${ses.index + 1})` : null;
      page.innerHTML = `${A.bar({ title: 'Voice &amp; Speech', sub: `${esc(t.voiceLabel)} · ${t.rate}×` })}
        <div class="set-wrap">
          ${sesLine ? `<div class="card accent"><span class="kicker">Saved TTS session</span><h4 style="margin-top:8px">${sesLine}</h4><p>${esc(A.shortTitle((st.loaded ? st.story : W.story(ses.storyId)).title))}</p><div class="acts"><button class="btn sm" data-act="resume">${I('play_arrow', 'fill')}Resume TTS</button><button class="btn sm ghost" data-act="clearSes">Clear session</button></div></div>` : `<div class="card"><h4>No saved session</h4><p>Your place is saved whenever you stop reading aloud.</p></div>`}
          <div class="card"><span class="label">Profile</span><h4 style="margin-top:8px">${esc(t.profileStatus)}</h4><div class="acts"><button class="btn sm tonal" data-act="switchP">Switch</button><button class="btn sm ghost" data-act="saveP">Save as profile</button><button class="btn sm text" data-act="delP">Delete</button></div></div>
          ${A.secHead('i', 'Voice')}
          <div class="idx">
            <button class="idx-row" data-act="engine">${I('memory', 'lead')}<span class="leaders"><span class="l"><b>Engine</b></span><span class="d"></span><span class="v">${esc(t.engine)}</span></span>${I('expand_more')}</button>
            <button class="idx-row" data-act="voice">${I('record_voice_over', 'lead')}<span class="leaders"><span class="l"><b>Voice</b></span><span class="d"></span><span class="v">${esc(t.voiceLabel)}</span></span>${I('expand_more')}</button>
          </div>
          <div class="ctl"><div class="top"><b>Rate</b><div class="stepper"><button data-act="rate" data-d="-0.1" aria-label="Slower">${I('remove')}</button><b>${t.rate.toFixed(1)}</b><button data-act="rate" data-d="0.1" aria-label="Faster">${I('add')}</button></div></div>
            <input type="range" class="rng" data-rng="rate" min="0.5" max="3" step="0.1" value="${t.rate}" style="--p:${((t.rate - 0.5) / 2.5 * 100).toFixed(0)}%" aria-label="Rate"></div>
          ${isKokoro() ? '<p class="sec-hint" style="margin-top:12px">Pitch isn’t adjustable for Kokoro voices.</p>' : `<div class="ctl"><div class="top"><b>Pitch</b><span class="val">${t.pitch.toFixed(1)}</span></div><input type="range" class="rng" data-rng="pitch" min="0.5" max="2" step="0.1" value="${t.pitch}" style="--p:${((t.pitch - 0.5) / 1.5 * 100).toFixed(0)}%" aria-label="Pitch"></div>`}
          ${isKokoro() ? `<div class="card"><span class="label">Kokoro voices</span><h4 style="margin-top:8px">${t.kokoroInstalledMb ? `Installed. Uses ${t.kokoroInstalledMb} MB on this phone.` : 'Not installed.'}</h4><div class="acts">${t.kokoroInstalledMb ? `<button class="btn sm tonal" data-act="test">Test speed</button><button class="btn sm ghost" data-act="results">Last results</button><button class="btn sm text" data-act="remove" style="color:var(--err)">Remove</button>` : '<button class="btn sm" data-act="install">Install (369 MB)</button>'}</div></div>` : ''}
          ${A.secHead('ii', 'Behaviour')}
          <div style="border-top:1px solid var(--rule-2)">
            ${A.srow('replayAfterBreaks', 'Replay a little after long breaks', 'Repeats the last sentence when you come back', t.replayAfterBreaks)}
            ${A.srow('sayTitles', 'Say chapter titles', 'Announce each chapter before it starts', t.sayTitles)}
            ${A.srow('skipWholeChapters', 'Headphone and lock-screen skips move whole chapters', 'Otherwise they skip 15 / 30 seconds', t.skipWholeChapters)}
          </div>
          ${A.secHead('iii', 'Pauses')}
          ${pauses.map(([k, l, mn, mx]) => `<div class="ctl"><div class="top"><b>${l}</b><span class="val" data-val="${k}">${t[k]} ms</span></div><input type="range" class="rng" data-rng="${k}" min="${mn}" max="${mx}" step="10" value="${t[k]}" style="--p:${((t[k] - mn) / (mx - mn) * 100).toFixed(0)}%" aria-label="${l}"></div>`).join('')}
          ${A.secHead('iv', 'Try it')}
          <div class="tryit card" style="margin-top:0">${ui.sample.split(/\s+/).map((w, i) => `<span class="w" data-w="${i}">${esc(w)}</span>`).join(' ')}</div>
          <textarea class="input" id="sample" style="margin-top:10px;min-height:80px" aria-label="Sample text">${esc(ui.sample)}</textarea>
          <div class="act-row" style="margin-top:10px"><button class="btn tonal grow" data-act="sample">${I(ui.playing ? 'stop' : 'play_arrow', 'fill')}${ui.playing ? 'Stop' : 'Play sample'}</button><button class="btn text" data-act="resetText">Reset text</button></div>
          <div class="save-bar"><button class="btn block" data-act="save">${I('check')}Save TTS</button></div>
        </div>`;
      page.querySelector('#sample').addEventListener('input', (e) => { ui.sample = e.target.value || SAMPLE; const box = page.querySelector('.tryit'); box.innerHTML = ui.sample.split(/\s+/).map((w, i) => `<span class="w" data-w="${i}">${esc(w)}</span>`).join(' '); });
    }
    render();
    page.addEventListener('input', (e) => {
      const r = e.target.closest('[data-rng]'); if (!r) return;
      const k = r.dataset.rng; const v = +r.value; t[k] = v; ui.dirty = true;
      r.style.setProperty('--p', ((v - r.min) / (r.max - r.min) * 100).toFixed(0) + '%');
      if (k === 'rate') { page.querySelector('.stepper b').textContent = v.toFixed(1); page.querySelector('.bar .ttl small').textContent = `${t.voiceLabel} · ${v}×`; }
      else if (k === 'pitch') r.parentNode.querySelector('.val').textContent = v.toFixed(1);
      else page.querySelector(`[data-val="${k}"]`).textContent = v + ' ms';
    });
    function stopSample() { clearInterval(wordTimer); ui.playing = false; page.querySelectorAll('.tryit .w').forEach((w) => w.classList.remove('now')); }
    A.onLeave(() => clearInterval(wordTimer));
    const pick = (title, list, cur, fn) => A.menu({ title, groups: [{ items: list.map((x) => ({ label: esc(x), on: x === cur, end: `<span class="radio ${x === cur ? 'on' : ''}"></span>`, act: () => fn(x) })) }] });
    A.acts(page, {
      resume: () => { const ses = W.session; if (!P.state().loaded) P.load(ses.storyId, ses.chapterId, { index: ses.index, play: true }); else P.play(); A.toast('Resumed'); },
      clearSes: () => A.confirm('Clear the saved session?', 'You’ll start from the top next time you read aloud.', 'Clear', () => { P.stop(); W.session = null; render(); A.toast('Session cleared'); }),
      switchP: () => pick('Switch <i>profile</i>', PROFILES, null, (p) => { t.profileStatus = `Using “${p}”.`; render(); A.toast(`Switched to ${esc(p)}`); }),
      saveP: () => A.dialog({ title: 'Save as profile', body: '<p>Saves the engine, voice, rate and pauses.</p><input class="input" id="pn" placeholder="Profile name" value="Kokoro · Bella 1.8×">', actions: [{ label: 'Cancel' }, { label: 'Save', act: (n) => { const v = n.querySelector('#pn').value || 'New profile'; PROFILES.push(v); t.profileStatus = `Using “${v}”.`; render(); A.toast('Profile saved'); } }] }),
      delP: () => A.confirm('Delete this profile?', 'Your current settings stay as they are.', 'Delete', () => { t.profileStatus = 'No profile · custom settings'; render(); }, true),
      engine: () => pick('Speech <i>engine</i>', ENGINES, t.engine, (e) => { t.engine = e; t.voiceLabel = VOICES[e][0]; ui.dirty = true; render(); }),
      voice: () => pick('Voice', VOICES[t.engine] || VOICES['Kokoro (built in)'], t.voiceLabel, (v) => { t.voiceLabel = v; ui.dirty = true; render(); A.toast(`Voice: ${esc(v)}`); }),
      rate: (b) => { t.rate = Math.max(0.5, Math.min(3, Math.round((t.rate + +b.dataset.d) * 10) / 10)); ui.dirty = true; render(); },
      sw: (b) => { const k = b.dataset.k; t[k] = !t[k]; ui.dirty = true; const s = b.querySelector('.switch'); s.classList.toggle('on', t[k]); s.setAttribute('aria-checked', t[k]); },
      test: (b) => { b.innerHTML = '<span class="spin"></span>Testing…'; setTimeout(() => { if (b.isConnected) b.textContent = 'Test speed'; A.toast('Kokoro runs at 3.2× real time on this phone'); }, 2200); },
      results: () => A.dialog({ title: 'Last results', body: '<dl class="colophon">' + [['Real-time factor', '3.2×'], ['First audio after', '0.9 s'], ['Threads', '4'], ['Tested', 'Nov 8, 2026']].map(([k, v]) => `<div class="leaders"><dt class="l">${k}</dt><span class="d"></span><dd class="v">${v}</dd></div>`).join('') + '</dl>', actions: [{ label: 'Close' }] }),
      remove: () => A.confirm('Remove Kokoro voices?', 'Frees 369 MB. Audio you already generated still plays.', 'Remove', () => { t.kokoroInstalledMb = 0; render(); }, true),
      install: (b) => { b.innerHTML = '<span class="spin"></span>Installing…'; setTimeout(() => { t.kokoroInstalledMb = 369; render(); A.toast('Kokoro voices installed'); }, 2000); },
      sample: () => {
        if (ui.playing) { stopSample(); render(); return; }
        ui.playing = true; render();
        let i = 0; const words = page.querySelectorAll('.tryit .w');
        wordTimer = setInterval(() => { words.forEach((w) => w.classList.toggle('now', +w.dataset.w === i)); i++; if (i > words.length) { stopSample(); render(); } }, 330 / t.rate * 1.6);
      },
      resetText: () => { ui.sample = SAMPLE; stopSample(); render(); },
      save: () => { ui.dirty = false; A.toast('TTS settings saved'); },
    });
  };
})();
