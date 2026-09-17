package com.novadrive.app.nav

import java.util.concurrent.CopyOnWriteArrayList

/**
 * SDK-free signal: the embedded navigation engine is speaking guidance right now.
 *
 * P3: guidance leaves the speaker and enters the microphone, and Baidu then treats it as the
 * driver's speech. With the SDK embedded (ADR-007) we are told exactly when it speaks, so the
 * voice session can stop listening for that time instead of guessing.
 */
object NavigationGuidanceVoice {
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var speaking: Boolean = false
        private set

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    fun onPlayStart() = publish(true)

    fun onPlayEnd() = publish(false)

    private fun publish(value: Boolean) {
        speaking = value
        listeners.forEach { it(value) }
    }
}
