#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Usage: ship-status.sh [branch]   (default: the current branch)
# Read-only. One short line per fact CLAUDE.md asks for before a build is
# reported: unpushed commits, how far the branch and main are apart, other
# session branches ahead of main, CI state and the debug APK link.
# What can't be determined prints "unknown (<reason>)"; the script never
# fails. SHIP_STATUS_NO_NET=1 skips the network checks.
set -u
branch=${1:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null)}
if [ -z "$branch" ] || [ "$branch" = HEAD ]; then
  echo "branch: unknown (no branch given or checked out)"; exit 0
fi
repo=gangefors/moto
echo "branch: $branch"

if ! git rev-parse --verify -q "refs/heads/$branch" >/dev/null 2>&1; then
  echo "branch: unknown (no local branch $branch)"; exit 0
fi
head=$(git rev-parse --short "refs/heads/$branch")
echo "head: $head"

count() { git rev-list --count "$1" 2>/dev/null || echo "unknown (cannot compare)"; }
if git rev-parse --verify -q "refs/remotes/origin/$branch" >/dev/null 2>&1; then
  echo "unpushed: $(count "origin/$branch..$branch") commit(s) not on origin/$branch"
else
  echo "unpushed: unknown (origin/$branch not fetched or not pushed)"
fi
if git rev-parse --verify -q refs/remotes/origin/main >/dev/null 2>&1; then
  echo "ahead of main: $(count "origin/main..$branch")"
  echo "behind main: $(count "$branch..origin/main")"
  others=$(git for-each-ref --format='%(refname:strip=3)' 'refs/remotes/origin/claude/*' 'refs/remotes/origin/ccr-*' 2>/dev/null |
    while read -r b; do
      [ "$b" = "$branch" ] && continue
      n=$(git rev-list --count "origin/main..origin/$b" 2>/dev/null || echo 0)
      [ "$n" -gt 0 ] && printf '%s(+%s) ' "$b" "$n"
    done)
  echo "other branches ahead of main: ${others:-none}"
else
  echo "ahead of main: unknown (origin/main not fetched)"
  echo "behind main: unknown (origin/main not fetched)"
  echo "other branches ahead of main: unknown (origin/main not fetched)"
fi

if [ "${SHIP_STATUS_NO_NET:-}" = 1 ]; then
  echo "ci: unknown (network checks skipped)"
  echo "apk: unknown (network checks skipped)"
  exit 0
fi

full=$(git rev-parse "refs/heads/$branch")
ci="unknown (no gh and no curl)"
if command -v gh >/dev/null 2>&1; then
  s=$(gh api "repos/$repo/commits/$full/check-runs" --jq '[.check_runs[] | "\(.name)=\(.conclusion // .status)"] | join(" ")' 2>/dev/null)
  if [ -n "$s" ]; then ci=$s; else ci="unknown (gh could not read check runs)"; fi
elif command -v curl >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
  s=$(curl -fsS --proto '=https' -L --max-time 15 -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$repo/commits/$full/check-runs" 2>/dev/null |
    python3 -I -c 'import json,sys; d=json.load(sys.stdin); print(" ".join("%s=%s"%(r["name"],r.get("conclusion") or r["status"]) for r in d["check_runs"]))' 2>/dev/null)
  if [ -n "$s" ]; then ci=$s; else ci="unknown (GitHub API unreachable, private or no runs yet)"; fi
fi
echo "ci: $ci"

tag=debug-branch
[ "$branch" = main ] && tag=debug-latest
url="https://github.com/$repo/releases/download/$tag/moto-debug-$head.apk"
if command -v curl >/dev/null 2>&1; then
  code=$(curl -sI -L --proto '=https' --proto-redir '=https' --max-time 20 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)
  case $code in
    ''|000) echo "apk: unknown (no answer from $url)" ;;
    *) echo "apk: HTTP $code $url" ;;
  esac
else
  echo "apk: unknown (curl missing)"
fi
exit 0
