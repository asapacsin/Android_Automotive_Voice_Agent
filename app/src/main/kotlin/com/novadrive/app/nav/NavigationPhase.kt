package com.novadrive.app.nav

/**
 * v2 section 12 calls this NavigationState; it is named NavigationPhase here because the
 * legacy NavigationState object still drives the shipped speech-mute and VAD behaviour,
 * and the two are merged at SPEC-005 Phase 4.
 */
enum class NavigationPhase {
    IDLE,
    RESOLVING_DESTINATION,
    AWAITING_DESTINATION_SELECTION,
    PLANNING_ROUTE,
    CALCULATING_ROUTE,
    AWAITING_ROUTE_SELECTION,
    ROUTE_READY,
    NAVIGATING,
    ARRIVED,
    STOPPED,
    ERROR,
    ;

    /**
     * A session exists once a route is being planned or driven.
     * Destination/route picking and STOPPED are not a navigation session — the driver is choosing
     * or the session has ended.
     */
    val isNavigationSessionActive: Boolean
        get() = when (this) {
            PLANNING_ROUTE, CALCULATING_ROUTE, ROUTE_READY, NAVIGATING -> true
            IDLE,
            RESOLVING_DESTINATION,
            AWAITING_DESTINATION_SELECTION,
            AWAITING_ROUTE_SELECTION,
            ARRIVED,
            STOPPED,
            ERROR,
            -> false
        }
}
