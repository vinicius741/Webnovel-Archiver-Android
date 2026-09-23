"""Scenario drivers: cold starts, warmed navigation cycles, and scroll interactions.

Discipline encoded here (see docs/architecture/performance-tracking.md):
- Cold arrival is always force-stop + `am start --es dev_start_screen …` (dev-launch-screen).
- uiautomator dumps happen only OUTSIDE measured windows (verification after the app-emitted
  terminal event); timing comes from in-app monotonic event timestamps.
- Fallback navigation never counts as success: the session's first screen_built route must
  equal the expected route, or the iteration fails.
- Missing prerequisites (no library story, no downloaded chapter) are reported as skipped.
"""

import json
import os
import re
import time

from . import adb
from . import aggregate
from .device import MAIN_ACTIVITY
from .session import event_by_name, parse_session_json, parse_frames_jsonl, iteration_status

COLD_SCENARIOS = {
    "cold_library": {"token": "library", "route": "library", "completeOn": "screen_built:library"},
    "cold_settings": {"token": "settings", "route": "settings", "completeOn": "screen_built:settings"},
    "cold_queue": {"token": "queue", "route": "queue", "completeOn": "screen_built:queue"},
    "cold_details": {"token": "details", "route": "details", "completeOn": "screen_built:details", "needsStory": True},
    "cold_reader": {"token": "reader", "route": "reader", "completeOn": "reader_content_presented", "needsStory": True},
}
DEFAULT_SUITES = ["cold_library", "cold_settings", "cold_queue", "cold_details", "cold_reader", "warmed_navigation", "reader_scroll", "library_scroll"]

MARKERS = {
    "library": ["Library"],
    "settings": ["Settings"],
    "queue": ["Downloads"],
    "details": [],
    "reader": ["Read aloud"],
}


def parse_am_start(text):
    result = {}
    status = re.search(r"Status:\s*(\w+)", text or "")
    total = re.search(r"TotalTime:\s*(\d+)", text or "")
    wait = re.search(r"WaitTime:\s*(\d+)", text or "")
    if status:
        result["launchStatus"] = status.group(1)
    if total:
        result["amTotalTimeMs"] = int(total.group(1))
    if wait:
        result["amWaitTimeMs"] = int(wait.group(1))
    return result


def cold_start_with_perf(serial, app_id, run_id, scenario, iteration, complete_on, dev_extras=(), max_ms=60000):
    """Force-stop + cold start carrying both dev_start_* and perf_* extras. am start -W gives
    the host-side launch wall-time (TotalTime), reported separately from in-app metrics."""
    adb.force_stop(serial, app_id)
    time.sleep(0.4)
    extras = [
        ("perf_session", "1"),
        ("perf_run_id", run_id),
        ("perf_scenario", scenario),
        ("perf_iteration", str(iteration)),
        ("perf_complete_on", complete_on),
        ("perf_max_ms", str(max_ms)),
    ] + list(dev_extras)
    cmd = f"am start -W -n {app_id}/{MAIN_ACTIVITY} " + "".join(f"--es {k} '{v}' " for k, v in extras)
    result = parse_am_start(adb.shell(serial, cmd, timeout=90, check=False))
    if app_id not in adb.top_activity(serial):
        # A foreign task (leftover probe apps on this emulator) resurfaced after the force-stop;
        # re-front the app. The am-start timing of this iteration is polluted — flag it.
        adb.launch_and_wait_foreground(serial, app_id, cmd, timeout_s=20)
        result["retriedForeground"] = True
    return result


def parse_polled_session(text):
    doc, _ = parse_session_json(text)
    return doc


def wait_for_session(serial, app_id, run_id, iteration, timeout_s, require_complete=True):
    """Polls cache/perf/session.json until it belongs to this launch and (optionally) completed."""

    def parse(text):
        doc = parse_polled_session(text)
        if doc is None:
            return None
        identity = doc.get("session") or {}
        if identity.get("runId") != run_id or identity.get("iteration") != str(iteration):
            return None
        names = [e.get("name") for e in doc.get("events", [])]
        if require_complete and "scenario_complete" not in names:
            return None
        if not names:
            return None
        return doc

    try:
        doc, _ = adb.poll_session_file(serial, app_id, "cache/perf/session.json", parse, timeout_s)
        return doc, None
    except TimeoutError as exc:
        return None, str(exc)


def wait_for_session_event(serial, app_id, run_id, event_name, route, timeout_s, after_nanos=0):
    """Polls until the session contains a specific event (optionally route-filtered) newer than
    [after_nanos]. The floor prevents an earlier cycle's matching event from falsely releasing
    a wait — e.g. cycle 2 must not be satisfied by cycle 1's reader_content_painted."""

    def parse(text):
        doc = parse_polled_session(text)
        if doc is None or (doc.get("session") or {}).get("runId") != run_id:
            return None
        for event in doc.get("events", []):
            if event.get("name") != event_name:
                continue
            if route is not None and (event.get("detail") or {}).get("route") != route:
                continue
            if (event.get("t") or 0) <= after_nanos:
                continue
            return doc
        return None

    doc, _ = adb.poll_session_file(serial, app_id, "cache/perf/session.json", parse, timeout_s)
    return doc


def latest_event_nanos(doc):
    events = (doc or {}).get("events") or []
    return max((e.get("t") or 0) for e in events) if events else 0


def refresh_session(serial, app_id, run_id, timeout_s=15):
    def parse(text):
        doc = parse_polled_session(text)
        if doc is not None and (doc.get("session") or {}).get("runId") == run_id:
            return doc
        return None

    doc, _ = adb.poll_session_file(serial, app_id, "cache/perf/session.json", parse, timeout_s)
    return doc


def pull_frames(serial, app_id):
    raw = adb.run_as_read(serial, app_id, "cache/perf/frames.jsonl")
    samples, dropped = parse_frames_jsonl(raw.decode("utf-8", "replace"))
    return samples, dropped


def verify_screen(serial, expected_markers, absent_markers=(), package=None):
    """One uiautomator dump OUTSIDE the timing window; returns (ok, xml, detail). A dump that
    belongs to a different package (a leftover foreign app resurfacing after force-stop) fails
    verification rather than feeding wrong-screen coordinates into the run."""
    xml = adb.uiautomator_dump(serial)
    if package is not None and f'package="{package}"' not in xml:
        return False, xml, {"foreignPackageDump": True}
    text = adb.unescape_xml(xml)
    missing = [m for m in expected_markers if m and m not in text]
    present_bad = [m for m in absent_markers if m and m in text]
    detail = {}
    if missing:
        detail["missingMarkers"] = missing
    if present_bad:
        detail["forbiddenMarkersPresent"] = present_bad
    return not missing and not present_bad, xml, detail


def wait_and_dump(serial, expected_markers, absent_markers=(), timeout_s=18, interval_s=1.5, package=None):
    """Polls dumps until the markers settle (API 36 dumps can lag a screen transition or return
    a stale hierarchy right after navigation). Returns (xml, None) or (None, detail)."""
    deadline = time.time() + timeout_s
    last_detail = {}
    while time.time() < deadline:
        ok, xml, detail = verify_screen(serial, expected_markers, absent_markers, package)
        if ok:
            return xml, None
        last_detail = detail
        time.sleep(interval_s)
    return None, last_detail


def verify_screen_settled(serial, expected_markers, absent_markers=(), package=None):
    """Verification-grade check with settle retries; keeps one dump artifact on success."""
    xml, detail = wait_and_dump(serial, expected_markers, absent_markers, timeout_s=9, package=package)
    return xml is not None, xml or "", detail or {}


def crash_evidence(serial, app_id):
    """Crash/ANR/StrictMode lines from the iteration's logcat window (cleared at start)."""
    raw = adb.shell(serial, "logcat -d -v time", timeout=60, check=False)
    interesting = [
        line
        for line in raw.splitlines()
        if re.search(r"FATAL EXCEPTION|ANR in|StrictMode policy violation", line) or ("AndroidRuntime" in line and app_id in line)
    ]
    crashes = [l for l in interesting if "FATAL EXCEPTION" in l or "ANR in" in l]
    strictmode = [l for l in interesting if "StrictMode policy violation" in l]
    return raw, {
        "crashLines": crashes[:20],
        "strictModeLines": strictmode[:40],
        "crashCount": len(crashes),
        "strictModeCount": len(strictmode),
    }


def first_route(doc):
    for event in doc.get("events", []):
        if event.get("name") == "screen_built":
            return (event.get("detail") or {}).get("route")
    return None


def skipped_scenario(name, reason):
    return {
        "status": "skipped",
        "skipReason": reason,
        "ok_iterations": 0,
        "total_iterations": 0,
        "metrics": {},
        "frames": {},
        "iterations": [],
    }


def write_iteration_artifacts(artifacts_dir, scenario, iteration, doc, xml, logcat_raw, meminfo, frames_text, evidence):
    directory = os.path.join(artifacts_dir, scenario)
    os.makedirs(directory, exist_ok=True)
    prefix = os.path.join(directory, str(iteration))
    if doc is not None:
        with open(prefix + ".session.json", "w", encoding="utf-8") as handle:
            json.dump(doc, handle, indent=2)
    if frames_text:
        with open(prefix + ".frames.jsonl", "w", encoding="utf-8") as handle:
            handle.write(frames_text)
    if xml:
        with open(prefix + ".verify.xml", "w", encoding="utf-8") as handle:
            handle.write(xml)
    if logcat_raw:
        with open(prefix + ".logcat.txt", "w", encoding="utf-8") as handle:
            handle.write(logcat_raw)
    if meminfo:
        with open(prefix + ".meminfo.txt", "w", encoding="utf-8") as handle:
            handle.write(meminfo)
    if evidence:
        with open(prefix + ".evidence.json", "w", encoding="utf-8") as handle:
            json.dump(evidence, handle, indent=2)


def strip_for_report(result):
    """Small per-iteration block; the full session document lives in the artifacts directory."""
    return {
        "iteration": result["iteration"],
        "status": result["status"],
        "error": result.get("error"),
        "verifyOk": (result.get("verify") or {}).get("ok"),
        "metrics": {k: round(v, 2) for k, v in (result.get("metrics") or {}).items() if isinstance(v, (int, float))},
        "launch": result.get("launch"),
        "evidence": result.get("evidence"),
    }


def run_cold_scenario(
    serial,
    app_id,
    run_id,
    scenario_name,
    config,
    iterations,
    reader_target=None,
    artifacts_dir=None,
    launch_timeout_s=45,
):
    """Drives one cold-start scenario for N iterations; returns the aggregate block."""
    if config.get("needsStory") and not reader_target:
        return skipped_scenario(scenario_name, "no library story with a downloaded chapter")
    dev_extras = [("dev_start_screen", config["token"])]
    markers = MARKERS.get(config["route"], [])
    absent = ["Preparing chapter"] if config["route"] == "reader" else []
    if config.get("needsStory"):
        dev_extras += [
            ("dev_start_story", reader_target["storyId"]),
            ("dev_start_chapter", reader_target["chapterId"]),
        ]
        if config["route"] == "details":
            markers = markers + [reader_target["storyTitle"][:20]]
    iteration_results = []
    for i in range(1, iterations + 1):
        adb.shell(serial, "logcat -c", check=False, timeout=30)
        launch = cold_start_with_perf(serial, app_id, run_id, scenario_name, i, config["completeOn"], dev_extras)
        doc, error = wait_for_session(serial, app_id, run_id, i, launch_timeout_s)
        result = {
            "iteration": i,
            "status": "failed",
            "error": None,
            "launch": launch,
            "session": None,
            "metrics": {},
            "frame_samples": None,
        }
        if doc is None:
            result["status"] = "execution_failure"
            result["error"] = f"session not captured: {error}"
        else:
            status, status_error = iteration_status(doc, {"runId": run_id, "iteration": str(i)})
            route = first_route(doc)
            if status == "ok" and route != config["route"]:
                status = "failed"
                status_error = f"fallback navigation: first screen_built route {route!r} != expected {config['route']!r}"
            result["status"] = status
            result["error"] = status_error
            result["session"] = doc
            if status == "ok":
                # Reader WebView onPageFinished lands after the presentation event that
                # completes the scenario. Wait for it before extracting the paint metric.
                try:
                    if config["route"] == "reader":
                        doc = wait_for_session_event(
                            serial, app_id, run_id, "reader_content_painted", None, 4,
                            after_nanos=(event_by_name(doc, "reader_content_presented") or {}).get("t", 0),
                        )
                    else:
                        doc = refresh_session(serial, app_id, run_id, timeout_s=6)
                    result["session"] = doc
                except TimeoutError:
                    pass
                result["metrics"] = aggregate.extract_cold_metrics(doc, config["route"])
                if launch.get("amTotalTimeMs") is not None:
                    result["metrics"]["am_total_ms"] = float(launch["amTotalTimeMs"])
                # Capture the first second of completed draws, then let the app's
                # one-second frame-file flush publish those samples before reading.
                # Verification dumps start only after this measured window.
                time.sleep(2.1)
            result["frame_samples"], _ = pull_frames(serial, app_id)
        verified, xml, verify_detail = verify_screen_settled(serial, markers, absent, package=app_id)
        result["verify"] = {"ok": verified, **verify_detail}
        if not verified and result["status"] == "ok":
            result["status"] = "execution_failure"
            result["error"] = f"screen verification failed: {verify_detail}"
        logcat_raw, evidence = crash_evidence(serial, app_id)
        result["evidence"] = evidence
        if evidence["crashCount"]:
            result["status"] = "execution_failure"
            result["error"] = (result["error"] or "") + " crash/ANR lines in logcat window"
        meminfo = adb.shell(serial, f"dumpsys meminfo {app_id}", check=False, timeout=60)
        if artifacts_dir:
            frames_text = read_frames_text(serial, app_id)
            write_iteration_artifacts(artifacts_dir, scenario_name, i, doc, xml, logcat_raw, meminfo, frames_text, evidence)
        iteration_results.append(result)
        time.sleep(0.5)
    return dict(
        aggregate.aggregate_cold_scenario(iteration_results, config["route"]),
        iterations=[strip_for_report(r) for r in iteration_results],
    )


def read_frames_text(serial, app_id):
    try:
        return adb.run_as_read(serial, app_id, "cache/perf/frames.jsonl").decode("utf-8", "replace")
    except (adb.DeviceError, TimeoutError):
        return ""


def run_warmed_navigation(serial, app_id, run_id, reader_target, cycles, artifacts_dir=None):
    """One process, K tap-navigation cycles: library card -> details -> chapter -> reader ->
    back -> details -> back -> library. Dumps run once at setup, outside the cycle windows."""
    if not reader_target:
        return skipped_scenario("warmed_navigation", "no library story with a downloaded chapter")
    adb.shell(serial, "logcat -c", check=False, timeout=30)
    launch = cold_start_with_perf(
        serial, app_id, run_id, "warmed_navigation", 1, "screen_built:library",
        dev_extras=[("dev_start_screen", "library")],
    )
    doc, error = wait_for_session(serial, app_id, run_id, 1, 45)
    if doc is None:
        failed = skipped_scenario("warmed_navigation", None)
        failed["status"] = "failed"
        failed["skipReason"] = f"session not captured: {error}"
        return failed
    coords = derive_navigation_coords(serial, app_id, run_id, reader_target)
    if coords is None:
        failed = skipped_scenario("warmed_navigation", "could not derive tap coordinates from UI dump")
        failed["status"] = "failed"
        return failed
    card, back = coords
    size = adb.shell(serial, "wm size", check=False)
    match = re.search(r"(\d+)x(\d+)", size)
    width, height = (int(match.group(1)), int(match.group(2))) if match else (1080, 2340)
    doc = refresh_session(serial, app_id, run_id)
    floor = latest_event_nanos(doc)
    for cycle in range(1, cycles + 1):
        # 1. library card -> details
        adb.tap(serial, *card)
        try:
            doc = wait_for_session_event(serial, app_id, run_id, "screen_built", "details", 20, after_nanos=floor)
            floor = latest_event_nanos(doc)
        except TimeoutError as exc:
            return warmed_failure(cycle, "tap_card", str(exc))
        # 2. reveal the chapter row (fresh details builds start scrolled to the top) and tap it
        chapter_row = reveal_chapter_row(serial, app_id, reader_target, back[1], width, height)
        if chapter_row is None:
            return warmed_failure(cycle, "reveal_chapter_row", "chapter row not visible after scrolling")
        floor = latest_event_nanos(refresh_session(serial, app_id, run_id))
        adb.tap(serial, *chapter_row)
        try:
            doc = wait_for_session_event(serial, app_id, run_id, "reader_content_painted", None, 35, after_nanos=floor)
            floor = latest_event_nanos(doc)
        except TimeoutError as exc:
            return warmed_failure(cycle, "tap_chapter", str(exc))
        # 3. reader -> details, 4. details -> library
        for step_name, route in (("back_to_details", "details"), ("back_to_library", "library")):
            adb.tap(serial, *back)
            try:
                doc = wait_for_session_event(serial, app_id, run_id, "screen_built", route, 20, after_nanos=floor)
                floor = latest_event_nanos(doc)
            except TimeoutError as exc:
                return warmed_failure(cycle, step_name, str(exc))
        time.sleep(0.3)
    time.sleep(1.6)
    try:
        final_doc = refresh_session(serial, app_id, run_id)
    except TimeoutError:
        final_doc = doc
    flat, legs = aggregate.extract_warmed_navigation(final_doc)
    result = aggregate.aggregate_warmed_scenario([{"status": "ok", "metrics_flat": flat, "legs": legs}])
    logcat_raw, evidence = crash_evidence(serial, app_id)
    result["launch"] = launch
    result["evidence"] = evidence
    result["cycles_requested"] = cycles
    result["legs_observed"] = len(legs)
    if artifacts_dir:
        write_iteration_artifacts(artifacts_dir, "warmed_navigation", 1, final_doc, "", logcat_raw, "", read_frames_text(serial, app_id), evidence)
    if evidence["crashCount"]:
        result["status"] = "failed"
        result["skipReason"] = "crash/ANR lines in logcat window"
    return result


def warmed_failure(
    cycle: int,
    step: str,
    reason: str,
):
    failed = skipped_scenario("warmed_navigation", f"cycle {cycle} step {step}: {reason}")
    failed["status"] = "failed"
    return failed


def reveal_chapter_row(serial, app_id, target, back_y, width, height):
    """Scrolls the details chapter list until the target chapter row is visible and returns its
    center. Fresh details builds always start at the top, so every cycle re-reveals the row;
    the dumps/swipes here sit between the details and reader timing anchors (event-based
    metrics are unaffected; warmed-nav frame stats include this interaction noise by design)."""
    for _ in range(8):
        xml = adb.uiautomator_dump(serial)
        if f'package="{app_id}"' in xml:
            candidates = adb.nodes_with_text(xml, text=target["chapterTitle"])
            below_header = [center for _, center in candidates if center[1] > back_y + 150]
            if below_header:
                return below_header[0]
        adb.swipe(serial, width // 2, int(height * 0.72), width // 2, int(height * 0.35), 400)
        time.sleep(0.8)
    return None


def derive_navigation_coords(serial, app_id, run_id, target):
    """Setup-phase coordinates: the library card (story title) and the app-bar Back button.
    The chapter row is re-derived per cycle (details rebuilds start scrolled to the top).
    Returns None when markers are missing — the scenario then reports failed, not bogus
    timings. All dumps happen outside the measured cycle windows."""
    card = None
    xml, _ = wait_and_dump(serial, [target["storyTitle"][:20]], package=app_id)
    if xml is not None:
        card = first_center(adb.nodes_with_text(xml, text=target["storyTitle"]))
    if card is None:
        return None
    floor = latest_event_nanos(refresh_session(serial, app_id, run_id))
    adb.tap(serial, *card)
    try:
        wait_for_session_event(serial, app_id, run_id, "screen_built", "details", 20, after_nanos=floor)
    except TimeoutError:
        return None
    xml2, _ = wait_and_dump(serial, [target["storyTitle"][:20]], package=app_id)
    back = first_center(adb.nodes_with_text(xml2 or "", content_desc="Back")) if xml2 else None
    if back is None:
        return None
    # Return to library so the measured cycles always start from a settled library screen.
    floor = latest_event_nanos(refresh_session(serial, app_id, run_id))
    adb.tap(serial, *back)
    try:
        wait_for_session_event(serial, app_id, run_id, "screen_built", "library", 20, after_nanos=floor)
    except TimeoutError:
        return None
    return card, back


def first_center(matches):
    return matches[0][1] if matches else None


def run_scroll_scenario(serial, app_id, run_id, name, start_token, swipes, reader_target=None, artifacts_dir=None, story_count=None):
    if start_token == "reader" and not reader_target:
        return skipped_scenario(name, "no library story with a downloaded chapter")
    if start_token == "library" and (story_count or 0) < 4:
        return skipped_scenario(name, f"library too small to scroll ({story_count or 0} stories)")
    dev_extras = [("dev_start_screen", start_token)]
    if start_token == "reader":
        dev_extras += [
            ("dev_start_story", reader_target["storyId"]),
            ("dev_start_chapter", reader_target["chapterId"]),
        ]
    complete_on = "reader_content_presented" if start_token == "reader" else f"screen_built:{start_token}"
    adb.shell(serial, "logcat -c", check=False, timeout=30)
    launch = cold_start_with_perf(serial, app_id, run_id, name, 1, complete_on, dev_extras)
    doc, error = wait_for_session(serial, app_id, run_id, 1, 45)
    if doc is None:
        failed = skipped_scenario(name, f"session not captured: {error}")
        failed["status"] = "failed"
        return failed
    if start_token == "reader":
        ok, _, detail = verify_screen_settled(serial, ["Read aloud"], absent_markers=["Preparing chapter"], package=app_id)
        if not ok:
            ok, _, detail = verify_screen_settled(serial, ["Next chapter"], absent_markers=["Preparing chapter"], package=app_id)
        if not ok:
            failed = skipped_scenario(name, "reader never settled before scrolling")
            failed["status"] = "failed"
            failed["skipReason"] = str(detail)
            return failed
    size = adb.shell(serial, "wm size", check=False)
    match = re.search(r"(\d+)x(\d+)", size)
    width, height = (int(match.group(1)), int(match.group(2))) if match else (1080, 2340)
    for _ in range(swipes):
        adb.swipe(serial, width // 2, int(height * 0.7), width // 2, int(height * 0.3), 350)
        time.sleep(0.9)
    for _ in range(swipes):
        adb.swipe(serial, width // 2, int(height * 0.35), width // 2, int(height * 0.75), 350)
        time.sleep(0.9)
    time.sleep(1.6)
    try:
        final_doc = refresh_session(serial, app_id, run_id)
    except TimeoutError:
        final_doc = doc
    samples, _ = pull_frames(serial, app_id)
    scroll = aggregate.extract_scroll_metrics(final_doc, samples)
    logcat_raw, evidence = crash_evidence(serial, app_id)
    status = "ok" if scroll["interaction_count"] >= swipes else "incomplete"
    result = {
        "status": status,
        "launch": launch,
        "swipes_requested": swipes,
        "interactions_recorded": scroll["interaction_count"],
        "frames": scroll["frame_stats"],
        "frames_by_tag": scroll["tag_breakdown"],
        "evidence": evidence,
        "metrics": {},
        "iterations": [],
    }
    if status != "ok":
        result["skipReason"] = f"only {scroll['interaction_count']}/{swipes} interactions recorded"
    if evidence["crashCount"]:
        result["status"] = "failed"
        result["skipReason"] = "crash/ANR lines in logcat window"
    if artifacts_dir:
        write_iteration_artifacts(artifacts_dir, name, 1, final_doc, "", logcat_raw, "", read_frames_text(serial, app_id), evidence)
    return result
