#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Stefan Gangefors
#
# Installs what the local checks need, once per machine, at the versions
# CI pins in .github/workflows/android.yml. Idempotent: what is already
# there is skipped. Downloads go over HTTPS and are checked against the
# publisher's checksum before they run.
#
#   setup-env.sh rust      Rust toolchain (+ clippy, rustfmt), cargo-deny
#   setup-env.sh android   the above, plus Android SDK packages, NDK,
#                          cargo-ndk and the Android Rust targets
#
# Prints the environment to use as `export` lines on stdout; progress
# goes to stderr.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
wf="$root/.github/workflows/android.yml"
want=${1:-rust}
case $want in rust|android) ;; *) echo "usage: setup-env.sh rust|android" >&2; exit 64 ;; esac

pin() { sed -n "s/^  $1: \"\{0,1\}\([^\"]*\)\"\{0,1\}$/\1/p" "$wf" | head -1; }
RUST_VERSION=$(pin RUST_VERSION)
CARGO_NDK_VERSION=$(pin CARGO_NDK_VERSION)
CARGO_DENY_VERSION=$(pin CARGO_DENY_VERSION)
[ -n "$RUST_VERSION" ] || { echo "setup-env: RUST_VERSION not found in $wf" >&2; exit 1; }
say() { echo "setup-env: $*" >&2; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# --- Rust
export PATH="$HOME/.cargo/bin:$PATH"
if ! command -v rustup >/dev/null 2>&1; then
  say "installing rustup"
  triple="$(uname -m)-unknown-linux-gnu"
  base="https://static.rust-lang.org/rustup/dist/$triple"
  curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/rustup-init" "$base/rustup-init"
  sum=$(curl -fsSL --proto '=https' --tlsv1.2 "$base/rustup-init.sha256" | cut -d' ' -f1)
  echo "$sum  $tmp/rustup-init" | sha256sum -c --quiet - || { say "rustup-init checksum mismatch"; exit 1; }
  chmod +x "$tmp/rustup-init"
  "$tmp/rustup-init" -y --no-modify-path --profile minimal --default-toolchain none >/dev/null
fi
if ! rustup run "$RUST_VERSION" cargo clippy --version >/dev/null 2>&1 \
   || ! rustup run "$RUST_VERSION" cargo fmt --version >/dev/null 2>&1; then
  say "installing Rust $RUST_VERSION"
  rustup toolchain install "$RUST_VERSION" --profile minimal --component clippy,rustfmt >/dev/null
fi
if [ -n "$CARGO_DENY_VERSION" ] && ! cargo-deny --version 2>/dev/null | grep -q " $CARGO_DENY_VERSION\$"; then
  say "installing cargo-deny $CARGO_DENY_VERSION"
  cargo "+$RUST_VERSION" install cargo-deny --version "$CARGO_DENY_VERSION" --locked -q
fi
echo "export PATH=\"$HOME/.cargo/bin:\$PATH\""
[ "$want" = android ] || exit 0

# --- Android
command -v java >/dev/null 2>&1 || { say "Java 21 is missing; install a JDK 21 first"; exit 1; }
props="$root/android/local.properties"
sdk=${ANDROID_HOME:-}
[ -n "$sdk" ] || sdk=$(sed -n 's/^sdk\.dir=//p' "$props" 2>/dev/null | head -1 || true)
[ -n "$sdk" ] || sdk="$HOME/android-sdk"
mgr="$sdk/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$mgr" ]; then
  say "installing Android command-line tools into $sdk"
  index=https://dl.google.com/android/repository/repository2-3.xml
  curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/repo.xml" "$index"
  read -r zip sha1 < <(python3 - "$tmp/repo.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for p in root.findall("remotePackage"):
    if p.get("path") != "cmdline-tools;latest":
        continue
    for a in p.iter("archive"):
        if a.findtext("host-os") == "linux":
            c = a.find("complete")
            print(c.findtext("url"), c.findtext("checksum"))
            sys.exit(0)
sys.exit(1)
PY
)
  case $zip in commandlinetools-linux-*_latest.zip) ;; *) say "unexpected archive name: $zip"; exit 1 ;; esac
  case $sha1 in [0-9a-f]*) [ "${#sha1}" -eq 40 ] || { say "bad checksum in the index"; exit 1; } ;; *) say "bad checksum in the index"; exit 1 ;; esac
  curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/$zip" "https://dl.google.com/android/repository/$zip"
  echo "$sha1  $tmp/$zip" | sha1sum -c --quiet - || { say "command-line tools checksum mismatch"; exit 1; }
  mkdir -p "$sdk/cmdline-tools"
  rm -rf "$sdk/cmdline-tools/latest"
  python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$tmp/$zip" "$tmp/x"
  mv "$tmp/x/cmdline-tools" "$sdk/cmdline-tools/latest"
  chmod +x "$sdk/cmdline-tools/latest/bin/"*
fi
# The packages CI installs (Google's own; only their licences are accepted).
mapfile -t pkgs < <(sed -n '/^  ANDROID_PACKAGES:/,/^$/p' "$wf" | sed -n 's/^    \([a-z-]*;[^ ]*\)$/\1/p')
[ "${#pkgs[@]}" -gt 0 ] || { say "ANDROID_PACKAGES not found in $wf"; exit 1; }
missing=()
for p in "${pkgs[@]}"; do [ -d "$sdk/${p//;//}" ] || missing+=("$p"); done
if [ "${#missing[@]}" -gt 0 ]; then
  say "installing ${missing[*]}"
  yes 2>/dev/null | "$mgr" --sdk_root="$sdk" --licenses >/dev/null || true
  "$mgr" --sdk_root="$sdk" "${missing[@]}" >/dev/null
fi
ndk_pkg=$(printf '%s\n' "${pkgs[@]}" | grep '^ndk;' | head -1)
ndk="$sdk/ndk/${ndk_pkg#ndk;}"
rustup target add --toolchain "$RUST_VERSION" aarch64-linux-android x86_64-linux-android >/dev/null 2>&1
if ! cargo ndk --version 2>/dev/null | grep -q " $CARGO_NDK_VERSION\$"; then
  say "installing cargo-ndk $CARGO_NDK_VERSION"
  cargo "+$RUST_VERSION" install cargo-ndk --version "$CARGO_NDK_VERSION" --locked -q
fi
grep -q '^sdk\.dir=' "$props" 2>/dev/null || echo "sdk.dir=$sdk" >> "$props"
echo "export ANDROID_HOME=\"$sdk\" ANDROID_NDK_HOME=\"$ndk\""
