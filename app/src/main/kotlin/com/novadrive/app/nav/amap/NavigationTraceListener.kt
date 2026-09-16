package com.novadrive.app.nav.amap

import android.os.Bundle
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.novadrive.app.DebugVoiceLog

/**
 * Registered via `AMapNavi.addAMapNaviListener(...)`.
 *
 * Nothing previously registered this. `AmapNaviViewHost` only had an
 * `AMapNaviViewListener`, which reports *view* events — it never sees
 * `onCalculateRouteSuccess` / `onCalculateRouteFailure` / `onStartNavi`. So route
 * calculation could not have reported success OR a concrete error: it would have
 * failed silently. This listener is the instrumentation for stages 5-7.
 *
 * Logging rule: fixed strings plus SDK error codes only. Never a coordinate, an
 * address, or guidance text — those are personal/location data.
 */
internal class NavigationTraceListener(
    private val onRouteReady: (routeIds: IntArray) -> Unit,
    private val onNavigationEnded: (reason: String) -> Unit,
    private val onRouteFailed: (errorCode: Int) -> Unit = {},
) : AMapNaviListener {

    // ---- stage 5: route calculation outcome -------------------------------

    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        val ids = routeIds ?: IntArray(0)
        DebugVoiceLog.log("nav_calc_success routes=${ids.size}")
        onRouteReady(ids)
    }

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        // Newer overload; the IntArray one is the primary trigger so we only trace here.
        DebugVoiceLog.log("nav_calc_success_v2 type=${result?.calcRouteType}")
    }

    override fun onCalculateRouteFailure(errorCode: Int) {
        DebugVoiceLog.log("nav_calc_failure code=$errorCode")
        onRouteFailed(errorCode)
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        // getErrorDescription is a vendor diagnostic string, not user data.
        DebugVoiceLog.log(
            "nav_calc_failure_v2 code=${result?.errorCode} detail=${result?.errorDescription}",
        )
    }

    // ---- stages 6-7: navigation start / engine state ----------------------

    override fun onStartNavi(type: Int) {
        DebugVoiceLog.log("nav_started type=$type")
    }

    override fun onInitNaviSuccess() {
        DebugVoiceLog.log("nav_init_success")
    }

    override fun onInitNaviFailure() {
        DebugVoiceLog.log("nav_init_failure")
    }

    /**
     * Completion in REAL (GPS) navigation.
     *
     * Measured 2026-09-16: this did NOT fire in emulator mode (`nav_arrived` count 0)
     * while [onEndEmulatorNavi] did. Both are wired because the modes signal differently,
     * and wiring only the one observed would leave real navigation never terminating —
     * the same defect, in the mode that actually matters.
     */
    override fun onArriveDestination() {
        DebugVoiceLog.log("nav_arrived")
        onNavigationEnded("arrived")
    }

    /**
     * Completion in EMULATOR navigation. Observed firing on arrival at 16:40:08, after
     * which nothing called stopNavi() — navigation stayed active for 2m42s until a manual
     * nav_stop. That gap is the defect this hook closes.
     */
    override fun onEndEmulatorNavi() {
        DebugVoiceLog.log("nav_emulator_end")
        onNavigationEnded("emulator_end")
    }

    override fun onGpsOpenStatus(open: Boolean) {
        DebugVoiceLog.log("nav_gps_open=$open")
    }

    override fun onGpsSignalWeak(weak: Boolean) {
        DebugVoiceLog.log("nav_gps_weak=$weak")
    }

    override fun onReCalculateRouteForYaw() {
        DebugVoiceLog.log("nav_recalc_yaw")
    }

    override fun onReCalculateRouteForTrafficJam() {
        DebugVoiceLog.log("nav_recalc_jam")
    }

    // ---- high-frequency callbacks: deliberately silent --------------------
    // onLocationChange and onNaviInfoUpdate fire continuously and carry position
    // data. They are not logged: it would be both noisy and a location-data leak.

    override fun onLocationChange(location: AMapNaviLocation?) = Unit

    override fun onNaviInfoUpdate(info: NaviInfo?) = Unit

    override fun onGetNavigationText(type: Int, text: String?) = Unit

    override fun onGetNavigationText(text: String?) = Unit

    override fun onTrafficStatusUpdate() = Unit

    override fun onArrivedWayPoint(index: Int) = Unit

    override fun updateCameraInfo(info: Array<out AMapNaviCameraInfo>?) = Unit

    override fun updateIntervalCameraInfo(a: AMapNaviCameraInfo?, b: AMapNaviCameraInfo?, c: Int) = Unit

    override fun onServiceAreaUpdate(info: Array<out AMapServiceAreaInfo>?) = Unit

    override fun showCross(cross: AMapNaviCross?) = Unit

    override fun hideCross() = Unit

    override fun showModeCross(cross: AMapModelCross?) = Unit

    override fun hideModeCross() = Unit

    override fun showLaneInfo(lanes: Array<out AMapLaneInfo>?, a: ByteArray?, b: ByteArray?) = Unit

    override fun showLaneInfo(lane: AMapLaneInfo?) = Unit

    override fun hideLaneInfo() = Unit

    override fun notifyParallelRoad(type: Int) = Unit

    override fun OnUpdateTrafficFacility(info: Array<out AMapNaviTrafficFacilityInfo>?) = Unit

    override fun OnUpdateTrafficFacility(info: AMapNaviTrafficFacilityInfo?) = Unit

    override fun updateAimlessModeStatistics(stat: AimLessModeStat?) = Unit

    override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit

    override fun onPlayRing(type: Int) = Unit

    override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) = Unit
}
