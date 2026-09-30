package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolFailureAdviceTest {
    @Test
    fun aToolCallInsideAGuidanceTurnAsksForSilence() {
        assertEquals(
            "这是导航播报，不需要回应。请不要说任何话。",
            ToolFailureAdvice.forCode(com.novadrive.app.voice.GeminiPromptTurn.NOT_A_DRIVER_TURN),
        )
    }
}
