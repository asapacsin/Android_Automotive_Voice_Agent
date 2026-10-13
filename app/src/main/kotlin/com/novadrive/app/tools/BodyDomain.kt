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
            description = "控制车窗（模拟）。「打开车窗」open；「关窗」close；「车窗开一半」set,value=50；「开一点主驾车窗」adjust,window=driver,value=20；「副驾关小一点」adjust,window=passenger,value=-20；「车窗开了多少」get_state。未指明窗时 window=all。只按返回的 windows/announce 确认；ok=false 如实说没成功。",
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
            description = "调座椅高度（模拟）。「座椅调高一点」「再调高一点」「座位有点低」adjust_height,value=1；「座位有点高」value=-1；「调到最低」set_height,value=0；「最高」value=10；「座椅多高」get_state。未指明座位时 seat=driver。描述感受时直接调一档，不要反问。只按返回的 seat_height/announce 确认；ok=false 如实说没成功。",
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
                args.optString("action") == "adjust" && keys.contains("value") && args.optDouble("value") == 0.0 -> "INVALID_VALUE"
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
                args.optString("action") == "adjust_height" && args.optDouble("value") == 0.0 -> "INVALID_VALUE"
                else -> null
            }
            else -> null
        }
    }
}
