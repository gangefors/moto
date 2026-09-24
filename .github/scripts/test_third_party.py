# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for third_party.py (python3 -m unittest discover -s .github/scripts)."""

import json
import os
import tempfile
import unittest
import zipfile

import third_party as tp

POM = """<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  {parent}
  <licenses>{licences}</licenses>
</project>"""


def licence(name, url=""):
    return f"<license><name>{name}</name><url>{url}</url></license>"


class Fixture:
    """A temp tree: crates, a Gradle cache and kept licence texts."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.cache = os.path.join(self.root, "caches", "modules-2", "files-2.1")
        self.licenses = os.path.join(self.root, "licenses")
        os.makedirs(os.path.join(self.licenses, "maven"))
        self.write("licenses/Apache-2.0.txt", "Apache License\nVersion 2.0\n")
        self.write("licenses/MPL-2.0.txt", "Mozilla Public License Version 2.0\n")
        self.packages = []

    def write(self, rel, text, mode="w"):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, mode) as f:
            f.write(text)
        return path

    def crate(self, name, version, expression, files=None):
        d = f"crates/{name}-{version}"
        manifest = self.write(f"{d}/Cargo.toml", "[package]\n")
        for fname, text in (files or {}).items():
            self.write(f"{d}/{fname}", text)
        self.write(f"{d}/src/LICENSE-not-top-level", "ignored")
        self.packages.append({"name": name, "version": version, "license": expression, "manifest_path": manifest})

    def maven(self, group, name, version, licences, parent=None, entries=None):
        d = os.path.join(self.cache, group, name, version, "abc123")
        os.makedirs(d, exist_ok=True)
        p = ""
        if parent:
            g, a, v = parent
            p = f"<parent><groupId>{g}</groupId><artifactId>{a}</artifactId><version>{v}</version></parent>"
        with open(os.path.join(d, f"{name}-{version}.pom"), "w") as f:
            f.write(POM.format(parent=p, licences="".join(licences)))
        if entries is not None:
            with zipfile.ZipFile(os.path.join(d, f"{name}-{version}.aar"), "w") as z:
                for entry, text in entries.items():
                    z.writestr(entry, text)
                z.writestr("classes.jar", b"not a notice")

    def index(self):
        return tp.crate_index({"packages": self.packages})

    def artifact_paths(self):
        return sorted(os.path.join(d, n) for d, _, names in os.walk(self.cache) for n in names)

    def artifacts(self):
        return tp.index_artifacts(self.artifact_paths())


class CargoTreeTest(unittest.TestCase):
    def test_parses_crates_without_local_ones_or_repeats(self):
        text = (
            "moto-ffi v0.1.0 (/home/u/moto/core/moto-ffi)|AGPL-3.0-only\n"
            "serde v1.0.229|MIT OR Apache-2.0\n"
            "serde v1.0.229|MIT OR Apache-2.0 (*)\n"
            "toml v1.1.6+spec-1.1.0|MIT OR Apache-2.0\n"
            "local v0.1.0 (C:\\moto\\x)|MIT\n"
            "\n"
        )
        self.assertEqual(tp.parse_cargo_tree(text), {("serde", "1.0.229"), ("toml", "1.1.6+spec-1.1.0")})

    def test_rejects_odd_lines(self):
        with self.assertRaises(tp.LicenceError):
            tp.parse_cargo_tree("serde 1.0|MIT\n")


class SpdxTest(unittest.TestCase):
    def test_ids_of_expressions(self):
        self.assertEqual(tp.spdx_ids("MIT OR Apache-2.0"), ["MIT", "Apache-2.0"])
        self.assertEqual(tp.spdx_ids("MIT/Apache-2.0"), ["MIT", "Apache-2.0"])
        self.assertEqual(
            tp.spdx_ids("(Apache-2.0 WITH LLVM-exception) OR MIT"), ["Apache-2.0", "MIT"]
        )

    def test_pom_licence_names(self):
        self.assertEqual(tp.spdx_of_pom("The Apache Software License, Version 2.0", ""), "Apache-2.0")
        self.assertEqual(tp.spdx_of_pom("Apache-2.0", ""), "Apache-2.0")
        self.assertEqual(tp.spdx_of_pom("BSD", "https://opensource.org/licenses/BSD-2-Clause"), "BSD-2-Clause")
        self.assertEqual(tp.spdx_of_pom("LGPL-2.1-or-later", ""), "LGPL-2.1-or-later")
        self.assertEqual(tp.spdx_of_pom("MIT License", ""), "MIT")
        self.assertIsNone(tp.spdx_of_pom("BSD", ""))
        self.assertIsNone(tp.spdx_of_pom("Proprietary", ""))
        self.assertIsNone(tp.spdx_of_pom("Permit anything", ""))


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.addCleanup(self.f.tmp.cleanup)

    def build(self, crates, components):
        return tp.build(crates, self.f.index(), components, self.f.artifacts(), self.f.licenses, "AGPL text\n# not a heading\n")

    def test_texts_are_found_numbered_and_shared(self):
        f = self.f
        mit_a = "MIT License\n\nCopyright (c) A\n"
        f.crate("a", "1.0.0", "MIT OR Apache-2.0", {"LICENSE-MIT": mit_a, "LICENSE-APACHE": "Apache License\nVersion 2.0\n"})
        f.crate("b", "2.0.0", "MIT", {"LICENSE": "MIT License\n\n  Copyright (c) A\n"})  # same as a's, up to spaces
        f.crate("uniffi", "0.32.1", "MPL-2.0")  # ships no licence file
        f.maven("androidx.core", "core", "1.18.0", [licence("The Apache Software License, Version 2.0")],
                entries={"META-INF/androidx/core/core/LICENSE.txt": "Apache License\nVersion 2.0\n"})
        f.maven("com.google.guava", "guava-parent", "26.0", [licence("Apache License, Version 2.0")])
        f.maven("com.google.guava", "listenablefuture", "1.0", [], parent=("com.google.guava", "guava-parent", "26.0"), entries={})
        f.maven("org.maplibre.gl", "android-sdk", "13.6.1", [licence("BSD", "https://opensource.org/licenses/BSD-2-Clause")], entries={})
        f.write("licenses/maven/org.maplibre.gl__android-sdk__13.6.1.txt", "BSD 2-Clause\n### [Boost](x)\n")
        f.maven("net.java.dev.jna", "jna", "5.18.1", [licence("LGPL-2.1-or-later"), licence("Apache-2.0")], entries={})

        text = self.build(
            {("a", "1.0.0"), ("b", "2.0.0"), ("uniffi", "0.32.1")},
            {("androidx.core", "core", "1.18.0"), ("com.google.guava", "listenablefuture", "1.0"),
             ("org.maplibre.gl", "android-sdk", "13.6.1"), ("net.java.dev.jna", "jna", "5.18.1")},
        )
        # Apache (a's file, AndroidX's, the kept text for guava and JNA) is
        # written once; the MIT texts of a and b once; MPL and MapLibre's.
        self.assertIn("## Licence texts (4)", text)
        self.assertEqual(text.count("Apache License"), 1)
        self.assertEqual(text.count("Copyright (c) A"), 1)
        self.assertIn("a 1.0.0\nLicence: MIT OR Apache-2.0 · https://crates.io/crates/a/1.0.0\nTexts: 1, 2", text)
        self.assertIn("b 2.0.0\nLicence: MIT · https://crates.io/crates/b/2.0.0\nTexts: 2", text)
        self.assertIn("Used by: a, b", text)
        self.assertIn("uniffi 0.32.1\nLicence: MPL-2.0", text)
        self.assertIn("Used by: a, androidx.core:core, com.google.guava:listenablefuture, net.java.dev.jna:jna", text)
        self.assertIn("com.google.guava:listenablefuture 1.0\nLicence: Apache-2.0", text)
        self.assertIn("net.java.dev.jna:jna 5.18.1\nLicence: LGPL-2.1-or-later OR Apache-2.0", text)
        # Headings inside texts can't pass for ours.
        self.assertIn("\n ### [Boost](x)", text)
        self.assertIn("\n # not a heading", text)
        self.assertNotIn("src/LICENSE-not-top-level", text)
        self.assertEqual([l for l in text.splitlines() if l.startswith("## ")],
                         ["## moto", "## Rust crates (3)", "## Android libraries (4)", "## Licence texts (4)"])
        # Deterministic.
        self.assertEqual(text, self.build(
            {("uniffi", "0.32.1"), ("b", "2.0.0"), ("a", "1.0.0")},
            {("net.java.dev.jna", "jna", "5.18.1"), ("org.maplibre.gl", "android-sdk", "13.6.1"),
             ("com.google.guava", "listenablefuture", "1.0"), ("androidx.core", "core", "1.18.0")},
        ))

    def test_missing_licences_fail(self):
        f = self.f
        f.crate("nolicence", "1.0.0", "")
        f.crate("notext", "1.0.0", "Zlib")
        f.maven("x", "bsd", "1.0", [licence("BSD", "https://opensource.org/licenses/BSD-2-Clause")], entries={})
        f.maven("x", "odd", "1.0", [licence("Proprietary")], entries={})
        f.maven("x", "none", "1.0", [], entries={})
        for crates, components in [
            ({("nolicence", "1.0.0")}, set()),
            ({("notext", "1.0.0")}, set()),
            ({("missing", "1.0.0")}, set()),
            (set(), {("x", "bsd", "1.0")}),  # no kept notice for this version
            (set(), {("x", "odd", "1.0")}),
            (set(), {("x", "none", "1.0")}),
            (set(), {("x", "absent", "1.0")}),
        ]:
            with self.assertRaises(tp.LicenceError, msg=f"{crates} {components}"):
                self.build(crates, components)

    def test_huge_notices_fail(self):
        big = "x" * (tp.MAX_NOTICE_BYTES + 1)
        self.f.crate("big", "1.0.0", "MIT", {"LICENSE": big})
        with self.assertRaises(tp.LicenceError):
            self.build({("big", "1.0.0")}, set())
        self.f.maven("x", "big", "1.0", [licence("MIT")], entries={"META-INF/LICENSE": big})
        with self.assertRaises(tp.LicenceError):
            self.build(set(), {("x", "big", "1.0")})

    def test_artifacts_are_matched_by_their_cache_path(self):
        c = os.path.join("/g", "caches", "modules-2", "files-2.1")
        index = tp.index_artifacts([
            f"{c}/a.b/lib/1.0/h1/lib-1.0.pom",
            f"{c}/a.b/lib/1.0/h2/lib-1.0.aar",
            f"{c}/a.b/lib/1.0/h3/lib-1.0-sources.jar",
            f"{c}/a.b/lib/1.0/h4/lib-1.0.module",
            "/elsewhere/lib.jar",
            "short/path.jar",
        ])
        self.assertEqual(index, {("a.b", "lib", "1.0"): {"pom": f"{c}/a.b/lib/1.0/h1/lib-1.0.pom", "archives": [f"{c}/a.b/lib/1.0/h2/lib-1.0.aar"]}})

    def test_maven_list(self):
        self.assertEqual(tp.parse_maven_list("a:b:1\n\n a:b:1 \nc:d:2\n"), {("a", "b", "1"), ("c", "d", "2")})
        for bad in ["a:b", "a:b:c:d", "a::1"]:
            with self.assertRaises(tp.LicenceError):
                tp.parse_maven_list(bad)


class MainTest(unittest.TestCase):
    def test_writes_the_file_or_fails_with_status_1(self):
        f = Fixture()
        self.addCleanup(f.tmp.cleanup)
        f.crate("a", "1.0.0", "MPL-2.0")
        tree = f.write("tree.txt", "a v1.0.0|MPL-2.0\n")
        meta = f.write("meta.json", json.dumps({"packages": f.packages}))
        maven = f.write("maven.txt", "")
        paths = f.write("artifacts.txt", "")
        app = f.write("LICENSE", "AGPL\n")
        out = os.path.join(f.root, "out", "third_party.txt")
        args = ["--cargo-tree", tree, "--cargo-metadata", meta, "--maven", maven, "--artifacts", paths,
                "--licenses", f.licenses, "--app-licence", app, "--out", out]
        self.assertEqual(tp.main(args), 0)
        with open(out, encoding="utf-8") as fh:
            self.assertIn("Mozilla Public License", fh.read())
        f.write("maven.txt", "x:y:1\n")
        self.assertEqual(tp.main(args), 1)
        f.write("meta.json", "{not json")
        self.assertEqual(tp.main(args), 1)


if __name__ == "__main__":
    unittest.main()
