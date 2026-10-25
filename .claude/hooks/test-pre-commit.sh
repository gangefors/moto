#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Tests for .githooks/pre-commit in a throwaway repo. Needs bash and git.
set -u
here=$(cd "$(dirname "$0")" && pwd)
hook="$here/../../.githooks/pre-commit"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
git init -q -b main "$tmp"
cd "$tmp" || exit 1
git config user.email t@example.com; git config user.name t
git commit -q --allow-empty -m init
fails=0; n=0
label=; want=

# verdict: runs the hook on what is staged. Block cases must also name the
# rule on stderr (WANT_MSG overrides the text, for the SPDX case).
verdict() {
  local rc err; err=$(bash "$hook" 2>&1 >/dev/null); rc=$?
  n=$((n + 1))
  if { [ "$want" = allow ] && [ $rc -ne 0 ]; } || { [ "$want" = block ] && [ $rc -eq 0 ]; }; then
    echo "FAIL: $label: wanted $want, exit $rc"; fails=$((fails + 1))
  elif [ "$want" = block ] && ! printf '%s' "$err" | grep -q "${WANT_MSG:-separate commits}"; then
    echo "FAIL: $label: stderr lacks the rule: $err"; fails=$((fails + 1))
  fi
}

# check <label> <allow|block> <path>...  (stages new files, runs the hook)
check() {
  label=$1; want=$2; shift 2
  local f
  git reset -q
  for f in "$@"; do
    mkdir -p "$(dirname "$f")"
    case $f in
      *.rs|*.kt|*.kts|*.py|*.sh) [ -n "${NOSPDX:-}" ] && echo "fn main() {}" > "$f" \
        || printf '// SPDX-License-Identifier: AGPL-3.0-only\n' > "$f" ;;
      *) echo "x $RANDOM" > "$f" ;;
    esac
    git add "$f"
  done
  verdict
}

check "CLAUDE.md + skill file" allow CLAUDE.md .claude/skills/x/SKILL.md
check "CLAUDE.md + docs" allow CLAUDE.md docs/repo-map.md
check "CLAUDE.md + githooks" allow CLAUDE.md .githooks/pre-commit
check "ADR + core file" block docs/adr/0001-x.md core/moto-core/src/lib.rs
check "CLAUDE.md + android file" block CLAUDE.md android/app/src/A.kt
check ".github + CLAUDE.md" block .github/workflows/a.yml CLAUDE.md
check "core alone" allow core/moto-core/src/lib.rs
check "docs + core, no rule file" allow docs/prd.md core/moto-core/src/lib.rs
NOSPDX=1 WANT_MSG=SPDX check "new .rs without SPDX" block core/moto-core/src/new.rs
check "CLAUDE.md + spaced non-ASCII android file" block CLAUDE.md "android/app/Å ö.kt"
check "root android-x file + CLAUDE.md" allow CLAUDE.md android-x

# Cases that need history: renames and deletions.
git reset -q; git add -A; git commit -q -m files
mkdir -p core/moto-core/src docs/adr
echo a > core/moto-core/src/old.rs; echo b > docs/adr/0002-y.md
echo c > core/moto-core/src/gone.rs
git add -A; git commit -q -m history

label="core file renamed into docs + CLAUDE.md"; want=block
git mv core/moto-core/src/old.rs docs/old.rs; echo r >> CLAUDE.md; git add CLAUDE.md
verdict; git reset -q --hard

label="ADR moved + core edit"; want=block
git mv docs/adr/0002-y.md docs/y.md; echo r >> core/moto-core/src/gone.rs; git add core
verdict; git reset -q --hard

label="core file deleted + CLAUDE.md"; want=block
git rm -q core/moto-core/src/gone.rs; echo r >> CLAUDE.md; git add CLAUDE.md
verdict; git reset -q --hard

echo "pre-commit: $n cases, $fails failed"
[ "$fails" -eq 0 ]
