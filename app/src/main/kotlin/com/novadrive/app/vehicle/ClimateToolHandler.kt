package com.novadrive.app.vehicle

import com.novadrive.vehicle.ClimateLimits
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import org.json.JSONObject

/**
 * Maps the `control_climate` tool onto [VehicleControlPort]. Depends only on the interface: it
 * cannot tell (and must not care) whether the backend is simulated, AAOS, CAN or an OEM SDK.
 *
 * Relative commands (「调高一点」) are sent as relative calls, so the backend's *current* state is
 * used. The model is never trusted to know or invent the previous value.
 */
class ClimateToolHandler(private val port: VehicleControlPort) {
    data class Outcome(val ok: Boolean, val output: String, val chip: String?, val errorCode: String?)

    suspend fun handle(arguments: Map<String, String>): Outcome {
        val action = arguments["action"]
            ?: return rejected("MISSING_ACTION", "action is required")
        val value = arguments["value"]
        val result = when (action) {
            ACTION_POWER_ON -> port.setHvacPower(true)
            ACTION_POWER_OFF -> port.setHvacPower(false)
            ACTION_SET_TEMPERATURE -> {
                val celsius = value?.toDoubleOrNull()
                    ?: return rejected("INVALID_VALUE", "set_temperature needs a numeric value")
                port.setCabinTemperature(celsius)
            }
            ACTION_ADJUST_TEMPERATURE -> {
                val delta = if (value == null) ClimateLimits.DEFAULT_TEMPERATURE_STEP_C else value.toDoubleOrNull()
                    ?: return rejected("INVALID_VALUE", "adjust_temperature value must be numeric")
                port.changeCabinTemperature(delta)
            }
            ACTION_SET_FAN -> {
                val level = value?.toWholeNumberOrNull()
                    ?: return rejected("INVALID_VALUE", "set_fan needs a whole-number value")
                port.setFanLevel(level)
            }
            ACTION_ADJUST_FAN -> {
                val delta = if (value == null) ClimateLimits.DEFAULT_FAN_STEP else value.toWholeNumberOrNull()
                    ?: return rejected("INVALID_VALUE", "adjust_fan value must be a whole number")
                port.changeFanLevel(delta)
            }
            ACTION_GET_STATE -> VehicleActionResult.Success(port.getClimateState())
            else -> return rejected("ACTION_NOT_ALLOWED", "unknown action $action")
        }
        return render(action, result)
    }

    private fun render(action: String, result: VehicleActionResult): Outcome =
        when (result) {
            is VehicleActionResult.Success -> Outcome(
                ok = true,
                output = stateJson(JSONObject().put("ok", true).put("tool", TOOL).put("action", action), result.state)
                    .put("limit_reached", result.limitReached)
                    .toString(),
                chip = chip(result.state),
                errorCode = null,
            )
            is VehicleActionResult.InvalidArgument -> failure(action, "INVALID_ARGUMENT", result.reason)
            is VehicleActionResult.Unsupported -> failure(action, "VEHICLE_UNSUPPORTED", result.feature)
            is VehicleActionResult.Unavailable -> failure(action, "VEHICLE_UNAVAILABLE", result.reason)
            is VehicleActionResult.PermissionDenied -> failure(action, "VEHICLE_PERMISSION_DENIED", result.reason)
            is VehicleActionResult.Failure -> failure(action, "VEHICLE_EXECUTION_FAILED", result.reason)
        }

    private fun failure(action: String, code: String, detail: String): Outcome =
        Outcome(
            ok = false,
            output = JSONObject()
                .put("ok", false)
                .put("tool", TOOL)
                .put("action", action)
                .put("error", code)
                .put("detail", detail)
                .put("instruction", FAILURE_INSTRUCTION)
                .toString(),
            chip = null,
            errorCode = code,
        )

    private fun rejected(code: String, detail: String): Outcome = failure("invalid", code, detail)

    private fun stateJson(json: JSONObject, state: ClimateState): JSONObject =
        json.put("power_on", state.powerOn)
            .put("temperature_c", state.targetTemperatureCelsius)
            .put("fan_level", state.fanLevel)
            .put("temperature_range_c", "${ClimateLimits.MIN_TEMPERATURE_C}-${ClimateLimits.MAX_TEMPERATURE_C}")
            .put("fan_range", "${ClimateLimits.MIN_FAN_LEVEL}-${ClimateLimits.MAX_FAN_LEVEL}")

    companion object {
        const val TOOL = "control_climate"
        const val ACTION_POWER_ON = "power_on"
        const val ACTION_POWER_OFF = "power_off"
        const val ACTION_SET_TEMPERATURE = "set_temperature"
        const val ACTION_ADJUST_TEMPERATURE = "adjust_temperature"
        const val ACTION_SET_FAN = "set_fan"
        const val ACTION_ADJUST_FAN = "adjust_fan"
        const val ACTION_GET_STATE = "get_state"
        val ACTIONS = listOf(
            ACTION_POWER_ON, ACTION_POWER_OFF, ACTION_SET_TEMPERATURE, ACTION_ADJUST_TEMPERATURE,
            ACTION_SET_FAN, ACTION_ADJUST_FAN, ACTION_GET_STATE,
        )
        const val FAILURE_INSTRUCTION = "操作没有成功。如实告诉用户没有完成，不要说已经完成。"

        fun chip(state: ClimateState): String =
            "✓ 空调${if (state.powerOn) "开" else "关"} · ${formatTemperature(state.targetTemperatureCelsius)}°C · 风${state.fanLevel}"

        fun formatTemperature(celsius: Double): String =
            if (celsius == celsius.toLong().toDouble()) celsius.toLong().toString() else celsius.toString()

        private fun String.toWholeNumberOrNull(): Int? {
            val parsed = toDoubleOrNull() ?: return null
            if (!parsed.isFinite() || parsed != Math.floor(parsed)) return null
            if (parsed > Int.MAX_VALUE || parsed < Int.MIN_VALUE) return null
            return parsed.toInt()
        }
    }
}
