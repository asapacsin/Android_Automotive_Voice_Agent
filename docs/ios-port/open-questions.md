# Open questions

Answer each before the milestone that needs it (migration-plan.md). Record the answer, the source
(URL or device run) and the date here.

## iOS platform (no external documentation was fetched while writing this spec)

| ID | Question | Needed for | How to answer |
|---|---|---|---|
| OQ-IOS-1 | Does iFlytek offer an iOS wake-word SDK compatible with the existing `wakeword.jet` model and app id? If not, which engine (e.g. Picovoice Porcupine) and licence? | M5 | iFlytek developer portal; spike |
| OQ-IOS-2 | Amap iOS Navi SDK: multi-route calculation and selection, guidance text callback, ability to mute the built-in voice and speak via the app, camera/speed-limit info, simulated (emulator) navigation, CarPlay rendering | M3, M4, M7 | Amap iOS SDK docs; spike app |
| OQ-IOS-3 | Is `AVAudioEngine` voice processing enough to prevent self barge-in with loudspeaker playback in a car, or must WebRTC AEC3 be ported? | M1 | device spike with the speech-harness clips |
| OQ-IOS-4 | Background policy: may the app keep the mic and WebSocket alive in background (audio background mode) and pass App Review? What happens on screen lock during navigation? | M5 | Apple App Review Guidelines, background execution docs; TestFlight review |
| OQ-IOS-5 | Gemini Live API: is the protocol in contracts/api-contracts.md current (field names, models `gemini-3.8-live`, resumption, close codes)? Any iOS SDK? | M1 | Google AI docs |
| OQ-IOS-6 | CarPlay: navigation entitlement eligibility for this app; confirm no vehicle-control access | M7 | Apple CarPlay developer guide |
| OQ-IOS-7 | Named music: is MusicKit (Apple Music) acceptable as the only named-music path? Netease/QQ Music iOS SDK options? | M6 | Owner decision; MusicKit docs |

## Android facts not fully verified

| ID | Question | Where |
|---|---|---|
| OQ-AND-1 | Exact `control_climate` action enum and `choose_navigation_option` field names | `A/vehicle/ClimateToolHandler.kt:102`, `A/tools/NavigationDomain.kt:76` |
| OQ-AND-2 | Wake phrase text (only inside the `.jet` model) | `A/wake/IflytekWakeWordDetector.kt` |
| OQ-AND-3 | ETA field in route_traffic (only remaining_km seen) | `A/LiveInfoTool.kt` |
| OQ-AND-4 | Screen-off / onStop behaviour beyond the foreground service | `A/MainActivity.kt` |
| OQ-AND-5 | No device latency baseline for Gemini + Azure (P48 is emulator) | owner device run |
| OQ-AND-6 | FZ-14 clarification in a real recording (runs 298/317/319 did not show it) | nav demo on owner PC |
| OQ-AND-7 | Default provider decision: ADR-017 says Qwen; owner froze Qwen 2026-10-22, so Gemini. Should an ADR record this? | `DECISIONS/` |
| OQ-AND-8 | Test registry header dated 2026-09-21 while rows reach 2026-10-20 — registry partly stale | `TEST_MATRIX.yaml` |
