# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for bench_compare.py (python3 -m unittest discover -s .github/scripts)."""

import json
import os
import tempfile
import unittest

import bench_compare as bc

BASE = {
    "calibration_ms": 100.0,
    "edges": 411401,
    "osm_timestamp": 1790126972,
    "verify_ms": 20.0,
    "open_ms": 12.0,
    "snap_us_mean": 5.0,
    "route_ms_mean": 15.0,
    "route_ms_p95": 40.0,
    "fav_route_ms_mean": 30.0,
    "fav_route_ms_p95": 90.0,
    "match_ms_per_km": 0.3,
}


def with_(**changes):
    d = dict(BASE)
    d.update(changes)
    return d


class CompareTest(unittest.TestCase):
    def test_same_numbers_pass(self):
        lines, regressed = bc.compare(BASE, dict(BASE))
        self.assertEqual(regressed, [])
        self.assertEqual(sum("≈ unchanged" in l for l in lines), len(bc.METRICS))

    def test_a_metric_new_since_the_baseline_is_not_comparable(self):
        old = {k: v for k, v in BASE.items() if not k.startswith("fav_")}
        lines, regressed = bc.compare(old, with_(fav_route_ms_mean=500.0))
        self.assertEqual(regressed, [])
        self.assertEqual(sum("not comparable" in l for l in lines), 2)

    def test_significant_regression_is_flagged(self):
        _, regressed = bc.compare(BASE, with_(route_ms_mean=19.5))
        self.assertEqual(regressed, ["Route, mean"])

    def test_small_slowdown_only_warns(self):
        lines, regressed = bc.compare(BASE, with_(route_ms_mean=17.0))
        self.assertEqual(regressed, [])
        self.assertTrue(any("⚠️" in l for l in lines))

    def test_short_io_timings_need_a_large_absolute_change(self):
        # +46 % on a 7.6 ms verify (as seen on CI with no code change): noise.
        noisy = with_(verify_ms=20.0 * 1.46, open_ms=12.0 * 1.37)
        self.assertEqual(bc.compare(BASE, noisy)[1], [])
        # Double the open time and more than 5 ms slower: real.
        self.assertEqual(bc.compare(BASE, with_(open_ms=24.5))[1], ["Open region"])
        # Over 50 % but under 5 ms: still noise.
        small = with_(open_ms=3.0)
        self.assertEqual(bc.compare(small, with_(open_ms=5.0))[1], [])

    def test_repeated_runs_use_the_fastest(self):
        slow = with_(route_ms_mean=30.0)
        merged = bc.fastest([slow, dict(BASE), None])
        self.assertEqual(merged["route_ms_mean"], 15.0)
        self.assertEqual(bc.compare(BASE, merged)[1], [])
        self.assertIsNone(bc.fastest([None]))
        # Runs on machines of different speed are compared after scaling.
        fast_machine = {k: (v / 2 if isinstance(v, float) else v) for k, v in BASE.items()}
        merged = bc.fastest([with_(snap_us_mean=9.0), fast_machine])
        self.assertAlmostEqual(merged["snap_us_mean"] / merged["calibration_ms"], 5.0 / 100.0)

    def test_improvement_is_reported(self):
        lines, _ = bc.compare(BASE, with_(snap_us_mean=3.0))
        self.assertTrue(any("✅ faster" in l and "-40 %" in l for l in lines))

    def test_calibration_scales_timings(self):
        # A machine twice as slow: everything doubles, nothing regresses.
        slow = {k: (v * 2 if isinstance(v, float) else v) for k, v in BASE.items()}
        _, regressed = bc.compare(BASE, slow)
        self.assertEqual(regressed, [])
        # Same machine speed but routing twice as slow does regress.
        _, regressed = bc.compare(BASE, with_(route_ms_p95=80.0))
        self.assertEqual(regressed, ["Route, p95"])

    def test_no_baseline_passes(self):
        lines, regressed = bc.compare(None, BASE)
        self.assertEqual(regressed, [])
        self.assertTrue(any("No baseline" in l for l in lines))

    def test_changed_region_is_noted(self):
        lines, _ = bc.compare(BASE, with_(edges=400000))
        self.assertTrue(any("region data changed" in l for l in lines))

    def test_map_matching_regressions_are_flagged(self):
        _, regressed = bc.compare(BASE, with_(match_ms_per_km=0.4))
        self.assertEqual(regressed, ["Map matching, per km"])

    def test_a_baseline_without_a_new_metric_is_not_comparable_there(self):
        old = {k: v for k, v in BASE.items() if k != "match_ms_per_km"}
        lines, regressed = bc.compare(old, dict(BASE))
        self.assertEqual(regressed, [])
        self.assertTrue(any("Map matching" in l and "not comparable" in l for l in lines))

    def test_bad_values_are_not_comparable(self):
        for bad in ({"calibration_ms": 0}, {"route_ms_mean": "x"}, {"verify_ms": -1.0}, {"open_ms": True}):
            lines, regressed = bc.compare(BASE, with_(**bad))
            self.assertEqual(regressed, [])
            self.assertTrue(any("not comparable" in l for l in lines))


class MainTest(unittest.TestCase):
    def run_main(self, baseline, current, *extra):
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for name, data in (("b.json", baseline), ("c.json", current)):
                path = os.path.join(d, name)
                if data is not None:
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(data if isinstance(data, str) else json.dumps(data))
                paths.append(path)
            summary = os.path.join(d, "summary.md")
            args = ["--baseline", paths[0], "--current", paths[1], "--summary", summary]
            code = bc.main([*args, *extra])
            with open(summary, encoding="utf-8") as f:
                return code, f.read()

    def test_exit_codes(self):
        self.assertEqual(self.run_main(BASE, BASE)[0], 0)
        code, text = self.run_main(BASE, with_(route_ms_p95=60.0))
        self.assertEqual(code, 1)
        self.assertIn("Significant regression", text)
        code, text = self.run_main(BASE, with_(route_ms_p95=60.0), "--accepted")
        self.assertEqual(code, 0)
        self.assertIn("accepted", text)

    def test_two_current_runs_keep_the_better_one(self):
        with tempfile.TemporaryDirectory() as d:
            files = []
            for name, data in (("b", BASE), ("c1", with_(route_ms_p95=60.0)), ("c2", BASE)):
                path = os.path.join(d, name)
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(data, f)
                files.append(path)
            self.assertEqual(bc.main(["--baseline", files[0], "--current", *files[1:]]), 0)
            self.assertEqual(bc.main(["--baseline", files[0], "--current", files[1]]), 1)

    def test_baseline_runs_are_merged_too(self):
        with tempfile.TemporaryDirectory() as d:
            files = []
            for name, data in (("b1", with_(route_ms_p95=90.0)), ("b2", BASE), ("c", with_(route_ms_p95=60.0))):
                path = os.path.join(d, name)
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(data, f)
                files.append(path)
            # The faster baseline run (40 ms) counts, so 60 ms regresses.
            self.assertEqual(bc.main(["--baseline", *files[:2], "--current", files[2]]), 1)

    def test_no_baseline_at_all_passes(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "c")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(BASE, f)
            self.assertEqual(bc.main(["--current", path]), 0)

    def test_missing_or_broken_baseline_passes(self):
        self.assertEqual(self.run_main(None, BASE)[0], 0)
        self.assertEqual(self.run_main("{not json", BASE)[0], 0)
        self.assertEqual(self.run_main("[1, 2]", BASE)[0], 0)

    def test_unreadable_current_is_an_error(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(bc.main(["--baseline", os.path.join(d, "b"), "--current", os.path.join(d, "c")]), 2)


if __name__ == "__main__":
    unittest.main()
