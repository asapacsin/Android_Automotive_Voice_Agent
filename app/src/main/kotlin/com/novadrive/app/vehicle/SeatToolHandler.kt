package com.novadrive.app.vehicle

import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import org.json.JSONObject

/**
 * Maps the `control_seat` tool onto [VehicleControlPort] (SPEC-015). A relative request
 * (「座位有点高」) is a relative port call from the current height; its direction must be stated,
 * so adjust_height without a value is rejected rather than guessed.
 */
class SeatToolHandler(private val port: VehicleControlPort) {
    suspend fun handle(arguments: Map<String, String>): BodyToolOutcome {
        val action = arguments["action"]
            ?: return rejected("MISSING_ACTION", "action is required")
        val seatArg = arguments["seat"] ?: SEAT_DRIVER
        val seat = SEATS_BY_NAME[seatArg]
            ?: return rejected("INVALID_VALUE", "unknown seat $seatArg")
        val value = arguments["value"]
        var delta: Int? = null
        val result = when (action) {
            ACTION_SET_HEIGHT -> {
                val level = value?.toWholeNumberOrNull()
                    ?: return rejected("INVALID_VALUE", "set_height needs a whole-number level")
                port.setSeatHeight(seat, level)
            }
            ACTION_ADJUST_HEIGHT -> {
                val step = value?.toWholeNumberOrNull()
                    ?: return rejected("MISSING_VALUE", "adjust_height needs a signed whole-number step")
                delta = step
                port.changeSeatHeight(seat, step)
            }
            ACTION_GET_STATE -> VehicleActionResult.Success(port.getCabinState())
            else -> return rejected("ACTION_NOT_ALLOWED", "unknown action $action")
        }
        return when (result) {
            is VehicleActionResult.Success -> {
                val level = result.state.seatHeights.getValue(seat)
                BodyToolOutcome(
                    ok = true,
                    output = JSONObject()
                        .put("ok", true)
                        .put("tool", TOOL)
                        .put("action", action)
                        .put("seat", seatArg)
                        .put("seat_height", level)
                        .put("limit_reached", result.limitReached)
                        .put("announce", ActionAnnouncement.seat(action, seat, level, result.limitReached, delta))
                        .put("instruction", WindowToolHandler.ANNOUNCE_INSTRUCTION)
                        .toString(),
                    chip = chip(seat, level),
                    errorCode = null,
                )
            }
            else -> failure(TOOL, action, result)
        }
    }

    private fun rejected(code: String, detail: String): BodyToolOutcome = failureOutcome(TOOL, "invalid", code, detail)

    companion object {
        const val TOOL = "control_seat"
        const val ACTION_ADJUST_HEIGHT = "adjust_height"
        const val ACTION_SET_HEIGHT = "set_height"
        const val ACTION_GET_STATE = "get_state"
        val ACTIONS = listOf(ACTION_ADJUST_HEIGHT, ACTION_SET_HEIGHT, ACTION_GET_STATE)

        const val SEAT_DRIVER = "driver"
        val SEATS_BY_NAME: Map<String, SeatId> = linkedMapOf(SEAT_DRIVER to SeatId.DRIVER, "passenger" to SeatId.PASSENGER)
        val SEATS: List<String> = SEATS_BY_NAME.keys.toList()

        fun chip(seat: SeatId, level: Int): String =
            if (seat == SeatId.DRIVER) "✓ 座椅 ${level}档" else "✓ 副驾座椅 ${level}档"
    }
}
