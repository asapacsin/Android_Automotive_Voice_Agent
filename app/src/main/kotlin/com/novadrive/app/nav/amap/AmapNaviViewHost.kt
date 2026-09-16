package com.novadrive.app.nav.amap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.NaviPoi
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.nav.RouteCandidate

/**
 * The only file permitted to import `com.amap`.
 * Owns AMapNaviView construction, AMapNavi instance, and lifecycle forwarding.
 *
 * P5: constructing AMapNavi does NOT acquire a position. `startGPS()` must be called
 * explicitly, and only once FINE location is granted, or the SDK throws from
 * addNmeaListener. Verified against the resolved artifact with javap.
 */
class AmapNaviViewHost(context: Context) : FrameLayout(context) {
    private val naviView: AMapNaviView
    private var navi: AMapNavi? = null
    private var locationStarted = false
    private var traceListener: NavigationTraceListener? = null
    private val navLock = Any()
    private var navigationActive = false

    private companion object {
        /** Emulator-only: fast enough to reach a nearby destination within a test cycle. */
        const val EMULATOR_SPEED_KMH = 120
    }

    init {
        AmapPrivacyCompliance.ensure(context)
        navi = AMapNavi.getInstance(context.applicationContext)
        naviView = AMapNaviView(context)
        naviView.setAMapNaviViewListener(NoOpNaviViewListener)
        addView(naviView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** True once FINE location is held; the SDK cannot use GNSS without it. */
    private fun hasFineLocation(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Idempotent. Returns true when the SDK accepted the request.
     * Logs fixed strings only — never a coordinate or address (personal data).
     */
    fun startLocation(): Boolean {
        if (locationStarted) return true
        if (!hasFineLocation()) {
            DebugVoiceLog.log("amap_gps skipped=no_fine_permission")
            return false
        }
        val accepted = navi?.startGPS() ?: false
        locationStarted = accepted
        DebugVoiceLog.log("amap_gps startGPS=$accepted")
        enableMyLocation()
        return accepted
    }

    /**
     * P5 root cause 3: `startGPS()` alone is not enough. Measured 2026-09-16 — the SDK
     * received 10 injected fixes and the map still drew nothing, because `AMapNaviView`
     * renders the vehicle along a *calculated route* and Phase 1 has no route.
     *
     * `AMapNaviView.getMap()` exposes the underlying `AMap`, which owns the my-location
     * layer. Enabling it here shows the position on the view we already have, which avoids
     * hosting a second `MapView` purely for the idle state.
     *
     * UNVERIFIED: whether `setMyLocationEnabled(true)` is self-sourcing, or whether the SDK
     * expects an `AMap.setLocationSource(...)`. The simple form is tried first; if the device
     * shows no marker, a LocationSource is the next step — not a guess to make in advance.
     */
    private fun enableMyLocation() {
        val map = runCatching { naviView.map }.getOrNull()
        if (map == null) {
            DebugVoiceLog.log("amap_gps myLocation=no_map")
            return
        }
        runCatching {
            val style = MyLocationStyle()
                .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                .showMyLocation(true)
                .interval(2000L)
            map.myLocationStyle = style
            map.isMyLocationEnabled = true
        }.onSuccess {
            DebugVoiceLog.log("amap_gps myLocation=enabled")
        }.onFailure {
            DebugVoiceLog.log("amap_gps myLocation=failed")
        }
    }

    /** True once the map holds a fix. Exposes no coordinate. */
    fun hasMyLocation(): Boolean =
        runCatching { naviView.map?.myLocation != null }.getOrDefault(false)

    // ---- stages 4-6: route calculation and navigation start ---------------
    // Nothing here existed before: PhaseOneNavigationController was a stub that
    // returned EMBEDDED_ROUTING_NOT_IMPLEMENTED without issuing any SDK call.

    /**
     * Stage 4. Issues a drive-route request to the current [endLat]/[endLon].
     * Start is null so the SDK uses the current fix.
     *
     * Returns whether the SDK *accepted* the request — success or failure arrives
     * asynchronously on [NavigationTraceListener], which is why registering that
     * listener is a precondition rather than an optional extra.
     */
    fun calculateDriveRoute(
        endLat: Double,
        endLon: Double,
        endName: String,
        strategy: Int = PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT,
    ): Boolean {
        ensureTraceListener()
        val navi = this.navi
        if (navi == null) {
            DebugVoiceLog.log("nav_calc_request accepted=false reason=no_navi")
            return false
        }
        val end = NaviPoi(endName, LatLng(endLat, endLon), null)
        val accepted = runCatching {
            navi.calculateDriveRoute(null, end, null, strategy)
        }.getOrElse {
            DebugVoiceLog.log("nav_calc_request accepted=false reason=exception")
            false
        }
        DebugVoiceLog.log("nav_calc_request accepted=$accepted")
        return accepted
    }

    fun selectRoute(routeId: Int): Boolean {
        val navi = this.navi
        if (navi == null) {
            DebugVoiceLog.log("nav_select_route routeId=$routeId accepted=false reason=no_navi")
            return false
        }
        val accepted = runCatching { navi.selectRouteId(routeId) }.getOrDefault(false)
        // Path length identifies WHICH route the SDK currently treats as active, so it is
        // the only way to tell selectRouteId's return value apart from its actual effect.
        // Route length is a property of the route, not the driver's position.
        val meters = runCatching { navi.naviPath?.allLength }.getOrNull()
        DebugVoiceLog.log("nav_select_route routeId=$routeId accepted=$accepted pathMeters=$meters")
        return accepted
    }

    fun routeCandidates(): List<RouteCandidate> {
        val paths = navi?.naviPaths ?: return emptyList()
        return paths.entries.mapNotNull { entry ->
            val routeId = entry.key ?: return@mapNotNull null
            val path = entry.value ?: return@mapNotNull null
            RouteCandidate(
                routeId = routeId.toInt(),
                distanceMeters = path.allLength,
                durationSeconds = path.allTime,
                labels = path.labels?.takeIf { it.isNotBlank() },
                trafficLightCount = path.trafficLightCount,
            )
        }
    }

    fun setRouteCalculationCallbacks(
        onSuccess: ((IntArray) -> Unit)?,
        onFailure: ((Int) -> Unit)?,
    ) {
        onRoutesCalculated = onSuccess
        onRouteCalculationFailed = onFailure
    }

    /**
     * Notified whenever an active session ends, with the same [reason] passed to
     * [stopNavigation]. The state machine lives outside this file, and arrival stops the
     * host directly, so without this hook nothing upstream ever learns the drive is over.
     */
    fun setNavigationEndedCallback(onEnded: ((String) -> Unit)?) {
        onNavigationEndedCallback = onEnded
    }

    /**
     * Stage 6. [NaviType.EMULATOR] drives a simulated vehicle along the route, which
     * is the only way to prove stages 7-8 render without physically driving.
     * [NaviType.GPS] is the real mode.
     */
    fun startNavigation(emulator: Boolean): Boolean {
        val navi = this.navi
        if (navi == null) {
            DebugVoiceLog.log("nav_start accepted=false reason=no_navi")
            return false
        }
        // Route size, so "no completion within N minutes" is judgeable rather than a
        // mystery. Distance/duration are route properties, not the user's position.
        //
        // NAMED "_prestart" DELIBERATELY. Measured 2026-09-16: getNaviPath() does NOT reflect
        // a successful selectRouteId() until startNavi() runs, so this line reports the
        // PREVIOUS route. Read next to nav_route_selected it looks exactly like a selection
        // bug, and was briefly mis-reported as one. The authoritative value is
        // nav_active_route, logged after startNavi below.
        runCatching {
            navi.naviPath?.let { path ->
                DebugVoiceLog.log("nav_route_info_prestart meters=${path.allLength} seconds=${path.allTime}")
            }
        }
        if (emulator) {
            // Without this the emulator drives at the SDK default, so even a nearby
            // destination takes minutes and the arrival/completion hook never gets
            // exercised in a test cycle. Test-only concern: real GPS mode ignores it.
            runCatching { navi.setEmulatorNaviSpeed(EMULATOR_SPEED_KMH) }
            DebugVoiceLog.log("nav_emulator_speed kmh=$EMULATOR_SPEED_KMH")
        }
        val mode = if (emulator) NaviType.EMULATOR else NaviType.GPS
        val accepted = runCatching { navi.startNavi(mode) }.getOrDefault(false)
        if (accepted) synchronized(navLock) { navigationActive = true }
        DebugVoiceLog.log("nav_start accepted=$accepted mode=$mode")
        // Read AFTER startNavi. This is the route navigation is actually following and is
        // the only sound evidence that selectRouteId(id) took effect: the
        // nav_route_info_prestart line above is sampled before the engine starts and was
        // measured stale on 2026-09-16, reporting the previous route's length after a
        // successful selection.
        if (accepted) {
            runCatching {
                navi.naviPath?.let { path ->
                    DebugVoiceLog.log("nav_active_route meters=${path.allLength} seconds=${path.allTime}")
                }
            }
        }
        return accepted
    }

    /**
     * Idempotent. [reason] distinguishes an automatic completion stop from a manual one.
     *
     * The guard matters because arrival and the manual `nav_stop` fallback can both land
     * here, and `stopNavi()` must be called exactly once. It also means the `nav_stopped`
     * line now genuinely proves the SDK call was reached, rather than being logged
     * unconditionally even when `navi` was null.
     */
    fun stopNavigation(reason: String = "manual"): Boolean {
        synchronized(navLock) {
            if (!navigationActive) {
                DebugVoiceLog.log("nav_stop_skipped reason=$reason already_inactive=true")
                return false
            }
            navigationActive = false
        }
        val navi = this.navi
        if (navi == null) {
            DebugVoiceLog.log("nav_stopped reached=false reason=$reason no_navi=true")
            return false
        }
        runCatching { navi.stopNavi() }
            .onSuccess { DebugVoiceLog.log("nav_stopped reached=true reason=$reason") }
            .onFailure { DebugVoiceLog.log("nav_stopped reached=false reason=$reason exception=true") }
        // Every termination path funnels through here -- arrival, emulator end, and the
        // manual nav_stop fallback -- so this one call is what keeps the state machine in
        // sync. The navigationActive guard above means it fires exactly once per session.
        onNavigationEndedCallback?.invoke(reason)
        return true
    }

    /** True while an emulator or GPS navigation session is running. */
    val isNavigating: Boolean
        get() = synchronized(navLock) { navigationActive }

    /** Route ids from the most recent successful calculation; empty until stage 5. */
    @Volatile
    var lastRouteIds: IntArray = IntArray(0)
        private set

    @Volatile
    private var onRoutesCalculated: ((IntArray) -> Unit)? = null

    @Volatile
    private var onRouteCalculationFailed: ((Int) -> Unit)? = null

    @Volatile
    private var onNavigationEndedCallback: ((String) -> Unit)? = null

    private fun ensureTraceListener() {
        if (traceListener != null) return
        val listener = NavigationTraceListener(
            onRouteReady = { ids ->
                lastRouteIds = ids
                onRoutesCalculated?.invoke(ids)
            },
            // Completion -> the SAME stop path as the manual nav_stop fallback.
            // No separate teardown implementation.
            onNavigationEnded = { reason -> stopNavigation(reason) },
            onRouteFailed = { code -> onRouteCalculationFailed?.invoke(code) },
        )
        traceListener = listener
        runCatching { navi?.addAMapNaviListener(listener) }
        DebugVoiceLog.log("nav_listener_registered")
    }

    /** Objective check for the P5 fix; no location value is exposed. */
    fun isGpsReady(): Boolean = navi?.isGpsReady == true

    private fun stopLocation() {
        if (!locationStarted) return
        locationStarted = false
        val stopped = navi?.stopGPS() ?: false
        DebugVoiceLog.log("amap_gps stopGPS=$stopped")
    }

    fun onCreate(savedInstanceState: Bundle?) {
        naviView.onCreate(savedInstanceState)
    }

    fun onResume() {
        naviView.onResume()
        startLocation()
    }

    fun onPause() {
        stopLocation()
        naviView.onPause()
    }

    fun onDestroy() {
        stopLocation()
        naviView.onDestroy()
        navi = null
        AMapNavi.destroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        naviView.onSaveInstanceState(outState)
    }
}
