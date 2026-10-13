package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranscriptTextTest {
    @Test
    fun spacedCjkIsJoined() {
        assertEquals("帮我把天窗打开。", TranscriptText.normalize("帮 我 把 天 窗 打 开 。"))
        assertEquals("把我这边的窗户开一点。", TranscriptText.normalize(" 把 我 这 边 的 窗 户 开 一 点 。 "))
    }

    @Test
    fun latinTokensKeepTheirSpaces() {
        assertEquals("放Taylor Swift的歌", TranscriptText.normalize("放 Taylor Swift 的 歌"))
        assertEquals("调到22度", TranscriptText.normalize("调 到 22 度"))
        assertEquals("what can you do", TranscriptText.normalize("what can you do"))
    }

    @Test
    fun unspacedTextIsUnchanged() {
        assertEquals("帮我把天窗打开。", TranscriptText.normalize("帮我把天窗打开。"))
        assertEquals("有蚊子，把天窗关上", TranscriptText.normalize("有蚊子，把天窗关上"))
    }
}
