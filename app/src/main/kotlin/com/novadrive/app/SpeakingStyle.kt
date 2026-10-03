package com.novadrive.app

/** How 小诺 speaks. Tone only; the voice never changes (SPEC-015 B6). */
enum class SpeakingStyle(val wireName: String, val chipLabel: String) {
    DEFAULT("default", "默认"),
    SWEET("sweet", "甜"),
    TSUNDERE("tsundere", "傲娇"),
    GENTLE("gentle", "温柔"),
    LIVELY("lively", "元气"),
    ;

    companion object {
        fun fromWire(raw: String?): SpeakingStyle {
            val key = raw?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.wireName == key } ?: DEFAULT
        }
    }
}

/** Process-wide current style. Sticky: only [set] changes it. Persistence is injected later. */
object SpeakingStyleState {
    @Volatile
    var current: SpeakingStyle = SpeakingStyle.DEFAULT
        private set

    @Volatile
    var persist: (SpeakingStyle) -> Unit = {}

    fun set(style: SpeakingStyle) {
        current = style
        persist(style)
    }

    /** Load at startup; does not call [persist]. */
    fun restore(style: SpeakingStyle) {
        current = style
    }
}
