package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** P37: the bridge's uplink never blocks the PC and never replays a stall as a burst. */
class HostAudioBridgeUplinkTest {
    @Test
    fun aStallKeepsOnlyTheNewestAudioInWholeSamples() {
        val buffer = HostAudioBridge.UplinkBuffer(maxBytes = 640)
        // The capture loop stopped reading (settings in front) while 2 s of audio arrived.
        repeat(200) { i -> buffer.write(ByteArray(320) { i.toByte() }, 320) }
        val frame = ByteArray(320)
        assertEquals(320, buffer.read(frame))
        assertArrayEquals(ByteArray(320) { 198.toByte() }, frame)
        assertEquals(320, buffer.read(frame))
        assertArrayEquals(ByteArray(320) { 199.toByte() }, frame)
        // Nothing left: no burst of the stalled audio, just "nothing yet".
        assertEquals(0, buffer.read(frame))
    }

    @Test
    fun aClosedSocketEndsTheSource() {
        val buffer = HostAudioBridge.UplinkBuffer()
        buffer.write(ByteArray(100), 100)
        buffer.close()
        assertEquals(-1, buffer.read(ByteArray(320)))
    }
}
