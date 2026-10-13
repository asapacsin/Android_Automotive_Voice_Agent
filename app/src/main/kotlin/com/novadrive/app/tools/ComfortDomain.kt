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
            description = "执行固定舒适场景。「有蚊子」name=mosquito；「蚊子还没走」再调用 mosquito（车窗已开时改为调大风量）；刚执行 mosquito 后「蚊子出去了，关上吧」name=mosquito_done；「有点闷」「有异味」stuffy；「好困」drowsy。描述感受时直接执行，不要反问。按返回的 announce 说做了什么，没做成的也要说。",
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
