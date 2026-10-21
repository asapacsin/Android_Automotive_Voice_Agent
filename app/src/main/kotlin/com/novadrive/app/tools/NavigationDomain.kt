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
            description = "导航到地点：「导航去X」「带我去X」。屏幕会列出候选，不要念列表，按返回的 next 只说一句，选择用 choose_navigation_option；选好路线前导航未开始，不要说已开始。换目的地（「不是这个」「换成X」）时用新目的地再调用。没有路线时找附近的地点（「附近有加油站吗」「最近的充电站」）也用它：destination=附近的加油站。",
            properties = JSONObject().put(
                "destination",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 120),
            ),
            required = listOf("destination"),
        )
        val chooseNavigationOption = ToolSpec(
            name = CHOOSE_NAVIGATION_OPTION,
            description =
                "选屏幕上的候选地点或路线（等同点选），三个参数只填一个。「第一个」「第二条」→index；「最快的」→preference=fastest；「最短的」→shortest；「推荐的」→recommended；「不要收费」→no_toll；路线列表时「开始导航」「就这条」→recommended；「红绿灯少的」→fewest_lights；「最近的那个」→nearest；「选万达广场那个」→name。按结果答：destination_selected 不念路线，按 next 说一句请选路线；navigation_started 说导航已开始；ok=false 按 error：OUT_OF_RANGE=没有这一项，NO_OPTIONS_ON_SCREEN=没有可选列表，OPTIONS_NOT_READY=还在计算，AMBIGUOUS=多个匹配请说第几个，OPTIONS_STALE=列表过期请重新说，CONFIRM_CANDIDATE=问是不是 candidate_position 那项。",
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
            description = "真正停止屏幕导航或关闭候选列表：「结束导航」「取消导航」「退出导航」；候选列表显示时「算了」「不用了」「不去了」「返回」关闭列表。导航中只说「算了」「不去了」不会结束导航。只口头答应不调用则屏幕不变。按 status 答：navigation_stopped=已结束，navigation_selection_cancelled=已取消选择，no_navigation_active=当前没有导航。",
        )
        val savePlace = spec(
            name = "save_place",
            description = "记住家或公司：「我家在XX」「我公司在XX」→slot=home/work，address 填地址。之后「回家」「去公司」可直接导航。ok=false 按 error 如实说，不要谎称已保存。",
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
