/* v1 Refined — Settings hub + Voice & Speech. */
(function () {
  const V = window.V1;
  const { esc } = WNA;
  const SET = WNA.settings;

  V.row = ({ icon, title, sub = '', act = '', value = '', chev = true, data = '', danger = false }) => `
    <button class="li has-ic ${danger ? 'danger' : ''}" data-act="${act}" ${data}>
      <span class="lic">${V.ic(icon)}</span>
      <div class="grow"><div class="lt">${esc(title)}</div>${sub ? `<div class="ls">${esc(sub)}</div>` : ''}</div>
      ${value ? `<span class="lv">${esc(value)}</span>` : ''}${chev ? V.ic('chevron_right', 'chev') : ''}
    </button>`;
  V.swRow = ({ title, sub = '', on, act, data = '' }) => `
    <div class="li tap" data-act="${act}" ${data} role="switch" aria-checked="${on}">
      <div class="grow"><div class="lt">${esc(title)}</div>${sub ? `<div class="ls">${esc(sub)}</div>` : ''}</div>
      <span class="sw ${on ? 'on' : ''}"></span>
    </div>`;
  V.group = (title, inner, help = '') => `<section class="section"><div class="sec-h"><h2>${esc(title)}</h2></div>${help ? `<p class="sec-help">${esc(help)}</p>` : ''}${inner}</section>`;

  V.screens.settings = () => {
    const cleanup = SET.cleanup || { regex: [], sentences: [] };
    const el = WNA.el(`<div class="screen">${V.appBar({ title: 'Settings', large: true })}<div class="wrap body"></div><div class="pad-bottom"></div></div>`);
    function render() {
      el.querySelector('.body').innerHTML = `
        ${V.group('Appearance', `<div class="card pad"><div class="lt" style="font:var(--t-body-m)">Large screen layout</div><div class="ls" style="font:var(--t-sm);color:var(--text-2);margin:2px 0 12px">How the app uses the Fold’s inner screen.</div>
          <div class="seg">${[['auto', 'Auto'], ['cover', 'Cover'], ['inner', 'Inner']].map(([k, l]) => `<button class="${SET.layout === k ? 'on' : ''}" data-act="layout" data-k="${k}">${l}</button>`).join('')}</div></div>`)}
        ${V.group('Reading & audio', `<div class="list">
          ${V.row({ icon: 'record_voice_over', title: 'Voice & Speech', sub: `${SET.tts.voiceLabel.split(' (')[0]} · Kokoro · ${SET.tts.rate}×`, act: 'go', data: 'data-to="tts"' })}
          ${V.row({ icon: 'cleaning_services', title: 'Text cleanup rules', sub: `${V.plural(cleanup.regex.length, 'rule')} · ${V.plural(cleanup.sentences.length, 'sentence')}`, act: 'go', data: 'data-to="cleanup"' })}
          ${V.row({ icon: 'auto_awesome', title: 'AI settings', sub: `OpenRouter · $${WNA.ai.spend.month.toFixed(2)} this month`, act: 'go', data: 'data-to="ai"' })}
        </div>`)}
        ${V.group('Library', `<div class="list">
          ${V.row({ icon: 'tab', title: 'Manage tabs', sub: WNA.tabs().map((t) => t.name).join(', '), act: 'go', data: 'data-to="tabs"' })}
          ${V.row({ icon: 'download', title: 'Download settings', sub: `${SET.downloads.parallel} sources at once · ${SET.downloads.maxPerEpub} chapters per EPUB`, act: 'go', data: 'data-to="downloads"' })}
        </div>`)}
        ${V.group('System', `<div class="list">
          ${V.row({ icon: 'notifications', title: 'Notifications', sub: 'Allowed · 4 categories', act: 'go', data: 'data-to="notifications"' })}
          ${V.row({ icon: 'backup', title: 'Data & backup', sub: `Last full backup ${WNA.fmt.ago(SET.backups[0].at)}`, act: 'go', data: 'data-to="data"' })}
          ${V.row({ icon: 'system_update', title: 'App updates', sub: `Version ${SET.app.version}`, act: 'go', data: 'data-to="update"' })}
        </div>`)}
        <p class="faint" style="text-align:center;font:var(--t-xs);margin-top:24px">Webnovel Archiver ${esc(SET.app.version)}</p>`;
    }
    render();
    V.bind(el, {
      go: (t) => WNA.go('/settings/' + t.dataset.to),
      layout(t) { SET.layout = t.dataset.k; render(); V.toast(`Layout: ${t.textContent}`); },
    });
    V.watchScroll(el);
    return { el, title: 'Settings' };
  };

  // ---------- Voice & Speech ----------
  const VOICES = ['Bella (American, female)', 'Sarah (American, female)', 'Nicole (American, female)', 'Sky (American, female)', 'Adam (American, male)', 'Michael (American, male)', 'Emma (British, female)', 'Isabella (British, female)', 'George (British, male)', 'Lewis (British, male)'];
  const ENGINES = ['Kokoro (built in)', 'Google Speech Services', 'Samsung text-to-speech'];
  const PAUSES = [['phrasePauseMs', 'Extra at commas and phrases', 0, 300], ['sentencePauseMs', 'Between sentences', 0, 1000], ['paragraphPauseMs', 'Between paragraphs', 0, 1500], ['scenePauseMs', 'At scene breaks', 0, 2000]];
  const SAMPLE = 'Hecate checked the chamber, counted the rounds twice, and stepped into the square. Nobody was going to burn this witch today.';

  V.screens.settings_tts = () => {
    const T = SET.tts;
    const saved = JSON.parse(JSON.stringify(T));
    let dirty = false, sessionCleared = false, sample = SAMPLE, playing = false, testing = false;
    const el = WNA.el(`<div class="screen">${V.appBar({ title: 'Voice & Speech' })}<div class="wrap body"></div><div class="pad-bottom"></div></div>`);
    const $ = (q) => el.querySelector(q);
    const isKokoro = () => T.engine.startsWith('Kokoro');
    const mark = () => { dirty = true; T.profileStatus = 'Based on “Google”, with changes not saved to it.'; saveBar(); };

    function render() {
      const ss = WNA.session;
      const prof = (T.profiles || [])[0];
      $('.body').innerHTML = `
        ${ss && !sessionCleared ? `<section class="section"><div class="card pad sess">
          <div class="gb-l">Saved session</div>
          <b>${esc(ss.chapterTitle)}</b><span>${esc(ss.storyTitle)} · sentence ${ss.index + 1}</span>
          <div class="btn-row" style="margin-top:12px">${V.btn('Resume', 'resume', { kind: 'tonal', icon: 'play_arrow', cls: 'sm' })}${V.btn('Clear session', 'clearsess', { kind: 'text', cls: 'sm' })}</div>
        </div></section>` : ''}
        ${V.group('Profile', `<div class="card pad">
          <div class="row"><span class="lic" style="width:40px;height:40px;border-radius:12px;background:var(--s3);display:grid;place-items:center;color:var(--gold)">${V.ic('tune')}</span><div class="grow"><div style="font:var(--t-body-m)">Custom settings</div><div style="font:var(--t-sm);color:var(--text-2)">${esc(T.profileStatus)}</div></div></div>
          <div class="btn-row" style="margin-top:14px">${V.btn('Switch', 'switch', { kind: 'tonal', icon: 'swap_horiz', cls: 'sm' })}${V.btn('Save as profile', 'saveprof', { kind: 'text', cls: 'sm' })}${V.btn('Delete', 'delprof', { kind: 'text', cls: 'sm', disabled: !prof })}</div>
        </div>`)}
        ${V.group('Speech', `<div class="list">
          <div class="li col" style="align-items:stretch;gap:4px">
            <div class="row"><div class="grow lt">Rate</div><span class="valchip tnum rate-v">${T.rate.toFixed(2).replace(/0$/, '')}×</span></div>
            ${V.slider('rate', 0.5, 3, 0.05, T.rate)}
            <div class="row faint" style="font:var(--t-xs);justify-content:space-between;margin-top:-4px"><span>Slower · 0.5×</span><span>3× · Faster</span></div>
          </div>
          ${isKokoro() ? `<div class="li"><div class="grow"><div class="lt">Pitch</div><div class="ls">Kokoro voices keep their natural pitch</div></div><span class="lv faint">Not available</span></div>` : `<div class="li col" style="align-items:stretch;gap:4px"><div class="row"><div class="grow lt">Pitch</div><span class="valchip tnum pitch-v">${T.pitch.toFixed(2)}</span></div>${V.slider('pitch', 0.5, 2, 0.05, T.pitch)}</div>`}
          ${V.row({ icon: 'settings_voice', title: 'Engine', value: T.engine, act: 'engine' })}
          ${V.row({ icon: 'record_voice_over', title: 'Voice', value: T.voiceLabel, act: 'voice' })}
        </div>`)}
        ${isKokoro() ? `<section class="section"><div class="card pad">
          <div class="row"><div class="grow"><div style="font:var(--t-body-m)">Kokoro voices</div><div style="font:var(--t-sm);color:var(--text-2)">Installed · uses ${T.kokoroInstalledMb} MB on this phone</div></div><span class="pill completed">${V.ic('check')}Ready</span></div>
          <div class="btn-row" style="margin-top:14px">${testing ? `<button class="btn tonal sm" disabled><span class="spin"></span>Testing…</button>` : V.btn('Test speed', 'test', { kind: 'tonal', icon: 'speed', cls: 'sm' })}${V.btn('Last results', 'results', { kind: 'text', cls: 'sm' })}${V.btn('Remove', 'rmkokoro', { kind: 'text', cls: 'sm danger' })}</div>
        </div></section>` : ''}
        ${V.group('Playback', `<div class="list">
          ${V.swRow({ title: 'Replay a little after long breaks', sub: 'Repeats the last sentence when you resume', on: T.replayAfterBreaks, act: 'tg', data: 'data-k="replayAfterBreaks"' })}
          ${V.swRow({ title: 'Say chapter titles', sub: 'Reads the title before each chapter', on: T.sayTitles, act: 'tg', data: 'data-k="sayTitles"' })}
          ${V.swRow({ title: 'Headphone and lock-screen skips move whole chapters', on: T.skipWholeChapters, act: 'tg', data: 'data-k="skipWholeChapters"' })}
        </div>`)}
        ${V.group('Pauses', `<div class="list">${PAUSES.map(([k, l, mn, mx]) => `<div class="li col" style="align-items:stretch;gap:4px"><div class="row"><div class="grow lt">${l}</div><span class="valchip tnum" data-v="${k}">${T[k]} ms</span></div>${V.slider(k, mn, mx, 25, T[k])}</div>`).join('')}</div>`)}
        ${V.group('Try it', `<div class="card pad"><span class="input ta"><textarea name="sample" rows="3">${esc(sample)}</textarea></span>
          <div class="btn-row" style="margin-top:12px">${playing ? `<button class="btn filled sm" data-act="stopsample">${V.ic('stop')}<span class="eq"><i></i><i></i><i></i></span>Playing</button>` : V.btn('Play sample', 'sample', { kind: 'filled', icon: 'play_arrow', cls: 'sm' })}${V.btn('Reset text', 'resetsample', { kind: 'text', cls: 'sm' })}</div></div>`)}`;
      saveBar();
    }
    function saveBar() {
      let b = el.querySelector('.botbar');
      if (dirty && !b) { b = WNA.el(`<div class="botbar savebar"><span class="grow" style="font:var(--t-sm);color:var(--text-2)">Unsaved changes</span>${V.btn('Discard', 'discard', { kind: 'text' })}${V.btn('Save TTS', 'save', { kind: 'filled', icon: 'check' })}</div>`); el.insertBefore(b, el.querySelector('.pad-bottom')); }
      if (!dirty && b) b.remove();
    }
    render();

    el.addEventListener('input', (e) => {
      const t = e.target;
      if (t.name === 'sample') { sample = t.value; return; }
      if (!t.classList.contains('slider')) return;
      V.syncSlider(t);
      const v = +t.value;
      T[t.name] = v;
      if (t.name === 'rate') $('.rate-v').textContent = v.toFixed(2).replace(/0$/, '') + '×';
      else if (t.name === 'pitch') $('.pitch-v').textContent = v.toFixed(2);
      else $(`[data-v="${t.name}"]`).textContent = v + ' ms';
      mark();
    });

    V.bind(el, {
      resume() { const ss = WNA.session; WNA.player.load(ss.storyId, ss.chapterId, { index: ss.index, play: true }); V.toast('Resuming ' + V.short(ss.chapterTitle)); },
      clearsess() { sessionCleared = true; render(); V.toast('Session cleared', { action: 'Undo', onAction: () => { sessionCleared = false; render(); } }); },
      tg(t) { const k = t.dataset.k; T[k] = !T[k]; t.querySelector('.sw').classList.toggle('on', T[k]); t.setAttribute('aria-checked', T[k]); mark(); },
      engine() { V.menu({ title: 'Engine', items: ENGINES.map((x) => ({ act: x, label: x, sub: x.startsWith('Kokoro') ? 'Offline · natural voices' : 'System voice', on: T.engine === x })), onPick(x) { T.engine = x; if (!x.startsWith('Kokoro')) T.voiceLabel = 'English (United States) · sfg'; else T.voiceLabel = VOICES[0]; mark(); render(); } }); },
      voice() {
        if (!isKokoro()) { V.toast('Voices come from the system engine settings'); return; }
        V.sheet({ title: 'Voice', sub: 'Kokoro · tap to preview', body: VOICES.map((x) => `<button class="mi" data-v="${esc(x)}"><span class="radio ${T.voiceLabel === x ? 'on' : ''}"></span><span class="grow">${esc(x)}</span>${V.ic('volume_up')}</button>`).join(''),
          onMount(sh, close) { WNA.on(sh, 'click', '[data-v]', (e, t) => { T.voiceLabel = t.dataset.v; WNA.player.state(); mark(); render(); close(); V.toast(`Voice: ${t.dataset.v.split(' (')[0]}`); }); } });
      },
      switch() { V.menu({ title: 'Switch profile', items: [...(T.profiles || []).map((p) => ({ act: p.id, icon: 'tune', label: p.name, sub: `${p.rate}× · ${p.voiceIdentifier}` })), { act: 'none', icon: 'restart_alt', label: 'Defaults' }], onPick(a) { const p = (T.profiles || []).find((x) => x.id === a); if (p) { ['rate', 'pitch', 'phrasePauseMs', 'sentencePauseMs', 'paragraphPauseMs', 'scenePauseMs'].forEach((k) => (T[k] = p[k])); T.profileStatus = `Using “${p.name}”.`; } dirty = true; render(); V.toast(p ? `Switched to ${p.name}` : 'Defaults loaded'); } }); },
      async saveprof() { const r = await V.dialog({ title: 'Save as profile', html: V.field('name', 'Profile name', 'Night listening'), actions: [{ label: 'Cancel', value: null }, { label: 'Save', value: 'ok', kind: 'filled' }] }); if (r.value) { (T.profiles = T.profiles || []).push({ id: 'p' + Date.now(), name: r.form.name || 'Profile', ...T }); T.profileStatus = `Using “${r.form.name}”.`; dirty = false; render(); V.toast('Profile saved'); } },
      async delprof() { if (await V.confirm('Delete profile?', `“${T.profiles[0].name}” will be removed. Your current settings stay.`, 'Delete')) { T.profiles.shift(); render(); } },
      test() { testing = true; render(); setTimeout(() => { testing = false; render(); V.dialog({ icon: 'speed', title: 'Kokoro speed test', html: '<div class="kv"><span>Speed</span><b>3.2× real time</b><span>First audio</span><b>0.8 s</b><span>Memory</span><b>412 MB peak</b></div><p style="margin-top:12px">This phone keeps up with listening at up to 2.5×.</p>', actions: [{ label: 'OK', value: true, kind: 'filled' }] }); }, 2400); },
      results: () => V.dialog({ icon: 'history', title: 'Last results', html: '<div class="kv"><span>Nov 9</span><b>3.2× real time</b><span>Nov 2</span><b>3.0× real time</b><span>Oct 21</span><b>2.7× real time</b></div>', actions: [{ label: 'Close', value: true }] }),
      async rmkokoro() { if (await V.confirm('Remove Kokoro voices?', `Frees ${T.kokoroInstalledMb} MB. Generated audio stays; new audio will need the voices again.`, 'Remove')) V.toast('Kokoro voices removed', { action: 'Undo' }); },
      sample() { playing = true; render(); setTimeout(() => { playing = false; render(); }, 4200); },
      stopsample() { playing = false; render(); },
      resetsample() { sample = SAMPLE; render(); },
      discard() { Object.assign(T, JSON.parse(JSON.stringify(saved))); dirty = false; render(); V.toast('Changes discarded'); },
      save() { Object.assign(saved, JSON.parse(JSON.stringify(T))); dirty = false; T.profileStatus = 'Based on “Google”, with changes not saved to it.'; render(); V.toast('TTS settings saved'); if (WNA.player.state().loaded) WNA.player.setRate(Math.round(T.rate * 20) / 20); },
    });
    V.watchScroll(el);
    return { el, title: 'Voice & Speech' };
  };
})();
