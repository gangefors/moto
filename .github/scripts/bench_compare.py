#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Compare `moto-regionbuild --check --json` results of the baseline and
this build.

CI runs the baseline build's benchmark binary and this build's binary
alternately on the same machine, so machine differences cancel out.
Timings are also divided by each run's CPU calibration time. Several
results may be given per side (repeated runs); each metric uses its
fastest.

A significant regression makes the script exit with status 1, unless
--accepted is given (the commit carries a `Perf-Accepted:` trailer):
more than 25 % slower for the CPU-bound snapping and routing, and more
than 50 % and 5 ms slower for the short verify and open timings, which
depend on memory and disk and vary more between CI machines. See
"Testing and performance" in CLAUDE.md.
"""

import argparse
import json
import sys

# (key, label, regression ratio, minimum raw increase) of the timings
# compared; lower is better. A metric regresses when it is both more than
# `ratio` times the baseline (after calibration) and at least `floor`
# (in its own unit) slower.
METRICS = [
    ("verify_ms", "Verify region (CRC + structure)", 1.50, 5.0),
    ("open_ms", "Open region", 1.50, 5.0),
    ("snap_us_mean", "Snap, mean", 1.25, 0.0),
    ("route_ms_mean", "Route, mean", 1.25, 0.0),
    ("route_ms_p95", "Route, p95", 1.25, 0.0),
]
WARNING = 1.10
IMPROVEMENT = 0.90


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def scaled(data, key):
    """A timing divided by the run's calibration time, or None if unusable."""
    value, calib = data.get(key), data.get("calibration_ms")
    numbers = all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in (value, calib))
    if not numbers or calib <= 0 or value < 0:
        return None
    return value / calib


def fastest(results):
    """Merges repeated runs: the fastest value of every timing, and the
    calibration of the run with the fastest calibration."""
    runs = [r for r in results if r is not None]
    if not runs:
        return None
    merged = dict(min(runs, key=lambda r: r.get("calibration_ms", float("inf"))))
    for key, *_ in METRICS:
        # Compare each run's timing after scaling by its own calibration.
        best = min(runs, key=lambda r: scaled(r, key) if scaled(r, key) is not None else float("inf"))
        if scaled(best, key) is not None:
            merged[key] = scaled(best, key) * merged["calibration_ms"]
    return merged


def verdict(ratio, regressed):
    if regressed:
        return "❌ significantly slower"
    if ratio > WARNING:
        return "⚠️ slower"
    if ratio < IMPROVEMENT:
        return "✅ faster"
    return "≈ unchanged"


def compare(baseline, current):
    """Returns (markdown lines, list of regressed metric labels)."""
    lines = ["## Performance", ""]
    if baseline is None:
        lines += [
            "No baseline to compare with (no earlier main build, or its benchmark "
            "could not read this region file); this build becomes the baseline.",
            "",
        ]
        lines += ["| Metric | This build |", "| --- | ---: |"]
        for key, label, *_ in METRICS:
            lines.append(f"| {label} | {current.get(key, 0):.2f} |")
        return lines, []

    if (baseline.get("edges"), baseline.get("osm_timestamp")) != (
        current.get("edges"),
        current.get("osm_timestamp"),
    ):
        lines += [
            "Note: the region data changed since the baseline "
            f"({baseline.get('edges')} → {current.get('edges')} edges), "
            "so the comparison is approximate.",
            "",
        ]
    lines += [
        "Timings scaled by each run's CPU calibration "
        f"(baseline {baseline.get('calibration_ms', 0):.0f} ms, "
        f"this build {current.get('calibration_ms', 0):.0f} ms).",
        "",
        "| Metric | Baseline | This build | Change | |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    regressed = []
    for key, label, limit, floor in METRICS:
        b, c = scaled(baseline, key), scaled(current, key)
        if b is None or c is None or b == 0:
            lines.append(f"| {label} | – | – | – | not comparable |")
            continue
        ratio = c / b
        # The raw increase, at this build's machine speed.
        increase = (c - b) * current["calibration_ms"]
        bad = ratio > limit and increase >= floor
        if bad:
            regressed.append(label)
        lines.append(
            f"| {label} | {baseline[key]:.2f} | {current[key]:.2f} | "
            f"{(ratio - 1) * 100:+.0f} % | {verdict(ratio, bad)} |"
        )
    return lines, regressed


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", nargs="+", default=[], help="results of the baseline build")
    p.add_argument("--current", nargs="+", required=True, help="results of this build")
    p.add_argument("--summary", help="file to append the markdown table to")
    p.add_argument("--accepted", action="store_true", help="a Perf-Accepted trailer is present")
    args = p.parse_args(argv)

    current = fastest(load(c) for c in args.current)
    if current is None:
        print(f"error: cannot read {', '.join(args.current)}", file=sys.stderr)
        return 2
    lines, regressed = compare(fastest(load(b) for b in args.baseline), current)
    if regressed:
        if args.accepted:
            lines += ["", "Regression accepted with a `Perf-Accepted:` trailer: " + ", ".join(regressed) + "."]
        else:
            lines += [
                "",
                "**Significant regression**: " + ", ".join(regressed) + ". "
                "Re-evaluate the implementation; accept only with a `Perf-Accepted: <reason>` "
                "trailer if the cost buys something worth it.",
            ]
    text = "\n".join(lines) + "\n"
    print(text)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 1 if regressed and not args.accepted else 0


if __name__ == "__main__":
    sys.exit(main())
