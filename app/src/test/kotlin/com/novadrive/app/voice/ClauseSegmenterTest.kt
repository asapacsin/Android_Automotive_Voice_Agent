package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClauseSegmenterTest {
    private fun cut(vararg chunks: String): List<String> {
        val segmenter = ClauseSegmenter()
        return chunks.flatMap(segmenter::append) + listOfNotNull(segmenter.flush())
    }

    @Test
    fun sentenceEndsCutAtOnceAcrossChunks() {
        assertEquals(listOf("空调已经调到二十二度了。", "还要别的吗？"), cut("空调已经调", "到二十二度了。还要", "别的吗？"))
    }

    @Test
    fun theFirstCommaCutsEarlyForAFastStartLaterCommasNeedLength() {
        assertEquals(
            listOf("好的，", "前方五百米右转，然后走第二个出口。"),
            cut("好的，前方五百米右转，然后走第二个出口。"),
        )
    }

    @Test
    fun aLongRunWithoutPunctuationIsCutAtTheMaximum() {
        val text = "一".repeat(45)
        assertEquals(listOf("一".repeat(40), "一".repeat(5)), cut(text))
    }

    @Test
    fun punctuationOnlyIsNeverSpoken() {
        assertEquals(listOf("好。"), cut("好。", "。！", "  "))
        assertNull(ClauseSegmenter().apply { append("……") }.flush())
    }

    @Test
    fun resetForgetsAHalfClause() {
        val segmenter = ClauseSegmenter()
        segmenter.append("说到一半")
        segmenter.reset()
        assertEquals(listOf("新的回复。"), segmenter.append("新的回复。"))
    }
}
