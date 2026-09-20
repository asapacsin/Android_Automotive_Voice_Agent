package com.novadrive.app.nav.amap

import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.enums.AMapNaviViewShowMode
import com.amap.api.navi.model.RouteOverlayOptions
import com.novadrive.app.DebugVoiceLog

/**
 * Native Amap driving presentation. The host calls these instead of tilting the idle map.
 *
 * APIs verified against navi-3dmap 11.2.100 (`javap` on the resolved artifact).
 */
internal object AmapDrivingPresentation {

    /** Whole-route, north-up-ish overview while the driver picks a path. */
    fun applyRoutePreview(naviView: AMapNaviView) {
        applyOptions(naviView, driving = false, preview = true)
        runCatching { naviView.displayOverview() }
        DebugVoiceLog.log("nav_presentation mode=preview")
    }

    /** Lock-car driving HUD. Must run when `startNavi` is accepted, not as a later cosmetic. */
    fun applyDriving(navi: AMapNavi?, naviView: AMapNaviView) {
        navi?.let { enableLiveTraffic(it) }
        applyOptions(naviView, driving = true, preview = false)
        lockCar(naviView)
        DebugVoiceLog.log("nav_presentation mode=driving naviMode=car_up trafficLine=true layout=true")
    }

    /** Browse map after navigation ends. Native navi chrome is put away so it does not cover the bar. */
    fun applyIdle(naviView: AMapNaviView) {
        applyOptions(naviView, driving = false, preview = false)
        DebugVoiceLog.log("nav_presentation mode=idle")
    }

    fun lockCar(naviView: AMapNaviView) {
        runCatching { naviView.setNaviMode(AMapNaviView.CAR_UP_MODE) }
        runCatching { naviView.setShowMode(AMapNaviViewShowMode.SHOW_MODE_LOCK_CAR) }
        runCatching { naviView.recoverLockMode() }
        runCatching { naviView.setTrafficLine(true) }
        runCatching { naviView.setCarOverlayVisible(true) }
        runCatching { naviView.setTrafficLightsVisible(true) }
        runCatching { naviView.setShowTrafficLightView(true) }
        runCatching { naviView.setShowDriveCongestion(true) }
    }

    fun showOverview(naviView: AMapNaviView): Boolean =
        runCatching {
            naviView.displayOverview()
            DebugVoiceLog.log("nav_presentation mode=overview")
            true
        }.getOrDefault(false)

    fun resumeTracking(naviView: AMapNaviView): Boolean =
        runCatching {
            lockCar(naviView)
            DebugVoiceLog.log("nav_presentation mode=driving recovered=true")
            true
        }.getOrDefault(false)

    fun listener(onExit: () -> Unit): AMapNaviViewListener = ExitForwardingListener(onExit)

    private fun enableLiveTraffic(navi: AMapNavi) {
        runCatching { navi.setTrafficStatusUpdateEnabled(true) }
        runCatching { navi.setTrafficInfoUpdateEnabled(true) }
        runCatching { navi.setCameraInfoUpdateEnabled(true) }
        runCatching { navi.setTrafficSignalEnable(true) }
    }

    private fun applyOptions(naviView: AMapNaviView, driving: Boolean, preview: Boolean) {
        runCatching {
            val options = naviView.viewOptions ?: AMapNaviViewOptions()
            options.setLayoutVisible(driving)
            options.setAutoLockCar(driving)
            options.setNaviMode(if (driving) AMapNaviView.CAR_UP_MODE else AMapNaviView.NORTH_UP_MODE)
            options.setTrafficLine(driving || preview)
            options.setTrafficLayerEnabled(java.lang.Boolean.TRUE)
            options.setTrafficBarEnabled(if (driving) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE)
            options.setNaviArrowVisible(driving)
            options.setLaneInfoShow(driving)
            options.setModeCrossDisplayShow(driving)
            options.setRealCrossDisplayShow(driving)
            options.setAutoChangeZoom(driving)
            options.setAutoDisplayOverview(preview)
            options.setCameraBubbleShow(driving)
            options.setShowCameraDistance(driving)
            options.setSecondActionVisible(driving)
            options.setNaviStatusBarEnabled(driving)
            options.setCompassEnabled(java.lang.Boolean.valueOf(driving || preview))
            options.setRouteListButtonShow(false)
            options.setSensorEnable(driving)
            options.setLockMapDelayed(if (driving) LOCK_MAP_DELAY_MS else 0L)
            if (driving) {
                options.setTilt(DRIVING_TILT_DEG)
                options.setPointToCenter(LOCK_CENTER_X, LOCK_CENTER_Y)
            } else {
                options.setTilt(0)
            }
            val overlay = options.routeOverlayOptions ?: RouteOverlayOptions()
            overlay.setTurnArrowIs3D(driving)
            overlay.setOnRouteCameShow(driving)
            options.setRouteOverlayOptions(overlay)
            naviView.viewOptions = options
        }
    }

    private const val DRIVING_TILT_DEG = 45
    private const val LOCK_CENTER_X = 0.5
    private const val LOCK_CENTER_Y = 0.75
    private const val LOCK_MAP_DELAY_MS = 5_000L
}

/**
 * Native 退出 / cancel must stop our session. Other view events are notifications:
 * the SDK already performed the corresponding UI change.
 */
internal class ExitForwardingListener(
    private val onExit: () -> Unit,
) : AMapNaviViewListener {
    override fun onNaviSetting() = Unit
    override fun onNaviCancel() = onExit()
    override fun onNaviBackClick(): Boolean = false
    override fun onNaviMapMode(mode: Int) = Unit
    override fun onNaviTurnClick() = Unit
    override fun onNextRoadClick() = Unit
    override fun onScanViewButtonClick() = Unit
    override fun onLockMap(locked: Boolean) = Unit
    override fun onNaviViewLoaded() = Unit
    override fun onMapTypeChanged(type: Int) = Unit
    override fun onNaviViewShowMode(mode: Int) = Unit
    override fun onStopSpeaking() = Unit
    override fun onViewTypeChanged(pageType: AmapPageType?) = Unit
    override fun onAMapNaviViewExit() = onExit()
    override fun onStrategyChanged(strategy: Int) = Unit
    override fun onBroadcastModeChanged(mode: Int) = Unit
    override fun onDayAndNightModeChanged(mode: Int) = Unit
    override fun onScaleAutoChanged(auto: Boolean) = Unit
    override fun onListenToVoiceDuringCallChanged(enabled: Boolean) = Unit
    override fun onControlMusicVolumeModeChanged(mode: Int) = Unit
    override fun onEagleChanged(enabled: Boolean) = Unit
    override fun onNaviRouteHighlightChange(id: Long, value: Int) = Unit
}
