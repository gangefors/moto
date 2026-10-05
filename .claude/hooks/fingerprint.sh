#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Prints a fingerprint of the code a push would put on CI: the trees of
# android/, core/ and .github/scripts at a commit (default HEAD). Docs
# commits don't change it, so a passing `verify` stays valid after them.
set -u
rev=${1:-HEAD}
for p in android core .github/scripts; do
  printf '%s %s\n' "$p" "$(git rev-parse -q --verify "$rev:$p" || echo none)"
done | sha256sum | cut -d' ' -f1
