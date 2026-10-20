package com.novadrive.app

/** ALONG-PROBE debug line: counts only, never a coordinate, name or POI id (I-8). */
object AlongProbe {
    val RANGES = listOf(250, 500, 1000, 2000)

    fun line(rangeM: Int, code: Int, raw: Int?, ms: Long): String =
        "along_probe range_m=$rangeM code=$code raw=${raw ?: -1} ms=$ms"

    const val POLYLINE_RANGE_M = 1000
    const val MAX_POLYLINE_POINTS = 100
    const val WEB_RADIUS_M = 3000

    fun polylineLine(rangeM: Int, code: Int, raw: Int?, points: Int): String =
        "along_probe form=polyline range_m=$rangeM code=$code raw=${raw ?: -1} points=$points"

    fun modeLine(mode: Int, rangeM: Int, code: Int, raw: Int?): String =
        "along_probe form=startend mode=$mode range_m=$rangeM code=$code raw=${raw ?: -1}"

    fun webAroundLine(radiusM: Int, count: Int?): String =
        "along_probe form=web_around radius_m=$radiusM count=${count ?: -1}"

    /** Evenly picks at most [max] items, keeping the first and the last. */
    fun <T> downsample(items: List<T>, max: Int): List<T> {
        if (items.size <= max) return items
        if (max < 2) return items.take(max)
        return (0 until max).map { i -> items[(i.toLong() * (items.size - 1) / (max - 1)).toInt()] }
    }
}
