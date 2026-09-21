package com.novadrive.app.nav.amap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stable navigation baseline while live countdown seconds are blocked on Amap entitlement.
 *
 * Absence of countdown data is NOT a failure: the listener must tolerate null/empty
 * traffic inputs without crashing, and route/guidance callbacks must still fire.
 */
class TrafficLightCountdownBaselineTest {
    @Test
    fun countdownIsDeclaredBlockedOnExternalEntitlement() {
        assertEquals(
            "BLOCKED_EXTERNAL_AMAP_ENTITLEMENT",
            AmapDrivingPresentation.TRAFFIC_COUNTDOWN_STATUS,
        )
    }

    @Test
    fun missingCountdownDataNeverReachesCallbacksOrCrashes() {
        var cameraCalls = 0
        var facilityCalls = 0
        var locationCalls = 0
        val listener = NavigationTraceListener(
            onRouteReady = {},
            onNavigationEnded = {},
            onLocation = { locationCalls++ },
            onCameraLimits = { cameraCalls++ },
            onFacilityLimit = { facilityCalls++ },
        )

        listener.updateCameraInfo(null)
        listener.updateCameraInfo(emptyArray())
        listener.OnUpdateTrafficFacility(null as com.amap.api.navi.model.AMapNaviTrafficFacilityInfo?)
        listener.OnUpdateTrafficFacility(null as Array<com.amap.api.navi.model.AMapNaviTrafficFacilityInfo>?)
        listener.onNaviInfoUpdate(null)
        listener.onLocationChange(null)

        assertEquals(0, cameraCalls)
        assertEquals(0, facilityCalls)
        assertEquals(0, locationCalls)
    }

    @Test
    fun emulatorProximityArrivalFiresWhenSdkEndNeverComes() {
        var ended: String? = null
        val listener = NavigationTraceListener(
            onRouteReady = {},
            onNavigationEnded = { ended = it },
        )
        listener.onStartNavi(NavigationProgressTrace.NAVI_TYPE_EMULATOR)
        repeat(NavigationProgressTrace.PROXIMITY_SAMPLES_REQUIRED) {
            listener.onNaviInfoUpdate(
                com.amap.api.navi.model.NaviInfo().apply {
                    pathRetainDistance = 20
                    pathRetainTime = 5
                },
            )
        }
        assertEquals("emulator_end", ended)
    }

    @Test
    fun routeAndGuidanceCallbacksStillFireWithoutCountdown() {
        var routeIds: IntArray? = null
        var ended: String? = null
        val listener = NavigationTraceListener(
            onRouteReady = { routeIds = it },
            onNavigationEnded = { ended = it },
        )

        listener.onCalculateRouteSuccess(intArrayOf(11, 22))
        listener.onStartNavi(2)
        listener.onPlayRing(100)
        listener.onEndEmulatorNavi()

        assertTrue(routeIds?.contentEquals(intArrayOf(11, 22)) == true)
        assertEquals("emulator_end", ended)
    }
}
