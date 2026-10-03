package com.novadrive.app.tools

import com.novadrive.app.vehicle.ComfortScenarios
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `comfort` car domain (SPEC-015 FZ-09/FZ-10, ADR-015): fixed multi-step comfort scenarios. */
object ComfortDomain : ToolDomain {
    override val id = "comfort"

    override fun specs(): List<ToolSpec> {
        val runScenario = spec(
            name = "run_scenario",
            description = "执行固定的舒适场景，一次完成几步车内操作。「有蚊子」「有虫子飞进来了」name=mosquito；" +
                "「蚊子出去了」「好了关上吧」（只在刚执行过 mosquito 之后）name=mosquito_done；" +
                "「有点闷」「空气不好」「有异味」name=stuffy；「好困」「有点犯困」name=drowsy。" +
                "用户只描述感受时直接执行，不要反问。按返回的 announce 说做了什么，没做成的也要说。" +
                "Run a fixed comfort scenario; report exactly the returned announce, including what was not done.",
            properties = JSONObject()
                .put("name", JSONObject().put("type", "string").put("enum", JSONArray(ComfortScenarios.NAMES))),
            required = listOf("name"),
        )
        return listOf(runScenario.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val keys = argumentKeys(args)
        return when (name) {
            ComfortScenarios.TOOL -> when {
                keys != setOf("name") -> "INVALID_FIELDS"
                args.opt("name") !is String -> "INVALID_FIELD_TYPE"
                args.optString("name") !in ComfortScenarios.NAMES -> "INVALID_FIELD_VALUE"
                else -> null
            }
            else -> null
        }
    }
}
