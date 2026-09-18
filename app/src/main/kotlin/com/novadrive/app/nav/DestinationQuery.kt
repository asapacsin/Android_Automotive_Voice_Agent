package com.novadrive.app.nav

/**
 * Turns what the driver said into something a POI search can actually find.
 *
 * Measured on device 2026-09-18 (checklist row T04): 「导航去附近的麦当劳」 reached `navigate_to` with
 * `destination=附近的麦当劳`, that exact string went to Amap's around-search as the keyword, and the
 * search returned **zero** results — no POI is called "附近的麦当劳". The driver was told navigation
 * failed, for a brand with branches a few hundred metres away.
 *
 * "附近" is not part of the name; it is an instruction about *where* to search, which the
 * around-search already does. So the qualifier is stripped from the keyword and reported separately.
 *
 * Pure text, no Android, unit-tested.
 */
object DestinationQuery {
    /**
     * Words that say "near me" rather than naming a place. Longest first, so 「离我最近的」 is
     * consumed before 「最近的」 can match inside it.
     */
    private val NEARBY_PREFIXES = listOf(
        "离我最近的", "离我最近", "距离我最近的", "最近的一家", "附近的一家",
        "我附近的", "我周围的", "我旁边的", "这附近的", "这周围的",
        "附近的", "附近", "周围的", "周围", "周边的", "周边",
        "最近的", "最近", "旁边的", "旁边", "就近的", "就近",
        "nearest", "nearby", "closest",
    ).sortedByDescending { it.length }

    /** Politeness and filler that is never part of a POI name. */
    private val LEADING_FILLER = listOf(
        "帮我", "给我", "带我", "我要", "我想", "请", "麻烦", "去", "到", "找", "搜索", "搜", "查找", "查",
    ).sortedByDescending { it.length }

    private val TRAILING_FILLER = listOf("吧", "呢", "啊", "哦", "谢谢", "。", "，", "!", "！", "?", "？")

    /** True when the driver asked for something near them rather than a named place. */
    fun isNearbyRequest(spoken: String): Boolean {
        val text = spoken.trim()
        return NEARBY_PREFIXES.any { text.contains(it) }
    }

    /**
     * The keyword to search for: the place or brand, with the locality qualifier and any filler
     * removed. Returns the original text when stripping would leave nothing — 「导航去附近」 is a
     * request with no destination in it, and an empty keyword would search for everything.
     */
    fun searchKeyword(spoken: String): String {
        var text = spoken.trim()
        for (filler in TRAILING_FILLER) {
            while (text.endsWith(filler)) text = text.removeSuffix(filler).trim()
        }
        var changed = true
        while (changed) {
            changed = false
            for (prefix in LEADING_FILLER) {
                if (text.startsWith(prefix) && text.length > prefix.length) {
                    text = text.removePrefix(prefix).trim()
                    changed = true
                }
            }
            for (prefix in NEARBY_PREFIXES) {
                val at = text.indexOf(prefix)
                // Only strip a qualifier that sits at the front of what is left; a name that
                // genuinely contains one (「附近公园」 is not a real case, but 「最近发展区」 could be)
                // keeps it once the front no longer matches.
                if (at == 0 && text.length > prefix.length) {
                    text = text.removeRange(0, prefix.length).trim()
                    changed = true
                }
            }
        }
        // 的 left dangling by a strip: 「附近的麦当劳」 -> 「麦当劳」 already, but 「我家附近 的 店」 can leave it.
        while (text.startsWith("的") && text.length > 1) text = text.removePrefix("的").trim()
        return text.ifBlank { spoken.trim() }
    }
}
