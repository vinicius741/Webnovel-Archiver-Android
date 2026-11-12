/* v6 — command palette: fuzzy search over novels, chapters, authors, tags, settings + parsed actions. */
(() => {
  const V6 = window.V6, W = V6.W, esc = V6.esc, ic = V6.ic;
  let el = null, input = null, resEl = null, flat = [], active = 0;

  // ---------- indexes (built lazily) ----------
  let idx = null;
  function buildIndex() {
    const authors = new Map();
    V6.stories().forEach((s) => { if (!s.author) return; const a = authors.get(s.author) || { name: s.author, ids: [] }; a.ids.push(s.id); authors.set(s.author, a); });
    const featured = [];
    Object.entries(W.data.chapters).forEach(([sid, list]) => { if (W.story(sid)) list.forEach((c) => featured.push({ sid, id: c.id, title: c.t })); });
    idx = { authors: [...authors.values()], tags: W.tagCounts(V6.stories()), featured };
  }
  const SETTINGS = [
    ['Voice & Speech', '/settings/tts', 'record_voice_over', 'tts voice rate speed engine kokoro'], ['Speech rate', '/settings/tts?focus=rate', 'speed', 'speed tts'],
    ['Voice · Bella', '/settings/tts?focus=voice', 'record_voice_over', 'voice kokoro bella'], ['Pauses between sentences', '/settings/tts?focus=pauses', 'timer', 'pause comma paragraph scene'],
    ['Kokoro voices', '/settings/tts?focus=kokoro', 'download_for_offline', 'model install'], ['Say chapter titles', '/settings/tts?focus=switches', 'campaign', 'announce'],
    ['Text Cleanup Rules', '/settings/cleanup', 'cleaning_services', 'regex sentences remove'], ['AI Settings', '/settings/ai', 'auto_awesome', 'openrouter key model spend'],
    ['Notifications', '/settings/notifications', 'notifications', 'alerts permission'], ['Manage Tabs', '/settings/tabs', 'tab', 'tabs rename order'],
    ['Data & Backup', '/settings/data', 'backup', 'export import restore storage'], ['Download Settings', '/settings/downloads', 'download', 'parallel delay epub'],
    ['App Updates', '/settings/update', 'system_update', 'version check'], ['Large screen layout', '/settings?focus=layout', 'devices_fold', 'fold inner cover'],
  ];
  const STATIC = () => {
    const d = W.downloads.state(), n = W.narration.state(), p = W.player.state();
    const s = V6.ctxStory();
    const list = [
      { t: 'Open Downloads', sub: `${d.summary.done} of ${d.summary.total} chapters`, icon: 'download', go: '/queue', k: 'queue' },
      { t: 'Open Audio generation', sub: n.current ? `Generating · ${Math.round(n.current.progress * 100)}%` : 'Nothing is generating', icon: 'graphic_eq', go: '/narration', k: 'narration tts generate' },
      { t: 'Open Library grid', sub: 'All novels, filters and sort', icon: 'grid_view', run: () => { V6.state.homeMode = 'grid'; W.go('/library?view=grid'); }, k: 'all browse' },
      { t: 'Open the Deck', sub: 'Home', icon: 'style', go: '/library', k: 'home stage' },
      { t: 'Open Settings', icon: 'settings', go: '/settings', k: 'preferences' },
      { t: 'Voice & Speech', icon: 'record_voice_over', go: '/settings/tts', k: 'tts' },
      { t: 'Add a story', sub: 'Paste a story URL', icon: 'add', go: '/add', k: 'new import url' },
      { t: d.paused ? 'Resume all downloads' : 'Pause all downloads', icon: d.paused ? 'play_arrow' : 'pause', run: () => { d.paused ? W.downloads.resumeAll() : W.downloads.pauseAll(); V6.toast(d.paused ? 'Downloads resumed' : 'Downloads paused', { icon: 'download' }); } },
      { t: 'Retry failed downloads', sub: `${d.failedChapters} failed`, icon: 'refresh', run: () => { W.downloads.retryFailed(); V6.toast('Retrying failed chapters', { icon: 'refresh' }); } },
      { t: n.paused ? 'Resume generating' : 'Pause generating', icon: n.paused ? 'play_arrow' : 'pause', run: () => { W.narration.setPaused(!n.paused); V6.toast(n.paused ? 'Generating resumed' : 'Generating paused', { icon: 'graphic_eq' }); } },
      { t: 'Check for updates', sub: 'Version 1.0.21-native', icon: 'system_update', run: () => V6.toast('You have the latest version', { icon: 'verified' }) },
      { t: 'Export backup', icon: 'backup', run: () => V6.toast('Backup exported · wna-backup.json', { icon: 'backup' }) },
    ];
    if (p.loaded) list.push(
      { t: p.playing ? 'Pause' : 'Resume', sub: p.chapter.title, icon: p.playing ? 'pause' : 'play_arrow', run: () => W.player.toggle() },
      { t: 'Next chapter', icon: 'skip_next', run: () => W.player.nextChapter() }, { t: 'Previous chapter', icon: 'skip_previous', run: () => W.player.prevChapter() },
      { t: 'Back 15 seconds', icon: 'replay', run: () => W.player.skip(-15) }, { t: 'Stop playback', icon: 'stop', run: () => W.player.stop() },
      { t: 'Now Playing', icon: 'headphones', go: '/player', k: 'player' }, { t: 'Sleep timer off', icon: 'bedtime_off', run: () => V6.act.sleep(null) });
    if (s) list.push({ t: `Generate audio for next 5 chapters · ${V6.short(s)}`, icon: 'graphic_eq', run: () => V6.act.generate(s, 5), k: 'narrate tts' });
    return list;
  };

  // per-story actions; used by verbs and as "smart" actions for the top novel hit
  function storyActions(s, verb, n) {
    const sh = V6.short(s), c = V6.resumeChapter(s), rem = V6.remaining(s);
    const all = {
      play: { t: `Play ${sh}`, sub: c ? `Ch ${c.index} · ${c.title}` : '', icon: 'play_arrow', run: () => V6.act.play(s) },
      sync: { t: `Sync ${sh}`, sub: `Last checked ${W.fmt.ago(s.lastSync)}`, icon: 'sync', run: () => V6.act.sync(s) },
      download: rem ? { t: `Download remaining · ${sh}`, sub: `${rem} chapter${rem > 1 ? 's' : ''}`, icon: 'download', run: () => V6.act.download(s) } : null,
      generate: { t: `Generate audio for next ${n || 5} chapters · ${sh}`, sub: 'Voice Bella · Kokoro', icon: 'graphic_eq', run: () => V6.act.generate(s, n || 5) },
      read: c ? { t: `Read ${sh} · Ch ${c.index}`, sub: c.title, icon: 'menu_book', go: `/reader/${s.id}/${c.id}` } : null,
      open: { t: `Open ${sh}`, sub: 'Details', icon: 'open_in_full', go: '/story/' + s.id },
      trends: { t: `Trends · ${sh}`, icon: 'show_chart', go: `/story/${s.id}/trends` },
      ai: { t: `AI cover & description · ${sh}`, icon: 'auto_awesome', go: `/story/${s.id}/ai` },
      epub: { t: `Generate EPUB · ${sh}`, icon: 'book_2', run: () => { V6.state.epub[s.id] = 'ready'; V6.toast(`EPUB ready · ${esc(sh)}`, { icon: 'book_2' }); } },
      audio: { t: `Chapter audio · ${sh}`, icon: 'library_music', go: `/narration/${s.id}` },
      move: { t: `Move ${sh} to tab…`, icon: 'drive_file_move', run: () => V6.moveSheet([s.id]) },
      delete: { t: `Delete ${sh}`, icon: 'delete', run: () => V6.confirmDelete([s.id]), danger: true },
    };
    return verb ? [all[verb]].filter(Boolean) : ['play', 'sync', 'download', 'generate'].map((k) => all[k]).filter(Boolean);
  }
  const VERBS = [
    ['play', ['play', 'listen', 'continue', 'resume']], ['sync', ['sync', 'refresh', 'check']], ['download', ['download', 'dl', 'fetch', 'get']],
    ['generate', ['generate', 'gen', 'narrate', 'audio']], ['read', ['read']], ['open', ['open', 'details', 'show']], ['trends', ['trends', 'stats', 'chart']],
    ['ai', ['ai', 'cover']], ['epub', ['epub']], ['move', ['move']], ['delete', ['delete']],
  ];

  function novelsFor(q, n) {
    const out = [];
    V6.stories().forEach((s) => { const mt = V6.fuzzy(q, s.title); const m = mt || V6.fuzzy(q, s.author || ''); if (m) out.push({ s, m, score: m.score * (mt ? 1 : 0.8) + (s.id === (V6.ctxStory() || {}).id ? 8 : 0) }); });
    out.sort((a, b) => b.score - a.score);
    const top = out.length ? out[0].score : 0;
    return out.filter((x) => x.score >= top * 0.55).slice(0, n);
  }

  // ---------- query -> sections ----------
  function search(raw) {
    const q = raw.trim().replace(/\s+/g, ' ');
    const ql = q.toLowerCase();
    const sections = [];
    const ctx = V6.ctxStory();
    const p = W.player.state();
    if (!q) {
      const sug = [];
      if (p.loaded) {
        sug.push({ t: 'Sleep timer 30 min', sub: p.sleep ? `Now: ${p.sleep === 'chapter' ? 'end of chapter' : p.sleep + ' min'}` : 'Timer is off', icon: 'bedtime', run: () => V6.act.sleep(30) });
        const nx = W.player.SPEEDS[(W.player.SPEEDS.indexOf(p.rate) + 1) % W.player.SPEEDS.length];
        sug.push({ t: `Speed ${nx}×`, sub: `Now ${p.rate}×`, icon: 'speed', run: () => V6.act.speed(nx) });
      } else if (W.session && !V6.state.continueDismissed) {
        const s = W.story(W.session.storyId);
        sug.push({ t: `Continue ${V6.short(s)}`, sub: W.session.chapterTitle, icon: 'play_arrow', run: () => V6.act.play(s) });
      }
      if (ctx) { const busy = p.loaded ? p.story.id === ctx.id : W.session && W.session.storyId === ctx.id; sug.push(...storyActions(ctx).filter((a) => !(busy && /^Play/.test(a.t)))); }
      sug.push({ t: 'Open Downloads', sub: `${W.downloads.state().summary.done} of ${W.downloads.state().summary.total} chapters`, icon: 'download', go: '/queue' });
      sug.push({ t: 'Voice & Speech', sub: 'Rate, voice, pauses', icon: 'record_voice_over', go: '/settings/tts' });
      sections.push({ title: 'Suggested', items: sug.slice(0, 7) });
      if (V6.state.recents.length) sections.push({ title: 'Recent', items: V6.state.recents.slice(0, 4) });
      return sections;
    }
    const acts = [];
    const [t0, ...restA] = ql.split(' ');
    const rest = restA.join(' ');
    // player verbs with numbers
    let m;
    if ((m = ql.match(/^(sp|spe|spee|speed|rate|x)\s*([\d.]+)?\s*[x×]?$/))) {
      const v = m[2] ? Math.max(0.5, Math.min(3, parseFloat(m[2]))) : null;
      (v ? [v] : W.player.SPEEDS).forEach((r) => acts.push({ t: `Speed ${r}×`, sub: p.loaded ? `Now ${p.rate}×` : 'Applies to the next playback', icon: 'speed', run: () => V6.act.speed(r) }));
    }
    if ((m = ql.match(/^(sl|sle|slee|sleep|timer|ti|tim)\s*(\d+)?\s*(m|min|mins|minutes)?$/))) {
      const v = m[2] ? Math.min(240, parseInt(m[2], 10)) : null;
      (v ? [v] : [15, 30, 45, 60]).forEach((x) => acts.push({ t: `Sleep timer ${x} min`, icon: 'bedtime', run: () => V6.act.sleep(x) }));
      if (!v) acts.push({ t: 'Sleep at end of chapter', icon: 'bedtime', run: () => V6.act.sleep('chapter') }, { t: 'Sleep timer off', icon: 'bedtime_off', run: () => V6.act.sleep(null) });
    }
    if ((m = ql.match(/^(ch|chapter|c)\s*(\d+)$/)) && ctx) {
      const c = W.chapters(ctx.id)[parseInt(m[2], 10) - 1];
      if (c) acts.push({ t: `Read chapter ${c.index} · ${V6.short(ctx)}`, sub: c.title, icon: 'menu_book', go: `/reader/${ctx.id}/${c.id}` }, { t: `Play chapter ${c.index} · ${V6.short(ctx)}`, sub: c.title, icon: 'play_arrow', run: () => V6.act.play(ctx, c.id) });
    }
    // story verbs
    if (t0.length >= 2) {
      VERBS.forEach(([verb, kws]) => {
        if (!kws.some((k) => k.startsWith(t0))) return;
        let r = rest, n = null;
        const nm = r.match(/^(?:next\s*)?(\d+)\s*(?:chapters?|ch)?\s*(?:of|for|·)?\s*(.*)$/);
        if (verb === 'generate' && nm) { n = Math.min(50, parseInt(nm[1], 10)); r = nm[2]; }
        r = r.replace(/^(for|of|the|remaining|next)\s+/g, '');
        const targets = r ? novelsFor(r, 3).map((x) => x.s) : [ctx || V6.stories()[0]].filter(Boolean);
        targets.forEach((s) => acts.push(...storyActions(s, verb, n)));
      });
    }
    // static commands
    STATIC().forEach((c) => { const mm = V6.fuzzy(q, c.t) || (c.k && V6.fuzzy(q, c.k) && { score: 20, idx: [] }); if (mm) acts.push({ ...c, _s: mm.score, idx: mm.idx }); });
    // novels
    const nov = novelsFor(q, 6);
    if (nov.length && q.length >= 3 && nov[0].m.score >= 120 && (nov.length === 1 || nov[0].score - nov[1].score > 12) && acts.length < 4) acts.unshift(...storyActions(nov[0].s));
    if (acts.length) sections.push({ title: 'Do', items: dedupe(acts).slice(0, 7) });
    if (nov.length) sections.push({ title: 'Novels', items: nov.map(({ s, m: mt }) => ({ t: s.title, idx: V6.fuzzy(q, s.title) ? mt.idx : [], sub: `${s.author || ''} · ${s.downloaded}/${s.total} ch`, cover: s, go: '/story/' + s.id })) });
    // chapters: context story + featured real titles
    if (q.length >= 2) {
      const ch = [];
      if (ctx) W.chapters(ctx.id).forEach((c) => { const mt = V6.fuzzy(q, c.title); if (mt) ch.push({ s: ctx, c, mt, score: mt.score + 10 }); });
      if (q.length >= 3) idx.featured.forEach((f) => { if (ctx && f.sid === ctx.id) return; const mt = V6.fuzzy(q, f.title); if (mt && mt.score > 60) ch.push({ s: W.story(f.sid), c: { id: f.id, title: f.title, index: W.chapterIndex(f.sid, f.id) + 1 }, mt, score: mt.score }); });
      ch.sort((a, b) => b.score - a.score);
      if (ch.length) { const top = ch[0].score; for (let i = ch.length - 1; i >= 0; i--) if (ch[i].score < top * 0.55) ch.splice(i, 1); }
      if (ch.length) sections.push({ title: 'Chapters', items: ch.slice(0, 5).map(({ s, c, mt }) => ({ t: c.title, idx: mt.idx, sub: `${V6.short(s)} · Ch ${c.index}`, num: c.index, go: `/reader/${s.id}/${c.id}` })) });
    }
    const au = idx.authors.map((a) => ({ a, mt: V6.fuzzy(q, a.name) })).filter((x) => x.mt && x.mt.score > 0).sort((a, b) => b.mt.score - a.mt.score).filter((x, i, arr) => x.mt.score >= arr[0].mt.score * 0.55).slice(0, 3);
    if (au.length) sections.push({ title: 'Authors', items: au.map(({ a, mt }) => ({ t: a.name, idx: mt.idx, sub: `${a.ids.length} novel${a.ids.length > 1 ? 's' : ''} in your library`, icon: 'person', go: a.ids.length > 1 ? '/author/' + a.ids[0] : '/story/' + a.ids[0] })) });
    const tg = idx.tags.map((t) => ({ t, mt: V6.fuzzy(q, t.name) })).filter((x) => x.mt && x.mt.score > 0).sort((a, b) => b.mt.score - a.mt.score).slice(0, 3);
    if (tg.length) sections.push({ title: 'Tags', items: tg.map(({ t, mt }) => ({ t: t.name, idx: mt.idx, sub: `${t.count} novels · filter the grid`, icon: 'sell', run: () => { Object.assign(V6.state.filters, { tags: [t.name], sources: [], query: '' }); V6.state.homeMode = 'grid'; W.go('/library?view=grid'); } })) });
    const st = SETTINGS.map(([t, go, icon, k]) => ({ t, go, icon, mt: V6.fuzzy(q, t) || (V6.fuzzy(q, k) && { score: 10, idx: [] }) })).filter((x) => x.mt).sort((a, b) => b.mt.score - a.mt.score).slice(0, 4);
    if (st.length) sections.push({ title: 'Settings', items: st.map((x) => ({ t: x.t, idx: x.mt.idx, icon: x.icon, go: x.go, sub: 'Settings' })) });
    return sections;
  }
  const dedupe = (arr) => { const seen = new Set(); return arr.filter((a) => (seen.has(a.t) ? false : seen.add(a.t))); };

  // ---------- render ----------
  function render() {
    const sections = search(input.value);
    flat = [];
    if (!sections.length) { resEl.innerHTML = `<div class="pal-none">${ic('search_off')}<b>No matches for “${esc(input.value)}”</b><span>Try a novel, a chapter, “speed 1.5” or “sleep 30”.</span></div>`; return; }
    // Thumb-first: best results sit closest to the input (bottom). Sections render bottom-up.
    const html = sections.map((sec) => {
      const items = sec.items.map((it) => { const i = flat.push(it) - 1; return itemHtml(it, i); });
      return `<div class="pal-sec"><div class="pal-h">${sec.title}<span>${sec.items.length}</span></div>${items.reverse().join('')}</div>`;
    }).reverse().join('');
    resEl.innerHTML = `<div class="pal-in-wrap">${html}</div>`;
    active = 0; mark();
    resEl.scrollTop = resEl.scrollHeight;
  }
  function itemHtml(it, i) {
    const lead = it.cover ? `<span class="pi-cov">${V6.cover(it.cover)}</span>` : it.num != null ? `<span class="pi-num">${it.num}</span>` : `<span class="pi-ic ${it.danger ? 'hot' : ''}">${ic(it.icon || 'bolt')}</span>`;
    return `<button class="pi" data-i="${i}">${lead}<span class="pi-t"><b>${V6.hl(it.t, it.idx)}</b>${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</span><span class="pi-k">${i === 0 ? '↵' : it.go ? ic('arrow_outward') : ''}</span></button>`;
  }
  function mark() { resEl.querySelectorAll('.pi').forEach((b) => b.classList.toggle('act', +b.dataset.i === active)); const a = resEl.querySelector('.pi.act'); if (a) a.scrollIntoView({ block: 'nearest' }); }
  function run(it) {
    if (!it) return;
    V6.state.recents = [{ ...it, idx: [] }, ...V6.state.recents.filter((r) => r.t !== it.t)].slice(0, 6);
    close();
    setTimeout(() => (it.go ? W.go(it.go) : it.run && it.run()), 120);
  }

  function open(prefill = '') {
    if (el) return;
    buildIndex();
    el = W.el(`<div class="pal" role="dialog" aria-label="Command palette">
      <div class="pal-res"></div>
      <div class="pal-foot">
        <div class="chips pal-verbs">${['play', 'sync', 'download', 'generate', 'speed', 'sleep', 'ch', 'open', 'read'].map((v) => `<button class="chip" data-v="${v}">${v}</button>`).join('')}</div>
        <div class="pal-bar"><span class="ms">bolt</span><input type="search" enterkeyhint="go" autocomplete="off" spellcheck="false" placeholder="Search or do anything…"><button class="ib" data-x aria-label="Close">${ic('close')}</button></div>
      </div></div>`);
    document.getElementById('layer').appendChild(el);
    input = el.querySelector('input'); resEl = el.querySelector('.pal-res');
    input.value = prefill;
    input.addEventListener('input', render);
    input.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowUp') { active = Math.min(flat.length - 1, active + 1); mark(); e.preventDefault(); }
      else if (e.key === 'ArrowDown') { active = Math.max(0, active - 1); mark(); e.preventDefault(); }
      else if (e.key === 'Enter') { run(flat[active]); e.preventDefault(); }
      else if (e.key === 'Escape') { close(); e.stopPropagation(); }
    });
    el.addEventListener('click', (e) => {
      const b = e.target.closest('.pi'); if (b) return run(flat[+b.dataset.i]);
      const v = e.target.closest('[data-v]'); if (v) { input.value = v.dataset.v + ' '; render(); input.focus(); return; }
      if (e.target.closest('[data-x]') || e.target === resEl) close();
    });
    render();
    input.focus({ preventScroll: true }); // synchronous, inside the tap, so the soft keyboard opens
    requestAnimationFrame(() => el && el.classList.add('in'));
  }
  function close() { if (!el) return; const e = el; el = null; e.classList.remove('in'); setTimeout(() => e.remove(), 220); }
  V6.palette = { open, close, isOpen: () => !!el, search: (q) => { buildIndex(); return search(q); } };
})();
