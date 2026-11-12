#!/usr/bin/env python3
"""Build shared/library/data.js (+ resized covers, chapter text) from a pulled app data root.

Usage: python3 build_data.py <pulled webnovel_archiver dir> <chapter html dir>
The output under shared/library/ holds the owner's real library and is git-ignored.
"""
import glob
import html
import json
import os
import re
import sys

from PIL import Image

SRC, TEXT_SRC = sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "shared", "library")
os.makedirs(os.path.join(OUT, "covers"), exist_ok=True)


def load(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    return d.get("payload", d) if isinstance(d, dict) else d


SOURCES = {
    "royal_road": "RoyalRoad", "scribble_hub": "Scribble Hub", "spacebattles": "SpaceBattles", "space_battles": "SpaceBattles",
    "sufficient_velocity": "Sufficient Velocity", "fanfiction": "FanFiction.net", "fanfiction_net": "FanFiction.net",
    "ao3": "Archive of Our Own", "archive_of_our_own": "Archive of Our Own",
}

index = load(os.path.join(SRC, "library_index.json"))
tabs = load(os.path.join(SRC, "tabs.json"))
positions = load(os.path.join(SRC, "tts_positions.json"))
session = load(os.path.join(SRC, "tts_session.json"))
tts = load(os.path.join(SRC, "tts_settings.json"))
prefs = load(os.path.join(SRC, "display_preferences.json"))
regex_rules = load(os.path.join(SRC, "regex_cleanup_rules.json"))
sentences = load(os.path.join(SRC, "sentence_removal.json"))

# Resize covers
covers = set()
cover_files = glob.glob(os.path.join(SRC, "covers", "*")) + glob.glob(os.path.join(SRC, "remote_covers", "*.img"))
for f in cover_files:
    sid = os.path.splitext(os.path.basename(f))[0]
    if sid in covers:
        continue
    try:
        im = Image.open(f).convert("RGB")
    except Exception:  # noqa: BLE001 - skip HTML error pages and unreadable images
        continue
    im.thumbnail((360, 540))
    im.save(os.path.join(OUT, "covers", sid + ".jpg"), quality=78, optimize=True)
    covers.add(sid)

stories = []
FEATURED_MAX = 24
featured_ids = []
for sid in index:
    path = os.path.join(SRC, "stories", sid + ".json")
    if not os.path.exists(path):
        continue
    p = load(path)
    meta = p.get("sourceMetadata") or {}
    pat = p.get("patreonStats") or {}
    chapters = p.get("chapters") or []
    bookmark = None
    pos = positions.get(sid) if isinstance(positions, dict) else None
    if pos:
        for i, c in enumerate(chapters):
            if c.get("id") == pos.get("chapterId"):
                bookmark = i
                break
    last_pub = [c.get("publishedAt") for c in chapters if c.get("publishedAt")]
    s = {
        "id": sid,
        "title": p.get("title", ""),
        "author": p.get("author", ""),
        "source": SOURCES.get(p.get("sourceId"), p.get("sourceId")),
        "pub": p.get("publicationStatus", "unknown"),
        "status": p.get("status"),
        "score": (p.get("score") or "").split(" ")[0] or None,
        "tags": [t for t in (p.get("tags") or []) if not t.startswith("⚠")][:10],
        "warnings": meta.get("contentWarnings") or [],
        "type": meta.get("sourceType"),
        "total": p.get("totalChapters") or len(chapters),
        "downloaded": p.get("downloadedChapters") or 0,
        "tab": p.get("tabId"),
        "added": p.get("dateAdded"),
        "updated": p.get("lastUpdated"),
        "lastSync": p.get("lastChapterSyncAt"),
        "desc": (p.get("description") or "")[:1400],
        "cover": sid in covers,
        "bookmark": bookmark,
        "patreon": {"members": pat.get("paidMembers"), "usd": round((pat.get("exactMonthlyUsdCents") or 0) / 100)} if pat else None,
        "recentPubs": sorted(last_pub)[-8:],
    }
    stories.append(s)
    if s["cover"] and len(chapters) > 0 and len(featured_ids) < FEATURED_MAX:
        featured_ids.append(sid)

# Always feature the stories we have chapter text for and the TTS session story
text_ids = sorted({os.path.basename(f).split("__")[0] for f in glob.glob(os.path.join(TEXT_SRC, "*.html"))})
for sid in [session.get("storyId")] + text_ids:
    if sid and sid not in featured_ids:
        featured_ids.insert(0, sid)

chapter_lists = {}
metrics = {}
for sid in featured_ids:
    p = load(os.path.join(SRC, "stories", sid + ".json"))
    chapter_lists[sid] = [
        {"id": c.get("id"), "t": c.get("title"), "d": bool(c.get("downloaded")), "at": c.get("publishedAt")}
        for c in (p.get("chapters") or [])
    ][:600]
    mpath = os.path.join(SRC, "metrics", sid + ".json")
    if os.path.exists(mpath):
        snaps = load(mpath).get("snapshots", [])
        metrics[sid] = [
            {"at": s.get("capturedAt"), "score": s.get("score"), "total": s.get("totalChapters"),
             "m": {m.get("kind", m.get("name", "?")): m.get("value") for m in (s.get("metrics") or []) if isinstance(m, dict)}}
            for s in snaps
        ][-60:]

# Chapter text: plain paragraphs
texts = {}
for f in sorted(glob.glob(os.path.join(TEXT_SRC, "*.html"))):
    sid, rest = os.path.basename(f).split("__", 1)
    cid = rest.split("_", 1)[1].rsplit(".", 1)[0]
    raw = open(f, encoding="utf-8", errors="ignore").read()
    raw = re.sub(r"<img[^>]*>", "", raw)
    paras = [html.unescape(re.sub(r"<[^>]+>", "", m)).strip() for m in re.findall(r"<p[^>]*>(.*?)</p>", raw, re.S)]
    paras = [x for x in paras if x][:80]
    if paras:
        texts.setdefault(sid, {})[cid] = paras

data = {
    "generatedAt": prefs.get("continueListeningDismissedThrough"),
    "tabs": sorted(tabs, key=lambda t: t.get("order", 0)),
    "stories": stories,
    "chapters": chapter_lists,
    "metrics": metrics,
    "text": texts,
    "session": session,
    "tts": {k: tts.get(k) for k in ["rate", "pitch", "voiceIdentifier", "enginePackage", "sentencePauseMs", "paragraphPauseMs", "scenePauseMs", "phrasePauseMs", "announceChapters", "contextReplay", "profiles"]},
    "prefs": prefs,
    "cleanup": {"regex": regex_rules, "sentences": sentences[:40]},
    "stats": {"stories": len(stories), "chapters": sum(s["total"] for s in stories), "downloaded": sum(s["downloaded"] for s in stories)},
}
with open(os.path.join(OUT, "data.js"), "w", encoding="utf-8") as f:
    f.write("/* Generated by tools/build_data.py from the owner's library. Do not commit. */\n")
    f.write("window.WNA_DATA = ")
    json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    f.write(";\n")
print("stories", len(stories), "covers", len(covers), "featured", len(featured_ids), "texts", {k: len(v) for k, v in texts.items()},
      "size KB", os.path.getsize(os.path.join(OUT, "data.js")) // 1024)
