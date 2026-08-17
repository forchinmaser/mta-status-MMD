# Transit for Mudita Kompakt — Android source

Native Kotlin + Jetpack Compose, built on **Mudita Mindful Design (MMD)**.
Live MTA data, every subway line and every bus route.

## Modules

| Module | What it is |
| --- | --- |
| `:mmd-core` | MMD, vendored from github.com/mudita/MMD (Apache-2.0, `LICENSE` included). Upstream sources unmodified. |
| `:app` | This app. |

Upstream MMD is a Kotlin Multiplatform library with an `androidMain`-only source
set and no published Maven artifact. Here its sources sit at
`mmd-core/src/main/java` and it builds as a plain Android library, so the build
needs no KMP, compose-multiplatform, dokka, or maven-publish plugins. Namespace
stays `com.mudita.mmd`, so every import and `R` reference is unchanged — if MMD
is published later, delete the module and depend on the artifact instead.

## What comes from MMD

- `ThemeMMD` wraps the app: `eInkColorScheme` (pure black on pure white) and
  `eInkTypography` (Lato), ripple disabled globally. The app defines no theme.
- `LazyColumnMMD` is every scrolling region — stop diagram, route grid, open
  alert card. It scrolls in discrete steps and draws MMD's chevron scrollbar.
- `TopAppBarMMD`, `ButtonMMD` / `OutlinedButtonMMD`, `CardMMD`, `FilterChipMMD`,
  `HorizontalDividerMMD`, `TextMMD` carry every other surface.

## Ghosting discipline

Everything in `ui/Design.kt` exists to keep an element on the same pixels from
one screen to the next, so the panel never repaints a boundary that did not move:

- **One row pitch** (70dp + 8dp) for every full-width row. The home wordmark
  occupies the first two row slots of the same stack the mode buttons sit in, so
  TRAIN and BUS land exactly where the third and fourth borough rows land.
- **Two chip widths only** — 58dp for 2-4 characters, 124dp for long/SBS codes —
  in explicit fixed-pitch rows (four narrow per row, two wide), so a route chip
  is in the same column on every page of every borough.
- **The detail badge matches the tapped chip** in shape, size and position.
- **Discrete paging everywhere**: 5 stops per step; a full page per grid step; a
  text page less two lines of context in the alert card.
- **The open alert card takes the whole screen and the diagram is not drawn** —
  alert text and diagram never compete for the same pixels.
- Fixed top bar height, no window animations, no ripple.

## Route data

Built from **MTA GTFS static feeds** — every subway line and every bus route,
with real stop order:

- `data/GtfsSources.kt` — feed URLs (subway, one per bus borough, MTA Bus Company).
- `data/GtfsImporter.kt` — streams the zip; reads `routes.txt`, `trips.txt`,
  `stops.txt`, `stop_times.txt`; takes the longest trip per route+direction as the
  representative pattern. `stop_times.txt` is walked twice (count, then collect) so
  peak memory is one route's stops, not one feed's — the Queens bus feed is
  hundreds of MB uncompressed.
- Parsed output is cached in `filesDir` as JSON; the zip stays in `cacheDir`.
- `assets/routes.json` is the first-run/offline fallback and the `dev` flavor's
  entire dataset.

Alerts come from MTA's GTFS-realtime service-alert feeds (no API key).
`MtaAlertsClient.normalise()` strips the feed's `[accessibility icon]` /
`[shuttle bus icon]` placeholders, expands `[1][2][N]` route bullets, and turns
pipe-delimited station runs into lines — without it the raw text renders as
punctuation soup.

## Network policy

- Route schedules download on the tap that needs them: TRAIN starts the subway
  feed; tapping a borough starts that borough's feed.
- Alerts prefetch on the same tap, cached in memory for 5 minutes.
- Nothing refreshes in the background. There is no WorkManager, AlarmManager,
  JobScheduler, Service, or boot receiver in the project, and every fetch runs in
  `viewModelScope`, which dies with the Activity.

## Build variants

`devDebug`, **`prodDebug`**, `devRelease`, `prodRelease`.

- `prod` — live MTA feeds (`USE_LIVE_FEEDS = true`).
- `dev` — bundled sample data only, no network; installs alongside prod as
  `com.example.transitkompakt.dev`.

Running `prodDebug` on the device: see **BUILD-ON-MAC.md**.

## Reference prototype

`../transit-kompakt-prototype.html` is the browser mock the layout was tuned in.
It pulls live MTA data too, but from the CORS-enabled data.ny.gov endpoints and
derives stop ORDER geographically, because GTFS `stop_times` is not fetchable
from a browser. The app uses the real GTFS sequence. Treat the prototype as the
layout spec and the device build as the truth.

## Known gaps before ship

- `minSdk` is 26 as a guess; confirm with `adb shell getprop ro.build.version.sdk`.
- Stop-level alert matching compares MTA `stop_id` values against display names;
  wire GTFS static `stops.txt` for exact matching.
- First subway import is a ~10 MB download; a large bus borough is bigger and can
  take a minute to parse on device. Screens show download/parse status meanwhile.
- Feed URLs are `http://web.mta.info/...`, hence `usesCleartextTraffic`.
- No feed staleness check: clear app storage to force a re-import.
- Hardware paging listens on DPAD/volume/page/soft keys and logs unmapped keys
  under tag `KompaktKeys`; add the Kompakt's real codes in `ui/HardwareKeys.kt`.
- Nothing here has been compiled. Expect to fix a few call-site errors on the
  first Gradle sync.
