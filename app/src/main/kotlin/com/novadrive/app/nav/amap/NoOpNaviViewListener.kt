package com.novadrive.app.nav.amap

import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AmapPageType

/**
 * `AMapNaviView` requires a listener; the SDK ships no adapter, so all 22 methods
 * are stubbed here. This file and [AmapNaviViewHost] are the only places permitted
 * to import `com.amap`.
 *
 * Phase 1 deliberately reacts to none of these — the milestone is a render proof.
 * Two are called out because they will matter and must not be silently swallowed later:
 *
 *  - [onNaviBackClick] returns false, i.e. "not consumed", so the platform back
 *    behaviour is unchanged. Returning true here would silently break back navigation.
 *  - [onNaviCancel] and [onAMapNaviViewExit] are Amap's own exit affordances. They are
 *    reachable today because the SDK's native controls are still visible (the 退出/全览
 *    buttons that overlap our bottom bar). When those are hidden with
 *    `AMapNaviViewOptions.setLayoutVisible(false)`, or when real navigation starts in
 *    Phase 2, these must route through `NavigationController` rather than do nothing.
 */
internal object NoOpNaviViewListener : AMapNaviViewListener {
    override fun onNaviSetting() = Unit

    override fun onNaviCancel() = Unit

    /** false = not consumed; leave platform back behaviour alone. */
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

    override fun onAMapNaviViewExit() = Unit

    override fun onStrategyChanged(strategy: Int) = Unit

    override fun onBroadcastModeChanged(mode: Int) = Unit

    override fun onDayAndNightModeChanged(mode: Int) = Unit

    override fun onScaleAutoChanged(auto: Boolean) = Unit

    override fun onListenToVoiceDuringCallChanged(enabled: Boolean) = Unit

    override fun onControlMusicVolumeModeChanged(mode: Int) = Unit

    override fun onEagleChanged(enabled: Boolean) = Unit

    override fun onNaviRouteHighlightChange(id: Long, value: Int) = Unit
}
