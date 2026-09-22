package com.novadrive.app.voice

import com.novadrive.contracts.CapabilityIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UtteranceIntentResolverTest {
    private val resolver = UtteranceIntentResolver.product()

    @Test
    fun capabilityHelpGrammarMatchesPositives() {
        listOf(
            "你能做什么",
            "你能干啥",
            "能干啥",
            "有什么功能",
            "你会什么",
            "能帮我做什么",
            "你可以做什麼",
            "what can you do",
            "你好小诺，你能做什么？",
        ).forEach { phrase ->
            assertEquals(
                CapabilityIds.SPEECH_CAPABILITY_HELP,
                resolver.resolve(phrase)?.capabilityId,
                phrase,
            )
        }
    }

    @Test
    fun capabilityHelpGrammarRejectsNearMisses() {
        listOf(
            "你能帮我导航吗",
            "调低温度",
            "你干啥",
            "干啥",
            "你能听到吗",
            "今天天气怎么样",
        ).forEach { phrase ->
            assertNull(resolver.resolve(phrase), phrase)
        }
    }
}
