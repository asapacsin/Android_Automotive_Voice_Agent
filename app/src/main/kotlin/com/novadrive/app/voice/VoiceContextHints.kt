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
        resolution: ContextResolver.Resolution? = null,
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
            resolution?.let { describeResolution(it)?.let(::add) }
        }
        return if (parts.isEmpty()) null else "当前状态：" + parts.joinToString("")
    }

    private fun describeClimate(climate: DriverContext.Climate): String {
        val power = if (climate.powerOn) "已打开" else "已关闭"
        return "空调$power，当前设定温度 ${ClimateToolHandlerText.temperature(climate.temperatureC)} 度，风量 ${climate.fanLevel} 档；" +
            "相对调节必须用 adjust_temperature / adjust_fan，不要自己推算原来的数值。"
    }

    /**
     * The app has already worked out what a context-dependent sentence refers to. Saying so keeps
     * the decision in deterministic code: the model is told which dimension and which step, not
     * asked to remember a conversation it never saw.
     */
    private fun describeResolution(resolution: ContextResolver.Resolution): String? = when (resolution) {
        is ContextResolver.Resolution.Adjust -> buildString {
            val action = if (resolution.dimension == DriverContext.Dimension.FAN) {
                ClimateToolActions.ADJUST_FAN
            } else {
                ClimateToolActions.ADJUST_TEMPERATURE
            }
            val what = if (resolution.dimension == DriverContext.Dimension.FAN) "风量" else "温度"
            if (resolution.powerOnFirst) {
                append("空调现在是关着的，用户不会有任何感觉：必须先调用 control_climate{action=power_on}，")
                append("确认返回 ok=true 之后，再调用 control_climate{action=$action, value=${plain(resolution.delta)}}。")
            } else {
                append("用户这句话指的是$what：调用 control_climate{action=$action, value=${plain(resolution.delta)}}。")
            }
            if (resolution.atLimit) {
                append("上一次同方向的调节已经到达可调范围的极限；如果这次返回 limit_reached=true，")
                append("必须如实说已经到头了，不要说又调了一档。")
            }
        }
        is ContextResolver.Resolution.Clarify ->
            "无法确定用户指的是" + resolution.options.joinToString("还是") { readable(it) } +
                "：必须只用一句话反问用户，不要调用任何工具，也不要自己选一个。"
        ContextResolver.Resolution.NotContextual -> null
    }

    private fun readable(dimension: DriverContext.Dimension): String =
        if (dimension == DriverContext.Dimension.FAN) "风量" else "温度"

    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** Live state, read when a session (or a reset conversation) is configured. */
    fun current(): String? =
        describe(EmbeddedNavigation.currentOrNull(), CameraVisionGateway.current()?.isOpen == true)

    /** The hint for a given navigation flow and camera state (also used by the simulation). */
    fun describe(navigation: com.novadrive.app.nav.EmbeddedNavigationController?, cameraOpen: Boolean): String? =
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
            val resolution = context?.let {
                ContextResolver.resolve(it.currentRequestText(), it, it.currentEpoch())
            }
            // Deciding to ask is a decision the app owns, so it is recorded here: the driver's
            // answer next turn would otherwise have nothing to attach to.
            if (context != null && resolution is ContextResolver.Resolution.Clarify) {
                context.recordClarification(resolution.options, resolution.delta, context.currentEpoch())
            }
            compose(
                phase = phase,
                cameraOpen = cameraOpen,
                options = options,
                climate = context?.climateState(),
                resolution = resolution,
            )
        }.getOrNull()
}

/** Formatting shared with the climate chip, kept out of the hint so both read the same. */
private object ClimateToolHandlerText {
    fun temperature(celsius: Double): String =
        if (celsius == celsius.toLong().toDouble()) celsius.toLong().toString() else celsius.toString()
}
