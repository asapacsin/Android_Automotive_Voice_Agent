package com.novadrive.app.sim

import com.novadrive.app.NavigationState
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.evaluation.StateKeys
import com.novadrive.vehicle.ClimateState

/**
 * Reads the simulated world (and the app state that matters) into the oracle's key/value form.
 * Shared by the JVM simulation and the phone benchmark runner, so both are judged identically.
 */
class WorldProbe(
    private val climate: () -> ClimateState?,
    private val navigation: () -> EmbeddedNavigationController?,
    private val world: SimulatedNavigationWorld?,
    private val musicPlaying: () -> Boolean,
    private val cameraOpen: () -> Boolean,
    private val visionRequests: () -> Int,
    private val sessionAlive: () -> Boolean,
    private val sessionConnected: () -> Boolean = { false },
    private val sessionConnects: () -> Int = { 0 },
    private val visionCompleted: () -> Int = { 0 },
) {
    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        climate()?.let { c ->
            out[StateKeys.HVAC_POWER] = if (c.powerOn) "on" else "off"
            out[StateKeys.HVAC_TEMP] = StateKeys.num(c.targetTemperatureCelsius)
            out[StateKeys.HVAC_FAN] = c.fanLevel.toString()
        }
        navigation()?.let { nav ->
            out[StateKeys.NAV_PHASE] = nav.state().value.name
            out[StateKeys.NAV_DESTINATION] = nav.destination.value?.name.orEmpty()
            out[StateKeys.NAV_CANDIDATES] = nav.destinationCandidates.value.size.toString()
            out[StateKeys.NAV_ROUTES] = nav.routeCandidates.value.size.toString()
        }
        world?.let { w ->
            out[StateKeys.NAV_ROUTE] = w.activeRouteLabel.substringBefore(',')
            out[StateKeys.NAV_PROGRESS] = String.format(java.util.Locale.US, "%.2f", w.progress)
            out[StateKeys.NAV_SEARCHES_IN_FLIGHT] = w.searchesInFlight.toString()
        }
        out[StateKeys.NAV_SPEECH_MUTE] = NavigationState.navigating.toString()
        out[StateKeys.MEDIA_PLAYING] = musicPlaying().toString()
        out[StateKeys.CAMERA_OPEN] = cameraOpen().toString()
        out[StateKeys.VISION_REQUESTS] = visionRequests().toString()
        out[StateKeys.SESSION_ALIVE] = sessionAlive().toString()
        out[StateKeys.SESSION_CONNECTED] = sessionConnected().toString()
        out[StateKeys.SESSION_CONNECTS] = sessionConnects().toString()
        out[StateKeys.VISION_COMPLETED] = visionCompleted().toString()
        return out
    }
}
