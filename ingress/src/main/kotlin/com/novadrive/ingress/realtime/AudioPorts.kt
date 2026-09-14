package com.novadrive.ingress.realtime

fun interface SessionClock {
    fun nowMs(): Long
}

object SystemSessionClock : SessionClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class FakeClock(
    private var millis: Long = 0L,
) : SessionClock {
    override fun nowMs(): Long = millis

    fun advance(deltaMs: Long) {
        millis += deltaMs
    }

    fun set(nowMs: Long) {
        millis = nowMs
    }
}

fun interface MicrophonePort {
    fun start(onFrame: (ByteArray) -> Unit)

    fun stop() {}

    var muted: Boolean
        get() = false
        set(_) {}
}

interface PlaybackPort {
    fun start()

    fun enqueue(pcm16le: ByteArray)

    fun flush()

    fun stop()

    val queuedFrames: Int
        get() = 0
}

class InMemoryMicrophonePort : MicrophonePort {
    var started: Boolean = false
        private set
    var stopped: Boolean = false
        private set
    var startCount: Int = 0
        private set
    override var muted: Boolean = false
    private var listener: ((ByteArray) -> Unit)? = null
    val forwarded = mutableListOf<ByteArray>()

    override fun start(onFrame: (ByteArray) -> Unit) {
        startCount += 1
        started = true
        stopped = false
        listener = onFrame
    }

    override fun stop() {
        stopped = true
        started = false
        listener = null
    }

    fun emit(frame: ByteArray) {
        if (!muted) {
            forwarded += frame
            listener?.invoke(frame)
        }
    }
}

class InMemoryPlaybackPort : PlaybackPort {
    val played = mutableListOf<ByteArray>()
    var flushCount: Int = 0
        private set
    var started: Boolean = false
        private set
    var stopped: Boolean = false
        private set
    var lastFlushAtMs: Long? = null
    var clock: SessionClock = SystemSessionClock

    override val queuedFrames: Int
        get() = played.size

    override fun start() {
        started = true
        stopped = false
        played.clear()
    }

    override fun enqueue(pcm16le: ByteArray) {
        if (started && !stopped) {
            played += pcm16le
        }
    }

    override fun flush() {
        flushCount += 1
        lastFlushAtMs = clock.nowMs()
        played.clear()
    }

    override fun stop() {
        stopped = true
        started = false
        played.clear()
    }
}
