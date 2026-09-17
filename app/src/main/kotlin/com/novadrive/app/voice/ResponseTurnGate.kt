package com.novadrive.app.voice

/**
 * Serialises the replies the app itself asks for (read a camera answer aloud, a typed turn,
 * a tool result) with the replies Baidu creates for the driver's speech.
 *
 * Measured 2026-09-17: the camera's automatic answer sent `response.create` while the driver
 * was still asking a question. Baidu refused the driver's turn with "Conversation already has an
 * active response in progress", and that refusal put the whole session into ERROR.
 *
 * A turn submitted while Baidu is busy is queued and sent after the current reply finishes.
 * "Busy" means: a reply is running, the driver is speaking, or the driver stopped less than
 * [afterSpeechMs] ago (Baidu creates its own reply then). Busy marks expire after [staleMs] so a
 * lost event cannot block the app's replies for good.
 */
class ResponseTurnGate(
    private val now: () -> Long = System::currentTimeMillis,
    private val staleMs: Long = 30_000,
    private val afterSpeechMs: Long = 1_500,
) {
    /** [messages] go out together; [afterReset] replaces them if the conversation was reset meanwhile. */
    data class Turn(val messages: List<String>, val afterReset: List<String> = messages, val epoch: Int = 0)

    private var responseSince = 0L
    private var speechUntil = 0L
    private val queue = ArrayDeque<Turn>()

    @Synchronized
    fun isBusy(): Boolean {
        val t = now()
        return (responseSince != 0L && t - responseSince < staleMs) || t < speechUntil
    }

    @Synchronized
    fun onResponseCreated() {
        responseSince = now()
        speechUntil = 0L
    }

    @Synchronized
    fun onResponseDone() {
        responseSince = 0L
    }

    @Synchronized
    fun onSpeechStarted() {
        speechUntil = now() + staleMs
    }

    @Synchronized
    fun onSpeechStopped() {
        speechUntil = now() + afterSpeechMs
    }

    /** A new socket: nothing is running on it. Queued turns are kept. */
    @Synchronized
    fun onConnectionReset() {
        responseSince = 0L
        speechUntil = 0L
    }

    /** Returns true when [turn] may be sent now (and marks a reply as running); otherwise queues it. */
    @Synchronized
    fun submit(turn: Turn): Boolean {
        if (isBusy() || queue.isNotEmpty()) {
            queue.addLast(turn)
            return false
        }
        responseSince = now()
        return true
    }

    /** Baidu refused a reply because another was running: ask again once it has finished. */
    @Synchronized
    fun onBusyRejected(retry: Turn) {
        queue.addFirst(retry)
    }

    /** The next queued turn if Baidu is free (marking a reply as running), else null. */
    @Synchronized
    fun next(): Turn? {
        if (isBusy()) return null
        val turn = queue.removeFirstOrNull() ?: return null
        responseSince = now()
        return turn
    }

    @Synchronized
    fun pending(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
        onConnectionReset()
    }
}
