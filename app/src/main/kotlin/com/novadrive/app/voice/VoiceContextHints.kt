package com.novadrive.app.voice

import com.novadrive.app.nav.EmbeddedNavigation
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
    fun compose(phase: NavigationPhase?, cameraOpen: Boolean): String? {
        val parts = buildList {
            when (phase) {
                NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                    add("屏幕上正在显示导航目的地候选列表；用户说「算了」「不去了」「取消」时调用 exit_navigation_mode。")
                NavigationPhase.AWAITING_ROUTE_SELECTION ->
                    add("屏幕上正在显示路线选择列表；用户说「算了」「不去了」「取消」时调用 exit_navigation_mode。")
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
            compose(
                phase = EmbeddedNavigation.currentOrNull()?.state()?.value,
                cameraOpen = CameraVisionGateway.current()?.isOpen == true,
            )
        }.getOrNull()
}
