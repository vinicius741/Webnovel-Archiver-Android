#!/usr/bin/env python3
"""Convert a webnovel_backup_*.json (v1/v2) export into the app's on-disk storage layout.

Used by .agents/skills/restore-library-backup to REPLACE the debug app's emulator
library without the in-app SAF picker (which only merges).

Replicates exactly what the app's JsonBackupImporter does when importing stories into
an EMPTY library (every story takes the "new story" path):
  - BackupMergePlanning.scrubPortableIncomingStory: chapters lose content/filePath/
    downloadedAt and are marked not-downloaded; epubPath/epubPaths/epubStale nulled.
  - AppStorage.saveStoryOnly: totalChapters = chapters.size, downloadedChapters = 0.
Then wraps each document in the DurableJson envelope {"schemaVersion":1,...}. Gson
omits null fields, so null-valued keys are dropped to match app-written files.

Usage:
  python3 scripts/backup_to_storage.py <backup.json> <staging-dir> [--app-version X]
"""
import argparse
import hashlib
import json
import os
import re
import sys

SAFE_NAME_RE = re.compile(r"[^A-Za-z0-9._-]")


def safe_name(value: str) -> str:
    """AppStorage.safeName: non [A-Za-z0-9._-] -> '_', truncated to 120 chars."""
    return SAFE_NAME_RE.sub("_", value)[:120]


def strip_nulls(obj):
    if isinstance(obj, dict):
        return {k: strip_nulls(v) for k, v in obj.items() if v is not None}
    if isinstance(obj, list):
        return [strip_nulls(v) for v in obj]
    return obj


def envelope(payload, app_version):
    return {"schemaVersion": 1, "appVersion": app_version, "payload": payload}


def scrub_story(story: dict) -> dict:
    chapters = []
    for ch in story.get("chapters") or []:
        scrubbed = dict(ch)
        scrubbed.pop("content", None)
        scrubbed.pop("filePath", None)
        scrubbed.pop("downloadedAt", None)
        scrubbed["downloaded"] = False
        chapters.append(scrubbed)
    out = dict(story)
    out["chapters"] = chapters
    out["totalChapters"] = len(chapters)
    out["downloadedChapters"] = 0
    out.pop("epubPath", None)
    out.pop("epubPaths", None)
    out.pop("epubStale", None)
    return strip_nulls(out)


def write_json(path: str, doc) -> None:
    with open(path, "w") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("backup", help="path to webnovel_backup_*.json")
    parser.add_argument("staging", help="output staging directory (created; must not be the app data dir)")
    parser.add_argument("--app-version", default="1.0.2-native", help="appVersion recorded in envelopes (informational)")
    args = parser.parse_args()

    with open(args.backup) as f:
        backup = json.load(f)
    version = backup.get("version")
    if version not in (1, 2):
        print(f"error: unexpected backup version {version!r} (expected 1 or 2)", file=sys.stderr)
        return 1
    library = backup.get("library")
    if not isinstance(library, list) or not library:
        print("error: backup has no library list", file=sys.stderr)
        return 1

    ids = [s.get("id") for s in library]
    if not all(isinstance(i, str) and i.strip() for i in ids):
        print("error: blank or non-string story id in backup", file=sys.stderr)
        return 1
    if len(set(ids)) != len(ids):
        dupes = sorted({i for i in ids if ids.count(i) > 1})
        print(f"error: duplicate story ids in backup: {dupes}", file=sys.stderr)
        return 1
    names = [safe_name(i) for i in ids]
    if len(set(names)) != len(names):
        print("error: safeName collision between distinct story ids; refusing to write", file=sys.stderr)
        return 1

    stories_dir = os.path.join(args.staging, "stories")
    os.makedirs(stories_dir, exist_ok=True)
    for story in library:
        write_json(os.path.join(stories_dir, f"{safe_name(story['id'])}.json"), envelope(scrub_story(story), args.app_version))
    write_json(os.path.join(args.staging, "library_index.json"), envelope(ids, args.app_version))

    tabs = sorted(backup.get("tabs") or [], key=lambda t: t.get("order", 0))
    write_json(os.path.join(args.staging, "tabs.json"), envelope(strip_nulls(tabs), args.app_version))

    tab_ids = {t.get("id") for t in tabs}
    orphan_tabs = sorted({s.get("tabId") for s in library if s.get("tabId")} - tab_ids)
    archived = sum(1 for s in library if s.get("isArchived"))
    # Same computation as DevLibraryReportPlanning.storyIdsSha256 (sha256 of ids joined with
    # "\n", UTF-8): a matching storyIdsSha256 in the app's dev_library_report proves the app
    # hydrated exactly these stories in exactly this order.
    ids_hash = hashlib.sha256("\n".join(ids).encode("utf-8")).hexdigest()
    print(f"stories written: {len(library)} ({archived} archived)")
    print(f"tabs written:    {len(tabs)} ({[t.get('name') for t in tabs]})")
    print(f"chapters total:  {sum(len(s.get('chapters') or []) for s in library)} (none downloaded — JSON backups carry metadata only)")
    print(f"storyIdsSha256:  {ids_hash}")
    if orphan_tabs:
        print(f"warning: stories reference tab ids absent from tabs list: {orphan_tabs}", file=sys.stderr)
    print(f"staging ready:   {args.staging}/ (library_index.json, tabs.json, stories/)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
