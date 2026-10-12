package com.novadrive.app.livedemo

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.voice.ListeningControls
import com.novadrive.app.voice.ListeningIntent
import com.novadrive.app.voice.ListeningLifecycle
import com.novadrive.app.voice.SpeechArbiter
import com.novadrive.app.voice.SpeechAuthority
import com.novadrive.app.voice.VoiceCommandRouter
import com.novadrive.ingress.realtime.PlaybackPort
import com.novadrive.ingress.realtime.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The drive scene's speech rules: the app's own [SpeechAuthority] / [SpeechArbiter] wired to the
 * harness exactly where the app wires them, so the run shows how 小诺 behaves while Amap speaks and
 * near a manoeuvre. Nothing here decides anything; every decision is the arbiter's.
 *
 * - [onUiState]: VoiceSessionController's onUiState (R0, driver mid-utterance).
 * - [onUserFinalTranscript]: the real [VoiceCommandRouter], which opens the driver-request window.
 * - [guidanceListener]: VoiceSessionController.guidanceListener, fed by the simulated Amap prompt.
 * - [port]: AndroidPlaybackPort.enqueue / flush (DROP discards, HOLD queues paused, PLAY plays) on
 *   top of the timeline's player, which [SpeechAuthority.playbackHold] pauses and resumes.
 * - [uplinkClosed]: PcmAudioCapture.guidanceGated, read per captured frame.
 *
 * Not mirrored: audio focus (no focus loss on the JVM), SPEC-018 assistant guidance (off by default;
 * Amap speaks guidance), ducking, and the listening lifecycle's `speaks` gate on playback.
 */
class DriveSpeechRules(
    private val playback: LiveDemoTimeline.Playback,
    scope: CoroutineScope,
    pickerOpen: () -> Boolean = { false },
    taskPending: () -> Boolean = { false },
) : AutoCloseable {
    private val recheck = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "livedemo-recheck").apply { isDaemon = true } }

    private object NoControls : ListeningControls {
        override fun setCloudUpload(enabled: Boolean) = Unit
        override fun cancelAssistantReply() = Unit
        override fun closeCloudSession() = Unit
        override fun openCloudSession() = true
    }

    private val lifecycle = ListeningLifecycle(scope, NoControls, nowMs = System::currentTimeMillis)

    /** VoiceSessionController.commandRouter, with the same context and onDriverRequest. */
    private val router = VoiceCommandRouter(
        lifecycle = lifecycle,
        context = { ListeningIntent.Context(pickerOpen = pickerOpen(), taskPending = taskPending()) },
        log = { DebugVoiceLog.log(it) },
        onDriverRequest = { SpeechAuthority.arbiter.onDriverRequest() },
    )

    init {
        // A fresh arbiter per run on the wall clock (the app's is per process); nothing leaks in.
        SpeechAuthority.resetForTest(System::currentTimeMillis)
        SpeechAuthority.playbackHold = { pause -> if (pause) playback.pause() else playback.resume() }
        SpeechAuthority.scheduleRecheck = { delayMs, task -> recheck.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
        lifecycle.onSessionStarted("wake_word")
    }

    fun onUiState(state: VoiceUiState) = SpeechAuthority.arbiter.onDriverSpeaking(state == VoiceUiState.USER_SPEAKING)

    fun onUserFinalTranscript(text: String) {
        router.onUserUtterance(text)
    }

    /** VoiceSessionController.guidanceListener, verbatim but for the player. */
    val guidanceListener: (Boolean) -> Unit = { speaking ->
        val arbiter = SpeechAuthority.arbiter
        arbiter.onGuidanceSpeaking(speaking)
        SpeechAuthority.uplinkClosed()
        if (speaking) {
            playback.pause()
            SpeechAuthority.notePaused()
        } else {
            SpeechAuthority.syncPlaybackHold()
        }
    }

    fun uplinkClosed(): Boolean = SpeechAuthority.uplinkClosed()

    /** AndroidPlaybackPort's reply path (no SPEC-018 guidance chunks, no focus, no duck). */
    val port: PlaybackPort = object : PlaybackPort {
        private var droppingForNavigation = false

        override fun start() = playback.start()

        override fun beginReply(epoch: Int) {
            SpeechAuthority.onReplyEnded() // D2: the new reply has not started playing
            playback.beginReply(epoch)
        }

        override fun enqueue(pcm16le: ByteArray, epoch: Int) {
            val arbiter = SpeechAuthority.arbiter
            val decision = SpeechAuthority.reply()
            if (decision == SpeechArbiter.Reply.DROP) {
                if (!droppingForNavigation) {
                    droppingForNavigation = true
                    val reason = if (arbiter.navigationMuted()) "navigation_unprompted" else "focus_lost"
                    DebugVoiceLog.log("reply_audio_not_played reason=$reason")
                }
                return
            }
            droppingForNavigation = false
            if (decision == SpeechArbiter.Reply.HOLD) SpeechAuthority.syncPlaybackHold()
            playback.enqueue(pcm16le, epoch)
            SpeechAuthority.onReplyChunk(decision, queued = true)
            SpeechAuthority.syncPlaybackHold()
        }

        override fun flush(epoch: Int) {
            playback.flush(epoch)
            SpeechAuthority.onReplyEnded()
        }

        override fun stop() {
            playback.stop()
            SpeechAuthority.onReplyEnded()
        }

        override val queuedFrames: Int get() = playback.queuedFrames
    }

    override fun close() {
        recheck.shutdownNow()
        SpeechAuthority.resetForTest()
    }
}
