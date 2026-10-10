# Capabilities

**The one place that says what this product can and cannot do.** If you add, remove or change a
capability, change it here in the same commit.

Support status is decided by [ProductCapabilities](../contracts/src/main/kotlin/com/novadrive/contracts/Capability.kt),
kept in step with this file and `config/capabilities.yaml`. A tool is declared in
`BaiduFlexProtocol.sessionUpdate` and routed by `AndroidToolDispatcher`. The persona prompt, the UI,
and keyword lists in `ActionClaimGuard` are not sources of truth
([INVARIANTS.md](INVARIANTS.md) I-10, I-11).

## Supported

| Capability | Tool | Executes via | Success result | Failure result |
| --- | --- | --- | --- | --- |
| Navigate to a place | `navigate_to(destination)` | `DestinationQuery` → `LiveDestinationCandidateSource` → `AmapPoiClient` → `EmbeddedNavigationController` | `ok=true`, candidates on screen, `next` asks the driver to choose. **Navigation has not started yet.** | `ok=false` `NO_WEB_KEY` / `NO_CANDIDATES` / `NAVIGATION_UNAVAILABLE` |
| Go home / go to work | `navigate_to(destination)` | `SavedPlaces` → `SavedPlaceStore` → `EmbeddedNavigationController` | `ok=true`, the saved place is the only candidate and routing starts from it — no POI search | `ok=false` `HOME_NOT_SET` / `WORK_NOT_SET`: the driver has not told us where it is, and the assistant says so instead of guessing |
| Remember home / work | `save_place(slot, address)` | `SavedPlaceTool` → `LiveDestinationCandidateSource` → `SavedPlaceStore` | `ok=true` with the **resolved** place name, so the confirmation names what was stored | `ok=false` `ADDRESS_NOT_FOUND` — an address that cannot be resolved is not saved |
| Call a contact | `place_call(contact)` | `PhoneCallTool` → `PhonePort` (`PhoneProvider` selects `AndroidContacts`) | `ok=true status=confirm_required` with the **name only** — nothing is dialled yet. A second call with `confirmed=true` places it | `ok=false` `NO_TELEPHONY` (no SIM — device-verified), `CONTACT_NOT_FOUND`, `CONTACTS_PERMISSION_DENIED` (did not search), or `status=ambiguous` when several people share the name |
| Pick a candidate or route | `choose_navigation_option(index \| preference \| name)` | `NavigationChoiceResolver` → `EmbeddedNavigationController` → `AmapNaviViewHost` / `AmapDrivingPresentation` | `ok=true` `destination_selected` (routes shown, full-route preview) or `navigation_started` (native lock-car driving HUD) | `ok=false` `OUT_OF_RANGE` / `NO_MATCH` / `AMBIGUOUS` / `NO_OPTIONS_ON_SCREEN` / `OPTIONS_NOT_READY`, each with `next` |
| End navigation | `exit_navigation_mode()` | `EmbeddedNavigationController.endByVoice()` | `ok=true` `navigation_stopped` / `navigation_selection_cancelled` | `ok=false` `no_navigation_active` |
| Play / stop music (generic 「放首歌」 only) | `control_music(play\|stop)` | `BundledMusicPlayer` (in-app, `res/raw`) | `ok=true` `music_playing` / `music_stopped` (stop also pauses the music app's session; `music_stopped` only when the readback shows nothing playing, `bundled_stopped_app_not_paused` with an instruction not to say 「已关闭」 after a hand-off without notification access) | `ok=false` `MUSIC_UNAVAILABLE` / `MUSIC_STILL_PLAYING` |
| Play a named or described song (SPEC-017) | `play_music(title?, artist?, album_or_work?, mood?, query?, exclude_title?)` | `MediaServer` → `MusicHandoffTool` (`AndroidMediaHandoff` `MEDIA_PLAY_FROM_SEARCH` to 网易云/QQ音乐/VLC, then `NowPlayingVerifier` over `AndroidNowPlayingSource`) | `ok=true` `status=playing` with `now_playing{title, artist, app}`, `matches_request` and `announce` (only now_playing may be claimed); `ok=true` `status=requested_unverified` without notification access (may say only that the app was asked). Unit-verified; never run on a device | `ok=false` `NOT_PLAYING` / `NO_MUSIC_APP` / `HANDOFF_REJECTED` / `MUSIC_HANDOFF_UNAVAILABLE`, `DUPLICATE_IN_TURN`; `control_music{play}` for a named request stays `MEDIA_LIBRARY_UNSUPPORTED` with `next` pointing at `play_music` |
| Cabin climate | `control_climate(power_on\|power_off\|set_temperature\|set_fan\|…, value?)` | `ClimateToolHandler` → `VehicleControlPort` → **`SimulatedVehicleControl`** | `ok=true` with the state read back, plus `limit_reached` | `ok=false` `InvalidArgument` / `Unsupported` / `Unavailable` / `PermissionDenied` |
| Windows | `control_window(open\|close\|set\|adjust\|get_state, window?, value?)` | `WindowToolHandler` → `VehicleControlPort` → **`SimulatedVehicleControl`** | `ok=true` with every window read back (`windows`), `limit_reached` and an `announce` sentence saying what changed | `ok=false` `INVALID_ARGUMENT` / `VEHICLE_UNSUPPORTED` / `VEHICLE_UNAVAILABLE` / `VEHICLE_PERMISSION_DENIED` / `VEHICLE_EXECUTION_FAILED` |
| Comfort scenario | `run_scenario(name=mosquito\|mosquito_done\|stuffy\|drowsy)` | `ComfortServer` → fixed `ComfortScenarios` steps routed through the same climate / window / music servers as a direct call | `ok=true` `status=done` with `steps` [{tool, action, ok}] and one `announce` built from each step's read-back; drowsy appends a rest-stop offer (never navigates); `status=already_open` (mosquito, windows already at 50%) and `status=already_closed` (mosquito_done) change nothing and say so | `ok=false` `status=partial` naming what was and was not done; a step whose prerequisite failed is `SKIPPED_PREREQUISITE_FAILED` |
| Seat height | `control_seat(adjust_height\|set_height\|get_state, seat?, value?)` | `SeatToolHandler` → `VehicleControlPort` → **`SimulatedVehicleControl`** | `ok=true` with `seat_height` read back, `limit_reached` and an `announce` sentence | `ok=false` as for windows |
| Look through the camera | `describe_camera_view(question)` | `CameraQuestionHandler` → `CameraVisionGateway` + `QianfanVisionClient` | `ok=true` with the answer | `ok=false` — no camera, no permission, no frame, not configured, auth, request |
| Open an app | `open_app(maps\|settings)` | `SafeAndroidActionExecutor` intents | `ok=true` | `ok=false` `APP_UNAVAILABLE` |
| Stop talking / sleep | `set_speech_output(silent\|spoken)`, `end_conversation()` | `ListeningLifecycle` via `VoiceSessionGateway` | `ok=true` | `ok=false` `LISTENING_CONTROL_UNAVAILABLE` |
| Speaking tone (嗲 / 傲娇 / 温柔 / 元气 / 正常一点) — tone only, voice unchanged, sticky until asked again; an acted tone never refuses or misreports | `set_speaking_style(default\|sweet\|tsundere\|gentle\|lively)` | `SpeakingStyleState` + `SpeakingStyleStore` → `PersonaProfiles.compose` | `ok=true` with `style`, `instruction`; `persisted=false` if the save failed (still applied this run) | `ok=false` `INVALID_FIELDS` / `STYLE_NOT_ALLOWED` |
| Live information (SPEC-011) | `query_live_info(kind, where?, day?, category?, target?)` — `weather`, `route_traffic`, `along_route`, `place_details` | `LiveInfoTool` → `AmapPoiClient` (REST weather / regeo / place detail) or `RouteLiveInfoSource` → `AmapRouteLiveInfo` (Navi `getTrafficStatuses`, Search `RoutePOISearch`); along-route results go to `EmbeddedNavigationController.requestCandidates`, the existing picker | `ok=true` with only the fields the source returned and `reported_at`; route traffic is counts and distances, never coordinates. `DriverTurn` releases a weather/traffic answer only when a lookup of that kind succeeded this turn | `ok=false` `AMAP_WEB_KEY_MISSING`, `NO_LOCATION`, `NO_DESTINATION`, `NOT_NAVIGATING`, `LIVE_INFO_UNAVAILABLE` (4 s timeout, HTTP, status≠1), `LIVE_INFO_QUOTA`, `NO_RESULTS`, `DUPLICATE_IN_TURN`, each with `next`. Weather and place details are unit-verified; route traffic and along-route have not run on a device |
| Explain what the assistant can do | *(none — spoken answer only)* | `UtteranceIntentResolver` → `speech.capability_help`; copy from `ProductCapabilities.spokenHelpSummary` | Short list naming supported groups (导航/音乐/空调/车窗·座椅/摄像头/电话/地图·设置 as wired) | `help_incomplete` nudge — never 「没听清」 for a recognised help question |

**Driving navigation presentation (no separate tool).** After the driver starts navigation, the embedded
map must enter native turn-by-turn driving — lock-car tracking, heading-up map, traffic colouring,
maneuver/lane HUD, and a speed/limit chip from Amap data (`navigation.driving_presentation`,
`navigation.speed_hud`, … in `config/capabilities.yaml`). Route pick before start remains overview
(`navigation.route_preview`); overview must return to lock-car (`navigation.return_to_tracking`).

**Truth and echo protection (no separate tool).** `speech.action_claim_truthfulness` and
`speech.post_speech_echo_protection` are enforced by `DriverTurn` / `ActionClaimGuard` and the
post-reply mic hold — not by persona rules alone.

**Climate, windows and seat are simulated.** `SimulatedVehicleControl` is a real state machine with real limits, but it
drives nothing physical. Swapping in a vehicle is one new `VehicleControlPort` implementation
selected in `VehicleControlProvider`.

## Not supported — and what must happen

These have **no tool**. The required behaviour is one honest sentence, identical on screen and in
the speaker, with no intermediate claim ([INVARIANTS.md](INVARIANTS.md) I-2, I-3).

| Request | Recognised by | Required response |
| --- | --- | --- |
| Volume, sunroof, doors, boot, lights, wipers | `UtteranceIntentResolver` → `CapabilityCatalog` (`unsupported.*`) | 「这个操作没有执行，暂时不支持。」 — never 「正在调整」 |
| News, stocks, fuel prices, exchange rates, air quality, driving restrictions | `ActionClaimGuard.NO_SOURCE_INFO_WORDS` | An honest refusal with **no** figure, city or forecast. Weather and traffic moved to `query_live_info` (SPEC-011); a successful lookup never unlocks these |

Adding a capability: declare the tool in its car domain (`app/tools/*Domain.kt`), execute it in that domain's `ToolServer` (ADR-015), give it a real executor
and an honest failure result, add a row here **and** in `ProductCapabilities` /
`config/capabilities.yaml`, and map the utterances in `UtteranceIntentResolver`. Do not add a
second word-list that claims to know whether the capability exists.

## Known gaps

- **Navigating to a brand near you works; a spoken branch name only matches what is on screen.**
  A name nobody offered returns `NO_MATCH` and the driver is asked to say which one. It is never a
  silent guess.
- **The wake word works, by a human voice.** 「你好小诺」 spoken by the product owner opens a
  session on `2391ff70` (2026-09-19). Not yet characterised: false-accept rate over a long drive,
  and reliability at distance with road noise — those are reliability questions, not existence ones.
- **Barge-in by voice does not exist.** The microphone is gated while the assistant speaks; the wake
  word is the interrupt. See `ARCHITECTURE.md` and `OPEN_PROBLEMS.md` P20.
