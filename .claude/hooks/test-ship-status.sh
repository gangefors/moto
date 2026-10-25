#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Tests for .claude/scripts/ship-status.sh in a throwaway repo (no
# network: SHIP_STATUS_NO_NET=1). Needs bash and git only.
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../scripts/ship-status.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null SHIP_STATUS_NO_NET=1
fails=0; n=0
expect() { # <label> <pattern> <output>
  n=$((n + 1))
  printf '%s\n' "$3" | grep -qE "$2" || { echo "FAIL: $1: want /$2/ in:"; printf '%s\n' "$3"; fails=$((fails + 1)); }
}
g() { git -C "$tmp/repo" "$@"; }
git init -q --bare "$tmp/remote.git"
git init -q -b main "$tmp/repo"
g config user.email t@example.com; g config user.name t
g remote add origin "$tmp/remote.git"
g commit -q --allow-empty -m init
g push -q origin main
g checkout -q -b claude/work
g commit -q --allow-empty -m one
g push -q origin claude/work
g commit -q --allow-empty -m two
g checkout -q -b claude/other main
g commit -q --allow-empty -m other
g push -q origin claude/other
g checkout -q claude/work
g fetch -q origin

out=$(cd "$tmp/repo" && bash "$script"); rc=$?
expect "exit code" '^0$' "$rc"
expect "default branch" '^branch: claude/work$' "$out"
expect "unpushed" '^unpushed: 1 commit' "$out"
expect "ahead" '^ahead of main: 2$' "$out"
expect "behind" '^behind main: 0$' "$out"
expect "other ahead" '^other branches ahead of main: claude/other\(\+1\)' "$out"
expect "ci skipped" '^ci: unknown \(' "$out"
expect "apk skipped" '^apk: unknown \(' "$out"

out=$(cd "$tmp/repo" && bash "$script" claude/other)
expect "named branch" '^branch: claude/other$' "$out"
expect "named other list" '^other branches ahead of main: claude/work\(\+1\)' "$out"

out=$(cd "$tmp/repo" && bash "$script" nope); rc=$?
expect "missing branch" '^branch: unknown \(no local branch nope\)$' "$out"
expect "missing branch exit" '^0$' "$rc"

g checkout -q -b claude/local main
out=$(cd "$tmp/repo" && bash "$script")
expect "never pushed" '^unpushed: unknown \(' "$out"

g remote remove origin
out=$(cd "$tmp/repo" && bash "$script" claude/work); rc=$?
expect "no remote exit" '^0$' "$rc"

echo "$n cases, $fails failed"
[ "$fails" -eq 0 ]
