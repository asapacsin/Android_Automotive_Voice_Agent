# SPEC-011 — Live information from Amap: weather, route traffic, along-route search, place details

Status: **Draft**
Raised: 2026-09-24 · Source: [B-025](../BACKLOG.md#b-025--live-information-from-amap)
Depends on: [I-1, I-3, I-8, I-9, I-11](../docs/INVARIANTS.md), [ADR-001](../DECISIONS/ADR-001-direct-provider-connection.md) (phone calls vendors directly), [ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md), [SPEC-008](SPEC-008-live-scenario-suite.md) (live selection measurement), [SPEC-010](SPEC-010-screen-affordances.md) B4 (per-turn capability claim)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

Today 「目的地天气怎么样」「前面堵不堵」「路上有加油站吗」 are refused, because there is no source
and any answer would be invented (`realtime_weather_traffic_news: unsupported`). Amap, which the
app already uses, has the data. This turns those refusals into answers backed by a real lookup,
without a server of our own, and without letting the model answer them from its own imagination.

## Scope

One tool, `query_live_info`, with a `kind`:

| kind | Arguments | Source | Needs |
| --- | --- | --- | --- |
| `weather` | `where`: `here` / `destination` / a city name | REST `v3/weather/weatherInfo` (`extensions=base` now, `all` for 「明天」); adcode from REST `v3/geocode/regeo` (here) or the destination's `adcode` | web-service key |
| `route_traffic` | none | Navi SDK `AMapNavi.getTrafficStatuses` on the active route | active navigation |
| `along_route` | `category`: `fuel` / `charging` / `service_area` / `toilet` | Search SDK `RoutePOISearch` (in the bundled `navi-3dmap-location-search`, search 9.8.1) | a calculated route |
| `place_details` | `target`: `destination` or a picker position | REST `v3/place/detail?id=<poiId>&extensions=all` | a POI id |

Why one tool instead of four: every declaration competes for the model's attention, and a missed
call is the failure class this SPEC must not make worse ([P2](../OPEN_PROBLEMS.md)).

`along_route` results are shown as destination candidates in the **existing picker**, so choosing
one (「第一个」) is the existing, device-verified navigation path, and SPEC-010 makes it sayable.

## Non-goals

- News, stock prices, fuel prices, exchange rates: still no source → still refused.
- A second POI client. REST calls go into `AmapPoiClient`; the two SDK calls (`getTrafficStatuses`,
  `RoutePOISearch`) go behind a port whose implementation lives in `app/nav/amap`, the only package
  allowed to import `com.amap` (I-9, `ArchitectureRulesTest.onlyOneFileImportsAmap`).
  `LiveDestinationCandidateSource` stays the one place candidates come from.
- Proactive announcements (「前方拥堵」) — excluded by [B-026](../BACKLOG.md#b-026--one-owner-for-who-may-speak).

## Capability ground truth

At `846d116`: `realtime_weather_traffic_news` is `unsupported`, recognised by
`ActionClaimGuard.REALTIME_INFO_WORDS`, protected by `TRUTH-WEATHER-001`. The web-service key is
read by `AmapSettings.loadWebKey()`; its absence is `AMAP_WEB_KEY_MISSING`. The route-traffic and
along-route sources need an active or calculated route from `EmbeddedNavigationController`.

## Behaviour

B1. **Dispatch.** `AndroidToolDispatcher` routes `query_live_info` to a `LiveInfoTool` (new, `app`),
which validates `kind` and its arguments and returns a `ToolDispatchResult` with `deferredOutput`
(the camera pattern), so a slow network never blocks the event loop.

B2. **Output.** `{"ok":true,"kind":"weather","where":"目的地","city":"…","now":"晴","temp_c":24,
"wind":"东北风3级","reported_at":"14:00"}` — only fields the source returned; a missing field is
left out, never filled in. `route_traffic` returns counts of congested segments and the distance to
the first one, not coordinates.

B3. **Truth guard (I-1, I-3).** `ActionClaimGuard` stops treating weather/traffic words as fabricated
*when a successful `query_live_info` result of that kind exists in the same turn*; otherwise the
existing refusal still applies. News/stock/price words stay refused unconditionally.

B4. **Freshness.** Weather is cached 10 min per adcode; traffic and along-route are never cached.
Every result says when it was reported.

B5. **Declarations.** The tool description lists example phrasings per kind, in the same style as
the existing climate table that fixed CVC selection on 2026-09-19.

B6. **Deterministic fallback — gated by measurement.** If step 5 shows the model misses the tool in
more than 1 of 10 runs for a kind, enable the fallback for that kind: when the transcript contains
that kind's cue and the model's turn ends without calling it, the app dispatches the same synthesised
call through the dispatcher and sends the result with `sendText`. SPEC-010 B4 makes the two paths
execute once between them.

## Failure behaviour

| Case | Code | Driver hears |
| --- | --- | --- |
| No web-service key | `AMAP_WEB_KEY_MISSING` | 天气查询还没有配置 |
| No location fix for `here` | `NO_LOCATION` | 还没有定位，查不了这里的天气 |
| `destination` with no destination | `NO_DESTINATION` | asks where |
| `route_traffic` / `along_route` without a route | `NOT_NAVIGATING` | 现在没有在导航 |
| Timeout (4 s) / HTTP error / `status≠1` | `LIVE_INFO_UNAVAILABLE` | 现在查不到 — never a guess |
| Amap daily quota (infocode `10003`, `10044`) | `LIVE_INFO_QUOTA` | 今天的查询次数用完了 |
| Zero results | `NO_RESULTS` | 沿途没有找到加油站 |
| Called twice in one turn | `DUPLICATE_IN_TURN` | nothing extra |

Each code gets a `ToolFailureAdvice` entry so the model's sentence is shaped, not invented.

## Observability

`live_info kind=<k> ok=<b> code=<c> ms=<n> cached=<b>`. Never a coordinate, adcode, city, address
or POI name in a log (I-8); REST URLs carrying `location=` are never logged.

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Each REST response parses from a recorded fixture; missing fields stay missing | functional | `AmapLiveInfoParserTest` | not built |
| A2 | Every failure row above yields its code | negative | `LiveInfoToolTest` | not built |
| A3 | A weather claim with no result this turn is still corrected; with a result it is not | regression protection | `FalseCapabilityClaimTest` + `TRUTH-WEATHER-001` updated | not built |
| A4 | News / price words stay refused | negative | `TRUTH-LIVEINFO-NEWS-001` | not built |
| A5 | Nothing identifying a place is logged; SDK types stay in `app/nav/amap` | architectural | `ArchitectureRulesTest.locationIsNotLogged` extended, `onlyOneFileImportsAmap` | not built |
| A6 | `along_route` results open the existing picker and 「第一个」 navigates | production wiring | `LiveInfoAlongRouteWiringTest` | not built |
| A7 | Existing SPEC-008 selection does not regress after the declaration is added | device | SPEC-008 suite before/after, same build otherwise (LOCAL_DEVICE) | not built |
| A8 | Each kind is selected from speech in ≥ 9 of 10 runs (pass^k) | device | `LIVE-INFO-DEVICE-001` (LOCAL_DEVICE) | not built |
| A9 | Live Amap calls return real data with the owner's keys | external service | `LIVE-INFO-L6-001` | not built |
| A10 | The tool is declared and dispatched; capabilities.yaml splits `realtime_weather_traffic_news`; registry agrees | reconciliation | `ArchitectureRulesTest.declaredToolsAndDispatchedToolsAgree`, `harness_check.py`, `test_matrix.py --validate` | not built |

## Open product decisions

- **Which kinds first.** Default order: weather → route_traffic → along_route → place_details
  (cheapest and most asked first). Each kind is shippable on its own.
- **Web-service quota.** The personal key's daily limits are the owner's console setting; the
  default cache (B4) keeps weather to at most 6 calls per hour per city.

## Implementation status

Nothing built. Steps, each a separate verified commit:

0. **Fixtures and baseline.** Record one real response per REST endpoint (no coordinates in the
   committed fixture — replace them with fixed values) and confirm the `RoutePOISearchType` names
   in search 9.8.1. Run the SPEC-008 suite on the phone and record the baseline selection rate (A7).
1. Parsers + `AmapPoiClient` methods + `DestinationCandidate.adcode`; A1.
2. `LiveInfoTool` + dispatcher route + `ToolFailureAdvice` codes; A2, A5. Kind `weather` only.
3. `ActionClaimGuard` change + capabilities split + matrix rows; A3, A4, A10.
4. Declaration in `BaiduFlexProtocol`; build; device run A7 then A8 for `weather`; A9.
5. Repeat 2–4 for `route_traffic`, `along_route` (A6), `place_details`.
6. B6 only for kinds that failed A8.
