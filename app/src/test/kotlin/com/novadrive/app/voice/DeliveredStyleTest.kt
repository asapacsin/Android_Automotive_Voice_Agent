package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P55 review: a style counts as delivered only once a socket accepted the message. */
class DeliveredStyleTest {
    private var style = SpeakingStyle.DEFAULT
    private var composed = 0

    private fun delivered(): DeliveredStyle {
        lateinit var d: DeliveredStyle
        // As in the clients: the instructions are composed with take().
        d = DeliveredStyle(
            current = { style },
            provider = "test",
            instructions = { composed++.let { "TEXT-${d.take()}" } },
            wire = { "MSG:$it" },
        )
        d.take()
        return d
    }

    @Test
    fun aFailedSendIsRetriedAtTheNextIdlePoint() {
        val d = delivered()
        style = SpeakingStyle.SWEET
        d.checkPending = true
        val attempts = mutableListOf<String>()
        d.atIdle { attempts += it; false }
        assertEquals(1, attempts.size)
        d.atIdle { attempts += it; true }
        assertEquals(2, attempts.size, "retried at the next idle point")
        d.checkPending = true
        d.atIdle { attempts += it; true }
        assertEquals(2, attempts.size, "sent once it was accepted")
    }

    @Test
    fun anUnsupportedProviderComposesNothing() {
        val d = delivered()
        composed = 0
        style = SpeakingStyle.SWEET
        assertTrue(d.redelivery("baidu", { composed++; "TEXT" }, { null }, supported = false).isEmpty())
        assertEquals(0, composed, "no instructions composed for nothing")
        assertTrue(d.changed(), "not marked delivered")
    }
}
