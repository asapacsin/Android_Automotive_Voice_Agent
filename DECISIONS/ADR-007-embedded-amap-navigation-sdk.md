# ADR-007 — Embed the Amap Navigation SDK; the app owns the screen

Status: **Accepted** (2026-09-16, decided by the product owner)
Supersedes: [ADR-003](ADR-003-amap-navigation-delegation.md) (external Amap app via coordinate deep link)
Reaffirms: [ADR-001](ADR-001-direct-provider-connection.md), [ADR-002](ADR-002-baidu-flex-default-provider.md) (v2 §16: "not a reason to replace ASR/LLM/action layers"), [ADR-006](ADR-006-wake-word-aikit-shared-capture.md)
Source: [DEMAND-2026-09-16-embedded-amap-v2.md](../SPECS/DEMAND-2026-09-16-embedded-amap-v2.md) — a replacement specification, verbatim
Spec: [SPEC-005](../SPECS/SPEC-005-embedded-amap-mvp.md)

## Context

ADR-003 delegated navigation to the installed 高德地图 app through `androidamap://navi?lat&lon`. It worked — hands-free turn-by-turn was verified on device — but every hard problem this project met since is a consequence of **another app owning the screen and the speaker**:

| Problem | Root cause under ADR-003 |
| --- | --- |
| MIUI killed our WebSocket ~5 s after Amap took the foreground | we were a background app; needed a microphone FGS just to survive |
| P1 — 小诺 talked over guidance | two apps competing for audio with no shared state |
| P3 — Amap's guidance transcribed as the driver, poisoning the model until tool calls stopped | we could not tell *when* Amap was speaking; SPEC-002 measured that no observable signal exists on this device |
| P4 — no way to know navigation ended | Amap's state was invisible to us |
| Splash screens, ads, a pick-list on some links | Amap's UI, not ours |

ADR-003's own "what would justify revisiting" clause named this outcome: *"A requirement for in-app navigation UI … would mean embedding the Amap Navi SDK (needs an SDK key registered against package name + SHA1)."* The product owner has now made that requirement explicit, with a mentor reference for a **map-first, assistant-on-map** vehicle UI.

## Decision

1. **Embed the Amap Android Navigation SDK** (target 11.2.100 or a compatible newer release). `AMapNaviView` is the base layer of our own Activity; the assistant is ordinary same-app views layered above it.
2. **The external Amap app is no longer the primary navigation path.** `NavigationAdapter`'s deep links leave the normal flow. `AmapAutoPickService` is obsolete.
3. **All navigation goes through a project-owned `NavigationController`** (resolve → plan → start/stop/cancel/reroute, `StateFlow<NavigationState>`). The voice model never touches `AMapNavi`; it calls typed tools.
4. **`NavigationState` becomes the authority** — `IDLE / RESOLVING_DESTINATION / PLANNING_ROUTE / ROUTE_READY / NAVIGATING / ARRIVED / ERROR`. The boolean `navigating` and the old "Amap app is foreground" meaning are retired.
5. **No `SYSTEM_ALERT_WINDOW`**, no cross-app overlays, no accessibility overlays, no PiP tricks.
6. **The voice stack is unchanged:** Baidu Flex end-to-end, direct from the phone, with function calling. This is a navigation/UI boundary change only.

## Consequences

**What it unblocks**

- **SPEC-002's missing signal now exists.** The SDK exposes navigation text callbacks and a TTS-playing state; internal guidance voice becomes something *we* start. Gating the uplink while guidance speaks — the original Option A intent — becomes an in-process check, not a forensic one.
- P4 dissolves: navigation end, arrival and errors arrive as callbacks. `exit_navigation_mode` is replaced by a real `stop_navigation` through the controller.
- The microphone FGS stops being a survival mechanism (v2 §3.10). It may remain for screen-off sessions, but nothing may *depend* on background survival.
- `AmapPoiClient` (Web Service POI search) is the natural first `DestinationResolver` backend — it is kept, not replaced.

**What it costs**

- **A second, different Amap credential.** The existing key is a *Web service* key. The SDK needs an *Android platform* key bound to `com.novadrive.app` and the signing SHA1 (debug now, release later). Injected via local build config; never committed.
- Amap **privacy-compliance initialisation** must run before any SDK call, or the SDK refuses to work.
- SDK native libraries add materially to an APK that already doubled today for AIKit.
- Lifecycle forwarding for `AMapNaviView` is our responsibility; recreation and rotation must be handled, not assumed.
- **Amap's terms for a commercial vehicle product are unverified.** Open question, not a blocker for the MVP checkpoint.
- Existing device evidence for navigation (deep-link L5 results) no longer applies to the product path and must be re-earned.

**Retirement policy** — per v2 §46 ("must not delete working voice/action code") and `PRODUCT.md`'s dormant-code rule: `NavigationAdapter` and `AmapAutoPickService` are removed from the normal flow at Phase 4 and kept dormant until the embedded path passes L5; deleting them is a separate, later decision.

## What would justify revisiting this decision

- Amap withholding an Android platform key or imposing terms incompatible with the product.
- The SDK proving unable to run acceptably on the target hardware (rendering, memory, battery), measured — not assumed.
- A vehicle/OEM navigation surface becoming mandatory (AAOS), which would change the host, not the principle that the app owns the screen.
