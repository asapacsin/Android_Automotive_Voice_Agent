package com.novadrive.ingress.realtime

data class LatencyMetrics(
    val connectMs: Long? = null,
    val reconnectMs: Long? = null,
    val speechEndToFirstAudioMs: Long? = null,
    val interruptToPlaybackStoppedMs: Long? = null,
)

class LatencyDiagnostics(
    private val clock: SessionClock,
) {
    var connectStartedAt: Long? = null
        private set
    var reconnectStartedAt: Long? = null
        private set
    var speechEndedAt: Long? = null
        private set
    var interruptDetectedAt: Long? = null
        private set
    var metrics: LatencyMetrics = LatencyMetrics()
        private set

    fun markConnectStart() {
        connectStartedAt = clock.nowMs()
    }

    fun markConnected() {
        val started = connectStartedAt ?: return
        metrics = metrics.copy(connectMs = clock.nowMs() - started)
    }

    fun markReconnectStart() {
        reconnectStartedAt = clock.nowMs()
    }

    fun markReconnected() {
        val started = reconnectStartedAt ?: return
        metrics = metrics.copy(reconnectMs = clock.nowMs() - started)
    }

    fun markSpeechEnd() {
        speechEndedAt = clock.nowMs()
    }

    fun markFirstAudio() {
        val started = speechEndedAt ?: return
        if (metrics.speechEndToFirstAudioMs == null) {
            metrics = metrics.copy(speechEndToFirstAudioMs = clock.nowMs() - started)
        }
    }

    fun markInterruptDetected() {
        interruptDetectedAt = clock.nowMs()
    }

    fun markPlaybackStopped() {
        val started = interruptDetectedAt ?: return
        metrics = metrics.copy(interruptToPlaybackStoppedMs = clock.nowMs() - started)
    }
}

class StructuredVoiceLog {
    val lines = mutableListOf<String>()

    fun info(event: String, details: Map<String, Any?> = emptyMap()): String {
        val line = format("INFO", event, details)
        lines += line
        return line
    }

    fun warn(event: String, details: Map<String, Any?> = emptyMap()): String {
        val line = format("WARN", event, details)
        lines += line
        return line
    }

    companion object {
        private val secretKeys =
            setOf(
                "api_key",
                "secret",
                "token",
                "authorization",
                "password",
                "dashscope",
                "openai",
                "access_token",
            )

        fun redact(value: String): String {
            var out = value
            val assignment = Regex("(?i)(api[_-]?key|secret|token|authorization|access_token)\\s*[=:]\\s*([^\\s,;]+)")
            out = assignment.replace(out) { "${it.groupValues[1]}=***" }
            return out
        }

        fun format(level: String, event: String, details: Map<String, Any?>): String {
            val safe =
                details.entries.joinToString(" ") { (key, value) ->
                    val lowered = key.lowercase()
                    val shown =
                        if (secretKeys.any { lowered.contains(it) }) {
                            "configured:" + if (value != null && value.toString().isNotBlank()) "yes" else "no"
                        } else {
                            redact(value?.toString() ?: "")
                        }
                    "$key=$shown"
                }
            return "$level event=$event $safe".trim()
        }
    }
}
