#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Push and remote-rewrite policy for guard-bash.sh. Reads the Bash command
# on stdin. Exit 0 = allow, 2 = block (reason on stderr).
#
# Strict whitelist: a command line that involves `push` must be exactly one
# of
#   git push [-u|--set-upstream] origin claude/NAME[:claude/NAME]
#   git push [-u|--set-upstream] origin HEAD
#   git push [-u|--set-upstream] [--force-with-lease[=claude/NAME[:SHA]]]
#            [--force-if-includes] origin claude/NAME[:claude/NAME]
# (options in any order, each once, exact spellings, plain characters
# only). Anything else, including a push sharing a line with `;`, `&&`,
# `|`, `$(...)` or backticks, is blocked. A few other ways to move main or
# rewrite the remote (git config, git remote, the GitHub ref APIs) are
# blocked as well.
import re
import sys

PLAIN = re.compile(r"^[A-Za-z0-9 ._/@:=-]+$")
SHA = re.compile(r"^[0-9a-f]{7,64}$")
INTERPRETER = re.compile(r"\b(bash|sh|zsh|dash|ksh|python3?|perl|ruby|node|eval|source|exec|xargs|env)\b")
HEREDOC = re.compile(r"<<-?\s*(['\"]?)(\w+)\1")


class Block(Exception):
    pass


def name_ok(n):
    if not n.startswith("claude/") or not re.fullmatch(r"[A-Za-z0-9._/-]+", n):
        return False
    return all(p and not p.startswith(("-", ".")) and ".." not in p and not p.endswith((".", ".lock"))
               for p in n.split("/"))


def code_of(cmd):
    """The command without heredoc bodies (message text), unless an
    interpreter might run that text as code."""
    out, tags = [], []
    for line in cmd.split("\n"):
        if tags:
            if line.strip() == tags[0]:
                tags.pop(0)
            continue
        out.append(line)
        tags.extend(m.group(2) for m in HEREDOC.finditer(line))
    code = "\n".join(out)
    return cmd if INTERPRETER.search(code) else code


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
                raise Block("--force-with-lease=<ref>[:<sha>] must name a claude/* branch")
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
        raise Block("refspec must be claude/NAME or claude/NAME:claude/NAME (a lease push needs an explicit branch)")


def check_others(code, n):
    low = n.lower()
    if re.search(r"\bgit_(config|dir|work_tree|ssh|exec_path|alternate|proxy)", low):
        raise Block("git environment overrides are not allowed")
    if re.search(r"\bgit\b.*\bconfig\b.*(\b(alias|remote|url|push)\.|\bbranch\.\S*\.(remote|pushremote))", low) \
            or re.search(r"\bgit\b.*\s-c\s*(alias|remote|url|push)\.", low):
        raise Block("git config for aliases, remotes, urls or push is not changed from a session")
    if re.search(r"\bgit\b.*\bremote\s+(add|set-url|rename|remove|rm)\b", low):
        raise Block("git remotes are not changed from a session")
    if re.search(r"\bgh\b.*\bapi\b.*(git/refs|/merges?\b)", low):
        raise Block("no GitHub ref or merge API calls from a session")
    if re.search(r"\b(curl|wget)\b.*api\.github\.com.*(git/refs|/merges?\b)", low):
        raise Block("no GitHub ref or merge API calls from a session")


def analyze(cmd):
    code = code_of(cmd)
    n = re.sub(r"[\\'\"]", "", code)
    check_others(code, n)
    if re.search(r"\bgit\b\s+\S*[$`]", n):
        raise Block("can't tell which git command runs")
    if re.search(r"\bpush\b", n) and ("git" in n or re.search(r"[?*\[$`{]", n)):
        check_push(code.strip())


def main():
    try:
        analyze(sys.stdin.read())
    except Block as e:
        sys.stderr.write("Blocked: %s\n" % e)
        sys.exit(2)


if __name__ == "__main__":
    main()
