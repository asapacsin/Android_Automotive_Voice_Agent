package com.novadrive.app.voice

import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Silent mode (「闭嘴」「安静」「shut up」): 小诺 keeps listening and keeps carrying out commands, but
 * its replies are not spoken — they only appear as text. Voice returns on 「可以说话了」 / 「恢复语音」,
 * the `set_speech_output` tool, or a tap on the status row.
 *
 * Process-wide and deliberately not persisted: a restart of the app speaks again. Navigation
 * guidance is not affected (it is not 小诺's voice).
 */
object SpeechOutput {
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var silent: Boolean = false
        private set

    /** Returns true when the mode actually changed. */
    fun setSilent(value: Boolean, reason: String): Boolean {
        synchronized(this) {
            if (silent == value) return false
            silent = value
        }
        runCatching { com.novadrive.app.DebugVoiceLog.log("speech_output silent=$value reason=$reason") }
        Telemetry.record(if (value) EventType.SPEECH_OUTPUT_SILENCED else EventType.SPEECH_OUTPUT_RESTORED, detail = reason)
        listeners.forEach { runCatching { it(value) } }
        return true
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
