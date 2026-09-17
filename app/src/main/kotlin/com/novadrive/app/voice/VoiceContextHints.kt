package com.novadrive.app.voice

import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.NavigationChoiceResolver
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.vision.CameraVisionGateway

/**
 * What is on screen right now, told to every new Baidu conversation.
 *
 * The conversation is reset after each tool turn (see [ConversationResetPolicy]), so the model
 * no longer remembers that it just opened a destination list. Measured on device 2026-09-17:
 * a bare 「算了」 with the list open got 「没听清」. A one-line hint restores that context.
 */
object VoiceContextHints {
    /** Pure composition, unit-tested. */
    fun compose(phase: NavigationPhase?, cameraOpen: Boolean, options: String? = null): String? {
        val listed = options?.takeIf { it.isNotBlank() }?.let { "（已显示，不要念出：$it）" }.orEmpty()
        val parts = buildList {
            when (phase) {
                NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                    add("屏幕上正在显示导航目的地候选列表$listed；用户说「第几个」或地点名称时调用 choose_navigation_option；说「算了」「不用了」「不去了」「取消」「返回」时必须调用 exit_navigation_mode（只口头答应不会关闭列表）。")
                NavigationPhase.AWAITING_ROUTE_SELECTION ->
                    add("屏幕上正在显示路线选择列表$listed；导航还没有开始；用户说「第几条」「最快的」「最短的」「免费的」时调用 choose_navigation_option，说「开始导航」「好的」「就这条」时用 preference=recommended；说「算了」「不用了」「不去了」「取消」「返回」时必须调用 exit_navigation_mode（只口头答应不会关闭列表）。")
                NavigationPhase.NAVIGATING ->
                    add("当前正在导航；用户说「结束导航」「算了」「不去了」时调用 exit_navigation_mode。")
                else -> Unit
            }
            if (cameraOpen) add("摄像头画面已打开；「这是什么」「前面有什么」等问题调用 describe_camera_view。")
        }
        return if (parts.isEmpty()) null else "当前状态：" + parts.joinToString("")
    }

    /** Live state, read when a session (or a reset conversation) is configured. */
    fun current(): String? =
        runCatching {
            val navigation = EmbeddedNavigation.currentOrNull()
            val phase = navigation?.state()?.value
            val options = when (phase) {
                NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                    NavigationChoiceResolver.describeDestinations(navigation.destinationCandidates.value)
                NavigationPhase.AWAITING_ROUTE_SELECTION ->
                    NavigationChoiceResolver.describeRoutes(navigation.routeCandidates.value)
                else -> null
            }
            compose(
                phase = phase,
                cameraOpen = CameraVisionGateway.current()?.isOpen == true,
                options = options,
            )
        }.getOrNull()
}
