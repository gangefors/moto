# Contributing

Thanks for your interest in moto.

## No outside code

moto is a one-person project and doesn't accept code from others: pull requests from outside are closed unmerged. the owner writes all of it and holds its whole copyright, which keeps the project free to publish the official apps (including on the App Store) and to offer commercial licenses. See [ADR-0004](docs/adr/0004-license-agpl-cla.md).

Bug reports and ideas are welcome as [issues](https://github.com/gangefors/moto/issues). Report security problems as described in [`SECURITY.md`](SECURITY.md), not as issues. Under the AGPL you are free to fork the code; a fork must use another name (see the README).

The rest of this page is how the code is written, for anyone reading it.

## License headers

The project is licensed under [AGPL-3.0-only](LICENSE). Every new source file starts with an SPDX header, using the file's comment syntax:

```rust
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors
```

Every `Cargo.toml` sets `license = "AGPL-3.0-only"` (directly or via `license.workspace = true`).

## Third-party code

- Don't copy in code from other projects under GPL or AGPL licenses; it would end the sole copyright and limit relicensing.
- New dependencies must have AGPL-compatible licenses (e.g. MIT, Apache-2.0, BSD, MPL-2.0).

## Tests, performance and security

- Every change comes with tests for what it adds or fixes; bug fixes start with a test that reproduces the bug. CI must be green.
- CI benchmarks each build against the last `main` build's benchmark binary on the same machine. A significant regression (more than 25 % slower for snapping and routing; more than 50 % and 5 ms for opening the region) fails the build: look for a faster approach first, and accept the regression only with a `Perf-Accepted: <reason>` trailer in the commit message when it buys something worth it.
- Security comes before performance and convenience. Treat all external input (downloads, imports, other apps, network responses) as hostile. The full rules are in the Security section of [`CLAUDE.md`](CLAUDE.md); report vulnerabilities as described in [`SECURITY.md`](SECURITY.md).

## Development

See [`CLAUDE.md`](CLAUDE.md) for the architecture, layout, rules and commands (`cargo test --workspace`, `cargo clippy`, `cargo fmt`).
