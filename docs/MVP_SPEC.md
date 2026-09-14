# MVP specification — Nova Drive / 小诺

## Product

In-car voice assistant for Mainland China passenger vehicles. The driver talks to **小诺** (`你好小诺` wake phrase, configurable). Mandarin is primary; later ingress/entity layers must accept Chinese-English code switching, acronyms, brands, app names, and English POIs.

## In-scope MVP domains

1. **Navigation** through a provider-neutral `NavigationProvider` (Fake now; AMap, Baidu, OEM later). No Google Maps.
2. **Media** play / pause / volume. No Spotify or other global-service assumption.
3. **Phone** place / end call, with deterministic clarification for Chinese names, aliases, pinyin, homophones, and duplicates.
4. **HVAC** cabin temperature (°C) and fan level.

Out of scope for the whole MVP (not just this checkpoint): windows, sunroof, charging, parking, cameras, video, seat position, smart scenes.

## Checkpoint 1 MVP slice

The driver-facing audio stack is **not** built yet. What must already be true:

- Typed commands exist for the four domains.
- Safety returns only `ALLOW`, `CONFIRM`, or `DENY`.
- `CONFIRM` never executes until `confirm(correlationId)`.
- `DENY` and invalid bounds never mutate vehicle state.
- Success (`VERIFIED`) is returned only after observed-state read-back matches.
- Feedback strings are short Simplified Chinese, metric units.
- A local JVM demonstration can be run without network credentials.

## Feedback style

Driving-appropriate, one sentence, `zh-CN` examples:

- `已开始前往人民广场。`
- `即将拨打电话，请确认。`
- `该操作已被安全策略拒绝。`
- `温度已设为22度。`

No long explanations, no English-first copy in the default renderer.

## Safety bootstrap (not production-complete)

| Command | Invalid | DENY | CONFIRM | ALLOW |
| --- | --- | --- | --- | --- |
| Start navigation | empty dest, illegal lat/lon | `restricted` destination | — | normal POI |
| Place call | missing identity / no match | privacy block / blocked number | unique contact | after explicit confirm |
| Cabin temperature | outside 16–32 °C | — | Δ ≥ 5 °C | small change |
| Volume | outside 0–100 | — | ≥ 80 | lower |
| Fan | outside 0–7 | — | — | in range |

Production rule tables, geofences, and speed-based UX limits are Checkpoint 4.

## Non-goals this checkpoint

- Live mic, AEC, wake word, PTT, VAD
- Real S2S websocket / credentials
- AMap / Baidu / OEM SDK jars
- AAOS `Car` / VHAL property writes
- Publishing
