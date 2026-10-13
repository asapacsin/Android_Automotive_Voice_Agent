package com.novadrive.app.tools

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `climate` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object ClimateDomain : ToolDomain {
    override val id = "climate"

    override fun specs(): List<ToolSpec> {
        val controlClimate = spec(
            name = "control_climate",
            // SPEC-006 C2: the driver states a feeling, not a command. Measured on device
            // 2026-09-19 - 「有点热」 was answered 「需要我帮你调低空调温度吗？」 with no call, so the
            // cabin did not change. A question costs the driver a whole turn at the wheel.
            description = "控制空调（模拟）。「打开/关闭空调」power_on/power_off；「调到22度」set_temperature,value=22；「温度调高/低一点」「再暖/凉一点」adjust_temperature,value=±1；「风量调到3档」set_fan,value=3；「风量调大/小」adjust_fan,value=±1；「现在多少度」get_state。相对调节用 adjust_*，不要自己推算温度。描述感受时直接调，不要反问：「有点热」「还是有点热」adjust_temperature,value=-2；「有点冷」value=2；「风太大」adjust_fan,value=-1；「风太小」value=1。「有点闷」不是调风量，用 run_scenario name=stuffy。只按返回的 temperature_c/fan_level/power_on 确认；ok=false 如实说没成功。",
            properties = JSONObject()
                .put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(ClimateToolHandler.ACTIONS)),
                )
                .put(
                    "value",
                    JSONObject()
                        .put("type", "number")
                        .put("description", "set_temperature: 16-32 摄氏度；set_fan: 0-7 档；adjust_*: 变化量，默认 1"),
                ),
            required = listOf("action"),
        )
        return listOf(controlClimate.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            "control_climate" -> when {
                !keys.contains("action") -> "INVALID_FIELDS"
                !setOf("action", "value").containsAll(keys) -> "INVALID_FIELDS"
                json.opt("action") !is String -> "INVALID_FIELD_TYPE"
                json.optString("action") !in ClimateToolHandler.ACTIONS -> "ACTION_NOT_ALLOWED"
                keys.contains("value") && json.opt("value") !is Number -> "INVALID_FIELD_TYPE"
                json.optString("action") in setOf("set_temperature", "set_fan") && !keys.contains("value") -> "MISSING_VALUE"
                else -> null
            }
            else -> null
        }
    }
}
