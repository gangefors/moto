#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Force-push policy for guard-bash.sh. Reads the Bash command on stdin,
# the working directory is argv[1]. Exit 0 = allow, 2 = block (reason on
# stderr). Allowed: `git push` with --force-with-lease (optionally =ref[:sha])
# and --force-if-includes where every pushed ref is a claude/* branch on
# origin. Everything else that forces, mirrors or deletes is blocked, and
# whenever the command can't be understood for sure the answer is block.
import os
import re
import shlex
import subprocess
import sys

CLAUDE_REF = re.compile(r"^(refs/heads/)?claude/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
SAFE_SRC = re.compile(r"^[A-Za-z0-9._/-]+$")
SHA = re.compile(r"^[0-9a-f]{7,64}$")
PLAIN_OPTS = {"-u", "--set-upstream", "-q", "--quiet", "-v", "--verbose", "--progress"}
SEPARATORS = set("\n;&|(){}`")


class Block(Exception):
    pass


def valid_claude(ref):
    return bool(CLAUDE_REF.match(ref)) and ".." not in ref and not ref.endswith((".", ".lock"))


def split_segments(cmd):
    segs, buf, quote, i = [], [], None, 0
    while i < len(cmd):
        c = cmd[i]
        if quote:
            buf.append(c)
            if c == "\\" and quote == '"' and i + 1 < len(cmd):
                i += 1
                buf.append(cmd[i])
            elif c == quote:
                quote = None
        elif c == "\\" and i + 1 < len(cmd):
            buf.append(c)
            i += 1
            buf.append(cmd[i])
        elif c in "'\"":
            quote = c
            buf.append(c)
        elif c in SEPARATORS:
            segs.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    if quote:
        raise Block("unbalanced quotes")
    segs.append("".join(buf))
    return segs


def git_out(cdir, *args):
    try:
        r = subprocess.run(["git", "-C", cdir, *args], capture_output=True, text=True, timeout=10)
    except Exception:
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def current_branch(cdir):
    b = git_out(cdir, "symbolic-ref", "--short", "-q", "HEAD")
    if not b or not valid_claude(b) or b.startswith("refs/"):
        raise Block("current branch is not a claude/* branch")
    return b


def check_push(args, cdir, clean_prefix):
    lease = [a for a in args if a.startswith(("--force-with-lease", "--force-if-includes"))]
    danger = False
    for a in args:
        if a.startswith("--force") and not a.startswith(("--force-with-lease", "--force-if-includes")):
            danger = True
        elif a in ("--mirror", "--delete", "--prune", "--no-force-with-lease") or a.startswith("--delete="):
            danger = True
        elif re.match(r"^-[A-Za-z]*[fd]", a):
            danger = True
        elif a.startswith(("+", ":")) or ":+" in a:
            danger = True
    if danger:
        raise Block("no --force, -f, +refspec, --mirror or deletions; --force-with-lease to claude/* only")
    if not lease:
        return
    if not clean_prefix:
        raise Block("a lease push needs a plain `git [-C path] push` (no env, wrappers or git options)")
    pos = []
    for a in args:
        if a.startswith("--force-with-lease="):
            ref, colon, sha = a.split("=", 1)[1].partition(":")
            if not valid_claude(ref) or (colon and not SHA.match(sha)):
                raise Block("--force-with-lease=<ref>[:<sha>] must name a claude/* branch")
        elif a in ("--force-with-lease", "--force-if-includes") or a in PLAIN_OPTS:
            pass
        elif a.startswith("-"):
            raise Block("option %s not allowed on a lease push" % a)
        else:
            pos.append(a)
    if pos and pos[0] != "origin":
        raise Block("lease pushes go to origin only")
    specs = pos[1:]
    if not specs:
        b = current_branch(cdir)
        if git_out(cdir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}") != "origin/" + b:
            raise Block("upstream is not origin/%s; name the branch explicitly" % b)
        if (git_out(cdir, "config", "--get", "push.default") or "simple") not in ("simple", "current", "upstream"):
            raise Block("push.default would push more than the current branch")
        if git_out(cdir, "config", "--get-all", "remote.origin.push"):
            raise Block("remote.origin.push is configured; name the branch explicitly")
        for key in ("branch.%s.pushRemote" % b, "remote.pushDefault"):
            v = git_out(cdir, "config", "--get", key)
            if v and v != "origin":
                raise Block("%s redirects the push" % key)
        return
    for p in specs:
        if ":" in p:
            src, dst = p.split(":", 1)
            if not src or not SAFE_SRC.match(src) or not valid_claude(dst):
                raise Block("refspec %s must end in a claude/* branch" % p)
        elif p == "HEAD":
            current_branch(cdir)
        elif not valid_claude(p):
            raise Block("%s is not a claude/* branch" % p)


def analyze(cmd, cwd, depth=0):
    if depth > 3:
        raise Block("nested too deeply")
    for seg in split_segments(cmd):
        try:
            toks = shlex.split(seg)
        except ValueError:
            if "git" in seg and "push" in seg:
                raise Block("can't parse the command")
            continue
        out, skip = [], False
        for t in toks:
            if skip:
                skip = False
            elif re.match(r"^\d*(>>?|<)", t):
                skip = re.fullmatch(r"\d*(>>?|<)&?", t) is not None
            elif re.fullmatch(r"\d+", t) and False:
                pass
            else:
                out.append(t)
        toks = out
        for t in toks:
            if re.search(r"\s", t) and "git" in t:
                analyze(t, cwd, depth + 1)
        gi = next((i for i, t in enumerate(toks) if os.path.basename(t) == "git"), None)
        if gi is None:
            if "push" in toks and any(t.startswith("$") or "`" in t for t in toks):
                raise Block("can't tell what runs `push`")
            continue
        pre, post = toks[:gi], toks[gi + 1:]
        clean = all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=\S*", p) and not p.startswith("GIT_") for p in pre)
        cdir, i = cwd, 0
        while i < len(post) and post[i].startswith("-"):
            t = post[i]
            if t == "-C" and i + 1 < len(post):
                cdir = os.path.join(cdir, post[i + 1])
                i += 1
            elif t == "--no-pager":
                pass
            else:
                clean = False
                if t in ("-c", "--git-dir", "--work-tree", "--namespace") and i + 1 < len(post):
                    if t == "-c" and post[i + 1].lower().startswith(("alias.", "core.hookspath", "remote.", "url.", "push.")):
                        raise Block("git -c may not set aliases, remotes, hooks or push config")
                    i += 1
            i += 1
        sub = post[i] if i < len(post) else None
        if sub is None:
            continue
        if "$" in sub or "`" in sub:
            raise Block("can't tell which git command runs")
        if sub != "push":
            continue
        args = post[i + 1:]
        if any("$" in a or "`" in a or "~" in a or any(ch in a for ch in "*?[") for a in args):
            raise Block("no shell expansion in a push")
        check_push(args, cdir, clean)


def main():
    cmd = sys.stdin.read()
    try:
        analyze(cmd, sys.argv[1] if len(sys.argv) > 1 else os.getcwd())
    except Block as e:
        sys.stderr.write("Blocked: %s\n" % e)
        sys.exit(2)


if __name__ == "__main__":
    main()
