# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for regions_changed.py (python3 -m unittest discover -s .github/scripts)."""

import json
import os
import tempfile
import unittest

import regions_changed as rc


def manifest(**hashes):
    return {
        "format_major": 1,
        "regions": [{"id": k, "name": k.title(), "gz_sha256": v} for k, v in hashes.items()],
    }


def signed(doc):
    return b"\x07" * rc.SIGNATURE_BYTES + json.dumps(doc).encode()


class RegionsChanged(unittest.TestCase):
    def test_only_changed_and_new_regions_are_uploaded(self):
        published = rc.published_hashes(signed(manifest(sweden="aa", norway="bb")))
        self.assertEqual(published, {"sweden": "aa", "norway": "bb"})
        new = manifest(sweden="aa", norway="cc", finland="dd")
        self.assertEqual(
            rc.changed(published, new), ["norway-v1.region.gz", "finland-v1.region.gz"]
        )

    def test_nothing_changed_uploads_nothing(self):
        published = rc.published_hashes(signed(manifest(sweden="aa")))
        self.assertEqual(rc.changed(published, manifest(sweden="aa")), [])

    def test_an_unreadable_published_manifest_uploads_everything(self):
        for junk in [b"", b"x" * 70, signed({"regions": "no"}), signed([1, 2])]:
            published = rc.published_hashes(junk)
            self.assertEqual(published, {})
            self.assertEqual(
                rc.changed(published, manifest(sweden="aa")), ["sweden-v1.region.gz"]
            )

    def test_bad_ids_are_refused(self):
        for rid in ["../x", "Sweden", "", "a" * 33, "se/../../etc"]:
            with self.assertRaises(ValueError):
                rc.changed({}, {"format_major": 1, "regions": [{"id": rid, "gz_sha256": "a"}]})

    def test_main_checks_the_files_exist(self):
        with tempfile.TemporaryDirectory() as d:
            old = os.path.join(d, "old.manifest")
            new = os.path.join(d, "new.json")
            with open(old, "wb") as f:
                f.write(signed(manifest(sweden="aa")))
            with open(new, "w") as f:
                json.dump(manifest(sweden="bb"), f)
            with self.assertRaises(FileNotFoundError):
                rc.main(["x", old, new, d])
            open(os.path.join(d, "sweden-v1.region.gz"), "wb").close()
            self.assertEqual(rc.main(["x", old, new, d]), 0)
        self.assertEqual(rc.main(["x"]), 2)


if __name__ == "__main__":
    unittest.main()
