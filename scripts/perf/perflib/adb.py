"""ADB plumbing with the repo's fail-closed emulator-only serial rules.

The only unqualified adb call allowed anywhere is `adb devices -l` for discovery; every
device-targeting command goes through run() with a resolved emulator-* serial, mirroring
scripts/redeploy.sh and AGENTS.md device safety.
"""

import html
import re
import subprocess
import time

ADB = "adb"
SERIAL_ENV = "EMULATOR_SERIAL"


def unescape_xml(text):
    """uiautomator dumps XML-escape text attributes (&, <, >, quotes); unescape for matching."""
    return html.unescape(text or "")


class DeviceError(RuntimeError):
    pass


def list_devices(adb=ADB):
    out = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True, check=True).stdout
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def resolve_serial(explicit=None, adb=ADB, env=None):
    """Explicit serial wins; otherwise exactly one healthy emulator, else fail closed."""
    env = env if env is not None else {}
    chosen = explicit or env.get(SERIAL_ENV) or None
    devices = list_devices(adb)
    emulators = [d for d in devices if d.startswith("emulator-")]
    if chosen:
        if not chosen.startswith("emulator-"):
            raise DeviceError(f"refusing non-emulator serial {chosen!r}")
        if chosen not in emulators:
            raise DeviceError(f"emulator {chosen} is not connected/healthy (visible: {devices or 'none'})")
        return chosen
    if not emulators:
        raise DeviceError("no running emulator found; start webnovel_api36 (see .agents/skills/emulator-qa)")
    if len(emulators) > 1:
        raise DeviceError(f"multiple emulators running {emulators}; pass --serial or set EMULATOR_SERIAL")
    return emulators[0]


def run(serial, *args, adb=ADB, timeout=60, check=True, binary=False):
    cmd = [adb, "-s", serial, *args]
    result = subprocess.run(cmd, capture_output=True, text=not binary, timeout=timeout)
    if check and result.returncode != 0:
        err = result.stderr if isinstance(result.stderr, str) else result.stderr.decode("utf-8", "replace")
        raise DeviceError(f"adb {' '.join(args[:3])} failed: {err.strip()[:400]}")
    return result.stdout if not binary else result.stdout


def shell(serial, command, **kwargs):
    return run(serial, "shell", command, **kwargs).strip()


def wait_boot_complete(serial, timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if shell(serial, "getprop sys.boot_completed", check=False, timeout=15) == "1":
                return
        except (subprocess.TimeoutExpired, DeviceError):
            pass
        time.sleep(2)
    raise DeviceError(f"emulator {serial} did not finish booting within {timeout_s}s")


def run_as_read(serial, package, rel_path, timeout=30):
    return run(serial, "exec-out", "run-as", package, "cat", rel_path, timeout=timeout, binary=True)


def poll_session_file(serial, package, rel_path, predicate, timeout_s, interval_s=0.4):
    """Polls a run-as-readable file until predicate(text) returns non-None. Returns
    (value, waited_s) or raises TimeoutError."""
    deadline = time.time() + timeout_s
    last_error = None
    while time.time() < deadline:
        try:
            text = run_as_read(serial, package, rel_path).decode("utf-8", "replace")
            value = predicate(text)
            if value is not None:
                return value, timeout_s - (deadline - time.time())
        except DeviceError as exc:
            last_error = exc
        time.sleep(interval_s)
    raise TimeoutError(f"timed out after {timeout_s}s polling {rel_path}" + (f"; last error: {last_error}" if last_error else ""))


def force_stop(serial, package):
    shell(serial, f"am force-stop {package}", check=False)


def top_activity(serial):
    out = run(serial, "shell", "dumpsys", "activity", "activities", timeout=30, check=False)
    for line in (out or "").splitlines():
        if "topResumedActivity" in line:
            return line.strip()
    return ""


def launch_and_wait_foreground(
    serial,
    package,
    start_command,
    timeout_s=25,
    attempts=3,
):
    """Runs the am start command and waits until [package] holds the resumed activity.
    Retries when a foreign task (e.g. a leftover probe app) resurfaces after a force-stop."""
    for attempt in range(attempts):
        shell(serial, start_command, timeout=90, check=False)
        deadline = time.time() + timeout_s / attempts
        while time.time() < deadline:
            if package in top_activity(serial):
                return True
            time.sleep(0.7)
    return False


def uiautomator_dump(serial, timeout=30):
    """Returns the dumped hierarchy XML text. The file is removed first so a silently failed
    dump can never return a stale previous hierarchy (observed: identical bytes for 8+s)."""
    for attempt in range(3):
        shell(serial, "rm -f /sdcard/perf_ui.xml", timeout=15, check=False)
        shell(serial, "uiautomator dump /sdcard/perf_ui.xml", timeout=timeout, check=False)
        try:
            xml = run(serial, "exec-out", "cat", "/sdcard/perf_ui.xml", timeout=timeout, binary=True)
        except DeviceError:
            xml = b""
        text = xml.decode("utf-8", "replace")
        if "<hierarchy" in text or "<bounds" in text:
            return text
        time.sleep(0.8 + attempt)
    return ""


def parse_bounds(bounds_text):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_text or "")
    if not match:
        return None
    x0, y0, x1, y1 = (int(g) for g in match.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2


def nodes_with_text(xml, text=None, content_desc=None):
    """(text_or_desc, bounds_center) for nodes matching exact text or content-desc."""
    results = []
    if not xml:
        return results
    for match in re.finditer(r"<node[^>]*>", xml):
        tag = match.group(0)
        text_attr = re.search(r' text="([^"]*)"', tag)
        desc_attr = re.search(r' content-desc="([^"]*)"', tag)
        bounds_attr = re.search(r' bounds="([^"]*)"', tag)
        if bounds_attr is None:
            continue
        t = unescape_xml(text_attr.group(1)) if text_attr else ""
        d = unescape_xml(desc_attr.group(1)) if desc_attr else ""
        if text is not None and t == text:
            center = parse_bounds(bounds_attr.group(1))
            if center:
                results.append((t, center))
        if content_desc is not None and d == content_desc:
            center = parse_bounds(bounds_attr.group(1))
            if center:
                results.append((d, center))
    return results


def tap(serial, x, y):
    shell(serial, f"input tap {x} {y}", timeout=20)


def swipe(serial, x0, y0, x1, y1, duration_ms=300):
    shell(serial, f"input swipe {x0} {y0} {x1} {y1} {duration_ms}", timeout=20)
