#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# PreToolUse hook for Bash: stops the ways around the git hooks. It fails
# closed: anything it can't read or run blocks the command.
deny() { echo "Blocked: $1" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || deny "guard can't run: jq is missing"
command -v python3 >/dev/null 2>&1 || deny "guard can't run: python3 is missing"
input=$(cat)
cmd=$(printf '%s' "$input" | jq -er '.tool_input.command | strings') \
  || deny "guard can't read the command from the hook input"
[ -n "$cmd" ] || deny "empty command"
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(commit|push|merge|rebase|cherry-pick|am)[^|;&]*(--no-verify|[[:space:]]-n([[:space:]]|$))'; then
  deny "git hooks (commit rules, verify stamp) can't be skipped"
fi
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(config|-c)[^|;&]*core\.hooksPath'; then
  deny "core.hooksPath is set by the session-start hook; leave it"
fi
# Pushes, remotes and ref APIs: strict whitelist in guard-push.py.
out=$(printf '%s' "$cmd" | python3 "$(dirname "$0")/guard-push.py" 2>&1)
case $? in
  0) exit 0 ;;
  2) echo "$out" >&2; exit 2 ;;
  *) deny "guard-push.py failed: $out" ;;
esac
