#!/usr/bin/env python3
"""Apply the default foldable profile to a stopped AVD, preserving its data."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def entries(text):
    return dict(
        line.split("=", 1)
        for line in text.splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_root = Path(os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android/avd"))
    parser.add_argument("--avd-dir", type=Path, default=default_root / "webnovel_api36.avd")
    args = parser.parse_args()
    # Discovery only. Never operate a physical device.
    devices = subprocess.run(["adb", "devices", "-l"], check=True, capture_output=True, text=True)
    if any(line.startswith("emulator-") for line in devices.stdout.splitlines()):
        parser.error("Stop running emulators before configuring their hardware.")
    config = args.avd_dir / "config.ini"
    original = config.read_text()
    overrides = entries(Path(__file__).with_name("fold7-config.ini").read_text())
    current = entries(original)
    if all(current.get(key) == value for key, value in overrides.items()):
        print(f"Fold7 profile already configured: {config}")
        return
    backup = config.with_name("config.ini.before-fold7")
    if not backup.exists():
        shutil.copy2(config, backup)
    lines = [line for line in original.splitlines() if line.split("=", 1)[0] not in overrides]
    lines.extend(f"{key}={value}" for key, value in overrides.items())
    temporary = config.with_name("config.ini.fold7.tmp")
    temporary.write_text("\n".join(lines) + "\n")
    temporary.replace(config)
    print(f"Configured {config}; original configuration saved at {backup}")
    print("Data images are unchanged. Start webnovel_api36 with a cold boot, never -wipe-data.")


if __name__ == "__main__":
    main()
