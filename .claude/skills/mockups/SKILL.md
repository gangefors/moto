---
name: mockups
description: How to build and revise UI mockups for the moto app as published HTML artifacts, from the shared kit in docs/mockups (stylesheet, map backgrounds, template). Use whenever the rider asks for a mockup, a revision of one, or a visual proposal of a screen.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Mockups

Mockups look as close to the real app as possible, never ASCII sketches:
an HTML page published as an artifact, with phones at real size (360 dp
wide) and the app's own Material 3 colours (light and dark), Roboto type
scale, components at their real dp sizes, strings from `strings.xml` and
icons from `res/drawable`. Mark what changes (pink), and show the current
screen beside the proposal when something moves.

## The kit (docs/mockups)

- `mockup.css`: page chrome, phone frame and every component drawn so far.
- `mockup.js`: the Light/Dark page buttons.
- `map-light.png`/`map-dark.png`, `map2-…`, `map3-…`: three curvy roads
  (no route drawn) in the app's light and dark map styles; classes
  `mapl`/`mapd`, `mapl2`/`mapd2`, `mapl3`/`mapd3`. Draw routes and
  favourites over them as SVG. Publish only the ones the page uses.
- `template.html`: a page with one phone; copy it to the scratchpad.

Publish the page with the kit beside it, so it is never inlined or read:

```
files: {"mockup.css": "docs/mockups/mockup.css",
        "mockup.js": "docs/mockups/mockup.js",
        "map-light.png": "docs/mockups/map-light.png",
        "map-dark.png": "docs/mockups/map-dark.png"}   # plus map2/map3 if used
```

## Keeping tokens down

- Never read a whole earlier mockup artifact, and never inline the map
  images as base64. For a component the kit lacks, grep `mockup.css` and
  the composable; read an earlier artifact only with `path` (saved to disk)
  and grep the file for the one component.
- Draw icons from `res/drawable` (convert the vector path to an inline
  SVG); grep strings in `strings.xml`.
- Check the mockup against the composables it draws (padding, type style,
  component) and fix any drift in `mockup.css` first.
- One rendered screenshot check is enough for a small revision; render
  only the changed phone.
- New or changed components go into `docs/mockups/mockup.css` (its own
  commit), so the next mockup starts from them.
- Answering a mockup comment (a rename, a colour) is a small edit: change
  the page file and republish; don't rebuild the page.
