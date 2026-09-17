package com.novadrive.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the assistant is listening to the cloud. The one source of truth for "may microphone
 * audio be uploaded" (the temporary playback / guidance gates are separate and only apply inside
 * [ACTIVE]).
 */
enum class ListeningState {
    /** Microphone audio is uploaded; follow-up turns need no wake word. */
    ACTIVE,

    /** Connected (while the server keeps the socket), but nothing is captured or uploaded. */
    STANDBY,

    /** Nothing captured, socket closed. Wake word or the UI start a new session. */
    DEEP_IDLE,
}

/** Timeouts in one place. The defaults are the product decision of 2026-09-17. */
data class ListeningTimeouts(
    /** No meaningful user turn for this long after the assistant finished → STANDBY. */
    val standbyAfterMs: Long = STANDBY_AFTER_MS,
    /** This long in STANDBY → DEEP_IDLE (socket closed). */
    val deepIdleAfterStandbyMs: Long = DEEP_IDLE_AFTER_STANDBY_MS,
    /** A deadline that passed while busy fires this long after the app becomes idle. */
    val idleGraceMs: Long = IDLE_GRACE_MS,
) {
    companion object {
        const val STANDBY_AFTER_MS = 30_000L
        const val DEEP_IDLE_AFTER_STANDBY_MS = 300_000L
        const val IDLE_GRACE_MS = 1_500L
    }
}

/** What the lifecycle switches. Implemented by the app's VoiceSessionController. */
interface ListeningControls {
    /** Starts / resumes microphone capture and upload (false: stop now, drop queued audio). */
    fun setCloudUpload(enabled: Boolean)

    /** Stops the reply in progress (playback and server). */
    fun cancelAssistantReply()

    /** The next turn starts a fresh conversation. */
    fun startFreshConversation()

    /** Closes the realtime connection without scheduling a reconnect. */
    fun closeCloudSession()

    /** Opens a new realtime session (from DEEP_IDLE). Returns false when it cannot. */
    fun openCloudSession(): Boolean
}

/**
 * ACTIVE ⇄ STANDBY → DEEP_IDLE, with one inactivity timer and one deep-idle timer, both guarded by
 * an epoch so a stale timer can never act on a newer state.
 *
 * Inactivity counts from the last *meaningful* activity: session start or resume, or a real user
 * turn once the assistant has finished answering it. Assistant speech, navigation guidance, tool
 * work, UI and vehicle updates never extend it; the timer only refuses to fire while a user turn or
 * its answer is in progress ([onBusyChanged]).
 */
class ListeningLifecycle(
    private val scope: CoroutineScope,
    private val controls: ListeningControls,
    private val timeouts: ListeningTimeouts = ListeningTimeouts(),
    private val nowMs: () -> Long,
    private val onTransition: (from: ListeningState, to: ListeningState, reason: String, streamedMs: Long) -> Unit = { _, _, _, _ -> },
) {
    private val lock = Any()
    private val _state = MutableStateFlow(ListeningState.DEEP_IDLE)
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    private var epoch = 0L
    private var timer: Job? = null
    private var busy = false
    private var deadlineMs = 0L
    private var meaningfulTurnInProgress = false
    private var standbyAfterReply = false

    private var activeSinceMs: Long? = null
    private var streamedTotalMs = 0L

    /** Total time spent in ACTIVE (cloud streaming allowed), including the current stretch. */
    val cloudStreamingMs: Long
        get() = synchronized(lock) { streamedTotalMs + (activeSinceMs?.let { nowMs() - it } ?: 0L) }

    /** A new realtime session was started (wake word, UI, app prompt). */
    fun onSessionStarted(reason: String) = synchronized(lock) {
        busy = false
        meaningfulTurnInProgress = false
        standbyAfterReply = false
        enterActiveLocked(reason, openSession = false, fresh = false)
    }

    /**
     * Wake word, UI tap or an app prompt wants the assistant listening. From STANDBY this resumes
     * upload on the same connection; from DEEP_IDLE it opens a new session; in ACTIVE it restarts
     * the inactivity countdown.
     */
    fun activate(reason: String): Boolean = synchronized(lock) {
        when (_state.value) {
            ListeningState.ACTIVE -> {
                standbyAfterReply = false
                restartCountdownLocked()
                true
            }
            ListeningState.STANDBY -> enterActiveLocked(reason, openSession = false, fresh = true)
            ListeningState.DEEP_IDLE -> enterActiveLocked(reason, openSession = true, fresh = false)
        }
    }

    /** TERMINATE_LISTENING: immediate, from the driver's own words or the UI. */
    fun terminate(reason: String) = synchronized(lock) {
        if (_state.value != ListeningState.ACTIVE) return@synchronized
        controls.setCloudUpload(false)
        controls.cancelAssistantReply()
        enterStandbyLocked(reason)
    }

    /** The model ended the conversation (tool); go to STANDBY once its goodbye has been spoken. */
    fun standbyAfterReply(reason: String) = synchronized(lock) {
        if (_state.value != ListeningState.ACTIVE) return@synchronized
        if (!busy) {
            controls.setCloudUpload(false)
            enterStandbyLocked(reason)
        } else {
            standbyAfterReply = true
        }
    }

    /**
     * A user turn or its answer is in progress (user speaking, model thinking or talking, reply
     * audio playing, tool result owed). The timer never fires while busy.
     */
    fun onBusyChanged(nowBusy: Boolean) = synchronized(lock) {
        if (busy == nowBusy) return@synchronized
        busy = nowBusy
        if (_state.value != ListeningState.ACTIVE) return@synchronized
        if (nowBusy) {
            cancelTimerLocked()
            return@synchronized
        }
        if (standbyAfterReply) {
            standbyAfterReply = false
            controls.setCloudUpload(false)
            enterStandbyLocked("conversation_ended")
            return@synchronized
        }
        if (meaningfulTurnInProgress) {
            meaningfulTurnInProgress = false
            restartCountdownLocked()
        } else {
            // Noise or a non-user reply: keep the original deadline, but never fire mid-sentence.
            scheduleStandbyLocked(maxOf(deadlineMs, nowMs() + timeouts.idleGraceMs))
        }
    }

    /** The driver said something real (not VAD noise). Counted once its answer has finished. */
    fun onMeaningfulUserTurn() = synchronized(lock) {
        if (_state.value == ListeningState.ACTIVE) meaningfulTurnInProgress = true
    }

    /** The connection failed or closed. In STANDBY that ends the session instead of reconnecting. */
    fun onConnectionLost() = synchronized(lock) {
        if (_state.value == ListeningState.STANDBY) enterDeepIdleLocked("connection_lost_in_standby")
    }

    /** The session was stopped from outside (settings, app teardown). */
    fun onSessionStopped(reason: String) = synchronized(lock) {
        if (_state.value == ListeningState.DEEP_IDLE) return@synchronized
        cancelTimerLocked()
        epoch++
        transitionLocked(ListeningState.DEEP_IDLE, reason)
    }

    // ---- transitions ----------------------------------------------------------------------

    private fun enterActiveLocked(reason: String, openSession: Boolean, fresh: Boolean): Boolean {
        if (openSession && !controls.openCloudSession()) return false
        if (fresh) controls.startFreshConversation()
        controls.setCloudUpload(true)
        transitionLocked(ListeningState.ACTIVE, reason)
        restartCountdownLocked()
        return true
    }

    private fun enterStandbyLocked(reason: String) {
        cancelTimerLocked()
        meaningfulTurnInProgress = false
        standbyAfterReply = false
        transitionLocked(ListeningState.STANDBY, reason)
        val mine = ++epoch
        timer = scope.launch {
            delay(timeouts.deepIdleAfterStandbyMs)
            synchronized(lock) {
                if (epoch == mine && _state.value == ListeningState.STANDBY) enterDeepIdleLocked("standby_timeout")
            }
        }
    }

    private fun enterDeepIdleLocked(reason: String) {
        cancelTimerLocked()
        epoch++
        transitionLocked(ListeningState.DEEP_IDLE, reason)
        controls.closeCloudSession()
    }

    private fun restartCountdownLocked() {
        scheduleStandbyLocked(nowMs() + timeouts.standbyAfterMs)
    }

    private fun scheduleStandbyLocked(at: Long) {
        cancelTimerLocked()
        deadlineMs = at
        if (busy) return
        val mine = ++epoch
        timer = scope.launch {
            delay((at - nowMs()).coerceAtLeast(0))
            synchronized(lock) {
                if (epoch != mine || _state.value != ListeningState.ACTIVE || busy) return@synchronized
                controls.setCloudUpload(false)
                enterStandbyLocked("inactivity_timeout")
            }
        }
    }

    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
        epoch++
    }

    private fun transitionLocked(to: ListeningState, reason: String) {
        val from = _state.value
        if (from == to) return
        val now = nowMs()
        if (from == ListeningState.ACTIVE) {
            streamedTotalMs += now - (activeSinceMs ?: now)
            activeSinceMs = null
        }
        if (to == ListeningState.ACTIVE) activeSinceMs = now
        _state.value = to
        onTransition(from, to, reason, streamedTotalMs)
    }
}
