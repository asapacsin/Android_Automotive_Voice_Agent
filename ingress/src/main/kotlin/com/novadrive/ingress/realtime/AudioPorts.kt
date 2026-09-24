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

    /** Starts a session synchronized to the controller's current output epoch. */
    fun start(epoch: Int) = start()

    /** [epoch] must match the session's current [playbackEpoch] or the chunk is dropped. */
    fun enqueue(pcm16le: ByteArray, epoch: Int)

    /** Opens a new reply epoch without interrupting already accepted playout from the prior reply. */
    fun beginReply(epoch: Int) {}

    /** Invalidates all prior output and begins accepting [epoch] only after the flush returns. */
    fun flush(epoch: Int)

    /** Signals that no more PCM belongs to [epoch], allowing a final partial device frame to drain. */
    fun complete(epoch: Int) {}

    fun stop()

    /** PCM16 mono samples waiting in the app queue or the platform track buffer. */
    val queuedFrames: Int
        get() = 0

    val playbackActive: Boolean
        get() = queuedFrames > 0
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
    var droppedEpochMismatch = 0
        private set
    var flushCount: Int = 0
        private set
    var started: Boolean = false
        private set
    var stopped: Boolean = false
        private set
    var lastFlushAtMs: Long? = null
    var clock: SessionClock = SystemSessionClock
    /** Simulates [AudioTrack] buffer still draining after the app queue is empty. */
    var trackBufferedFrames: Int = 0

    override val queuedFrames: Int
        get() = played.sumOf { it.size / 2 } + trackBufferedFrames

    override val playbackActive: Boolean
        get() = queuedFrames > 0

    override fun start() {
        started = true
        stopped = false
        played.clear()
        trackBufferedFrames = 0
        completedThroughEpoch = Int.MIN_VALUE
    }

    override fun start(epoch: Int) {
        start()
        acceptEpoch = epoch
        completedThroughEpoch = epoch - 1
    }

    override fun enqueue(pcm16le: ByteArray, epoch: Int) {
        if (!started || stopped || epoch <= completedThroughEpoch) return
        if (epoch != acceptEpoch) {
            droppedEpochMismatch += 1
            return
        }
        played += pcm16le
    }

    private var acceptEpoch = 0
    private var completedThroughEpoch = Int.MIN_VALUE

    override fun beginReply(epoch: Int) {
        if (started && !stopped) {
            acceptEpoch = epoch
        }
    }

    override fun flush(epoch: Int) {
        flushCount += 1
        lastFlushAtMs = clock.nowMs()
        played.clear()
        trackBufferedFrames = 0
        acceptEpoch = epoch
        completedThroughEpoch = maxOf(completedThroughEpoch, epoch - 1)
    }

    override fun complete(epoch: Int) {
        if (started && !stopped && epoch == acceptEpoch) completedThroughEpoch = maxOf(completedThroughEpoch, epoch)
    }

    override fun stop() {
        stopped = true
        started = false
        played.clear()
        trackBufferedFrames = 0
        acceptEpoch = 0
        completedThroughEpoch = Int.MIN_VALUE
    }
}
