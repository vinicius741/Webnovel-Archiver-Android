"""Generates a self-contained blind-ballot web page for the chapter-polish spike.

`python3 spike.py ballot-ui` writes results/ballot.html — one file, no server,
no dependencies: open it by double-clicking. The page embeds the same version
data and label shuffle as results/ballot.md / ballot_key.json (both come from
spike.build_ballot), so votes made against the page map onto the key.

The reader gets: one version at a time in a comfortable reading layout, a
blind rating (worse / same / better / much better than the chapter's other
versions), optional notes, a side-by-side compare mode, progress tracking,
localStorage persistence, and an export block to paste back. The key reveal is
gated behind an explicit confirmation.
"""

from __future__ import annotations

import html
import json
import os

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Chapter-polish blind ballot</title>
<style>
  :root {
    --bg: #0b0f19;
    --panel: #131b2e;
    --panel2: #1c263d;
    --panel3: #263352;
    --panel-hover: #2a385b;
    --ink: #f1f5f9;
    --ink-bright: #ffffff;
    --muted: #94a3b8;
    --muted-light: #cbd5e1;
    --line: #2d3d5f;
    --line-light: #3e527d;
    
    --accent: #3b82f6;
    --accent-hover: #60a5fa;
    --accent-glow: rgba(59, 130, 246, 0.25);
    
    /* Rating states */
    --bad: #ef4444;
    --bad-bg: rgba(239, 68, 68, 0.15);
    --bad-border: #f87171;
    --bad-solid: #dc2626;
    
    --same: #a855f7;
    --same-bg: rgba(168, 85, 247, 0.15);
    --same-border: #c084fc;
    --same-solid: #9333ea;
    
    --better: #0ea5e9;
    --better-bg: rgba(14, 165, 233, 0.15);
    --better-border: #38bdf8;
    --better-solid: #0284c7;
    
    --good: #10b981;
    --good-bg: rgba(16, 185, 129, 0.15);
    --good-border: #34d399;
    --good-solid: #059669;

    --reader-bg: #111726;
    --reader-ink: #e2e8f0;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--ink);
    font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  button, select, input {
    font: inherit;
    color: inherit;
  }
  header {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    flex-wrap: wrap;
    gap: .75rem;
    align-items: center;
    padding: .75rem 1.25rem;
    background: var(--panel);
    border-bottom: 1px solid var(--line);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  }
  header h1 {
    font-size: 1.05rem;
    margin: 0 1rem 0 0;
    font-weight: 700;
    color: var(--ink-bright);
    letter-spacing: -0.01em;
  }
  .tabs {
    display: flex;
    gap: .4rem;
    flex-wrap: wrap;
  }
  .tab {
    padding: .4rem .85rem;
    border: 1px solid var(--line);
    border-radius: 9999px;
    background: var(--panel2);
    color: var(--muted-light);
    cursor: pointer;
    font-size: .85rem;
    font-weight: 500;
    transition: all 0.15s ease;
  }
  .tab:hover {
    color: var(--ink-bright);
    border-color: var(--line-light);
    background: var(--panel3);
  }
  .tab.active {
    color: #ffffff;
    border-color: var(--accent);
    background: #1e3a8a;
    box-shadow: 0 0 0 1px var(--accent), 0 2px 6px var(--accent-glow);
    font-weight: 600;
  }
  .progress {
    margin-left: auto;
    color: var(--muted-light);
    font-size: .85rem;
    font-weight: 500;
    background: var(--panel2);
    padding: .35rem .75rem;
    border-radius: .4rem;
    border: 1px solid var(--line);
  }
  main {
    max-width: 1000px;
    margin: 0 auto;
    padding: 1.2rem 1.25rem 5rem;
  }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: .6rem;
    align-items: center;
    margin: .6rem 0 1.2rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: .4rem;
    padding: .45rem .9rem;
    border-radius: .5rem;
    border: 1px solid var(--line);
    background: var(--panel2);
    color: var(--ink-bright);
    cursor: pointer;
    font-size: .9rem;
    font-weight: 550;
    transition: all 0.15s ease;
  }
  .btn:hover {
    border-color: var(--line-light);
    background: var(--panel3);
    color: var(--ink-bright);
  }
  .btn:active {
    transform: translateY(1px);
  }
  .btn.primary {
    background: #2563eb;
    color: #ffffff;
    border-color: #3b82f6;
    font-weight: 600;
    box-shadow: 0 2px 8px rgba(37, 99, 235, 0.35);
  }
  .btn.primary:hover {
    background: #1d4ed8;
    border-color: #60a5fa;
  }
  .btn.ghost {
    background: transparent;
    color: var(--muted-light);
    border-color: var(--line);
  }
  .btn.ghost:hover {
    background: var(--panel2);
    color: var(--ink-bright);
    border-color: var(--line-light);
  }
  select.btn {
    appearance: auto;
    color: var(--ink-bright);
    background-color: var(--panel2);
    padding-right: 1.5rem;
  }
  select.btn option {
    background-color: var(--panel);
    color: var(--ink-bright);
  }
  .version-picker {
    display: flex;
    gap: .5rem;
    flex-wrap: wrap;
    margin: .4rem 0 1.2rem;
  }
  .vchip {
    min-width: 2.8rem;
    padding: .45rem .75rem;
    text-align: center;
    border: 1px solid var(--line);
    border-radius: .5rem;
    background: var(--panel2);
    cursor: pointer;
    color: var(--ink-bright);
    font-weight: 700;
    font-size: 1rem;
    transition: all 0.15s ease;
  }
  .vchip:hover {
    border-color: var(--line-light);
    background: var(--panel3);
  }
  .vchip.active {
    border-color: var(--accent);
    background: #1e3a8a;
    color: #ffffff;
    box-shadow: 0 0 0 1px var(--accent), 0 2px 8px var(--accent-glow);
  }
  .vchip .dot {
    display: block;
    height: 4px;
    margin: .3rem auto 0;
    border-radius: 2px;
    background: transparent;
  }
  .vchip[data-rated="2"] .dot { background: var(--good); box-shadow: 0 0 4px var(--good); }
  .vchip[data-rated="1"] .dot { background: var(--better); box-shadow: 0 0 4px var(--better); }
  .vchip[data-rated="0"] .dot { background: var(--same); box-shadow: 0 0 4px var(--same); }
  .vchip[data-rated="-1"] .dot { background: var(--bad); box-shadow: 0 0 4px var(--bad); }
  
  .rating-row {
    display: flex;
    gap: .6rem;
    flex-wrap: wrap;
    align-items: center;
    margin-bottom: 1.2rem;
    background: var(--panel);
    padding: .85rem 1rem;
    border-radius: .7rem;
    border: 1px solid var(--line);
  }
  .rate {
    padding: .55rem 1rem;
    border-radius: .55rem;
    border: 1px solid var(--line);
    background: var(--panel2);
    color: var(--ink-bright);
    cursor: pointer;
    font-size: .95rem;
    font-weight: 600;
    transition: all 0.15s ease;
    display: inline-flex;
    align-items: center;
    gap: .4rem;
  }
  .rate:hover {
    transform: translateY(-1px);
  }
  .rate[data-r="-1"]:hover {
    border-color: var(--bad-border);
    background: var(--bad-bg);
    color: #fca5a5;
  }
  .rate[data-r="0"]:hover {
    border-color: var(--same-border);
    background: var(--same-bg);
    color: #e9d5ff;
  }
  .rate[data-r="1"]:hover {
    border-color: var(--better-border);
    background: var(--better-bg);
    color: #bae6fd;
  }
  .rate[data-r="2"]:hover {
    border-color: var(--good-border);
    background: var(--good-bg);
    color: #a7f3d0;
  }
  
  /* Selected state */
  .rate.sel[data-r="-1"] {
    background: var(--bad-solid);
    color: #ffffff;
    border-color: var(--bad-border);
    box-shadow: 0 2px 10px rgba(239, 68, 68, 0.4);
  }
  .rate.sel[data-r="0"] {
    background: var(--same-solid);
    color: #ffffff;
    border-color: var(--same-border);
    box-shadow: 0 2px 10px rgba(168, 85, 247, 0.4);
  }
  .rate.sel[data-r="1"] {
    background: var(--better-solid);
    color: #ffffff;
    border-color: var(--better-border);
    box-shadow: 0 2px 10px rgba(14, 165, 233, 0.4);
  }
  .rate.sel[data-r="2"] {
    background: var(--good-solid);
    color: #ffffff;
    border-color: var(--good-border);
    box-shadow: 0 2px 10px rgba(16, 185, 129, 0.4);
  }
  
  .note {
    flex: 1 1 240px;
    padding: .55rem .85rem;
    border-radius: .55rem;
    border: 1px solid var(--line);
    background: var(--panel2);
    color: var(--ink-bright);
    min-width: 200px;
    font-size: .95rem;
    transition: border-color 0.15s ease, box-shadow 0.15s ease;
  }
  .note:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 2px var(--accent-glow);
    background: var(--panel3);
  }
  .note::placeholder {
    color: var(--muted);
  }
  
  .reader {
    background: var(--reader-bg);
    border: 1px solid var(--line);
    border-radius: .8rem;
    padding: 2.5rem clamp(1.2rem, 5vw, 3.5rem);
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
  }
  .reader article {
    font-family: "Iowan Old Style", Georgia, "Times New Roman", serif;
    font-size: 1.06rem;
    line-height: 1.75;
    color: var(--reader-ink);
    max-width: 68ch;
    margin: 0 auto;
  }
  .reader p {
    margin: 0 0 1.2rem;
  }
  .reader p.panel {
    font-family: ui-monospace, Menlo, Monaco, Consolas, monospace;
    font-size: .88rem;
    white-space: pre-wrap;
    background: var(--panel2);
    border: 1px solid var(--line-light);
    border-left: 3px solid var(--accent);
    border-radius: .5rem;
    padding: .8rem 1rem;
    color: var(--muted-light);
  }
  .compare {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
  }
  .compare .col {
    overflow: auto;
    max-height: 75vh;
  }
  .col-title {
    font-weight: 700;
    margin-bottom: .5rem;
    color: var(--muted-light);
    font-size: .95rem;
    position: sticky;
    top: 0;
    background: var(--bg);
    padding: .4rem 0;
    z-index: 2;
  }
  @media (max-width: 900px) {
    .compare { grid-template-columns: 1fr; }
  }
  dialog {
    background: var(--panel);
    color: var(--ink-bright);
    border: 1px solid var(--line-light);
    border-radius: .8rem;
    max-width: 700px;
    width: 92vw;
    padding: 1.5rem;
    box-shadow: 0 12px 36px rgba(0, 0, 0, 0.6);
  }
  dialog::backdrop {
    background: rgba(0, 0, 0, 0.75);
    backdrop-filter: blur(2px);
  }
  pre.export {
    white-space: pre-wrap;
    background: var(--panel2);
    padding: 1rem;
    border-radius: .5rem;
    border: 1px solid var(--line);
    max-height: 40vh;
    overflow: auto;
    font-size: .85rem;
    color: var(--ink);
    font-family: ui-monospace, Menlo, Monaco, monospace;
  }
  .hint {
    color: var(--muted-light);
    font-size: .85rem;
    margin: .6rem 0 1.2rem;
    line-height: 1.6;
  }
  kbd {
    border: 1px solid var(--line-light);
    border-bottom-width: 2px;
    border-radius: .3rem;
    padding: .1rem .4rem;
    font-size: .8rem;
    background: var(--panel2);
    color: var(--ink-bright);
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  }
</style>
</head>
<body>
<header>
  <h1>Chapter-polish ballot</h1>
  <div class="tabs" id="tabs"></div>
  <div class="progress" id="progress"></div>
</header>
<main>
  <div class="toolbar">
    <button class="btn" id="prev">&larr; Prev</button>
    <button class="btn" id="next">Next &rarr;</button>
    <select class="btn" id="fontSize" title="Reading size">
      <option value="0.95">Smaller text</option>
      <option value="1.06" selected>Normal text</option>
      <option value="1.2">Larger text</option>
    </select>
    <button class="btn" id="compareBtn">Compare two versions</button>
    <button class="btn primary" id="exportBtn">Export votes</button>
    <button class="btn ghost" id="revealBtn">Reveal key (after voting)</button>
  </div>
  <div class="version-picker" id="picker"></div>
  <div id="ratingWrap"></div>
  <div class="hint">
    Rate each version honestly; the labels are anonymous and shuffled with seed __SEED__.
    Keyboard: <kbd>&larr;</kbd>/<kbd>&rarr;</kbd> switch versions, <kbd>1</kbd>&ndash;<kbd>4</kbd> rate
    (1&nbsp;worse, 2&nbsp;same, 3&nbsp;better, 4&nbsp;much better). Progress saves automatically in this browser.
  </div>
  <div class="reader" id="reader"><article id="article"></article></div>
</main>

<dialog id="compareDlg">
  <h3 style="margin-top:0">Compare two versions</h3>
  <div style="display:flex; gap:.6rem; margin-bottom:.8rem;">
    <select id="cmpA" class="btn"></select>
    <select id="cmpB" class="btn"></select>
  </div>
  <div class="compare"><div class="col"><div class="col-title" id="cmpATitle"></div><div class="reader" id="cmpABody"></div></div>
                      <div class="col"><div class="col-title" id="cmpBTitle"></div><div class="reader" id="cmpBBody"></div></div></div>
  <div style="margin-top:.8rem; text-align:right;"><button class="btn" id="cmpClose">Close</button></div>
</dialog>

<dialog id="exportDlg">
  <h3 style="margin-top:0">Your votes</h3>
  <p class="hint">Copy this and paste it back to the assistant, then (optionally) reveal the key.</p>
  <pre class="export" id="exportText"></pre>
  <div style="text-align:right;"><button class="btn primary" id="copyExport">Copy</button>
  <button class="btn" id="exportClose">Close</button></div>
</dialog>

<dialog id="revealDlg">
  <h3 style="margin-top:0">Reveal which model is which?</h3>
  <p class="hint">Only do this after voting. Once seen, it cannot be unseen &mdash; but your saved votes stay.</p>
  <div id="revealBody" style="max-height:50vh; overflow:auto;"></div>
  <div style="margin-top:.8rem; text-align:right;">
    <button class="btn primary" id="revealYes">Reveal</button>
    <button class="btn" id="revealNo">Not yet</button>
  </div>
</dialog>

<script type="application/json" id="ballotData">__DATA__</script>
<script type="application/json" id="ballotKey">__KEY__</script>
<script>
"use strict";
const DATA = JSON.parse(document.getElementById("ballotData").textContent);
const KEY = JSON.parse(document.getElementById("ballotKey").textContent);
const STORE = "chapter-polish-ballot-" + DATA.seed;
let votes = {};
try { votes = JSON.parse(localStorage.getItem(STORE) || "{}"); } catch (e) { votes = {}; }
let chapterIx = 0, versionIx = 0;

const $ = (id) => document.getElementById(id);
const chapter = () => DATA.chapters[chapterIx];
const version = () => chapter().versions[versionIx];

function save() { localStorage.setItem(STORE, JSON.stringify(votes)); }

function voteFor(label) {
  votes[chapter().id] = votes[chapter().id] || {};
  return votes[chapter().id][label] || {};
}

function renderTabs() {
  const tabs = $("tabs"); tabs.innerHTML = "";
  DATA.chapters.forEach((ch, i) => {
    const b = document.createElement("button");
    b.className = "tab" + (i === chapterIx ? " active" : "");
    const done = Object.keys(votes[ch.id] || {}).length;
    b.textContent = `${ch.id} (${ch.kind})${done ? " · " + done + "/" + ch.versions.length : ""}`;
    b.onclick = () => { chapterIx = i; versionIx = 0; render(); };
    tabs.appendChild(b);
  });
}

function renderProgress() {
  const ch = chapter();
  const done = Object.keys(votes[ch.id] || {}).length;
  $("progress").textContent = `${done}/${ch.versions.length} rated · ${versionIx + 1}/${ch.versions.length} shown`;
}

function renderPicker() {
  const picker = $("picker"); picker.innerHTML = "";
  chapter().versions.forEach((v, i) => {
    const b = document.createElement("button");
    b.className = "vchip" + (i === versionIx ? " active" : "");
    const vote = votes[chapter().id] || {};
    b.dataset.rated = vote[v.label] ? String(vote[v.label].rating) : "";
    b.innerHTML = `${v.label}<span class="dot"></span>`;
    b.onclick = () => { versionIx = i; render(); };
    picker.appendChild(b);
  });
}

function paragraphNode(text) {
  const p = document.createElement("p");
  const looksLikePanel = /^([\\[\\(]?\\s*(SYSTEM|\\[|={3,}|-{3,}|[A-Z][A-Za-z ]{2,20}:)|[━─=═]{4,})/.test(text) ||
                          (/^[A-Z][A-Za-z /]{1,18}:/.test(text.split("\\n")[0]) && text.split("\\n").length > 1);
  if (looksLikePanel) p.className = "panel";
  p.textContent = text;
  return p;
}

function fillArticle(articleEl, ver) {
  articleEl.innerHTML = "";
  ver.paragraphs.forEach((t) => articleEl.appendChild(paragraphNode(t)));
}

function renderRating() {
  const wrap = $("ratingWrap"); wrap.innerHTML = "";
  const row = document.createElement("div"); row.className = "rating-row";
  const v = version();
  const opts = [[-1, "✕ Worse than the others"], [0, "≈ Same"], [1, "✓ Better"], [2, "★ Much better"]];
  opts.forEach(([r, label]) => {
    const b = document.createElement("button");
    b.className = "rate" + (voteFor(v.label).rating === r ? " sel" : "");
    b.dataset.r = r; b.textContent = label;
    b.onclick = () => { votes[chapter().id] = votes[chapter().id] || {};
                        votes[chapter().id][v.label] = Object.assign(voteFor(v.label), {rating: r});
                        save(); render(); };
    row.appendChild(b);
  });
  const note = document.createElement("input");
  note.className = "note"; note.type = "text"; note.placeholder = "Optional note for version " + v.label;
  note.value = voteFor(v.label).note || "";
  note.oninput = () => { votes[chapter().id] = votes[chapter().id] || {};
                         votes[chapter().id][v.label] = Object.assign(voteFor(v.label), {note: note.value});
                         save(); };
  row.appendChild(note);
  const tag = document.createElement("div");
  tag.style.cssText = "flex-basis:100%; color:var(--muted); font-size:.85rem;";
  tag.textContent = `Version ${v.label} · ${v.paragraphs.length} paragraphs`;
  row.appendChild(tag);
  wrap.appendChild(row);
}

function render() {
  renderTabs(); renderPicker(); renderProgress(); renderRating();
  fillArticle($("article"), version());
  $("fontSize").onchange = () => { $("article").style.fontSize = $("fontSize").value + "rem"; };
}

function step(d) { versionIx = (versionIx + d + chapter().versions.length) % chapter().versions.length; render();
                   window.scrollTo({top: 0}); }
$("prev").onclick = () => step(-1);
$("next").onclick = () => step(1);
document.addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") return;
  if (e.key === "ArrowLeft") step(-1);
  if (e.key === "ArrowRight") step(1);
  const map = {"1": -1, "2": 0, "3": 1, "4": 2};
  if (map[e.key] !== undefined) {
    const v = version();
    votes[chapter().id] = votes[chapter().id] || {};
    votes[chapter().id][v.label] = Object.assign(voteFor(v.label), {rating: map[e.key]});
    save(); render();
  }
});

// compare dialog
function fillCompareSelects() {
  const ch = chapter();
  [$("cmpA"), $("cmpB")].forEach((sel, i) => {
    sel.innerHTML = "";
    ch.versions.forEach((v) => {
      const o = document.createElement("option");
      o.value = v.label; o.textContent = "Version " + v.label;
      sel.appendChild(o);
    });
    sel.selectedIndex = Math.min(i === 0 ? versionIx : (versionIx + 1) % ch.versions.length, ch.versions.length - 1);
  });
}
function renderCompare() {
  const ch = chapter();
  const a = ch.versions.find((v) => v.label === $("cmpA").value);
  const b = ch.versions.find((v) => v.label === $("cmpB").value);
  $("cmpATitle").textContent = "Version " + a.label;
  $("cmpBTitle").textContent = "Version " + b.label;
  const mk = () => { const el = document.createElement("article"); return el; };
  const aA = mk(), aB = mk();
  fillArticle(aA, a); fillArticle(aB, b);
  $("cmpABody").innerHTML = ""; $("cmpABody").appendChild(aA);
  $("cmpBBody").innerHTML = ""; $("cmpBBody").appendChild(aB);
}
$("compareBtn").onclick = () => { fillCompareSelects(); renderCompare(); $("compareDlg").showModal(); };
[$("cmpA"), $("cmpB")].forEach((s) => s.onchange = renderCompare);
$("cmpClose").onclick = () => $("compareDlg").close();

// export dialog
$("exportBtn").onclick = () => {
  const lines = ["BALLOT VOTES seed=" + DATA.seed, ""];
  DATA.chapters.forEach((ch) => {
    lines.push("## " + ch.id + " (" + ch.kind + ")");
    const ranked = ch.versions.map((v) => ({label: v.label, vote: (votes[ch.id] || {})[v.label]}))
      .sort((x, y) => (y.vote ? y.vote.rating : -99) - (x.vote ? x.vote.rating : -99));
    ranked.forEach(({label, vote}) => {
      const r = vote && vote.rating !== undefined ? ["worse", "same", "better", "MUCH better"][vote.rating + 1] : "unrated";
      const note = vote && vote.note ? " — " + vote.note : "";
      lines.push(`  ${label}: ${r}${note}`);
    });
    lines.push("");
  });
  $("exportText").textContent = lines.join("\\n");
  $("exportDlg").showModal();
};
$("copyExport").onclick = () => {
  navigator.clipboard.writeText($("exportText").textContent).then(() => { $("copyExport").textContent = "Copied ✓"; });
};
$("exportClose").onclick = () => { $("copyExport").textContent = "Copy"; $("exportDlg").close(); };

// key reveal
$("revealBtn").onclick = () => $("revealDlg").showModal();
$("revealNo").onclick = () => $("revealDlg").close();
$("revealYes").onclick = () => {
  const body = $("revealBody"); body.innerHTML = "";
  DATA.chapters.forEach((ch) => {
    const h = document.createElement("div"); h.textContent = "## " + ch.id; h.style.margin = ".6rem 0 .2rem";
    h.style.fontWeight = "700"; body.appendChild(h);
    Object.entries(KEY.labels[ch.id] || {}).forEach(([label, name]) => {
      const d = document.createElement("div");
      const rv = (votes[ch.id] || {})[label];
      const rtxt = rv && rv.rating !== undefined ? ["worse", "same", "better", "MUCH better"][rv.rating + 1] : "";
      d.textContent = `${label} = ${name}` + (rtxt ? "  · your vote: " + rtxt : "");
      body.appendChild(d);
    });
  });
  $("revealYes").style.display = "none";
};

render();
</script>
</body>
</html>
"""


def generate_page(ballot_data: dict, key: dict) -> str:
    payload = json.dumps(ballot_data, ensure_ascii=False)
    key_payload = json.dumps({"labels": key}, ensure_ascii=False)
    # The key rides along for the post-vote reveal; it is only displayed behind a
    # confirmation dialog.
    return (TEMPLATE
            .replace("__DATA__", payload)
            .replace("__KEY__", key_payload)
            .replace("__SEED__", str(ballot_data["seed"])))


def write_ballot_page(out_path: str, ballot_data: dict, key: dict) -> None:
    page = generate_page(ballot_data, key)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"wrote {out_path} ({len(page) // 1024} KB) — open it in any browser")
