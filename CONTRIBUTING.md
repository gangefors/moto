# Contributing

Thanks for your interest in moto.

## Contributor License Agreement

All outside contributions require signing the [Contributor License Agreement](CLA.md) **before** they can be merged. You keep the copyright to your contribution; the CLA grants Stefan Gangefors a copyright license, including the right to relicense, and a patent license. This keeps the project able to publish official apps (including on the App Store) and offer commercial licenses. See [ADR-0004](docs/adr/0004-license-agpl-cla.md) for the reasoning.

When you open a pull request, the CLA Assistant bot checks whether you have signed. If not, it posts a comment explaining how: reply on the pull request with the sentence it asks for. You only need to sign once.

## License headers

The project is licensed under [AGPL-3.0-only](LICENSE). Every new source file starts with an SPDX header, using the file's comment syntax:

```rust
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors
```

Every `Cargo.toml` sets `license = "AGPL-3.0-only"` (directly or via `license.workspace = true`).

## Third-party code

- Don't copy in code from other projects under GPL or AGPL licenses; it can't be covered by the CLA and would limit relicensing.
- New dependencies must have AGPL-compatible licenses (e.g. MIT, Apache-2.0, BSD, MPL-2.0).

## Development

See [`CLAUDE.md`](CLAUDE.md) for the architecture, layout and commands (`cargo test --workspace`, `cargo clippy`, `cargo fmt`).
