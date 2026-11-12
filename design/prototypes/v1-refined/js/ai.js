/* v1 Refined — AI Controls (cover versions + description). */
(function () {
  const V = window.V1;
  const { esc } = WNA;
  V.coverOverride = {};
  const CTX = ['Automatic', 'First 3 chapters', 'First 10 chapters', 'Latest 5 chapters'];

  V.screens.ai = (r) => {
    const s = WNA.story(r.params.id);
    const id = s.id;
    const st = { tab: r.query.tab === 'description' ? 'description' : 'cover', ctxCover: 'Automatic', ctxDesc: 'Automatic', prompt: '', writing: false, seenCover: true, seenDesc: true };

    const el = WNA.el(`
      <div class="screen">
        ${V.appBar({ title: 'AI Controls', subtitle: s.title })}
        <div class="wrap">
          <div class="seg tabseg" style="margin:4px 0 16px"></div>
          <div class="body"></div>
          <p class="ai-foot">Image: ${esc(WNA.ai.models.image)} · Prompt: ${esc(WNA.ai.models.description)}<br><a href="#/settings/ai" data-act="aiset">Change in AI Settings</a></p>
        </div>
        <div class="pad-bottom"></div>
      </div>`);
    const $ = (q) => el.querySelector(q);
    const A = () => WNA.ai.state(id);

    function tabs() {
      const a = A();
      const dotC = a.pending && st.tab !== 'cover';
      const dotD = a.draft && st.tab !== 'description';
      $('.tabseg').innerHTML = `<button data-act="tab" data-k="cover" class="${st.tab === 'cover' ? 'on' : ''}">${V.ic('image', 'sm')}Cover${dotC ? '<i class="dotn"></i>' : ''}</button><button data-act="tab" data-k="description" class="${st.tab === 'description' ? 'on' : ''}">${V.ic('notes', 'sm')}Description${dotD ? '<i class="dotn"></i>' : ''}</button>`;
    }
    const jobCard = (a) => `<div class="ai-job">${'<span class="spin"></span>'}<div class="grow"><div style="font:var(--t-body-m)">${esc(a.job.message)}</div><div class="faint" style="font:var(--t-xs);margin-top:2px">You can leave this screen; it keeps going.</div></div></div>`;

    function coverTab() {
      const a = A();
      const cur = a.versions.find((v) => v.id === a.inUse) || a.versions[0];
      const pend = a.pending && a.versions.find((v) => v.id === a.pending);
      const thumb = (v, w) => (v.url ? `<div class="cov" style="width:${w}px;height:${w * 1.5}px"><img src="${v.url}" alt=""></div>` : V.cover(s, w));
      return `
        ${a.job && a.job.kind === 'cover' ? jobCard(a) : ''}
        ${pend ? `<div class="card ai-ready" style="margin-top:${a.job ? 12 : 0}px">
          <h3>${V.ic('auto_awesome', 'fill')}New cover ready</h3>
          <div class="cmp"><figure>${thumb(cur, 88)}Current</figure>${V.ic('arrow_forward', 'faint')}<figure>${thumb(pend, 88)}<span class="gold">New</span></figure></div>
          <p class="cost">OpenRouter: ${esc(a.cost || '$0.04 · 2 calls')}</p>
          <div class="btn-row fill" style="margin-top:14px">${V.btn('Keep current', 'keep', { kind: 'outlined' })}${V.btn('Use new cover', 'usenew', { kind: 'filled' })}</div>
        </div>` : ''}
        <section class="section">
          <div class="sec-h"><h2>Covers</h2><span class="faint" style="font:var(--t-xs)">${a.versions.length} versions</span></div>
          <div class="strip">${a.versions.map((v) => `<div class="cv ${v.id === a.inUse ? 'inuse' : ''}" data-vid="${v.id}" role="button" tabindex="0"><div style="position:relative">${thumb(v, 96)}${v.isNew ? '<span class="pill gold nb">New</span>' : ''}${v.id === a.inUse ? `<span class="ck">${V.ic('check')}</span>` : ''}</div>${esc(v.label)}</div>`).join('')}</div>
          <p class="sec-help" style="margin-top:4px">Tap a cover to use it. Long-press to see its prompt.</p>
        </section>
        <section class="section">
          <div class="sec-h"><h2>Make a new cover</h2></div>
          <div class="card pad" style="display:grid;gap:14px">
            <button class="li tap" data-act="ctx" data-k="ctxCover" style="padding:0;min-height:44px"><div class="grow"><div class="lt">Cover context chapters</div><div class="ls">Chapters the AI reads before painting</div></div><span class="lv">${esc(st.ctxCover)}</span>${V.ic('chevron_right', 'chev')}</button>
            <label class="field"><span class="lab">Image prompt (optional)</span><span class="input ta"><textarea name="prompt" rows="4" placeholder="Leave empty and the AI writes one from the story">${esc(st.prompt)}</textarea></span></label>
            <div class="btn-row fill">
              <button class="btn outlined" data-act="write" ${st.writing || a.job ? 'disabled' : ''}>${st.writing ? '<span class="spin"></span>' : V.ic('edit_note')}<span>Write prompt with AI</span></button>
            </div>
            ${V.btn(a.job && a.job.kind === 'cover' ? 'Generating…' : 'Generate cover', 'gen', { kind: 'filled', icon: 'auto_awesome', cls: 'block lg', disabled: !!a.job })}
          </div>
        </section>`;
    }

    function descTab() {
      const a = A();
      return `
        <div class="list"><button class="li tap" data-act="ctx" data-k="ctxDesc"><div class="grow"><div class="lt">Description context chapters</div><div class="ls">Chapters the AI reads to summarize</div></div><span class="lv">${esc(st.ctxDesc)}</span>${V.ic('chevron_right', 'chev')}</button></div>
        ${a.desc ? `<section class="section"><div class="sec-h"><h2>Current AI description</h2><span class="pill ongoing">${V.ic('auto_awesome')}AI generated</span></div>
          <div class="card pad"><p class="desc">${esc(a.desc)}</p>
          <button class="row" data-act="showai" style="margin-top:14px;min-height:44px;width:100%"><span class="check ${a.showAiDesc ? 'on' : ''}">${V.ic('check')}</span><span style="font:var(--t-body)">Show AI description in Details</span></button></div></section>` : ''}
        ${a.job && a.job.kind === 'description' ? `<div style="margin-top:16px">${jobCard(a)}</div>` : ''}
        ${a.draft ? `<section class="section"><div class="sec-h"><h2>Draft</h2><span class="faint" style="font:var(--t-xs)">${esc(a.cost || '')}</span></div>
          <div class="card draft"><span class="pill ongoing">${V.ic('auto_awesome')}Preview</span><p>${esc(a.draft)}</p>
          <div class="btn-row fill" style="margin-top:16px">${V.btn('Discard', 'discard', { kind: 'outlined' })}${V.btn('Apply', 'apply', { kind: 'filled', icon: 'check' })}</div></div></section>` : ''}
        <section class="section">
          <div class="sec-h"><h2>Source description</h2></div>
          <div class="card pad"><p class="desc clamp">${esc(s.desc || 'No description.')}</p></div>
        </section>
        <div style="margin-top:16px">${V.btn(a.desc ? 'Generate a new description' : 'Generate Description with AI', 'gendesc', { kind: 'filled', icon: 'auto_awesome', cls: 'block lg', disabled: !!a.job })}</div>`;
    }

    function render() {
      tabs();
      $('.body').innerHTML = st.tab === 'cover' ? coverTab() : descTab();
    }
    render();

    V.bind(el, {
      tab(t) { st.tab = t.dataset.k; render(); },
      aiset: () => WNA.go('/settings/ai'),
      ctx(t) { const k = t.dataset.k; V.menu({ title: 'Context chapters', items: CTX.map((c) => ({ act: c, label: c, on: st[k] === c })), onPick(v) { st[k] = v; render(); } }); },
      write() {
        st.writing = true; render();
        setTimeout(() => { st.writing = false; st.prompt = `Painted fantasy cover for “${V.short(s.title)}”: ${s.tags.slice(0, 3).join(', ').toLowerCase()} mood, a lone hooded figure in the foreground, dramatic rim light, deep teal and gold palette, painterly texture, empty space at the top for a title, no lettering.`; render(); V.toast('Prompt written · edit it if you like'); }, 1500);
      },
      gen() { const ta = el.querySelector('textarea[name=prompt]'); st.prompt = ta ? ta.value : st.prompt; WNA.ai.generateCover(id, st.prompt.trim()); V.aiWatch(id); render(); },
      usenew() { const a = A(); const v = a.versions.find((x) => x.id === a.pending); WNA.ai.useCover(id, a.pending); V.coverOverride[id] = v.url; V.libDirty = true; V.toast('Cover updated'); },
      keep() { WNA.ai.keepCurrent(id); V.toast('Kept the current cover'); },
      gendesc() { WNA.ai.generateDescription(id); V.aiWatch(id); },
      apply() { WNA.ai.applyDraft(id); V.toast('Description applied · shown in Details'); },
      discard() { WNA.ai.discardDraft(id); V.toast('Draft discarded'); },
      showai() { const a = A(); WNA.ai.setShowAi(id, !a.showAiDesc); },
    });
    el.addEventListener('input', (e) => { if (e.target.name === 'prompt') st.prompt = e.target.value; });
    WNA.on(el, 'click', '.cv[data-vid]', (e, t) => {
      const a = A(); if (t.dataset.vid === a.inUse) return;
      const v = a.versions.find((x) => x.id === t.dataset.vid);
      WNA.ai.useCover(id, v.id); V.coverOverride[id] = v.id === 'src' ? null : v.url; V.libDirty = true;
      V.toast(`Using ${v.label} cover`);
    });
    V.longPress(el, '.cv[data-vid]', (t) => {
      const v = A().versions.find((x) => x.id === t.dataset.vid);
      V.dialog({ icon: 'image', title: `${v.label} prompt`, body: v.prompt || 'This is the cover from the source site. It has no prompt.', actions: [{ label: 'Copy', value: 'copy' }, { label: 'Close', value: null, kind: 'filled' }] }).then((x) => { if (x.value === 'copy') V.toast('Prompt copied'); });
    });

    const off = WNA.ai.on(() => { const ta = el.querySelector('textarea[name=prompt]'); if (ta) st.prompt = ta.value; render(); });
    V.watchScroll(el);
    return { el, title: 'AI Controls', destroy: off };
  };
})();
