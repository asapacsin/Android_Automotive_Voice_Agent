# ADR-003 — Delegate navigation to Amap via coordinate deep link

Status: **Superseded by [ADR-007](ADR-007-embedded-amap-navigation-sdk.md)** (2026-09-16)

> **Why:** the product owner issued a replacement specification requiring an in-app, map-first UI with the Amap **Navigation SDK embedded** — precisely the first revisit condition listed at the bottom of this file. The deep-link measurements below remain true and are kept as history; the deep link is no longer the product's navigation path. Do not build on it.

## Context

The assistant must start real turn-by-turn navigation from a spoken destination. Building a navigation engine — map data, routing, guidance, traffic — is out of scope for this product.

Measured on a physical device, Amap's deep links behave differently depending on what they carry:

| Link | Result |
| --- | --- |
| `androidamap://navi?...&lat=&lon=&dev=0` | **Starts turn-by-turn immediately, zero taps** |
| `androidamap://keywordNavi?keyword=…` | Stops at a destination pick-list; requires taps |
| `amapuri://route/plan/?dname=…` | Stops at a destination pick-list |
| `geo:0,0?q=…` | Shows a place; never navigates |

Amap will not choose a POI from a name on the user's behalf. Only coordinates produce hands-free navigation.

## Decision

Navigation is delegated to the Amap app, driven by a **coordinate** deep link.

`navigate_to` resolves the spoken destination to coordinates first, using the Amap Web Service API (`place/around` biased by coarse location when available, falling back to nationwide `place/text`), then launches `androidamap://navi` with `lat`/`lon`.

This requires a user-supplied **Amap Web service key**, stored in Keystore as `amap_web_key`. Without a key the flow degrades to `keywordNavi` (one tap) and then `geo:` — degraded, not broken.

## Consequences

- Hands-free navigation works, verified on device: the real `navigate_to` path reached live turn-by-turn guidance with zero taps.
- The product depends on the Amap app being installed, and inherits its UI (splash, ads) during handoff.
- A keyless geocoder is not an option: `nominatim.openstreetmap.org` is unreachable from the test device, while `restapi.amap.com` responds in ~0.2 s.
- An `AccessibilityService` (`AmapAutoPickService`) exists as a no-key fallback that taps the pick-list. It is **not** the primary path and is disabled by default.
- The assistant must keep working while Amap is foreground — see `CURRENT_MILESTONE.md`.

## What would justify revisiting this decision

- A requirement for in-app navigation UI, no third-party dependency, or AAOS integration — which would mean embedding the Amap Navi SDK (needs an SDK key registered against package name + SHA1).
- Amap changing or removing the `androidamap://navi` deep link contract.
- A vehicle/OEM navigation interface becoming available, making app delegation inappropriate.
