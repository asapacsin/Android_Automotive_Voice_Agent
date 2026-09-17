package com.novadrive.evaluation

import com.novadrive.evaluation.StateKeys.CAMERA_OPEN
import com.novadrive.evaluation.StateKeys.HVAC_FAN
import com.novadrive.evaluation.StateKeys.HVAC_POWER
import com.novadrive.evaluation.StateKeys.HVAC_TEMP
import com.novadrive.evaluation.StateKeys.MEDIA_PLAYING
import com.novadrive.evaluation.StateKeys.NAV_CANDIDATES
import com.novadrive.evaluation.StateKeys.NAV_DESTINATION
import com.novadrive.evaluation.StateKeys.NAV_PHASE
import com.novadrive.evaluation.StateKeys.NAV_PROGRESS
import com.novadrive.evaluation.StateKeys.NAV_ROUTE
import com.novadrive.evaluation.StateKeys.NAV_ROUTES
import com.novadrive.evaluation.StateKeys.NAV_SPEECH_MUTE
import com.novadrive.evaluation.StateKeys.SESSION_ALIVE
import com.novadrive.evaluation.StateKeys.VISION_REQUESTS

/**
 * The deterministic scenario set. It relies on the simulated world's fixed data
 * (app `SimulatedNavigationWorld`, `SimulatedVision`):
 *
 * - 「澳门大学」 → 澳门大学 / 澳门大学图书馆 / 澳门大学运动场 (3 candidates)
 * - 「万达」 → 万达广场 / 万达影城 / 万达酒店
 * - 「横琴口岸」, 「公司」, 「家」 → exactly one candidate (routes are calculated at once)
 * - every destination has three routes, in this order:
 *   1 推荐 (fastest, 25 min) · 2 距离最短 (shortest) · 3 免费 (no toll, fewest lights)
 *
 * Variants of wording are separate scenarios so a result names the phrasing that failed.
 */
object ScenarioCatalog {
    private val ALL_MODES = setOf(TestMode.SIM_LOGIC, TestMode.TEXT_LIVE, TestMode.AUDIO_E2E)
    private val SIM_ONLY = setOf(TestMode.SIM_LOGIC)
    private val DEVICE_ONLY = setOf(TestMode.TEXT_LIVE, TestMode.AUDIO_E2E)
    private const val READ_CLIMATE = "control_climate:get_state"

    // ---- building blocks -----------------------------------------------------------------

    private fun climate(action: String, value: String? = null) =
        ToolCallSpec("control_climate", if (value == null) mapOf("action" to action) else mapOf("action" to action, "value" to value))

    private fun navigate(destination: String) = ToolCallSpec("navigate_to", mapOf("destination" to "~$destination"))
    private fun choose(index: Int) = ToolCallSpec("choose_navigation_option", mapOf("index" to "$index"))
    private fun prefer(p: String) = ToolCallSpec("choose_navigation_option", mapOf("preference" to p))
    private fun music(action: String) = ToolCallSpec("control_music", mapOf("action" to action))
    private val exitNav = ToolCallSpec("exit_navigation_mode")
    private val look = ToolCallSpec("describe_camera_view")

    private fun say(
        utterance: String,
        vararg tools: ToolCallSpec,
        state: Map<String, String> = emptyMap(),
        outcome: Outcome = Outcome.SUCCESS,
        alternatives: List<List<ToolCallSpec>> = emptyList(),
        model: ModelBehavior = ModelBehavior.CORRECT,
        modelCalls: List<ToolCallSpec> = emptyList(),
        requireReply: Boolean = true,
        forbidden: List<String> = emptyList(),
        mentions: List<String> = emptyList(),
        awaitSettle: Boolean = true,
        timeoutMs: Long = 30_000,
    ) = Step.Say(
        utterance = utterance,
        expect = TurnExpectation(
            tools = tools.toList(), alternatives = alternatives, tolerated = setOf(READ_CLIMATE),
            state = state, outcome = outcome, requireReply = requireReply,
            forbiddenReplyWords = forbidden, replyMentionsAny = mentions, awaitSettle = awaitSettle,
        ),
        model = model, modelCalls = modelCalls, timeoutMs = timeoutMs,
    )

    private fun temp(v: Double) = StateKeys.num(v)

    /** 「空调调到X度」 accepts set, or power-on then set. */
    private fun setTempAlternatives(v: Int) = listOf(listOf(climate("power_on"), climate("set_temperature", "$v")))

    private val routeList = mapOf(NAV_PHASE to "AWAITING_ROUTE_SELECTION", NAV_ROUTES to "3", NAV_CANDIDATES to "0")
    private val destinationList = mapOf(NAV_PHASE to "AWAITING_DESTINATION_SELECTION", NAV_CANDIDATES to "3")

    /** Gets a single-candidate destination onto the route list. */
    private fun toRouteList(place: String) = say("导航到$place", navigate(place), state = routeList + (NAV_DESTINATION to place))

    private fun startRecommended(place: String) = say(
        "开始导航", prefer("recommended"),
        alternatives = listOf(listOf(choose(1))),
        state = mapOf(NAV_PHASE to "NAVIGATING", NAV_DESTINATION to place, NAV_ROUTE to "推荐"),
    )

    // ---- vehicle ---------------------------------------------------------------------------

    private val setTemp24Phrases = listOf(
        "A" to "空调调到二十四度",
        "B" to "设成24度",
        "C" to "有点热，调到二十四",
        "D" to "温度24",
        "E" to "帮我把空调设到24度",
    )

    private fun vehicle(): List<Scenario> = buildList {
        setTemp24Phrases.forEach { (tag, phrase) ->
            add(
                Scenario(
                    "HVAC_SET_TEMP_24_$tag", "Set 24 °C — wording $tag", setOf(Suite.VEHICLE, Suite.REGRESSION, Suite.AUDIO_E2E) +
                        (if (tag == "A") setOf(Suite.SMOKE) else emptySet()),
                    setup = WorldSetup(hvacPower = true, temperature = 20.0),
                    steps = listOf(say(phrase, climate("set_temperature", "24"), state = mapOf(HVAC_TEMP to temp(24.0)), alternatives = setTempAlternatives(24))),
                    purpose = "Same intent, different wording; the state must read 24.0.",
                ),
            )
        }
        add(Scenario("HVAC_POWER_ON", "Turn the air conditioning on", setOf(Suite.VEHICLE, Suite.REGRESSION, Suite.AUDIO_E2E),
            steps = listOf(say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")))))
        add(Scenario("HVAC_POWER_OFF", "Turn the air conditioning off", setOf(Suite.VEHICLE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true),
            steps = listOf(say("空调关掉", climate("power_off"), state = mapOf(HVAC_POWER to "off")))))
        add(Scenario("HVAC_TEMP_UP", "Relative temperature change uses the current state", setOf(Suite.VEHICLE, Suite.REGRESSION, Suite.AUDIO_E2E),
            setup = WorldSetup(hvacPower = true, temperature = 24.0),
            steps = listOf(say("温度调高一点", ToolCallSpec("control_climate", mapOf("action" to "adjust_temperature")),
                state = mapOf(HVAC_TEMP to temp(25.0)),
                alternatives = listOf(listOf(climate("set_temperature", "25"))))),
        ))
        add(Scenario("HVAC_FAN_UP", "Fan one step up", setOf(Suite.VEHICLE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true, fan = 2),
            steps = listOf(say("风量调大", ToolCallSpec("control_climate", mapOf("action" to "adjust_fan")),
                state = mapOf(HVAC_FAN to "3"), alternatives = listOf(listOf(climate("set_fan", "3"))))),
        ))
        add(Scenario("HVAC_SET_FAN_5", "Absolute fan level", setOf(Suite.VEHICLE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true),
            steps = listOf(say("风量调到5档", climate("set_fan", "5"), state = mapOf(HVAC_FAN to "5")))))
        add(Scenario("HVAC_OUT_OF_RANGE", "50 °C is refused, nothing changes, nothing is claimed", setOf(Suite.VEHICLE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true, temperature = 24.0),
            steps = listOf(say("温度调到50度", climate("set_temperature", "50"), outcome = Outcome.REPORTED_FAILURE,
                state = mapOf(HVAC_TEMP to temp(24.0)), alternatives = listOf(emptyList()))),
        ))
        add(Scenario("HVAC_AT_LIMIT", "Raising at the maximum stays at the maximum", setOf(Suite.VEHICLE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true, temperature = 32.0),
            steps = listOf(say("温度再调高一点", ToolCallSpec("control_climate", mapOf("action" to "adjust_temperature")),
                state = mapOf(HVAC_TEMP to temp(32.0)), alternatives = listOf(emptyList(), listOf(climate("set_temperature", "32"))))),
        ))
        add(Scenario("HVAC_FAULT_EXECUTION", "Backend failure must not be reported as success", setOf(Suite.VEHICLE, Suite.CHAOS, Suite.SMOKE, Suite.REGRESSION),
            setup = WorldSetup(hvacPower = true, temperature = 24.0),
            steps = listOf(
                Step.Inject(Fault.VehicleFailNext("EXECUTION_FAILURE")),
                say("空调调到22度", climate("set_temperature", "22"), outcome = Outcome.REPORTED_FAILURE, state = mapOf(HVAC_TEMP to temp(24.0))),
            ),
            purpose = "False-success prevention: the tool fails, the state is unchanged, the reply must say so.",
        ))
        add(Scenario("HVAC_FAULT_UNAVAILABLE", "Climate offline for every call", setOf(Suite.VEHICLE, Suite.CHAOS),
            steps = listOf(
                Step.Inject(Fault.VehicleFailAlways("POWER", "UNAVAILABLE")),
                say("打开空调", climate("power_on"), outcome = Outcome.REPORTED_FAILURE, state = mapOf(HVAC_POWER to "off")),
                Step.Inject(Fault.VehicleClearFaults),
                say("再打开一次空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")),
            ),
        ))
        add(Scenario("HVAC_FALSE_CLAIM_RESCUED", "Model claims without a tool; the app makes it true", setOf(Suite.VEHICLE, Suite.CHAOS, Suite.REGRESSION),
            modes = SIM_ONLY, setup = WorldSetup(hvacPower = true, temperature = 24.0),
            steps = listOf(say("温度调到26度", climate("set_temperature", "26"), model = ModelBehavior.FALSE_CLAIM, state = mapOf(HVAC_TEMP to temp(26.0)))),
            purpose = "Measured 2026-09-17: 「调高温度了。」 with no tool call. ActionClaimGuard must trigger the action.",
        ))
        add(Scenario("HVAC_MODEL_IGNORES_ERROR", "Tool fails but the model says it worked", setOf(Suite.VEHICLE, Suite.CHAOS, Suite.REGRESSION),
            modes = SIM_ONLY, setup = WorldSetup(hvacPower = true, temperature = 24.0),
            steps = listOf(
                Step.Inject(Fault.VehicleFailNext("UNAVAILABLE")),
                say("空调调到21度", climate("set_temperature", "21"), model = ModelBehavior.IGNORE_TOOL_ERROR,
                    outcome = Outcome.REPORTED_FAILURE, state = mapOf(HVAC_TEMP to temp(24.0))),
            ),
            purpose = "The reply must not end in a success claim when the tool returned ok=false.",
        ))
    }

    // ---- navigation --------------------------------------------------------------------------

    private fun navigation(): List<Scenario> = buildList {
        val nav = setOf(Suite.NAVIGATION, Suite.REGRESSION)
        add(Scenario("NAV_CANDIDATE_ROUTE_FASTEST", "Destination by position, route by preference", nav + Suite.SMOKE + Suite.AUDIO_E2E,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("第二个", choose(2), state = routeList + (NAV_DESTINATION to "澳门大学图书馆")),
                say("选最快的", prefer("fastest"), alternatives = listOf(listOf(choose(1)), listOf(prefer("recommended"))),
                    state = mapOf(NAV_PHASE to "NAVIGATING", NAV_DESTINATION to "澳门大学图书馆", NAV_ROUTE to "推荐")),
            ),
        ))
        add(Scenario("NAV_CANDIDATE_THIRD_CORRECTION", "「不是这个，第三个」 in the destination list", nav + Suite.AUDIO_E2E,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("不是这个，第三个", choose(3), state = routeList + (NAV_DESTINATION to "澳门大学运动场")),
            ),
        ))
        add(Scenario("NAV_CANDIDATE_BY_NAME", "Destination by name", nav,
            steps = listOf(
                say("导航到万达", navigate("万达"), state = destinationList),
                say("就去万达影城", ToolCallSpec("choose_navigation_option", mapOf("name" to "~万达影城")),
                    alternatives = listOf(listOf(choose(2))),
                    state = routeList + (NAV_DESTINATION to "万达影城")),
            ),
        ))
        add(Scenario("NAV_REPLACE_IN_PICKER", "「换成横琴口岸」 replaces the list", nav + Suite.AUDIO_E2E,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("换成横琴口岸", navigate("横琴口岸"), state = routeList + (NAV_DESTINATION to "横琴口岸")),
            ),
        ))
        add(Scenario("NAV_CANCEL_PICKER", "「算了」 closes the list", nav + Suite.AUDIO_E2E,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("算了", exitNav, state = mapOf(NAV_PHASE to "IDLE", NAV_CANDIDATES to "0")),
            ),
        ))
        add(Scenario("NAV_BACK_PICKER", "「返回」 closes the list", nav,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("返回", exitNav, state = mapOf(NAV_PHASE to "IDLE", NAV_CANDIDATES to "0")),
            ),
        ))
        add(Scenario("NAV_ROUTE_SECOND", "Route by position", nav,
            steps = listOf(
                toRouteList("横琴口岸"),
                say("第二条", choose(2), state = mapOf(NAV_PHASE to "NAVIGATING", NAV_ROUTE to "距离最短")),
            ),
        ))
        add(Scenario("NAV_ROUTE_NO_HIGHWAY", "「不要高速」 picks the no-toll route", nav + Suite.AUDIO_E2E,
            steps = listOf(
                toRouteList("横琴口岸"),
                say("不要高速", prefer("no_toll"), alternatives = listOf(listOf(choose(3))),
                    state = mapOf(NAV_PHASE to "NAVIGATING", NAV_ROUTE to "免费")),
            ),
        ))
        add(Scenario("NAV_ROUTE_SHORTEST", "Shortest route", nav,
            steps = listOf(
                toRouteList("公司"),
                say("走最短的那条", prefer("shortest"), alternatives = listOf(listOf(choose(2))),
                    state = mapOf(NAV_PHASE to "NAVIGATING", NAV_ROUTE to "距离最短")),
            ),
        ))
        add(Scenario("NAV_START_RECOMMENDED", "「开始导航」 takes the recommended route", nav + Suite.AUDIO_E2E,
            steps = listOf(toRouteList("公司"), startRecommended("公司")),
        ))
        add(Scenario("NAV_ARRIVAL_CLEANUP", "Driving to arrival returns the app to a clean state", nav + Suite.SMOKE,
            steps = listOf(
                toRouteList("家"),
                startRecommended("家"),
                Step.AdvanceRoute(0.25), Step.Check(mapOf(NAV_PHASE to "NAVIGATING", NAV_PROGRESS to "0.25")),
                Step.AdvanceRoute(0.50),
                Step.AdvanceRoute(0.90), Step.Check(mapOf(NAV_PHASE to "NAVIGATING", NAV_PROGRESS to "0.90")),
                Step.Arrive,
                Step.Check(mapOf(NAV_PHASE to "ARRIVED", NAV_CANDIDATES to "0", NAV_ROUTES to "0", NAV_SPEECH_MUTE to "false")),
                say("空调调到23度", climate("set_temperature", "23"), alternatives = setTempAlternatives(23),
                    state = mapOf(HVAC_TEMP to temp(23.0), NAV_PHASE to "ARRIVED")),
            ),
            purpose = "After arrival nothing of the drive may linger (lists, speech mute).",
        ))
        add(Scenario("NAV_STOP_BY_VOICE", "「结束导航」 stops guidance", nav + Suite.AUDIO_E2E,
            steps = listOf(
                toRouteList("公司"), startRecommended("公司"),
                say("结束导航", exitNav, state = mapOf(NAV_PHASE to "STOPPED", NAV_SPEECH_MUTE to "false")),
            ),
        ))
        add(Scenario("NAV_COMMANDS_WHILE_DRIVING", "Climate and music work during navigation", nav + Suite.VEHICLE + Suite.MEDIA,
            steps = listOf(
                toRouteList("公司"), startRecommended("公司"),
                say("空调调到23度", climate("set_temperature", "23"), alternatives = setTempAlternatives(23),
                    state = mapOf(HVAC_TEMP to temp(23.0), NAV_PHASE to "NAVIGATING")),
                say("播放音乐", music("play"), state = mapOf(MEDIA_PLAYING to "true", NAV_PHASE to "NAVIGATING")),
                say("暂停音乐", music("stop"), state = mapOf(MEDIA_PLAYING to "false", NAV_PHASE to "NAVIGATING")),
            ),
        ))
        add(Scenario("NAV_REPLACE_WHILE_DRIVING", "New destination while navigating", nav,
            steps = listOf(
                toRouteList("公司"), startRecommended("公司"),
                say("换成横琴口岸", navigate("横琴口岸"), state = routeList + (NAV_DESTINATION to "横琴口岸")),
                startRecommended("横琴口岸"),
            ),
        ))
        add(Scenario("NAV_INDEX_OUT_OF_RANGE", "「第五个」 with three on screen changes nothing", nav,
            steps = listOf(
                say("导航到澳门大学", navigate("澳门大学"), state = destinationList),
                say("第五个", choose(5), outcome = Outcome.REPORTED_FAILURE, alternatives = listOf(emptyList()), state = destinationList),
            ),
        ))
        add(Scenario("NAV_PICK_WITH_NOTHING_ON_SCREEN", "「第二个」 with no list", nav,
            steps = listOf(
                say("第二个", choose(2), outcome = Outcome.REPORTED_FAILURE, alternatives = listOf(emptyList()),
                    state = mapOf(NAV_PHASE to "IDLE")),
            ),
        ))
    }

    // ---- media, vision, unsupported ---------------------------------------------------------

    private fun media(): List<Scenario> = listOf(
        Scenario("MEDIA_PLAY", "Play music", setOf(Suite.MEDIA, Suite.REGRESSION, Suite.SMOKE, Suite.AUDIO_E2E),
            steps = listOf(say("播放音乐", music("play"), state = mapOf(MEDIA_PLAYING to "true")))),
        Scenario("MEDIA_PAUSE", "Pause music", setOf(Suite.MEDIA, Suite.REGRESSION, Suite.AUDIO_E2E),
            setup = WorldSetup(musicPlaying = true),
            steps = listOf(say("暂停音乐", music("stop"), state = mapOf(MEDIA_PLAYING to "false")))),
        Scenario("MEDIA_FAULT", "Player failure is reported, not claimed", setOf(Suite.MEDIA, Suite.CHAOS, Suite.REGRESSION),
            steps = listOf(
                Step.Inject(Fault.MusicFailsOnce),
                say("播放音乐", music("play"), outcome = Outcome.REPORTED_FAILURE, state = mapOf(MEDIA_PLAYING to "false")),
            )),
        Scenario("MEDIA_DUPLICATE_CALL", "The model emits the same call twice", setOf(Suite.MEDIA, Suite.CHAOS),
            modes = SIM_ONLY,
            steps = listOf(say("播放音乐", music("play"), model = ModelBehavior.DUPLICATE_CALL, state = mapOf(MEDIA_PLAYING to "true")))),
    )

    private val sceneWords = listOf("行人", "车辆", "停车场")

    private fun vision(): List<Scenario> = listOf(
        Scenario("VISION_ASK_OPEN_CAMERA", "Question about an open camera view", setOf(Suite.VISION, Suite.REGRESSION, Suite.SMOKE, Suite.AUDIO_E2E),
            setup = WorldSetup(cameraOpen = true, visionFixture = "pedestrian"),
            steps = listOf(say("前面有什么", look, state = mapOf(VISION_REQUESTS to "1", CAMERA_OPEN to "true"), mentions = listOf("行人", "人")))),
        Scenario("VISION_ASK_CAMERA_CLOSED", "The question opens the camera", setOf(Suite.VISION, Suite.REGRESSION),
            setup = WorldSetup(cameraOpen = false, visionFixture = "car_front"),
            steps = listOf(say("看看前面是什么", look, state = mapOf(VISION_REQUESTS to "1", CAMERA_OPEN to "true")))),
        Scenario("VISION_FAULT_NO_FABRICATION", "Vision failure: say so, invent nothing", setOf(Suite.VISION, Suite.CHAOS, Suite.REGRESSION),
            setup = WorldSetup(cameraOpen = true, visionFixture = "parking_lot"),
            steps = listOf(
                Step.Inject(Fault.VisionFailsOnce),
                say("前面有什么", look, outcome = Outcome.REPORTED_FAILURE, state = mapOf(VISION_REQUESTS to "1"), forbidden = sceneWords),
            )),
        Scenario("VISION_REAL_API_FIXTURES", "Real Qianfan vision on fixed images", setOf(Suite.VISION_REAL),
            modes = DEVICE_ONLY, setup = WorldSetup(cameraOpen = true, visionFixture = "real:pedestrian"),
            steps = listOf(
                say("前面有什么", look, state = mapOf(VISION_REQUESTS to "1"), mentions = listOf("人", "行人")),
                Step.Camera(true, "real:parking_lot"),
                say("现在前面是什么地方", look, state = mapOf(VISION_REQUESTS to "2"), mentions = listOf("停车", "车")),
                Step.Camera(true, "real:dark_scene"),
                say("前面有什么", look, state = mapOf(VISION_REQUESTS to "3"), mentions = listOf("暗", "黑", "看不清", "不清楚")),
            )),
    )

    private fun unsupported(): List<Scenario> = listOf(
        Scenario("UNSUPPORTED_VOLUME", "No volume tool: refuse honestly", setOf(Suite.REGRESSION, Suite.SMOKE, Suite.AUDIO_E2E),
            steps = listOf(say("音量调大", outcome = Outcome.NO_ACTION, model = ModelBehavior.REFUSE, state = mapOf(HVAC_FAN to "2")))),
        Scenario("UNSUPPORTED_WINDOW", "No window tool: refuse honestly", setOf(Suite.REGRESSION),
            steps = listOf(say("打开车窗", outcome = Outcome.NO_ACTION, model = ModelBehavior.REFUSE))),
        Scenario("UNSUPPORTED_FALSE_CLAIM_CORRECTED", "Model claims an unsupported action; the app corrects it", setOf(Suite.CHAOS, Suite.REGRESSION),
            modes = SIM_ONLY,
            steps = listOf(say("音量调大", outcome = Outcome.NO_ACTION, model = ModelBehavior.FALSE_CLAIM))),
        Scenario("CHAT_HELLO", "Chit-chat calls no tool", setOf(Suite.REGRESSION),
            steps = listOf(say("你好", outcome = Outcome.SUCCESS))),
    )

    // ---- chaos -----------------------------------------------------------------------------

    private fun chaos(): List<Scenario> = buildList {
        val c = setOf(Suite.CHAOS)
        add(Scenario("CHAOS_LATENCY_500", "+500 ms server latency", c, modes = SIM_ONLY,
            steps = listOf(Step.Inject(Fault.ServerLatency(500)), say("空调调到22度", climate("set_temperature", "22"), state = mapOf(HVAC_TEMP to temp(22.0))))))
        add(Scenario("CHAOS_LATENCY_2000", "+2 s server latency", c, modes = SIM_ONLY,
            steps = listOf(Step.Inject(Fault.ServerLatency(2_000)), say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")))))
        add(Scenario("CHAOS_DISCONNECT_BEFORE_TOOL", "Connection drops mid-turn; the next command works", c, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.ServerDisconnectDuringNextTurn(afterToolCall = false)),
                say("打开空调", outcome = Outcome.NO_ACTION, requireReply = false, state = mapOf(HVAC_POWER to "off"), timeoutMs = 20_000),
                Step.Pause(1_500),
                Step.Check(mapOf(SESSION_ALIVE to "true")),
                say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")),
            ),
            purpose = "The lost turn must not execute later, and the session must reconnect by itself."))
        add(Scenario("CHAOS_DISCONNECT_AFTER_TOOL", "Connection drops after the tool ran", c, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.ServerDisconnectDuringNextTurn(afterToolCall = true)),
                say("空调调到26度", climate("set_temperature", "26"), requireReply = false, state = mapOf(HVAC_TEMP to temp(26.0)), timeoutMs = 20_000),
                Step.Pause(1_500),
                say("风量调到4档", climate("set_fan", "4"), state = mapOf(HVAC_FAN to "4", HVAC_TEMP to temp(26.0))),
            ),
            purpose = "No duplicate execution after reconnect; state stays consistent."))
        add(Scenario("CHAOS_CONNECT_REJECTED", "First connect refused; the session retries", c, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.ServerRejectsNextConnect),
                Step.Pause(2_500),
                say("播放音乐", music("play"), state = mapOf(MEDIA_PLAYING to "true")),
            )))
        add(Scenario("CHAOS_SERVER_ERROR_EVENT", "Server error event on a turn", c, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.ServerErrorEventOnNextTurn),
                say("打开空调", outcome = Outcome.NO_ACTION, requireReply = false, state = mapOf(HVAC_POWER to "off"), timeoutMs = 20_000),
                Step.Pause(2_000),
                say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")),
            )))
        add(Scenario("CHAOS_EMPTY_RESPONSE", "Empty model response is retried once", c, modes = SIM_ONLY,
            steps = listOf(say("风量调到3档", climate("set_fan", "3"), model = ModelBehavior.EMPTY_RESPONSE, state = mapOf(HVAC_FAN to "3")))))
        add(Scenario("CHAOS_DUPLICATE_TOOL_EVENT", "The same tool-call event arrives twice", c, modes = SIM_ONLY,
            steps = listOf(Step.Inject(Fault.DuplicateToolResult), say("风量调大", ToolCallSpec("control_climate", mapOf("action" to "adjust_fan")), state = mapOf(HVAC_FAN to "3")))))
        add(Scenario("CHAOS_STALE_DESTINATION", "A slow first search must not overwrite the newer one", c + Suite.NAVIGATION, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.NavigationResolveDelay("澳门大学", 1_500)),
                say("导航到澳门大学", navigate("澳门大学"), awaitSettle = false, timeoutMs = 300),
                say("不，导航到横琴口岸", navigate("横琴口岸"), state = routeList + (NAV_DESTINATION to "横琴口岸")),
                Step.Pause(2_500),
                Step.Check(routeList + (NAV_DESTINATION to "横琴口岸")),
            ),
            purpose = "Delayed A arrives after B: the active destination must remain B."))
        add(Scenario("CHAOS_ROUTE_CALC_FAILURE", "Route calculation fails", c + Suite.NAVIGATION, modes = setOf(TestMode.SIM_LOGIC, TestMode.TEXT_LIVE),
            steps = listOf(
                Step.Inject(Fault.NavigationCalcFailsOnce(12)),
                say("导航到横琴口岸", navigate("横琴口岸"), outcome = Outcome.REPORTED_FAILURE, state = mapOf(NAV_PHASE to "ERROR")),
                say("导航到横琴口岸", navigate("横琴口岸"), state = routeList),
            )))
        add(Scenario("CHAOS_START_FAILURE", "Navigation start fails", c + Suite.NAVIGATION, modes = setOf(TestMode.SIM_LOGIC, TestMode.TEXT_LIVE),
            steps = listOf(
                toRouteList("公司"),
                Step.Inject(Fault.NavigationStartFailsOnce),
                say("开始导航", prefer("recommended"), alternatives = listOf(listOf(choose(1))), outcome = Outcome.REPORTED_FAILURE,
                    state = mapOf(NAV_PHASE to "!NAVIGATING")),
            )))
        add(Scenario("CHAOS_RESOLVE_FAILURE", "Destination search fails", c + Suite.NAVIGATION, modes = setOf(TestMode.SIM_LOGIC, TestMode.TEXT_LIVE),
            steps = listOf(
                Step.Inject(Fault.NavigationResolveFailsOnce),
                say("导航到澳门大学", navigate("澳门大学"), outcome = Outcome.REPORTED_FAILURE, state = mapOf(NAV_PHASE to "ERROR")),
            )))
        add(Scenario("CHAOS_SLOW_VISION_AND_VOICE", "Slow vision result while a new command runs", c + Suite.VISION, modes = SIM_ONLY,
            setup = WorldSetup(cameraOpen = true, visionFixture = "road_clear"),
            steps = listOf(
                Step.Inject(Fault.ToolResultDelay("describe_camera_view", 2_500)),
                say("前面有什么", look, awaitSettle = false, timeoutMs = 400),
                say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")),
                Step.Pause(3_000),
                Step.Check(mapOf(VISION_REQUESTS to "1", HVAC_POWER to "on")),
            )))
        add(Scenario("CHAOS_ROUTE_AND_CLIMATE", "Route calculation in flight while a climate command runs", c + Suite.NAVIGATION, modes = SIM_ONLY,
            steps = listOf(
                Step.Inject(Fault.NavigationResolveDelay("公司", 800)),
                say("导航到公司", navigate("公司"), awaitSettle = false, timeoutMs = 200),
                say("空调调到25度", climate("set_temperature", "25"), alternatives = setTempAlternatives(25), state = mapOf(HVAC_TEMP to temp(25.0))),
                Step.Pause(1_500),
                Step.Check(routeList + (NAV_DESTINATION to "公司")),
            )))
        listOf(100L, 300L, 700L, 2_000L).forEach { at ->
            add(Scenario("CHAOS_BARGE_IN_${at}MS", "Interrupt the assistant $at ms into its reply", c, modes = setOf(TestMode.SIM_LOGIC, TestMode.AUDIO_E2E),
                steps = listOf(
                    Step.BargeIn(
                        primer = "给我介绍一下珠海",
                        afterTtsMs = at,
                        utterance = "停一下",
                        expect = TurnExpectation(outcome = Outcome.NO_ACTION, requireReply = false, tolerated = setOf(READ_CLIMATE)),
                    ),
                    say("打开空调", climate("power_on"), state = mapOf(HVAC_POWER to "on")),
                ),
                purpose = "Playback must stop, nothing stale may execute, the next command must work."))
        }
        add(Scenario("CHAOS_LIFECYCLE_BACKGROUND", "Background and foreground during navigation with the camera open", c, modes = DEVICE_ONLY,
            setup = WorldSetup(cameraOpen = true),
            steps = listOf(
                toRouteList("公司"), startRecommended("公司"),
                Step.Inject(Fault.BackgroundForeground),
                Step.Check(mapOf(NAV_PHASE to "NAVIGATING", NAV_DESTINATION to "公司", CAMERA_OPEN to "true")),
                say("空调调到22度", climate("set_temperature", "22"), alternatives = setTempAlternatives(22), state = mapOf(HVAC_TEMP to temp(22.0))),
            )))
    }

    // ---- long sessions -------------------------------------------------------------------------

    /**
     * The ten-turn drive from the product brief, checked after every turn. [cycles] repeats it with
     * a different temperature each time (20 / 50 / 100 turns).
     */
    fun longSession(cycles: Int): Scenario {
        val steps = mutableListOf<Step>()
        repeat(cycles) { k ->
            val t = 22 + (k % 4)
            steps += say("导航到澳门大学", navigate("澳门大学"), state = destinationList)
            steps += say("第二个", choose(2), state = routeList + (NAV_DESTINATION to "澳门大学图书馆"))
            steps += say("选最快的", prefer("fastest"), alternatives = listOf(listOf(choose(1)), listOf(prefer("recommended"))),
                state = mapOf(NAV_PHASE to "NAVIGATING", NAV_ROUTE to "推荐"))
            steps += say("空调调到${t}度", climate("set_temperature", "$t"), alternatives = setTempAlternatives(t),
                state = mapOf(HVAC_TEMP to temp(t.toDouble()), NAV_PHASE to "NAVIGATING"))
            steps += say("播放音乐", music("play"), state = mapOf(MEDIA_PLAYING to "true"))
            steps += say("暂停音乐", music("stop"), state = mapOf(MEDIA_PLAYING to "false"))
            steps += say("换成横琴口岸", navigate("横琴口岸"), state = routeList + (NAV_DESTINATION to "横琴口岸"))
            steps += say("空调关掉", climate("power_off"), state = mapOf(HVAC_POWER to "off", HVAC_TEMP to temp(t.toDouble())))
            steps += say("前面有什么", look, state = mapOf(VISION_REQUESTS to "${k + 1}"))
            steps += say("结束导航", exitNav, state = mapOf(NAV_PHASE to "!NAVIGATING", NAV_CANDIDATES to "0", NAV_ROUTES to "0"))
        }
        val turns = cycles * 10
        return Scenario(
            "LONG_SESSION_$turns", "$turns-turn drive, state checked after every turn",
            setOf(Suite.LONG_SESSION) + (if (turns == 10) setOf(Suite.SMOKE) else emptySet()),
            setup = WorldSetup(cameraOpen = true, hvacPower = true),
            steps = steps,
            purpose = "Stale context, wrong tool carry-over, duplicates, state-machine corruption.",
        )
    }

    val all: List<Scenario> by lazy {
        val list = vehicle() + navigation() + media() + vision() + unsupported() + chaos() +
            listOf(longSession(1), longSession(2), longSession(5), longSession(10))
        val ids = list.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate scenario ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}" }
        list
    }

    /** RELEASE = everything that runs in [mode] except the 50/100-turn soak runs. */
    fun suite(suite: Suite, mode: TestMode): List<Scenario> {
        val inMode = all.filter { mode in it.modes }
        return when (suite) {
            Suite.RELEASE -> inMode.filter { s ->
                s.suites.any { it != Suite.LONG_SESSION && it != Suite.VISION_REAL } || s.id == "LONG_SESSION_20"
            }
            else -> inMode.filter { suite in it.suites }
        }
    }

    fun byId(id: String): Scenario? = all.firstOrNull { it.id == id }

    /** Every utterance a synthetic-speech run may need, for the audio generator. */
    fun utterances(): Set<String> = all.flatMap { s ->
        s.steps.flatMap { step ->
            when (step) {
                is Step.Say -> listOf(step.utterance)
                is Step.BargeIn -> listOf(step.primer, step.utterance)
                else -> emptyList()
            }
        }
    }.toSortedSet()
}
