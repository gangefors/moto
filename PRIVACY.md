<!--
  SPDX-License-Identifier: AGPL-3.0-only
  Copyright (C) 2026 Stefan Gangefors
-->

# Privacy

*Last updated: 28 September 2026*

moto is a motorcycle routing app made by Stefan Gangefors as a hobby project. It is built to keep your riding data on your phone. This page says what the app does with your data and which outside services it talks to.

## In short

- Your location, rides, saved sections, routes and settings stay on your phone. The app never sends them to the developer or anyone else.
- The app has no account, no ads, no analytics, no crash reporting and no tracking.
- To show the map and install map regions, the app fetches files from two outside services, OpenFreeMap and GitHub. They see your IP address and what is fetched; the developer does not.

## What stays on your phone

- **Location.** The app uses your phone's GPS to show where you are, to plan routes and loops from there, and to record a ride when you start one. Recording runs as a service with a visible notification while you ride; the app never asks for location access in the background.
- **Your data.** Recorded rides, saved sections and their ratings, saved routes and your settings are stored in the app's private storage, which other apps can't read.
- **No backups.** The app is excluded from Android's cloud backup and from device-to-device transfer, so your data isn't copied to Google Drive or a new phone. Uninstalling the app deletes it.
- **Map regions.** Downloaded road data and the map's tile cache are also kept in the app's storage.

## What leaves your phone

- **Map tiles from [OpenFreeMap](https://openfreemap.org).** To draw the map, the app downloads map tiles and the map style. OpenFreeMap's servers receive your IP address and which tiles are requested, which shows roughly which area of the map you are looking at (not necessarily where you are). Their handling of this is covered by their own privacy policy.
- **Map regions from GitHub.** When you look for or download a region (My data → Map region), the app fetches the region list and the region file from this repository's releases on [GitHub](https://github.com). GitHub receives your IP address and which file was fetched, under [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement). The developer only sees how many times each file was downloaded, never who downloaded it.
- **What you share yourself.** When you share or save a route, ride or sections (GPX, GeoJSON), the file goes only where you send it, for example your navigation app or a folder you pick.

Nothing else is sent. The app talks to no other servers.

## Debug builds

Development builds from GitHub (`debug-latest`) also have a Debug tools screen for measuring speed and memory. What it records (timings, memory figures and distances, but no coordinates) stays on the phone, in the app and in Android's system log on the phone. It is only shared if you copy the report and send it yourself. Release builds don't have it.

## Children

The app is made for adult motorcyclists and is not directed at children.

## Changes

Changes to this policy are made in this file; its history on GitHub shows every change.

## Contact

Questions about privacy: open an issue at [github.com/gangefors/moto/issues](https://github.com/gangefors/moto/issues). To report a security or privacy problem privately, use GitHub's private vulnerability reporting as described in [`SECURITY.md`](SECURITY.md).
