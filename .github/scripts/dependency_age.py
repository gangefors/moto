# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Checks that the Gradle versions a pull request moves to are old enough.

Dependabot's cooldown can't hold for Gradle dependencies from Google's Maven
repository, which gives it no release dates (it opened AGP 9.4.1 five days
after the release). This reads the version catalog before and after the
change, finds each changed version's artifacts, and takes their publication
date from the repository (the POM's Last-Modified). A version is old enough
once every artifact is at least --min-days old (CLAUDE.md, Versions); a date
that can't be found counts as too new.

Prints one JSON object: {"ok": bool, "description": str, "lines": [str]}.
The description fits a GitHub commit status (140 characters).

    python3 dependency_age.py --base old.toml --head new.toml [--min-days 7]
"""

import argparse
import datetime as dt
import email.utils
import json
import sys
import tomllib
import urllib.error
import urllib.request

REPOSITORIES = (
    "https://dl.google.com/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://plugins.gradle.org/m2",
)


def changed_versions(base: dict, head: dict) -> dict[str, str]:
    """Version keys whose value is new or different in head: key -> new version."""
    old = base.get("versions", {})
    return {k: v for k, v in head.get("versions", {}).items() if old.get(k) != v}


def artifacts_for(catalog: dict, key: str) -> list[tuple[str, str]]:
    """(group, name) of every library and plugin (its marker) using version key."""
    def uses(entry) -> bool:
        version = entry.get("version") if isinstance(entry, dict) else None
        return isinstance(version, dict) and version.get("ref") == key

    found = []
    for lib in catalog.get("libraries", {}).values():
        if uses(lib) and isinstance(lib.get("module"), str):
            group, _, name = lib["module"].partition(":")
            found.append((group, name))
    for plugin in catalog.get("plugins", {}).values():
        if uses(plugin) and isinstance(plugin.get("id"), str):
            found.append((plugin["id"], plugin["id"] + ".gradle.plugin"))
    return found


def pom_date(group: str, name: str, version: str, fetch) -> dt.datetime | None:
    """Publication date of group:name:version from the first repository that has it."""
    path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"
    for repo in REPOSITORIES:
        modified = fetch(f"{repo}/{path}")
        if modified:
            try:
                return email.utils.parsedate_to_datetime(modified)
            except (TypeError, ValueError):
                return None
    return None


def last_modified(url: str) -> str | None:
    """The Last-Modified header of url, or None if it isn't there."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "moto-dependency-age"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.headers.get("Last-Modified")
    except (urllib.error.URLError, TimeoutError):
        return None


def check(base: dict, head: dict, now: dt.datetime, min_days: int, fetch) -> dict:
    lines = []
    waits = []  # (ready date, text)
    for key, version in sorted(changed_versions(base, head).items()):
        artifacts = artifacts_for(head, key)
        if not artifacts:
            lines.append(f"{key} {version}: not used by any library or plugin, not checked")
            continue
        dates = [pom_date(g, n, version, fetch) for g, n in artifacts]
        if any(d is None for d in dates):
            missing = [f"{g}:{n}" for (g, n), d in zip(artifacts, dates) if d is None]
            lines.append(f"{key} {version}: no release date found for {', '.join(missing)}")
            waits.append((None, f"{key} {version}: release date unknown"))
            continue
        released = max(dates)
        age = (now - released).days
        ready = (released + dt.timedelta(days=min_days)).date()
        lines.append(f"{key} {version}: released {released.date()}, {age} days ago")
        if now - released < dt.timedelta(days=min_days):
            waits.append((ready, f"{key} {version} is {age} days old, OK from {ready}"))
    if not waits:
        description = f"All updated releases are at least {min_days} days old"
        return {"ok": True, "description": description, "lines": lines}
    description = "; ".join(text for _, text in waits)
    if len(description) > 140:
        description = description[:137] + "..."
    return {"ok": False, "description": description, "lines": lines}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--base", required=True, help="version catalog before the change")
    p.add_argument("--head", required=True, help="version catalog after the change")
    p.add_argument("--min-days", type=int, default=7)
    args = p.parse_args(argv)
    with open(args.base, "rb") as f:
        base = tomllib.load(f)
    with open(args.head, "rb") as f:
        head = tomllib.load(f)
    result = check(base, head, dt.datetime.now(dt.timezone.utc), args.min_days, last_modified)
    json.dump(result, sys.stdout)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
