package com.novadrive.app.voice

/**
 * Baidu Flex occasionally finishes a turn with `response.done status=completed output=[]` —
 * no tool call, no reply — right after the driver spoke (measured 2026-09-16: 温度调高一点).
 * The driver then hears nothing. This policy asks for one more response in that case.
 *
 * At most one retry per driver utterance, so it can never loop. Empty responses that are not
 * tied to an utterance (VAD triggered by noise) are not retried.
 *
 * The transcript and the empty response can arrive in either order, so both are handled.
 */
class EmptyResponseRetryPolicy {
    private var utterancePending = false
    private var emptyWithoutUtterance = false
    private var retriedThisUtterance = false

    /**
     * New speech began. Any earlier empty response belonged to something before it (often VAD
     * noise), so it must not be attributed to this utterance.
     */
    @Synchronized
    fun onSpeechStarted() {
        emptyWithoutUtterance = false
    }

    /** Returns true when the caller should send `response.create`. */
    @Synchronized
    fun onUserTranscriptCompleted(): Boolean {
        retriedThisUtterance = false
        if (emptyWithoutUtterance) {
            // The empty response for this utterance already arrived.
            emptyWithoutUtterance = false
            retriedThisUtterance = true
            return true
        }
        utterancePending = true
        return false
    }

    /** Returns true when the caller should send `response.create`. */
    @Synchronized
    fun onResponseDone(status: String, outputCount: Int): Boolean {
        if (status != "completed" || outputCount > 0) {
            utterancePending = false
            emptyWithoutUtterance = false
            return false
        }
        if (!utterancePending) {
            emptyWithoutUtterance = !retriedThisUtterance
            return false
        }
        utterancePending = false
        if (retriedThisUtterance) return false
        retriedThisUtterance = true
        return true
    }

    @Synchronized
    fun reset() {
        utterancePending = false
        emptyWithoutUtterance = false
        retriedThisUtterance = false
    }
}
