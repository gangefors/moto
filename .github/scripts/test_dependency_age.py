# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for dependency_age.py (python3 -m unittest discover -s .github/scripts)."""

import datetime as dt
import tomllib
import unittest
from urllib.parse import urlparse

import dependency_age as da

CATALOG = """
[versions]
agp = "{agp}"
maplibre = "{maplibre}"
unused = "1.0"

[libraries]
maplibre-android = {{ module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }}
material3 = {{ module = "androidx.compose.material3:material3" }}
pinned = {{ module = "a.b:c", version = "2.0" }}

[plugins]
android-application = {{ id = "com.android.application", version.ref = "agp" }}
"""

NOW = dt.datetime(2026, 9, 28, 12, 0, tzinfo=dt.timezone.utc)


def catalog(agp="9.4.0", maplibre="13.6.1"):
    return tomllib.loads(CATALOG.format(agp=agp, maplibre=maplibre))


def fetcher(dates):
    """A fake HEAD request: Last-Modified by URL substring, else None."""

    def fetch(url):
        for part, date in dates.items():
            if part in url:
                return date
        return None

    return fetch


class DependencyAgeTest(unittest.TestCase):
    def test_finds_changed_versions_and_their_artifacts(self):
        base, head = catalog(), catalog(agp="9.4.1")
        self.assertEqual({"agp": "9.4.1"}, da.changed_versions(base, head))
        self.assertEqual(
            [("com.android.application", "com.android.application.gradle.plugin")],
            da.artifacts_for(head, "agp"),
        )
        self.assertEqual([("org.maplibre.gl", "android-sdk")], da.artifacts_for(head, "maplibre"))
        self.assertEqual([], da.artifacts_for(head, "unused"))

    def test_old_enough_passes(self):
        fetch = fetcher({"9.4.1": "Fri, 18 Sep 2026 14:15:56 GMT"})
        r = da.check(catalog(), catalog(agp="9.4.1"), NOW, 7, fetch)
        self.assertTrue(r["ok"], r)
        self.assertIn("released 2026-09-18, 9 days ago", r["lines"][0])

    def test_too_new_fails_with_the_day_it_is_ready(self):
        fetch = fetcher({"9.4.1": "Thu, 24 Sep 2026 10:00:00 GMT"})
        r = da.check(catalog(), catalog(agp="9.4.1"), NOW, 7, fetch)
        self.assertFalse(r["ok"])
        self.assertEqual("agp 9.4.1 is 4 days old, OK from 2026-10-01", r["description"])

    def test_the_newest_artifact_decides(self):
        base, head = catalog(), catalog(agp="9.4.1", maplibre="13.7.0")
        fetch = fetcher({"9.4.1": "Fri, 18 Sep 2026 14:15:56 GMT", "13.7.0": "Sun, 27 Sep 2026 08:00:00 GMT"})
        r = da.check(base, head, NOW, 7, fetch)
        self.assertFalse(r["ok"])
        self.assertTrue(r["description"].startswith("maplibre 13.7.0 is 1 days old"), r)

    def test_an_unknown_date_counts_as_too_new(self):
        r = da.check(catalog(), catalog(agp="9.4.1"), NOW, 7, fetcher({}))
        self.assertFalse(r["ok"])
        self.assertIn("release date unknown", r["description"])
        bad = da.check(catalog(), catalog(agp="9.4.1"), NOW, 7, fetcher({"9.4.1": "not a date"}))
        self.assertFalse(bad["ok"])

    def test_nothing_changed_passes(self):
        r = da.check(catalog(), catalog(), NOW, 7, fetcher({}))
        self.assertTrue(r["ok"])

    def test_the_first_repository_with_the_file_answers(self):
        urls = []

        def fetch(url):
            urls.append(url)
            host = urlparse(url).hostname
            return "Fri, 18 Sep 2026 14:15:56 GMT" if host == "repo1.maven.org" else None

        date = da.pom_date("org.maplibre.gl", "android-sdk", "13.6.1", fetch)
        self.assertEqual(dt.datetime(2026, 9, 18, 14, 15, 56, tzinfo=dt.timezone.utc), date)
        self.assertTrue(urls[0].startswith("https://dl.google.com/android/maven2/org/maplibre/gl/android-sdk/13.6.1/"))
        self.assertEqual(2, len(urls))

    def test_long_descriptions_fit_a_commit_status(self):
        head = tomllib.loads("[versions]\n" + "".join(f'k{i} = "1"\n' for i in range(20)) +
                             "[libraries]\n" + "".join(f'l{i} = {{ module = "g:n{i}", version.ref = "k{i}" }}\n' for i in range(20)))
        r = da.check({}, head, NOW, 7, fetcher({}))
        self.assertLessEqual(len(r["description"]), 140)


if __name__ == "__main__":
    unittest.main()
