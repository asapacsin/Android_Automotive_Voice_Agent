package com.novadrive.evaluation

/**
 * How a scenario is driven. What each mode proves is stated in docs/EVALUATION.md; in short:
 *
 * - [SIM_LOGIC]: JVM, Level A. The real app stack (Baidu Flex client and protocol, session core,
 *   guards, tool dispatcher, navigation state machine, vehicle simulator) runs against a SCRIPTED
 *   realtime server. The model's choices are scripted, so tool-selection numbers here measure the
 *   app's plumbing and recovery, never the model's understanding.
 * - [TEXT_LIVE]: phone, Level B. Scripted text turns go to the real Baidu model; simulated world.
 *   Measures the real model's tool choice without ASR. (The "fast logic" mode on device.)
 * - [AUDIO_E2E]: phone, Level B. Synthetic speech through the real audio path, Baidu ASR and model;
 *   simulated world.
 */
enum class TestMode(val level: Level) {
    SIM_LOGIC(Level.A_SIMULATED),
    TEXT_LIVE(Level.B_DEVICE),
    AUDIO_E2E(Level.B_DEVICE),
}

enum class Level { A_SIMULATED, B_DEVICE, C_REAL_WORLD }

enum class Suite {
    SMOKE,
    REGRESSION,
    AUDIO_E2E,
    CHAOS,
    LONG_SESSION,
    NAVIGATION,
    VEHICLE,
    MEDIA,
    VISION,
    /** Vision against the real Qianfan API with fixture images (phone, costs quota). */
    VISION_REAL,
    RELEASE,
}

/** A tool call as expected by the oracle, or as emitted by the scripted model. */
data class ToolCallSpec(val name: String, val args: Map<String, String> = emptyMap()) {
    override fun toString(): String = if (args.isEmpty()) name else "$name(" + args.entries.joinToString { "${it.key}=${it.value}" } + ")"
}

/** What a turn must lead to. */
enum class Outcome {
    /** The requested action happened and the reply may say so. */
    SUCCESS,

    /** Nothing is supposed to happen (unsupported, chit-chat); the reply must not claim an action. */
    NO_ACTION,

    /** The action was attempted and failed (injected fault); the reply must not claim success. */
    REPORTED_FAILURE,
}

data class TurnExpectation(
    /** Expected tool calls in order. Empty = no tool expected. */
    val tools: List<ToolCallSpec> = emptyList(),
    /**
     * Alternative acceptable call sequences (a real model may choose e.g. set vs change for
     * 「调到24度」). Any one matching sequence counts as correct selection.
     */
    val alternatives: List<List<ToolCallSpec>> = emptyList(),
    /**
     * Calls that may additionally appear without counting as unexpected: a tool name, or
     * `name:action` for one action of it (e.g. `control_climate:get_state`, a harmless read).
     */
    val tolerated: Set<String> = emptySet(),
    /** State keys (see [StateKeys]) and expected values. `!x` means "not x", `*` means "any value". */
    val state: Map<String, String> = emptyMap(),
    val outcome: Outcome = Outcome.SUCCESS,
    /** False when the turn is expected to get no answer (e.g. the connection drops mid-turn). */
    val requireReply: Boolean = true,
    /** Words that must not appear in any reply (e.g. invented scene content after a vision failure). */
    val forbiddenReplyWords: List<String> = emptyList(),
    /** At least one of these must appear in the final reply (loose content check, real-API suites). */
    val replyMentionsAny: List<String> = emptyList(),
    /** False: deliver and move on after [Step.Say.timeoutMs] without judging state or reply (overlap tests). */
    val awaitSettle: Boolean = true,
)

/** Scripted-model behaviour for [TestMode.SIM_LOGIC]. Ignored by live modes. */
enum class ModelBehavior {
    CORRECT,

    /** Replies 「好的，已经办好了。」 without calling any tool (the measured false-claim pattern). */
    FALSE_CLAIM,

    /** Calls the tool, then claims success even if the tool result says ok=false. */
    IGNORE_TOOL_ERROR,

    /** Emits the same call twice with different call ids in one response. */
    DUPLICATE_CALL,

    /** Completes the first response with no output at all. */
    EMPTY_RESPONSE,

    /** Declines politely (「暂时不支持。」). */
    REFUSE,

    /** Emits [Step.Say.modelCalls] instead of the expected calls (a wrong tool choice). */
    CUSTOM_CALLS,
}

sealed interface Step {
    /**
     * One user turn. [utterance] is the text (TEXT_LIVE / SIM_LOGIC) and the speech content
     * (AUDIO_E2E, file chosen by [audioKey] and the run's variant).
     */
    data class Say(
        val utterance: String,
        val expect: TurnExpectation,
        val model: ModelBehavior = ModelBehavior.CORRECT,
        val modelCalls: List<ToolCallSpec> = emptyList(),
        val audioKey: String? = null,
        val timeoutMs: Long = 30_000,
    ) : Step

    /** Moves the simulated vehicle along the active route (0.0..1.0). */
    data class AdvanceRoute(val fraction: Double) : Step

    /** The simulated vehicle reaches the destination. */
    data object Arrive : Step

    /** Injects a fault before the following steps. */
    data class Inject(val fault: Fault) : Step

    /** A state check without a turn. */
    data class Check(val state: Map<String, String>) : Step

    data class Pause(val ms: Long) : Step

    /** Interrupts the assistant [afterTtsMs] after it starts speaking with [utterance]. */
    data class BargeIn(
        val primer: String,
        val afterTtsMs: Long,
        val utterance: String,
        val expect: TurnExpectation,
    ) : Step

    /** Opens / closes the (simulated) camera window. */
    data class Camera(val open: Boolean, val fixture: String = "road_clear") : Step
}

/** Faults a driver may inject. Unsupported faults are reported as SKIPPED, never silently ignored. */
sealed interface Fault {
    data class VehicleFailNext(val kind: String = "EXECUTION_FAILURE") : Fault
    data class VehicleFailAlways(val operation: String, val kind: String = "UNAVAILABLE") : Fault
    data object VehicleClearFaults : Fault
    data class NavigationResolveDelay(val query: String, val delayMs: Long) : Fault
    data object NavigationResolveFailsOnce : Fault
    data class NavigationCalcFailsOnce(val code: Int = 12) : Fault
    data object NavigationStartFailsOnce : Fault
    data object MusicFailsOnce : Fault
    data object VisionFailsOnce : Fault
    data class ServerLatency(val ms: Long) : Fault
    /** The server drops the connection on the next turn, before or after emitting its tool call. */
    data class ServerDisconnectDuringNextTurn(val afterToolCall: Boolean = false) : Fault
    data object ServerRejectsNextConnect : Fault
    data object ServerErrorEventOnNextTurn : Fault
    data class ToolResultDelay(val tool: String, val ms: Long) : Fault
    data object DuplicateToolResult : Fault
    data object BackgroundForeground : Fault
}

data class WorldSetup(
    val hvacPower: Boolean = false,
    val temperature: Double = 24.0,
    val fan: Int = 2,
    val cameraOpen: Boolean = false,
    val visionFixture: String = "road_clear",
    val musicPlaying: Boolean = false,
)

data class Scenario(
    val id: String,
    val title: String,
    val suites: Set<Suite>,
    val modes: Set<TestMode> = setOf(TestMode.SIM_LOGIC, TestMode.TEXT_LIVE, TestMode.AUDIO_E2E),
    val setup: WorldSetup = WorldSetup(),
    val steps: List<Step>,
    /** Why this scenario exists; shown in failure output. */
    val purpose: String = "",
) {
    init {
        require(id.matches(Regex("[A-Z0-9_]+"))) { "scenario id $id" }
        require(steps.isNotEmpty()) { "$id has no steps" }
    }

    val turns: List<Step.Say> get() = steps.filterIsInstance<Step.Say>()
}

/** State keys every driver's probe must report. Values are strings; numbers as in [StateKeys.num]. */
object StateKeys {
    const val HVAC_POWER = "hvac.power" // on | off
    const val HVAC_TEMP = "hvac.temperature" // 24.0
    const val HVAC_FAN = "hvac.fan" // 2
    const val NAV_PHASE = "nav.phase" // NavigationPhase name
    const val NAV_DESTINATION = "nav.destination" // selected destination name, or ""
    const val NAV_ROUTE = "nav.route" // route label of the driven route (推荐/距离最短/免费), or ""
    const val NAV_CANDIDATES = "nav.candidates" // destinations on screen
    const val NAV_ROUTES = "nav.routes" // routes on screen
    const val NAV_PROGRESS = "nav.progress" // 0.00..1.00
    const val NAV_SPEECH_MUTE = "nav.speechMuteFlag" // legacy NavigationState.navigating
    const val MEDIA_PLAYING = "media.playing" // true | false
    const val CAMERA_OPEN = "camera.open" // true | false
    const val VISION_REQUESTS = "vision.requests" // count
    const val SESSION_ALIVE = "session.alive" // true | false

    fun num(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
