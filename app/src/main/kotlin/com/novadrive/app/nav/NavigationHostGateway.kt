package com.novadrive.app.nav

import com.novadrive.app.nav.amap.AmapNaviViewHost

/**
 * Process-scoped handle on the *live* [AmapNaviViewHost] owned by the visible Activity.
 *
 * Why this exists: `DebugToolReceiver` is a separate BroadcastReceiver and cannot reach
 * `MainActivity.screen`, which is a private field. Constructing a second host inside the
 * receiver would create a detached, never-displayed view — it would report route success
 * while rendering nothing, which is worse than failing, because stage 8 would look proven.
 *
 * Deliberately mirrors `VoiceSessionGateway`, which is already device-proven, including
 * the identity guard below.
 */
object NavigationHostGateway {
    private val lock = Any()

    @Volatile
    private var host: AmapNaviViewHost? = null

    @Volatile
    private var ownerIdentity: Any? = null

    /** [owner] is the object whose lifecycle governs this host (the screen or Activity). */
    fun attach(owner: Any, host: AmapNaviViewHost) {
        synchronized(lock) {
            ownerIdentity = owner
            this.host = host
        }
    }

    /**
     * Clears only when [owner] is identical (===) to the current owner. Without this a
     * finishing Activity unhooks a newer one's host — the exact defect this codebase has
     * now hit three times (duplicate onFocusChanged, the throwaway BaiduFlexClient, and
     * the VoiceSessionGateway detach it was added to prevent).
     */
    fun detach(owner: Any) {
        synchronized(lock) {
            if (ownerIdentity === owner) {
                ownerIdentity = null
                host = null
            }
        }
    }

    /** Null when no Activity is showing a map — callers must treat that as a real state. */
    fun current(): AmapNaviViewHost? = host
}
