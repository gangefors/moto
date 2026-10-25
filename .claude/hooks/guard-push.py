#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Policy for guard-bash.sh: reads the Bash command on stdin. Exit 0 = allow,
# 2 = block (reason on stderr). A seat belt against mistakes, not a sandbox;
# the real control over main is server-side branch protection.
#
# Pushes are a strict whitelist: a command line that involves `push` (the
# word push or git-push on its own; names that only contain it, such as
# pre-push, push.default or origin/push-x, count only when the command has
# $, backticks, xargs or a `>` redirect, or runs any program not on the
# read-only list git, cat, head, tail, less, ls, grep, rg, wc, sort, uniq,
# diff, stat, file, echo, printf, test, [, cd, true and find without
# -exec/-ok/-delete) must be exactly one of
#   git push [-u|--set-upstream] origin BRANCH[:BRANCH]
#   git push [-u|--set-upstream] origin HEAD
#   git push [-u|--set-upstream] [--force-with-lease[=BRANCH[:SHA]]]
#            [--force-if-includes] origin BRANCH[:BRANCH]
# where BRANCH is claude/NAME or ccr-NAME
# (options in any order, each once, exact spellings, plain characters
# only, never sharing a line with another command). The whole command text
# is analysed, heredoc bodies included, except the message of the single
# shape `[git add ... && | git status && ]git commit ... -F - <<'TAG'`.
# Also blocked: skipping git hooks, changing git config that redirects
# pushes or hooks, tampering with the hook files and scripts, writing the
# verify stamp other than through verify.sh, and ref-moving calls
# (gh pr merge, GitHub ref/merge/contents/graphql APIs, plumbing pushes).
import re
import sys

PLAIN = re.compile(r"^[A-Za-z0-9 ._/@:=-]+$")
SHA = re.compile(r"^[0-9a-f]{7,64}$")
CHARS = r"[A-Za-z0-9 ._/@:=-]"
COMMIT_SHAPE = re.compile(
    r"\A((?:(?:git add " + CHARS + r"+|git status) && )?git commit" + CHARS + r"* -F - <<(['\"])(\w+)\2)\n(.*)\n\3\n?\Z",
    re.S)
PROTECTED = r"(\.githooks|\.git/hooks|\.claude/hooks|\.claude/scripts|\.claude/settings\.json|\.git/config)"


class Block(Exception):
    pass


def name_ok(n):
    if not (n.startswith("claude/") or re.match(r"ccr-[A-Za-z0-9]", n)) or not re.fullmatch(r"[A-Za-z0-9._/-]+", n):
        return False
    return all(p and not p.startswith(("-", ".")) and ".." not in p and not p.endswith((".", ".lock"))
               for p in n.split("/"))


def code_of(cmd):
    """The text to analyse: all of it, except the message of a plain
    `git commit -F - <<'TAG'` command."""
    m = COMMIT_SHAPE.match(cmd)
    if m and m.group(3) not in m.group(4).split("\n"):
        return m.group(1)
    return cmd


def check_push(line):
    if re.search(r"[;&|`$()<>{}\n]", line):
        raise Block("run git push as its own command")
    if not PLAIN.match(line):
        raise Block("a push line may only use letters, digits and ._/@:=- (no quotes, backslashes or expansions)")
    t = line.split()
    if t[:2] != ["git", "push"]:
        raise Block("only a plain `git push ...` is allowed")
    seen, lease, pos = set(), False, []
    for a in t[2:]:
        key = {"--set-upstream": "-u"}.get(a, a)
        if a.startswith("--force-with-lease="):
            key, lease = "--force-with-lease", True
            ref, colon, sha = a.split("=", 1)[1].partition(":")
            if not name_ok(ref) or (colon and not SHA.match(sha)):
                raise Block("--force-with-lease=<ref>[:<sha>] must name a claude/* or ccr-* branch")
        elif a == "--force-with-lease":
            lease = True
        elif a in ("-u", "--set-upstream", "--force-if-includes"):
            pass
        elif a.startswith("-"):
            raise Block("option %s is not allowed" % a)
        else:
            pos.append(a)
            continue
        if key in seen:
            raise Block("option %s given twice" % a)
        seen.add(key)
    if len(pos) != 2 or pos[0] != "origin":
        raise Block("push to `origin` with exactly one refspec")
    spec = pos[1]
    if spec == "HEAD" and not lease and "--force-if-includes" not in seen:
        return
    parts = spec.split(":")
    if len(parts) > 2 or not all(name_ok(p) for p in parts):
        raise Block("refspec must be claude/NAME or ccr-NAME, or BRANCH:BRANCH of those (a lease push needs an explicit branch)")


def check_hooks_not_skipped(n):
    sub = r"\bgit\s+(?:(?:-C\s*\S+|-c\s*\S+|--[a-z-]+(?:=\S+)?)\s+)*(commit|merge|rebase|push|am|cherry-pick)\b([^;&|\n]*)"
    for m in re.finditer(sub, n):
        rest = m.group(2)
        if re.search(r"(^|\s)--no-v", rest) or re.search(r"(^|\s)-[A-Za-z]*n[A-Za-z]*(\s|$)", rest):
            raise Block("git hooks (commit rules, verify stamp) can't be skipped")


def check_others(n):
    low = n.lower()
    if "hookspath" in low:
        raise Block("core.hooksPath is set by the session-start hook; leave it")
    if re.search(r"\bgit_(config|dir|work_tree|ssh|exec_path|alternate|proxy)", low):
        raise Block("git environment overrides are not allowed")
    if re.search(r"\bgit\b.*(\s-c|--config-env)\s*\S*(?i:hooks|include|alias|url|remote|push)", n):
        raise Block("git -c may not set hooks, includes, aliases, urls, remotes or push config")
    if re.search(r"\bgit\b.*\bconfig\b.*(include|alias\.|remote\.|url\.|push\.|branch\.\S*\.(remote|pushremote))", low):
        raise Block("git config for includes, aliases, remotes, urls or push is not changed from a session")
    if re.search(r"\bgit\b.*\bremote\s+(add|set-url|rename|remove|rm)\b", low):
        raise Block("git remotes are not changed from a session")
    if re.search(r"\bgit\b.*\b(send-pack|receive-pack|http-push|update-ref)\b", low):
        raise Block("plumbing that writes refs is not run from a session")
    if re.search(PROTECTED, low) and (
            re.search(r"\b(rm|mv|cp|ln|chmod|chown|tee|truncate|install|rsync|dd|unlink|shred)\b", low)
            or re.search(r"\b(sed|perl|ruby)\s+(-\w*i|--in-place)", low)
            or re.search(r">>?\s*\S*" + PROTECTED, low)):
        raise Block("the hook files and settings are not changed from a session")
    if "moto-verif" in low or ("fingerprint.sh" in low and re.search(r">|\btee\b", low)):
        raise Block("the verify stamp is written only by .claude/scripts/verify.sh")
    if re.search(r"\bgh\b.*\bpr\b.*\bmerge\b", low):
        raise Block("pull requests are merged by CI, not from a session")
    if re.search(r"\bgh\b.*\bapi\b", low):
        write = re.search(r"(-x\s*|--method[= ]\s*)(put|patch|post|delete)", low) or re.search(r"\s(-f|--field|--raw-field|--input)\b", low)
        if re.search(r"git/refs|/merges?\b|graphql", low) or (
                write and re.search(r"contents|branches|refs", low)):
            raise Block("no ref-moving GitHub API calls from a session")
    if re.search(r"\b(curl|wget)\b.*api\.github\.com.*(/graphql|/contents|git/refs|/merges?\b)", low):
        raise Block("no ref-moving GitHub API calls from a session")


def analyze(cmd):
    cmd = cmd.replace("\\\r\n", "").replace("\\\n", "")
    code = code_of(cmd)
    n = re.sub(r"[\\'\"]", "", code)
    check_hooks_not_skipped(n)
    check_others(n)
    if re.search(r"\bgit\b\s+\S*[$`]", n):
        raise Block("can't tell which git command runs")
    if mentions_push(n) and ("git" in n or re.search(r"[?*\[$`{]", n)):
        check_push(code.strip())


# Programs that only read and print (or run git, whose own push is caught
# by the narrow match). Any other program might rewrite text and run it.
READ_ONLY = {"git", "cat", "head", "tail", "less", "ls", "grep", "rg", "wc", "sort", "uniq",
             "diff", "stat", "file", "echo", "printf", "test", "[", "cd", "true", "find"}
FIND_RUNS = re.compile(r"(?<!\S)-(exec|execdir|ok|okdir|delete)(?!\S)")


def only_read_only(n):
    """True if every simple command starts with a READ_ONLY program (path
    stripped; a leading `!`, then, do or else is skipped), find has no
    -exec/-execdir/-ok/-okdir/-delete, and nothing is redirected to a file
    (writing a script and running it later)."""
    if ">" in n:
        return False
    for seg in re.split(r"[;&|(){}\n]", n):
        words = seg.split()
        while words and words[0] in ("!", "then", "do", "else"):
            words = words[1:]
        if not words:
            continue
        prog = words[0].rsplit("/", 1)[-1]
        if prog not in READ_ONLY or (prog == "find" and FIND_RUNS.search(seg)):
            return False
    return True


def mentions_push(n):
    """Whether the (unquoted) command may run a push. When there is no $,
    backtick or xargs and every program is on the READ_ONLY list with no
    redirect to a file, literal text is split only on whitespace and shell
    operators and nothing rewrites it into a command, so a name such as
    pre-push or push.default can never become the word push: only push
    between non-name characters (anything but \\w . / -) or git-push
    counts. Otherwise expansions (${x:-push}, IFS tricks, $(...)), xargs
    -d or any other program (`tr . ' ' | sh`, perl -e, make -f -, a
    script written and then run) could turn those names into push, so any
    \\bpush\\b counts, as before."""
    if re.search(r"[$`]|\bxargs\b", n) or not only_read_only(n):
        return re.search(r"\bpush\b", n) is not None
    return re.search(r"(?<![\w./-])push(?![\w./-])|\bgit-push\b", n) is not None


def main():
    try:
        analyze(sys.stdin.read())
    except Block as e:
        sys.stderr.write("Blocked: %s\n" % e)
        sys.exit(2)


if __name__ == "__main__":
    main()
