#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# PreToolUse hook for Bash: stops the ways around the git hooks.
cmd=$(jq -r '.tool_input.command // empty')
deny() { echo "Blocked: $1" >&2; exit 2; }
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(commit|push|merge|rebase|cherry-pick|am)[^|;&]*(--no-verify|[[:space:]]-n([[:space:]]|$))'; then
  deny "git hooks (commit rules, verify stamp) can't be skipped"
fi
if printf '%s' "$cmd" | grep -qE 'git[[:space:]]+(config|-c)[^|;&]*core\.hooksPath'; then
  deny "core.hooksPath is set by the session-start hook; leave it"
fi
if printf '%s' "$cmd" | grep -qE 'git[^|;&]* push[^|;&]*( --force( |$)| -f( |$)| \+[A-Za-z])'; then
  deny "no force push; use --force-with-lease only to restart a merged branch"
fi
exit 0
