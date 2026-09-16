# Embedded Amap AI Assistant — Architecture, UI, Voice, Action, and Test Specification

**Status:** Replacement specification  
**Target:** Android MVP / vehicle-style tablet UI  
**Architecture decision:** Embedded Amap Navigation SDK, not external Amap app handoff  
**Initial navigation SDK target:** Amap Android Navigation SDK 11.2.100 or newer compatible release  
**Primary principle:** The assistant app owns the screen. Amap supplies the map/navigation engine and navigation view inside the app.

---

## 1. Goal

Build an Android voice assistant with a **map-first vehicle-style interface**.

The screen should behave like the mentor reference:

- the navigation map occupies most or all of the main screen;
- the assistant remains visibly present on the same screen;
- assistant status, avatar, speech bubble, action confirmations, or compact cards may overlay the map;
- navigation remains interactive and visible underneath;
- the assistant can receive voice commands while navigation is active;
- the assistant can control navigation through the embedded Amap Navigation SDK;
- device/application actions use an abstract action layer and mock implementations for the MVP.

The product must **not** switch to the separately installed 高德地图 application for normal navigation.

The product must **not** require Android "draw over other apps" permission for the normal assistant-on-map UI.

---

## 2. Product Model

The previous model was:

```text
Assistant App
    ↓
launch external Amap app
    ↓
Amap owns foreground screen
    ↓
assistant attempts to survive in background
```

This specification replaces it with:

```text
OUR ANDROID APP — remains foreground
│
├── Amap Navigation SDK
│   ├── AMapNaviView
│   ├── AMapNavi
│   ├── route planning
│   ├── GPS/location
│   ├── traffic
│   ├── rerouting
│   └── navigation guidance
│
├── Assistant UI overlay
│   ├── avatar
│   ├── listening/processing state
│   ├── speech bubble
│   └── action/navigation feedback
│
├── Voice runtime
│   ├── wake / push-to-talk
│   ├── microphone
│   ├── ASR / realtime model
│   ├── intent / tool calling
│   └── assistant TTS
│
└── Action layer
    ├── MockActionExecutor
    ├── AndroidActionExecutor      ← later
    ├── CarActionExecutor          ← later
    └── ExternalDeviceExecutor     ← later
```

---

## 3. Non-Negotiable Architecture Rules

1. **Do not launch the external Amap app for the primary navigation flow.**
2. **Do not recreate map rendering, route calculation, traffic, GPS navigation, or turn-by-turn guidance ourselves.**
3. **Embed Amap's navigation view inside our Activity/Fragment/Compose host.**
4. **Place assistant UI above the embedded navigation view using normal same-app view layering.**
5. **All navigation operations must go through a NavigationController abstraction.**
6. **All device/application actions must go through an ActionExecutor abstraction.**
7. **The voice model must not directly call Amap SDK APIs or hardware APIs.**
8. **UI state, navigation state, assistant state, and action state must be observable and testable independently.**
9. **Do not scatter Amap-specific code across unrelated assistant modules.**
10. **Do not design around background-app survival when the normal product flow keeps our own Activity foreground.**

---

## 4. Top-Level Runtime Architecture

```text
User Voice
    ↓
Audio Capture
    ↓
Realtime Voice / ASR
    ↓
Intent / Tool Router
    │
    ├── INFORMATION
    │       ↓
    │   Assistant Response
    │
    ├── NAVIGATION
    │       ↓
    │   NavigationController
    │       ↓
    │   AmapNavigationController
    │       ↓
    │   AMapNavi / AMapNaviView
    │
    └── ACTION
            ↓
        ActionExecutor
            ↓
        MockActionExecutor
            ↓
        ActionResult

All response-producing branches
    ↓
ResponseCategory
    ↓
VoicePolicy
    ↓
Assistant UI + TTS decision
```

Amap navigation callbacks flow back into application state:

```text
AMapNaviListener / navigation callbacks
    ↓
NavigationStateStore
    ↓
UI
    ├── route state
    ├── maneuver / guidance state
    ├── arrival state
    └── navigation status

VoicePolicy
    └── may also use SDK navigation/TTS state when needed
```

---

## 5. Screen Architecture

The map is the visual base layer.

Recommended Android structure:

```text
MainActivity
└── AssistantNavigationScreen
    └── Box / FrameLayout
        ├── AMapNaviView              ← full-screen/base
        ├── NavigationTopOverlay      ← optional
        ├── AssistantOverlay          ← our UI
        │   ├── AssistantAvatar
        │   ├── AssistantStateIndicator
        │   └── AssistantSpeechBubble
        ├── ActionFeedbackOverlay     ← short status
        ├── OptionalMediaControls
        └── OptionalVehicleControls
```

### 5.1 Layering rule

`AMapNaviView` must be the base map/navigation surface.

Assistant elements are ordinary views inside the same Activity and should be placed above it through z-order/layout composition.

Do **not** use:

- `SYSTEM_ALERT_WINDOW`;
- cross-app floating windows;
- accessibility overlays;
- picture-in-picture hacks;
- screenshots of Amap;
- WebView-based map imitation.

### 5.2 Minimum mentor-style MVP UI

Implement only:

1. full-screen embedded navigation map;
2. small assistant avatar/status element;
3. temporary speech/status bubble;
4. compact action confirmation;
5. navigation state visible on the map.

Do not initially reproduce every car-system control shown in the mentor photos.

---

## 6. Assistant Visual States

Use explicit semantic UI states.

```text
AssistantUiState
├── IDLE
├── LISTENING
├── PROCESSING
├── RESPONDING
├── ACTION_SUCCESS
├── ACTION_FAILURE
└── ERROR
```

Suggested behavior:

### IDLE

- avatar/icon visible but unobtrusive;
- no large card;
- map remains primary.

### LISTENING

- avatar/icon indicates microphone/listening state;
- no large blocking panel.

### PROCESSING

- compact processing indicator;
- map remains usable.

### RESPONDING

- short temporary speech bubble/card;
- card auto-dismisses;
- avoid covering critical maneuver UI.

### ACTION_SUCCESS / ACTION_FAILURE

Example:

```text
空调已打开
```

or:

```text
空调连接失败
```

The feedback should be short.

---

## 7. Amap SDK Responsibilities

Amap remains responsible for:

- map rendering;
- current-position rendering;
- route calculation;
- route alternatives where supported;
- traffic information;
- GPS navigation;
- rerouting;
- maneuver guidance;
- navigation route drawing;
- arrival events;
- navigation guidance text;
- Amap navigation voice when the chosen speech mode uses Amap internal voice.

Our app remains responsible for:

- voice assistant interaction;
- AI reasoning;
- intent routing;
- assistant UI;
- visual overlays;
- action abstraction;
- media/device command logic;
- assistant speech;
- test harness;
- local diagnostics.

---

## 8. Amap SDK Setup Requirements

Before implementation, the project must have:

1. an Amap developer application;
2. an Android Amap API key;
3. package name bound to the key;
4. debug SHA1 bound to the key;
5. release SHA1 bound before release testing;
6. Navigation SDK integrated into Gradle;
7. location permissions;
8. required Android lifecycle forwarding;
9. Amap privacy-compliance initialization completed before using SDK APIs.

The implementation must not hard-code secrets into source control.

For the API key:

- inject using local/secure build configuration;
- do not commit production credentials;
- support debug/release key separation if needed.

---

## 9. Navigation Abstraction

Create a project-owned abstraction.

```kotlin
interface NavigationController {
    suspend fun resolveDestination(query: String): DestinationResult
    suspend fun planRoute(destination: Destination): RoutePlanResult
    suspend fun startNavigation(routeId: Int? = null): NavigationResult
    suspend fun stopNavigation()
    suspend fun cancelRoute()
    suspend fun reroute(): NavigationResult
    fun state(): StateFlow<NavigationState>
}
```

Do not expose `AMapNavi` directly to the voice/LLM layer.

Implementation:

```text
NavigationController
    ↓
AmapNavigationController
    ↓
AMapNavi
```

This makes it possible to replace or mock Amap in tests.

---

## 10. Destination Resolution

A voice phrase such as:

```text
导航去珠海站
```

does not itself contain coordinates.

Therefore navigation requires a destination-resolution stage.

```text
"珠海站"
    ↓
DestinationResolver
    ↓
POI / coordinate candidate(s)
    ↓
selected Destination
    ↓
route calculation
```

Create:

```kotlin
data class Destination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String? = null,
    val address: String? = null
)

interface DestinationResolver {
    suspend fun resolve(query: String): DestinationResult
}
```

For the MVP, this may be backed by an appropriate Amap search/POI capability.

Do not let the LLM invent latitude/longitude values.

If destination resolution is ambiguous:

```text
"导航去万达"
```

the assistant should request clarification or show/select candidates rather than guessing.

---

## 11. Navigation Flow

Example:

```text
User:
"导航去珠海站"

↓ ASR

导航去珠海站

↓ Intent

type = NAVIGATION
destinationQuery = 珠海站

↓ DestinationResolver

Destination(
    name = "珠海站",
    lat = ...,
    lon = ...
)

↓ NavigationController.planRoute()

↓ AMapNavi route planning

↓ route success

↓ route displayed in embedded AMapNaviView

↓ start navigation

↓ NavigationState = NAVIGATING
```

The app **does not leave MainActivity** as part of the normal flow.

---

## 12. Navigation Runtime State

Use state owned by our application.

```text
NavigationState
├── IDLE
├── RESOLVING_DESTINATION
├── PLANNING_ROUTE
├── ROUTE_READY
├── NAVIGATING
├── ARRIVED
└── ERROR
```

Do not use the old meaning of:

```text
AMAP_ACTIVE = another application is foreground
```

If the name `AMAP_ACTIVE` already exists in code, either remove it or redefine it clearly as:

```text
embedded navigation session is active
```

Prefer `NavigationState` instead.

---

## 13. AMapNaviView Lifecycle

The Activity/Fragment must forward the lifecycle expected by the Amap navigation view and navigation manager.

Implementation must verify and correctly handle:

- creation;
- resume;
- pause;
- save-instance-state if required;
- destroy;
- orientation/configuration behavior;
- Activity recreation;
- app background/foreground transition.

Do not assume the SDK survives lifecycle transitions automatically.

Navigation state should be restored or fail cleanly.

---

## 14. AMap Navigation Callbacks

Register navigation callbacks through the current supported Amap listener APIs.

At minimum consume:

- initialization success/failure;
- route calculation success/failure;
- navigation start;
- position/location update where appropriate;
- guidance / navigation information;
- navigation text callback;
- arrival at destination;
- GPS weak/error state where available;
- route changes / rerouting where available.

Callbacks update `NavigationStateStore`.

The UI must not directly parse Amap callbacks.

---

## 15. Navigation UI Customization

The product should use `AMapNaviView` as a navigation surface but customize the surrounding UI to match the mentor-style product.

The implementation may:

- hide unnecessary default Amap controls;
- reposition allowed controls;
- add application-owned controls above the map;
- add application-owned assistant elements above the map;
- preserve legally/contractually required Amap branding or attribution;
- preserve critical navigation information.

Do not remove mandatory attribution or controls if Amap terms/API requirements require them.

---

## 16. Voice Runtime

The existing voice architecture remains conceptually:

```text
Microphone
    ↓
Wake / push-to-talk / listening state
    ↓
Baidu realtime voice / ASR
    ↓
intent / tool call
    ↓
NavigationController / ActionExecutor / information handler
    ↓
response
    ↓
VoicePolicy
    ↓
assistant TTS + UI feedback
```

The map integration must not require rewriting the entire voice provider.

The embedded navigation change is primarily a **navigation/UI boundary change**, not a reason to replace ASR/LLM/action layers.

---

## 17. Response Categories

Use semantic categories.

```text
INFORMATIONAL_RESPONSE
ACTION_CONFIRMATION
ACTION_FAILURE
NAVIGATION_CONFIRMATION
NAVIGATION_ERROR
CRITICAL_ALERT
```

Do not decide speech behavior by matching Chinese words in response strings.

Bad:

```text
if ("空调" in text) ...
```

Good:

```text
responseCategory == ACTION_CONFIRMATION
```

---

## 18. Voice Policy During Navigation

Because the map and navigation are embedded in our own app, the policy should be based on `NavigationState`, not whether an external Amap app is in the foreground.

Initial policy:

| Category | Not navigating | Navigating |
|---|---|---|
| INFORMATIONAL_RESPONSE | Speak | Silent by default |
| ACTION_CONFIRMATION | Speak | Speak briefly |
| ACTION_FAILURE | Speak | Speak briefly |
| NAVIGATION_CONFIRMATION | Speak briefly | Speak only when useful |
| NAVIGATION_ERROR | Speak | Speak briefly |
| CRITICAL_ALERT | Speak | Speak |

Example:

```text
NavigationState = NAVIGATING
responseCategory = INFORMATIONAL_RESPONSE
→ UI may update
→ assistant TTS = SILENT
```

Example:

```text
NavigationState = NAVIGATING
responseCategory = ACTION_CONFIRMATION
→ assistant TTS = SPEAK_SHORT
```

---

## 19. Navigation Speech Coordination

This section replaces the old external-Amap heuristic.

### 19.1 Preferred architecture

Use the embedded Navigation SDK's own navigation-speech state/control where supported.

Amap documentation exposes navigation voice facilities including:

- internal navigation voice mode;
- navigation text callbacks;
- a TTS-playing state query;
- custom text playback while internal navigation voice is in use.

The implementation must verify exact behavior against the selected SDK version and the target device.

### 19.2 Preferred action-confirmation behavior

When an action completes during navigation:

```text
Action executes immediately
    ↓
ActionResult generated
    ↓
Need short spoken confirmation?
    ↓
Check navigation speech state / supported SDK speech API
    ↓
If safe:
    speak short confirmation
If navigation guidance currently owns speech:
    use supported SDK coordination/queue behavior if reliable
```

The action itself must never be delayed just to delay speech.

### 19.3 Fallback behavior

If reliable navigation-speech coordination cannot be validated on the target SDK/device:

- do not block the MVP;
- allow short action confirmation overlap after initialization;
- optionally retain a configurable startup suppression window;
- suppressed startup confirmations are discarded, not replayed later.

The **5-second startup suppression** is therefore a fallback compatibility policy, not the primary embedded-SDK architecture.

---

## 20. Audio Focus Rules

The application must avoid unnecessary audio interference.

When assistant TTS is silent:

- do not start assistant audio playback;
- do not request playback audio focus unnecessarily;
- do not play silent audio as a workaround.

When assistant TTS is allowed:

- request audio focus according to Android media/voice best practice;
- verify whether Amap internal navigation voice is interrupted, ducked, delayed, or lost;
- record audio-focus events for diagnostics.

Navigation guidance has higher practical priority than noncritical assistant speech.

---

## 21. Action Abstraction Layer

Keep an abstract device/action layer.

```kotlin
data class ActionRequest(
    val target: String,
    val action: String,
    val value: String? = null
)

data class ActionResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null
)

interface ActionExecutor {
    suspend fun execute(request: ActionRequest): ActionResult
}
```

Current implementation:

```text
MockActionExecutor
```

Future implementations:

```text
AndroidActionExecutor
CarActionExecutor
BluetoothActionExecutor
HomeAssistantExecutor
ExternalApiExecutor
```

The navigation integration must not bypass this layer.

---

## 22. Mock Action Behavior

Example:

```text
User:
打开空调

↓ Intent

target = air_conditioner
action = turn_on

↓ MockActionExecutor

success = true
message = 空调已打开
```

No real air-conditioner hardware is required for the MVP.

Validate:

```text
voice
→ ASR
→ intent
→ ActionExecutor
→ ActionResult
→ VoicePolicy
→ assistant UI
→ optional TTS
```

---

## 23. Mock Failure Behavior

Support controlled errors.

Example:

```json
{
  "success": false,
  "errorCode": "DEVICE_OFFLINE",
  "message": "空调连接失败"
}
```

During navigation:

- action failure appears visually;
- short failure speech is allowed unless navigation voice coordination suppresses it;
- failure must be recorded in the test result.

---

## 24. Navigation Command Set — MVP

Support at minimum:

```text
导航去 <destination>
停止导航
取消导航
重新规划路线
```

Optional after basic route flow works:

```text
换一条路线
避开高速
避开拥堵
回到当前位置
```

Do not add advanced commands before basic route planning + embedded map + navigation are stable.

---

## 25. Same-Screen UI Requirements

During navigation, the user must be able to see simultaneously:

1. Amap navigation map;
2. route/current-position/navigation state;
3. assistant avatar/status;
4. temporary assistant feedback when applicable.

Pass condition:

```text
Assistant response does NOT replace the map screen.
```

Fail examples:

- opening another Activity that completely hides navigation for routine responses;
- launching the installed Amap app;
- displaying the assistant only through system notifications;
- requiring a floating-window permission for normal operation.

---

## 26. Touch Interaction

Assistant overlays must not accidentally block map gestures over large areas.

Requirements:

- only visible controls consume touch;
- transparent regions should pass interaction appropriately;
- assistant speech bubble should be compact;
- overlays must not obscure critical maneuver information;
- dismissible cards should auto-dismiss or offer a clear close action.

---

## 27. Automated Speech Test Harness

Retain the real speech pipeline test.

```text
Test Phrase
    ↓
TTS User Simulator
    ↓
Audio
    ↓
Assistant ASR
    ↓
Intent / Tool Router
    ↓
NavigationController / ActionExecutor
    ↓
VoicePolicy
    ↓
Assistant audio + UI
    ↓
Capture / state log
    ↓
ASR verifier + structural assertions
```

Do not test only by injecting text into the intent parser.

---

## 28. Test Levels

### Level A — Logic / deterministic test

Use deterministic inputs to test:

- intent;
- NavigationController interface;
- ActionExecutor;
- VoicePolicy;
- state transitions;
- UI state;
- failure recording.

Use fake/mocked Amap interfaces where possible.

### Level B — Direct audio pipeline test

Generate TTS user audio and feed it to the voice input pipeline.

Measure:

- ASR correctness;
- intent correctness;
- command execution;
- response category;
- voice decision.

### Level C — Physical embedded-navigation E2E test

On the actual Android device:

- our app is foreground;
- embedded `AMapNaviView` is visible;
- actual Amap route/navigation session is active;
- generated user voice is played physically or injected through a supported test setup;
- microphone captures it;
- command executes;
- navigation remains visible;
- assistant overlay updates;
- navigation voice and assistant voice behavior are measured.

This replaces the old "our app in background while external Amap is foreground" test model.

---

## 29. Core Test Cases

### TC01 — App launches embedded map

Expected:

- our Activity remains foreground;
- `AMapNaviView` renders;
- assistant overlay visible;
- no external Amap app launches.

### TC02 — Destination resolution

Input:

```text
导航去珠海站
```

Expected:

- ASR succeeds;
- NAVIGATION intent detected;
- destination resolver returns a real destination;
- no invented coordinates.

### TC03 — Route planning

Expected:

- route request sent to Amap;
- route success callback received;
- route rendered;
- state = ROUTE_READY.

### TC04 — Start navigation

Expected:

- navigation starts;
- same Activity remains visible;
- state = NAVIGATING;
- map remains interactive.

### TC05 — Information request during navigation

Input:

```text
现在几点？
```

Expected:

- ASR succeeds;
- request may be processed;
- UI may show compact result if desired;
- assistant speech = SILENT by default;
- navigation remains visible.

### TC06 — Action success during navigation

Input:

```text
打开空调
```

Expected:

- action intent detected;
- `MockActionExecutor` executes;
- state updates;
- assistant overlay shows `空调已打开`;
- short confirmation speech follows current VoicePolicy;
- map remains visible.

### TC07 — Action failure during navigation

Inject:

```text
DEVICE_OFFLINE
```

Expected:

- assistant overlay shows failure;
- short failure speech follows VoicePolicy;
- navigation continues.

### TC08 — Stop navigation

Input:

```text
停止导航
```

Expected:

- embedded navigation stops;
- app remains open;
- state returns to IDLE or map-browse state;
- no external app transition.

### TC09 — Arrival callback

Expected:

- state = ARRIVED;
- route/navigation UI updates;
- assistant may provide short arrival feedback.

### TC10 — Ambiguous destination

Input:

```text
导航去万达
```

Expected:

- no invented destination;
- ambiguity is surfaced;
- ask/select candidate.

---

## 30. Same-Screen UI Test

Automated/instrumented test must assert that while `NavigationState = NAVIGATING`:

```text
AMapNaviView = visible
AssistantOverlay = visible
Activity = our app
```

When assistant response appears:

```text
AMapNaviView remains visible
AssistantOverlay response visible
```

A screenshot artifact should be captured for regression testing.

---

## 31. Navigation Voice / Assistant Voice Test

Test at least:

### Case A — navigation voice idle

Action:

```text
打开空调
```

Expected:

- action executes;
- short confirmation can play.

### Case B — navigation guidance is speaking

Action:

```text
关闭空调
```

Expected:

- action executes immediately;
- test selected SDK-level coordination behavior;
- navigation session must not be destroyed;
- navigation guidance must not be silently lost without being recorded.

### Case C — coordination unsupported/unreliable

Expected:

- fallback policy activates;
- overlap or configured suppression is recorded;
- system does not deadlock waiting for a nonexistent speech-end event.

---

## 32. Navigation Lifecycle Test

Test:

1. start route;
2. begin navigation;
3. press Home;
4. return to app;
5. rotate/configuration change if supported;
6. lock/unlock where appropriate;
7. stop navigation;
8. destroy/relaunch.

Record:

- route state;
- map state;
- voice connection;
- WebSocket/session state;
- crashes;
- memory/resource leaks.

---

## 33. Local Failure Record

Every failed automated/E2E test must create a persistent local record.

Suggested structure:

```text
test-results/
├── latest/
│   ├── report.json
│   └── report.md
└── failures/
    └── 2026-09-16/
        └── TC06_001/
            ├── failure.json
            ├── input.wav
            ├── output.wav
            ├── screenshot.png
            └── logs.txt
```

Do not overwrite historical failure records.

---

## 34. Failure Record Contents

At minimum:

```json
{
  "test_id": "TC06",
  "timestamp": "...",
  "navigation_state": "NAVIGATING",
  "our_activity_foreground": true,
  "amap_navi_view_visible": true,
  "assistant_overlay_visible": true,
  "input_text": "打开空调",
  "input_asr": "打开空调",
  "expected_intent": "ACTION",
  "actual_intent": "ACTION",
  "action_success": true,
  "response_category": "ACTION_CONFIRMATION",
  "voice_decision": "SPEAK_SHORT",
  "expected_output": "空调已打开",
  "output_asr": "空调已打开",
  "navigation_interrupted": false,
  "failure_stage": null,
  "result": "PASS"
}
```

Save where relevant:

- input audio;
- output audio;
- screenshot;
- assistant state;
- navigation state;
- route-planning result;
- Amap callback events;
- audio-focus events;
- ASR transcript;
- logs;
- exception stack trace.

---

## 35. Failure Stage Classification

Use:

```text
AMAP_INITIALIZATION
AMAP_RENDERING
DESTINATION_RESOLUTION
ROUTE_PLANNING
NAVIGATION_START
NAVIGATION_RUNTIME
NAVIGATION_CALLBACK
INPUT_AUDIO
ASR
INTENT
ACTION_EXECUTION
VOICE_POLICY
ASSISTANT_TTS
NAVIGATION_TTS
AUDIO_FOCUS
UI_OVERLAY
LIFECYCLE
NETWORK
OUTPUT_ASR
UNKNOWN
```

Do not report only:

```text
TEST FAILED
```

---

## 36. Automatic Regression Corpus

A reproducible bug can be promoted to:

```text
tests/regressions/
```

Examples:

```text
embedded_map_missing_001.json
assistant_overlay_hidden_002.json
route_start_failure_003.json
navigation_action_tts_conflict_004.json
ambiguous_destination_005.json
```

Transient network/microphone failures should remain failure records unless confirmed reproducible.

---

## 37. Performance Measurements

### Voice

- ASR latency;
- CER;
- recognition success rate;
- intent accuracy;
- time to first assistant audio;
- assistant speech duration.

### Navigation

- destination resolution latency;
- route planning latency;
- time from route success to map display;
- time from start-navigation command to NAVIGATING state;
- reroute latency;
- navigation callback delay where measurable.

### Actions

- action success rate;
- action execution latency;
- confirmation latency.

### UI

- time to assistant overlay update;
- dropped frames/jank during navigation;
- memory growth during long navigation;
- overlay visibility correctness.

Report:

- median;
- P90;
- P95.

---

## 38. Product-Level Metrics

Track independently:

```text
Embedded map render success rate
Route calculation success rate
Navigation start success rate
Navigation session unexpected stop count
Assistant ASR success during navigation
Action execution success during navigation
Assistant overlay visibility success rate
Unwanted assistant speech rate
Expected short-confirmation success rate
Navigation/assistant audio conflict count
WebSocket disconnect count
Crash count
```

Do not collapse all behavior into a single generic pass rate.

---

## 39. Voice-Suppression Metric

For categories expected to be silent:

```text
Unwanted Voice Rate
=
assistant voice events where VoicePolicy expected SILENT
/
requests where VoicePolicy expected SILENT
```

Target for deterministic tests:

```text
0%
```

Action confirmations are excluded when:

```text
expected policy = SPEAK_SHORT
```

---

## 40. UI Acceptance Criteria

The UI passes when:

1. Amap navigation renders inside our application.
2. The assistant remains visible on the same map screen.
3. The user does not need to switch to an external Amap application.
4. Routine assistant feedback does not replace the navigation screen.
5. Assistant overlays do not block critical navigation information.
6. Map interaction remains usable.
7. Action state can be displayed without leaving navigation.
8. Assistant state changes are visually observable.
9. UI remains stable through navigation callbacks and route changes.

---

## 41. Functional Acceptance Criteria

The MVP passes when:

1. Amap Navigation SDK initializes successfully.
2. Amap API key/privacy/permission setup is correct.
3. Embedded map renders.
4. Voice destination command can resolve a real destination.
5. Route can be calculated.
6. Route appears in the embedded navigation view.
7. Navigation can start without launching external Amap.
8. Navigation state is available to our app.
9. Assistant remains on the same screen.
10. ASR remains functional during navigation.
11. Information requests follow navigation voice policy.
12. Action commands execute through `ActionExecutor`.
13. Mock actions work without real vehicle hardware.
14. Action confirmations appear visually over the map.
15. Short action TTS works according to VoicePolicy.
16. Navigation speech and assistant speech coordination is tested.
17. Navigation can be stopped through our controller.
18. Arrival/navigation errors are handled.
19. Failed tests generate useful local records.
20. Reproducible failures can become regression tests.
21. Latency distributions are recorded.
22. External Amap handoff is not used for the normal navigation path.

---

## 42. Explicit Non-Goals for This MVP

Do not spend time yet on:

- building our own map renderer;
- building our own route algorithm;
- reproducing the entire production Amap UI;
- real CAN-bus integration;
- real vehicle HVAC integration;
- full infotainment launcher replacement;
- advanced camera/video assistant panel;
- complex animation/avatar system;
- perfect navigation/assistant speech arbitration before basic embedded navigation works;
- supporting every Amap route strategy;
- large-scale noise/accent stress testing.

---

## 43. Migration From Current Implementation

### Keep

```text
Baidu realtime connection
ASR / voice input
intent/tool routing
ActionRequest
ActionResult
ActionExecutor
MockActionExecutor
VoicePolicy concept
local diagnostics
test harness concepts
wake-word work
credential storage
```

### Replace

```text
external Amap launch
external-app navigation handoff
AMAP_ACTIVE meaning "Amap app is foreground"
background-only coexistence architecture
cross-app assistant overlay assumptions
tests whose success requires external Amap to own foreground
```

### Add

```text
AMapNaviView
AMapNavi integration
AmapNavigationController
DestinationResolver
NavigationStateStore
assistant-on-map overlay UI
Amap callback adapter
embedded-navigation lifecycle handling
navigation UI tests
SDK-level navigation speech coordination
```

---

## 44. Implementation Order

Do not attempt all functionality at once.

### Phase 1 — Embedded map proof

Goal:

```text
our Activity
+
AMapNaviView visible
+
assistant placeholder overlay visible
```

No voice command required yet.

Pass condition:

- app renders map;
- assistant placeholder remains above it;
- no external Amap launch.

### Phase 2 — Route proof

Hard-code one known destination for development only.

Pass condition:

- calculate route;
- render route;
- start simulated or real navigation;
- remain in our Activity.

### Phase 3 — Navigation abstraction

Implement:

```text
NavigationController
AmapNavigationController
NavigationState
callback adapter
```

Remove direct SDK calls from UI.

### Phase 4 — Voice destination integration

Connect:

```text
ASR
→ NAVIGATION intent
→ DestinationResolver
→ NavigationController
```

### Phase 5 — Assistant overlay behavior

Add:

```text
IDLE
LISTENING
PROCESSING
RESPONDING
ACTION_SUCCESS
ACTION_FAILURE
```

### Phase 6 — Action commands during navigation

Connect existing:

```text
ActionExecutor
+
VoicePolicy
+
assistant overlay
```

### Phase 7 — Audio coordination

Test Amap navigation voice versus assistant confirmation speech.

Use SDK-level speech state/control if validated.

Only use startup suppression/overlap fallback if required.

### Phase 8 — Physical E2E + regression

Run speech-generated test phrases on the target device while embedded navigation is active.

---

## 45. P0 / P1 / P2

### P0

```text
Amap SDK integration
Amap API key / privacy / permissions
AMapNaviView embedded in our screen
assistant overlay on same screen
NavigationController abstraction
DestinationResolver
route calculation
start/stop navigation
navigation state callbacks
MockActionExecutor
voice → intent → action/navigation routing
same-screen action feedback
basic VoicePolicy
physical embedded-navigation test
local failure recording
```

### P1

```text
navigation/assistant TTS coordination
audio-focus monitoring
output-ASR verification
automatic regression replay
failure-stage classification
P50/P90/P95 metrics
lifecycle stress testing
route ambiguity handling polish
UI interaction/jank measurement
```

### P2

```text
real car/device integrations
advanced media integration
advanced avatar/video panel
large noise/accent suite
long-duration navigation stress test
production vehicle UI polish
```

---

## 46. Architecture Checkpoint Before Coding

Before the executor modifies code, it must report:

1. current Android package name;
2. current min/target SDK;
3. current UI framework: XML Views, Compose, or mixed;
4. current Amap-related implementation files;
5. whether current code launches external Amap through Intent/deep link/component;
6. exact existing voice provider modules to preserve;
7. exact ActionExecutor modules to preserve;
8. existing test infrastructure;
9. required Amap key/SHA1 information still missing;
10. proposed files to add/modify/remove.

The executor must **not** delete working voice/action code merely because navigation architecture is changing.

---

## 47. First Coding Checkpoint

The first implementation checkpoint is intentionally small.

Required output:

```text
1. Project builds.
2. AMapNaviView renders inside our Activity.
3. A static/placeholder assistant avatar or bubble is visible above the map.
4. No external Amap app opens.
5. Existing Baidu/voice code still compiles.
6. Existing tests not related to the removed handoff behavior still pass.
```

Only after this checkpoint passes should route planning be added.

---

## 48. Implementation Notes Verified Against Current Amap Documentation

At the time this specification was rewritten:

- Amap's Android Navigation SDK documents `AMapNavi` as the navigation management singleton for route planning and navigation.
- `AMapNaviListener` provides route/navigation callbacks.
- `AMapNaviView` supports customization of map elements and UI controls.
- Real navigation requires successful route calculation before `startNavi(...)`.
- current setup documentation requires an Amap key and Android configuration.
- the Android Navigation SDK change log lists version 11.2.100 dated 2026-08-06.
- Amap documentation also describes internal navigation voice, navigation-text callbacks, TTS-playing state, and custom text playback APIs.

Exact API signatures and compatibility must be checked against the actual SDK dependency selected by the project before implementation.

---

# Final Architecture Summary

```text
                    OUR ANDROID APP
┌─────────────────────────────────────────────────────┐
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │                AMapNaviView                   │  │
│  │                                               │  │
│  │       map / route / traffic / position        │  │
│  │                                               │  │
│  │   ┌──────────────────────────────────────┐    │  │
│  │   │ AssistantOverlay                     │    │  │
│  │   │ avatar + state + short speech bubble │    │  │
│  │   └──────────────────────────────────────┘    │  │
│  │                                               │  │
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  Voice Runtime                 Navigation            │
│  ─────────────                 ──────────            │
│  Baidu realtime               NavigationController  │
│  ASR / intent                 ↓                     │
│  VoicePolicy                  AMapNavi               │
│                               ↓                     │
│  Action Layer                 AMap callbacks         │
│  ────────────                 ↓                     │
│  ActionExecutor               NavigationState       │
│  ↓                                                  │
│  Mock / real later                                  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**The app owns the product experience. Amap owns navigation technology.**
