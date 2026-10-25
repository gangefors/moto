#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# The local checks, picked from what the branch changed, with the
# versions CI uses. Prints one line per check and, for a failure, only its
# first real errors (the full log path is printed too). When every check
# that applies has passed and no code is uncommitted, it records the code
# fingerprint of HEAD: the pre-push hook lets code up only with that
# stamp, and nothing else writes it (the Bash guard blocks it).
#
#   verify.sh            checks for origin/main...HEAD plus uncommitted work
#   verify.sh --all      every check, whatever changed
#
# Exit 0: all applicable checks passed. 1: a check failed or could not run.
set -uo pipefail
root=$(git rev-parse --show-toplevel); cd "$root"
here="$root/.claude/scripts"
stamp="$(git rev-parse --git-common-dir)/moto-verified"
logs=$(mktemp -d /tmp/verify-logs.XXXXXX)
all=no; [ "${1:-}" = "--all" ] && all=yes

dirty=$(git status --porcelain -- android core .github/scripts)
want=$("$root/.claude/hooks/fingerprint.sh" HEAD)
if [ "$all" = no ] && [ -z "$dirty" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$want" ]; then
  echo "verify: HEAD $(git rev-parse --short HEAD) already verified; nothing to run"
  exit 0
fi

base=$(git merge-base HEAD origin/main 2>/dev/null) \
  || { echo "verify: origin/main unknown; run git fetch origin main"; exit 1; }
changed=$( { git diff --name-only --no-renames "$base"; git ls-files --others --exclude-standard; } | sort -u)
has() { [ "$all" = yes ] || printf '%s\n' "$changed" | grep -qE "$1"; }

checks=()
has '^core/'                                && checks+=(fmt clippy test)
has '(^|/)Cargo\.(toml|lock)$|^core/deny\.toml$' && checks+=(deny)
# The app build compiles the core and generates the bindings, so an FFI
# change needs it too.
has '^android/|^core/moto-ffi/'             && checks+=(android)
has '^\.github/scripts/'                    && checks+=(scripts)
has '^\.claude/hooks/|^\.claude/scripts/|^\.githooks/' && checks+=(guards)

if [ "${#checks[@]}" -eq 0 ]; then
  echo "verify: no code changed (docs only); nothing to run, no stamp needed"
  exit 0
fi

env_for() { # rust|android: exports the toolchain, installing it once
  local out
  out=$("$here/setup-env.sh" "$1" 2>"$logs/setup-$1.log") || return 1
  eval "$out"
}
RUST_VERSION=$(sed -n 's/^  RUST_VERSION: "\(.*\)"$/\1/p' .github/workflows/android.yml)
first_errors() { # log: the first real errors, about 30 lines
  local hits
  hits=$(grep -nE -A6 '^(error|warning)(\[|:)|panicked at|^---- .* stdout ----|^failures:|test result: FAILED|^Diff in |^e: |FAILURE:|What went wrong|^FAIL|Error:|Traceback' "$1" | head -30)
  [ -n "$hits" ] && printf '%s\n' "$hits" || tail -30 "$1"
}

failed=0; notrun=0
run() { # name dir command...
  local name=$1 dir=$2; shift 2
  local log="$logs/$name.log"
  if (cd "$dir" && "$@") >"$log" 2>&1; then
    echo "PASS    $name"
  else
    echo "FAIL    $name  (log: $log)"
    first_errors "$log" | sed 's/^/    /'
    failed=1
  fi
}
for c in "${checks[@]}"; do
  case $c in
    fmt|clippy|test|deny)
      if [ -z "${rust_ok:-}" ]; then
        if env_for rust; then rust_ok=yes; else rust_ok=no; fi
      fi
      if [ "$rust_ok" = no ]; then
        echo "NOT RUN $c  (Rust setup failed: $logs/setup-rust.log)"; notrun=1; continue
      fi ;;
  esac
  case $c in
    fmt)    run fmt core cargo "+$RUST_VERSION" fmt --all --check ;;
    clippy) run clippy core cargo "+$RUST_VERSION" clippy --workspace --all-targets --all-features -- -D warnings ;;
    test)   run test core cargo "+$RUST_VERSION" test --workspace ;;
    deny)   run deny core cargo "+$RUST_VERSION" deny --locked check ;;
    android)
      if env_for android; then
        RUSTUP_TOOLCHAIN=$RUST_VERSION run android android ./gradlew --console=plain -q assembleDebug lintDebug testDebugUnitTest compileReleaseKotlin
      else
        echo "NOT RUN android  (setup failed: $logs/setup-android.log)"; tail -5 "$logs/setup-android.log" | sed 's/^/    /'; notrun=1
      fi ;;
    scripts) run scripts . python3 -m unittest discover -s .github/scripts -p 'test_*.py' ;;
    guards)  run guards . bash -c 'bash .claude/hooks/test-guard-bash.sh && bash .claude/hooks/test-ship-status.sh' ;;
  esac
done

if [ "$failed" -ne 0 ] || [ "$notrun" -ne 0 ]; then
  echo "verify: FAIL; not stamped"
  exit 1
fi
if [ -n "$dirty" ]; then
  echo "verify: all passed, but code is uncommitted; commit, then run verify.sh again to stamp"
  exit 1
fi
printf '%s\n' "$want" > "$stamp"
echo "verify: PASS; stamped $(git rev-parse --short HEAD)"
rm -rf "$logs"
