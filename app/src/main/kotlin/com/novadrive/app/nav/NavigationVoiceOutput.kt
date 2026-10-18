package com.novadrive.app.nav

import org.json.JSONObject

/**
 * Tool output for the navigation tools: what is on screen now, in a form the model can read out.
 * The conversation is reset after tool turns, so this is the model's only view of the list.
 */
object NavigationVoiceOutput {
    fun build(tool: String, status: String, snapshot: EmbeddedNavigationController.OptionsSnapshot?): String {
        val out = JSONObject().put("ok", true).put("tool", tool).put("status", status)
        if (snapshot == null) return out.toString()
        if (snapshot.cancelled) return cancelled(out, snapshot).toString()
        when (snapshot.phase) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION -> out
                .put("screen", "destination_list")
                .put("count", snapshot.destinations.size)
                .put("options_on_screen", NavigationChoiceResolver.describeDestinations(snapshot.destinations))
                .put("next", "列表已经显示在屏幕上，不要念出列表。只说一句，例如：找到${snapshot.destinations.size}个地点，请说第几个，或直接点选。")
            NavigationPhase.AWAITING_ROUTE_SELECTION -> out
                .put("screen", "route_list")
                .put("destination", snapshot.destinationName.orEmpty())
                .put("count", snapshot.routes.size)
                .put("options_on_screen", NavigationChoiceResolver.describeRoutes(snapshot.routes))
                .put(
                    "next",
                    "路线已经显示在屏幕上，不要逐条念路线；目的地已选好，但路线还没选，导航还没有开始。只说一句，例如：" +
                        "有${snapshot.routes.size}条路线${recommendedMinutes(snapshot.routes)}，说「开始导航」走推荐路线，或说第几条、最快的。" +
                        "用户说「开始导航」「好的」「就这条」时选推荐路线",
                )
            NavigationPhase.NAVIGATING -> out
                .put("screen", "navigating")
                .put("destination", snapshot.destinationName.orEmpty())
            NavigationPhase.ERROR -> return JSONObject().put("ok", false).put("tool", tool)
                .put("error", "NAVIGATION_FAILED").toString()
            NavigationPhase.RESOLVING_DESTINATION, NavigationPhase.CALCULATING_ROUTE -> out
                .put("screen", "still_loading")
                .put("next", "告诉用户正在查找，稍后在屏幕上选择")
            else -> out.put("screen", "none")
        }
        return out.toString()
    }

    /** The driver cancelled while this call waited: no list, no 「请选择」 (D-CANCEL-STALE-REPLY, SPEC-006 CVC-36). */
    private fun cancelled(out: JSONObject, snapshot: EmbeddedNavigationController.OptionsSnapshot): JSONObject {
        out.put("screen", "cancelled")
        if (snapshot.phase == NavigationPhase.NAVIGATING) {
            return out.put("destination", snapshot.destinationName.orEmpty()).put(
                "next",
                "司机已取消这次查找，不要让他选择，不要念任何选项，也不要说有新的目的地。原来的导航继续进行，只简短说一句，例如：好的，已取消，继续按原路线导航。",
            )
        }
        return out.put("next", "司机已取消，不要让他选择，不要念任何选项。只简短确认「好的，已取消」，或者不再多说。")
    }

    /** 「，推荐的约29分钟」: the one number worth saying aloud. */
    private fun recommendedMinutes(routes: List<RouteCandidate>): String {
        val recommended = routes.firstOrNull { it.labels.orEmpty().contains("推荐") } ?: routes.firstOrNull() ?: return ""
        return "，推荐的约" + NavigationFormatters.formatDurationSeconds(recommended.durationSeconds).replace(" ", "")
    }
}
