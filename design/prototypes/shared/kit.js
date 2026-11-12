/*
 * WNA prototype kit — headless data + simulation layer shared by every prototype version.
 *
 * Load order in each version's index.html:
 *   <script src="../shared/library/data.js"></script>
 *   <script src="../shared/kit.js"></script>
 *   <script src="your-app.js"></script>
 *
 * The kit owns NO visual design (except the injected version-switcher/feedback overlay). Each version renders its
 * own UI and subscribes to the simulations below. Everything is in-memory; a reload resets state.
 *
 * API summary (window.WNA):
 *   Data      stories(), story(id), tabs(), storiesInTab(tabId|'all'|'unassigned'), chapters(storyId),
 *             chapter(storyId, chapterId), chapterIndex(storyId, chapterId), chapterText(storyId, chapterId) -> string[],
 *             sentences(storyId, chapterId) -> [{p, text}], sources, sourceCounts(list), tagCounts(list),
 *             filterStories({tab, query, sources:[], tags:[], sort, asc}), authorNovels(id), stats
 *   Visuals   coverUrl(story) -> url|null, coverFallback(story) -> CSS background string (gradient), hue(story)
 *   Derived   forecast(story) -> {state:'due'|'expected'|'late'|'unknown', text, short}, scoreTrend(id) -> 'up'|'down'|null,
 *             patreonLine(story), trendSeries(id) -> [{key,label,kind,points:[{t,v}]}], coverage(story) -> segments
 *   Format    fmt.num(1234)->"1.2K", fmt.int, fmt.money, fmt.date(ts), fmt.dateTime, fmt.ago(ts), fmt.dur(sec), fmt.clock(sec)
 *   Router    router(onRoute) ; go(path) ; back() ; route() -> {path, name, params, query}
 *   Sims      player, downloads, narration, ai, settings (see each section). All expose on(fn) -> unsubscribe.
 *   Util      el(html) -> Element, esc(str), on(root, event, selector, fn) delegated events, debounce
 */
(function () {
  const D = window.WNA_DATA;
  if (!D) {
    document.body.innerHTML = '<p style="font:16px sans-serif;color:#fff;background:#000;padding:24px">Missing shared/library/data.js — run tools/build_data.py</p>';
    return;
  }
  const scriptSrc = document.currentScript ? document.currentScript.src : location.href;
  const BASE = scriptSrc.replace(/kit\.js(\?.*)?$/, '');
  const NOW = Date.now();

  // ---------- tiny utils ----------
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  const on = (root, ev, sel, fn) => root.addEventListener(ev, (e) => { const t = e.target.closest(sel); if (t && root.contains(t)) fn(e, t); });
  const debounce = (fn, ms) => { let h; return (...a) => { clearTimeout(h); h = setTimeout(() => fn(...a), ms); }; };
  const hash = (s) => { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; };
  const rng = (seed) => () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  function emitter() {
    const fns = new Set();
    return { on(fn) { fns.add(fn); return () => fns.delete(fn); }, emit(...a) { fns.forEach((f) => { try { f(...a); } catch (e) { console.error(e); } }); } };
  }

  // ---------- format ----------
  const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const fmt = {
    num(n) { if (n == null) return '—'; const a = Math.abs(n); if (a >= 1e6) return (n / 1e6).toFixed(a >= 1e7 ? 0 : 1).replace(/\.0$/, '') + 'M'; if (a >= 1e3) return (n / 1e3).toFixed(a >= 1e4 ? 0 : 1).replace(/\.0$/, '') + 'K'; return String(Math.round(n * 100) / 100); },
    int(n) { return n == null ? '—' : Math.round(n).toLocaleString('en-US'); },
    money(n) { return n == null ? '—' : '$' + Math.round(n).toLocaleString('en-US'); },
    date(ts) { if (!ts) return '—'; const d = new Date(ts); return `${MON[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()}`; },
    dateShort(ts) { const d = new Date(ts); return `${MON[d.getMonth()]} ${d.getDate()}`; },
    dateTime(ts) { const d = new Date(ts); return `${fmt.date(ts)} · ${d.getHours() % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${d.getHours() < 12 ? 'AM' : 'PM'}`; },
    ago(ts) { if (!ts) return 'never'; const s = Math.max(0, (Date.now() - ts) / 1000); if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return Math.floor(s / 3600) + ' h ago'; const d = Math.floor(s / 86400); if (d < 30) return d + (d === 1 ? ' day ago' : ' days ago'); if (d < 365) return Math.floor(d / 30) + ' mo ago'; return Math.floor(d / 365) + ' y ago'; },
    dur(sec) { sec = Math.max(0, Math.round(sec)); const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60); if (h && m) return `${h} h ${m} min`; if (h) return `${h} h`; if (m) return `${m} min`; return `${sec} s`; },
    clock(sec) { sec = Math.max(0, Math.round(sec)); const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0'); },
    days(ms) { const d = ms / 86400000; if (d < 1) return Math.max(1, Math.round(d * 24)) + ' h'; return Math.round(d) + (Math.round(d) === 1 ? ' day' : ' days'); },
  };

  // ---------- data ----------
  const stories = D.stories;
  const byId = Object.fromEntries(stories.map((s) => [s.id, s]));
  const tabs = D.tabs.map((t) => ({ id: t.id, name: t.name }));
  const SOURCES = [
    { name: 'RoyalRoad', host: 'royalroad.com' },
    { name: 'Scribble Hub', host: 'scribblehub.com' },
    { name: 'SpaceBattles', host: 'forums.spacebattles.com' },
    { name: 'Sufficient Velocity', host: 'forums.sufficientvelocity.com' },
    { name: 'FanFiction.net', host: 'fanfiction.net' },
    { name: 'Archive of Our Own', host: 'archiveofourown.org' },
  ];

  function storiesInTab(tabId) {
    if (!tabId || tabId === 'all') return stories.slice();
    if (tabId === 'unassigned') return stories.filter((s) => !s.tab || !tabs.some((t) => t.id === s.tab));
    return stories.filter((s) => s.tab === tabId);
  }

  const chapterCache = {};
  function chapters(id) {
    if (chapterCache[id]) return chapterCache[id];
    const s = byId[id];
    if (!s) return [];
    let list = D.chapters[id];
    if (list) list = list.map((c, i) => ({ id: c.id, title: c.t, downloaded: c.d, publishedAt: c.at, index: i + 1 }));
    else {
      const n = Math.min(s.total || 0, 1500);
      const step = 2.5 * 86400000;
      list = Array.from({ length: n }, (_, i) => ({ id: `${id}-c${i + 1}`, title: `Chapter ${i + 1}`, downloaded: i < s.downloaded, publishedAt: (s.updated || NOW) - (n - i - 1) * step, index: i + 1 }));
    }
    // Mock audio availability: the 6 chapters around the bookmark (or the first 6) have generated audio.
    const b = s.bookmark != null ? s.bookmark : 0;
    list.forEach((c, i) => { c.audio = i >= Math.max(0, b - 2) && i < b + 4 && c.downloaded; });
    chapterCache[id] = list;
    return list;
  }
  const chapter = (sid, cid) => chapters(sid).find((c) => c.id === cid) || chapters(sid)[0];
  const chapterIndex = (sid, cid) => Math.max(0, chapters(sid).findIndex((c) => c.id === cid));

  const textPool = Object.entries(D.text).flatMap(([sid, m]) => Object.values(m));
  function chapterText(sid, cid) {
    const real = D.text[sid] && D.text[sid][cid];
    if (real) return real;
    const pool = textPool[hash(sid + cid) % textPool.length] || ['(No text)'];
    return pool;
  }
  function sentences(sid, cid) {
    const out = [];
    chapterText(sid, cid).forEach((para, p) => {
      const parts = para.match(/[^.!?…]+[.!?…]+["”’)\]]*\s*|[^.!?…]+$/g) || [para];
      parts.forEach((t) => { if (t.trim()) out.push({ p, text: t.trim() }); });
    });
    return out;
  }

  const countBy = (list, keyFn) => {
    const m = new Map();
    list.forEach((s) => [].concat(keyFn(s) || []).forEach((k) => m.set(k, (m.get(k) || 0) + 1)));
    return [...m.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
  };
  const sourceCounts = (list) => countBy(list, (s) => s.source);
  const tagCounts = (list) => countBy(list, (s) => s.tags);

  const SORTS = {
    default: { label: 'Default (Smart)', short: 'Default', asc: false, key: (s) => s.updated || 0 },
    title: { label: 'Title', short: 'Title', asc: true, key: (s) => s.title.toLowerCase() },
    updated: { label: 'Last Updated', short: 'Updated', asc: false, key: (s) => s.updated || 0 },
    added: { label: 'Date Added', short: 'Added', asc: false, key: (s) => s.added || 0 },
    chapters: { label: 'Chapter Count', short: 'Chapters', asc: false, key: (s) => s.total || 0 },
    score: { label: 'Score', short: 'Score', asc: false, key: (s) => parseFloat(s.score) || 0 },
    patreonUsd: { label: 'Patreon Earnings', short: 'Patreon $', asc: false, key: (s) => (s.patreon && s.patreon.usd) || 0 },
    patreonMembers: { label: 'Patreon Paid Members', short: 'Patrons', asc: false, key: (s) => (s.patreon && s.patreon.members) || 0 },
  };
  function filterStories({ tab = 'all', query = '', sources = [], tags = [], sort = 'default', asc } = {}) {
    let list = storiesInTab(tab);
    const q = query.trim().toLowerCase();
    if (q) list = list.filter((s) => s.title.toLowerCase().includes(q) || (s.author || '').toLowerCase().includes(q));
    if (sources.length) list = list.filter((s) => sources.includes(s.source));
    if (tags.length) list = list.filter((s) => tags.every((t) => s.tags.includes(t)));
    const so = SORTS[sort] || SORTS.default;
    const dir = (asc == null ? so.asc : asc) ? 1 : -1;
    return list.sort((a, b) => { const x = so.key(a), y = so.key(b); return x < y ? -dir : x > y ? dir : 0; });
  }
  const authorNovels = (id) => { const s = byId[id]; return s ? stories.filter((o) => o.author === s.author && o.id !== id) : []; };

  // ---------- visuals ----------
  const coverUrl = (s) => (s && s.cover ? `${BASE}library/covers/${s.id}.jpg` : null);
  const hue = (s) => hash((s && s.title) || '') % 360;
  function coverFallback(s) {
    const h = hue(s);
    return `linear-gradient(160deg, hsl(${h} 45% 32%), hsl(${(h + 40) % 360} 40% 14%))`;
  }

  // ---------- derived ----------
  function forecast(s) {
    const pubs = (s.recentPubs || []).filter(Boolean);
    if (s.pub === 'completed') return { state: 'unknown', text: 'Completed', short: 'Completed' };
    if (pubs.length < 3) return { state: 'unknown', text: 'Not enough releases to estimate', short: '' };
    const gaps = pubs.slice(1).map((t, i) => t - pubs[i]).sort((a, b) => a - b);
    const cadence = gaps[Math.floor(gaps.length / 2)];
    // Pretend "now" is a little after the last sync so the library has a mix of states.
    const now = (s.lastSync || NOW) + (hash(s.id) % 5) * 86400000 * 0.6;
    const next = pubs[pubs.length - 1] + cadence;
    const usually = `usually every ${fmt.days(cadence)}`;
    if (now > next + cadence * 2) return { state: 'late', text: `Later than usual · no chapter in ${fmt.days(now - pubs[pubs.length - 1])} · ${usually}`, short: 'Late', cadence };
    if (now >= next) return { state: 'due', text: 'Next chapter may be out · sync to check', short: 'May be out', cadence };
    return { state: 'expected', text: `Next chapter expected in ${fmt.days(next - now)} · ${usually}`, short: `In ${fmt.days(next - now)}`, cadence };
  }
  function scoreTrend(id) {
    const m = (D.metrics[id] || []).map((x) => parseFloat(x.score)).filter((v) => !isNaN(v));
    if (m.length < 2) return null;
    return m[m.length - 1] > m[0] ? 'up' : m[m.length - 1] < m[0] ? 'down' : null;
  }
  function patreonLine(s) {
    if (!s.patreon || !s.patreon.members) return '';
    return s.patreon.usd ? `Patreon ~$${s.patreon.usd.toLocaleString('en-US')}/mo · ${s.patreon.members.toLocaleString('en-US')} paid` : `Patreon · ${s.patreon.members} paid`;
  }
  function coverage(s) {
    const list = chapters(s.id);
    const segs = [];
    let start = null;
    list.forEach((c, i) => { if (c.downloaded && start == null) start = i; if ((!c.downloaded || i === list.length - 1) && start != null) { segs.push([start / list.length, (c.downloaded ? i + 1 : i) / list.length]); start = null; } });
    return { segments: segs, bookmark: s.bookmark != null && list.length ? (s.bookmark + 0.5) / list.length : null };
  }
  function trendSeries(id) {
    const s = byId[id];
    const snaps = D.metrics[id] || [];
    const series = [];
    const score = snaps.map((x) => ({ t: x.at, v: parseFloat(x.score) })).filter((p) => !isNaN(p.v));
    if (score.length) series.push({ key: 'score', label: 'Score', kind: 'score', points: score });
    const kinds = {};
    snaps.forEach((x) => Object.entries(x.m || {}).forEach(([k, v]) => { if (v != null) (kinds[k] = kinds[k] || []).push({ t: x.at, v }); }));
    Object.entries(kinds).forEach(([k, pts]) => series.push({ key: k.toLowerCase(), label: k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, ' '), kind: 'count', points: pts }));
    // Synthesize plausible engagement + Patreon history so every version can demo charts.
    const r = rng(hash(id));
    const t0 = (snaps[0] && snaps[0].at) || (NOW - 120 * 86400000);
    const walk = (end, n, vol) => { const pts = []; let v = end * (0.7 + r() * 0.2); for (let i = 0; i < n; i++) { v += (end - v) / (n - i) + (r() - 0.45) * end * vol; pts.push({ t: t0 + ((NOW - t0) * i) / (n - 1), v: Math.max(0, v) }); } pts[n - 1].v = end; return pts; };
    if (!kinds.FOLLOWERS) series.push({ key: 'followers', label: 'Followers', kind: 'count', points: walk(800 + (hash(id) % 12000), 24, 0.02) });
    if (s && s.patreon && s.patreon.members) {
      series.push({ key: 'patreon_members', label: 'Patreon members', kind: 'members', points: walk(s.patreon.members, 18, 0.03) });
      if (s.patreon.usd) series.push({ key: 'patreon_usd', label: 'Patreon monthly earnings', kind: 'usd', points: walk(s.patreon.usd, 18, 0.03) });
    }
    return series;
  }

  // ---------- router ----------
  const ROUTES = [
    ['library', /^\/?(library)?$/],
    ['add', /^\/add$/],
    ['select', /^\/story\/([^/]+)\/select$/, ['id']],
    ['trends', /^\/story\/([^/]+)\/trends$/, ['id']],
    ['ai', /^\/story\/([^/]+)\/ai$/, ['id']],
    ['story', /^\/story\/([^/]+)$/, ['id']],
    ['author', /^\/author\/([^/]+)$/, ['id']],
    ['reader', /^\/reader\/([^/]+)\/([^/]+)$/, ['id', 'chapterId']],
    ['player', /^\/player$/],
    ['narrationStory', /^\/narration\/([^/]+)$/, ['id']],
    ['narration', /^\/narration$/],
    ['queue', /^\/queue$/],
    ['settingsPage', /^\/settings\/([^/]+)$/, ['page']],
    ['settings', /^\/settings$/],
  ];
  function route() {
    const raw = decodeURIComponent(location.hash.replace(/^#/, '')) || '/library';
    const [path, qs] = raw.split('?');
    const query = Object.fromEntries(new URLSearchParams(qs || ''));
    for (const [name, re, keys] of ROUTES) {
      const m = path.match(re);
      if (m) { const params = {}; (keys || []).forEach((k, i) => (params[k] = m[i + 1])); return { path, name, params, query }; }
    }
    return { path, name: 'unknown', params: {}, query };
  }
  let depth = 0;
  function go(path, { replace = false } = {}) {
    const h = '#' + (path.startsWith('/') ? path : '/' + path);
    if (replace) { history.replaceState(null, '', h); window.dispatchEvent(new HashChangeEvent('hashchange')); } else { depth++; location.hash = h; }
  }
  function back(fallback = '/library') { if (depth > 0) { depth--; history.back(); } else go(fallback, { replace: true }); }
  function router(onRoute) {
    const run = () => onRoute(route());
    window.addEventListener('hashchange', run);
    run();
  }

  // ---------- simulations ----------
  const rate0 = (D.tts && D.tts.rate) || 1.8;

  // Player: sentence-level TTS simulation.
  const player = (() => {
    const ev = emitter();
    const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 1.8, 2.0];
    const st = { storyId: null, chapterId: null, sents: [], idx: 0, playing: false, buffering: false, rate: rate0, sleep: null, sleepEnd: null, history: [], voice: 'Bella · Kokoro' };
    let timer = null;
    const wordsOf = (t) => t.split(/\s+/).length;
    const sentSec = (i) => (st.sents[i] ? wordsOf(st.sents[i].text) * 0.36 + 0.3 : 0); // at 1x
    const totalSec = () => st.sents.reduce((a, _, i) => a + sentSec(i), 0);
    const elapsedSec = () => st.sents.slice(0, st.idx).reduce((a, _, i) => a + sentSec(i), 0);
    function schedule() {
      clearTimeout(timer);
      if (!st.playing) return;
      timer = setTimeout(() => {
        if (st.sleep && st.sleepEnd && Date.now() > st.sleepEnd) { api.pause(); st.sleep = null; ev.emit(api.state()); return; }
        if (st.idx < st.sents.length - 1) { st.idx++; ev.emit(api.state()); schedule(); }
        else if (st.sleep === 'chapter') { api.pause(); st.sleep = null; ev.emit(api.state()); }
        else api.nextChapter(true);
      }, Math.max(900, (sentSec(st.idx) * 1000) / st.rate));
    }
    const api = {
      SPEEDS,
      on: ev.on,
      state() {
        const s = byId[st.storyId];
        const list = s ? chapters(s.id) : [];
        const ci = s ? chapterIndex(s.id, st.chapterId) : 0;
        return {
          loaded: !!s, story: s, chapter: s ? chapter(s.id, st.chapterId) : null, chapterIndex: ci, chapterCount: list.length,
          sentences: st.sents, index: st.idx, playing: st.playing, buffering: st.buffering, rate: st.rate, sleep: st.sleep,
          sleepLeftMin: st.sleepEnd ? Math.max(0, Math.ceil((st.sleepEnd - Date.now()) / 60000)) : null,
          progress: st.sents.length ? st.idx / Math.max(1, st.sents.length - 1) : 0,
          elapsedSec: elapsedSec(), totalSec: totalSec(), remainingSec: (totalSec() - elapsedSec()) / st.rate,
          voice: st.voice, history: st.history.slice(),
        };
      },
      load(storyId, chapterId, { index = 0, play = true } = {}) {
        if (st.storyId && st.chapterId && (st.storyId !== storyId || st.chapterId !== chapterId)) {
          st.history.unshift({ storyId: st.storyId, chapterId: st.chapterId, index: st.idx, at: Date.now(), excerpt: (st.sents[st.idx] || {}).text || '' });
          st.history = st.history.slice(0, 8);
        }
        st.storyId = storyId; st.chapterId = chapterId || chapters(storyId)[0].id; st.sents = sentences(storyId, st.chapterId);
        st.idx = Math.min(index, st.sents.length - 1); st.playing = false; ev.emit(api.state());
        if (play) { st.buffering = true; ev.emit(api.state()); setTimeout(() => { st.buffering = false; api.play(); }, 450); }
      },
      play() { if (!st.storyId) return; st.playing = true; ev.emit(api.state()); schedule(); },
      pause() { st.playing = false; clearTimeout(timer); ev.emit(api.state()); },
      toggle() { st.playing ? api.pause() : api.play(); },
      stop() { clearTimeout(timer); st.storyId = null; st.chapterId = null; st.sents = []; st.idx = 0; st.playing = false; st.sleep = null; st.sleepEnd = null; ev.emit(api.state()); },
      seekSentence(i) { st.idx = Math.max(0, Math.min(st.sents.length - 1, i)); ev.emit(api.state()); schedule(); },
      seekFraction(f) { api.seekSentence(Math.round(f * (st.sents.length - 1))); },
      skip(sec) { let i = st.idx, acc = 0; const dir = Math.sign(sec); while (acc < Math.abs(sec) && i + dir >= 0 && i + dir < st.sents.length) { i += dir; acc += sentSec(i); } api.seekSentence(i); },
      nextChapter(auto) {
        const list = chapters(st.storyId); const i = chapterIndex(st.storyId, st.chapterId);
        const nxt = list.slice(i + 1).find((c) => c.downloaded);
        if (nxt) api.load(st.storyId, nxt.id, { play: auto ? true : st.playing }); else api.pause();
      },
      prevChapter() {
        const list = chapters(st.storyId); const i = chapterIndex(st.storyId, st.chapterId);
        const prv = list.slice(0, i).reverse().find((c) => c.downloaded);
        if (prv) api.load(st.storyId, prv.id, { play: st.playing });
      },
      setRate(r) { st.rate = r; ev.emit(api.state()); schedule(); },
      cycleRate() { const i = SPEEDS.indexOf(st.rate); api.setRate(SPEEDS[(i + 1) % SPEEDS.length]); },
      setSleep(v) { st.sleep = v; st.sleepEnd = typeof v === 'number' ? Date.now() + v * 60000 : null; ev.emit(api.state()); },
      restore(h) { api.load(h.storyId, h.chapterId, { index: h.index, play: true }); },
    };
    return api;
  })();

  // Saved session ("Continue listening")
  const session = (() => {
    const s = D.session || {};
    return s.storyId && byId[s.storyId] ? { storyId: s.storyId, chapterId: s.chapterId, chapterTitle: s.chapterTitle, index: s.currentChunkIndex || 0, storyTitle: byId[s.storyId].title } : null;
  })();

  // Player continuity: the real app shows the saved session (paused) at startup, and the prototype keeps the
  // player's place, speed and play state across page loads in this browser tab so switching versions keeps it.
  (() => {
    let saved = null;
    try { saved = JSON.parse(sessionStorage.getItem('wnaPlayer') || 'null'); } catch (e) { /* storage blocked */ }
    if (saved && saved.stopped) { /* user closed the player: start empty */ } else {
      const src = saved && byId[saved.storyId] ? saved : session && { storyId: session.storyId, chapterId: session.chapterId, index: session.index };
      if (src) {
        if (saved && saved.rate) player.setRate(saved.rate);
        player.load(src.storyId, src.chapterId, { index: src.index || 0, play: !!(saved && saved.playing) });
      }
    }
    player.on((s) => {
      try {
        sessionStorage.setItem('wnaPlayer', JSON.stringify(s.loaded ? { storyId: s.story.id, chapterId: s.chapter.id, index: s.index, rate: s.rate, playing: s.playing || s.buffering } : { stopped: true }));
      } catch (e) { /* storage blocked */ }
    });
  })();

  // Downloads queue simulation.
  const downloads = (() => {
    const ev = emitter();
    const r = rng(7);
    const pick = (pred) => stories.filter(pred);
    const mk = (s, n, opts = {}) => {
      const list = chapters(s.id);
      const pending = list.filter((c) => !c.downloaded);
      const chosen = (pending.length >= n ? pending.slice(0, n) : list.slice(-n)).map((c) => ({ id: c.id, index: c.index, title: c.title, status: 'queued' }));
      return { storyId: s.id, title: s.title, source: s.source, chapters: chosen, status: opts.status || 'queued', expanded: false, note: opts.note || null };
    };
    const candidates = pick((s) => s.cover && s.pub === 'ongoing');
    const items = [];
    if (candidates.length > 4) {
      const a = mk(candidates[0], 14, { status: 'downloading' }); a.chapters.slice(0, 5).forEach((c) => (c.status = 'done')); a.chapters[5].status = 'downloading';
      const b = mk(candidates[1], 6, { status: 'downloading', note: 'pacing' }); b.chapters.slice(0, 2).forEach((c) => (c.status = 'done'));
      const c = mk(candidates[2], 22, { status: 'queued' });
      const d = mk(candidates[3], 9, { status: 'paused' }); d.chapters.slice(0, 3).forEach((x) => (x.status = 'done')); d.chapters.slice(3).forEach((x) => (x.status = 'paused'));
      const e = mk(candidates[4], 5, { status: 'finished' }); e.chapters.forEach((x, i) => (x.status = i === 2 ? 'failed' : 'done')); e.chapters[2].error = 'network problem';
      items.push(a, b, c, d, e);
    }
    let blocked = { site: 'SpaceBattles', state: 'needs', novels: 1, chapters: 4 };
    let paused = false;
    let timer = null;
    function tick() {
      if (!paused) {
        items.filter((it) => it.status === 'downloading').forEach((it) => {
          const cur = it.chapters.find((c) => c.status === 'downloading');
          if (cur) { cur.status = r() < 0.06 ? 'failed' : 'done'; if (cur.status === 'failed') cur.error = 'the site is limiting requests'; }
          const nxt = it.chapters.find((c) => c.status === 'queued');
          if (nxt) nxt.status = 'downloading';
          else if (!it.chapters.some((c) => c.status === 'downloading')) {
            it.status = 'finished';
            const q = items.find((x) => x.status === 'queued'); if (q) { q.status = 'downloading'; }
          }
          // reflect into story counts
          const s = byId[it.storyId]; if (cur && cur.status === 'done' && s) { const ch = chapters(s.id).find((c) => c.id === cur.id); if (ch && !ch.downloaded) { ch.downloaded = true; s.downloaded++; } }
        });
        ev.emit(api.state());
      }
      timer = setTimeout(tick, 1600 + r() * 1400);
    }
    const counts = (it) => ({ done: it.chapters.filter((c) => c.status === 'done').length, failed: it.chapters.filter((c) => c.status === 'failed').length, total: it.chapters.length });
    const api = {
      on: ev.on,
      state() {
        const all = items.map((it) => ({ ...it, ...counts(it) }));
        return {
          paused, blocked, items: all,
          downloading: all.filter((i) => i.status === 'downloading'), upNext: all.filter((i) => i.status === 'queued'),
          pausedItems: all.filter((i) => i.status === 'paused'), finished: all.filter((i) => i.status === 'finished'),
          failedChapters: all.reduce((a, i) => a + i.failed, 0),
          summary: { novels: all.length, done: all.reduce((a, i) => a + i.done, 0), total: all.reduce((a, i) => a + i.total, 0) },
        };
      },
      forStory(id) { const it = items.find((i) => i.storyId === id && i.status !== 'finished'); return it ? { ...it, ...counts(it) } : null; },
      statusLine(it) {
        if (it.status === 'paused') return 'Paused';
        if (it.status === 'finished') return it.failed ? `${it.failed} failed` : 'Done';
        if (it.status === 'queued') { const first = items.find((x) => x.status === 'downloading'); return first ? `After ${first.title.split(/[[(:]/)[0].trim()}` : 'Starting soon'; }
        if (it.note === 'pacing') return 'Next chapter in 12 seconds';
        const cur = it.chapters.find((c) => c.status === 'downloading'); return cur ? `Downloading chapter ${cur.index}` : 'Downloading';
      },
      enqueue(storyId, chapterIds) {
        const s = byId[storyId]; if (!s) return;
        const list = chapters(storyId).filter((c) => (chapterIds ? chapterIds.includes(c.id) : !c.downloaded));
        if (!list.length) return;
        let it = items.find((i) => i.storyId === storyId && i.status !== 'finished');
        if (!it) { it = { storyId, title: s.title, source: s.source, chapters: [], status: 'downloading', expanded: false }; items.unshift(it); }
        list.forEach((c) => { if (!it.chapters.some((x) => x.id === c.id)) it.chapters.push({ id: c.id, index: c.index, title: c.title, status: 'queued' }); });
        if (it.status !== 'downloading') it.status = 'downloading';
        ev.emit(api.state());
      },
      pauseAll() { paused = true; items.forEach((i) => { if (i.status === 'downloading' || i.status === 'queued') i.status = 'paused'; }); ev.emit(api.state()); },
      resumeAll() { paused = false; items.forEach((i) => { if (i.status === 'paused') i.status = 'downloading'; }); ev.emit(api.state()); },
      pause(id) { const it = items.find((i) => i.storyId === id); if (it) it.status = 'paused'; ev.emit(api.state()); },
      resume(id) { const it = items.find((i) => i.storyId === id); if (it) it.status = 'downloading'; paused = false; ev.emit(api.state()); },
      retryFailed(id) { items.filter((i) => !id || i.storyId === id).forEach((i) => { i.chapters.forEach((c) => { if (c.status === 'failed') { c.status = 'queued'; delete c.error; } }); if (i.chapters.some((c) => c.status === 'queued')) i.status = 'downloading'; }); ev.emit(api.state()); },
      remove(id) { const i = items.findIndex((x) => x.storyId === id); if (i >= 0) items.splice(i, 1); ev.emit(api.state()); },
      clearFinished() { for (let i = items.length - 1; i >= 0; i--) if (items[i].status === 'finished') items.splice(i, 1); ev.emit(api.state()); },
      cancelAll() { items.length = 0; ev.emit(api.state()); },
      moveFirst(id) { const i = items.findIndex((x) => x.storyId === id); if (i > 0) { const [it] = items.splice(i, 1); const at = items.findIndex((x) => x.status === 'queued'); items.splice(at < 0 ? items.length : at, 0, it); } ev.emit(api.state()); },
      toggleExpanded(id) { const it = items.find((i) => i.storyId === id); if (it) it.expanded = !it.expanded; ev.emit(api.state()); },
      verify() { blocked = { ...blocked, state: 'verified' }; ev.emit(api.state()); setTimeout(() => { blocked = null; ev.emit(api.state()); }, 3000); },
    };
    timer = setTimeout(tick, 1200);
    return api;
  })();

  // Narration (audio generation) simulation.
  const narration = (() => {
    const ev = emitter();
    const mainId = (session && session.storyId) || stories[0].id;
    const others = stories.filter((s) => s.cover && s.downloaded > 10 && s.id !== mainId).slice(0, 3);
    const mkJobs = (sid, from, n) => chapters(sid).slice(from, from + n).map((c) => ({ storyId: sid, chapterId: c.id, title: c.title, status: 'queued', progress: 0, voice: 'Bella' }));
    const mb = chapters(mainId); const startAt = Math.max(0, (byId[mainId].bookmark || 0) + 4);
    const queue = [...mkJobs(mainId, startAt, 5), ...(others[0] ? mkJobs(others[0].id, 0, 3) : [])];
    if (others[1]) { const f = mkJobs(others[1].id, 2, 1)[0]; f.status = 'failed'; f.error = 'The voice model ran out of memory'; queue.push(f); }
    if (queue[0]) { queue[0].status = 'generating'; queue[0].progress = 0.4; }
    const ready = {}; // storyId -> {chapterId: {dur, voice}}
    chapters(mainId).forEach((c) => { if (c.audio) (ready[mainId] = ready[mainId] || {})[c.id] = { dur: 420 + (hash(c.id) % 300), voice: 'Bella' }; });
    others.forEach((o, k) => chapters(o.id).slice(0, 2 + k * 3).forEach((c) => ((ready[o.id] = ready[o.id] || {})[c.id] = { dur: 500 + (hash(c.id) % 400), voice: k === 2 ? 'Google · Sfg' : 'Bella' })));
    void mb;
    const state = {
      paused: false, keepAhead: 5, target: 'phone',
      computers: [{ id: 'pc1', name: 'Gaming PC', address: '192.168.1.20:8790' }, { id: 'pc2', name: 'MacBook', address: '100.101.102.103:8790' }],
      batteryRestricted: true, storage: { used: '125 MB', free: '2.2 GB' },
    };
    let timer;
    function tick() {
      if (!state.paused) {
        const cur = queue.find((j) => j.status === 'generating');
        if (cur) {
          cur.progress = Math.min(1, cur.progress + 0.04 + Math.random() * 0.04);
          if (cur.progress >= 1) {
            cur.status = 'done'; (ready[cur.storyId] = ready[cur.storyId] || {})[cur.chapterId] = { dur: 450 + (hash(cur.chapterId) % 300), voice: cur.voice };
            const c = chapters(cur.storyId).find((x) => x.id === cur.chapterId); if (c) c.audio = true;
            queue.splice(queue.indexOf(cur), 1);
            const nxt = queue.find((j) => j.status === 'queued'); if (nxt) { nxt.status = 'generating'; nxt.progress = 0; }
          }
        }
        ev.emit(api.state());
      }
      timer = setTimeout(tick, 1000);
    }
    const api = {
      on: ev.on,
      state() {
        const cur = queue.find((j) => j.status === 'generating');
        const byStory = [];
        queue.forEach((j) => { let g = byStory.find((x) => x.storyId === j.storyId); if (!g) byStory.push((g = { storyId: j.storyId, story: byId[j.storyId], jobs: [] })); g.jobs.push(j); });
        const generated = Object.entries(ready).map(([sid, m]) => { const v = Object.values(m); const sec = v.reduce((a, x) => a + x.dur, 0); return { storyId: sid, story: byId[sid], chapters: v.length, durationSec: sec, sizeMb: Math.round(sec * 0.045), voices: [...new Set(v.map((x) => x.voice))].join(', ') }; });
        const target = state.target === 'phone' ? null : state.computers.find((c) => c.id === state.target);
        return {
          ...state, queue: queue.slice(), byStory, generated, current: cur ? { ...cur, story: byId[cur.storyId], sentence: Math.round(cur.progress * 80), sentences: 80, speed: target ? '9.6× real time' : '3.2× real time', etaMin: Math.max(1, Math.round((1 - cur.progress) * 7)) } : null,
          queuedCount: queue.filter((j) => j.status !== 'failed').length, failedCount: queue.filter((j) => j.status === 'failed').length, targetComputer: target,
        };
      },
      chapterStatus(storyId, chapterId) {
        const j = queue.find((x) => x.storyId === storyId && x.chapterId === chapterId);
        if (j) { if (j.status === 'generating') return { kind: 'generating', progress: j.progress, text: `Generating · ${Math.round(j.progress * 100)}%` }; if (j.status === 'failed') return { kind: 'failed', text: `Failed · ${j.error}` }; return { kind: 'queued', text: `Queued · #${queue.filter((x) => x.status !== 'failed').indexOf(j) + 1}` }; }
        const r = ready[storyId] && ready[storyId][chapterId];
        if (r) return hash(chapterId) % 23 === 0 ? { kind: 'stale', text: 'Text changed · regenerate to play' } : { kind: 'ready', durationSec: r.dur, text: `Ready · ${fmt.clock(r.dur)} · ${r.voice}` };
        return { kind: 'none', text: 'Not generated' };
      },
      enqueue(storyId, chapterIds, { first = false } = {}) {
        const jobs = chapterIds.filter((id) => !queue.some((j) => j.storyId === storyId && j.chapterId === id)).map((id) => ({ storyId, chapterId: id, title: chapter(storyId, id).title, status: 'queued', progress: 0, voice: 'Bella' }));
        if (first) { const at = queue.findIndex((j) => j.status === 'queued'); queue.splice(at < 0 ? queue.length : at, 0, ...jobs); } else queue.push(...jobs);
        if (!queue.some((j) => j.status === 'generating')) { const n = queue.find((j) => j.status === 'queued'); if (n) n.status = 'generating'; }
        ev.emit(api.state()); return jobs.length;
      },
      storyFirst(storyId) { const mine = queue.filter((j) => j.storyId === storyId && j.status === 'queued'); mine.forEach((j) => queue.splice(queue.indexOf(j), 1)); const at = queue.findIndex((j) => j.status === 'queued'); queue.splice(at < 0 ? queue.length : at, 0, ...mine); ev.emit(api.state()); },
      remove(storyId, chapterId) { for (let i = queue.length - 1; i >= 0; i--) if (queue[i].storyId === storyId && (!chapterId || queue[i].chapterId === chapterId)) queue.splice(i, 1); if (!queue.some((j) => j.status === 'generating')) { const n = queue.find((j) => j.status === 'queued'); if (n) n.status = 'generating'; } ev.emit(api.state()); },
      deleteAudio(storyId, chapterIds) { if (ready[storyId]) (chapterIds || Object.keys(ready[storyId])).forEach((id) => { delete ready[storyId][id]; const c = chapters(storyId).find((x) => x.id === id); if (c) c.audio = false; }); ev.emit(api.state()); },
      retryFailed() { queue.forEach((j) => { if (j.status === 'failed') { j.status = 'queued'; delete j.error; } }); ev.emit(api.state()); },
      clearQueue() { queue.length = 0; ev.emit(api.state()); },
      setPaused(p) { state.paused = p; ev.emit(api.state()); },
      setKeepAhead(n) { state.keepAhead = n; ev.emit(api.state()); },
      setTarget(id) { state.target = id; ev.emit(api.state()); },
      addComputer(c) { state.computers.push({ id: 'pc' + Date.now(), ...c }); ev.emit(api.state()); },
      removeComputer(id) { state.computers = state.computers.filter((c) => c.id !== id); if (state.target === id) state.target = 'phone'; ev.emit(api.state()); },
      allowBattery() { state.batteryRestricted = false; ev.emit(api.state()); },
    };
    timer = setTimeout(tick, 1000);
    return api;
  })();

  // AI generation simulation (cover + description). Covers reuse other library covers as "AI versions".
  const ai = (() => {
    const ev = emitter();
    const pool = stories.filter((s) => s.cover);
    const st = {}; // storyId -> {versions:[{id,label,url,prompt}], inUse, pending, job, desc, draft}
    const get = (id) => {
      if (st[id]) return st[id];
      const s = byId[id]; const r = rng(hash(id));
      const versions = [{ id: 'src', label: 'Source', url: coverUrl(s), prompt: null }];
      const n = 1 + Math.floor(r() * 3);
      for (let i = 1; i <= n; i++) versions.push({ id: 'ai' + i, label: 'AI ' + i, url: coverUrl(pool[Math.floor(r() * pool.length)]), prompt: `A dramatic painted book cover for "${s.title}": a lone figure framed by storm light, rich oil-paint texture, title space at the top, no text.` });
      st[id] = { versions, inUse: 'src', pending: null, job: null, desc: null, draft: null, showAiDesc: false };
      return st[id];
    };
    const api = {
      on: ev.on,
      state: (id) => ({ ...get(id), versions: get(id).versions.slice() }),
      generateCover(id, prompt) {
        const x = get(id); if (x.job) return;
        const steps = prompt ? ['Painting cover...'] : ['Writing image prompt...', 'Painting cover...'];
        let i = 0; x.job = { kind: 'cover', message: steps[0], storyId: id }; ev.emit(api.state(id));
        const step = () => { i++; if (i < steps.length) { x.job.message = steps[i]; ev.emit(api.state(id)); setTimeout(step, 2200); } else { const n = x.versions.length; const v = { id: 'ai' + n, label: 'AI ' + n, url: coverUrl(pool[Math.floor(Math.random() * pool.length)]), prompt: prompt || 'A painted fantasy book cover with a hero silhouetted against a burning sky, no text.', isNew: true }; x.versions.splice(1, 0, v); x.pending = v.id; x.job = null; x.cost = '$0.04 · 2 calls'; ev.emit(api.state(id)); } };
        setTimeout(step, 2200);
      },
      useCover(id, vid) { const x = get(id); x.inUse = vid; x.pending = null; ev.emit(api.state(id)); },
      keepCurrent(id) { get(id).pending = null; ev.emit(api.state(id)); },
      generateDescription(id) {
        const x = get(id); if (x.job) return; x.job = { kind: 'description', message: 'Generating...', storyId: id }; ev.emit(api.state(id));
        setTimeout(() => { const s = byId[id]; x.job = null; x.draft = `${s.title.split(/[[(]/)[0].trim()} follows a protagonist forced to grow from nothing in a world that punishes weakness. Across ${s.total} chapters, ${s.author}'s story mixes hard-won progression with ${(s.tags[0] || 'adventure').toLowerCase()} and ${(s.tags[2] || 'character drama').toLowerCase()}, building toward stakes that keep escalating.`; x.cost = '$0.003 · 1 call'; ev.emit(api.state(id)); }, 2600);
      },
      applyDraft(id) { const x = get(id); x.desc = x.draft; x.draft = null; x.showAiDesc = true; ev.emit(api.state(id)); },
      discardDraft(id) { get(id).draft = null; ev.emit(api.state(id)); },
      setShowAi(id, v) { get(id).showAiDesc = v; ev.emit(api.state(id)); },
      models: { description: 'google/gemini-2.5-flash', image: 'google/gemini-2.5-flash-image', decision: 'openai/gpt-5-mini', reasoning: 'Default' },
      spend: { today: 0.04, month: 1.82, allTime: 6.37, unknown: 0, recent: [
        { feature: 'Cover image', model: 'google/gemini-2.5-flash-image', at: NOW - 3600e3, cost: 0.039, tokens: 1290 },
        { feature: 'Cover prompt', model: 'google/gemini-2.5-flash', at: NOW - 3605e3, cost: 0.002, tokens: 5210 },
        { feature: 'Description', model: 'google/gemini-2.5-flash', at: NOW - 86400e3 * 2, cost: 0.003, tokens: 8800 },
      ] },
    };
    return api;
  })();

  // Settings (mutable in-memory copy).
  const settings = {
    layout: 'auto',
    tts: { ...D.tts, engine: 'Kokoro (built in)', voiceLabel: 'Bella (American, female)', profileStatus: 'Based on "Google", with changes not saved to it.', replayAfterBreaks: !!(D.tts && D.tts.contextReplay), sayTitles: !!(D.tts && D.tts.announceChapters), skipWholeChapters: false, kokoroInstalledMb: 369 },
    reader: { fontScale: (D.prefs && D.prefs.readerFontScale) || 1, dark: true },
    downloads: { parallel: 2, delayMin: 800, delayMax: 2500, maxPerEpub: 150 },
    app: { version: '1.0.21-native', checkedAgo: '5 minutes ago', autoCheck: true },
    cleanup: D.cleanup,
    backups: [{ label: 'Full backup', size: '412 MB', at: NOW - 86400e3 * 3 }, { label: 'Library backup (JSON)', size: '1.8 MB', at: NOW - 86400e3 * 11 }],
  };

  window.WNA = {
    data: D, base: BASE, NOW,
    stories: () => stories, story: (id) => byId[id], tabs: () => tabs, storiesInTab, chapters, chapter, chapterIndex, chapterText, sentences,
    sources: SOURCES, sourceCounts, tagCounts, SORTS, filterStories, authorNovels,
    stats: { stories: stories.length, chapters: D.stats.chapters, downloaded: D.stats.downloaded },
    coverUrl, coverFallback, hue, forecast, scoreTrend, patreonLine, coverage, trendSeries,
    fmt, router, route, go, back, session,
    player, downloads, narration, ai, settings,
    el, esc, on, debounce, hash, rng,
  };

  // ---------- overlay (version switcher + feedback) ----------
  const vs = document.createElement('script'); vs.src = BASE + 'versions.js';
  vs.onload = () => { const ov = document.createElement('script'); ov.src = BASE + 'overlay.js'; document.body.appendChild(ov); };
  document.head.appendChild(vs);
})();
