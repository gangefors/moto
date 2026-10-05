#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# PreToolUse hook for GitHub comment and PR tools: no session link in
# PR descriptions or comments (CLAUDE.md).
if jq -r '[.tool_input.body, .tool_input.title] | map(. // "") | join("\n")' | grep -qiE 'claude\.ai/code/session|Claude-Session:'; then
  echo "Blocked: no session link in PRs or comments (CLAUDE.md); remove it and retry." >&2
  exit 2
fi
exit 0
