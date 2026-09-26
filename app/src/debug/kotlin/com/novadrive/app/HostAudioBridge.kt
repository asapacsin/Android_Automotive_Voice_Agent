package com.novadrive.app

import android.util.Log
import com.novadrive.app.voice.HostAudioTap
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Debug only: talk to the app with the PC's microphone and hear it on the PC's speakers, for the
 * x86 emulator whose host-mic bridge chops speech (docs/EMULATOR_TESTING.md). The PC side is
 * `tools/speech-harness/host_audio_bridge.py`, reached through `adb reverse`.
 *
 * One TCP socket to 127.0.0.1:[port]. PC → app: raw 16 kHz mono PCM16LE, paced in real time by
 * the PC's capture. App → PC: `[u32 sampleRate][u32 length][PCM16LE]` for every slice the player
 * has written to its AudioTrack. Frames enter the normal capture path ([HostAudioTap]), so gain,
 * gating, mute and turn handling are exactly those of the live microphone.
 */
object HostAudioBridge {
    private const val TAG = "NovaVoice"
    @Volatile private var socket: Socket? = null

    @Synchronized
    fun start(port: Int): String {
        stop()
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 3000)
        s.soTimeout = 250
        s.tcpNoDelay = true
        socket = s
        val input = s.getInputStream()
        val output = s.getOutputStream()
        val queue = LinkedBlockingQueue<ByteArray>(2000)
        var downBytes = 0L
        Thread({
            try {
                while (socket === s) {
                    val chunk = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    output.write(chunk)
                    downBytes += chunk.size - 8
                }
            } catch (e: Exception) {
                Log.d(TAG, "host_bridge downlink_closed bytes=$downBytes")
                if (socket === s) stop()
            }
        }, "nova-host-bridge-down").apply { isDaemon = true }.start()
        HostAudioTap.sink = { pcm, rate ->
            val framed = ByteBuffer.allocate(8 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            framed.putInt(rate).putInt(pcm.size).put(pcm)
            queue.offer(framed.array())
        }
        HostAudioTap.source = SocketSource(input) { socket === s }
        Log.d(TAG, "host_bridge on port=$port")
        return "on port=$port"
    }

    @Synchronized
    fun stop(): String {
        val s = socket ?: return "off"
        socket = null
        HostAudioTap.source = null
        HostAudioTap.sink = null
        runCatching { s.close() }
        Log.d(TAG, "host_bridge off")
        return "off"
    }

    val status: String get() = if (socket != null) "on" else "off"

    /** Keeps a partial frame across read timeouts so no sample is ever dropped or reordered. */
    private class SocketSource(private val input: InputStream, private val alive: () -> Boolean) :
        HostAudioTap.Source {
        private var pending = ByteArray(0)
        private var filled = 0
        private var frames = 0L

        override fun read(buf: ByteArray): Int {
            if (!alive()) return -1
            if (pending.size != buf.size) {
                pending = ByteArray(buf.size)
                filled = 0
            }
            try {
                while (filled < pending.size) {
                    val n = input.read(pending, filled, pending.size - filled)
                    if (n < 0) {
                        stop()
                        return -1
                    }
                    filled += n
                }
            } catch (_: SocketTimeoutException) {
                return 0
            } catch (_: Exception) {
                stop()
                return -1
            }
            System.arraycopy(pending, 0, buf, 0, buf.size)
            filled = 0
            if (++frames % 250 == 0L) Log.d(TAG, "host_bridge uplink_frames=$frames")
            return buf.size
        }
    }
}
