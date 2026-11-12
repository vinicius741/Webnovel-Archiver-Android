/* v1 Refined — Reader with live TTS highlighting. */
(function () {
  const V = window.V1;
  const { esc } = WNA;

  V.screens.reader = (r) => {
    const s = WNA.story(r.params.id);
    const id = s.id;
    const list = WNA.chapters(id);
    const c = WNA.chapter(id, r.params.chapterId);
    const ci = WNA.chapterIndex(id, c.id);
    const sents = WNA.sentences(id, c.id);
    const prev = list.slice(0, ci).reverse().find((x) => x.downloaded);
    const next = list.slice(ci + 1).find((x) => x.downloaded);

    // paragraphs -> sentence spans
    const paras = [];
    sents.forEach((x, i) => { (paras[x.p] = paras[x.p] || []).push(`<span class="sn" data-s="${i}">${esc(x.text)}</span>`); });
    const isBm = () => s.bookmark === ci;

    const el = WNA.el(`
      <div class="screen reader" style="--rs:${V.state.readerScale}">
        <div class="rd-top">
          ${V.appBar({ title: c.title, subtitle: `${ci + 1} / ${list.length} · ${s.title}`, actions: '<span class="acts"></span>' })}
          <div class="rd-prog"><i></i></div>
        </div>
        <article class="rd-body">
          <div class="rd-kicker">${esc(s.title)}</div>
          <h1 class="rd-h">${esc(c.title)}</h1>
          ${paras.filter(Boolean).map((p, i) => `<p data-p="${i}">${p.join(' ')}</p>`).join('')}
          <div class="rd-end">
            ${next ? `<button class="rd-next" data-act="next"><span class="faint">Up next · ${ci + 2} of ${list.length}</span><b>${esc(next.title)}</b>${V.ic('arrow_forward')}</button>` : `<div class="faint" style="text-align:center;padding:24px">You’re caught up. Sync the story to check for new chapters.</div>`}
          </div>
        </article>
        <div class="rd-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);

    function acts() {
      const ps = WNA.player.state();
      const here = ps.loaded && ps.story.id === id && ps.chapter.id === c.id;
      $('.acts').outerHTML = `<span class="acts" style="display:flex">${V.ib('bookmark', 'bm', isBm() ? 'Remove bookmark' : 'Bookmark chapter', { fill: isBm(), cls: isBm() ? 'on bmon' : '' })}${V.ib(here && ps.playing ? 'pause_circle' : 'headphones', 'aloud', 'Read aloud', { cls: here ? 'on' : '' })}${V.ib('text_fields', 'settings', 'Reader settings')}</span>`;
    }
    function bottom() {
      const ps = WNA.player.state();
      const docked = ps.loaded && ps.story.id === id;
      const b = $('.rd-bottom');
      if (docked) { b.className = 'rd-bottom docked'; b.innerHTML = `<div class="mini docked">${V.miniHtml(ps, true)}</div>`; return; }
      b.className = 'rd-bottom';
      b.innerHTML = `<nav class="chnav">
        <button class="nv" data-act="prev" ${prev ? '' : 'disabled'}>${V.ic('chevron_left')}<span>Previous</span></button>
        <div class="mid tnum"><b>${ci + 1}</b> / ${list.length}<div class="prog"><i style="width:${V.pct((ci + 1) / list.length)}"></i></div></div>
        <button class="nv r" data-act="next" ${next ? '' : 'disabled'}><span>Next</span>${V.ic('chevron_right')}</button>
      </nav>`;
    }

    // ---------- highlight + follow ----------
    let lastIdx = -1, userScrollAt = 0, programmatic = false, first = true;
    function highlight(ps) {
      const here = ps.loaded && ps.story.id === id && ps.chapter.id === c.id;
      const idx = here ? ps.index : -1;
      if (idx === lastIdx) return;
      const old = el.querySelector('.sn.hl'); if (old) old.classList.remove('hl');
      lastIdx = idx;
      if (idx < 0) return;
      const sp = el.querySelector(`.sn[data-s="${idx}"]`);
      if (!sp) return;
      sp.classList.add('hl');
      if (Date.now() - userScrollAt > 3500) {
        const rect = sp.getBoundingClientRect(), box = el.getBoundingClientRect();
        const target = el.scrollTop + rect.top - box.top - box.height * 0.4;
        programmatic = true;
        el.scrollTo({ top: Math.max(0, target), behavior: first ? 'auto' : 'smooth' });
        first = false;
        setTimeout(() => (programmatic = false), 600);
      }
    }
    let followChapter = c.id;
    const onPlayer = (ps) => {
      acts(); bottom(); highlight(ps);
      // follow the narration into the next chapter while reading along
      if (ps.loaded && ps.story.id === id && ps.chapter.id !== c.id && followChapter === c.id && ps.playing) {
        followChapter = null;
        WNA.go(`/reader/${id}/${encodeURIComponent(ps.chapter.id)}`, { replace: true });
      }
    };

    // reading progress
    const prog = $('.rd-prog i');
    el.addEventListener('scroll', () => {
      if (!programmatic) userScrollAt = Date.now();
      const max = el.scrollHeight - el.clientHeight;
      prog.style.width = V.pct(max > 0 ? el.scrollTop / max : 0);
      el.classList.toggle('scrolled', el.scrollTop > 4);
    }, { passive: true });

    function readFrom(idx) {
      const ps = WNA.player.state();
      if (ps.loaded && ps.story.id === id && ps.chapter.id === c.id) { WNA.player.seekSentence(idx); if (!ps.playing) WNA.player.play(); }
      else WNA.player.load(id, c.id, { index: idx, play: true });
      userScrollAt = 0;
    }

    const act = {
      bm() {
        s.bookmark = isBm() ? null : ci; V.libDirty = true; acts();
        V.toast(isBm() ? `Bookmarked chapter ${ci + 1}` : 'Bookmark removed');
      },
      aloud() {
        const ps = WNA.player.state();
        if (ps.loaded && ps.story.id === id && ps.chapter.id === c.id) { WNA.player.toggle(); return; }
        const ss = WNA.session;
        if (ss && ss.storyId === id && ss.chapterId === c.id) { readFrom(ss.index); return; }
        if (ss && ss.storyId === id) {
          V.dialog({ icon: 'headphones', title: 'Read aloud', body: 'You have a saved listening spot in this story.', actions: [{ label: 'Cancel', value: null }, { label: `Continue ${V.short(ss.chapterTitle)}`, value: 'cont', kind: 'tonal' }, { label: 'Listen from this chapter', value: 'here', kind: 'filled' }] })
            .then(({ value }) => {
              if (value === 'here') readFrom(0);
              if (value === 'cont') { WNA.player.load(id, ss.chapterId, { index: ss.index, play: true }); WNA.go(`/reader/${id}/${ss.chapterId}`, { replace: true }); }
            });
          return;
        }
        readFrom(0);
      },
      settings: openSettings,
      prev: () => prev && WNA.go(`/reader/${id}/${encodeURIComponent(prev.id)}`, { replace: true }),
      next: () => next && WNA.go(`/reader/${id}/${encodeURIComponent(next.id)}`, { replace: true }),
    };
    V.bind(el, act);
    el.addEventListener('click', (e) => { const t = e.target.closest('[data-mini]'); if (t) V.miniAction(t.dataset.mini); });

    // double-tap a paragraph to read from there
    let lastTap = { t: 0, p: null };
    el.addEventListener('pointerup', (e) => {
      const p = e.target.closest('.rd-body p[data-p]');
      if (!p) return;
      const now = Date.now();
      if (lastTap.p === p && now - lastTap.t < 320) {
        const sn = e.target.closest('.sn') || p.querySelector('.sn');
        readFrom(+sn.dataset.s);
        p.classList.add('flash'); setTimeout(() => p.classList.remove('flash'), 600);
        lastTap = { t: 0, p: null };
      } else lastTap = { t: now, p };
    });

    function openSettings() {
      const pct = () => Math.round(V.state.readerScale * 100);
      V.sheet({
        title: 'Reader settings',
        body: `
          <div style="padding:4px 24px 8px">
            <div class="fsz">
              <button class="ib" data-f="-1" aria-label="Smaller text"><span style="font:600 15px var(--font)">A−</span></button>
              <div class="grow" style="text-align:center"><b class="tnum fszv">${pct()}%</b><div class="faint" style="font:var(--t-xs)">Text size</div></div>
              <button class="ib" data-f="1" aria-label="Larger text"><span style="font:600 20px var(--font)">A+</span></button>
            </div>
            <input class="slider fszs" type="range" min="80" max="160" step="10" value="${pct()}" style="--p:${((pct() - 80) / 80) * 100}%" aria-label="Text size">
          </div>
          <div class="list" style="margin:8px 16px 0;background:var(--s3)">
            <div class="li"><div class="grow"><div class="lt">Dark reader</div><div class="ls">Warm dark page for night reading</div></div>${V.sw(WNA.settings.reader.dark, 'dark')}</div>
            <button class="li" data-go="voice">${V.ic('record_voice_over', 'gold')}<div class="grow"><div class="lt">Voice settings</div><div class="ls">Bella · Kokoro · 1.8×</div></div>${V.ic('chevron_right', 'chev')}</button>
            <button class="li" data-go="copy">${V.ic('content_copy', 'gold')}<div class="grow"><div class="lt">Copy chapter text</div></div></button>
          </div>`,
        onMount(sh, close) {
          const apply = (v) => {
            V.state.readerScale = Math.max(0.8, Math.min(1.6, Math.round(v * 10) / 10));
            el.style.setProperty('--rs', V.state.readerScale);
            sh.querySelector('.fszv').textContent = pct() + '%';
            const sl = sh.querySelector('.fszs'); sl.value = pct(); V.syncSlider(sl);
          };
          WNA.on(sh, 'click', '[data-f]', (e, t) => apply(V.state.readerScale + 0.1 * +t.dataset.f));
          sh.querySelector('.fszs').addEventListener('input', (e) => apply(+e.target.value / 100));
          WNA.on(sh, 'click', '[data-act="dark"]', (e, t) => { WNA.settings.reader.dark = !WNA.settings.reader.dark; t.classList.toggle('on'); el.classList.toggle('light-ish', !WNA.settings.reader.dark); });
          WNA.on(sh, 'click', '[data-go]', (e, t) => { close(); if (t.dataset.go === 'voice') WNA.go('/settings/tts'); else V.toast('Chapter text copied'); });
        },
      });
    }

    acts(); bottom();
    const off = WNA.player.on(onPlayer);
    return {
      el, title: c.title, destroy: off,
      onShow() {
        setTimeout(() => {
          lastIdx = -1; first = true; highlight(WNA.player.state());
          if (r.query.play === '1') {
            const ss = WNA.session;
            const idx = ss && ss.storyId === id && ss.chapterId === c.id ? ss.index : 0;
            const ps = WNA.player.state();
            if (!(ps.loaded && ps.story.id === id && ps.chapter.id === c.id)) WNA.player.load(id, c.id, { index: idx, play: true });
          }
        }, 30);
      },
    };
  };
})();
