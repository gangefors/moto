# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
"""Whether CI may fast-forward main to a work branch (CLAUDE.md, Rules).

The merge job in android.yml runs this once core, bench and build passed
for a push to a Claude work branch. It fast-forwards main only when that
is exactly what a session would do by hand:

- the tested commit is still on the branch, and any newer commits on it
  change nothing CI builds (a newer code commit gets its own run, which
  merges it);
- main is an ancestor of the branch tip (no merge needed: if main moved
  on, the session brings it into the branch and CI runs again);
- no workflow file changes (the job's token may not change workflows, so
  a session merges those by hand).

Prints `target=<sha>` and `dispatch=<workflow files>` for $GITHUB_OUTPUT
when main may move, and always the reason on stderr. A push made with the
job's token starts no workflows, so the job starts main's CI (debug-latest,
the benchmark baseline) and CodeQL itself.

    python3 fast_forward.py <tested sha> <branch tip ref> <main ref>
"""

import re
import subprocess
import sys

SHA = re.compile(r"^[0-9a-f]{40}$")
MOVED_ON = (
    "main has moved on: merge main into the branch and push, "
    "then CI merges it when green"
)


def builds_app(path: str) -> bool:
    """Whether android.yml runs for a change to path (its push paths)."""
    if path.endswith(".md"):
        return False
    return (
        path.startswith("android/")
        or path.startswith("core/")
        or path == ".github/scripts/third_party.py"
    )


def scanned(path: str) -> bool:
    """Whether codeql.yml runs on main for a change to path."""
    if path.endswith(".md"):
        return False
    return path.startswith(
        ("android/", "core/", ".github/workflows/", ".github/scripts/")
    )


def decide(tested_on_branch, newer_files, main_behind, main_files):
    """Returns (may_merge, reason, workflows to start on main)."""
    if not tested_on_branch:
        if not main_behind:
            return False, MOVED_ON, []
        return False, "the tested commit is no longer on the branch", []
    newer_code = sorted(p for p in newer_files if builds_app(p))
    if newer_code:
        return False, "a newer commit changes code; its own CI run merges it", []
    if not main_behind:
        return False, MOVED_ON, []
    if not main_files:
        return False, "main already has these commits", []
    workflows = sorted(p for p in main_files if p.startswith(".github/workflows/"))
    if workflows:
        return (
            False,
            "workflow files changed (" + ", ".join(workflows) + "); "
            "the job's token can't push those: merge by hand",
            [],
        )
    dispatch = []
    if any(builds_app(p) for p in main_files):
        dispatch.append("android.yml")
    if any(scanned(p) for p in main_files):
        dispatch.append("codeql.yml")
    return True, "fast-forward main", dispatch


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True)


def is_ancestor(a: str, b: str) -> bool:
    return git("merge-base", "--is-ancestor", a, b).returncode == 0


def changed(a: str, b: str) -> list:
    # -z: NUL-separated, never C-quoted (non-ASCII, tab or quote in a path
    # would otherwise start with a quote and slip past the prefix checks).
    out = git("diff", "--name-only", "-z", "--no-renames", a, b)
    if out.returncode != 0:
        raise SystemExit("git diff failed: " + out.stderr.strip())
    return [p for p in out.stdout.split("\0") if p]


def resolve(ref: str) -> str:
    out = git("rev-parse", "--verify", "--quiet", ref + "^{commit}")
    sha = out.stdout.strip()
    if out.returncode != 0 or not SHA.match(sha):
        raise SystemExit("not a commit: " + ref)
    return sha


def main(argv: list) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    tested = resolve(argv[0])
    tip = resolve(argv[1])
    main_sha = resolve(argv[2])
    on_branch = is_ancestor(tested, tip)
    may, reason, dispatch = decide(
        on_branch,
        changed(tested, tip) if on_branch else [],
        is_ancestor(main_sha, tip),
        changed(main_sha, tip),
    )
    print(f"{reason} ({main_sha[:7]} -> {tip[:7]})", file=sys.stderr)
    if may:
        print(f"target={tip}")
        print("dispatch=" + " ".join(dispatch))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
