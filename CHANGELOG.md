# Changelog

## v1.1.0

- **Background playback** — the player now runs in a foreground service, so music
  keeps playing when you leave the app, with lock-screen, notification and
  Bluetooth controls via a `MediaSession`.
- **Home-screen widget** — a now-playing widget showing the current track with a
  play/pause button.
- **Real tile filters** — the home tiles now browse the library: Favorites,
  Albums (grouped), Artists (grouped) and Songs.
- **Favorites** — long-press any song to save it; tap the heart on the
  now-playing screen.

## v1.0.0

Initial release.

- Warm amber interface: home tiles, pill search, sliding bottom nav.
- Reads the device's audio library via `MediaStore`.
- Full playback: play/pause, seek, next/previous, shuffle, repeat off/all/one.
- Now-playing screen with artwork and a draggable progress bar.
- Mini player above the navigation bar.
- Instant library search.
- Offline by design — no network permission, no third-party dependencies.
