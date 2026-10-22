---
name: ui-rules
description: The moto app's UI rules (edge to edge and insets, system bar icons, fit at 360 dp and large fonts, bin icon for deletes, icons on the how-to page) and the mockup rules. Load before designing, building, mocking up or reviewing anything a rider sees on screen (Compose, layouts, strings with icons, mockups).
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# UI rules

These are as binding as CLAUDE.md; they live here so sessions that don't
touch the screen don't carry them. Wording of user-facing text ("favourite
sections", British English) stays in CLAUDE.md because the core's messages
follow it too.

- UI draws edge to edge, but interactive or informational elements (buttons, map controls, attribution, text) must never sit under the status bar, navigation bar or a display cutout; offset them by `WindowInsets.safeDrawing`. System bar icons must stay readable: keep the status bar fully transparent and switch its icons between light and dark to contrast with whatever is behind them, whatever the map style or overlay (like Google Maps); give the navigation bar a translucent scrim in the app theme's colour (light or dark: it follows the phone until the rider picks one in the menu), with icons that follow that theme; where an app panel (like the planning sheet) reaches down behind the bar, its icons contrast with that panel instead.
- UI elements must fit or scale, never squeeze: rows of buttons wrap onto another line (`FlowRow`) when there is no room, button labels stay on one line, and every screen, sheet and banner must work at 360 dp width and with large system font sizes.
- Delete and remove actions use the bin icon (`ic_delete`, in `DELETE_COLOR`; `DeleteButton` where a second tap confirms), never a text-only button. Clearing a single value (like an arrival time) is an X right beside it.
- The how-to page shows a button's own icon inline wherever its text refers to a button or other on-screen control, so the rider can find it. Elsewhere, add an icon to text only where it helps in that spot (e.g. pointing at a button on the same card).

## Mockups

- Mockups look as close to the real app as possible, never ASCII sketches: an HTML page published as an artifact, with phones at real size (360 dp wide), the app's own Material 3 colours (light and dark), Roboto, components at their real dp sizes, strings from `strings.xml` and icons from `res/drawable`, built from the kit in `docs/mockups/`. Mark what changes, and show the current screen beside the proposal when something moves. The `mockups` skill has the details.
The `mockups` skill has the kit and the steps.
