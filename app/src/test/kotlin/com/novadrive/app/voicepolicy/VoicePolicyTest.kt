package com.novadrive.app.voicepolicy

import com.novadrive.app.nav.NavigationPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoicePolicyTest {
    @Test
    fun notNavigatingInformationalResponseIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = false, ResponseCategory.INFORMATIONAL_RESPONSE),
        )
    }

    @Test
    fun notNavigatingActionConfirmationIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = false, ResponseCategory.ACTION_CONFIRMATION),
        )
    }

    @Test
    fun notNavigatingActionFailureIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = false, ResponseCategory.ACTION_FAILURE),
        )
    }

    @Test
    fun notNavigatingNavigationConfirmationIsSpeakShort() {
        assertEquals(
            VoiceDecision.SPEAK_SHORT,
            VoicePolicy.decide(navigating = false, ResponseCategory.NAVIGATION_CONFIRMATION),
        )
    }

    @Test
    fun notNavigatingNavigationErrorIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = false, ResponseCategory.NAVIGATION_ERROR),
        )
    }

    @Test
    fun notNavigatingCriticalAlertIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = false, ResponseCategory.CRITICAL_ALERT),
        )
    }

    @Test
    fun navigatingInformationalResponseIsSilent() {
        assertEquals(
            VoiceDecision.SILENT,
            VoicePolicy.decide(navigating = true, ResponseCategory.INFORMATIONAL_RESPONSE),
        )
    }

    @Test
    fun navigatingActionConfirmationIsSpeakShort() {
        assertEquals(
            VoiceDecision.SPEAK_SHORT,
            VoicePolicy.decide(navigating = true, ResponseCategory.ACTION_CONFIRMATION),
        )
    }

    @Test
    fun navigatingActionFailureIsSpeakShort() {
        assertEquals(
            VoiceDecision.SPEAK_SHORT,
            VoicePolicy.decide(navigating = true, ResponseCategory.ACTION_FAILURE),
        )
    }

    @Test
    fun navigatingNavigationConfirmationIsSpeakShort() {
        assertEquals(
            VoiceDecision.SPEAK_SHORT,
            VoicePolicy.decide(navigating = true, ResponseCategory.NAVIGATION_CONFIRMATION),
        )
    }

    @Test
    fun navigatingNavigationErrorIsSpeakShort() {
        assertEquals(
            VoiceDecision.SPEAK_SHORT,
            VoicePolicy.decide(navigating = true, ResponseCategory.NAVIGATION_ERROR),
        )
    }

    @Test
    fun navigatingCriticalAlertIsSpeakNormal() {
        assertEquals(
            VoiceDecision.SPEAK_NORMAL,
            VoicePolicy.decide(navigating = true, ResponseCategory.CRITICAL_ALERT),
        )
    }

    @Test
    fun navigationPhaseOverloadAgreesWithBooleanOverloadForEveryPhase() {
        for (phase in NavigationPhase.entries) {
            for (category in ResponseCategory.entries) {
                assertEquals(
                    VoicePolicy.decide(phase.isNavigationSessionActive, category),
                    VoicePolicy.decide(phase, category),
                    "$phase / $category",
                )
            }
        }
    }
}
