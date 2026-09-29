# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Which region files the regions workflow must upload (ADR-0009).

Reads the published signed manifest (a 64-byte signature, then the JSON)
and the new manifest, and prints the file name of every new region whose
compressed file differs from the published one (by SHA-256), or that the
published manifest doesn't list. A region file byte for byte the same as
the published one is left out; when every region is the same, nothing is
printed. Only reads the manifests' JSON, never runs or trusts anything
else in them: the ids name files only after the builder has checked them.

    python3 regions_changed.py published.manifest regions-vN.json <dir>
    python3 regions_changed.py --same-sources published.json current.json

The second form prints the ids whose sources (extract, border polygon,
builder) are the same as when their published region was built: those
need no new download or build (2026-09-29).
"""

import json
import os
import re
import sys

SIGNATURE_BYTES = 64
ID = re.compile(r"^[a-z0-9-]{1,32}$")


def published_hashes(signed: bytes) -> dict:
    """id -> gz_sha256 of a signed manifest; empty if it doesn't parse."""
    try:
        doc = json.loads(signed[SIGNATURE_BYTES:].decode("utf-8"))
        return {
            r["id"]: r["gz_sha256"]
            for r in doc["regions"]
            if isinstance(r.get("id"), str) and isinstance(r.get("gz_sha256"), str)
        }
    except (ValueError, KeyError, TypeError, AttributeError):
        return {}


def changed(published: dict, manifest: dict) -> list:
    """File names of the regions in `manifest` to upload."""
    major = int(manifest["format_major"])
    out = []
    for r in manifest["regions"]:
        rid = r["id"]
        if not ID.match(rid):
            raise ValueError(f"bad region id {rid!r}")
        if published.get(rid) != r["gz_sha256"]:
            out.append(f"{rid}-v{major}.region.gz")
    return out


def same_sources(published: dict, current: dict) -> list:
    """Ids whose source fingerprint equals the published one, sorted."""
    return sorted(
        rid
        for rid, src in current.items()
        if ID.match(rid) and isinstance(src, str) and published.get(rid) == src
    )


def read_json(path: str):
    """The JSON in `path`, or {} when it is missing or unreadable."""
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        return doc if isinstance(doc, dict) else {}
    except (OSError, ValueError):
        return {}


def main(argv: list) -> int:
    if len(argv) == 4 and argv[1] == "--same-sources":
        for rid in same_sources(read_json(argv[2]), read_json(argv[3])):
            print(rid)
        return 0
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], "rb") as f:
        published = published_hashes(f.read())
    with open(argv[2], encoding="utf-8") as f:
        manifest = json.load(f)
    for name in changed(published, manifest):
        if not os.path.isfile(os.path.join(argv[3], name)):
            raise FileNotFoundError(name)
        print(name)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
