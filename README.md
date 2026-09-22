# moto

General-purpose navigation apps optimise for speed and send motorcyclists onto motorways and straight arterials. moto is an Android route planner that prefers fun roads instead: capture your favourite road sections with a few taps (on the map, while riding, or from a recorded ride), and generate one-way and round-trip routes that deliberately pass through them and through curvy roads, within a detour budget you set. Routing runs offline on the phone in a Rust core, and routes export as GPX to your usual nav app.

- Architecture decisions: [`docs/adr/`](docs/adr/)
- Rust core: [`core/`](core/) — `cargo test --workspace`
- Contributing: [`CONTRIBUTING.md`](CONTRIBUTING.md)

## License

Copyright (C) 2026 Stefan Gangefors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License, version 3 only (AGPL-3.0-only). See [LICENSE](LICENSE).

If you distribute a modified version, or let others use it over a network, you must make your source code available under the same license.

Commercial licenses without the AGPL obligations are available on request.

The app name and logo are trademarks and are not covered by the code license. Forks must use a different name.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).

Contributions require signing the Contributor License Agreement. See [CONTRIBUTING.md](CONTRIBUTING.md).
