package com.novadrive.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The assistant's conversational state. The one source of truth for "may microphone audio be
 * uploaded" and "may a reply be spoken". The temporary playback / guidance microphone gates are
 * separate and only apply while audio is uploaded.
 *
 * ```
 * ACTIVE ──「闭嘴」──► SILENT_WAIT ──any real command──► ACTIVE
 *   │                  │  └── 20 s without real speech ──► SLEEP
 *   │                  └──「休眠」/「停止监听」────────────► SLEEP
 *   ├──「休眠」/「停止监听」/ 30 s idle ─────────────────────► SLEEP
 * SLEEP ── wake word / UI ──► ACTIVE          SLEEP ── 5 min ──► DEEP_IDLE ── wake word / UI ──► ACTIVE (new session)
 * ```
 */
enum class ListeningState {
    /** Normal conversation: audio is uploaded, replies are spoken. */
    ACTIVE,

    /**
     * 「闭嘴」: the reply was cut off and replies are not spoken, but audio is still uploaded and the
     * conversation continues — the next real command returns to [ACTIVE] without the wake word.
     */
    SILENT_WAIT,

    /** Nothing is captured or uploaded; ordinary speech is ignored. Only wake word / UI wake it. */
    SLEEP,

    /** [SLEEP] for a long time: the realtime connection is closed as well. */
    DEEP_IDLE,
    ;

    /** Microphone audio goes to the cloud in this state. */
    val uploads: Boolean get() = this == ACTIVE || this == SILENT_WAIT
}

/** All lifecycle timeouts in one place. */
data class ListeningTimeouts(
    /** ACTIVE: no meaningful user turn for this long after the assistant finished → SLEEP. */
    val sleepAfterInactivityMs: Long = SLEEP_AFTER_INACTIVITY_MS,
    /** SILENT_WAIT: no real user speech for this long → SLEEP. */
    val silentWaitMs: Long = SILENT_WAIT_TIMEOUT_MS,
    /** SLEEP this long → DEEP_IDLE (connection closed). */
    val deepIdleAfterSleepMs: Long = DEEP_IDLE_AFTER_SLEEP_MS,
    /** A deadline that passed while busy fires this long after the app becomes idle. */
    val idleGraceMs: Long = IDLE_GRACE_MS,
) {
    companion object {
        const val SLEEP_AFTER_INACTIVITY_MS = 30_000L
        const val SILENT_WAIT_TIMEOUT_MS = 20_000L
        const val DEEP_IDLE_AFTER_SLEEP_MS = 300_000L
        const val IDLE_GRACE_MS = 1_500L
    }
}

/** What the lifecycle switches. Implemented by the app's VoiceSessionController. */
interface ListeningControls {
    /** Starts / resumes microphone capture and upload (false: stop now, drop queued audio). */
    fun setCloudUpload(enabled: Boolean)

    /** Stops the reply in progress: local playback now, and the reply on the server. */
    fun cancelAssistantReply()

    /** Closes the realtime connection without scheduling a reconnect. */
    fun closeCloudSession()

    /** Opens a new realtime session (from DEEP_IDLE). Returns false when it cannot. */
    fun openCloudSession(): Boolean
}

/**
 * Transitions and timers. One timer job at a time, guarded by an epoch so a stale timer can never
 * act on a newer state. Timers never fire while a user turn or its answer is in progress
 * ([onBusyChanged]); VAD noise (no meaningful transcript) never extends them.
 *
 * Conversation context survives SILENT_WAIT and SLEEP (the connection stays open); it is lost only
 * in DEEP_IDLE, where the connection is closed.
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
    private var sleepAfterReply = false

    private var uploadingSinceMs: Long? = null
    private var streamedTotalMs = 0L

    /** Total time audio was uploaded (ACTIVE + SILENT_WAIT), including the current stretch. */
    val cloudStreamingMs: Long
        get() = synchronized(lock) { streamedTotalMs + (uploadingSinceMs?.let { nowMs() - it } ?: 0L) }

    /** Replies may be spoken only here. */
    val speaks: Boolean get() = _state.value == ListeningState.ACTIVE

    /** A new realtime session was started (wake word, UI, app prompt). */
    fun onSessionStarted(reason: String) = synchronized(lock) {
        busy = false
        meaningfulTurnInProgress = false
        sleepAfterReply = false
        enterActiveLocked(reason, openSession = false)
    }

    /**
     * Wake word, UI or an app prompt. SILENT_WAIT / SLEEP → ACTIVE on the same connection (context
     * kept); DEEP_IDLE → a new session; ACTIVE → the inactivity countdown restarts.
     */
    fun activate(reason: String): Boolean = synchronized(lock) {
        when (_state.value) {
            ListeningState.ACTIVE -> {
                sleepAfterReply = false
                restartInactivityLocked()
                true
            }
            ListeningState.SILENT_WAIT, ListeningState.SLEEP -> enterActiveLocked(reason, openSession = false)
            ListeningState.DEEP_IDLE -> enterActiveLocked(reason, openSession = true)
        }
    }

    /**
     * 「闭嘴」: cut off the reply now and wait silently for the next command. Upload continues. A
     * repeated 「闭嘴」 only re-arms the single timer.
     */
    fun silence(reason: String) = synchronized(lock) {
        when (_state.value) {
            ListeningState.ACTIVE, ListeningState.SILENT_WAIT -> {
                controls.cancelAssistantReply()
                meaningfulTurnInProgress = false
                sleepAfterReply = false
                transitionLocked(ListeningState.SILENT_WAIT, reason)
                scheduleLocked(nowMs() + timeouts.silentWaitMs)
            }
            // Asleep: nothing is talking and nothing is listening.
            ListeningState.SLEEP, ListeningState.DEEP_IDLE -> Unit
        }
    }

    /** 「休眠」/「停止监听」/ UI: stop listening now. */
    fun sleep(reason: String) = synchronized(lock) {
        if (!_state.value.uploads) return@synchronized
        controls.setCloudUpload(false)
        controls.cancelAssistantReply()
        enterSleepLocked(reason)
    }

    /** The model ended the conversation: SLEEP once its short goodbye has played. */
    fun sleepAfterReply(reason: String) = synchronized(lock) {
        when {
            !_state.value.uploads -> Unit
            // Nothing is being spoken in SILENT_WAIT, so there is no goodbye to wait for.
            !busy || _state.value == ListeningState.SILENT_WAIT -> {
                controls.setCloudUpload(false)
                enterSleepLocked(reason)
            }
            else -> sleepAfterReply = true
        }
    }

    /**
     * A user turn or its answer is in progress (user speaking, model thinking or talking, reply
     * audio playing, tool result owed). No timer fires while busy; speech that starts in
     * SILENT_WAIT pauses the sleep timer before the command is processed.
     */
    fun onBusyChanged(nowBusy: Boolean) = synchronized(lock) {
        if (busy == nowBusy) return@synchronized
        busy = nowBusy
        if (!_state.value.uploads) return@synchronized
        if (nowBusy) {
            cancelTimerLocked()
            return@synchronized
        }
        if (sleepAfterReply) {
            sleepAfterReply = false
            controls.setCloudUpload(false)
            enterSleepLocked("conversation_ended")
            return@synchronized
        }
        if (_state.value == ListeningState.ACTIVE && meaningfulTurnInProgress) {
            meaningfulTurnInProgress = false
            restartInactivityLocked()
        } else {
            // Noise, an answer to nobody, or still SILENT_WAIT: keep the original deadline, never
            // firing mid-sentence.
            scheduleLocked(maxOf(deadlineMs, nowMs() + timeouts.idleGraceMs))
        }
    }

    /**
     * The driver said something real (not VAD noise, not a listening-control phrase). In
     * SILENT_WAIT that is the next command: back to ACTIVE, so its answer is spoken.
     */
    fun onMeaningfulUserTurn() = synchronized(lock) {
        when (_state.value) {
            ListeningState.ACTIVE -> meaningfulTurnInProgress = true
            ListeningState.SILENT_WAIT -> {
                cancelTimerLocked()
                transitionLocked(ListeningState.ACTIVE, "user_command")
                meaningfulTurnInProgress = true
                if (!busy) restartInactivityLocked()
            }
            else -> Unit
        }
    }

    /** The connection failed or closed. In SLEEP that ends the session instead of reconnecting. */
    fun onConnectionLost() = synchronized(lock) {
        if (_state.value == ListeningState.SLEEP) enterDeepIdleLocked("connection_lost_in_sleep")
    }

    /** The session was stopped from outside (settings, app teardown). */
    fun onSessionStopped(reason: String) = synchronized(lock) {
        if (_state.value == ListeningState.DEEP_IDLE) return@synchronized
        cancelTimerLocked()
        transitionLocked(ListeningState.DEEP_IDLE, reason)
    }

    // ---- transitions ----------------------------------------------------------------------

    private fun enterActiveLocked(reason: String, openSession: Boolean): Boolean {
        if (openSession && !controls.openCloudSession()) return false
        controls.setCloudUpload(true)
        transitionLocked(ListeningState.ACTIVE, reason)
        restartInactivityLocked()
        return true
    }

    private fun enterSleepLocked(reason: String) {
        cancelTimerLocked()
        meaningfulTurnInProgress = false
        sleepAfterReply = false
        transitionLocked(ListeningState.SLEEP, reason)
        val mine = epoch
        timer = scope.launch {
            delay(timeouts.deepIdleAfterSleepMs)
            synchronized(lock) {
                if (epoch == mine && _state.value == ListeningState.SLEEP) enterDeepIdleLocked("sleep_timeout")
            }
        }
    }

    private fun enterDeepIdleLocked(reason: String) {
        cancelTimerLocked()
        transitionLocked(ListeningState.DEEP_IDLE, reason)
        controls.closeCloudSession()
    }

    private fun restartInactivityLocked() {
        scheduleLocked(nowMs() + timeouts.sleepAfterInactivityMs)
    }

    /** Arms the single sleep timer for the current (ACTIVE or SILENT_WAIT) state. */
    private fun scheduleLocked(at: Long) {
        cancelTimerLocked()
        deadlineMs = at
        if (busy) return
        val mine = epoch
        val from = _state.value
        timer = scope.launch {
            delay((at - nowMs()).coerceAtLeast(0))
            synchronized(lock) {
                if (epoch != mine || _state.value != from || busy) return@synchronized
                controls.setCloudUpload(false)
                enterSleepLocked(if (from == ListeningState.SILENT_WAIT) "silent_wait_timeout" else "inactivity_timeout")
            }
        }
    }

    /** Cancels the timer and invalidates any timer body already running. */
    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
        epoch++
    }

    private fun transitionLocked(to: ListeningState, reason: String) {
        val from = _state.value
        if (from == to) return
        val now = nowMs()
        if (from.uploads && !to.uploads) {
            streamedTotalMs += now - (uploadingSinceMs ?: now)
            uploadingSinceMs = null
        }
        if (!from.uploads && to.uploads) uploadingSinceMs = now
        _state.value = to
        onTransition(from, to, reason, streamedTotalMs + (uploadingSinceMs?.let { now - it } ?: 0L))
    }
}
