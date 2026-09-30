package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.CHOICE_PREFERENCES
import com.novadrive.app.voice.RealtimeToolCatalog.CHOOSE_NAVIGATION_OPTION
import com.novadrive.app.voice.RealtimeToolCatalog.MAX_CHOICE_INDEX
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `navigation` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object NavigationDomain : ToolDomain {
    override val id = "navigation"

    override fun specs(): List<ToolSpec> {
        val navigate = spec(
            name = "navigate_to",
            description = "导航到指定地点。用户说「导航到X」「带我去X」「去X」时调用。调用成功后屏幕会列出候选地点或路线，列表已显示在屏幕上，不要念出列表，按返回的 next 只说一句（找到几个，请说第几个或点选），选择用 choose_navigation_option；选好路线前导航没有开始，不要说已经开始导航。用户换目的地或说「不是这个」「换成X」时，必须用新的目的地再次调用本工具，屏幕上的候选会被替换。Shows destination and route choices on screen; navigation starts only after the driver picks a route, so never claim it has started. Call again with the new destination whenever the driver changes it; the on-screen list is replaced.",
            properties = JSONObject().put(
                "destination",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 120),
            ),
            required = listOf("destination"),
        )
        val chooseNavigationOption = ToolSpec(
            name = CHOOSE_NAVIGATION_OPTION,
            description =
                "在屏幕上的导航候选地点或候选路线中做选择（与点选效果相同）。屏幕显示候选列表时，用户说「第二个」「第一条」→ index；" +
                    "「选最快的」「时间最短」→ preference=fastest；「最短的」「距离最近的路线」→ shortest；「推荐的」→ recommended；" +
                    "「不要收费」「免费的」→ no_toll；路线列表显示时说「开始导航」「好的」「就这条」→ recommended；「红绿灯少的」→ fewest_lights；「最近的那个地点」→ nearest；「就去拱北口岸」「选万达广场那个」→ name。" +
                    "三个参数只填一个。根据返回结果如实回答：destination_selected 时不要逐条念路线，按返回的 next 只说一句并请用户选路线；" +
                    "navigation_started 时说导航已开始；ok=false 时按 error 说明（OUT_OF_RANGE=没有这一项，NO_OPTIONS_ON_SCREEN=现在没有可选的列表，" +
                    "OPTIONS_NOT_READY=还在计算，AMBIGUOUS=有多个匹配请说第几个，OPTIONS_STALE=列表太久了请重新说一遍选项再问第几个，CONFIRM_CANDIDATE=问用户是不是 candidate_position 那一项）。" +
                    "Pick from the on-screen destination or route list, exactly like a tap. Fill exactly one field.",
            parameters =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("index", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_CHOICE_INDEX))
                            .put(
                                "preference",
                                JSONObject().put("type", "string").put("enum", JSONArray(CHOICE_PREFERENCES.toList())),
                            )
                            .put("name", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 40)),
                    )
                    .put("additionalProperties", false),
        )
        val exitNavigationMode = specNoArgs(
            name = "exit_navigation_mode",
            description = "结束或取消导航。用户说「结束导航」「停止导航」「取消导航」「退出导航」「不去了」，或在导航、选择地点/路线时说「算了」「不用了」「返回」时调用；只口头答应而不调用本工具，屏幕上的导航或列表不会有任何变化。" +
                "会真正停止屏幕上的导航，或关闭候选列表；根据返回的 status 如实回答：navigation_stopped=导航已结束，" +
                "navigation_selection_cancelled=已取消选择，no_navigation_active=当前没有导航。" +
                "Ends the on-screen navigation or closes the picker; answer from the returned status.",
        )
        val savePlace = spec(
            name = "save_place",
            description = "记住用户的家或公司地址。用户说「我家在XX」「把XX设为我家」「我公司在XX」时调用：" +
                "slot=home 或 work，address 填用户说的地址或地点名。" +
                "保存后用户再说「回家」「去公司」就能直接导航。" +
                "如果返回 ok=false，请按 error 如实说明，不要谎称已经保存。" +
                "Save the driver's home or work address so 'go home' can navigate later.",
            properties = JSONObject()
                .put(
                    "slot",
                    JSONObject().put("type", "string").put("enum", JSONArray(listOf("home", "work"))),
                )
                .put(
                    "address",
                    JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 120),
                ),
            required = listOf("slot", "address"),
        )
        return listOf(navigate.copy(repeatSensitive = true), chooseNavigationOption, exitNavigationMode.copy(repeatSensitive = true), savePlace.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            "navigate_to" -> when {
                keys != setOf("destination") -> "INVALID_FIELDS"
                json.opt("destination") !is String -> "INVALID_FIELD_TYPE"
                json.optString("destination").trim().isEmpty() -> "BLANK_DESTINATION"
                json.optString("destination").trim().length > 120 -> "DESTINATION_TOO_LONG"
                else -> null
            }
            CHOOSE_NAVIGATION_OPTION -> when {
                keys.size != 1 || !setOf("index", "preference", "name").containsAll(keys) -> "INVALID_FIELDS"
                "index" in keys -> {
                    val index = when (val raw = json.opt("index")) {
                        is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
                        is String -> raw.trim().toIntOrNull()
                        else -> null
                    }
                    if (index == null || index !in 1..MAX_CHOICE_INDEX) "INVALID_INDEX" else null
                }
                "preference" in keys ->
                    if ((json.opt("preference") as? String) !in CHOICE_PREFERENCES) "PREFERENCE_NOT_ALLOWED" else null
                else -> {
                    val name = json.opt("name") as? String
                    if (name == null || name.isBlank() || name.length > 40) "INVALID_NAME" else null
                }
            }
            "exit_navigation_mode" -> when {
                keys.isNotEmpty() -> "INVALID_FIELDS"
                else -> null
            }
            else -> null
        }
    }
}
