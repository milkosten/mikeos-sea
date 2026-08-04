# MikeSea 🌊

A MikeOS app-agent for **what's on the water near you**. MikeSea shows **live vessel
traffic** (AIS) around you, lets you search harbours and coastal places, and embeds the
standard MikeOS agent so it can answer marine questions and message sibling apps.

It's the native companion to the MikeSea web viewer (`marine.osmike.com`) and consumes the
same backend, **`marine-api.osmike.com`** — live vessels from the Kystverket AIS feed
(NLOD) cached on the 242 box, plus Photon-proxied harbour/place search.

## What it does
- **Nearby vessels** — a live list of ships around your focus point (name, type, speed,
  distance, destination); tap one for full detail (MMSI, callsign, course, position).
- **Search** — find a harbour or coastal place (view-biased) and re-focus the water there.
- **Agent** — embeds the shared MikeAgent runtime: §0 self-registration with the on-device
  daemon, a resident heartbeat, the live hive, and the mandatory Agent Inspector. Its two
  marine skills (`nearby_vessels`, `find_place`) run over the live AIS feed.

## Build & ship
```bash
./gradlew assembleDebug --no-daemon --max-workers=2
# → app/build/outputs/apk/debug/app-debug.apk   (com.mikeos.sea)
```
Ship via **OTA** (not adb) — bump `versionCode`, build, publish to `mikeos-appstore`; the
on-device daemon Updater installs it. See `../mikeos-architecture/docs/PUBLISHING-APP-UPDATES.md`.

## Structure
- `com.mikeos.core.*` — the vendored MikeAgent SDK runtime (self-registration, hive,
  heartbeat, Agent Inspector). Shared across MikeOS apps; do not fork casually.
- `com.mikeos.sea.*` — the app: `MainActivity` (Compose dashboard), `net/MarineApi`
  (marine-api client), `agent/SeaMikeAgent` (marine soul + skills), theme.

Native Kotlin + Jetpack Compose, minSdk 31 / target 35. Never a WebView.
