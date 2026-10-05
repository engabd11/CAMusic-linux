# Contributing to CAMusic for Linux

## Getting started

1. Fork the repository and clone your fork.
2. Install JDK 21 or newer.
3. Create a feature branch: `git checkout -b feat/my-feature`.
4. Run the panel with `./gradlew :linux:run` (it reads `~/.config/camusic/settings.json`;
   see the README for the keys).

## Project structure

```
linux/
├── build.gradle.kts            # dependencies, run / preview / smoke tasks, app image
└── src/
    ├── main/kotlin/com/engabd/sendpin/
    │   ├── mpd/ jellyfin/      # the MPD and Jellyfin clients
    │   ├── library/ ma/        # the MusicSource seam and item models
    │   ├── audio/              # RemotePlayback, LocalTrack, StreamQuality
    │   ├── ui/theme/ ui/design/  # colours, bundled type, palette, glass, seek bar
    │   └── linux/              # the panel: shells, panes, settings, screensaver, listening view
    ├── main/resources/         # bundled fonts and the window icon
    └── test/kotlin/            # unit tests, plus preview/RenderPreviews.kt
```

## Code conventions

- **Kotlin:** official style (`kotlin.code.style=official`); match the surrounding code's
  comment density and naming.
- **Commit messages:** `linux: <what changed>` in the existing history's style.
- **Looks are options:** anything that changes how the panel looks or moves is a setting
  in `LinuxSettings` with a switch in `SettingsPane`, so it can be turned off.
- **Playback stays MPD's:** the panel is a remote; it never decodes audio.

## Testing

- **Unit tests:** `./gradlew :linux:test` (kotlin-test, coroutines-test; no mocking library).
  Keep decisions in pure functions so they can be tested without a screen.
- **Previews:** `./gradlew :linux:renderPreviews` draws every pane with a fake library into
  `linux/build/previews/*.png`. CI uploads them as an artifact on every push.

## Pull requests

1. Make sure `./gradlew :linux:test` passes.
2. Add tests for new logic, and a preview for a new screen.
3. Update the README if you add a setting.
4. Open the PR against `linux/compose-module`.

## Questions?

Open an issue or a discussion on GitHub.
