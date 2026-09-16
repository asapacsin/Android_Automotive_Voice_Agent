package com.novadrive.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentTranscriptTest {
    @Test
    fun firstLineStandsAlone() {
        assertEquals("你: 到万达。", recentTranscript("", "你: 到万达。"))
    }

    @Test
    fun keepsOnlyTheLatestExchange() {
        var text = ""
        listOf("你: 到万达。", "小诺: 请在屏幕上选择路线。", "你: 帮我播放音乐。", "小诺: 音乐已经播放。")
            .forEach { text = recentTranscript(text, it) }
        assertEquals("你: 帮我播放音乐。\n小诺: 音乐已经播放。", text)
    }

    @Test
    fun blankLinesAreDropped() {
        assertEquals("a\nb", recentTranscript("\n\na\n", "b"))
    }
}
