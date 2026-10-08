package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonaProfilesTest {
    private val rulesSentences = listOf(
        "每次回复不超过两句话",
        "只有在工具返回成功后，才能说某个操作已完成",
        "驾驶安全优先",
    )

    @Test fun defaultIsIdentityForDefaultText() {
        assertEquals(PersonaProfiles.sanitize(null), PersonaProfiles.compose(null, SpeakingStyle.DEFAULT))
        assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS, PersonaProfiles.compose(PersonaProfiles.DEFAULT_INSTRUCTIONS, SpeakingStyle.DEFAULT))
    }

    @Test fun defaultIsIdentityForCustomAndBlank() {
        val custom = "  你是一个助手。\n性格基调：严肃。  "
        assertEquals(PersonaProfiles.sanitize(custom), PersonaProfiles.compose(custom, SpeakingStyle.DEFAULT))
        assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS, PersonaProfiles.compose("   ", SpeakingStyle.DEFAULT))
    }

    @Test fun sweetReplacesBothLines() {
        val out = PersonaProfiles.compose(null, SpeakingStyle.SWEET)
        assertTrue(out.contains(PersonaProfiles.SWEET_TONE))
        assertTrue(out.contains(PersonaProfiles.SWEET_VOICE_STYLE))
        assertFalse(out.contains("不撒娇"))
        assertFalse(out.contains("不卖萌"))
        assertFalse(out.contains("平稳克制"))
        assertEquals(1, out.lines().count { it.startsWith("性格基调：") })
        assertEquals(1, out.lines().count { it.startsWith("语音风格：") })
        assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS.lines().size, out.lines().size)
    }

    @Test fun rulesParagraphPreservedInBothStyles() {
        for (style in SpeakingStyle.entries) {
            val out = PersonaProfiles.compose(null, style)
            rulesSentences.forEach { assertTrue(out.contains(it), "$style lost: $it") }
        }
        val defaultRules = PersonaProfiles.DEFAULT_INSTRUCTIONS.lines().filter { it.firstOrNull()?.isDigit() == true }
        val sweetRules = PersonaProfiles.compose(null, SpeakingStyle.SWEET).lines().filter { it.firstOrNull()?.isDigit() == true }
        assertEquals(defaultRules, sweetRules)
    }

    @Test fun sweetOnCustomWithoutLinesAppends() {
        val out = PersonaProfiles.compose("你是助手。", SpeakingStyle.SWEET)
        assertEquals("你是助手。\n${PersonaProfiles.SWEET_TONE}\n${PersonaProfiles.SWEET_VOICE_STYLE}", out)
    }

    @Test fun everyNonDefaultStyleSwapsBothLinesAndKeepsTheRules() {
        for (style in SpeakingStyle.entries - SpeakingStyle.DEFAULT) {
            val (tone, voice) = PersonaProfiles.toneLines(style)!!
            val out = PersonaProfiles.compose(null, style)
            assertTrue(out.contains(tone) && out.contains(voice), "$style")
            assertEquals(1, out.lines().count { it.startsWith("性格基调：") }, "$style")
            assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS.lines().size, out.lines().size, "$style")
        }
    }

    @Test fun anActedToneNeverLicensesRefusingOrMisreporting() {
        // 傲娇 must stay words: 「才不帮你开」 after the window opened would contradict the result.
        for (style in listOf(SpeakingStyle.TSUNDERE, SpeakingStyle.GENTLE, SpeakingStyle.LIVELY)) {
            assertTrue(PersonaProfiles.toneLines(style)!!.first.contains(PersonaProfiles.TONE_GUARD), "$style")
        }
        assertTrue(PersonaProfiles.TSUNDERE_TONE.contains("绝不刻薄"))
    }

    @Test fun resultIsCapped() {
        val long = "性格基调：x\n" + "a".repeat(PersonaProfiles.MAX_INSTRUCTIONS_CHARS * 2)
        assertTrue(PersonaProfiles.compose(long, SpeakingStyle.SWEET).length <= PersonaProfiles.MAX_INSTRUCTIONS_CHARS)
        assertTrue(PersonaProfiles.compose("你是助手。", SpeakingStyle.SWEET).length <= PersonaProfiles.MAX_INSTRUCTIONS_CHARS)
    }
}
