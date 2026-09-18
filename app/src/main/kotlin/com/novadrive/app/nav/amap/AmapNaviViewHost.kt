package com.novadrive.app.nav.amap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.TTSPlayListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviPoi
import com.novadrive.app.CoarseLocationProvider
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.nav.InitialLocationRecenter
import com.novadrive.app.nav.LocationFix
import com.novadrive.app.nav.NavigationGuidanceVoice
import com.novadrive.app.nav.RecenterDecision
import com.novadrive.app.nav.RecenterOutcome
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
    private val recenter = InitialLocationRecenter()
    private val platformLocation = CoarseLocationProvider(context)
    private var panWatchAttached = false

    /** Newest fix seen this app start, already in GCJ-02. Held only so 📍 has something to use. */
    @Volatile
    private var lastFix: LocationFix? = null

    /** Cancels the outstanding platform fix request; null when none is in flight. */
    @Volatile
    private var pendingFixRequest: (() -> Unit)? = null

    private companion object {
        /** Emulator-only: fast enough to reach a nearby destination within a test cycle. */
        const val EMULATOR_SPEED_KMH = 120

        /** Street level: close enough to recognise where you are, wide enough to orient. */
        const val IDLE_ZOOM = 16f
    }

    init {
        AmapPrivacyCompliance.ensure(context)
        AMapNavi.addTTSInitializeListener { code, _ -> DebugVoiceLog.log("nav_guidance_tts_init code=$code") }
        navi = AMapNavi.getInstance(context.applicationContext)
        enableGuidanceVoice()
        naviView = AMapNaviView(context)
        naviView.setAMapNaviViewListener(NoOpNaviViewListener)
        addView(naviView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Spoken turn-by-turn guidance. Never enabled before 2026-09-17: navigation ran silently
     * because the SDK's own voice is off unless asked for, and the guidance text callback was
     * discarded. The SDK ships an offline Mandarin voice (assets/tts), so no extra service.
     *
     * The play listener is what keeps guidance out of the assistant's ears (P3): see
     * [NavigationGuidanceVoice]. Logs never carry the guidance text (it names places).
     */
    private object GuidancePlayListener : TTSPlayListener {
        override fun onPlayStart(text: String?) {
            DebugVoiceLog.log("nav_guidance_play_start chars=${text?.length ?: 0}")
            NavigationGuidanceVoice.onPlayStart()
        }

        override fun onPlayEnd(text: String?) {
            DebugVoiceLog.log("nav_guidance_play_end")
            NavigationGuidanceVoice.onPlayEnd()
        }
    }

    private fun enableGuidanceVoice() {
        val navi = navi ?: return
        runCatching {
            navi.setUseInnerVoice(true, false)
            navi.addTTSPlayListener(GuidancePlayListener)
        }.onSuccess {
            DebugVoiceLog.log("nav_guidance_voice enabled=${navi.isUseInnerVoiceSafe()}")
        }.onFailure {
            DebugVoiceLog.log("nav_guidance_voice enabled=false exception=${it.javaClass.simpleName}")
        }
    }

    private fun AMapNavi.isUseInnerVoiceSafe(): Boolean = runCatching { getIsUseInnerVoice() }.getOrDefault(false)

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
        // Registering here, not only at route calculation, is what makes onLocationChange fire
        // while the app is merely idling on the map — the fixes the startup recentre needs.
        ensureTraceListener()
        val accepted = navi?.startGPS() ?: false
        locationStarted = accepted
        val servicesOn = platformLocation.locationServicesEnabled()
        DebugVoiceLog.log("amap_gps startGPS=$accepted location_services=$servicesOn")
        enableMyLocation()
        watchManualPan()
        seedFromLastKnown()
        requestFreshPlatformFix()
        return accepted
    }

    // ---- startup recentre --------------------------------------------------

    /**
     * A cached platform fix, so the map is roughly right in the second before GNSS answers.
     * It is WGS-84 and the map is GCJ-02, so it is converted here — this is the only file that
     * may touch the SDK. A fix the policy judges stale is dropped, never shown as "you are here".
     */
    private fun seedFromLastKnown() {
        if (recenter.settled || recenter.stopped) return
        val raw = platformLocation.lastKnownFix() ?: run {
            DebugVoiceLog.log("map_recenter_seed available=false")
            return
        }
        applyFix(raw.toGcj02(), source = "last_known")
    }

    /**
     * A live fix from the platform, in parallel with the SDK's own stream. Whichever answers
     * first recentres the map; the policy makes the second one a no-op. Cancelled on pause, and
     * skipped entirely once the camera has settled or the driver has taken over.
     */
    private fun requestFreshPlatformFix() {
        if (recenter.settled || recenter.stopped) return
        if (pendingFixRequest != null) return
        pendingFixRequest = platformLocation.requestSingleFix { raw ->
            pendingFixRequest = null
            DebugVoiceLog.log("map_recenter_fix source=platform")
            applyFix(raw.toGcj02(), source = "platform")
        }
        DebugVoiceLog.log("map_recenter_request requested=${pendingFixRequest != null}")
    }

    /** Android reports WGS-84; every Amap surface expects GCJ-02. Skew is ~100-500 m if skipped. */
    private fun LocationFix.toGcj02(): LocationFix = runCatching {
        val converted = CoordinateConverter(context)
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(latitude, longitude))
            .convert()
        copy(latitude = converted.latitude, longitude = converted.longitude)
    }.getOrElse { this }

    /**
     * Every fix, from either source, funnels through here. The decision of whether it moves the
     * camera belongs to [InitialLocationRecenter], which is plain Kotlin and unit-tested.
     */
    private fun applyFix(fix: LocationFix, source: String) {
        lastFix = fix
        // While navigating, AMapNaviView owns its camera (it locks to the vehicle). Moving it
        // from here would fight the SDK for control mid-drive.
        if (isNavigating) return
        val decision = recenter.decide(fix, System.currentTimeMillis())
        if (decision == RecenterDecision.IGNORE) return
        post { moveCameraTo(fix, source = source, why = decision.name.lowercase()) }
    }

    /** Logs the decision and the source only — never the coordinate. */
    private fun moveCameraTo(fix: LocationFix, source: String, why: String): Boolean {
        val map = runCatching { naviView.map }.getOrNull()
        if (map == null) {
            DebugVoiceLog.log("map_recenter ok=false source=$source why=$why reason=no_map")
            return false
        }
        return runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), IDLE_ZOOM))
        }.onSuccess {
            DebugVoiceLog.log("map_recenter ok=true source=$source why=$why")
        }.onFailure {
            DebugVoiceLog.log("map_recenter ok=false source=$source why=$why reason=exception")
        }.isSuccess
    }

    /**
     * A drag on the map means the driver chose what to look at. Only ACTION_MOVE counts: a tap
     * on a POI is not a decision to stop following the current position.
     */
    private fun watchManualPan() {
        if (panWatchAttached) return
        val map = runCatching { naviView.map }.getOrNull() ?: return
        runCatching {
            map.addOnMapTouchListener { event ->
                if (event?.action == MotionEvent.ACTION_MOVE && !recenter.stopped) {
                    recenter.onUserMovedCamera()
                    DebugVoiceLog.log("map_recenter_stopped reason=user_pan")
                }
            }
            panWatchAttached = true
        }
    }

    /**
     * The 📍 control. Manual, so it always moves if a position is known — the once-per-start rule
     * governs the automatic move only. Reports why it could not, so the UI can say something
     * truthful instead of nothing happening.
     */
    fun recenterOnCurrentLocation(): RecenterOutcome {
        if (!hasFineLocation()) {
            DebugVoiceLog.log("map_recenter_manual outcome=no_permission")
            return RecenterOutcome.NO_PERMISSION
        }
        val fix = lastFix ?: platformLocation.lastKnownFix()?.toGcj02()
        if (fix == null) {
            val outcome =
                if (!platformLocation.locationServicesEnabled()) {
                    RecenterOutcome.NO_LOCATION_SERVICE
                } else {
                    RecenterOutcome.NO_FIX
                }
            DebugVoiceLog.log("map_recenter_manual outcome=$outcome")
            return outcome
        }
        val moved = moveCameraTo(fix, source = "manual", why = "driver_request")
        DebugVoiceLog.log("map_recenter_manual outcome=${if (moved) "moved" else "failed"}")
        return if (moved) RecenterOutcome.MOVED else RecenterOutcome.NO_FIX
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
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
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
            onLocation = { location -> applyFix(location.toFix(), source = "sdk") },
        )
        traceListener = listener
        runCatching { navi?.addAMapNaviListener(listener) }
        DebugVoiceLog.log("nav_listener_registered")
    }

    /**
     * SDK fixes are already GCJ-02, so no conversion. `getTime()` is a boxed Long and has been
     * seen null; 0 then means "no timestamp", which the policy reads as a live fix.
     */
    private fun AMapNaviLocation.toFix(): LocationFix {
        val coord = coord
        return LocationFix(
            latitude = coord?.latitude ?: Double.NaN,
            longitude = coord?.longitude ?: Double.NaN,
            timeMs = runCatching { time ?: 0L }.getOrDefault(0L),
            accuracyMeters = accuracy,
        )
    }

    /** Objective check for the P5 fix; no location value is exposed. */
    fun isGpsReady(): Boolean = navi?.isGpsReady == true

    private fun stopLocation() {
        // Unconditional: a fix request can be outstanding even when startGPS was refused, and
        // leaving a LocationListener registered past onPause drains the battery.
        pendingFixRequest?.invoke()
        pendingFixRequest = null
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
        runCatching { navi?.removeTTSPlayListener(GuidancePlayListener) }
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
        navi = null
        AMapNavi.destroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        naviView.onSaveInstanceState(outState)
    }
}
