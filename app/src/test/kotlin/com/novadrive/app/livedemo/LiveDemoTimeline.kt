package com.novadrive.app.livedemo

import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.PlaybackPort
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The JVM twin of record_demo.py's `Session`: driver clips go up in real time, reply audio is placed
 * on a timeline the way a player would play it (the same cursor logic as `downlink`), and the same
 * timeline.json / reply.wav / driver.wav come out, so check_req.py grades a live run unchanged.
 * Times are seconds since [t0] (epoch seconds), like the recorder's video clock.
 */
class LiveDemoTimeline(private val out: File, private val clips: File) {
    private val lock = Object()
    @Volatile var t0: Double = 0.0
        private set
    private val pending = java.io.ByteArrayOutputStream()
    private var pendingPos = 0
    private val clipEvents = mutableListOf<Pair<Double, String>>()
    private val replies = mutableListOf<Triple<Double, Int, ByteArray>>()
    @Volatile var cursor = 0.0
        private set
    @Volatile var lastArrival = 0.0
        private set
    @Volatile var replyBytes = 0L
        private set
    val events = JSONArray()
    val wakes = mutableListOf<Double>()

    fun epochNow(): Double = System.currentTimeMillis() / 1000.0
    fun start() { t0 = epochNow() }
    fun now(): Double = epochNow() - t0

    fun clipPcm(key: String): ByteArray = File(clips, "$key.pcm").readBytes()

    // ---- uplink: 20 ms frames of 16 kHz s16le, silence when nothing is queued ----

    fun nextFrame(): ByteArray = synchronized(lock) {
        val bytes = pending.toByteArray()
        val frame = ByteArray(FRAME)
        val n = minOf(FRAME, bytes.size - pendingPos)
        if (n > 0) System.arraycopy(bytes, pendingPos, frame, 0, n)
        pendingPos += maxOf(0, n)
        if (pendingPos >= bytes.size && bytes.isNotEmpty()) {
            pending.reset()
            pendingPos = 0
        }
        frame
    }

    /** Queues [key]; returns its start on the timeline (where the queue is now). */
    fun queueClip(key: String): Double = synchronized(lock) {
        val pcm = clipPcm(key)
        val queued = pending.size() - pendingPos
        val t = now() + queued / 2.0 / RATE
        pending.write(pcm)
        clipEvents += t to key
        t
    }

    // ---- downlink ----

    /** The playback port: each chunk starts where the previous one ends, or now if the player ran dry. */
    inner class Playback(private val rate: Int) : PlaybackPort {
        @Volatile private var acceptFrom = 0
        override fun start() = Unit
        override fun enqueue(pcm16le: ByteArray, epoch: Int) {
            if (epoch < acceptFrom || t0 == 0.0) return
            val t = now()
            synchronized(lock) {
                val start = maxOf(t, cursor)
                replies += Triple(start, rate, pcm16le.copyOf())
                cursor = start + pcm16le.size / 2.0 / rate
                lastArrival = t
                replyBytes += pcm16le.size
            }
        }
        override fun flush(epoch: Int) {
            acceptFrom = epoch
            val t = now()
            synchronized(lock) {
                // What had not been played yet is never heard.
                val kept = replies.filter { it.first < t }.map { (s, r, pcm) ->
                    val maxBytes = (((t - s) * r).toInt() * 2).coerceAtMost(pcm.size)
                    Triple(s, r, pcm.copyOf(maxBytes))
                }
                replies.clear()
                replies += kept
                cursor = minOf(cursor, t)
            }
            DebugVoiceLog.log("playback_flush epoch=$epoch")
        }
        override fun stop() = Unit
        override val queuedFrames: Int get() = maxOf(0.0, (cursor - now()) * rate).toInt()
        val playing: Boolean get() = cursor > now()
    }

    /** record_demo.wait_reply: until a reply has played out and nothing new arrived for [quiet] s. */
    fun waitReply(quiet: Double = 1.8, timeout: Double = 35.0, firstTimeout: Double = 20.0): Boolean {
        val begin = now()
        val startBytes = replyBytes
        while (true) {
            Thread.sleep(200)
            val t = now()
            val got = replyBytes > startBytes
            if (got && t > cursor + quiet && t - lastArrival > quiet) return true
            if (!got && t - begin > firstTimeout) {
                println("[live]   no reply")
                return false
            }
            if (t - begin > timeout) {
                println("[live]   reply timeout")
                return got
            }
        }
    }

    fun title(text: String, sub: String) {
        val t = now()
        events.put(JSONObject().put("t", t).put("title", text).put("sub", sub))
        println(String.format(Locale.US, "[live] %7.2f == %s", t, text))
    }

    fun mark(kind: String) {
        events.put(JSONObject().put("t", now()).put(kind, true))
    }

    val clipList: List<Pair<Double, String>> get() = synchronized(lock) { clipEvents.toList() }

    // ---- output ----

    fun render(end: Double) {
        val n = (end * OUT_RATE).toInt() + OUT_RATE
        val reply = FloatArray(n)
        synchronized(lock) {
            for ((start, rate, pcm) in replies) mix(reply, toFloat(pcm), rate, start)
            val driver = FloatArray(n)
            for ((t, key) in clipEvents) mix(driver, toFloat(clipPcm(key)), RATE, t)
            writeWav(File(out, "reply.wav"), reply)
            writeWav(File(out, "driver.wav"), driver)
            val json = JSONObject()
                .put("clips", JSONArray(clipEvents.map { JSONArray().put(it.first).put(it.second) }))
                .put("events", events)
                .put("guidance", JSONArray())
                .put("end", end)
                .put("t0", t0)
                .put("wakes", JSONArray(wakes))
                .put("reply_segments", replies.size)
                .put("noise", JSONArray())
            File(out, "timeline.json").writeText(json.toString(1), Charsets.UTF_8)
        }
    }

    private fun toFloat(pcm: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(b.remaining()) { b.get(it).toFloat() }
    }

    /** Linear resampling to [OUT_RATE], as record_demo's np.interp. */
    private fun mix(dst: FloatArray, x: FloatArray, rate: Int, start: Double) {
        if (x.isEmpty()) return
        val m = (x.size.toLong() * OUT_RATE / rate).toInt()
        val i0 = (start * OUT_RATE).toInt()
        for (k in 0 until m) {
            val i = i0 + k
            if (i < 0) continue
            if (i >= dst.size) break
            val pos = if (m <= 1) 0.0 else k.toDouble() * (x.size - 1) / (m - 1)
            val a = pos.toInt()
            val f = (pos - a).toFloat()
            val v = if (a + 1 < x.size) x[a] * (1 - f) + x[a + 1] * f else x[a]
            dst[i] += v
        }
    }

    private fun writeWav(file: File, data: FloatArray) {
        val bytes = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { bytes.putShort(it.coerceIn(-32768f, 32767f).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(OUT_RATE); putInt(OUT_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(data.size * 2)
        }
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(header.array())
            f.write(bytes.array())
        }
    }

    companion object {
        const val RATE = 16_000
        const val FRAME = RATE * 2 / 50
        const val OUT_RATE = 24_000
    }
}
