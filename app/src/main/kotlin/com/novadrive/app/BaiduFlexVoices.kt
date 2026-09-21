package com.novadrive.app

/**
 * Curated Baidu Flex `session.voice` ids. Flex docs default to `"default"` and point at the
 * speech 音色表 ([SPEECH/Rluv3uq3d](https://cloud.baidu.com/doc/SPEECH/s/Rluv3uq3d)); numeric
 * `per` ids from that table are what we send as `voice`. Unsupported ids fall back to
 * `"default"` in [com.novadrive.app.voice.BaiduFlexClient].
 *
 * Product pick (owner 2026-09-21): **4196** 度清影-甜美女声 — youthful sweet female, 大模型音库.
 */
object BaiduFlexVoices {
    const val PREFERRED_YOUTHFUL_FEMALE = "4196"

    data class Entry(val id: String, val labelZh: String, val note: String)

    val CATALOG: List<Entry> = listOf(
        Entry("4196", "度清影-甜美女声", "product default · youthful sweet · 大模型"),
        Entry("4194", "度嫣然-活泼女声", "lively young · 大模型"),
        Entry("4103", "度米朵-可爱女声", "cute female"),
        Entry("6562", "度雨楠-元气少女", "energetic girl"),
        Entry("111", "度小萌-软萌妹子", "soft cute"),
        Entry("4157", "度言静-明亮女声", "bright female"),
        Entry("default", "Baidu Flex stock default", "fallback / older timbre"),
    )

    fun labelFor(id: String): String =
        CATALOG.firstOrNull { it.id == id }?.labelZh ?: id

    fun spinnerLabels(): Array<String> =
        CATALOG.map { "${it.id} · ${it.labelZh}" }.toTypedArray()

    fun idAt(index: Int): String =
        CATALOG.getOrElse(index) { CATALOG.first() }.id

    fun indexOf(id: String): Int {
        val i = CATALOG.indexOfFirst { it.id == id }
        return if (i >= 0) i else 0
    }
}
