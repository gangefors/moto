---
name: dev-commands
description: Build, test, lint, benchmark and golden-route commands for the moto repo (Rust core, region file, UniFFI bindings, Android app). Use before running local checks, building the app or a region file, running the benchmark or golden routes, generating Kotlin bindings, or measuring coverage.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Commands

**The local checks:** `.claude/scripts/verify.sh 2>&1 | tail -60`. It
picks the checks from what changed, uses CI's pinned versions (installing
them once through `.claude/scripts/setup-env.sh rust|android`), prints
one line per check with only the first errors of a failure, and writes
the stamp the pre-push hook needs when all passed. `--all` runs every
check. Nothing is pushed that fails them. The commands below are for
everything else, and for running one check by hand while fixing it.

Run cargo with the Rust version CI uses (`RUST_VERSION` in
`.github/workflows/android.yml`); newer clippy versions add lints. Pipe
long output through `tail`/`grep` so only the result lands in the
conversation, and don't re-read a file you just wrote.

```sh
cd core
cargo test --workspace
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo fmt --all
cargo deny --locked check   # advisories, licences, sources (deny.toml; cargo-deny 0.20.2)

# Region file and benchmark (CI compares --json output between builds).
# The Skåne region (the M0 region) for golden routes and the benchmark:
# download main's build from the skane-region release (CI publishes it
# when it is built again, about weekly), check it, then use it.
R=https://github.com/gangefors/moto/releases/download/skane-region
curl -fsSL --proto '=https' -O "$R/skane.region.gz" -O "$R/skane.region.gz.sha256"
sha256sum -c skane.region.gz.sha256 && gunzip skane.region.gz
cargo run --release -p moto-regionbuild -- --check skane.region
# Build it yourself only when your change touches the builder or the
# region format. The extract: download.geofabrik.de refuses Claude's
# sessions (never try it); use the same data from the openstreetmap.fr
# mirror:
# https://download.openstreetmap.fr/extracts/europe/sweden-latest.osm.pbf
cargo run --release -p moto-regionbuild -- sweden-latest.osm.pbf skane.region
# Derive curvature and built-up areas afresh for an existing region file,
# without the extract
cargo run --release -p moto-regionbuild -- --refresh skane.region skane-new.region
cargo run --release -p moto-regionbuild -- --check skane.region --json bench.json
python3 ../.github/scripts/bench_compare.py old.json bench.json
# Golden routes: route-quality regression set (run before and after every
# scoring change; see moto-core/tests/golden/README.md)
cargo run --release -p moto-regionbuild -- --golden skane.region moto-core/tests/golden --json golden.json
# Map matching on a real ride exported from the app (Rides → Export)
cargo run --release -p moto-regionbuild -- --match skane.region ride.gpx --geojson ride.geojson
python3 -m unittest discover -s ../.github/scripts -p 'test_*.py'
cargo llvm-cov --workspace --summary-only   # coverage (needs cargo-llvm-cov)

# Kotlin bindings (package se.gangefors.moto.core, see moto-ffi/uniffi.toml)
cargo build -p moto-ffi
cargo run -p moto-ffi --features cli --bin uniffi-bindgen -- \
  generate --library target/debug/libmoto_ffi.so --language kotlin --out-dir <dir>

# Android libs (needs cargo-ndk + ANDROID_NDK_HOME); Gradle runs this for you
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p moto-ffi

# Android app (needs ANDROID_HOME or android/local.properties, cargo-ndk,
# python3 and the aarch64/x86_64-linux-android Rust targets). preBuild runs
# cargo-ndk and generates the UniFFI bindings into app/build/generated/; the
# licence notices (.github/scripts/third_party.py) go into the assets.
cd ../android
./gradlew assembleDebug lintDebug testDebugUnitTest compileReleaseKotlin
```

## Guard rules

The Bash guard (`.claude/hooks/guard-bash.sh` with the policy in
`guard-push.py`, tested by `test-guard-bash.sh`) reads the whole command
text, heredoc bodies included. A seat belt, not a sandbox. If it blocks,
use the accepted form.

| Rejected | Accepted form |
|---|---|
| `git push` joined to anything: `&&`, `;`, pipes, redirects, `$()`, backticks, quotes, variables, `xargs`, `bash -c`, `env`/`sudo` prefixes, `git -C` | `git push -u origin claude/<name>` as its own command, plain characters only |
| Push to `main`, any other branch, another remote or a URL, `refs/...`, `+ref`, `:ref` (delete), `HEAD:other`, several refspecs | `origin claude/NAME`, `origin ccr-NAME`, `origin HEAD` (not with `--force-with-lease` or `--force-if-includes`), `origin A:B` with both of those names |
| `--force`, `-f`, `--delete`, `-d`, `--mirror`, `--all`, `--tags`, `--prune`, `--atomic`, `-o`, `--receive-pack`, an option twice | `-u`/`--set-upstream`, `--force-with-lease[=BRANCH[:SHA]]`, `--force-if-includes`, each once, with an explicit branch for a lease |
| `--no-verify`, `-n` or an abbreviation (`--no-ver`) on commit, merge, rebase, push, am, cherry-pick | no flag: fix what the hook reports |
| A commit message with a push or hook-skipping line in an odd shape, or any command after the heredoc | `[git add <paths> && \| git status && ]git commit [opts] -F - <<'EOF'` with the message as the whole rest of the command (nothing after the closing `EOF`, so run `git log` as a separate call) |
| Any command whose text mentions the git hooks path setting, git config for includes, aliases, remotes, urls or push settings, `git -c`/`--config-env` for these, the environment overrides `GIT_CONFIG*`, `GIT_DIR`, `GIT_WORK_TREE`, `GIT_SSH*`, `GIT_EXEC_PATH`, `GIT_ALTERNATE*`, `GIT_PROXY*` (other `GIT_*` are fine) | none: the session-start hook sets the hooks path; read a key with `git config --get <key>` |
| `git remote add/set-url/rename/remove`; `git send-pack`, `receive-pack`, `http-push`, `update-ref` | `git remote -v`, `git remote get-url origin` |
| One of those protected paths (`.githooks`, `.git/hooks`, `.claude/hooks`, `.claude/scripts`, `.claude/settings.json`, `.git/config`) anywhere in a command that also contains a word like `rm`, `mv`, `cp`, `ln`, `chmod`, `chown`, `tee`, `install`, `dd`, `truncate`, `rsync`, `unlink`, `shred`, `sed -i`, or a `>` redirect to it, even as an argument or in a heredoc body | edit these with the Edit/Write tools; the exec bit cannot be set from a session |
| Any mention of `moto-verif...` (even a read), or `fingerprint.sh` with `>` or `tee` | `.claude/scripts/verify.sh` is the only writer |
| `gh pr merge`; `gh api` with git/refs, merges or graphql, or with a write method or `-f`/`--field`/`--input` on contents, branches or refs; `curl`/`wget` to `api.github.com` with `/graphql`, `/contents`, git/refs or `/merges`, even as a GET | other `gh api` GETs; CI's merge job merges |
| Empty, non-JSON or unreadable hook input; missing `jq` or `python3` | fails closed: install the tool |

`guard-text.sh` guards the GitHub PR and comment tools (create and update
pull request, add issue comment): a body or title with a session link
(`claude.ai/code/session`) or a `Claude-Session:` line is rejected;
remove it. Never put one in commits either.
