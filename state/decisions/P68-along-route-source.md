# P68 decision - along_route source (decider, 2026-10-20)

DECIDED: option (b). One Web `v3/place/polygon` call per along_route question through AmapPoiClient; polygon = convex hull of a buffered corridor around the next L km of the remaining route; filter to the true corridor on device; order by distance along the route from the car. RoutePOISearch is deleted (no parallel source).

Evidence: ci/device-evidence runs/run_20261020_09b4c5f_r39417607816/dispatch - RoutePOISearch code=1000 raw=0 at 250/500/1000/2000 m, polyline form and DrivingNoHighWay; web place/around 加油站 3 km at the route midpoint = 5 (a floor, limit 5). RoutePOISearch calls `<host>/place/route`, not a listed Web service (沿途搜索 absent from lbs.amap.com/upgrade base search list).

Bounds:
- Exactly 1 search call per question; no retry, no fallback call.
- Category -> Amap `types` code (use types=, not keywords=): fuel 010100, charging 011100, service_area 180300, toilet 200300.
- Request: offset=25, page=1, extensions=base.
- Lookahead L from the car along the route: min(remaining, L); L = 10 km for fuel/charging/toilet, 50 km for service_area. Car position s_car = allLength - pathRetainDistance (NaviInfo, already received in nav/amap/NavigationTraceListener); 0 while the route is only on screen. Coordinates are GCJ-02 already - no conversion.
- Polygon (pure, SDK-free, JVM-tested, e.g. nav/AlongRouteCorridor): cut coordList for [s_car, s_car+L]; Douglas-Peucker 50 m; 8 points on a circle of w+50 m (550 m) around each vertex; convex hull; closed ring (first = last); lon,lat with <= 6 decimals joined by `|`; <= 64 vertices (raise tolerance if more).
- Filter: keep a POI when its distance to the unsimplified segment <= w = 500 m and 0 <= s - s_car <= L (earliest s when the route passes twice); de-duplicate by POI id.
- Order by along-route distance_m ascending (integer, from the car); cap at MAX_ALONG_ROUTE = 5; existing picker and 「第一个」 path unchanged.
- Latency: inside the existing 4 s deadline (LiveInfoTool.TIMEOUT_MS).
- Result carries searched_ahead_km and whether the search reached the destination. NO_RESULTS wording: 「前方10公里沿途没有找到加油站」 when the lookahead stopped short of the destination; 「沿途没有找到加油站」 only when the whole remaining route was searched.
- Truncation: response count > returned -> log truncated=true; show the real results; no second page.
- Log counts only (I-8): `along_route_search code=<status|infocode> total=<n> returned=<n> kept=<n> lookahead_m=<L> vertices=<v> ms=<t>` (+ truncated=true when so). Never the polygon, URL, names or ids.
- Failures: no web key -> AMAP_WEB_KEY_MISSING; status != 1 incl. QPS infocodes 10004, 10019-10021 -> LIVE_INFO_UNAVAILABLE (never NO_RESULTS).

Code: `placePolygon` in AmapPoiClient via the existing live() helper (never logs the URL), returning LiveInfoFetch with the parser's quota mapping; AmapLiveInfoRest gains placePolygon; the RouteLiveInfo port's alongRoute(category) is replaced by a route geometry/progress read (com.amap stays in nav/amap, I-9); LiveInfoTool.alongRoute orchestrates. Delete RoutePOISearch from AmapRouteLiveInfo, RoutePoi/toCandidates/alongRouteSearchLine from RouteLiveInfo.kt and their tests, and the SDK forms in debug AlongProbe.

Tests: AlongRouteCorridorTest - closed convex polygon <= 64 vertices <= 6 decimals; every route point within w covered incl. a U-turn route; POIs behind the car or off the corridor dropped; ordering by along-route distance. A recorded place/polygon fixture with fixed coordinates for the parser. A6 wiring test keeps working with only the fake changed.

If live: status != 1 / INVALID_PARAMS / kept=0 with total>0 on the 珠海站 route -> widen w to 1 km first; still failing -> option (a) with corrected geometry (r=1 km, s<=1.7 km, <=6 calls, concurrency <=2) - owner quota question.
