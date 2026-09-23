import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from perflib import aggregate  # noqa: E402
from test_session import make_session_doc  # noqa: E402


def cold_session(route="library", ui_ready_ms=800, built_ms=1200, startup_total=300, pss=90000):
    events = [
        {"name": "session_start", "t": 1_000_000_000},
        {
            "name": "startup_pass",
            "t": 1_100_000_000,
            "durationMillis": startup_total,
            "detail": {"loadMs": "150", "migrateMs": "10", "hydrateMs": "140"},
        },
        {"name": "ui_ready", "t": 1_000_000_000 + ui_ready_ms * 1_000_000},
        {"name": "screen_built", "t": 1_000_000_000 + built_ms * 1_000_000, "durationMillis": 45, "detail": {"route": route}},
        {"name": "scenario_complete", "t": 1_000_000_000 + built_ms * 1_000_000 + 2},
    ]
    doc = make_session_doc(events=events)
    doc["memory"] = [{"t": events[-2]["t"], "label": f"screen:{route}", "totalPssKb": pss}]
    return doc


class ColdMetricsTest(unittest.TestCase):
    def test_extracts_metrics(self):
        m = aggregate.extract_cold_metrics(cold_session(), "library")
        self.assertAlmostEqual(m["proc_start_to_ui_ready_ms"], 800.0)
        self.assertAlmostEqual(m["proc_start_to_screen_built_ms"], 1200.0)
        self.assertAlmostEqual(m["startup_total_ms"], 300.0)
        self.assertAlmostEqual(m["startup_load_ms"], 150.0)
        self.assertAlmostEqual(m["screen_build_ms"], 45.0)
        self.assertAlmostEqual(m["pss_at_ready_kb"], 90000.0)

    def test_missing_events_leave_metrics_absent(self):
        doc = make_session_doc(events=[{"name": "scenario_complete", "t": 5}])
        m = aggregate.extract_cold_metrics(doc, "library")
        self.assertEqual(m, {})

    def test_reader_milestones(self):
        events = [
            {"name": "reader_preparing", "t": 1_100_000_000, "detail": {"storyId": "s", "chapterId": "c"}},
            {"name": "reader_prepare_done", "t": 1_200_000_000, "durationMillis": 250, "detail": {"outcome": "ready"}},
            {"name": "reader_content_presented", "t": 1_300_000_000},
            {"name": "reader_content_painted", "t": 1_350_000_000},
            {"name": "screen_built", "t": 1_310_000_000, "durationMillis": 60, "detail": {"route": "reader"}},
        ]
        doc = make_session_doc(events=events)
        m = aggregate.extract_cold_metrics(doc, "reader")
        self.assertAlmostEqual(m["reader_prepare_ms"], 250.0)
        self.assertAlmostEqual(m["proc_start_to_reader_presented_ms"], 300.0)
        self.assertAlmostEqual(m["proc_start_to_reader_painted_ms"], 350.0)


class FrameStatsTest(unittest.TestCase):
    def test_percentiles_withheld_below_min(self):
        result = aggregate.frame_stats([{"durationMs": 5.0, "jank": False}] * 10, min_samples=100)
        self.assertTrue(result["insufficient_samples"])
        self.assertNotIn("p95_ms", result)

    def test_percentiles_reported_above_min(self):
        samples = [{"durationMs": float(i % 20), "jank": i % 20 > 16} for i in range(200)]
        result = aggregate.frame_stats(samples, min_samples=100)
        self.assertFalse(result["insufficient_samples"])
        self.assertIn("p95_ms", result)
        self.assertGreater(result["jank_pct"], 0)

    def test_empty(self):
        result = aggregate.frame_stats([])
        self.assertEqual(result["count"], 0)
        self.assertTrue(result["insufficient_samples"])


class ColdFrameWindowTest(unittest.TestCase):
    def test_window_bounds(self):
        doc = cold_session()
        window = aggregate.cold_frame_window(doc)
        self.assertEqual(window, (1_000_000_000 + 800 * 1_000_000, 1_000_000_000 + 2200 * 1_000_000 + 2))

    def test_no_window_without_ui_ready(self):
        doc = make_session_doc(events=[{"name": "scenario_complete", "t": 5}])
        self.assertIsNone(aggregate.cold_frame_window(doc))


class AggregateScenarioTest(unittest.TestCase):
    def test_all_ok(self):
        iterations = [
            {"status": "ok", "session": cold_session(), "metrics": {"proc_start_to_ui_ready_ms": 800.0}, "frame_samples": []},
            {"status": "ok", "session": cold_session(), "metrics": {"proc_start_to_ui_ready_ms": 1000.0}, "frame_samples": []},
        ]
        result = aggregate.aggregate_cold_scenario(iterations, "library")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["metrics"]["proc_start_to_ui_ready_ms"]["count"], 2)
        self.assertAlmostEqual(result["metrics"]["proc_start_to_ui_ready_ms"]["median"], 900.0)

    def test_partial_and_failed(self):
        iterations = [
            {"status": "ok", "session": cold_session(), "metrics": {"x": 1.0}, "frame_samples": []},
            {"status": "execution_failure", "error": "session not captured", "metrics": {}, "frame_samples": None},
        ]
        result = aggregate.aggregate_cold_scenario(iterations, "library")
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["ok_iterations"], 1)
        empty = aggregate.aggregate_cold_scenario([iterations[1]], "library")
        self.assertEqual(empty["status"], "failed")

    def test_cold_evidence_is_visible_at_scenario_level(self):
        iterations = [
            {"status": "ok", "session": cold_session(), "metrics": {"x": 1.0}, "frame_samples": [],
             "evidence": {"crashCount": 0, "strictModeCount": 4}},
            {"status": "ok", "session": cold_session(), "metrics": {"x": 2.0}, "frame_samples": [],
             "evidence": {"crashCount": 0, "strictModeCount": 3}},
        ]
        result = aggregate.aggregate_cold_scenario(iterations, "library")
        self.assertEqual(result["evidence"]["strictModeCount"], 7)


class WarmedNavigationTest(unittest.TestCase):
    def test_leg_pairing(self):
        events = [
            {"name": "session_start", "t": 0},
            {"name": "screen_built", "t": 50_000_000, "durationMillis": 40, "detail": {"route": "library"}},
            {"name": "interaction", "t": 1_000_000_000},
            {"name": "screen_built", "t": 1_200_000_000, "durationMillis": 35, "detail": {"route": "details"}},
            {"name": "interaction", "t": 2_000_000_000},
            {"name": "reader_content_presented", "t": 2_350_000_000},
            {"name": "reader_content_painted", "t": 2_450_000_000},
            {"name": "interaction", "t": 3_000_000_000},
            {"name": "screen_built", "t": 3_150_000_000, "durationMillis": 33, "detail": {"route": "details"}},
            {"name": "interaction", "t": 4_000_000_000},
            {"name": "screen_built", "t": 4_120_000_000, "durationMillis": 30, "detail": {"route": "library"}},
        ]
        doc = make_session_doc(scenario="warmed_navigation", events=events)
        doc["memory"] = [
            {"t": 4_120_000_000, "label": "screen:library", "totalPssKb": 100},
            {"t": 5_120_000_000, "label": "screen:library", "totalPssKb": 110},
        ]
        flat, legs = aggregate.extract_warmed_navigation(doc)
        self.assertEqual(len(legs), 5)
        self.assertAlmostEqual(flat["tap_to_details_ms"][0], 200.0)
        self.assertAlmostEqual(flat["tap_to_reader_presented_ms"][0], 350.0)
        self.assertAlmostEqual(flat["tap_to_reader_painted_ms"][0], 450.0)
        self.assertAlmostEqual(flat["tap_to_library_ms"][0], 120.0)
        self.assertAlmostEqual(flat["build_details_ms"][0], 35.0)
        self.assertAlmostEqual(flat["memory_trend_kb_per_cycle"], 10.0)


class ScrollMetricsTest(unittest.TestCase):
    def test_window_filtering(self):
        events = [{"name": "interaction", "t": t} for t in (1_000_000_000, 2_000_000_000)]
        doc = make_session_doc(scenario="reader_scroll", events=events)
        frames = [
            {"t": 1_100_000_000, "durationMs": 20.0, "jank": True, "tag": "reader"},
            {"t": 1_500_000_000, "durationMs": 8.0, "jank": False, "tag": "reader"},
            {"t": 1_900_000_000, "durationMs": 8.0, "jank": False, "tag": "reader"},  # outside both windows
        ]
        result = aggregate.extract_scroll_metrics(doc, frames)
        self.assertEqual(result["interaction_count"], 2)
        self.assertEqual(result["frame_stats"]["count"], 2)
        self.assertIn("reader", result["tag_breakdown"])


if __name__ == "__main__":
    unittest.main()
