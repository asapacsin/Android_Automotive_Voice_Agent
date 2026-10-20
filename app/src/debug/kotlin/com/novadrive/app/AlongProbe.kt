package com.novadrive.app

/** ALONG-PROBE debug line: counts only, never a coordinate, name or POI id (I-8). */
object AlongProbe {
    val RANGES = listOf(250, 500, 1000, 2000)

    fun line(rangeM: Int, code: Int, raw: Int?, ms: Long): String =
        "along_probe range_m=$rangeM code=$code raw=${raw ?: -1} ms=$ms"
}
