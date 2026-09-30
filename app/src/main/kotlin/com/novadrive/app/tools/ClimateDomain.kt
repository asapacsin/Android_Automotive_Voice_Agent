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
            description = "控制车内空调（当前为模拟空调）。「打开空调」action=power_on；「关闭空调」action=power_off；" +
                "「空调调到22度」action=set_temperature,value=22；「温度调高一点」action=adjust_temperature,value=1；" +
                "「温度调低一点」action=adjust_temperature,value=-1；「风量调到3档」action=set_fan,value=3；" +
                "「风量调大」action=adjust_fan,value=1；「风量调小」action=adjust_fan,value=-1；「现在空调多少度」action=get_state。" +
                "相对调节必须用 adjust_*，不要自己推算原来的温度。只根据工具返回的 temperature_c/fan_level/power_on 确认结果；" +
                "ok=false 时必须如实说没有成功。" +
                // SPEC-006 C2: the driver states a feeling, not a command. Measured on device
                // 2026-09-19 - 「有点热」 was answered 「需要我帮你调低空调温度吗？」 with no call, so the
                // cabin did not change. A question costs the driver a whole turn at the wheel.
                "用户只描述感受而没有说出命令时，直接调节，不要反问要不要调：" +
                "「有点热」「太热了」「还是有点热」 action=adjust_temperature,value=-2；" +
                "「有点冷」「太冷了」 action=adjust_temperature,value=2；" +
                "「风太大」「风太吵」 action=adjust_fan,value=-1；「风太小」「不够风」 action=adjust_fan,value=1。" +
                "说「再凉一点」「再暖一点」这类带方向的话则是一次 1 度。" +
                "Control the cabin climate (simulated backend).",
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
