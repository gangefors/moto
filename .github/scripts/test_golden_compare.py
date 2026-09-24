# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for golden_compare.py (python3 -m unittest discover -s .github/scripts)."""

import json
import os
import tempfile
import unittest

import golden_compare as gc


def outcome(name, fav=0.5, curvy=0.1, detour=1.1, failures=()):
    return {
        "name": name,
        "distance_km": 50.0,
        "duration_min": 45.0,
        "fastest_min": 41.0,
        "detour_ratio": detour,
        "favourite_share": fav,
        "curvy_share": curvy,
        "failures": list(failures),
    }


class CompareTest(unittest.TestCase):
    def test_unchanged_routes_pass(self):
        base = [outcome("a"), outcome("b")]
        lines, failed = gc.compare(base, [dict(o) for o in base])
        self.assertEqual(failed, [])
        self.assertEqual(sum("| ok |" in l for l in lines), 2)
        self.assertFalse(any("⚠️" in l for l in lines))

    def test_lower_shares_are_flagged_not_failed(self):
        lines, failed = gc.compare([outcome("a", fav=0.6, curvy=0.2)], [outcome("a", fav=0.4, curvy=0.2)])
        self.assertEqual(failed, [])
        self.assertTrue(any("⚠️ fav -20 pp" in l for l in lines))
        lines, _ = gc.compare([outcome("a", fav=0.4)], [outcome("a", fav=0.6)])
        self.assertTrue(any("fav +20 pp" in l and "⚠️" not in l for l in lines))
        lines, _ = gc.compare([outcome("a", fav=0.4)], [outcome("a", fav=0.405)])
        self.assertTrue(any("| – |" in l for l in lines))

    def test_failures_fail_and_new_routes_are_marked(self):
        lines, failed = gc.compare([outcome("a")], [outcome("a"), outcome("b", failures=["misses x | y"])])
        self.assertEqual(failed, ["b"])
        row = next(l for l in lines if l.startswith("| b "))
        self.assertIn("new", row)
        self.assertIn("❌ misses x / y", row)

    def test_round_trips_show_loops_and_reuse(self):
        def loop(n, reuse):
            return dict(outcome("l", detour=1.0), loops=n, reuse_share=reuse)

        lines, failed = gc.compare([loop(3, 0.02)], [loop(2, 0.06)])
        self.assertEqual(failed, [])
        row = next(l for l in lines if l.startswith("| l "))
        self.assertIn("2 loops, 6 % reuse", row)
        self.assertIn("⚠️ loops -1", row)
        self.assertIn("⚠️ reuse +4 pp", row)
        lines, _ = gc.compare([loop(2, 0.06)], [loop(3, 0.02)])
        row = next(l for l in lines if l.startswith("| l "))
        self.assertIn("loops +1", row)
        self.assertIn("reuse -4 pp", row)
        self.assertNotIn("⚠️", row)
        # Junk in place of a count is ignored, not a crash.
        lines, _ = gc.compare([loop(True, "x")], [loop("3", None)])
        self.assertTrue(any("1.10×" in l or "1.00×" in l for l in lines))

    def test_no_baseline(self):
        lines, failed = gc.compare(None, [outcome("a")])
        self.assertEqual(failed, [])
        self.assertTrue(any("No baseline" in l for l in lines))
        self.assertFalse(any("new" in l for l in lines))


class MainTest(unittest.TestCase):
    def write(self, d, name, data):
        path = os.path.join(d, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(data if isinstance(data, str) else json.dumps(data))
        return path

    def test_exit_codes_and_summary(self):
        with tempfile.TemporaryDirectory() as d:
            ok = self.write(d, "ok.json", [outcome("a")])
            bad = self.write(d, "bad.json", [outcome("a", failures=["x"])])
            broken = self.write(d, "broken.json", "{")
            summary = os.path.join(d, "summary.md")
            self.assertEqual(gc.main(["--current", ok, "--summary", summary]), 0)
            self.assertEqual(gc.main(["--current", bad, "--baseline", ok]), 1)
            self.assertEqual(gc.main(["--current", broken]), 2)
            # A broken baseline is no baseline.
            self.assertEqual(gc.main(["--current", ok, "--baseline", broken]), 0)
            with open(summary, encoding="utf-8") as f:
                self.assertIn("## Golden routes", f.read())

    def test_malformed_outcomes_are_unreadable(self):
        self.assertIsNone(gc.load("/definitely/not/here.json"))
        with tempfile.TemporaryDirectory() as d:
            for data in ({"name": "a"}, [1, 2], [{"no": "name"}]):
                self.assertIsNone(gc.load(self.write(d, "x.json", data)))


if __name__ == "__main__":
    unittest.main()
