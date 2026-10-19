# Contributing to Sonora

Thanks for wanting to help. A few ground rules keep things tidy.

## Getting started

1. Fork the repo and create a branch: `git checkout -b feat/your-thing`.
2. Build with `./gradlew assembleDebug` and run it on a device or emulator.
3. Keep commits small and clearly named: `type(scope): short description`,
   e.g. `feat(player): add gapless playback`. The scope is optional.

## What to work on

- The interface lives entirely in `app/src/main/assets/index.html`. You can open
  that file in a browser to iterate on the UI quickly — it falls back to a demo
  library and supports `#home`, `#library`, `#profile` and `#player` deep links.
- Native work (library scanning, playback, permissions) lives in
  `app/src/main/java/com/sonora/app/MainActivity.java`.

## Style

- Vanilla JS, no build step, no frameworks.
- Animate only `transform` and `opacity` — it keeps the app smooth on low-end
  devices.
- Match the existing visual language; don't introduce new dependencies.

## Pull requests

Fill in the pull request template, link any related issue, and include a
screenshot or short clip for anything visual.
