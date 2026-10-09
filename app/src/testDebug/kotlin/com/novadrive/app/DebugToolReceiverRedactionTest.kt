package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The debug dispatch log carries argument keys and result codes only (I-8). */
class DebugToolReceiverRedactionTest {
    @Test
    fun qwenSetupArgumentIsNeverLogged() {
        val line = DebugToolReceiver.safeDebugArg("qwen_setup", "sk-secret-key-value")
        assertEquals("<redacted>", line)
        assertFalse(line.contains("sk-"))
    }

    @Test
    fun dispatchArgumentValuesAreNotLogged() {
        val line = DebugToolReceiver.redactDispatchArg("play_music:title=晴天,artist=周杰伦,query=放周杰伦的晴天")
        assertEquals("play_music:title,artist,query", line)
        assertFalse(line.contains("周杰伦"))
    }

    @Test
    fun playMusicResultIsLoggedAsCodesOnly() {
        val out = """{"ok":true,"tool":"play_music","status":"playing","now_playing":{"title":"晴天","artist":"周杰伦"},"matches_request":true,"announce":"在放周杰伦的《晴天》"}"""
        val line = DebugToolReceiver.safeOutput("play_music", out)
        assertEquals("ok=true status=playing matches_request=true", line)
        assertFalse(line.contains("晴天"))
    }
}
