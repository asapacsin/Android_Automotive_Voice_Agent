package com.novadrive.app.nav.amap

/**
 * P63 (owner test 10-16): the spoken over-speed warning. Amap's own alert is a ring
 * (`onPlayRing`), not words; this says 「您已超速，限速N公里」 when [DrivingSpeedHud] turns its
 * overspeed on, and once more when the posted limit changes while still over (a new camera or
 * sign). Nothing is said while no limit is known: SDK 11.2.100 reports limits only at speed
 * cameras and limit facilities, so with neither ahead there is nothing to be over.
 *
 * Pure decision, no Android: the host feeds every HUD snapshot and speaks what this returns
 * through the guidance path ([AmapGuidanceVoice.speakAlert]). The sentence holds only a number,
 * so it may be logged by length like any guidance text, never by content.
 */
class OverspeedAlert(private val minGapMs: Long = MIN_GAP_MS) {
    private var wasOver = false
    private var lastLimit = 0
    private var lastSpokenAt: Long? = null

    /** The sentence to speak now, or null. */
    fun onSnapshot(snapshot: DrivingSpeedHud.Snapshot, nowMs: Long): String? {
        if (!snapshot.visible || !snapshot.overspeed || snapshot.limitKmh <= 0) {
            wasOver = false
            lastLimit = 0
            return null
        }
        val edge = !wasOver || snapshot.limitKmh != lastLimit
        wasOver = true
        lastLimit = snapshot.limitKmh
        if (!edge) return null
        val last = lastSpokenAt
        if (last != null && nowMs - last < minGapMs) return null
        lastSpokenAt = nowMs
        return sentence(snapshot.limitKmh)
    }

    /** Navigation ended: the next drive starts fresh. */
    fun reset() {
        wasOver = false
        lastLimit = 0
        lastSpokenAt = null
    }

    companion object {
        /** A flickering limit (two cameras close together) does not repeat the warning within this. */
        const val MIN_GAP_MS = 10_000L

        fun sentence(limitKmh: Int): String = "您已超速，限速${limitKmh}公里"

        /**
         * Where a due warning goes. [hostNavigating] is the map host's own navigation state - not
         * the legacy `NavigationState.navigating`, which a voice-session restart clears mid-drive.
         */
        fun target(hostNavigating: Boolean, relayOn: Boolean, alertQueueOn: Boolean): String = when {
            !hostNavigating -> "dropped:not_navigating"
            relayOn -> "relay"
            alertQueueOn -> "amap"
            else -> "dropped:no_queue"
        }
    }
}
