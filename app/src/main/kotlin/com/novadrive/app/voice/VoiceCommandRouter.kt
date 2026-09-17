package com.novadrive.app.voice

import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry

/**
 * Routes the driver's finished utterance through the listening-control rules before the model's
 * reply matters (precedence in [ListeningIntent]). Everything else is left to the model; a real
 * utterance also counts as activity, which ends SILENT_WAIT.
 */
class VoiceCommandRouter(
    private val lifecycle: ListeningLifecycle,
    private val context: () -> ListeningIntent.Context,
    private val log: (String) -> Unit = {},
) {
    fun onUserUtterance(text: String): ListeningIntent.Decision {
        val decision = ListeningIntent.classify(text, context())
        when (decision) {
            ListeningIntent.Decision.GO_TO_SLEEP -> {
                log("listening_sleep source=voice")
                Telemetry.record(EventType.SLEEP_REQUESTED, detail = "voice_command")
                lifecycle.sleep("voice_command")
            }
            ListeningIntent.Decision.SHUT_UP -> {
                log("listening_shut_up source=voice")
                Telemetry.record(EventType.SHUT_UP, detail = "voice_command")
                lifecycle.silence("voice_command")
            }
            ListeningIntent.Decision.PASS_TO_MODEL ->
                if (ListeningIntent.isMeaningful(text)) lifecycle.onMeaningfulUserTurn()
        }
        return decision
    }
}
