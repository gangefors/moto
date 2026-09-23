# Security

Security comes first in moto: a secure implementation is always chosen over a faster or simpler one. The rules the project follows are in the Security section of [`CLAUDE.md`](CLAUDE.md).

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's private vulnerability reporting: the **Security** tab of this repository → **Report a vulnerability**. Don't open a public issue for a security problem.

Include what you found, how to reproduce it, and what an attacker could do with it. You'll get an answer as soon as possible; moto is a hobby project, so please allow a few days.

## Scope

In scope: the Android app, the Rust core (including how it reads region files and other downloaded or imported data), the region builder, and the CI workflows. Out of scope: third-party services the app talks to (map tiles, OSM data providers).

## Automated checks

- **Dependencies:** Dependabot alerts and security updates for the Rust crates, the Gradle dependencies, the CI's Python tools and the GitHub Actions; `cargo deny` fails CI on a RustSec advisory, a licence that isn't AGPL-compatible or a crate from outside crates.io (`core/deny.toml`).
- **Code:** CodeQL scans the Kotlin app, the Rust core, the CI scripts and the workflows on every push to `main`, every pull request and weekly.
- **Workflows:** zizmor lints the GitHub Actions workflows; actions are pinned by commit SHA and every job gets the least permissions it needs.
- **Secrets:** GitHub secret scanning with push protection.

A dependency release that fixes a published vulnerability is taken in once it is a day old, reviewed and green in CI; other dependency updates wait until the release is at least a week old.
