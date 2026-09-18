import json
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from perflib import session as session_lib  # noqa: E402


def make_session_doc(run_id="r1", scenario="cold_library", iteration="1", events=None, ended=True):
    return {
        "format": "webnovel-perf-session",
        "version": 1,
        "session": {
            "runId": run_id,
            "scenario": scenario,
            "iteration": iteration,
            "completeOn": "screen_built",
            "completeOnRoute": None,
            "startedAtWallClockMillis": 1,
            "processStartElapsedNanos": 1_000_000_000,
            "scenarioCompleteElapsedNanos": None,
            "deadlineFired": False,
            "ended": ended,
        },
        "device": {"appId": "pkg", "appVersion": "1", "sdkInt": 36, "model": "emu", "densityDpi": 420, "refreshRateHz": 60.0},
        "events": events if events is not None else [],
        "memory": [],
        "overhead": {"eventRecordCalls": 0},
    }


class ParseSessionTest(unittest.TestCase):
    def test_valid(self):
        doc, err = session_lib.parse_session_json(json.dumps(make_session_doc()))
        self.assertIsNone(err)
        self.assertEqual(doc["session"]["runId"], "r1")

    def test_malformed_json(self):
        doc, err = session_lib.parse_session_json("{not json")
        self.assertIsNone(doc)
        self.assertIn("invalid json", err)

    def test_wrong_format_and_version(self):
        doc, _ = session_lib.parse_session_json(json.dumps({"format": "other", "version": 1}))
        self.assertIsNone(doc)
        doc, err = session_lib.parse_session_json(json.dumps(dict(make_session_doc(), version=99)))
        self.assertIsNone(doc)
        self.assertIn("version", err)

    def test_missing_events(self):
        bad = make_session_doc()
        del bad["events"]
        doc, err = session_lib.parse_session_json(json.dumps(bad))
        self.assertIsNone(doc)
        self.assertIn("events", err)


class ParseFramesTest(unittest.TestCase):
    def test_lines_and_truncation(self):
        text = (
            json.dumps({"t": 10, "durationMicros": 16000, "tag": "library", "jank": False}) + "\n"
            + json.dumps({"t": 11, "durationMicros": 40000, "tag": "library", "jank": True}) + "\n"
            + '{"t": 12, "durationMic'  # truncated trailing write
        )
        samples, dropped = session_lib.parse_frames_jsonl(text)
        self.assertEqual(len(samples), 2)
        self.assertEqual(dropped, 1)
        self.assertAlmostEqual(samples[0]["durationMs"], 16.0)
        self.assertTrue(samples[1]["jank"])

    def test_blank_and_bad_types(self):
        samples, dropped = session_lib.parse_frames_jsonl("\n\n" + json.dumps({"t": "x", "durationMicros": 5}) + "\n")
        self.assertEqual(samples, [])
        self.assertEqual(dropped, 1)


class IterationStatusTest(unittest.TestCase):
    def test_ok(self):
        doc = make_session_doc(events=[{"name": "scenario_complete", "t": 5}])
        status, err = session_lib.iteration_status(doc, {"runId": "r1", "iteration": "1"})
        self.assertEqual(status, "ok")
        self.assertIsNone(err)

    def test_stale_identity(self):
        doc = make_session_doc()
        status, _ = session_lib.iteration_status(doc, {"runId": "other", "iteration": "1"})
        self.assertEqual(status, "stale")

    def test_deadline_means_incomplete(self):
        doc = make_session_doc(events=[{"name": "scenario_deadline", "t": 5}])
        status, reason = session_lib.iteration_status(doc, {"runId": "r1", "iteration": "1"})
        self.assertEqual(status, "incomplete")
        self.assertIn("deadline", reason)

    def test_missing_completion_is_incomplete(self):
        doc = make_session_doc(events=[{"name": "ui_ready", "t": 3}])
        status, reason = session_lib.iteration_status(doc, {"runId": "r1", "iteration": "1"})
        self.assertEqual(status, "incomplete")
        self.assertIn("scenario_complete", reason)


class ElapsedTest(unittest.TestCase):
    def test_anchor_math(self):
        doc = make_session_doc()
        event = {"name": "ui_ready", "t": 1_002_000_000}
        self.assertAlmostEqual(session_lib.elapsed_ms_since_process_start(doc, event), 2.0)


if __name__ == "__main__":
    unittest.main()
