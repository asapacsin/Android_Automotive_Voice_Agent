# Capabilities

**The one place that says what this product can and cannot do.** If you add, remove or change a
capability, change it here in the same commit.

Support status is decided by the tool list in `BaiduFlexProtocol.sessionUpdate` — a capability
exists when a tool is declared **and** `AndroidToolDispatcher` routes it to an executor. The persona
prompt and the UI are not sources of truth ([INVARIANTS.md](INVARIANTS.md) I-11).

## Supported

| Capability | Tool | Executes via | Success result | Failure result |
| --- | --- | --- | --- | --- |
| Navigate to a place | `navigate_to(destination)` | `DestinationQuery` → `LiveDestinationCandidateSource` → `AmapPoiClient` → `EmbeddedNavigationController` | `ok=true`, candidates on screen, `next` asks the driver to choose. **Navigation has not started yet.** | `ok=false` `NO_WEB_KEY` / `NO_CANDIDATES` / `NAVIGATION_UNAVAILABLE` |
| Pick a candidate or route | `choose_navigation_option(index \| preference \| name)` | `NavigationChoiceResolver` → `EmbeddedNavigationController` | `ok=true` `destination_selected` (routes shown) or `navigation_started` | `ok=false` `OUT_OF_RANGE` / `NO_MATCH` / `AMBIGUOUS` / `NO_OPTIONS_ON_SCREEN` / `OPTIONS_NOT_READY`, each with `next` |
| End navigation | `exit_navigation_mode()` | `EmbeddedNavigationController.endByVoice()` | `ok=true` `navigation_stopped` / `navigation_selection_cancelled` | `ok=false` `no_navigation_active` |
| Play / stop music | `control_music(play\|stop)` | `BundledMusicPlayer` (in-app, `res/raw`) | `ok=true` `music_playing` / `music_stopped` | `ok=false` `MUSIC_UNAVAILABLE` |
| Cabin climate | `control_climate(power_on\|power_off\|set_temperature\|set_fan\|…, value?)` | `ClimateToolHandler` → `VehicleControlPort` → **`SimulatedVehicleControl`** | `ok=true` with the state read back, plus `limit_reached` | `ok=false` `InvalidArgument` / `Unsupported` / `Unavailable` / `PermissionDenied` |
| Look through the camera | `describe_camera_view(question)` | `CameraQuestionHandler` → `CameraVisionGateway` + `QianfanVisionClient` | `ok=true` with the answer | `ok=false` — no camera, no permission, no frame, not configured, auth, request |
| Open an app | `open_app(maps\|settings)` | `SafeAndroidActionExecutor` intents | `ok=true` | `ok=false` `APP_UNAVAILABLE` |
| Stop talking / sleep | `set_speech_output(silent\|spoken)`, `end_conversation()` | `ListeningLifecycle` via `VoiceSessionGateway` | `ok=true` | `ok=false` `LISTENING_CONTROL_UNAVAILABLE` |

**Climate is simulated.** `SimulatedVehicleControl` is a real state machine with real limits, but it
drives nothing physical. Swapping in a vehicle is one new `VehicleControlPort` implementation
selected in `VehicleControlProvider`.

## Not supported — and what must happen

These have **no tool**. The required behaviour is one honest sentence, identical on screen and in
the speaker, with no intermediate claim ([INVARIANTS.md](INVARIANTS.md) I-2, I-3).

| Request | Recognised by | Required response |
| --- | --- | --- |
| Volume, windows, sunroof, seats, doors, boot, lights, wipers, phone calls | `ActionClaimGuard.UNSUPPORTED_WORDS` | 「这个操作没有执行，暂时不支持。」 — never 「正在调整」 |
| Weather, air quality, traffic, fuel prices, stocks, news, exchange rates | `ActionClaimGuard.REALTIME_INFO_WORDS` | An honest refusal with **no** city, temperature or forecast |

Adding a capability: declare the tool, route it in `AndroidToolDispatcher`, give it a real executor
and an honest failure result, add a row above, and remove any keyword that now has a tool.

## Known gaps

- **Navigating to a brand near you works; a spoken branch name only matches what is on screen.**
  A name nobody offered returns `NO_MATCH` and the driver is asked to say which one. It is never a
  silent guess.
- **The wake word is unverified by a human voice.** The engine initialises and the wake *path*
  (`VoiceSessionGateway.start("wake_word")`) is exercised, but nobody has spoken 「你好小诺」 in a test.
- **Barge-in by voice does not exist.** The microphone is gated while the assistant speaks; the wake
  word is the interrupt. See `ARCHITECTURE.md` and `OPEN_PROBLEMS.md` P20.
