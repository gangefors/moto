#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Run by the `verify` agent after every local check has passed. Records
# the code fingerprint of HEAD so the pre-push hook lets the push through.
# Refuses when code is uncommitted, because then HEAD isn't what was checked.
set -eu
cd "$(git rev-parse --show-toplevel)"
if [ -n "$(git status --porcelain -- android core .github/scripts)" ]; then
  echo "Uncommitted code changes: commit first, then verify and stamp." >&2
  exit 1
fi
.claude/hooks/fingerprint.sh HEAD > "$(git rev-parse --git-common-dir)/moto-verified"
echo "Stamped verified: $(git rev-parse --short HEAD)"
