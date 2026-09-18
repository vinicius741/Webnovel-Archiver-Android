"""Unit tests for pure perf-runner logic. Run via perf_runner.py selftest or
`python3 -m unittest discover scripts/perf/tests` from the repo root."""

import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from perflib import stats  # noqa: E402


class PercentileTest(unittest.TestCase):
    def test_median_odd_and_even(self):
        self.assertEqual(stats.percentile([1, 2, 3], 50), 2.0)
        self.assertEqual(stats.percentile([1, 2, 3, 4], 50), 2.5)

    def test_interpolation(self):
        # numpy-linear: p90 of [0..9] ranks at 8.1 -> 8.1
        self.assertAlmostEqual(stats.percentile(list(range(10)), 90), 8.1)

    def test_empty_and_single(self):
        self.assertIsNone(stats.percentile([], 50))
        self.assertEqual(stats.percentile([7], 95), 7.0)

    def test_bounds(self):
        self.assertEqual(stats.percentile([5, 1, 9], 0), 1.0)
        self.assertEqual(stats.percentile([5, 1, 9], 100), 9.0)


class SummarizeTest(unittest.TestCase):
    def test_summarize(self):
        s = stats.summarize([4, 2, 6])
        self.assertEqual(s["count"], 3)
        self.assertEqual(s["median"], 4.0)
        self.assertAlmostEqual(s["mean"], 4.0)
        self.assertAlmostEqual(s["stdev"], (2 / 3) ** 0.5 * 2)
        self.assertEqual(s["min"], 2)
        self.assertEqual(s["max"], 6)

    def test_empty(self):
        s = stats.summarize([])
        self.assertEqual(s["count"], 0)
        self.assertIsNone(s["median"])

    def test_cv_undefined_for_zero_mean(self):
        self.assertIsNone(stats.coefficient_of_variation([0, 0]))
        self.assertIsNone(stats.coefficient_of_variation([]))


class SlopeTest(unittest.TestCase):
    def test_increasing(self):
        self.assertGreater(stats.slope([(0, 1), (1, 3), (2, 5)]), 0)

    def test_needs_two_points(self):
        self.assertIsNone(stats.slope([(0, 1)]))
        self.assertIsNone(stats.slope([(0, 1), (0, 2)]))


if __name__ == "__main__":
    unittest.main()
