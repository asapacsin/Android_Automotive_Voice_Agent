package com.novadrive.app.tools

import com.novadrive.app.vehicle.SeatToolHandler
import com.novadrive.app.vehicle.WindowToolHandler
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `body` car domain (SPEC-015 B1, ADR-015): windows and seat height over the vehicle port. */
object BodyDomain : ToolDomain {
    override val id = "body"

    override fun specs(): List<ToolSpec> {
        val controlWindow = spec(
            name = "control_window",
            description = "控制车窗（当前为模拟车窗）。「打开车窗」action=open；「关窗」「把车窗关上」action=close；" +
                "「把车窗打开一半」action=set,value=50；「开一点主驾车窗」action=adjust,window=driver,value=20；" +
                "「副驾车窗关小一点」action=adjust,window=passenger,value=-20；「车窗开了多少」action=get_state。" +
                "不说哪个窗时 window=all。只根据返回的 windows 和 announce 确认结果；ok=false 时必须如实说没有成功。" +
                "Control the windows (simulated backend); confirm only from the returned windows and announce.",
            properties = JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(WindowToolHandler.ACTIONS)))
                .put("window", JSONObject().put("type", "string").put("enum", JSONArray(WindowToolHandler.WINDOWS)))
                .put(
                    "value",
                    JSONObject()
                        .put("type", "number")
                        .put("description", "set: 0-100 开度百分比；adjust: 变化量（正数开大，负数关小），默认 20"),
                ),
            required = listOf("action"),
        )
        val controlSeat = spec(
            name = "control_seat",
            description = "调节座椅高度（当前为模拟座椅）。「座位有点高」「座椅太高了」action=adjust_height,value=-1；" +
                "「座位有点低」「座椅太低了」action=adjust_height,value=1；「座椅调到最低」action=set_height,value=0；" +
                "「座椅调到最高」value=10；「座椅现在多高」action=get_state。不说哪个座位时 seat=driver。" +
                "用户只描述感受时直接调节一档，不要反问。只根据返回的 seat_height 和 announce 确认结果；ok=false 时如实说没有成功。" +
                "Adjust the seat height (simulated backend); confirm only from the returned seat_height and announce.",
            properties = JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(SeatToolHandler.ACTIONS)))
                .put("seat", JSONObject().put("type", "string").put("enum", JSONArray(SeatToolHandler.SEATS)))
                .put(
                    "value",
                    JSONObject()
                        .put("type", "number")
                        .put("description", "set_height: 0-10 档；adjust_height: 变化档数（负数降低，正数升高），必填"),
                ),
            required = listOf("action"),
        )
        return listOf(controlWindow.copy(repeatSensitive = true), controlSeat.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val keys = argumentKeys(args)
        return when (name) {
            "control_window" -> when {
                !keys.contains("action") -> "INVALID_FIELDS"
                !setOf("action", "window", "value").containsAll(keys) -> "INVALID_FIELDS"
                args.opt("action") !is String -> "INVALID_FIELD_TYPE"
                args.optString("action") !in WindowToolHandler.ACTIONS -> "ACTION_NOT_ALLOWED"
                keys.contains("window") && args.opt("window") !is String -> "INVALID_FIELD_TYPE"
                keys.contains("window") && args.optString("window") !in WindowToolHandler.WINDOWS -> "INVALID_FIELD_VALUE"
                keys.contains("value") && args.opt("value") !is Number -> "INVALID_FIELD_TYPE"
                args.optString("action") == "set" && !keys.contains("value") -> "MISSING_VALUE"
                else -> null
            }
            "control_seat" -> when {
                !keys.contains("action") -> "INVALID_FIELDS"
                !setOf("action", "seat", "value").containsAll(keys) -> "INVALID_FIELDS"
                args.opt("action") !is String -> "INVALID_FIELD_TYPE"
                args.optString("action") !in SeatToolHandler.ACTIONS -> "ACTION_NOT_ALLOWED"
                keys.contains("seat") && args.opt("seat") !is String -> "INVALID_FIELD_TYPE"
                keys.contains("seat") && args.optString("seat") !in SeatToolHandler.SEATS -> "INVALID_FIELD_VALUE"
                keys.contains("value") && args.opt("value") !is Number -> "INVALID_FIELD_TYPE"
                args.optString("action") in setOf("set_height", "adjust_height") && !keys.contains("value") -> "MISSING_VALUE"
                else -> null
            }
            else -> null
        }
    }
}
