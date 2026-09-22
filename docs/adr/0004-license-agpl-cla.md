# ADR-0004: License — AGPL-3.0-only + CLA + trademark

**Status:** Accepted · **Date:** 2026-09-22 · **Deciders:** Stefan

## Context

The project should be open source, but nobody should be able to make money from it without sharing their implementation and improvements. Stefan wants to keep the option to make money from it himself, for example with paid apps, a paid community/sync service or commercial licenses. The app must be publishable on Apple's App Store (iOS port later) as well as Google Play. A server for community ratings is planned (P2), so modified versions could be run as a service without ever being distributed.

## Decision

1. License the code under **AGPL-3.0-only** (SPDX: `AGPL-3.0-only`).
2. Require a **Contributor License Agreement (CLA)** for all outside contributions. It grants Stefan the right to relicense contributed code. Contributors keep their copyright.
3. Treat the **app name and logo as trademarks**, not covered by the code license. Forks must rebrand.
4. **No app-store exception** for now. Stefan, as copyright holder with CLAs covering all outside code, publishes the official apps under his own terms. This can be revisited.

## Options Considered

| Option | Assessment |
| --- | --- |
| **AGPL-3.0 (chosen)** | Anyone who distributes a modified version, or lets users use it over a network, must share the source. Closes the server loophole for the P2 backend. − Some companies avoid AGPL code, so there may be fewer corporate contributors. |
| **GPL-3.0** | Same share-back rule for distributed apps, but running modified code on a server requires no sharing. |
| **MPL-2.0** | Only changed files must be shared; the code can be wrapped in a closed app. Too weak for the goal. |
| **Source-available (PolyForm Noncommercial, BSL, FSL)** | Can ban commercial use, but is not open source and doesn't require sharing improvements. Doesn't match the goal. |
| **"-or-later" vs "-only"** | "-only" keeps any future license change in Stefan's hands rather than the FSF's. |

## How monetisation still works

A license binds people who receive the code, not the copyright holder. With sole ownership (Stefan's code plus CLAs for everyone else's), Stefan can:

- publish paid or free apps on Google Play and the App Store under his own terms, avoiding the known conflict between Apple's store terms and GPL-family licenses
- sell commercial (non-AGPL) licenses, e.g. to embed the routing engine in a closed product
- run a paid community/sync service. Competitors running modified copies must publish their changes under AGPL.
- keep premium features closed if ever wanted (dual licensing / open core)

## Consequences

- **Easier:** share-back is enforced for apps and servers; commercial options stay open.
- **Harder:** a CLA adds friction for contributors, and every outside contribution must be CLA-covered **before** merging. Otherwise relicensing and App Store publishing of that code get complicated.
- **Third-party code:** dependencies must be AGPL-compatible (MapLibre BSD-2, UniFFI MPL-2.0 and MIT/Apache crates are fine). Anything copied in under GPL-only/AGPL from others is **not** CLA-covered and would limit relicensing, so avoid it.
- **OSM data** stays under ODbL regardless: attribution is required, and derived region files carry ODbL share-alike obligations.
- Not legal advice: have a Swedish IP lawyer review the CLA and trademark before selling anything.

## Action Items (for Claude Code / repo)

- [ ] Add `LICENSE` with the full, unmodified AGPL-3.0 text (from [gnu.org/licenses/agpl-3.0.txt](https://www.gnu.org/licenses/agpl-3.0.txt)).
- [ ] Add an SPDX header to source files: `// SPDX-License-Identifier: AGPL-3.0-only` and `// Copyright (C) 2026 Stefan Gangefors`.
- [ ] Set `license = "AGPL-3.0-only"` in `Cargo.toml` files.
- [ ] README "License" section (draft below).
- [ ] `CONTRIBUTING.md`: contributions require signing the CLA; set up the CLA Assistant GitHub app. Base the CLA on a standard template (e.g. Apache ICLA style or a Contributor Agreements .org template) with the right to relicense.
- [ ] Add `cargo-deny` license check to CI with an allow-list of AGPL-compatible licenses.
- [ ] Add an in-app "About / Licenses" screen: app license, OSM/ODbL attribution, OpenFreeMap attribution, third-party licenses.
- [ ] Later: register the app name as a trademark (Swedish PRV / EUIPO) once the name is settled.

### README license section (draft)

```markdown
## License

Copyright (C) 2026 Stefan Gangefors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License, version 3 only (AGPL-3.0-only). See [LICENSE](LICENSE).

If you distribute a modified version, or let others use it over a network, you must make your source code available under the same license.

Commercial licenses without the AGPL obligations are available on request.

The app name and logo are trademarks and are not covered by the code license. Forks must use a different name.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).

Contributions require signing the Contributor License Agreement. See [CONTRIBUTING.md](CONTRIBUTING.md).
```
