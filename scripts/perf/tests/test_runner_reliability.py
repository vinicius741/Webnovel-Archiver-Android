import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from perflib import device, report, scenarios  # noqa: E402


class DatasetProbeTest(unittest.TestCase):
    @patch.object(device.adb, "launch_and_wait_foreground", return_value=False)
    @patch.object(device.adb, "run")
    @patch.object(device.adb, "force_stop")
    def test_old_report_removed_before_launch(self, _stop, remove, _launch):
        with self.assertRaisesRegex(device.EnvironmentError_, "foreground"):
            device.probe_dataset("emulator-5554", "pkg.debug")
        remove.assert_called_once_with(
            "emulator-5554", "shell", "run-as", "pkg.debug", "rm", "-f", "cache/dev_library_report.json"
        )


class ColdVerificationTest(unittest.TestCase):
    @patch.object(scenarios.time, "sleep")
    @patch.object(scenarios.adb, "shell", return_value="")
    @patch.object(scenarios, "crash_evidence", return_value=("", {"crashCount": 0, "strictModeCount": 0}))
    @patch.object(scenarios, "verify_screen_settled", return_value=(False, "", {"missingMarkers": ["Library"]}))
    @patch.object(scenarios, "pull_frames", return_value=([], 0))
    @patch.object(scenarios, "refresh_session")
    @patch.object(scenarios, "wait_for_session")
    @patch.object(scenarios, "cold_start_with_perf", return_value={})
    def test_wrong_screen_does_not_count_as_success(self, _start, wait, refresh, _frames, _verify, _crashes, _shell, _sleep):
        doc = {
            "session": {"runId": "run", "scenario": "cold_library", "iteration": "1", "processStartElapsedNanos": 1},
            "events": [
                {"name": "screen_built", "t": 2, "durationMillis": 1, "detail": {"route": "library"}},
                {"name": "scenario_complete", "t": 3},
            ],
            "memory": [],
        }
        wait.return_value = (doc, None)
        refresh.return_value = doc
        result = scenarios.run_cold_scenario(
            "emulator-5554", "pkg.debug", "run", "cold_library", scenarios.COLD_SCENARIOS["cold_library"], 1
        )
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["ok_iterations"], 0)
        self.assertEqual(result["iterations"][0]["status"], "execution_failure")
        self.assertIn("screen verification failed", result["iterations"][0]["error"])


class ReportReliabilityTest(unittest.TestCase):
    def test_noisy_cold_metric_is_flagged(self):
        lines = report.render_scenario(
            "cold_library",
            {"status": "ok", "metrics": {"startup_load_ms": {"median": 150, "count": 2}},
             "variability_cv": {"startup_load_ms": 0.55}},
            None,
        )
        self.assertIn("High iteration variability (startup_load_ms)", "\n".join(lines))

    def test_tiny_metric_does_not_get_variability_warning(self):
        lines = report.render_scenario(
            "cold_reader",
            {"status": "ok", "metrics": {"screen_build_ms": {"median": 4.5, "count": 2}},
             "variability_cv": {"screen_build_ms": 0.55}},
            None,
        )
        self.assertNotIn("High iteration variability", "\n".join(lines))


if __name__ == "__main__":
    unittest.main()
