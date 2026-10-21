package com.novadrive.app.vehicle

import com.novadrive.vehicle.CabinLimits
import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import com.novadrive.vehicle.WindowId
import org.json.JSONObject

/**
 * Maps the `control_window` tool onto [VehicleControlPort] (SPEC-015). Depends only on the
 * interface. Relative requests use the port's relative call, so the backend's current opening is
 * used; the model never supplies the previous value.
 */
class WindowToolHandler(private val port: VehicleControlPort) {
    suspend fun handle(arguments: Map<String, String>): BodyToolOutcome {
        val action = arguments["action"]
            ?: return rejected("MISSING_ACTION", "action is required")
        val windowArg = arguments["window"] ?: WINDOW_ALL
        val targets = TARGETS[windowArg]
            ?: return rejected("INVALID_VALUE", "unknown window $windowArg")
        val value = arguments["value"]
        var delta: Int? = null
        val result = when (action) {
            ACTION_OPEN -> port.setWindows(targets, CabinLimits.WINDOW_MAX)
            ACTION_CLOSE -> port.setWindows(targets, CabinLimits.WINDOW_MIN)
            ACTION_SET -> {
                val percent = value?.toWholeNumberOrNull()
                    ?: return rejected("INVALID_VALUE", "set needs a whole-number percent")
                port.setWindows(targets, percent)
            }
            ACTION_ADJUST -> {
                val step = if (value == null) CabinLimits.DEFAULT_WINDOW_STEP else value.toWholeNumberOrNull()
                    ?: return rejected("INVALID_VALUE", "adjust value must be a whole number")
                if (step == 0) return rejected("INVALID_VALUE", "adjust value must not be 0")
                delta = step
                port.changeWindows(targets, step)
            }
            ACTION_GET_STATE -> VehicleActionResult.Success(port.getCabinState())
            else -> return rejected("ACTION_NOT_ALLOWED", "unknown action $action")
        }
        return when (result) {
            is VehicleActionResult.Success -> {
                val announce = ActionAnnouncement.window(action, targets, result.state, result.limitReached, delta)
                BodyToolOutcome(
                    ok = true,
                    output = JSONObject()
                        .put("ok", true)
                        .put("tool", TOOL)
                        .put("action", action)
                        .put("window", windowArg)
                        .put("windows", windowsJson(result.state))
                        .put("limit_reached", result.limitReached)
                        .put("announce", announce)
                        .put("instruction", ANNOUNCE_INSTRUCTION)
                        .toString(),
                    chip = chip(result.state),
                    errorCode = null,
                )
            }
            else -> failure(TOOL, action, result)
        }
    }

    private fun rejected(code: String, detail: String): BodyToolOutcome = failureOutcome(TOOL, "invalid", code, detail)

    companion object {
        const val TOOL = "control_window"
        const val ACTION_OPEN = "open"
        const val ACTION_CLOSE = "close"
        const val ACTION_SET = "set"
        const val ACTION_ADJUST = "adjust"
        const val ACTION_GET_STATE = "get_state"
        val ACTIONS = listOf(ACTION_OPEN, ACTION_CLOSE, ACTION_SET, ACTION_ADJUST, ACTION_GET_STATE)

        const val WINDOW_ALL = "all"
        val TARGETS: Map<String, Set<WindowId>> = linkedMapOf(
            WINDOW_ALL to WindowId.entries.toSet(),
            "front" to setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT),
            "rear" to setOf(WindowId.REAR_LEFT, WindowId.REAR_RIGHT),
            "driver" to setOf(WindowId.FRONT_LEFT),
            "passenger" to setOf(WindowId.FRONT_RIGHT),
            "front_left" to setOf(WindowId.FRONT_LEFT),
            "front_right" to setOf(WindowId.FRONT_RIGHT),
            "rear_left" to setOf(WindowId.REAR_LEFT),
            "rear_right" to setOf(WindowId.REAR_RIGHT),
        )
        val WINDOWS: List<String> = TARGETS.keys.toList()

        const val ANNOUNCE_INSTRUCTION = "用 announce 这句话告诉用户做了什么，可以换口气，不要加没做的事"
        const val FAILURE_INSTRUCTION = ClimateToolHandler.FAILURE_INSTRUCTION

        fun windowsJson(state: CabinState): JSONObject = JSONObject().apply {
            WindowId.entries.forEach { put(it.name.lowercase(), state.windows.getValue(it)) }
        }

        /** 「✓ 车窗 50%」 when all agree, 「✓ 车窗 前50% 后0%」 by row, else every window. */
        fun chip(state: CabinState): String {
            val w = state.windows
            val fl = w.getValue(WindowId.FRONT_LEFT)
            val fr = w.getValue(WindowId.FRONT_RIGHT)
            val rl = w.getValue(WindowId.REAR_LEFT)
            val rr = w.getValue(WindowId.REAR_RIGHT)
            return when {
                w.values.toSet().size == 1 -> "✓ 车窗 $fl%"
                fl == fr && rl == rr -> "✓ 车窗 前$fl% 后$rl%"
                else -> "✓ 车窗 左前$fl% 右前$fr% 左后$rl% 右后$rr%"
            }
        }
    }
}

/** Result of a body tool call; [chip] only on success, [errorCode] only on failure. */
data class BodyToolOutcome(val ok: Boolean, val output: String, val chip: String?, val errorCode: String?)

/** Shared by the body handlers: one failure vocabulary, as in [ClimateToolHandler]. */
internal fun failure(tool: String, action: String, result: VehicleActionResult<*>): BodyToolOutcome =
    when (result) {
        is VehicleActionResult.InvalidArgument -> failureOutcome(tool, action, "INVALID_ARGUMENT", result.reason)
        is VehicleActionResult.Unsupported -> failureOutcome(tool, action, "VEHICLE_UNSUPPORTED", result.feature)
        is VehicleActionResult.Unavailable -> failureOutcome(tool, action, "VEHICLE_UNAVAILABLE", result.reason)
        is VehicleActionResult.PermissionDenied -> failureOutcome(tool, action, "VEHICLE_PERMISSION_DENIED", result.reason)
        is VehicleActionResult.Failure -> failureOutcome(tool, action, "VEHICLE_EXECUTION_FAILED", result.reason)
        is VehicleActionResult.Success -> error("success is not a failure")
    }

internal fun failureOutcome(tool: String, action: String, code: String, detail: String): BodyToolOutcome =
    BodyToolOutcome(
        ok = false,
        output = JSONObject()
            .put("ok", false)
            .put("tool", tool)
            .put("action", action)
            .put("error", code)
            .put("detail", detail)
            .put("instruction", ClimateToolHandler.FAILURE_INSTRUCTION)
            .toString(),
        chip = null,
        errorCode = code,
    )

internal fun String.toWholeNumberOrNull(): Int? {
    val parsed = toDoubleOrNull() ?: return null
    if (!parsed.isFinite() || parsed != Math.floor(parsed)) return null
    if (parsed > Int.MAX_VALUE || parsed < Int.MIN_VALUE) return null
    return parsed.toInt()
}
