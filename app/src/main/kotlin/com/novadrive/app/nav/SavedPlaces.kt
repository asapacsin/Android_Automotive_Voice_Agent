package com.novadrive.app.nav

/**
 * 「回家」 is not a place name.
 *
 * Measured on device 2026-09-19: `navigate_to destination=回家` reached Amap's POI search verbatim
 * and returned `count=0`, so the one thing every driver says most often was the one thing the
 * product could not do. A saved place is not a search result — it is a fact about this driver, and
 * it has to be answered from what they told us, not guessed from a map.
 *
 * Pure text, no Android, unit-tested.
 */
enum class PlaceSlot(val spokenName: String) {
    HOME("家"),
    WORK("公司"),
}

object SavedPlaces {
    /**
     * Phrasings that mean a slot rather than a POI. Cantonese is included deliberately: the
     * product owner speaks it, 「返屋企」 is the natural form, and a Mandarin-only list would have
     * looked correct in review and failed in the car.
     */
    private val HOME_TERMS = listOf(
        "回家", "返家", "我家", "家里", "家裡", "我屋企", "返屋企", "屋企", "家",
        "go home", "home", "drive home", "take me home",
    )

    private val WORK_TERMS = listOf(
        "回公司", "去公司", "上班", "返工", "我公司", "公司", "单位", "單位", "办公室", "辦公室",
        "go to work", "work", "office", "the office",
    )

    /** Filler that can wrap a slot term without changing which slot is meant. */
    private val STRIPPED = listOf(
        "导航去", "导航到", "导航", "帶我", "带我", "送我", "我要", "我想", "麻烦", "帮我", "幫我",
        "请", "請", "现在", "現在", "立刻", "马上", "馬上", "吧", "啦", "喇", "了", "呀", "啊", "咯",
        "。", "，", "！", "!", "?", "？", " ",
    )

    /**
     * Which slot [spoken] names, or null when it names something else.
     *
     * Matching is on the *whole* utterance after filler is removed, not a substring search:
     * 「家乐福」 contains 家 and is a supermarket, and 「公司附近的星巴克」 is a coffee shop near work,
     * not work. Both must fall through to the POI search.
     */
    fun slotFor(spoken: String): PlaceSlot? {
        val core = strip(spoken)
        if (core.isEmpty()) return null
        if (HOME_TERMS.any { core.equals(it, ignoreCase = true) }) return PlaceSlot.HOME
        if (WORK_TERMS.any { core.equals(it, ignoreCase = true) }) return PlaceSlot.WORK
        return null
    }

    private fun strip(spoken: String): String {
        var text = spoken.trim()
        var changed = true
        while (changed) {
            changed = false
            for (token in STRIPPED) {
                if (text.length > token.length && text.startsWith(token, ignoreCase = true)) {
                    text = text.drop(token.length).trim()
                    changed = true
                }
                if (text.length > token.length && text.endsWith(token, ignoreCase = true)) {
                    text = text.dropLast(token.length).trim()
                    changed = true
                }
            }
        }
        return text
    }
}

/** A place this driver told us about, with the coordinates navigation actually needs. */
data class SavedPlace(
    val slot: PlaceSlot,
    val label: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude $latitude is outside -90..90" }
        require(longitude in -180.0..180.0) { "longitude $longitude is outside -180..180" }
    }

    /**
     * The single candidate a saved place resolves to. `id` is stable and namespaced so a saved
     * place is distinguishable from a POI result in a log or a picker.
     */
    fun toCandidate(): DestinationCandidate = DestinationCandidate(
        id = "saved:${slot.name.lowercase()}",
        name = label,
        address = address,
        district = "",
        latitude = latitude,
        longitude = longitude,
    )
}
