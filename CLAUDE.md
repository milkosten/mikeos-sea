# MikeSea — CLAUDE.md

Native MikeOS app-agent for live marine data. **Read the ecosystem hub first:**
`../mikeos-architecture/ecosystem/README.md` and `docs/APP-ANATOMY.md` (the app contract:
§0 self-registration, the closed loop, the heartbeat, the Agent Inspector).

## What this app is
MikeSea shows **live vessel traffic (AIS) near the user** and lets them search harbours /
coastal places. It consumes **`marine-api.osmike.com`** (the marine backend on the 242 box:
Kystverket live AIS cached on a 10 GB NVMe Redis + Photon-proxied geocoding). Its web sibling
is `marine.osmike.com` / `sea.osmike.com`.

## Layout
- `app/src/main/java/com/mikeos/core/**` — the vendored **MikeAgent SDK runtime** (self-registration,
  hive socket, heartbeat FGS, Agent Inspector). Self-contained; no back-references to the app package.
- `app/src/main/java/com/mikeos/sea/**` — the app:
  - `MainActivity.kt` — Compose marine dashboard (nearby vessels list + search + vessel detail),
    wires the Agent Inspector icon and boots the agent.
  - `net/MarineApi.kt` — OkHttp client for `marine-api.osmike.com` (`/live/vessels`, `/search`, `/health`).
  - `agent/SeaMikeAgent.kt` — boots `MikeAgent` with a marine Soul + skills (`nearby_vessels`, `find_place`);
    sets the heartbeat perception to the live vessel picture around the user's focus.
  - `ui/theme/Theme.kt` — dark sea palette.

## Conventions (MikeOS-wide)
- **Native Kotlin/Compose only, never a WebView.** minSdk 31, compile/target 35, AGP 8.7, Kotlin 2.0.
- **Every app self-registers (§0)** via the on-device daemon and keeps the Agent Inspector icon — both done in `SeaMikeAgent.boot` + `MainActivity`.
- **Build:** `./gradlew assembleDebug --no-daemon --max-workers=2`.
- **Ship via OTA, not adb:** bump `versionCode` (+ `versionName`), build, publish to `mikeos-appstore`
  under the applicationId `com.mikeos.sea`. Keep the shared debug keystore (`~/.android/debug.keystore`)
  so in-place updates work. See `../mikeos-architecture/docs/PUBLISHING-APP-UPDATES.md`.
- **Push over SSH** (`~/.ssh/mikeos_git_deploy`); the PAT can create repos but cannot push.

## Backend contract (marine-api.osmike.com)
- `GET /live/vessels?bbox=minLon,minLat,maxLon,maxLat&points=true` → FeatureCollection of vessel Points.
- `GET /search?q&lat&lon` → `{results:[{name,label,lat,lon,kind,country}]}` (distance-re-ranked).
- `GET /reverse?lat&lon`, `GET /health` → `{live:{count,updated_at}}`.
Attribution to display: **Live AIS © Kystverket (NLOD 2.0)**.
