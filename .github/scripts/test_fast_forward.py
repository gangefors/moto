# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Tests for fast_forward.py (python3 -m unittest discover -s .github/scripts)."""

import contextlib
import io
import os
import subprocess
import tempfile
import unittest

import fast_forward as ff


class Paths(unittest.TestCase):
    def test_builds_app_mirrors_android_yml(self):
        self.assertTrue(ff.builds_app("core/moto-core/src/lib.rs"))
        self.assertTrue(ff.builds_app("android/app/build.gradle.kts"))
        self.assertTrue(ff.builds_app(".github/scripts/third_party.py"))
        self.assertFalse(ff.builds_app("core/moto-core/tests/golden/README.md"))
        self.assertFalse(ff.builds_app("docs/adr/0012-backup.md"))
        self.assertFalse(ff.builds_app(".github/scripts/bench_compare.py"))
        self.assertFalse(ff.builds_app("CLAUDE.md"))
        self.assertFalse(ff.builds_app("coreutils/x.rs"))

    def test_scanned_mirrors_codeql_yml(self):
        self.assertTrue(ff.scanned("core/x.rs"))
        self.assertTrue(ff.scanned(".github/scripts/bench_compare.py"))
        self.assertFalse(ff.scanned(".claude/skills/x/SKILL.md"))
        self.assertFalse(ff.scanned(".github/dependabot.yml"))


class Decide(unittest.TestCase):
    def test_code_change_fast_forwards_and_starts_main_ci(self):
        may, _, dispatch = ff.decide(True, [], True, ["core/x.rs", "CLAUDE.md"])
        self.assertTrue(may)
        self.assertEqual(dispatch, ["android.yml", "codeql.yml"])

    def test_newer_docs_commit_rides_along(self):
        may, _, _ = ff.decide(True, ["docs/prd.md"], True, ["core/x.rs", "docs/prd.md"])
        self.assertTrue(may)

    def test_newer_code_commit_waits_for_its_own_run(self):
        may, reason, _ = ff.decide(True, ["core/x.rs"], True, ["core/x.rs"])
        self.assertFalse(may)
        self.assertIn("newer commit", reason)

    def test_tested_commit_gone_from_branch(self):
        self.assertFalse(ff.decide(False, [], True, ["core/x.rs"])[0])

    def test_main_moved_on(self):
        may, reason, _ = ff.decide(True, [], False, ["core/x.rs"])
        self.assertFalse(may)
        self.assertIn("main has moved on", reason)

    def test_nothing_new(self):
        self.assertFalse(ff.decide(True, [], True, [])[0])

    def test_workflow_change_is_merged_by_hand(self):
        may, reason, _ = ff.decide(
            True, [], True, ["core/x.rs", ".github/workflows/android.yml"]
        )
        self.assertFalse(may)
        self.assertIn(".github/workflows/android.yml", reason)

    def test_script_change_starts_codeql_only(self):
        may, _, dispatch = ff.decide(True, [], True, [".github/scripts/bench_compare.py"])
        self.assertTrue(may)
        self.assertEqual(dispatch, ["codeql.yml"])


class Git(unittest.TestCase):
    """main() against a real repository: main, then a branch on top."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.old = os.getcwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, self.old)
        self.run_git("init", "-q", "-b", "main")
        self.run_git("config", "user.email", "t@example.com")
        self.run_git("config", "user.name", "t")
        self.main = self.commit("README.md")

    def run_git(self, *args):
        return subprocess.run(
            ["git", *args], check=True, capture_output=True, text=True
        ).stdout.strip()

    def commit(self, path):
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        with open(path, "a") as f:
            f.write("x\n")
        self.run_git("add", path)
        self.run_git("commit", "-q", "-m", path)
        return self.run_git("rev-parse", "HEAD")

    def output(self, tested, tip, main):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(ff.main([tested, tip, main]), 0)
        return out.getvalue()

    def test_fast_forward_to_tip_past_a_docs_commit(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        tested = self.commit("core/a.rs")
        tip = self.commit("docs/b.md")
        self.assertEqual(
            self.output(tested, tip, self.main),
            f"target={tip}\ndispatch=android.yml codeql.yml\n",
        )

    def test_main_moved_on_prints_nothing(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        tested = self.commit("core/a.rs")
        self.run_git("checkout", "-q", "main")
        main = self.commit("docs/c.md")
        self.assertEqual(self.output(tested, tested, main), "")

    def test_rewritten_branch_prints_nothing(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        tested = self.commit("core/a.rs")
        self.run_git("reset", "-q", "--hard", self.main)
        tip = self.commit("core/b.rs")
        self.assertEqual(self.output(tested, tip, self.main), "")

    def test_non_ascii_code_path_is_code(self):
        for path in ("core/sk\u00e5ne.rs", "android/app/\u00e4.kt"):
            with self.subTest(path=path):
                self.run_git("checkout", "-q", "-B", "ccr-x", "main")
                self.commit(path)
                tip = self.run_git("rev-parse", "HEAD")
                self.assertEqual(self.output(self.main, tip, self.main), "")
                self.assertEqual(ff.changed(self.main, tip), [path])

    def test_odd_workflow_path_is_refused(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        self.commit(".github/workflows/\u00e5.yml")
        tip = self.run_git("rev-parse", "HEAD")
        self.assertEqual(self.output(self.main, tip, self.main), "")
        may, reason, _ = ff.decide(
            True, [], True, ff.changed(self.main, tip)
        )
        self.assertFalse(may)
        self.assertIn("workflow files", reason)

    def test_paths_with_space_tab_and_quote(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        names = ["docs/a b.md", 'docs/q"uote.md', "docs/t\tab.md"]
        for n in names:
            self.commit(n)
        tip = self.run_git("rev-parse", "HEAD")
        self.assertEqual(sorted(ff.changed(self.main, tip)), sorted(names))
        self.assertEqual(
            self.output(self.main, tip, self.main),
            f"target={tip}\ndispatch=\n",
        )
        self.commit('core/q"uote.rs')
        tip = self.run_git("rev-parse", "HEAD")
        self.assertEqual(self.output(self.main, tip, self.main), "")

    def test_tested_main_docs_range_fast_forwards(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        self.commit("docs/a.md")
        tip = self.commit("CLAUDE.md")
        self.assertEqual(
            self.output(self.main, tip, self.main), f"target={tip}\ndispatch=\n"
        )

    def test_tested_main_code_in_range_is_left_to_build(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        self.commit("docs/a.md")
        tip = self.commit("core/a.rs")
        self.assertEqual(self.output(self.main, tip, self.main), "")
        _, reason, _ = ff.decide(True, ["core/a.rs"], True, ["core/a.rs"])
        self.assertTrue(reason.startswith("a newer commit changes code"))

    def test_tested_main_branch_behind_main_says_moved_on(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        tip = self.commit("docs/a.md")
        self.run_git("checkout", "-q", "main")
        main = self.commit("docs/c.md")
        self.assertEqual(self.output(main, tip, main), "")
        self.assertTrue(ff.decide(False, [], False, [])[1].startswith("main has moved on"))

    def test_tested_main_merge_commit_in_range(self):
        self.run_git("checkout", "-q", "-b", "ccr-x")
        self.commit("docs/a.md")
        self.run_git("checkout", "-q", "main")
        main = self.commit("docs/c.md")
        self.run_git("checkout", "-q", "ccr-x")
        self.run_git("merge", "-q", "--no-ff", "-m", "merge", "main")
        tip = self.run_git("rev-parse", "HEAD")
        self.assertEqual(
            self.output(main, tip, main), f"target={tip}\ndispatch=\n"
        )

    def test_tested_main_equals_tip_is_up_to_date(self):
        self.assertEqual(self.output(self.main, self.main, self.main), "")

    def test_bad_ref_fails(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                ff.main(["nope", "HEAD", "main"])


if __name__ == "__main__":
    unittest.main()
