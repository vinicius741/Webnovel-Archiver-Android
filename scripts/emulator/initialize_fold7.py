#!/usr/bin/env python3
"""Seed the SDK's foldable system configuration into the existing debug emulator."""

import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("EMULATOR_SERIAL"))
    args = parser.parse_args()
    discovered = subprocess.run(["adb", "devices", "-l"], check=True, capture_output=True, text=True)
    healthy = [
        row.split()[0] for row in discovered.stdout.splitlines()
        if row.startswith("emulator-") and row.split()[1] == "device"
    ]
    if args.serial:
        if args.serial not in healthy:
            parser.error("--serial must select a healthy emulator-* target.")
        serial = args.serial
    elif len(healthy) == 1:
        serial = healthy[0]
    else:
        parser.error("Expected one healthy emulator; otherwise select --serial explicitly.")

    def adb(*command, **kwargs):
        return subprocess.run(["adb", "-s", serial, *command], check=True, **kwargs)

    name = adb("emu", "avd", "name", capture_output=True, text=True).stdout.splitlines()[0]
    if name != "webnovel_api36":
        parser.error(f"Expected webnovel_api36, found {name}.")
    sdk = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
    source = sdk / "system-images/android-36/google_apis/arm64-v8a/data/misc/pixel_9_pro_fold"
    files = [
        "devicestate/device_state_configuration.xml",
        "displayconfig/display_layout_configuration.xml",
        "display_settings.xml",
        "extra_feature.xml",
    ]
    for relative in files:
        if not (source / relative).is_file():
            parser.error(f"Missing SDK configuration: {source / relative}")
    adb("root")
    adb("wait-for-device", timeout=30)
    if adb("shell", "id", "-u", capture_output=True, text=True).stdout.strip() != "0":
        parser.error("This requires the debug Google APIs emulator image with adb root support.")
    changed = []
    for relative in files:
        destination = f"/data/system/{relative}"
        existing = subprocess.run(
            ["adb", "-s", serial, "shell", "cat", destination], capture_output=True
        )
        if existing.returncode == 0:
            if existing.stdout == (source / relative).read_bytes():
                continue
            # Android rewrites this file with additional display preferences at boot.
            # Preserve those preferences when the required foldable setting is present.
            if relative == "display_settings.xml":
                try:
                    root = ET.fromstring(existing.stdout)
                    if any(
                        display.get("name") == "local:4619827259835644672"
                        and display.get("ignoreOrientationRequest") == "true"
                        for display in root.findall("display")
                    ):
                        continue
                except ET.ParseError:
                    pass
        changed.append(relative)
        parent = str(Path(destination).parent)
        adb("shell", "mkdir", "-p", parent)
        if existing.returncode == 0:
            # Preserve pre-migration files before installing the SDK configuration.
            adb("shell", "cp", "-n", destination, destination + ".before-fold7")
        adb("push", str(source / relative), destination)
        adb("shell", "chown", "system:system", destination)
        adb("shell", "chmod", "640", destination)
        adb("shell", "restorecon", destination)
    for directory in ("devicestate", "displayconfig"):
        adb("shell", "chown", "system:system", f"/data/system/{directory}")
        adb("shell", "chmod", "2750", f"/data/system/{directory}")
        adb("shell", "restorecon", f"/data/system/{directory}")
    if changed:
        print("Fold/display configuration installed; rebooting. App data is preserved.")
        adb("reboot")
    else:
        print("Fold/display configuration already installed; no reboot needed.")


if __name__ == "__main__":
    main()
