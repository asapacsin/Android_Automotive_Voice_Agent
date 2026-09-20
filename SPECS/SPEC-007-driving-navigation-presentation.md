# SPEC-007 — Active driving-navigation presentation

Status: **ACTIVE** · Authority: product-owner demand 2026-09-20 · SDK: Amap navi-3dmap **11.2.100**

## Goal

After 「开始导航」, the screen is Amap's driving-navigation presentation (`AMapNavi` + `AMapNaviView`), not a generic 2D map with a polyline.

## Behaviour

SEARCH → DESTINATION_CANDIDATES → ROUTE_SELECTION → route preview (full-route overview is allowed) → 「开始导航」 / route pick → **ACTIVE_DRIVING_NAVIGATION** → arrival or cancel → NAVIGATION_ENDED.

Route picking still starts the engine (existing voice: 「第一条」「最快的」「开始导航」). Preview is the awaiting-route map; driving presentation is applied when `startNavi` is accepted.

During ACTIVE_DRIVING_NAVIGATION:

- lock-car / tracking (`recoverLockMode`, `SHOW_MODE_LOCK_CAR`, `CAR_UP_MODE`)
- vehicle towards the lower-middle of the screen (`setPointToCenter`)
- heading-up, auto zoom, tilt — **not** idle `moveCamera(newLatLngZoom)`
- traffic-coloured route, live traffic updates
- native HUD: layout, 3D turn arrows, lanes, junction enlarge, cameras, traffic lights
- overview is allowed; returning from it restores tracking

Voice still talks to `EmbeddedNavigationController` / `NaviEngine`. It must not name `AMapNaviView`.

## Non-goals

Faking this with a tilted `MapView`, a homemade 3D route, or a car icon on the idle browse camera. The P5 decision against a `MapView` fallback stays.

## Acceptance

A person looking at the screen after 「开始导航」 recognises in-car turn-by-turn, not a map viewer. Unit tests cover the state/presentation seam; visual lock-car, traffic colour, lanes and junctions are device/human.
