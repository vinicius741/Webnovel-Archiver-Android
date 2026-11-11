#!/usr/bin/env python3
"""Emulator-only offline scenarios. Uses production app engines in disposable storage."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent / "perf"))
from perflib import adb

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "android/app/src/debug/assets/simulation/catalog.json"
PACKAGE = "com.vinicius741.webnovelarchiver.nativeapp.debug"
ACTIVITY = "com.vinicius741.webnovelarchiver.app.MainActivity"
CONTROL = "com.vinicius741.webnovelarchiver.simulation.SimulationControlActivity"
BASE = "files/dev_simulation"
IDENTIFIER = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}\Z")
SCREENS = {"library", "queue", "settings", "notifications", "aisettings", "aicontrols", "cleanup", "patreon", "reader", "details", "addstory"}


def identifier(value):
    if not IDENTIFIER.fullmatch(value):
        raise argparse.ArgumentTypeError("use 1-80 letters, numbers, underscores or hyphens")
    return value


def catalog():
    data = json.loads(CATALOG.read_text())
    if data["version"] != 1:
        raise ValueError("Unsupported catalog")
    return {item["id"]: item for item in data["scenarios"]}


def target(serial=None):
    selected = adb.resolve_serial(serial, env=os.environ)
    name = adb.run(selected, "emu", "avd", "name").splitlines()[0].strip()
    if name != "webnovel_api36":
        raise adb.DeviceError(f"Expected webnovel_api36, found {name!r}")
    if not adb.shell(selected, f"pm path {PACKAGE}"):
        raise adb.DeviceError("Install the debug APK before running simulation")
    return selected


def private_shell(serial, command, data=None, check=True):
    if not serial.startswith("emulator-"):
        raise adb.DeviceError("Refusing a non-emulator target")
    # The payload goes to stdin, never through shell interpolation or command arguments.
    result = subprocess.run(
        [adb.ADB, "-s", serial, "shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(command)],
        input=data, capture_output=True, timeout=30,
    )
    if check and result.returncode:
        raise adb.DeviceError(result.stderr.decode("utf-8", "replace")[:400])
    return result.stdout


def read_json(serial, path):
    return json.loads(adb.run_as_read(serial, PACKAGE, path))


def active_config(serial):
    return read_json(serial, f"{BASE}/session.json")


def session_path(session):
    return f"{BASE}/sessions/{identifier(session)}"


def launch(serial, screen):
    if screen not in SCREENS:
        raise ValueError("Unknown dev screen")
    adb.run(serial, "shell", "am", "force-stop", PACKAGE)
    adb.run(serial, "shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}",
            "--es", "dev_start_screen", screen, "--es", "dev_start_story", "rr_990001")


def stage(serial, config):
    directory = session_path(config["sessionId"])
    exists = private_shell(serial, f"test -d {directory} && echo exists", check=False)
    if exists.strip():
        raise ValueError("Session already exists; use continue or choose a new session ID")
    adb.run(serial, "shell", "am", "force-stop", PACKAGE)
    archive_interrupted(serial)
    # Enable denial first. A staging failure can never leave an intended simulation online.
    private_shell(serial, f"mkdir -p {BASE} && touch {BASE}/enabled && rm -f {BASE}/startup_error.json")
    private_shell(serial, f"cat > {BASE}/session.json.tmp && mv {BASE}/session.json.tmp {BASE}/session.json",
                  json.dumps(config).encode())


def fetch_report(serial, session):
    return read_json(serial, f"{session_path(session)}/report.json")


def save_report(report):
    directory = ROOT / "simulation-runs" / identifier(report["sessionId"])
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    assertions = report.get("assertions", {})
    lines = [f"# {report['scenario']}", "", f"Session: `{report['sessionId']}`",
             f"Phase: **{report.get('phase', 'unknown')}**", f"Timing: {report.get('timing')} at {report.get('speed')}x", ""]
    lines += [f"- {'PASS' if passed else 'FAIL'}: {name}" for name, passed in assertions.items()]
    if not assertions:
        lines.append("No terminal assertions yet. This run is incomplete.")
    (directory / "report.md").write_text("\n".join(lines) + "\n")
    return directory


def archive_interrupted(serial):
    try:
        previous = active_config(serial)
        report = fetch_report(serial, previous["sessionId"])
    except (adb.DeviceError, ValueError):
        return
    if report.get("phase") not in {"completed", "failed"}:
        report["phase"] = "interrupted"
        report["passed"] = None
        path = f"{session_path(previous['sessionId'])}/report.json"
        private_shell(serial, f"cat > {path}.tmp && mv {path}.tmp {path}", json.dumps(report).encode())
    save_report(report)


def verify(serial, session):
    if active_config(serial)["sessionId"] != session:
        raise ValueError("Verification is only available for the active session")
    # The real solver activity invokes the same queue-resume path as an interactive check.
    private_shell(serial, f"touch {session_path(session)}/verify")
    adb.run(serial, "shell", "am", "start", "-n", f"{PACKAGE}/{CONTROL}", "--es", "command", "verify")


def wait_report(serial, session, until, timeout, auto_verify=False, minimum_boot=0):
    deadline = time.monotonic() + timeout
    attempted_verification = False
    last = None
    while time.monotonic() < deadline:
        try:
            report = fetch_report(serial, session)
            if report.get("bootCount", 0) < minimum_boot:
                time.sleep(0.25)
                continue
            last = report
            phase = report.get("phase")
            if auto_verify and phase == "waiting_verification" and not attempted_verification:
                verify(serial, session)
                attempted_verification = True
            ready_phases = {"ready", "running", "held", "waiting_verification", "completed", "failed", "interrupted"}
            if phase in {"completed", "failed"} or (until == "ready" and phase in ready_phases) or phase == until:
                return report
        except (adb.DeviceError, json.JSONDecodeError):
            error = private_shell(serial, f"cat {BASE}/startup_error.json", check=False)
            if error.strip():
                raise ValueError("Simulation startup failed; networking stays disabled. Inspect the startup screen, then use stop or a new run.")
        time.sleep(0.25)
    if last:
        save_report(last)
    raise TimeoutError(f"Session did not reach {until!r} in {timeout}s; latest phase: {(last or {}).get('phase', 'no report')}")


def make_config(args, scenario):
    timing = args.timing or scenario.get("timing", "fast")
    if args.hold and args.timing is None:
        timing = "controlled"
    if timing == "controlled" and not args.hold:
        raise ValueError("--timing controlled requires --hold")
    speed = args.speed if args.speed is not None else (100 if timing == "fast" else 1)
    if not 1 <= speed <= 1000:
        raise ValueError("--speed must be between 1 and 1000")
    return {
        "version": 1, "sessionId": args.session or datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:8],
        "scenario": scenario["id"], "seed": args.seed, "timing": timing, "speed": speed,
        "epochMillis": int(time.time() * 1000), "hold": args.hold, "autoStart": not args.manual,
    }


def print_report(report):
    directory = save_report(report)
    print(json.dumps({"sessionId": report["sessionId"], "scenario": report["scenario"], "phase": report.get("phase"),
                      "passed": report.get("passed"), "assertions": report.get("assertions", {}), "report": str(directory / "report.json")}, indent=2))


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--serial", help="Explicit emulator-* serial")
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="List bundled scenarios without using a device")
    suite = commands.add_parser("suite", help="Run scenarios sequentially, verifying local challenges")
    suite.add_argument("scenarios", nargs="*", choices=None)
    suite.add_argument("--timeout", type=float, default=90)
    run = commands.add_parser("run", help="Start a new disposable session")
    run.add_argument("scenario", choices=list(catalog()))
    run.add_argument("--screen", choices=sorted(SCREENS))
    run.add_argument("--session", type=identifier)
    run.add_argument("--seed", type=int, default=1)
    run.add_argument("--timing", choices=["fast", "visible", "controlled"])
    run.add_argument("--speed", type=float)
    run.add_argument("--hold", choices=["decisions", "description", "prompt", "image", "source"])
    run.add_argument("--manual", action="store_true", help="Seed and open; perform actions through the app UI")
    for name in ["report", "wait", "continue", "verify"]:
        cmd = commands.add_parser(name)
        cmd.add_argument("session", nargs="?", type=identifier)
    commands.choices["continue"].add_argument("--screen", choices=sorted(SCREENS))
    release = commands.add_parser("release", help="Release a stage or one held operation ID")
    release.add_argument("session", type=identifier)
    release.add_argument("operation", type=identifier)
    stop = commands.add_parser("stop", help="Stop services and explicitly return to the normal library")
    stop.add_argument("--no-launch", action="store_true")
    reset = commands.add_parser("reset", help="Remove one disposable session, never normal app data")
    reset.add_argument("session", type=identifier)
    for cmd in [run, commands.choices["wait"], commands.choices["continue"]]:
        cmd.add_argument("--until", choices=["ready", "held", "waiting_verification", "completed"], default="ready" if cmd == run else "completed")
        cmd.add_argument("--timeout", type=float, default=120)
        cmd.add_argument("--auto-verify", action="store_true")
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    scenarios = catalog()
    if args.command == "list":
        for name, scenario in scenarios.items():
            print(f"{name:36} {scenario['action']:12} {scenario.get('description', '')}")
        return 0
    if args.command == "suite":
        names = args.scenarios or list(scenarios)
        if any(name not in scenarios for name in names):
            raise ValueError("Unknown scenario in suite")
        failures = []
        for name in names:
            print(f"Running {name}", flush=True)
            try:
                code = main((["--serial", args.serial] if args.serial else []) +
                            ["run", name, "--timing", "fast", "--until", "completed", "--auto-verify", "--timeout", str(args.timeout)])
            except (adb.DeviceError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
                print(f"FAIL {name}: {error}", flush=True)
                code = 2
            if code:
                failures.append(name)
        print(json.dumps({"scenarios": len(names), "failures": failures}), flush=True)
        return bool(failures)
    serial = target(args.serial)
    if args.command == "run":
        config = make_config(args, scenarios[args.scenario])
        stage(serial, config)
        launch(serial, args.screen or scenarios[args.scenario]["screen"])
        report = wait_report(serial, config["sessionId"], args.until, args.timeout, args.auto_verify)
        print_report(report)
        return 1 if report.get("passed") is False else 0
    if args.command == "stop":
        adb.run(serial, "shell", "am", "force-stop", PACKAGE)
        archive_interrupted(serial)
        private_shell(serial, f"rm -f {BASE}/enabled {BASE}/session.json")
        if not args.no_launch:
            launch(serial, "library")
        return 0
    if args.command == "reset":
        try:
            config = active_config(serial)
        except (adb.DeviceError, ValueError):
            config = {}
        if config.get("sessionId") == args.session:
            raise ValueError("Stop the active session before resetting it")
        private_shell(serial, f"rm -rf {session_path(args.session)}")
        return 0
    session = args.session or active_config(serial)["sessionId"]
    if args.command == "release":
        if active_config(serial)["sessionId"] != session:
            raise ValueError("Cannot release an inactive session")
        private_shell(serial, f"mkdir -p {session_path(session)}/release && touch {session_path(session)}/release/{args.operation}")
        return 0
    if args.command == "verify":
        verify(serial, session)
        return 0
    if args.command == "continue":
        config = active_config(serial)
        if config["sessionId"] != session:
            raise ValueError("Continue requires the active session")
        previous_boot = fetch_report(serial, session).get("bootCount", 0)
        launch(serial, args.screen or scenarios[config["scenario"]]["screen"])
    report = wait_report(serial, session, args.until, args.timeout, args.auto_verify,
                         previous_boot + 1 if args.command == "continue" else 0) if args.command in {"wait", "continue"} else fetch_report(serial, session)
    print_report(report)
    return 1 if report.get("passed") is False else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (adb.DeviceError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
        print(f"Simulation: {error}", file=sys.stderr)
        raise SystemExit(2)
