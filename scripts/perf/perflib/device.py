"""Environment capture: git revision, APK identity, device configuration, dataset fingerprint.

The dataset fingerprint reuses the app's own dev_library_report probe (storyIdsSha256) so a
baseline is only comparable against runs over the exact same library contents/order.
"""

import hashlib
import json
import os
import re
import subprocess
import time

from . import adb

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DEBUG_APP_ID = "com.vinicius741.webnovelarchiver.nativeapp.debug"
INSTRUMENTATION_APP_ID = "com.vinicius741.webnovelarchiver.nativeapp.instrumentation"
MAIN_ACTIVITY = "com.vinicius741.webnovelarchiver.app.MainActivity"
APK_BY_VARIANT = {
    "debug": os.path.join(REPO_ROOT, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
    "instrumentation": os.path.join(REPO_ROOT, "android", "app", "build", "outputs", "apk", "instrumentation", "app-instrumentation.apk"),
}
VARIANTS = {
    "debug": DEBUG_APP_ID,
    "instrumentation": INSTRUMENTATION_APP_ID,
}


class EnvironmentError_(RuntimeError):
    pass


def git_metadata():
    def git(*args):
        return subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False).stdout.strip()

    dirty = git("status", "--porcelain").splitlines()
    return {
        "revision": git("rev-parse", "HEAD") or "unknown",
        "branch": git("rev-parse", "--abbrev-ref", "HEAD") or "unknown",
        "dirty": bool(dirty),
        "dirtyFileCount": len(dirty),
        # First 20 changed paths only — never contents.
        "dirtyFilesSample": [line[3:] for line in dirty[:20]],
    }


def apk_identity(variant, apk_path=None):
    path = apk_path or APK_BY_VARIANT[variant]
    if not os.path.isfile(path):
        raise EnvironmentError_(
            f"APK not found at {path}; build it first (android/gradlew -p android :app:assemble{'Debug' if variant == 'debug' else 'Instrumentation'}) or pass --apk"
        )
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return {
        "path": path,
        "sha256": digest.hexdigest(),
        "sizeBytes": os.path.getsize(path),
        "mtime": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(os.path.getmtime(path))),
        "variant": variant,
    }


def device_info(serial):
    def prop(name):
        return adb.shell(serial, f"getprop {name}", check=False)

    return {
        "serial": serial,
        "model": prop("ro.product.model"),
        "device": prop("ro.product.device"),
        "sdkInt": prop("ro.build.version.sdk"),
        "osRelease": prop("ro.build.version.release"),
        "buildFingerprint": prop("ro.build.fingerprint"),
        "abi": prop("ro.product.cpu.abi"),
        "screenSize": adb.shell(serial, "wm size", check=False).replace("\n", " "),
        "density": adb.shell(serial, "wm density", check=False).replace("\n", " "),
        "appId": None,
    }


def app_installed(serial, app_id):
    return adb.shell(serial, f"pm path {app_id}", check=False, timeout=30) != ""


def probe_dataset(serial, app_id, timeout_s=60):
    """Cold-starts the app once with dev_library_report to fingerprint the library.

    Non-destructive: the probe writes only the report file inside the app's own cache.
    Returns the parsed report dict, or raises TimeoutError.
    """
    adb.force_stop(serial, app_id)
    # The app keeps this report in cache across launches. Remove it before probing so a
    # failed hydration cannot silently reuse an older library fingerprint.
    adb.run(serial, "shell", "run-as", app_id, "rm", "-f", "cache/dev_library_report.json")
    if not adb.launch_and_wait_foreground(
        serial, app_id, f"am start -n {app_id}/{MAIN_ACTIVITY} --es dev_start_screen library --es dev_library_report 1"
    ):
        raise EnvironmentError_("library probe did not reach the app foreground")

    def parse(text):
        try:
            doc = json.loads(text)
        except ValueError:
            return None
        return doc if isinstance(doc, dict) and "storyIdsSha256" in doc else None

    report, _ = adb.poll_session_file(serial, app_id, "cache/dev_library_report.json", parse, timeout_s)
    adb.force_stop(serial, app_id)
    time.sleep(1.0)
    return report


def dataset_from_probe(probe, app_id):
    return {
        "appId": app_id,
        "storyIdsSha256": probe.get("storyIdsSha256"),
        "storyCount": probe.get("librarySize"),
        "downloadedChapterEntries": probe.get("downloadedChapterEntries"),
        "totalChapterEntries": probe.get("totalChapterEntries"),
        "storageIssues": len(probe.get("storageIssues") or []),
    }


def pick_reader_target(serial, app_id):
    """Chooses the deterministic reader/details target: a story card visible on the Library
    screen that has downloaded chapters, preferring first-chapter-downloaded (row 1 in the
    Details list). Falls back to any downloaded chapter on a visible story."""
    visible = visible_library_titles(serial, app_id)
    if not visible:
        return None
    index_text = adb.run_as_read(serial, app_id, "files/webnovel_archiver/library_index.json").decode("utf-8", "replace")
    try:
        index = json.loads(index_text)
        story_ids = index.get("payload") if isinstance(index, dict) else index
        if not isinstance(story_ids, list):
            return None
    except ValueError:
        return None
    fallback = None
    for story_id in story_ids:
        if not isinstance(story_id, str) or not story_id:
            continue
        story = read_story_payload(serial, app_id, story_id)
        if story is None:
            continue
        title = story.get("title") or story_id
        if title not in visible:
            continue
        chapters = story.get("chapters") or []
        downloaded = [c for c in chapters if c.get("downloaded")]
        if not downloaded:
            continue
        first_is_downloaded = bool(chapters) and bool(chapters[0].get("downloaded"))
        chosen = chapters[0] if first_is_downloaded else downloaded[0]
        target = {
            "storyId": story_id,
            "storyTitle": title,
            "chapterId": chosen.get("id"),
            "chapterTitle": chosen.get("title") or "",
            "downloadedChapters": len(downloaded),
            "totalChapters": len(chapters),
        }
        if first_is_downloaded:
            return target
        if fallback is None:
            fallback = target
    return fallback


def read_story_payload(serial, app_id, story_id):
    try:
        story_text = adb.run_as_read(serial, app_id, f"files/webnovel_archiver/stories/{safe_name(story_id)}.json").decode("utf-8", "replace")
        payload = json.loads(story_text).get("payload") or {}
        return payload if isinstance(payload, dict) else None
    except (ValueError, adb.DeviceError):
        return None


def visible_library_titles(serial, app_id, timeout_s=90):
    """Cold-starts the library once and returns the set of card-title texts visible in the dump
    (the deterministic navigation target must be tappable without scrolling). Long timeout:
    after heavy emulator use the accessibility snapshot can lag the rendered screen by tens of
    seconds while returning structurally valid but shallow dumps."""
    adb.force_stop(serial, app_id)
    if not adb.launch_and_wait_foreground(serial, app_id, f"am start -n {app_id}/{MAIN_ACTIVITY} --es dev_start_screen library"):
        return set()
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        time.sleep(2.0)
        xml = adb.uiautomator_dump(serial)
        if f'package="{app_id}"' not in xml:
            continue
        titles = {adb.unescape_xml(m.group(1)) for m in re.finditer(r' text="([^"]{8,120})"', xml)}
        if len(titles) >= 4:
            adb.force_stop(serial, app_id)
            time.sleep(0.8)
            return titles
    adb.force_stop(serial, app_id)
    return set()


def safe_name(story_id):
    return re.sub(r"[^A-Za-z0-9._-]", "_", story_id)[:120]


def storage_fingerprint(serial, app_id):
    """File-name/size listing of the persisted library root — detects incidental writes."""
    root = adb.shell(serial, f"run-as {app_id} ls -l files/webnovel_archiver/", check=False, timeout=30)
    stories = adb.shell(serial, f"run-as {app_id} ls -l files/webnovel_archiver/stories/", check=False, timeout=60)
    settings = adb.shell(serial, f"run-as {app_id} ls -l files/webnovel_archiver/library_index.json", check=False, timeout=30)
    return {"root": root.strip(), "stories": stories.strip(), "library_index": settings.strip()}


def diff_fingerprints(before, after):
    """Human-readable list of persisted-state changes between two storage fingerprints."""
    changes = []
    for key in ("root", "stories", "library_index"):
        b = (before or {}).get(key) or ""
        a = (after or {}).get(key) or ""
        if b != a:
            old_lines = set(b.splitlines())
            new_lines = set(a.splitlines())
            added = sorted(new_lines - old_lines)
            removed = sorted(old_lines - new_lines)
            for line in removed[:10]:
                changes.append(f"{key}: removed/changed {line}")
            for line in added[:10]:
                changes.append(f"{key}: added/changed {line}")
            if len(added) + len(removed) > 20:
                changes.append(f"{key}: … {len(added) + len(removed) - 20} more changes")
    return changes
