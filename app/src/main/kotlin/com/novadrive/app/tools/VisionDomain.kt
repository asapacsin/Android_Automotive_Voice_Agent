package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONObject

/** The `vision` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object VisionDomain : ToolDomain {
    override val id = "vision"

    override fun specs(): List<ToolSpec> {
        val describeCamera = spec(
            name = "describe_camera_view",
            description = "看摄像头画面并回答问题。用户说「看看前面有什么」「摄像头里是什么」「画面里有几个人」「这是什么东西」「这是什么」「这个是什么」「看一下这个」等询问眼前或镜头画面的问题时调用（没有其他上下文时，「这是什么」一律当作问镜头画面），" +
                "question 填用户的原问题。会自动打开摄像头并把当前画面发给视觉模型，需要几秒钟：结果返回之前什么都不要说，既不要描述画面，也不要说摄像头不能用，结果会自动送来。" +
                "只根据返回的 answer 回答；ok=false 时只转述 message，绝对不要猜测画面内容。" +
                "Look at the camera image and answer the driver's question about it.",
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
