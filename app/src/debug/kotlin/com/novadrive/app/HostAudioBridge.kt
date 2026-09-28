package com.novadrive.app

import android.util.Log
import com.novadrive.app.voice.HostAudioTap
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
 * One TCP socket to 127.0.0.1:[port]. PC → app: raw 16 kHz mono PCM16LE, paced in real time on
 * the PC by a 20 ms clock and drained here into [UplinkBuffer] whether or not capture is reading. App → PC: `[u32 sampleRate][u32 length][PCM16LE]` for every slice the player
 * has written to its AudioTrack, and `[u32 0][u32 length][UTF-8 text]` (rate 0 = tag) for each Amap
 * guidance prompt, which the PC speaks itself. Frames enter the normal capture path ([HostAudioTap]), so gain,
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
        HostAudioTap.guidanceSink = { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val framed = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            framed.putInt(0).putInt(bytes.size).put(bytes)
            queue.offer(framed.array())
        }
        val uplink = UplinkBuffer()
        // Drains the socket whether or not capture is reading, so the PC is never blocked by an
        // app that stopped listening (P37: settings in front, the bridge script died on a send
        // timeout and the app was left deaf).
        Thread({
            val chunk = ByteArray(1280)
            try {
                while (socket === s) {
                    val n = try {
                        input.read(chunk)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) break
                    uplink.write(chunk, n)
                }
            } catch (_: Exception) {
            }
            uplink.close()
            if (socket === s) stop()
        }, "nova-host-bridge-up").apply { isDaemon = true }.start()
        HostAudioTap.source = uplink
        Log.d(TAG, "host_bridge on port=$port")
        return "on port=$port"
    }

    @Synchronized
    fun stop(): String {
        val s = socket ?: return "off"
        socket = null
        HostAudioTap.source = null
        HostAudioTap.sink = null
        HostAudioTap.guidanceSink = null
        runCatching { s.close() }
        Log.d(TAG, "host_bridge off")
        return "off"
    }

    val status: String get() = if (socket != null) "on" else "off"

    /**
     * PC audio between the socket reader and capture. Holds at most [MAX_BYTES] (120 ms): audio
     * that capture did not take in time is dropped oldest-first, so a stall is never replayed as a
     * burst (P37: speech_started and speech_stopped 2 ms apart). Always whole samples.
     */
    internal class UplinkBuffer(private val maxBytes: Int = MAX_BYTES) : HostAudioTap.Source {
        private val lock = Object()
        private val ring = ByteArray(maxBytes)
        private var head = 0
        private var size = 0
        private var closed = false
        private var frames = 0L
        private var droppedBytes = 0L
        private var lastReadAt = 0L
        private var maxGapMs = 0L

        fun write(src: ByteArray, n: Int) = synchronized(lock) {
            for (i in 0 until n) {
                if (size == maxBytes) {
                    head = (head + 2) % maxBytes
                    size -= 2
                    droppedBytes += 2
                }
                ring[(head + size) % maxBytes] = src[i]
                size++
            }
            lock.notifyAll()
        }

        fun close() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

        override fun read(buf: ByteArray): Int = synchronized(lock) {
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (size < buf.size && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return 0
                lock.wait(left)
            }
            if (size < buf.size) return -1
            for (i in buf.indices) buf[i] = ring[(head + i) % maxBytes]
            head = (head + buf.size) % maxBytes
            size -= buf.size
            val now = System.currentTimeMillis()
            if (lastReadAt != 0L) maxGapMs = maxOf(maxGapMs, now - lastReadAt)
            lastReadAt = now
            if (++frames % 250 == 0L) {
                Log.d(TAG, "host_bridge uplink_frames=$frames dropped_ms=${droppedBytes / 32} max_gap_ms=$maxGapMs")
                maxGapMs = 0
            }
            buf.size
        }

        companion object {
            const val MAX_BYTES = 16_000 * 2 * 120 / 1000
            const val WAIT_MS = 40L
        }
    }
}
