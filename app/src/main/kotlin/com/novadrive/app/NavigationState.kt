package com.novadrive.app

import com.novadrive.app.voice.SpeechAuthority

/**
 * The legacy "are we navigating" flag (VAD profile, `session.update`). Whether 小诺 may speak is not
 * decided here: since SPEC-012 step 3 the permitted-reply window lives in `SpeechArbiter`, which
 * this object only informs.
 */
object NavigationState {
    @Volatile var navigating: Boolean = false
    @Volatile var onNavigatingChanged: ((Boolean) -> Unit)? = null
    private val lock = Any()

    fun begin() {
        val changed = synchronized(lock) {
            if (navigating) false else {
                navigating = true
                true
            }
        }
        SpeechAuthority.arbiter.onNavigating(true)
        if (changed) notifyNavigatingChanged(true)
    }

    fun reset() {
        val changed = synchronized(lock) {
            val wasNavigating = navigating
            navigating = false
            wasNavigating
        }
        SpeechAuthority.arbiter.onNavigating(false)
        if (changed) notifyNavigatingChanged(false)
    }

    private fun notifyNavigatingChanged(value: Boolean) {
        runCatching { onNavigatingChanged?.invoke(value) }
    }
}
