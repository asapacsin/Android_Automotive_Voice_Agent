package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONObject

/** The `vision` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object VisionDomain : ToolDomain {
    override val id = "vision"

    override fun specs(): List<ToolSpec> {
        val describeCamera = spec(
            name = "describe_camera_view",
            description = "看摄像头画面回答：「看看前面有什么」「画面里有几个人」「这是什么」（无其他上下文时一律当作问画面），question 填原问题。需几秒，结果返回前什么都不要说（不描述画面，也不说摄像头不能用）。只按返回的 answer 答；ok=false 只转述 message，绝不猜测画面。",
            properties = JSONObject().put(
                "question",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200),
            ),
            required = listOf("question"),
        )
        return listOf(describeCamera)
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            "describe_camera_view" -> when {
                keys != setOf("question") -> "INVALID_FIELDS"
                json.opt("question") !is String -> "INVALID_FIELD_TYPE"
                json.optString("question").isBlank() -> "BLANK_QUESTION"
                json.optString("question").length > 200 -> "QUESTION_TOO_LONG"
                else -> null
            }
            else -> null
        }
    }
}
