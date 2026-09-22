package com.novadrive.app.voice

/**
 * Deterministic local gate on the microphone uplink: sound only reaches Baidu once it has
 * behaved like speech for long enough to be worth a turn.
 *
 * Why this exists. The product streams raw microphone audio to an end-to-end S2S model, so the
 * *server* decides where a turn begins and ends. That means a tap on the dashboard is a turn: on
 * 2026-09-18 a stray noise between commands produced 「怎么回事。」 and the assistant answered
 * 「没听清，再说一遍。」 No tool ran — the action path was never at risk — but the driver heard the
 * assistant talk to nobody.
 *
 * The cheapest honest fix is to not upload the tap at all. A click or a chair scrape is loud but
 * *brief*; speech sustains. So a frame only leaves the phone once [minOnsetFrames] consecutive
 * frames have carried voice-like energy, and the [preRollFrames] before that are sent with it so
 * no onset is clipped.
 *
 * What this deliberately does **not** do:
 * - it does not raise the server VAD threshold (that deafens quiet speech — measured, see
 *   [MicInputGain]);
 * - it does not judge *content*: no keywords, no transcript, no second recogniser;
 * - it does not end turns. Once open it keeps streaming through pauses for [hangoverMs], which is
 *   far longer than the server's own `silence_duration_ms` of 200 ms, so the server still sees the
 *   trailing silence it needs to close a turn, and a 1–2 second pause mid-sentence never truncates
 *   the driver. If the gate does close during a long pause, the continuation re-opens on its own
 *   onset and the pre-roll covers its first 300 ms.
 *
 * Thresholds are frame-count based and derived from the 100 ms capture frame, so they hold
 * whatever the buffer size is. Loudness is judged against an adaptive noise floor, not a fixed
 * number, so a quiet cabin and a noisy one both work.
 *
 * All of it is pure arithmetic over PCM16: no Android, no clock, unit-testable frame by frame.
 *
 * Thread-safe. Three callers touch it: the capture thread, the debug injection coroutine, and the
 * realtime client reading [snapshot] when a response begins. Unsynchronised, their interleaving
 * wiped the onset counter between frames and real speech was rejected as a run of impulses
 * (measured 2026-09-18).
 */
class SpeechUplinkGate(
    private val frameMs: Int = DEFAULT_FRAME_MS,
    private val minOnsetFrames: Int = MIN_ONSET_FRAMES,
    private val preRollFrames: Int = PRE_ROLL_FRAMES,
    private val hangoverMs: Int = HANGOVER_MS,
    private val absoluteRmsFloor: Int = ABSOLUTE_RMS_FLOOR,
    private val noiseFactor: Double = NOISE_FACTOR,
) {
    /** What the caller should do with the frames it just handed over. */
    data class Decision(
        /** Frames to send, in order. Empty while the gate is closed. */
        val send: List<ByteArray>,
        /** Non-null when a segment just ended: its measured shape, for the phantom-turn gate. */
        val finished: Segment? = null,
        /** Non-null when sound was heard but never earned an upload (a tap, a click, a knock). */
        val rejected: Rejection? = null,
    )

    /** A stretch of uploaded audio, measured locally. Durations only — never content. */
    data class Segment(val durationMs: Int, val voicedFrames: Int, val peak: Int) {
        /** Percent of the segment's duration that carried voice-like energy. */
        val voicedRatio: Double
            get() = if (durationMs <= 0) 0.0 else voicedFrames * DEFAULT_FRAME_MS * 100.0 / durationMs

        /**
         * Too brief or too sparse to be a sentence. Not "this is noise" — it is "if the model
         * answers this with nothing useful, do not say it out loud". See [PhantomTurnGate].
         */
        fun isSuspicious(
            minDurationMs: Int = SUSPICIOUS_BELOW_MS,
            minVoicedRatio: Double = SUSPICIOUS_VOICED_RATIO,
        ): Boolean = durationMs < minDurationMs || voicedRatio < minVoicedRatio

        /**
         * Whether the reply to this input should be held until the turn can be judged. A wider
         * net than [isSuspicious] on purpose: holding costs nothing when the turn turns out to be
         * real, because the hold is released the moment the driver's transcript, a tool call or a
         * substantive reply arrives — typically a couple of hundred milliseconds in.
         *
         * Measured on device 2026-09-18: a 400 ms chair scrape produced a 500 ms segment with an
         * 80 % voiced ratio, so acoustics alone called it speech. It was the *absence of a user
         * transcript* that gave it away, and that is only known after the reply has started.
         */
        fun needsHold(): Boolean = durationMs < HOLD_BELOW_MS || voicedRatio < HOLD_VOICED_RATIO
    }

    /** Sound that never became an upload, so the model never saw it. */
    data class Rejection(val voicedFrames: Int, val peak: Int) {
        val durationMs: Int get() = voicedFrames * DEFAULT_FRAME_MS
    }

    private val preRoll = ArrayDeque<ByteArray>()
    private var open = false
    private var consecutiveVoiced = 0
    private var silentMsWhileOpen = 0

    // Segment accounting, reset on every open.
    private var segmentMs = 0
    private var segmentVoiced = 0
    private var segmentPeak = 0

    /**
     * Where the last voiced frame sat inside the segment. The segment is measured up to here and
     * not to the end: the trailing hangover is silence we send so the server can close the turn,
     * and counting it would make every short real command look sparse.
     */
    private var voicedSpanMs = 0

    // Voiced frames seen while closed that never reached onset — the taps and clicks.
    private var strayVoiced = 0
    private var strayPeak = 0

    /** Slow-moving estimate of the room. Starts optimistic; only quiet frames move it. */
    private var noiseRms = INITIAL_NOISE_RMS

    private var lastFinished: Segment? = null

    /**
     * The shape of the turn being judged right now.
     *
     * Measured on device 2026-09-18: a segment is only *closed* after the 1.2 s hangover, but the
     * server creates its response about 0.5 s after speech stops — so a caller that waited for the
     * closed segment judged every turn against the **previous** one's audio. While the gate is
     * open this reports the utterance so far, which by then is the whole utterance: the span is
     * measured to the last voiced frame, and only hangover silence follows.
     */
    @Synchronized
    fun snapshot(): Segment? =
        if (open) Segment(durationMs = voicedSpanMs, voicedFrames = segmentVoiced, peak = segmentPeak)
        else lastFinished

    val isOpen: Boolean
        @Synchronized get() = open

    /** Diagnostics: RMS of the most recent frame offered to the gate. */
    val lastFrameRms: Int
        @Synchronized get() = lastRms

    /** Diagnostics: the current adaptive threshold a frame must beat to count as voiced. */
    val voicedThreshold: Int
        @Synchronized get() = maxOf(absoluteRmsFloor, (noiseRms * noiseFactor).toInt())

    private var lastRms = 0

    /**
     * The microphone was gated (the assistant was speaking, or navigation guidance was) and has
     * just reopened. Residual audio in the room must not count towards an onset, so the counter
     * starts from zero and the pre-roll is dropped. Barge-in is unaffected: it is the gating
     * itself, not this reset, that decides whether the driver is heard while the assistant talks.
     */
    @Synchronized
    fun onCaptureInterrupted() {
        preRoll.clear()
        consecutiveVoiced = 0
        strayVoiced = 0
        strayPeak = 0
        if (open) closeSegment()
    }

    @Synchronized
    fun reset() {
        onCaptureInterrupted()
        noiseRms = INITIAL_NOISE_RMS
        silentMsWhileOpen = 0
    }

    @Synchronized
    fun offer(frame: ByteArray): Decision {
        val rms = rms(frame)
        lastRms = rms
        val peak = peakAbs(frame)
        val voiced = rms >= voicedThreshold
        if (!voiced) {
            // Only quiet frames teach the noise floor, so a long sentence cannot raise it until
            // the speaker is talking to a wall.
            noiseRms = noiseRms * (1 - NOISE_ADAPT) + rms * NOISE_ADAPT
        }

        if (open) {
            segmentMs += frameMs
            segmentPeak = maxOf(segmentPeak, peak)
            if (voiced) {
                segmentVoiced++
                voicedSpanMs = segmentMs
                silentMsWhileOpen = 0
            } else {
                silentMsWhileOpen += frameMs
            }
            // Keep streaming the tail: the server needs to hear silence to end the turn.
            if (silentMsWhileOpen >= hangoverMs) {
                val finished = closeSegment()
                return Decision(send = listOf(frame), finished = finished)
            }
            return Decision(send = listOf(frame))
        }

        // Closed: hold everything, and decide whether this is the start of something.
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()

        if (!voiced) {
            val rejection = if (consecutiveVoiced in 1 until minOnsetFrames || strayVoiced > 0) {
                Rejection(voicedFrames = maxOf(strayVoiced, consecutiveVoiced), peak = strayPeak)
            } else {
                null
            }
            consecutiveVoiced = 0
            strayVoiced = 0
            strayPeak = 0
            return Decision(send = emptyList(), rejected = rejection)
        }

        consecutiveVoiced++
        strayVoiced++
        strayPeak = maxOf(strayPeak, peak)
        if (consecutiveVoiced < minOnsetFrames) return Decision(send = emptyList())

        // Onset earned: open, and send the pre-roll so the first syllable is not clipped.
        open = true
        segmentMs = 0
        segmentVoiced = 0
        segmentPeak = 0
        silentMsWhileOpen = 0
        val flushed = preRoll.toList()
        preRoll.clear()
        strayVoiced = 0
        strayPeak = 0
        segmentMs = flushed.size * frameMs
        voicedSpanMs = segmentMs
        segmentVoiced = consecutiveVoiced
        segmentPeak = peak
        consecutiveVoiced = 0
        return Decision(send = flushed)
    }

    private fun closeSegment(): Segment {
        val segment = Segment(durationMs = voicedSpanMs, voicedFrames = segmentVoiced, peak = segmentPeak)
        lastFinished = segment
        open = false
        segmentMs = 0
        segmentVoiced = 0
        segmentPeak = 0
        voicedSpanMs = 0
        silentMsWhileOpen = 0
        consecutiveVoiced = 0
        return segment
    }

    companion object {
        /** 3200 bytes of 16 kHz PCM16 — the capture frame this app uses. */
        const val DEFAULT_FRAME_MS = 100

        /**
         * 200 ms of sustained voice. A finger tap, a mouse click or a switch is 20–60 ms and never
         * reaches two consecutive frames; the shortest real Chinese command (「停」) is ~300 ms.
         */
        const val MIN_ONSET_FRAMES = 2

        /** 300 ms, matching the server's own `prefix_padding_ms`, so nothing is clipped. */
        const val PRE_ROLL_FRAMES = 3

        /**
         * Six times the server's 200 ms `silence_duration_ms`. Long enough that the server always
         * sees the silence that ends a turn, and that a 1–2 second thinking pause stays inside one
         * open gate.
         */
        const val HANGOVER_MS = 1_200

        /**
         * Quiet speech at arm's length measured ~1300–1900 peak on this device, roughly 200+ RMS.
         * The floor sits well under that: it is a sanity bound for a silent room, and the adaptive
         * term does the real work everywhere else.
         */
        const val ABSOLUTE_RMS_FLOOR = 120

        /** A frame must be clearly above the room, not merely above it. */
        const val NOISE_FACTOR = 2.2

        const val INITIAL_NOISE_RMS = 80.0
        private const val NOISE_ADAPT = 0.05

        /**
         * Below this a segment is too brief to be an utterance worth answering out loud.
         *
         * Measured on device 2026-09-18: a chair scrape produced a 500 ms segment and was spoken,
         * because the bound was 500 and the comparison is strict. Knocks, coughs and scrapes
         * measured 200–500 ms; real commands 「关闭音乐」 and 「第二个」 measured 900–1400 ms. 800 ms
         * sits between the populations.
         *
         * Erring high is safe here: crossing this bound only makes a turn *eligible* to be
         * silenced, and it is still spoken unless the model asked for no action, nothing on screen
         * was waiting, and the reply carried no content. A real short command calls a tool, and a
         * tool call is never silenced.
         */
        const val SUSPICIOUS_BELOW_MS = 800

        /** Percent of frames that must carry voice for the segment to look like speech. */
        const val SUSPICIOUS_VOICED_RATIO = 35.0

        /**
         * Anything shorter than this has its reply held until the turn is judged. Above it the
         * driver clearly spoke a sentence and nothing is ever delayed.
         */
        const val HOLD_BELOW_MS = 1_200

        /**
         * Continuous speech measured 75–100 % voiced on this device. A multi-second stretch that
         * is mostly silence with bursts in it — three taps spread over three seconds, measured at
         * 43 % — is held until the turn proves itself, which costs a real sentence nothing.
         */
        const val HOLD_VOICED_RATIO = 60.0
    }
}

/** Root-mean-square level of a PCM16LE frame. Diagnostics and gating only; never content. */
internal fun rms(bytes: ByteArray): Int {
    if (bytes.size < 2) return 0
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < bytes.size) {
        val sample = (((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()).toInt()
        sum += sample.toDouble() * sample.toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0
    return kotlin.math.sqrt(sum / count).toInt()
}
