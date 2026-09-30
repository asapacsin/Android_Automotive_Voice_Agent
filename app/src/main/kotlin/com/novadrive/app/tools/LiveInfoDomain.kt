package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ALONG_ROUTE_CATEGORIES
import com.novadrive.app.voice.RealtimeToolCatalog.LIVE_INFO_KINDS
import com.novadrive.app.voice.RealtimeToolCatalog.QUERY_LIVE_INFO
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `live_info` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object LiveInfoDomain : ToolDomain {
    override val id = "live_info"

    override fun specs(): List<ToolSpec> {
        // SPEC-011 B5: one tool, example phrasings per kind, in the style of the climate table.
        val queryLiveInfo = spec(
            name = QUERY_LIVE_INFO,
            description = "查询高德的实时信息，只能根据返回结果回答，绝对不要自己编天气、路况或地点信息。" +
                "kind=weather：「今天天气怎么样」「外面冷不冷」→ where=here；「目的地天气怎么样」「到那边会下雨吗」→ where=destination；" +
                "「珠海天气怎么样」→ where=珠海；问「明天」时加 day=tomorrow。" +
                "kind=route_traffic（正在导航时）：「前面堵不堵」「路上堵车吗」「路况怎么样」。" +
                "kind=along_route（有路线时）：「路上有加油站吗」→ category=fuel；「附近有充电桩吗」「沿途充电站」→ charging；" +
                "「下一个服务区」→ service_area；「路上有厕所吗」→ toilet；结果会显示在屏幕上，用户说第几个再用 choose_navigation_option。" +
                "kind=place_details：「目的地几点关门」「那个地方电话多少」→ target=destination；「第二个几点开门」→ target=2。" +
                "新闻、股票、油价、汇率没有数据来源，不要调用，如实说无法回答。" +
                "ok=false 时按 next 如实说查不到的原因，不要猜。" +
                "Live Amap lookups: weather, traffic on the route, POIs along it, place details. Answer only from the result.",
            properties = JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(LIVE_INFO_KINDS)))
                .put(
                    "where",
                    JSONObject().put("type", "string").put("maxLength", 20)
                        .put("description", "weather：here=当前位置，destination=导航目的地，或城市名"),
                )
                .put("day", JSONObject().put("type", "string").put("enum", JSONArray(listOf("today", "tomorrow"))))
                .put(
                    "category",
                    JSONObject().put("type", "string").put("enum", JSONArray(ALONG_ROUTE_CATEGORIES)),
                )
                .put(
                    "target",
                    JSONObject().put("type", "string").put("maxLength", 20)
                        .put("description", "place_details：destination 或屏幕候选的序号"),
                ),
            required = listOf("kind"),
        )
        return listOf(queryLiveInfo.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            QUERY_LIVE_INFO -> when {
                "kind" !in keys || !setOf("kind", "where", "day", "category", "target").containsAll(keys) -> "INVALID_FIELDS"
                keys.any { json.opt(it) !is String } -> "INVALID_FIELD_TYPE"
                json.optString("kind") !in LIVE_INFO_KINDS -> "INVALID_KIND"
                keys.any { json.optString(it).length > 20 } -> "INVALID_ARGUMENT"
                else -> null
            }
            else -> null
        }
    }
}
