#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Before/after table of the golden routes (`moto-regionbuild --golden
--json`): this build's figures next to the last main build's, run on the
same case files and region.

Flags every route whose favourite or curvy share dropped (by more than
one percentage point) or that fails its expectations. Exits with status 1
when any route fails its expectations in this build; a drop in share alone
is reported, not failed (see the route-scoring procedure: judge it).
"""

import argparse
import json
import sys

# Share changes smaller than this (0-1 scale) count as unchanged.
TOLERANCE = 0.01


def load(path):
    """A list of outcome dicts, or None if the file is missing or broken."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return None
    if not isinstance(data, list) or not all(isinstance(o, dict) and isinstance(o.get("name"), str) for o in data):
        return None
    return data


def num(o, key):
    v = o.get(key)
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else 0.0


def cell(v):
    """Markdown-safe text: no pipes or line breaks from the case files."""
    return str(v).replace("|", "/").replace("\n", " ")


def compare(baseline, current):
    """Returns (markdown lines, names of failed routes)."""
    before = {o["name"]: o for o in baseline or []}
    lines = ["## Golden routes", ""]
    if baseline is None:
        lines += ["No baseline (the last main build has no golden routes yet, or couldn't run them).", ""]
    lines += [
        "| Route | km | min | Detour | Fav % | Curvy % | Change | Result |",
        "| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |",
    ]
    failed = []
    for o in current:
        name = o["name"]
        failures = o.get("failures") if isinstance(o.get("failures"), list) else []
        notes = []
        b = before.get(name)
        if b is not None:
            for key, label in (("favourite_share", "fav"), ("curvy_share", "curvy")):
                d = num(o, key) - num(b, key)
                if d < -TOLERANCE:
                    notes.append(f"⚠️ {label} {d * 100:+.0f} pp")
                elif d > TOLERANCE:
                    notes.append(f"{label} {d * 100:+.0f} pp")
            dd = num(o, "detour_ratio") - num(b, "detour_ratio")
            if abs(dd) > TOLERANCE:
                notes.append(f"detour {dd:+.2f}")
        elif baseline is not None:
            notes.append("new")
        if failures:
            failed.append(name)
        result = "❌ " + "; ".join(cell(f) for f in failures) if failures else "ok"
        lines.append(
            f"| {cell(name)} | {num(o, 'distance_km'):.1f} | {num(o, 'duration_min'):.1f} | "
            f"{num(o, 'detour_ratio'):.2f}× | {num(o, 'favourite_share') * 100:.0f} | "
            f"{num(o, 'curvy_share') * 100:.0f} | {', '.join(notes) or '–'} | {result} |"
        )
    return lines, failed


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--current", required=True, help="golden outcomes of this build")
    p.add_argument("--baseline", help="golden outcomes of the last main build")
    p.add_argument("--summary", help="file to append the markdown table to")
    args = p.parse_args(argv)

    current = load(args.current)
    if current is None:
        print(f"error: cannot read {args.current}", file=sys.stderr)
        return 2
    lines, failed = compare(load(args.baseline) if args.baseline else None, current)
    if failed:
        lines += ["", f"**{len(failed)} golden route(s) fail their expectations.**"]
    text = "\n".join(lines) + "\n"
    print(text)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
