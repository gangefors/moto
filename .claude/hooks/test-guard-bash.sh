#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Tests for guard-bash.sh: feeds commands as the hook receives them (JSON
# on stdin) and checks allow/block. Needs bash, git, jq, python3 only.
set -u
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
git init -q --bare "$tmp/remote.git"
git init -q -b main "$tmp/repo"; cd "$tmp/repo" || exit 1
git config user.email t@example.com; git config user.name t
git remote add origin "$tmp/remote.git"
git commit -q --allow-empty -m init
git push -q origin main
git checkout -q -b claude/work; git push -q -u origin claude/work
git checkout -q -b claude/noup
git checkout -q -b feature; git push -q -u origin feature
git checkout -q claude/work
repo=$tmp/repo
fails=0; n=0
t() { # expected(allow|block) command [cwd]
  local want=$1 cmd=$2 cwd=${3:-$repo} got
  n=$((n + 1))
  jq -nc --arg c "$cmd" --arg d "$cwd" '{tool_input:{command:$c},cwd:$d}' | "$here/guard-bash.sh" >/dev/null 2>&1
  [ $? -eq 2 ] && got=block || got=allow
  [ "$got" = "$want" ] || { echo "FAIL: want $want, got $got: $cmd"; fails=$((fails + 1)); }
}
# allowed
t allow 'git push -u origin claude/work'
t allow 'git push --force-with-lease origin claude/work'
t allow 'git push --force-with-lease=claude/work origin claude/work'
t allow 'git push --force-with-lease=claude/work:0123abc origin claude/work'
t allow 'git push --force-with-lease --force-if-includes origin claude/work'
t allow 'git push origin --force-with-lease HEAD'
t allow 'git push   --force-with-lease   origin   HEAD:claude/work'
t allow 'git push --force-with-lease origin refs/heads/claude/work:refs/heads/claude/work'
t allow 'git push --force-with-lease'
t allow 'git push --force-with-lease origin'
t allow 'git -C . push --force-with-lease origin claude/work'
t allow 'git push --force-with-lease origin claude/work claude/other'
t allow 'git push --force-with-lease origin claude/work 2>&1 | tail -3'
t allow 'git fetch origin main && git rebase origin/main && git push --force-with-lease origin claude/work'
t allow 'git log --oneline -f'
# blocked: plain force, mirrors, deletions
t block 'git push --force origin claude/work'
t block 'git push -f origin claude/work'
t block 'git push -fu origin claude/work'
t block 'git push origin +claude/work'
t block 'git push origin +HEAD:claude/work'
t block 'git push --mirror'
t block 'git push --delete origin claude/work'
t block 'git push origin :claude/work'
t block 'git push --force-with-lease origin :claude/work'
t block 'git push --force --force-with-lease origin claude/work'
t block 'git push --force-with-lease -f origin claude/work'
# blocked: wrong refs
t block 'git push --force-with-lease origin main'
t block 'git push --force-with-lease origin HEAD:main'
t block 'git push --force-with-lease origin claude/work main'
t block 'git push --force-with-lease origin claude/work:main'
t block 'git push --force-with-lease origin refs/tags/v1'
t block 'git push --force-with-lease origin --tags'
t block 'git push --force-with-lease --all origin'
t block 'git push --force-with-lease=main origin claude/work'
t block 'git push --force-with-lease=claude/work: origin claude/work'
t block 'git push --force-with-lease origin claude/../main'
t block 'git push --force-with-lease https://example.com/x.git claude/work'
t block 'git push --force-with-lease upstream claude/work'
# blocked: bare form from the wrong branch or upstream
t block 'git push --force-with-lease origin HEAD' "$tmp"
git -C "$repo" checkout -q feature
t block 'git push --force-with-lease'
t block 'git push --force-with-lease origin HEAD'
git -C "$repo" checkout -q claude/noup
t block 'git push --force-with-lease'
git -C "$repo" checkout -q claude/work
# blocked: obfuscation and wrappers
t block 'git push --force-with-lease origin claude/work;git push -f'
t block 'git pu""sh -f origin claude/work'
t block 'g""it push --force origin x'
t block 'git push $FLAGS origin claude/work'
t block 'git push --force-with-lease origin $BRANCH'
t block 'git push --force-with-lease origin "$(echo main)"'
t block 'eval "git push -f origin claude/work"'
t block 'bash -c "git push --force origin x"'
t block 'echo $(git push -f origin x)'
t block 'GIT_DIR=/x git push --force-with-lease origin claude/work'
t block 'env git push --force-with-lease origin claude/work'
t block 'git -c remote.origin.push=+main push --force-with-lease origin claude/work'
t block 'git -c alias.p="push -f" p'
t block 'git --git-dir=/x push --force-with-lease origin claude/work'
t block 'git push --force-with-lease origin "claude/work'
t block 'git push --no-verify origin claude/work'
t block 'git push --force-with-lease origin claude/work --receive-pack=evil'
echo "$n cases, $fails failed"
[ "$fails" -eq 0 ]
