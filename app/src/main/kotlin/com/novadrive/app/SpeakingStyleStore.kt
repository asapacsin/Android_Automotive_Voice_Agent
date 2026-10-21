package com.novadrive.app

import android.content.Context

/** Persists [SpeakingStyle] so it survives sessions, sleep and app restarts (SPEC-015 FZ-12). */
class SpeakingStyleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SpeakingStyle = decode(prefs.getString(KEY, null))

    fun save(style: SpeakingStyle) {
        if (!prefs.edit().putString(KEY, encode(style)).commit()) error("speaking style not saved")
    }

    companion object {
        const val PREFS = "nova_speaking_style"
        const val KEY = "style"

        fun encode(style: SpeakingStyle): String = style.wireName

        fun decode(raw: String?): SpeakingStyle = SpeakingStyle.fromWire(raw)
    }
}
