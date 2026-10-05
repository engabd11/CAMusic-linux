<p align="center">
  <img src="docs/app-icon.png" alt="CAMusic for Linux" width="120" />
</p>

<h1 align="center">CAMusic for Linux</h1>

<p align="center"><strong>The touchscreen panel for a bit-perfect stereo.</strong></p>

<p align="center">
  A Compose Multiplatform Desktop app that turns a small Linux panel into the control<br>
  surface for MPD and Jellyfin — browse the library, drive the transport, and let<br>
  HueGhost follow the session to your Hue Entertainment areas.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/JDK-21%2B-green" alt="JDK 21+" />
  <img src="https://img.shields.io/badge/MPD-0.24-9cf" alt="MPD 0.24" />
  <img src="https://img.shields.io/badge/local--only-no%20cloud-orange" alt="Local only" />
</p>

---

## What this is

CAMusic for Linux is a **`linux/compose-module`** Gradle module inside this repo —
a Compose Multiplatform Desktop app built for a 10–12" HDMI touchscreen on a
Fedora NUC (a "kiosk panel"), with exactly three jobs:

| Pane | Job | Who actually does the work |
|---|---|---|
| **Player** | Album art, transport, seek, live output readout | **MPD** — the app decodes nothing; MPD owns the sound card, bit-perfect to the DAC (Topping DX5 II in the reference setup: `S32_LE`, 44.1k/48k, 2ch) |
| **Library** | Browse/search Jellyfin albums, artists, queue them | **Jellyfin** — item file paths are mapped onto MPD's library root and queued through MPD's protocol |
| **Lights** | Sync on/off, mode, intensity, offset | **HueGhost** (v2.8) over its REST API — the panel never speaks to the Hue bridge |

The audio path never leaves MPD: Jellyfin supplies *what* to play (and its file
path), MPD supplies *how it sounds* (bit-perfect), and the app is the remote.
A Jellyfin session is reported alongside playback, so anything that follows
Jellyfin sessions — HueGhost's follow mode in particular — can drive the lights
from the panel's queue.

This is a **local, personal-testing build**: no packaging, no releases, no cloud.
It shares its Kotlin sources with the Android app in
[`engabd11/CAMusic`](https://github.com/engabd11/CAMusic) — see
[Relationship to CAMusic](#relationship-to-camusic).

## Status

Working and live-verified end to end on the reference panel:

- Browse Jellyfin (recently added, album detail, search) and queue an album to MPD
- MPD transport: play/pause/next/previous/seek with the DAC reporting the real stream
- Jellyfin `/Sessions` shows the panel's session and now-playing item while MPD plays
- HueGhost control: status read, sync on/off toggle, mode/intensity writes, offset trim
- Both layout modes (landscape / portrait), chosen in Settings, on a WM-less X server
- 26 unit tests green (`:linux:test`)

Not yet (deliberate, by the plan): packaging/installer, auto-play after queueing
(MPD stays paused until you tap play), quality badges on foreign queues.

## Requirements

- **JDK 21+** (Kotlin JVM module; do not pin `jvmTarget` — let it follow the JDK)
- **MPD** reachable at a `host:port` (default `127.0.0.1:6600`)
- **Jellyfin** server with an **API key** (Dashboard → API Keys) and a music
  library whose file location MPD also mounts
- *(optional)* **HueGhost ≥ 2.8** reachable over HTTP with its control token

## Setup — `~/.config/camusic/settings.json`

The Settings pane on the panel edits this file for you (Apply rebuilds the
clients without a restart). Hand-writing it works too:

```json
{
    "layout_mode": "landscape",
    "theme_choice": "dark",
    "mpd_address": "127.0.0.1:6600",
    "jf_url": "http://<jellyfin-host>:8096",
    "jf_token": "<dashboard API key>",
    "jf_user_id": "<user id from GET /Users>",
    "jf_library_id": "<music library id>",
    "jellyfin_path_prefix": "/mnt/media/",
    "hueghost_url": "http://<hueghost-host>:8787",
    "hueghost_token": "<HueGhost control token>",
    "report_to_jellyfin": true
}
```

The one non-obvious key is `jellyfin_path_prefix`. The panel queues tracks by
**file path**: it asks Jellyfin for each item's absolute path, strips the
prefix, and hands MPD the remainder — which must be relative to MPD's
`music_directory`. In the reference setup Jellyfin's Music library lives at
`/mnt/media` and MPD's root is `/mnt/music`, so the prefix is `/mnt/media/`
and `/mnt/media/Artist/Album/01 Track.flac` becomes
`Artist/Album/01 Track.flac`. Tracks whose path does not map are skipped —
never guessed at.

Notes learned the hard way, kept here so the next setup is faster:

- Jellyfin **Dashboard API keys carry no user**: `GET /Users/Me` returns 400.
  Resolve the user id from `GET /Users` once and put it in `jf_user_id`.
- The auth header must be Jellyfin's
  `Authorization: MediaBrowser Client="CAMusic", Device="Linux", DeviceId="…", Version="1.0", Token="…"` form — a
  bare `Bearer` token is the classic way to get a 401 from a healthy server.
- HueGhost **`POST /api/set` is POST-only** (a GET 404s — don't be fooled) with
  keys `mode`, `intensity`, `offset_s`, `enabled`. `/api/status` is a flat dict
  whose `mode`/`intensity` are the *engine's live* values, which can differ
  from the config defaults while a binding drives it. There is no brightness:
  official Hue Sync exposes none.
- Session reporting keys on the Jellyfin item id, while MPD queue addressing
  keys on the MPD path — each queue entry carries both, stamped as
  `scrobbleId`/`scrobbleProvider=mpd` with `id` kept as the Jellyfin id.

### Look and motion options

Every visual extra is a switch in **Settings → Now playing & motion** and
**Settings → Listening mode** (or a key in `settings.json`). None of them touch
playback.

| Key | Default | What it does |
|---|---|---|
| `art_hold_album` | `true` | Holds one cover for the whole album. Jellyfin names embedded art per *track*, so without this every skip reloaded the cover, wash and palette (the flash on skip). |
| `album_transition` | `flip` | `flip` \| `dissolve` \| `slide` \| `off` — how the cover changes when a **new album** starts. A skip inside one album never moves it. |
| `living_art` | `subtle` | `off` \| `subtle` \| `vivid` — from `living_art_delay_s` (default 10 s) into each track, the cover comes quietly alive: an SkSL flow field drifts soft texture (water, sky, smoke) while hard edges such as type stay still, light glints on the highlights, and the print breathes. Pausing settles it. |
| `track_text_motion` | `true` | Title and artist glide over on a track change. |
| `ambient_motion` | `true` | The album's colour blooms drift slowly behind the player and pages. |
| `cover_rests_on_pause` | `true` | Paused, the cover settles back slightly and dims. |
| `listening_mode` | `true` | While music plays and nobody touches the panel for `listening_delay_min` (default 3), the record takes the screen: a large living cover, the title set in a display face, a hairline of progress and the signal path. A tap brings the controls back. `listening_clock` shows the time. |
| `library_retap_home` | `true` | Tapping **Library** while browsing an album or artist returns to the library's home page. |
| `page_transitions` | `true` | Library pages slide; tabs cross-fade. |

## Build, run, test

```bash
# JDK 21 (or newer) on PATH / JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# run on the panel (undecorated, pinned to (0,0), sized to the window state)
./gradlew :linux:run --configure-on-demand

# unit tests (26, pure JVM)
./gradlew :linux:test --configure-on-demand
```

`--configure-on-demand` matters on a box without an Android SDK: the desktop
module never configures `:app`. On a headless box, add a software renderer —
the run task's JavaExec already sets `-Dskiko.renderApi=SOFTWARE`; on real
hardware with a GPU you may drop it for OpenGL. On a window-manager-less X
server (a bare `Xorg :0`), the window self-places: undecorated, `resizable =
false`, pinned at `(0,0)`, exact panel size — there is no WM to decorate,
place or close it, so the app is stopped by stopping the `:run` process.

## Architecture

```
app/src/main/java/com/engabd/sendpin/     ← the Android app's sources (shared FROM)
linux/
├── build.gradle.kts                      # syncSharedSources + deps + run tasks
└── src/main/kotlin/
    ├── com/engabd/sendpin/…              # shared files, synced verbatim at build time
    ├── com/engabd/sendpin/compat/        # shallow Android shims (Context token, BackHandler no-op)
    └── com/engabd/sendpin/linux/         # everything desktop-owned, below
```

**The sharing contract is one list.** `syncSharedSources` copies an explicit
include list from `:app` into `build/sharedSrc` before every compile. The rule
for what may join: *pure* Kotlin only — no `Context`, no DataStore, no
`BuildConfig`, no mid-file `LocalContext` reads. Shared today: the theme
package (`Color`, `Type`, `AccentTextFieldColors`), `mpd/MpdClient`,
`MpdRemote`, `MpdArt`, `jellyfin/JellyfinClient`, `library/JellyfinSource`,
`MusicSource`, `ServerConfig`, `ma/MaModels`, `audio/RemotePlayback`,
`LocalTrack`, `StreamQuality`, `data/LanOnlyCleartext`. Shallow shims live in
`compat/`. Android-deep things get desktop twins or stubs (`data/Http.kt`,
`SubsonicStubs`) or stay unshared (`LocalFavourites`).

**Two forks, both deliberate:** `ui/theme/Theme.kt` (drops the Material You
branch and the Android motion plumbing; everything else verbatim so the look
cannot drift) and the fonts (`AppFont` resolves to the platform default —
Roboto on Android, Noto Sans on Fedora — so `:linux` bundles its own).

**The desktop-only half** (`com/engabd/sendpin/linux/`):

| Piece | Role |
|---|---|
| `Main.kt` | Window (undecorated, panel-sized) + `SendspinTheme(DARK)` |
| `PanelShell.kt` | Two-pane shell, `LayoutMode` from Settings, tab strip, no nav stack |
| `PlayerPane.kt` | Art, title, scrub-committed seek, transport, MPD output readout |
| `LibraryPane.kt` | 6-column grid, album detail, search (350 ms debounce), queue-to-MPD |
| `LightsPane.kt` | HueGhost status every 2 s, mode/intensity chips, offset slider |
| `SettingsPane.kt` | All settings; Apply rebuilds clients live |
| `PanelModel.kt` | Settings → clients; MPD queue model; the 1 Hz poll loop |
| `SessionReporter.kt` | Pure state machine: start/progress/stop, 3 s tick, seek/pause/resume dedup, holds the session through MPD blips |
| `JellyfinToMpd.kt` | Prefix-strip path mapping + the two-identity queue stamp |
| `JellyfinPaths.kt` | `GET /Items?Ids=…&Fields=Path,MediaSources` in one round trip |
| `HueGhostClient.kt` | v2.8 REST: `GET /api/status`, `POST /api/on|off|set` |
| `LinuxSettings.kt` | JSON settings at `~/.config/camusic/settings.json`, Flow per key, atomic writes |

The two-identity rule is the load-bearing detail: a queue entry's `id` stays
the **Jellyfin item id** (what session reporting names), while
`scrobbleId` + `scrobbleProvider="mpd"` carry the **MPD path** (what
`MpdRemote` addresses the queue by). Stamp it the other way round and the
track is addressed by nothing and reported against nothing.

## Relationship to CAMusic

This repository is a working tree of
[`engabd11/CAMusic`](https://github.com/engabd11/CAMusic) (`master`) with the
`linux/compose-module` branch added. The Android app is **untouched** except
one mechanical move: `LocalTrack` lifted from `audio/LocalPlayer.kt` into its
own `audio/LocalTrack.kt` file (26 same-package referrers, zero edits) so the
desktop module can share it. Everything Linux-specific lives in `linux/`.

The Android app remains where the full feature set lives — multiple providers,
the Hue light show, ambience, Music Assistant, AirPlay, TV/Auto/webOS surfaces.
This module is the focused desktop panel for one room.

## License

Apache 2.0, as upstream. See [LICENSE](LICENSE) and
[docs/attributions.md](docs/attributions.md).