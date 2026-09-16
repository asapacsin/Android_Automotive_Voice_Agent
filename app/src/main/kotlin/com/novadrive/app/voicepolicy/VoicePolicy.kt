package com.novadrive.app.voicepolicy

import com.novadrive.app.nav.NavigationPhase

enum class ResponseCategory {
    INFORMATIONAL_RESPONSE,
    ACTION_CONFIRMATION,
    ACTION_FAILURE,
    NAVIGATION_CONFIRMATION,
    NAVIGATION_ERROR,
    CRITICAL_ALERT,
}

enum class VoiceDecision {
    SPEAK_NORMAL,
    SPEAK_SHORT,
    SILENT,
}

/**
 * v2 section 17: never inspect response text — [decide] takes no String.
 */
object VoicePolicy {
    fun decide(navigating: Boolean, category: ResponseCategory): VoiceDecision {
        return if (!navigating) {
            when (category) {
                ResponseCategory.INFORMATIONAL_RESPONSE -> VoiceDecision.SPEAK_NORMAL
                ResponseCategory.ACTION_CONFIRMATION -> VoiceDecision.SPEAK_NORMAL
                ResponseCategory.ACTION_FAILURE -> VoiceDecision.SPEAK_NORMAL
                ResponseCategory.NAVIGATION_CONFIRMATION -> VoiceDecision.SPEAK_SHORT
                ResponseCategory.NAVIGATION_ERROR -> VoiceDecision.SPEAK_NORMAL
                ResponseCategory.CRITICAL_ALERT -> VoiceDecision.SPEAK_NORMAL
            }
        } else {
            when (category) {
                ResponseCategory.INFORMATIONAL_RESPONSE -> VoiceDecision.SILENT
                ResponseCategory.ACTION_CONFIRMATION -> VoiceDecision.SPEAK_SHORT
                ResponseCategory.ACTION_FAILURE -> VoiceDecision.SPEAK_SHORT
                // "Speak only when useful" for NAVIGATION_CONFIRMATION while navigating is SPEAK_SHORT.
                ResponseCategory.NAVIGATION_CONFIRMATION -> VoiceDecision.SPEAK_SHORT
                ResponseCategory.NAVIGATION_ERROR -> VoiceDecision.SPEAK_SHORT
                ResponseCategory.CRITICAL_ALERT -> VoiceDecision.SPEAK_NORMAL
            }
        }
    }

    fun decide(phase: NavigationPhase, category: ResponseCategory): VoiceDecision =
        decide(phase.isNavigationSessionActive, category)
}
