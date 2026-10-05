#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# PreToolUse hook for Bash: stops the ways around the git hooks.
input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty')
cwd=$(printf '%s' "$input" | jq -r '.cwd // empty')
[ -n "$cwd" ] || cwd=$PWD
deny() { echo "Blocked: $1" >&2; exit 2; }
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(commit|push|merge|rebase|cherry-pick|am)[^|;&]*(--no-verify|[[:space:]]-n([[:space:]]|$))'; then
  deny "git hooks (commit rules, verify stamp) can't be skipped"
fi
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(config|-c)[^|;&]*core\.hooksPath'; then
  deny "core.hooksPath is set by the session-start hook; leave it"
fi
# Force pushes: only --force-with-lease to claude/* branches (guard-push.py);
# it fails closed, so a missing python3 blocks too.
printf '%s' "$cmd" | python3 "$(dirname "$0")/guard-push.py" "$cwd" || exit 2
exit 0
