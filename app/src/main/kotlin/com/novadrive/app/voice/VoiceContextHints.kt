package com.novadrive.app.voice

import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.NavigationChoiceResolver
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.vision.CameraVisionGateway

/**
 * What is on screen and what has already happened, told to every new Baidu conversation.
 *
 * The conversation is reset after each tool turn (see [ConversationResetPolicy]), so the model
 * no longer remembers that it just opened a destination list or just lowered the temperature.
 * Measured on device 2026-09-17: a bare 「算了」 with the list open got 「没听清」. A one-line hint
 * restores that context.
 *
 * This is the **single owner of cross-turn context** for the conversation
 * ([SPEC-006](../../../../../../../SPECS/SPEC-006-complex-voice-commands.md)). It does not keep
 * state of its own: navigation state is read live from the navigation owner and the driver's own
 * history from [DriverContext], which is built from authoritative tool results. A hint is advice
 * for the next turn — it never asserts that anything happened
 * ([I-1](../../../../../../../docs/INVARIANTS.md)).
 */
object VoiceContextHints {
    /** Pure composition, unit-tested. */
    fun compose(
        phase: NavigationPhase?,
        cameraOpen: Boolean,
        options: String? = null,
        climate: DriverContext.Climate? = null,
        referents: List<DriverContext.Dimension> = emptyList(),
        pendingClarification: List<DriverContext.Dimension>? = null,
        screenControls: List<String> = emptyList(),
    ): String? {
        val listed = options?.takeIf { it.isNotBlank() }?.let { "（已显示，不要念出：$it）" }.orEmpty()
        val parts = buildList {
            when (phase) {
                NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                    add(
                        "屏幕上正在显示导航目的地候选列表$listed；用户说「第几个」或地点名称时调用 choose_navigation_option；" +
                            "说「换个近一点的」「这个太远了」时调用 choose_navigation_option 并用 preference=nearest；" +
                            "说「算了」「不用了」「不去了」「取消」「返回」时必须调用 exit_navigation_mode（只口头答应不会关闭列表）。",
                    )
                NavigationPhase.AWAITING_ROUTE_SELECTION ->
                    add(
                        "屏幕上正在显示路线选择列表$listed；导航还没有开始；用户说「第几条」「最快的」「最短的」「免费的」时调用 choose_navigation_option，" +
                            "说「换条近一点的」「短一点的」时用 preference=shortest（nearest 只能用于地点，不能用于路线），" +
                            "说「开始导航」「好的」「就这条」时用 preference=recommended；说「算了」「不用了」「不去了」「取消」「返回」时必须调用 exit_navigation_mode（只口头答应不会关闭列表）。",
                    )
                NavigationPhase.NAVIGATING ->
                    add(
                        "当前正在导航；用户说「结束导航」「算了」「不去了」时调用 exit_navigation_mode；" +
                            "用户说「换个近一点的」「这个太远了」时屏幕上没有候选列表，必须先用一句话问清楚要改去哪里，不要自己猜目的地，也不要直接退出导航。",
                    )
                else -> Unit
            }
            if (cameraOpen) add("摄像头画面已打开；「这是什么」「前面有什么」等问题调用 describe_camera_view。")
            climate?.let { add(describeClimate(it)) }
            describeReferents(referents)?.let(::add)
            pendingClarification?.let { add(describePending(it)) }
            // SPEC-010 B1/A6: the model knows the bar's names too, in case the local match missed.
            if (screenControls.isNotEmpty()) {
                add("屏幕底部按钮可以直接说名字操作：${screenControls.joinToString("、")}。")
            }
        }
        return if (parts.isEmpty()) null else "当前状态：" + parts.joinToString("")
    }

    private fun describeClimate(climate: DriverContext.Climate): String {
        val power = if (climate.powerOn) "已打开" else "已关闭"
        return "空调$power，当前设定温度 ${ClimateToolHandlerText.temperature(climate.temperatureC)} 度，风量 ${climate.fanLevel} 档；" +
            "相对调节必须用 adjust_temperature / adjust_fan，不要自己推算原来的数值。"
    }

    /**
     * What a sentence with no stated object would refer to **next**.
     *
     * This is state, not a decision about something already said. The hint is composed when a
     * conversation is created — which is *before* the next utterance arrives — so anything phrased
     * as "this sentence means X" would be describing the previous turn.
     *
     * The rule stated here is the same one `ContextResolver` enforces at the dispatcher. Saying it
     * to the model is advice; the dispatcher is what makes it hold ([I-11](../../../../../../../docs/INVARIANTS.md)).
     */
    private fun describeReferents(referents: List<DriverContext.Dimension>): String? = when (referents.size) {
        // Nothing adjusted yet: say nothing. A line on every fresh conversation explaining what
        // would happen if the driver said something they have not said is noise on every turn, and
        // the no-referent case is refused at the dispatcher — which is where it actually holds.
        0 -> null
        1 -> {
            val what = readable(referents.first())
            "刚才调整的是$what；用户接下来说「再高一点」「再低一点」「再大一点」这类没有说明对象的话，" +
                "指的就是$what，用 adjust_${referents.first().wire} 调节。"
        }
        else ->
            "刚才温度和风量都调过；如果用户说「再低一点」这类没有说明对象的话，" +
                "必须用一句话反问是温度还是风量，不要自己选一个。"
    }

    private fun describePending(options: List<DriverContext.Dimension>): String =
        "你刚才已经问过用户是" + options.joinToString("还是") { readable(it) } +
            "；如果用户这句话回答的是其中一项，就按上一次的方向调节那一项，不要再问一遍。"

    private fun readable(dimension: DriverContext.Dimension): String =
        if (dimension == DriverContext.Dimension.FAN) "风量" else "温度"

    /** Live state, read when a session (or a reset conversation) is configured. */
    fun current(): String? =
        describe(EmbeddedNavigation.currentOrNull(), CameraVisionGateway.current()?.isOpen == true)

    /**
     * Whether the screen is waiting for the driver's answer (a list, a question, the camera). The
     * bottom bar is always on screen, so its names must not count, or every turn would look like
     * an answer (SPEC-010 A6).
     */
    fun awaitingAnswer(): Boolean =
        describe(EmbeddedNavigation.currentOrNull(), CameraVisionGateway.current()?.isOpen == true, withControls = false) != null

    /** The hint for a given navigation flow and camera state (also used by the simulation). */
    fun describe(
        navigation: com.novadrive.app.nav.EmbeddedNavigationController?,
        cameraOpen: Boolean,
        withControls: Boolean = true,
    ): String? =
        runCatching {
            val phase = navigation?.state()?.value
            val options = when (phase) {
                NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                    NavigationChoiceResolver.describeDestinations(navigation.destinationCandidates.value)
                NavigationPhase.AWAITING_ROUTE_SELECTION ->
                    NavigationChoiceResolver.describeRoutes(navigation.routeCandidates.value)
                else -> null
            }
            val context = DriverContext.currentOrNull()
            val referents = context?.validReferents().orEmpty().map { it.dimension }
            val pending = context?.pendingClarification(context.currentEpoch() + 1)?.options
            if (context != null) {
                com.novadrive.app.DebugVoiceLog.log(
                    "ctx_hint epoch=${context.currentEpoch()} " +
                        "referents=${referents.joinToString("+") { it.wire }.ifEmpty { "none" }} " +
                        "pending=${pending?.size ?: 0}",
                )
            }
            compose(
                phase = phase,
                cameraOpen = cameraOpen,
                options = options,
                climate = context?.climateState(),
                referents = referents,
                pendingClarification = pending,
                screenControls = if (!withControls) {
                    emptyList()
                } else {
                    com.novadrive.app.ui.ScreenAffordances.shared.current.value
                        .filter { it.position == null }
                        .mapNotNull { it.names.firstOrNull() }
                },
            )
        }.getOrNull()
}

/** Formatting shared with the climate chip, kept out of the hint so both read the same. */
private object ClimateToolHandlerText {
    fun temperature(celsius: Double): String =
        if (celsius == celsius.toLong().toDouble()) celsius.toLong().toString() else celsius.toString()
}
