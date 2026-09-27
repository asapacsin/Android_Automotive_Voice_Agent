package com.novadrive.app.nav

/**
 * What the text navigation panel shows where the map would be, on a build that cannot draw the
 * map (x86 emulator, see `TranslatedAbi`). Pure Kotlin so it is unit-tested; it holds no state —
 * the caller passes the controller's phase and lists plus the latest guidance progress the SDK
 * listener already receives. Screen text only: nothing here is logged.
 */
object TextNavigationPanelModel {
    /** Latest `NaviInfo`, reduced to what the panel shows. */
    data class Progress(
        val iconType: Int,
        val stepMeters: Int,
        val remainMeters: Int,
        val remainSeconds: Int,
        val nextRoad: String?,
    )

    data class Lines(val title: String, val body: List<String>)

    fun render(
        phase: NavigationPhase,
        destinations: List<DestinationCandidate>,
        routes: List<RouteCandidate>,
        destinationName: String?,
        progress: Progress?,
        limitKmh: Int,
        guidanceText: String?,
    ): Lines = when (phase) {
        NavigationPhase.IDLE -> Lines("待命", listOf("地图在此模拟器上无法显示（x86）", "说「导航去…」开始"))
        NavigationPhase.RESOLVING_DESTINATION -> Lines("正在搜索目的地…", emptyList())
        NavigationPhase.AWAITING_DESTINATION_SELECTION -> Lines(
            "选择目的地",
            destinations.mapIndexed { i, c ->
                val dist = c.distanceMeters?.let { "  " + NavigationFormatters.formatDistanceMeters(it) } ?: ""
                "${i + 1}. ${c.name}$dist"
            },
        )
        NavigationPhase.PLANNING_ROUTE, NavigationPhase.CALCULATING_ROUTE ->
            Lines("正在规划路线…", listOfNotNull(destinationName))
        NavigationPhase.AWAITING_ROUTE_SELECTION, NavigationPhase.ROUTE_READY -> Lines(
            "路线预览" + (destinationName?.let { " · $it" } ?: ""),
            routes.mapIndexed { i, r ->
                "${i + 1}. ${km(r.distanceMeters)}  ${NavigationFormatters.formatDurationSeconds(r.durationSeconds)}" +
                    (r.labels?.let { "  $it" } ?: "")
            },
        )
        NavigationPhase.NAVIGATING -> navigating(destinationName, progress, limitKmh, guidanceText)
        NavigationPhase.ARRIVED -> Lines("已到达", listOfNotNull(destinationName))
        NavigationPhase.STOPPED -> Lines("导航已结束", emptyList())
        NavigationPhase.ERROR -> Lines("导航出错", emptyList())
    }

    private fun navigating(dest: String?, p: Progress?, limitKmh: Int, text: String?): Lines {
        val title = "导航中" + (dest?.let { " → $it" } ?: "")
        if (p == null) return Lines(title, listOf("等待导航数据…"))
        val body = mutableListOf<String>()
        body += "${maneuver(p.iconType)}  ${NavigationFormatters.formatDistanceMeters(p.stepMeters)}" +
            (p.nextRoad?.takeIf { it.isNotBlank() }?.let { "  进入 $it" } ?: "")
        body += "剩余 ${km(p.remainMeters)} · ${NavigationFormatters.formatDurationSeconds(p.remainSeconds)}"
        if (limitKmh > 0) body += "限速 $limitKmh km/h"
        text?.takeIf { it.isNotBlank() }?.let { body += "🔊 $it" }
        return Lines(title, body)
    }

    private fun km(meters: Int): String = String.format(java.util.Locale.US, "%.1f km", meters.coerceAtLeast(0) / 1000.0)

    /** Amap `IconType` values (com.amap.api.navi.enums.IconType), as a word and an arrow. */
    fun maneuver(iconType: Int): String = when (iconType) {
        2 -> "← 左转"
        3 -> "→ 右转"
        4 -> "↖ 向左前方"
        5 -> "↗ 向右前方"
        6 -> "↙ 向左后方"
        7 -> "↘ 向右后方"
        8 -> "↶ 掉头"
        9 -> "↑ 直行"
        10 -> "◎ 到达途经点"
        11 -> "⟳ 进入环岛"
        12 -> "⟲ 驶出环岛"
        13 -> "⛽ 服务区"
        14 -> "收费站"
        15 -> "◎ 到达目的地"
        16 -> "隧道"
        else -> "↑ 沿路行驶"
    }
}
