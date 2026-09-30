package com.novadrive.app.vehicle

import com.novadrive.vehicle.CabinLimits
import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.WindowId

/**
 * The sentence 小诺 says after a body action succeeded (SPEC-015 B5): built from the state read
 * back, never from what the model asked for. Pure; only called for ok=true results, so a failure
 * can never be announced as done.
 */
object ActionAnnouncement {
    private val FRONT = setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT)
    private val REAR = setOf(WindowId.REAR_LEFT, WindowId.REAR_RIGHT)
    private val ALL = WindowId.entries.toSet()

    fun windowName(id: WindowId): String = when (id) {
        WindowId.FRONT_LEFT -> "主驾车窗"
        WindowId.FRONT_RIGHT -> "副驾车窗"
        WindowId.REAR_LEFT -> "左后车窗"
        WindowId.REAR_RIGHT -> "右后车窗"
    }

    private fun groupName(targets: Set<WindowId>): String? = when (targets) {
        ALL -> "车窗都"
        FRONT -> "前排车窗"
        REAR -> "后排车窗"
        else -> if (targets.size == 1) windowName(targets.single()) else null
    }

    /**
     * [delta] is the signed change of a relative request (null for set / open / close / get_state);
     * with [limitReached] it says which end was hit.
     */
    fun window(
        action: String,
        targets: Set<WindowId>,
        state: CabinState,
        limitReached: Boolean,
        delta: Int? = null,
    ): String {
        if (action == WindowToolHandler.ACTION_GET_STATE) return windowState(state)
        val values = targets.map { state.windows.getValue(it) }.toSet()
        val name = groupName(targets)
        // The port reports limitReached when ANY window clamped; only claim the end for the group
        // when every targeted window really reads back at it.
        val atEnd = delta != null && values.size == 1 &&
            values.single() == if (delta > 0) CabinLimits.WINDOW_MAX else CabinLimits.WINDOW_MIN
        if (limitReached && atEnd && name != null) {
            val subject = if (targets == ALL) "车窗" else name
            return if (delta!! > 0) "${subject}已经全开了" else "${subject}已经关到底了"
        }
        if (values.size == 1 && name != null) {
            val value = values.single()
            if (targets == ALL) {
                return when (value) {
                    CabinLimits.WINDOW_MAX -> "车窗都打开了"
                    CabinLimits.WINDOW_MIN -> "车窗都关好了"
                    50 -> "车窗都开了一半"
                    else -> "车窗都开到了$value%"
                }
            }
            return when (value) {
                CabinLimits.WINDOW_MAX -> "${name}全打开了"
                CabinLimits.WINDOW_MIN -> "${name}关好了"
                else -> "${name}开到了$value%"
            }
        }
        return WindowId.entries.filter { it in targets }
            .joinToString("，") { id -> perWindow(id, state.windows.getValue(id), "开到了", "关好了") }
    }

    private fun windowState(state: CabinState): String {
        val values = state.windows.values.toSet()
        if (values.size == 1) {
            return when (val value = values.single()) {
                CabinLimits.WINDOW_MIN -> "车窗现在都关着"
                CabinLimits.WINDOW_MAX -> "车窗现在都全开着"
                else -> "车窗现在都开着$value%"
            }
        }
        return "现在" + WindowId.entries.joinToString("，") { id ->
            perWindow(id, state.windows.getValue(id), "开着", "关着")
        }
    }

    private fun perWindow(id: WindowId, value: Int, openVerb: String, closed: String): String =
        if (value == CabinLimits.WINDOW_MIN) "${windowName(id)}$closed" else "${windowName(id)}$openVerb$value%"

    private fun seatName(seat: SeatId): String = if (seat == SeatId.DRIVER) "座椅" else "副驾座椅"

    /** [delta] is the signed step of adjust_height, null otherwise. */
    fun seat(action: String, seat: SeatId, level: Int, limitReached: Boolean, delta: Int? = null): String {
        val name = seatName(seat)
        return when (action) {
            SeatToolHandler.ACTION_ADJUST_HEIGHT -> {
                val step = delta ?: 0
                when {
                    limitReached && step < 0 -> "${name}已经是最低了"
                    limitReached -> "${name}已经是最高了"
                    step < 0 -> "${name}降低了${steps(-step)}档，现在是${level}档"
                    else -> "${name}升高了${steps(step)}档，现在是${level}档"
                }
            }
            SeatToolHandler.ACTION_SET_HEIGHT -> "${name}调到了${level}档"
            else -> "${name}现在是${level}档"
        }
    }

    private fun steps(n: Int): String = if (n == 1) "一" else n.toString()
}
