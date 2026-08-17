repo: forchinmaser/mta-status-MMD
branch: main
upstream-dependency: mudita/MMD @ master — vendored at android/mmd-core (Apache-2.0)
path: (README + documented public API only — repo not connected via GitHub tools)

## Last sync
date: 2026-08-17T18:16:19Z
source: MMD-master archive supplied by the user (uploads/MMD-master), read directly

### Updated in this project
- Prototype and Kotlin brought to parity: one row pitch, two chip widths, full-screen alert card that pages by text lines, badge matching the tapped chip.
- MTA alert text normalised at the feed boundary in both (icon placeholders, route bullets, pipe-delimited station runs).
- Target repo forchinmaser/mta-status-MMD identified; push instructions in PUSH-TO-GITHUB.md (read-only access here, so the commit is yours to make).
- Vendored MMD as a local Android library module `android/mmd-core` (Apache-2.0, LICENSE retained); upstream androidMain sources unmodified.
- App now themed by `ThemeMMD` (eInkColorScheme + eInkTypography/Lato, ripple off); removed the local MmdCompat shim.
- Stop list and route grid rebuilt on `LazyColumnMMD` (scrollStep 5 stops / 2 chip rows) — MMD's own discrete scrolling and vertical scrollbar replace the hand-rolled pager.
- Surfaces switched to TopAppBarMMD, ButtonMMD/OutlinedButtonMMD, CardMMD, FilterChipMMD, HorizontalDividerMMD, TextMMD.
- Added dev/prod flavors so `prodDebug` exists; toolchain moved to Kotlin 2.0.20 + AGP 8.5.2 + material3 1.3.1.

## Previous sync
date: 2026-08-14T00:00:00Z
source: README.md read over the public web (github.com/mudita/MMD)

### Updated in this project
- Scaffolded native Kotlin + Compose app under `android/` following MMD conventions.
- Added `ui/theme/MmdCompat.kt` mirroring `ThemeMMD` (eInk color scheme, eInk typography, ripple off) until the MMD artifact is published.
- Alias file `ui/theme/Mmd.kt` is the single seam to swap in `com.mudita.mmd.*`.

## Sync history
- 2026-08-14 — mudita/MuditaOS-K-Kernel-opensource (master) reviewed for keypad codes. It is a MediaTek-based Linux kernel tree; the keypad map is in the device tree / MTK keypad driver, not reachable without a repo connection. Hardware paging was therefore built to accept common paging keys and log unmapped ones (tag `KompaktKeys`).

## Screen map
| Screen | Built from |
| --- | --- |
| Home / mode picker | transit-kompakt-prototype.html `homeHTML` |
| Select Train | prototype `trainListHTML` |
| Select Borough / Bus routes | prototype `boroughListHTML`, `busRouteListHTML` |
| Route detail + paged stop list | prototype `detailHTML`, paging logic |
| Theme | mudita/MMD README (ThemeMMD, eInkColorScheme, eInkTypography, no ripple) |
