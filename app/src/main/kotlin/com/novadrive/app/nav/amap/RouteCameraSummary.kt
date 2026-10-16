package com.novadrive.app.nav.amap

/**
 * Debug summary of calculated routes and their speed cameras (codes and numbers only, never
 * coordinates or names). Each camera is `limit to position`. In SDK 11.2.100 a route camera is
 * built from `RouteCamera` (type, speed, lon, lat only), so `getDistance()` is not set before
 * guidance; the position is therefore the camera's index along the route (`allCameras` order).
 */
object RouteCameraSummary {
    data class RouteInfo(val id: Int, val lengthM: Int, val timeS: Int, val cameras: List<Pair<Int, Int>>)

    fun format(routes: List<RouteInfo>): String =
        (listOf("routes=${routes.size}") + routes.sortedBy { it.id }.map { r ->
            "r${r.id}:len=${r.lengthM},time=${r.timeS},cams=" +
                r.cameras.filter { it.first > 0 }.joinToString(";") { "${it.first}@${it.second}" }
        }).joinToString(" ")
}
