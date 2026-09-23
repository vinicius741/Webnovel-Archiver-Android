import json
import os
import shutil
import tempfile
import unittest

sys_path_setup = True
import os as _os
import sys as _sys

_sys.path.insert(0, _os.path.abspath(_os.path.join(_os.path.dirname(__file__), "..")))

from perflib import baseline as baseline_lib  # noqa: E402


def env_block(model="emu", sdk="36", sha="abc123", app_id="pkg.debug"):
    return {
        "device": {"model": model, "sdkInt": sdk},
        "dataset": {"storyIdsSha256": sha, "appId": app_id},
        "reportVersion": 1,
    }


def stats_block(median, count=3):
    return {"median": median, "count": count}


def report_block(status="ok", metrics=None, frames=None):
    return {
        "version": 1,
        "generatedAt": "2026-09-18T00:00:00",
        "environment": env_block(),
        "scenarios": {
            "cold_library": {
                "status": status,
                "metrics": metrics if metrics is not None else {
                    "proc_start_to_ui_ready_ms": stats_block(900.0),
                    "pss_at_ready_kb": stats_block(90000.0),
                },
                "frames": frames if frames is not None else {"count": 0, "insufficient_samples": True},
            }
        },
    }


class CompatibilityTest(unittest.TestCase):
    def test_comparable(self):
        self.assertEqual(baseline_lib.compatibility_reasons(env_block(), env_block()), [])

    def test_device_change_rejected(self):
        reasons = baseline_lib.compatibility_reasons(env_block(), env_block(model="other"))
        self.assertTrue(any("model" in r for r in reasons))
        reasons = baseline_lib.compatibility_reasons(env_block(sdk="35"), env_block())
        self.assertTrue(any("sdkInt" in r for r in reasons))

    def test_dataset_change_rejected(self):
        reasons = baseline_lib.compatibility_reasons(env_block(), env_block(sha="different"))
        self.assertTrue(any("dataset" in r for r in reasons))

    def test_chapter_count_and_reader_target_changes_rejected(self):
        current = env_block()
        baseline = env_block()
        current["dataset"]["downloadedChapterEntries"] = 11
        baseline["dataset"]["downloadedChapterEntries"] = 10
        current["readerTarget"] = {"storyId": "s", "chapterId": "new"}
        baseline["readerTarget"] = {"storyId": "s", "chapterId": "old"}
        reasons = baseline_lib.compatibility_reasons(current, baseline)
        self.assertTrue(any("downloadedChapterEntries" in r for r in reasons))
        self.assertTrue(any("chapterId" in r for r in reasons))

    def test_variant_change_rejected(self):
        reasons = baseline_lib.compatibility_reasons(env_block(), env_block(app_id="pkg.instrumentation"))
        self.assertTrue(any("app id" in r for r in reasons))

    def test_version_change_rejected(self):
        cur = dict(env_block(), reportVersion=2)
        reasons = baseline_lib.compatibility_reasons(cur, env_block())
        self.assertTrue(any("version" in r for r in reasons))


class ThresholdTest(unittest.TestCase):
    def test_regression_requires_abs_and_rel(self):
        threshold = {"abs": 150.0, "rel": 0.15}
        # +200ms but only +9% on a 2200ms baseline -> stable (abs passes, rel does not)
        verdict = baseline_lib.judge_metric(stats_block(2000.0), stats_block(2200.0), threshold)
        self.assertEqual(verdict["verdict"], "stable")
        # +200ms and +25% on an 800ms baseline -> regression (both pass)
        verdict = baseline_lib.judge_metric(stats_block(800.0), stats_block(1000.0), threshold)
        self.assertEqual(verdict["verdict"], "regression")

    def test_improved(self):
        verdict = baseline_lib.judge_metric(stats_block(1000.0), stats_block(700.0), {"abs": 150.0, "rel": 0.15})
        self.assertEqual(verdict["verdict"], "improved")

    def test_insufficient_samples(self):
        verdict = baseline_lib.judge_metric(stats_block(1000.0, count=1), stats_block(1200.0, count=1), {"abs": 1.0, "rel": 0.0})
        self.assertEqual(verdict["verdict"], "inconclusive")
        self.assertIn("insufficient", verdict["reason"])

    def test_missing_stats(self):
        verdict = baseline_lib.judge_metric(None, stats_block(100.0), {"abs": 1.0, "rel": 0.0})
        self.assertEqual(verdict["verdict"], "inconclusive")

    def test_zero_baseline_median(self):
        verdict = baseline_lib.judge_metric(stats_block(0.0), stats_block(50.0), {"abs": 1.0, "rel": 0.0})
        self.assertEqual(verdict["verdict"], "inconclusive")

    def test_diagnostic_metrics_never_regress(self):
        verdict = baseline_lib.judge_metric(stats_block(100.0), stats_block(500.0), baseline_lib.threshold_for("pss_at_ready_kb", {}))
        self.assertEqual(verdict["verdict"], "diagnostic")


class CompareReportsTest(unittest.TestCase):
    def test_synthetic_regression_detected(self):
        base = report_block(metrics={"proc_start_to_ui_ready_ms": stats_block(800.0), "reader_prepare_ms": stats_block(400.0)})
        current = report_block(metrics={"proc_start_to_ui_ready_ms": stats_block(1100.0), "reader_prepare_ms": stats_block(700.0)})
        comparison = baseline_lib.compare_reports(current, base)
        self.assertEqual(comparison["status"], "ok")
        block = comparison["scenarios"]["cold_library"]
        self.assertEqual(block["status"], "regression")
        self.assertEqual(block["metrics"]["proc_start_to_ui_ready_ms"]["verdict"], "regression")
        self.assertEqual(block["metrics"]["reader_prepare_ms"]["verdict"], "regression")

    def test_stable_and_improved(self):
        base = report_block(metrics={"proc_start_to_ui_ready_ms": stats_block(1000.0)})
        current = report_block(metrics={"proc_start_to_ui_ready_ms": stats_block(600.0)})
        comparison = baseline_lib.compare_reports(current, base)
        self.assertEqual(comparison["scenarios"]["cold_library"]["status"], "improved")

    def test_not_comparable_blocks_metric_verdicts(self):
        base = report_block()
        current = report_block()
        current["environment"]["dataset"]["storyIdsSha256"] = "changed"
        comparison = baseline_lib.compare_reports(current, base)
        self.assertEqual(comparison["status"], "not_comparable")
        self.assertEqual(comparison["scenarios"], {})

    def test_failed_execution_is_inconclusive_not_regression(self):
        base = report_block()
        current = report_block(status="failed")
        comparison = baseline_lib.compare_reports(current, base)
        block = comparison["scenarios"]["cold_library"]
        self.assertEqual(block["status"], "inconclusive")
        self.assertIn("non-ok execution", block["reason"])

    def test_scenario_missing_in_baseline(self):
        base = report_block()
        current = report_block()
        current["scenarios"]["cold_reader"] = {"status": "ok", "metrics": {"x": stats_block(1.0)}, "frames": {}}
        comparison = baseline_lib.compare_reports(current, base)
        self.assertEqual(comparison["scenarios"]["cold_reader"]["status"], "inconclusive")

    def test_frame_metrics_compared(self):
        base = report_block(frames={"count": 500, "insufficient_samples": False, "p95_ms": 10.0, "jank_pct": 5.0})
        current = report_block(frames={"count": 500, "insufficient_samples": False, "p95_ms": 20.0, "jank_pct": 12.0})
        comparison = baseline_lib.compare_reports(current, base)
        metrics = comparison["scenarios"]["cold_library"]["metrics"]
        self.assertEqual(metrics["frame_p95_ms"]["verdict"], "regression")
        self.assertEqual(metrics["frame_jank_pct"]["verdict"], "regression")


class BaselineStoreTest(unittest.TestCase):
    def test_save_list_and_no_overwrite(self):
        root = tempfile.mkdtemp()
        try:
            baseline_lib.save_baseline(report_block(), root, "main", source_path="x/report.json")
            with self.assertRaises(FileExistsError):
                baseline_lib.save_baseline(report_block(), root, "main")
            loaded, path = baseline_lib.load_baseline(root, "main")
            self.assertEqual(loaded["version"], 1)
            self.assertTrue(path.endswith("report.json"))
            missing = _os.path.join(root, "baselines", "nope", "report.json")
            with self.assertRaises(FileNotFoundError):
                baseline_lib.load_baseline(root, "nope")
        finally:
            shutil.rmtree(root)

    def test_load_by_path(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump(report_block(), handle)
            path = handle.name
        try:
            loaded, resolved = baseline_lib.load_baseline("/ignored", path)
            self.assertEqual(loaded["version"], 1)
            self.assertEqual(resolved, path)
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
