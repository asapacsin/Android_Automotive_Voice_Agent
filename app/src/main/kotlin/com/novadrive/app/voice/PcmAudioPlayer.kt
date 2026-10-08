package com.novadrive.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.AudioBufferGuard
import com.novadrive.ingress.realtime.BoundedThreadCleanup
import com.novadrive.ingress.realtime.PlaybackEpochEngine
import com.novadrive.ingress.realtime.PlaybackPort
import com.novadrive.ingress.realtime.mayStartAudioWorker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * PCM16 mono playback. The rate is configured per session from what the provider actually
 * returns (`resolvedOutputSampleRateHz`), not assumed: Baidu Flex and Lite differ.
 * Hardware routing, Bluetooth SCO, and AEC remain manual-test-only.
 */
class PcmAudioPlayer(
    private val onError: (String) -> Unit,
) {
    private val epochEngine = PlaybackEpochEngine()
    private val running = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var sampleRateHz: Int = PcmAudioCapture.SAMPLE_RATE
    @Volatile private var audioSessionId: Int = VoiceAudioSession.SESSION_ID_GENERATE
    private val stateLock = Any()
    /** Serializes queue extraction, track writes, and flush acknowledgement. */
    private val outputLock = Any()
    private enum class SliceResult { CONTINUE, RETRY, STOP }
    @Volatile private var speaking = false
    @Volatile private var playbackPaused = false
    @Volatile private var framesWritten: Long = 0
    @Volatile private var headOrigin: Long = 0
    @Volatile private var timestampAvailable = false
    private var latencyBuffer: LowLatencyPlaybackBuffer? = null
    private val playbackStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val playbackActiveListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var lastNotifiedPlaybackActive = false

    val isPlaying: Boolean
        get() = runCatching {
            running.get() && synchronized(outputLock) {
                speaking || epochEngine.queuedBytes() > 0
            }
        }.getOrDefault(false)

    fun setOnPlaybackStateChanged(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            playbackStateListeners.clear()
        } else {
            playbackStateListeners.add(listener)
        }
    }

    fun setOnPlaybackActiveChanged(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            playbackActiveListeners.clear()
        } else {
            playbackActiveListeners.add(listener)
        }
    }

    fun configureSampleRate(sampleRateHz: Int) {
        require(sampleRateHz in 8_000..48_000) { "AUDIO_SAMPLE_RATE_INVALID" }
        if (running.get()) return
        this.sampleRateHz = sampleRateHz
        epochEngine.configureSampleRate(sampleRateHz)
    }

    fun configureAudioSession(sessionId: Int) {
        if (running.get()) return
        audioSessionId = sessionId
    }

    val playbackSessionId: Int
        get() = track?.audioSessionId ?: audioSessionId

    fun start(epoch: Int = 0) {
        synchronized(outputLock) {
            val previousWorker = worker
            if (!mayStartAudioWorker(previousWorker?.isAlive == true, running.get())) {
                fail("worker_overlap")
                return
            }
            if (previousWorker?.isAlive == true) {
                return
            }
            if (previousWorker != null) {
                releaseTrackLocked()
                worker = null
            }
        }
        if (!running.compareAndSet(false, true)) return
        val minBuf =
            try {
                AudioBufferGuard.requireValidMinBuffer(
                    AudioTrack.getMinBufferSize(
                        sampleRateHz,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    "AUDIO_PLAYBACK_FAILED",
                )
            } catch (_: IllegalArgumentException) {
                running.set(false)
                fail("min_buffer")
                return
            }
        val bufferManager = LowLatencyPlaybackBuffer(sampleRateHz, minBuf)
        latencyBuffer = bufferManager
        val player =
            try {
                val builder =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRateHz)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferManager.capacityBytes())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                VoiceAudioSession.applyToTrackBuilder(builder, audioSessionId)
                builder.build()
            } catch (_: Exception) {
                running.set(false)
                fail("track_build")
                return
            }
        playbackPaused = false
        synchronized(outputLock) {
            epochEngine.resetForStart(epoch)
        }
        framesWritten = 0
        headOrigin = 0
        timestampAvailable = false
        try {
            player.play()
        } catch (_: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
            running.set(false)
            fail("track_play")
            return
        }
        bufferManager.reset(player)
        track = player
        VoiceAudioSession.recordPlaybackSession(player.audioSessionId)
        DebugVoiceLog.log(
            "playback_start playbackSessionId=${player.audioSessionId} captureSessionId=${VoiceAudioSession.captureSessionId} " +
                "sessions_match=${VoiceAudioSession.sessionsMatch()} playback_buffer_ms=${bufferManager.bufferMs}",
        )
        worker =
            thread(name = "nova-pcm-play", isDaemon = true) {
                val sliceBytes = sampleRateHz * 2 / 100
                val slice = ByteArray(sliceBytes)
                while (running.get()) {
                    val result =
                        synchronized(outputLock) {
                            if (running.get()) processSliceUnderLock(player, bufferManager, sliceBytes, slice)
                            else SliceResult.STOP
                        }
                    if (result == SliceResult.STOP) break
                    if (result == SliceResult.RETRY) {
                        try {
                            Thread.sleep(2)
                        } catch (_: InterruptedException) {
                            if (!running.get()) break
                        }
                    }
                }
            }
    }

    /** Must be called with [outputLock] held so a flush cannot acknowledge before this write ends. */
    private fun processSliceUnderLock(
        player: AudioTrack,
        bufferManager: LowLatencyPlaybackBuffer,
        sliceBytes: Int,
        slice: ByteArray,
    ): SliceResult {
        if (playbackPaused) return SliceResult.RETRY
        if (epochEngine.pendingSlice == null && epochEngine.stagePendingSlice(sliceBytes, slice)) {
            // staged on engine
        }
        val pending = epochEngine.pendingSlice
        if (pending == null) {
            // Nothing to write: the track running dry now says nothing about its buffer size.
            bufferManager.onStarved(player)
            emitIdleIfDrained()
            publishPlayoutDelay(player, null)
            return SliceResult.RETRY
        }
        val written =
            try {
                player.write(
                    pending,
                    epochEngine.pendingSliceOffset,
                    pending.size - epochEngine.pendingSliceOffset,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
            } catch (_: Exception) {
                fail("write_threw")
                return SliceResult.STOP
            }
        if (written < 0) {
            fail("write_error")
            return SliceResult.STOP
        }
        if (written == 0) return SliceResult.RETRY
        if (written % 2 != 0) {
            fail("write_odd_bytes")
            return SliceResult.STOP
        }

        VoiceAec.instance?.let { aec ->
            if (aec.isAvailable) {
                val accepted = pending.copyOfRange(epochEngine.pendingSliceOffset, epochEngine.pendingSliceOffset + written)
                if (!aec.processRender(accepted, sampleRateHz)) {
                    fail("aec_render")
                    return SliceResult.STOP
                }
            }
        }
        HostAudioTap.sink?.let { sink ->
            sink(pending.copyOfRange(epochEngine.pendingSliceOffset, epochEngine.pendingSliceOffset + written), sampleRateHz)
        }
        epochEngine.acceptShortWrite(written)
        framesWritten += written / 2
        val clock = renderClock(player)
        val playoutDelayMs = clock?.let { EchoDelayEstimator.renderLatency(it, System.nanoTime()).first }
        publishPlayoutDelay(player, playoutDelayMs, clock)
        bufferManager.afterWrite(player)
        VoiceAec.instance?.let { aec ->
            if (aec.isAvailable) {
                AecMetrics.maybeLog(
                    playbackActive = playbackActive,
                    streamDelayMs = playoutDelayMs,
                    stats = aec.snapshotStats(),
                    appQueueMs = appQueueDelayMs(),
                    trackBufferMs = bufferManager.bufferMs,
                    underrunCount = underrunCount(player),
                )
            }
        }
        return SliceResult.CONTINUE
    }

    fun enqueue(pcm16le: ByteArray, epoch: Int) {
        synchronized(outputLock) {
            when (epochEngine.enqueue(pcm16le, epoch, running.get())) {
                com.novadrive.ingress.realtime.PlaybackEnqueueResult.Accepted -> Unit
                com.novadrive.ingress.realtime.PlaybackEnqueueResult.OverflowFailed -> {
                    failReplyLocked(epoch)
                    return
                }
                else -> return
            }
        }
        emitSpeaking(true)
        notifyPlaybackActiveIfChanged()
    }

    val queuedFrames: Int
        get() = synchronized(outputLock) {
            val queued = epochEngine.queuedBytes() / 2
            val track = track ?: return queued
            return try {
                val head = playbackHeadFrames(track) ?: return queued
                val played = (head - headOrigin).coerceAtLeast(0)
                val buffered = (framesWritten - played).coerceAtLeast(0)
                (queued + buffered).toInt()
            } catch (_: Exception) {
                queued
            }
        }

    val playbackActive: Boolean
        get() = queuedFrames > 0 || speaking

    @Volatile
    var duckCount: Int = 0
        private set

    fun duck() {
        synchronized(outputLock) {
            duckCount++
            try {
                track?.setVolume(0.2f)
            } catch (_: Exception) {
            }
        }
    }

    @Volatile
    var unduckCount: Int = 0
        private set

    fun unduck() {
        synchronized(outputLock) {
            unduckCount++
            try {
                track?.setVolume(1.0f)
            } catch (_: Exception) {
            }
        }
    }

    fun pausePlayback() {
        synchronized(outputLock) {
            try {
                playbackPaused = true
                track?.pause()
            } catch (_: Exception) {
            }
        }
    }

    fun resumePlayback() {
        synchronized(outputLock) {
            try {
                playbackPaused = false
                track?.play()
            } catch (_: Exception) {
            }
        }
    }

    fun flush(epoch: Int) {
        synchronized(outputLock) { flushLocked(epoch) }
    }

    fun beginReply(epoch: Int) {
        synchronized(outputLock) {
            epochEngine.beginReply(epoch)
        }
    }

    fun complete(epoch: Int) {
        synchronized(outputLock) {
            if (!running.get()) return
            epochEngine.complete(epoch)
        }
    }

    /** Audio-focus STOP physically flushes output without changing the ingress epoch. */
    fun flushCurrentEpoch() {
        synchronized(outputLock) { flushLocked(epochEngine.acceptEpoch) }
    }

    private fun flushLocked(epoch: Int) {
        epochEngine.flush(epoch)
        track?.pause()
        track?.flush()
        framesWritten = 0
        headOrigin = track?.let { playbackHeadFrames(it) ?: 0L } ?: 0L
        timestampAvailable = false
        if (!playbackPaused) {
            track?.play()
        }
        emitSpeaking(false)
        notifyPlaybackActiveIfChanged()
        publishPlayoutDelay(track, null)
    }

    fun stop() {
        running.set(false)
        synchronized(outputLock) {
            epochEngine.flush(epochEngine.acceptEpoch)
            track?.run {
                try {
                    pause()
                    flush()
                    stop()
                } catch (_: Exception) {
                }
            }
        }
        latencyBuffer = null
        playbackPaused = false
        framesWritten = 0
        headOrigin = 0
        timestampAvailable = false
        VoicePlayoutDelay.clear()
        val toJoin = worker
        val joined = toJoin !== Thread.currentThread() && BoundedThreadCleanup.terminate(toJoin)
        if (joined) {
            synchronized(outputLock) {
                releaseTrackLocked()
                worker = null
            }
        } else if (toJoin != null) {
            thread(name = "nova-pcm-play-cleanup", isDaemon = true) {
                try {
                    toJoin.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                synchronized(outputLock) {
                    if (worker === toJoin) {
                        releaseTrackLocked()
                        worker = null
                    }
                }
            }
        } else {
            synchronized(outputLock) {
                releaseTrackLocked()
                worker = null
            }
        }
        emitSpeaking(false)
        notifyPlaybackActiveIfChanged()
    }

    private fun releaseTrackLocked() {
        track?.run {
            try {
                release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

    /**
     * Every playback failure reaches the screen as the same `AUDIO_PLAYBACK_FAILED`; the debug log
     * says which step failed, so a "playback failed" banner can be traced to its cause.
     */
    private fun fail(site: String) {
        DebugVoiceLog.log("playback_error code=AUDIO_PLAYBACK_FAILED site=$site")
        onError("AUDIO_PLAYBACK_FAILED")
    }

    /** Overflow or explicit failure for the current reply epoch; ingress still owns the next epoch. */
    private fun failReplyLocked(epoch: Int) {
        epochEngine.failReply(epoch)
        track?.pause()
        track?.flush()
        framesWritten = 0
        headOrigin = track?.let { playbackHeadFrames(it) ?: 0L } ?: 0L
        timestampAvailable = false
        if (!playbackPaused) {
            track?.play()
        }
        emitSpeaking(false)
        notifyPlaybackActiveIfChanged()
        publishPlayoutDelay(track, null)
        fail("queue_overflow")
    }

    /**
     * Where the output clock stands, for [EchoDelayEstimator]: the presented frame and the
     * monotonic time it was presented when the platform timestamps it, else the head position.
     */
    private fun renderClock(player: AudioTrack): EchoDelayEstimator.RenderClock? {
        if (framesWritten == 0L) return null
        val stamp = AudioTimestamp()
        val stamped = try {
            player.getTimestamp(stamp)
        } catch (_: Exception) {
            false
        }
        if (stamped) {
            timestampAvailable = true
            return EchoDelayEstimator.RenderClock(
                framesWritten, (stamp.framePosition - headOrigin).coerceAtLeast(0), stamp.nanoTime, sampleRateHz,
            )
        }
        val head = try {
            player.playbackHeadPosition.toLong() and 0xffffffffL
        } catch (_: Exception) {
            return null
        }
        return EchoDelayEstimator.RenderClock(framesWritten, (head - headOrigin).coerceAtLeast(0), null, sampleRateHz)
    }

    private fun appQueueDelayMs(): Int =
        com.novadrive.ingress.realtime.AppPlaybackQueuePolicy.pendingMs(
            epochEngine.queuedBytes(),
            sampleRateHz,
        )

    private fun publishPlayoutDelay(
        player: AudioTrack?,
        playoutDelayMs: Int?,
        renderClock: EchoDelayEstimator.RenderClock? = null,
    ) {
        val bufferMs = latencyBuffer?.bufferMs ?: 0
        VoicePlayoutDelay.publish(
            VoicePlayoutDelay.Snapshot(
                playoutDelayMs = playoutDelayMs,
                appQueueMs = appQueueDelayMs(),
                trackBufferMs = bufferMs,
                underrunCount = if (player != null) underrunCount(player) else 0,
                outputSampleRateHz = sampleRateHz,
                playbackActive = playbackActive,
                renderClock = renderClock,
            ),
        )
    }

    private fun underrunCount(player: AudioTrack): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player.underrunCount else 0

    private fun playbackHeadFrames(player: AudioTrack): Long? {
        val stamp = AudioTimestamp()
        return try {
            if (player.getTimestamp(stamp)) {
                timestampAvailable = true
                stamp.framePosition
            } else {
                player.playbackHeadPosition.toLong() and 0xffffffffL
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyPlaybackActiveIfChanged() {
        val now = playbackActive
        if (now == lastNotifiedPlaybackActive) return
        lastNotifiedPlaybackActive = now
        val listeners = playbackActiveListeners.toTypedArray()
        for (listener in listeners) {
            try {
                listener(now)
            } catch (_: Exception) {
            }
        }
    }

    private fun emitSpeaking(nowSpeaking: Boolean) {
        synchronized(stateLock) {
            if (speaking == nowSpeaking) return
            speaking = nowSpeaking
        }
        dispatchPlaybackState(nowSpeaking)
    }

    private fun emitIdleIfDrained() {
        if (epochEngine.queuedBytes() > 0 || queuedFrames > 0) return
        synchronized(stateLock) {
            if (epochEngine.queuedBytes() > 0) return
            if (!speaking) return
            speaking = false
        }
        dispatchPlaybackState(false)
        notifyPlaybackActiveIfChanged()
    }

    private fun dispatchPlaybackState(nowSpeaking: Boolean) {
        val listeners = playbackStateListeners.toTypedArray()
        synchronized(stateLock) {
            if (speaking != nowSpeaking) return
        }
        for (listener in listeners) {
            try {
                listener(nowSpeaking)
            } catch (_: Exception) {
            }
        }
    }
}

class AndroidPlaybackPort(
    private val player: PcmAudioPlayer,
    private val focus: AudioFocusController? = null,
    /** False unless the lifecycle is ACTIVE: a reply after 「闭嘴」 or 「休眠」 is not played. */
    private val playbackAllowed: () -> Boolean = { true },
) : PlaybackPort {
    override val queuedFrames: Int
        get() = player.queuedFrames

    override val playbackActive: Boolean
        get() = player.playbackActive

    /** SPEC-018: the app prompt whose audio this port is routing or playing. */
    private val guidance = GuidancePromptTracker(abandon = { id -> SpeechAuthority.arbiter.abandon(id) })

    /** An assistant guidance prompt is open or still playing: cancel paths must spare it. */
    val guidanceActive: Boolean get() = guidance.active

    init {
        player.setOnPlaybackStateChanged { speaking ->
            if (speaking) {
                focus?.requestSpeechFocus()
            } else {
                focus?.abandon()
                guidanceDrainedIfIdle() // R8b: an underrun before COMPLETED is not a drain
            }
        }
        // Single owner: AudioFocusController.onFocusChanged is one slot, not a listener list.
        focus?.onFocusChanged = { change -> applyFocusChange(change) }
        // SPEC-012 D1: the one playback-hold hook; SpeechAuthority.syncPlaybackHold drives it.
        // R8a: a held guidance chunk is claimed as the hold lifts, before it is heard. Refused (Amap
        // took it at its deadline): stay paused; the flush runs outside the hold lock.
        SpeechAuthority.playbackHold = { pause ->
            when {
                pause -> player.pausePlayback()
                guidance.startPlayout() -> player.resumePlayback()
                else -> SpeechAuthority.scheduleRecheck(0L) {
                    refusedGuidance(resume = !SpeechAuthority.arbiter.guidanceHeld()) // held again: stay paused
                }
            }
        }
        // D3: the 8 s workload cap must release even if distance updates stop.
        SpeechAuthority.scheduleRecheck = { delayMs, task ->
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ task() }, delayMs)
            }
        }
    }

    fun applyFocusChange(change: Int) {
        try {
            val arbiter = SpeechAuthority.arbiter
            when (focusAction(change)) {
                FocusAction.DUCK -> {
                    arbiter.onFocus(SpeechArbiter.Focus.DUCK)
                    // R7: a permitted reply during navigation stays at full volume; R8 ducks.
                    if (arbiter.volume() == SpeechArbiter.Volume.DUCK) player.duck()
                }
                FocusAction.PAUSE -> {
                    arbiter.onFocus(SpeechArbiter.Focus.TRANSIENT_LOSS) // R5
                    player.pausePlayback()
                    SpeechAuthority.notePaused()
                }
                FocusAction.STOP -> {
                    arbiter.onFocus(SpeechArbiter.Focus.PERMANENT_LOSS) // R4
                    player.pausePlayback()
                    SpeechAuthority.notePaused()
                    cutGuidance()
                    player.flushCurrentEpoch()
                }
                FocusAction.RESUME -> {
                    arbiter.onFocus(SpeechArbiter.Focus.HELD)
                    player.unduck()
                    SpeechAuthority.syncPlaybackHold() // not through a guidance or workload hold
                }
                FocusAction.NOTHING -> Unit
            }
        } catch (_: Exception) {
        }
    }

    override fun start() {
        player.start()
    }

    override fun start(epoch: Int) {
        player.start(epoch)
    }

    override fun beginReply(epoch: Int) {
        SpeechAuthority.onReplyEnded() // D2: the new reply has not started playing
        player.beginReply(epoch)
    }

    override fun complete(epoch: Int) {
        player.complete(epoch)
    }

    @Volatile private var droppingReply = false
    @Volatile private var droppingForNavigation = false
    @Volatile private var droppingGuidance = false

    override fun onAppPromptTurn(promptId: String, phase: com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase, epoch: Int) {
        val arbiter = SpeechAuthority.arbiter
        when (phase) {
            com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.OPENED -> {
                // B3: not-yet-played chatter is lost; flush(epoch) clears the queue and keeps accepting this epoch.
                cutGuidance()
                player.flush(epoch)
                SpeechAuthority.onReplyEnded()
                guidance.opened(promptId)
                droppingGuidance = false
                arbiter.onAssistantGuidance(promptId, phase)
            }
            com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED -> {
                guidance.completed(promptId) // playing stays set until the audio drains
                arbiter.onAssistantGuidance(promptId, phase)
                if (!player.isPlaying) guidanceDrainedIfIdle()
            }
            com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.VOIDED -> {
                arbiter.onAssistantGuidance(promptId, phase)
                if (guidance.voided(promptId)) {
                    player.flush(epoch)
                    SpeechAuthority.onReplyEnded()
                    guidancePlayoutEnded()
                }
            }
        }
        SpeechAuthority.syncPlaybackHold()
    }

    /** R8b: the drained prompt, only once COMPLETED was seen and nothing of it is queued. */
    private fun guidanceDrainedIfIdle() {
        guidance.drained()?.let { id ->
            guidancePlayoutEnded()
            GuidanceClaims.onGuidanceDrained(id)
        }
    }

    /**
     * R8a: queued guidance is about to be heard (the player is not held) — claim it now. A refused
     * claim means Amap took the sentence at its deadline: the queued audio is thrown away.
     */
    private fun startGuidancePlayout() {
        if (guidance.playing == null || SpeechAuthority.arbiter.guidanceHeld()) return
        if (!guidance.startPlayout()) refusedGuidance()
    }

    private fun refusedGuidance(resume: Boolean = false) {
        player.flushCurrentEpoch()
        if (resume) player.resumePlayback()
        guidancePlayoutEnded()
        com.novadrive.app.DebugVoiceLog.log("guidance_audio_not_played reason=claimed_by_amap")
    }

    /** B2a: guidance audio about to be flushed before it finished — tell the relay, stop the playout. */
    private fun cutGuidance() {
        if (guidance.flushed()) guidancePlayoutEnded()
    }

    private fun guidancePlayoutEnded() {
        SpeechAuthority.arbiter.onGuidancePlayout(false)
        SpeechAuthority.syncPlaybackHold()
    }

    /** SPEC-018 B6: guidance bypasses the listening-state gate; the arbiter's guidance row decides. */
    private fun enqueueGuidance(pcm16le: ByteArray, epoch: Int): Boolean {
        val route = guidance.route { id -> SpeechAuthority.guidanceReply(id) }
        when (route) {
            GuidancePromptTracker.Route.ORDINARY -> return false
            GuidancePromptTracker.Route.DROP -> if (!droppingGuidance) {
                droppingGuidance = true
                com.novadrive.app.DebugVoiceLog.log("guidance_audio_not_played")
            }
            GuidancePromptTracker.Route.QUEUE -> {
                player.unduck()
                SpeechAuthority.arbiter.onGuidancePlayout(true)
                SpeechAuthority.syncPlaybackHold() // HOLD (R1/R5) pauses before the chunk is visible
                player.enqueue(pcm16le, epoch)
                startGuidancePlayout() // no claim while HOLD-paused (R8a)
            }
        }
        return true
    }

    override fun enqueue(pcm16le: ByteArray, epoch: Int) {
        if (enqueueGuidance(pcm16le, epoch)) return
        // SPEC-012: the arbiter alone decides. DROP is R4 (focus lost for good) or R6 (P1); HOLD
        // still queues — the player is paused through SpeechAuthority.syncPlaybackHold until it lifts.
        val arbiter = SpeechAuthority.arbiter
        val decision = SpeechAuthority.reply()
        if (decision == SpeechArbiter.Reply.DROP) {
            if (!droppingForNavigation) {
                droppingForNavigation = true
                val reason = if (arbiter.navigationMuted()) "navigation_unprompted" else "focus_lost"
                com.novadrive.app.DebugVoiceLog.log("reply_audio_not_played reason=$reason")
            }
            return
        }
        droppingForNavigation = false
        if (!playbackAllowed()) {
            SpeechAuthority.onReplyChunk(decision, queued = false)
            if (!droppingReply) {
                droppingReply = true
                com.novadrive.app.DebugVoiceLog.log("reply_audio_not_played reason=not_active")
            }
            return
        }
        droppingReply = false
        // A permitted chunk restores full volume, as before SPEC-012 (the step-2 finding: R8's duck
        // therefore lasts until the next chunk). Kept deliberately to stay behaviour-preserving.
        player.unduck()
        // R6a: a held chunk must never be visible to the writer thread before the player pauses.
        if (decision == SpeechArbiter.Reply.HOLD) SpeechAuthority.syncPlaybackHold()
        player.enqueue(pcm16le, epoch)
        SpeechAuthority.onReplyChunk(decision, queued = true)
        SpeechAuthority.syncPlaybackHold()
    }

    override fun flush(epoch: Int) {
        cutGuidance()
        player.flush(epoch)
        SpeechAuthority.onReplyEnded()
        com.novadrive.evaluation.Telemetry.record(com.novadrive.evaluation.EventType.AUDIO_STOPPED)
    }

    override fun stop() {
        cutGuidance()
        player.stop()
        SpeechAuthority.onReplyEnded()
        // SPEC-018 R2: an open prompt must not route the next session's replies as guidance.
        guidance.reset()
        SpeechAuthority.arbiter.clearGuidance()
        runCatching { GuidanceClaims.onSessionStopped() }
    }
}

/** What losing or regaining audio focus should do to reply playback. */
enum class FocusAction { DUCK, PAUSE, STOP, RESUME, NOTHING }

/**
 * The focus mapping, separated from the player so it can be tested at all.
 *
 * `AndroidPlaybackPort` holds a concrete `PcmAudioPlayer` built on `AudioTrack`, so nothing about
 * this decision could be exercised on the JVM while it lived inside the `when`. The four cases are
 * not interchangeable: ducking under a navigation prompt and flushing a reply because a phone call
 * arrived are different promises to the driver.
 */
fun focusAction(change: Int): FocusAction = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.DUCK
    // Transient: the reply is still wanted, so it pauses rather than being thrown away.
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusAction.PAUSE
    // Permanent - a call, another assistant. What was queued is no longer worth saying.
    AudioManager.AUDIOFOCUS_LOSS -> FocusAction.STOP
    AudioManager.AUDIOFOCUS_GAIN -> FocusAction.RESUME
    else -> FocusAction.NOTHING
}
