#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""The app's third-party licence notices (ADR-0004), built at every Gradle
build so they always match what ships in the APK.

Rust crates come from `cargo tree` of moto-ffi for the Android targets
(normal dependencies, no proc-macros: what is linked into the library),
with the licence files each crate ships (paths from `cargo metadata`).
Android libraries come from the app's runtime classpath (one
`group:name:version` per line), with the licences their POMs declare
(following parent POMs) and the LICENSE/NOTICE files inside their archives.
Gradle resolves the POMs and archives and lists their paths (`--artifacts`);
they lie in its cache as `files-2.1/<group>/<name>/<version>/<hash>/<file>`,
which is how each file is matched to its component.

When a component ships no licence text, a kept copy is used: a standard
text by SPDX id (`<licenses>/<id>.txt`) or, for Maven components,
`<licenses>/maven/<group>__<name>__<version>.txt` (kept per version, so a
version bump fails the build until the notice is updated). A component
whose licence can't be determined or whose text can't be found fails the
build: nothing ships without its notice.

Identical texts (up to whitespace) are written once and referenced by
number. The output is plain text: "## " and "### " start headings, blank
lines separate paragraphs.
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

# Top-level files of a crate or archive entries that hold licence notices.
NOTICE_PREFIXES = ("LICENSE", "LICENCE", "COPYING", "COPYRIGHT", "NOTICE", "UNLICENSE")
# Largest notice file read (a guard against odd archives).
MAX_NOTICE_BYTES = 512 * 1024
# Parent POMs followed at most.
MAX_POM_DEPTH = 5
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class LicenceError(Exception):
    """A component whose licence or licence text can't be found."""


def is_notice(name):
    return os.path.basename(name).upper().startswith(NOTICE_PREFIXES)


def parse_cargo_tree(text):
    """(name, version) of the crates in `cargo tree --prefix none --format
    "{p}|{l}"` output, without local crates (ours, AGPL) or repeats."""
    crates = set()
    for line in text.splitlines():
        package = line.split("|", 1)[0].strip()
        if not package or "(/" in package or "(\\" in package or re.search(r"\([A-Za-z]:", package):
            continue
        parts = package.replace(" (*)", "").split()
        if len(parts) < 2 or not parts[1].startswith("v"):
            raise LicenceError(f"unexpected cargo tree line: {line!r}")
        crates.add((parts[0], parts[1][1:]))
    return crates


def crate_index(metadata):
    """{(name, version): (licence expression, crate directory)} from
    `cargo metadata` JSON."""
    return {
        (p["name"], p["version"]): (p.get("license") or "", os.path.dirname(p["manifest_path"]))
        for p in metadata["packages"]
    }


def read_text(data, what):
    if len(data) > MAX_NOTICE_BYTES:
        raise LicenceError(f"{what}: larger than {MAX_NOTICE_BYTES} bytes")
    return data.decode("utf-8", errors="replace")


def crate_notices(directory):
    """(file name, text) of the licence files at the top of a crate."""
    out = []
    for name in sorted(os.listdir(directory)):
        path = os.path.join(directory, name)
        if is_notice(name) and os.path.isfile(path):
            with open(path, "rb") as f:
                out.append((name, read_text(f.read(MAX_NOTICE_BYTES + 1), path)))
    return out


def spdx_ids(expression):
    """The licence ids in an SPDX expression (or crates.io's old "A/B")."""
    words = re.split(r"[\s()/]+", expression)
    return [w for w in words if w and w not in ("OR", "AND", "WITH") and not w.endswith("-exception")]


def standard_text(licenses_dir, spdx_id):
    path = os.path.join(licenses_dir, spdx_id + ".txt")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


def spdx_of_pom(name, url):
    """The SPDX id of a POM <license>, or None when unknown."""
    text = f"{name} {url}".lower()
    if "apache" in text and "2.0" in text:
        return "Apache-2.0"
    if "bsd-2-clause" in text:
        return "BSD-2-Clause"
    if "bsd-3-clause" in text:
        return "BSD-3-Clause"
    if "lgpl-2.1" in text:
        return "LGPL-2.1-or-later" if "later" in text or "+" in text else "LGPL-2.1-only"
    if "mit" in re.split(r"[^a-z]+", text):
        return "MIT"
    return None


def index_artifacts(paths):
    """{(group, name, version): {"pom": path or None, "archives": [paths]}}
    for files in Gradle's cache layout; other paths are ignored."""
    index = {}
    for path in paths:
        parts = os.path.normpath(path).split(os.sep)
        if len(parts) < 6 or parts[-6] != "files-2.1":
            continue
        entry = index.setdefault((parts[-5], parts[-4], parts[-3]), {"pom": None, "archives": []})
        if path.endswith(".pom"):
            entry["pom"] = path
        elif path.endswith((".aar", ".jar")) and not path.endswith(("-sources.jar", "-javadoc.jar")):
            entry["archives"].append(path)
    for entry in index.values():
        entry["archives"].sort()
    return index


def pom_licences(artifacts, group, name, version, depth=0):
    """[(spdx id, name, url)] declared by a POM or, when it declares none,
    its nearest parent."""
    pom = artifacts.get((group, name, version), {}).get("pom")
    if pom is None:
        raise LicenceError(f"{group}:{name}:{version}: no POM resolved")
    root = ET.parse(pom).getroot()
    out = []
    for lic in root.findall("m:licenses/m:license", POM_NS):
        lname = (lic.findtext("m:name", default="", namespaces=POM_NS) or "").strip()
        url = (lic.findtext("m:url", default="", namespaces=POM_NS) or "").strip()
        out.append((spdx_of_pom(lname, url), lname, url))
    parent = root.find("m:parent", POM_NS)
    if not out and parent is not None and depth < MAX_POM_DEPTH:
        pg, pa, pv = (parent.findtext(f"m:{k}", default="", namespaces=POM_NS).strip() for k in ("groupId", "artifactId", "version"))
        return pom_licences(artifacts, pg, pa, pv, depth + 1)
    return out


def archive_notices(artifacts, group, name, version):
    """(entry, text) of the licence files inside a component's .aar/.jar."""
    out = []
    for path in artifacts.get((group, name, version), {}).get("archives", []):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if not info.is_dir() and is_notice(info.filename):
                    if info.file_size > MAX_NOTICE_BYTES:
                        raise LicenceError(f"{path}!{info.filename}: larger than {MAX_NOTICE_BYTES} bytes")
                    out.append((info.filename, read_text(z.read(info), info.filename)))
    return out


def kept_notice(licenses_dir, group, name, version):
    path = os.path.join(licenses_dir, "maven", f"{group}__{name}__{version}.txt")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


class Texts:
    """Licence texts, each written once, numbered in order of first use."""

    def __init__(self):
        self.order = []  # [(title, text, users)]
        self.by_key = {}

    def add(self, title, text, user):
        key = " ".join(text.split())
        if key not in self.by_key:
            self.by_key[key] = len(self.order)
            self.order.append((title, text.strip("\n"), []))
        i = self.by_key[key]
        if user not in self.order[i][2]:
            self.order[i][2].append(user)
        return i + 1


def crate_entries(crates, index, licenses_dir, texts):
    lines = []
    for name, version in sorted(crates):
        if (name, version) not in index:
            raise LicenceError(f"crate {name} {version}: not in cargo metadata")
        expression, directory = index[(name, version)]
        if not expression:
            raise LicenceError(f"crate {name} {version}: no licence in its manifest")
        refs = [texts.add(f, t, name) for f, t in crate_notices(directory)]
        if not refs:
            for spdx_id in spdx_ids(expression):
                t = standard_text(licenses_dir, spdx_id)
                if t is not None:
                    refs.append(texts.add(spdx_id, t, name))
        if not refs:
            raise LicenceError(f"crate {name} {version} ({expression}): no licence text")
        lines += [
            f"{name} {version}",
            f"Licence: {expression} · https://crates.io/crates/{name}/{version}",
            "Texts: " + ", ".join(str(r) for r in sorted(set(refs))),
            "",
        ]
    return lines


def maven_entries(components, artifacts, licenses_dir, texts):
    lines = []
    for group, name, version in sorted(components):
        coords = f"{group}:{name}:{version}"
        licences = pom_licences(artifacts, group, name, version)
        ids = [i for i, _, _ in licences]
        if not ids or None in ids:
            raise LicenceError(f"{coords}: unknown licence {[n for _, n, _ in licences]}")
        who = f"{group}:{name}"
        refs = [texts.add(e, t, who) for e, t in archive_notices(artifacts, group, name, version)]
        kept = kept_notice(licenses_dir, group, name, version)
        if kept is not None:
            refs.append(texts.add(f"{name} notice", kept, who))
        if not refs:
            # POM licences are alternatives (e.g. JNA: LGPL or Apache).
            for spdx_id in ids:
                t = standard_text(licenses_dir, spdx_id)
                if t is not None:
                    refs.append(texts.add(spdx_id, t, who))
        if not refs:
            raise LicenceError(f"{coords} ({' OR '.join(ids)}): no licence text; keep one in licenses/maven/")
        lines += [
            f"{group}:{name} {version}",
            f"Licence: {' OR '.join(ids)} · Maven {coords}",
            "Texts: " + ", ".join(str(r) for r in sorted(set(refs))),
            "",
        ]
    return lines


def parse_maven_list(text):
    out = set()
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(":")
        if len(parts) != 3 or not all(parts):
            raise LicenceError(f"not group:name:version: {line!r}")
        out.add(tuple(parts))
    return out


def body(text):
    """A licence text as written, but no line of it can pass for one of our
    headings (Markdown notices have their own)."""
    return "\n".join(" " + l if l.startswith("#") else l for l in text.split("\n"))


def build(crates, index, components, artifacts, licenses_dir, app_licence):
    texts = Texts()
    crate_lines = crate_entries(crates, index, licenses_dir, texts)
    maven_lines = maven_entries(components, artifacts, licenses_dir, texts)
    out = [
        "## moto",
        "",
        "moto is free software: you can redistribute it and/or modify it under the terms of the "
        "GNU Affero General Public License, version 3 only (AGPL-3.0-only). "
        "Source code: https://github.com/gangefors/moto",
        "",
        "### GNU Affero General Public License",
        "",
        body(app_licence.strip("\n")),
        "",
        f"## Rust crates ({len(crates)})",
        "",
        "Built into the routing core.",
        "",
        *crate_lines,
        f"## Android libraries ({len(components)})",
        "",
        *maven_lines,
        f"## Licence texts ({len(texts.order)})",
        "",
    ]
    for i, (title, text, users) in enumerate(texts.order, 1):
        out += [f"### {i} · {title}", "", "Used by: " + ", ".join(users), "", body(text), ""]
    return "\n".join(out).rstrip("\n") + "\n"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cargo-tree", action="append", required=True, help="cargo tree output (one per target)")
    p.add_argument("--cargo-metadata", required=True, help="cargo metadata JSON")
    p.add_argument("--maven", required=True, help="group:name:version per line")
    p.add_argument("--artifacts", required=True, help="paths of the resolved POMs and archives, one per line")
    p.add_argument("--licenses", required=True, help="kept licence texts (android/app/licenses)")
    p.add_argument("--app-licence", required=True, help="the app's LICENSE file")
    p.add_argument("--out", required=True)
    args = p.parse_args(argv)
    try:
        crates = set()
        for path in args.cargo_tree:
            with open(path, encoding="utf-8") as f:
                crates |= parse_cargo_tree(f.read())
        with open(args.cargo_metadata, encoding="utf-8") as f:
            index = crate_index(json.load(f))
        with open(args.maven, encoding="utf-8") as f:
            components = parse_maven_list(f.read())
        with open(args.artifacts, encoding="utf-8") as f:
            artifacts = index_artifacts([l.strip() for l in f if l.strip()])
        with open(args.app_licence, encoding="utf-8") as f:
            app_licence = f.read()
        text = build(crates, index, components, artifacts, args.licenses, app_licence)
    except (LicenceError, OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as e:
        print(f"third-party licences: {e}", file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
