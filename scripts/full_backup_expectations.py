#!/usr/bin/env python3
"""Print the expected post-restore values for a webnovel_full_backup_*.zip.

Companion to .agents/skills/restore-library-backup (full-backup path): the app's
dev_library_report after a restore must match these values. Expectations mirror
FullBackupRestorer: library ids come from the manifest in order, and a chapter is
downloaded iff it is in the chapter index. Version 1 manifests list that index in
chapterFiles; version 2 derives it from the library (downloaded chapters minus the
chapters listed in missingContent).

Usage:
  python3 scripts/full_backup_expectations.py <webnovel_full_backup_*.zip>
"""
import argparse
import hashlib
import json
import sys
import zipfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("zip", help="path to webnovel_full_backup_*.zip")
    args = parser.parse_args()

    with zipfile.ZipFile(args.zip) as z:
        manifest = json.loads(z.read("manifest.json"))

    fmt = manifest.get("format")
    if fmt != "webnovel-archiver-full-backup":
        print(f"error: not a full backup (format={fmt!r})", file=sys.stderr)
        return 1

    library = manifest["library"]
    ids = [s["id"] for s in library]
    if len(set(ids)) != len(ids):
        print("error: duplicate story ids in manifest", file=sys.stderr)
        return 1

    version = manifest.get("version")
    if version == 1:
        chapter_files = manifest.get("chapterFiles") or []
    else:
        missing = {
            (m.get("storyId"), m.get("chapterId"))
            for m in manifest.get("missingContent") or []
            if m.get("kind") == "chapter"
        }
        chapter_files = [
            {"storyId": s["id"], "chapterId": c["id"]}
            for s in library
            for c in s.get("chapters") or []
            if c.get("downloaded") and (s["id"], c["id"]) not in missing
        ]
    known = set(ids)
    orphaned = [c["storyId"] for c in chapter_files if c["storyId"] not in known]
    tabs = (manifest.get("config") or {}).get("tabs") or []

    print(f"manifestVersion:          {version}")
    print(f"librarySize:              {len(library)}")
    print(f"storyIdsSha256:           {hashlib.sha256('\n'.join(ids).encode()).hexdigest()}")
    print(f"downloadedChapters:       {len(chapter_files)}  (chapter index entries)")
    print(f"metricFiles:              {len(manifest.get('metricFiles') or [])}")
    print(f"tabs:                     {[t.get('name') for t in sorted(tabs, key=lambda t: t.get('order', 0))]}")
    print(f"settings restored:        {sorted((manifest.get('config') or {}).keys())}")
    if orphaned:
        print(f"warning: chapterFiles referencing unknown story ids: {sorted(set(orphaned))}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
