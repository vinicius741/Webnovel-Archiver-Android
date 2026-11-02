import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("simulate", Path(__file__).resolve().parents[1] / "simulate.py")
simulate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = simulate
SPEC.loader.exec_module(simulate)


class SimulationRunnerTest(unittest.TestCase):
    def test_unsafe_session_paths_are_rejected(self):
        for value in ["../library", "/absolute", "a;echo secret", "x y", "", "a" * 81]:
            with self.assertRaises(Exception):
                simulate.session_path(value)

    def test_catalog_has_unique_scenarios_and_valid_screens(self):
        raw = json.loads(simulate.CATALOG.read_text())["scenarios"]
        self.assertEqual(len(raw), len(simulate.catalog()))
        for scenario in raw:
            self.assertIn(scenario["screen"], simulate.SCREENS)
            simulate.identifier(scenario["id"])

    def test_target_rejects_wrong_avd(self):
        with patch.object(simulate.adb, "resolve_serial", return_value="emulator-5554"), \
                patch.object(simulate.adb, "run", return_value="another_avd\nOK"):
            with self.assertRaises(simulate.adb.DeviceError):
                simulate.target()

    def test_no_device_needed_for_list(self):
        with patch.object(simulate, "target", side_effect=AssertionError("Touched device")), patch("builtins.print"):
            self.assertEqual(0, simulate.main(["list"]))

    def test_held_requests_require_a_named_stage(self):
        parser = simulate.parser()
        args = parser.parse_args(["run", "ai-cover", "--timing", "controlled"])
        with self.assertRaises(ValueError):
            simulate.make_config(args, simulate.catalog()["ai-cover"])
        args = parser.parse_args(["run", "ai-cover", "--hold", "image"])
        config = simulate.make_config(args, simulate.catalog()["ai-cover"])
        self.assertEqual("controlled", config["timing"])
        self.assertEqual(1, config["speed"])

    def test_config_payload_is_passed_only_through_stdin(self):
        payload = b'{"secret":"$(touch /tmp/do-not-create)"}'
        with patch.object(simulate.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = b""
            simulate.private_shell("emulator-5554", "cat > files/config.tmp", payload)
            args, kwargs = run.call_args
            self.assertNotIn(payload.decode(), " ".join(args[0]))
            self.assertEqual(payload, kwargs["input"])
            self.assertEqual(["adb", "-s", "emulator-5554"], args[0][:3])

    def test_restart_wait_does_not_accept_a_previous_boots_terminal_report(self):
        previous = {"sessionId": "test", "phase": "completed", "bootCount": 1}
        current = {"sessionId": "test", "phase": "held", "bootCount": 2}
        with patch.object(simulate, "fetch_report", side_effect=[previous, current]), patch.object(simulate.time, "sleep"):
            report = simulate.wait_report("emulator-5554", "test", "held", 2, minimum_boot=2)
            self.assertEqual(current, report)


if __name__ == "__main__":
    unittest.main()
