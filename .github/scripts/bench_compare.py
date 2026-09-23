#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Compare two `moto-regionbuild --check --json` results.

Timings are divided by each run's CPU calibration time before comparing,
so builds on faster or slower CI machines stay comparable. A metric more
than 25 % slower than the baseline is a significant regression and makes
the script exit with status 1, unless --accepted is given (the commit
carries a `Perf-Accepted:` trailer). See "Testing and performance" in
CLAUDE.md.
"""

import argparse
import json
import sys

# (key, label) of the timings compared; lower is better.
METRICS = [
    ("verify_ms", "Verify region (CRC + structure)"),
    ("open_ms", "Open region"),
    ("snap_us_mean", "Snap, mean"),
    ("route_ms_mean", "Route, mean"),
    ("route_ms_p95", "Route, p95"),
]
REGRESSION = 1.25  # more than 25 % slower fails
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


def verdict(ratio):
    if ratio > REGRESSION:
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
        lines += ["No baseline from `main` yet; this run becomes the first one.", ""]
        lines += ["| Metric | This build |", "| --- | ---: |"]
        for key, label in METRICS:
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
    for key, label in METRICS:
        b, c = scaled(baseline, key), scaled(current, key)
        if b is None or c is None or b == 0:
            lines.append(f"| {label} | – | – | – | not comparable |")
            continue
        ratio = c / b
        if ratio > REGRESSION:
            regressed.append(label)
        lines.append(
            f"| {label} | {baseline[key]:.2f} | {current[key]:.2f} | "
            f"{(ratio - 1) * 100:+.0f} % | {verdict(ratio)} |"
        )
    return lines, regressed


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("baseline")
    p.add_argument("current")
    p.add_argument("--summary", help="file to append the markdown table to")
    p.add_argument("--accepted", action="store_true", help="a Perf-Accepted trailer is present")
    args = p.parse_args(argv)

    current = load(args.current)
    if current is None:
        print(f"error: cannot read {args.current}", file=sys.stderr)
        return 2
    lines, regressed = compare(load(args.baseline), current)
    if regressed:
        if args.accepted:
            lines += ["", "Regression accepted with a `Perf-Accepted:` trailer: " + ", ".join(regressed) + "."]
        else:
            lines += [
                "",
                "**Significant regression** (more than 25 % slower): " + ", ".join(regressed) + ". "
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
