package com.novadrive.app.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.nav.amap.AmapNaviViewHost
import com.novadrive.app.vehicle.VehicleControlProvider
import com.novadrive.app.vision.CameraVisionGateway
import com.novadrive.app.vision.VisionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.novadrive.ingress.realtime.VoiceUiState

class AssistantNavigationScreen(context: Context) : FrameLayout(context) {
    var onCameraToggleRequested: (() -> Unit)? = null

    /** Asked by the camera when a vision request finds no camera permission. */
    var onCameraPermissionNeeded: (() -> Unit)?
        get() = camera.onPermissionNeeded
        set(value) {
            camera.onPermissionNeeded = value
        }
    var onOpenDeveloperSettings: (() -> Unit)? = null
        set(value) {
            field = value
            overlay.onOpenDeveloperSettings = value
        }

    private val mapHost = AmapNaviViewHost(context)
    private val overlay = AssistantOverlayView(context)
    private val choiceOverlay = NavigationChoiceOverlay(context)
    private val bottomBar = BottomBarView(context)
    private val camera = CameraPreviewView(context)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val cameraShowing: Boolean
        get() = camera.isShowing

    init {
        addView(mapHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            choiceOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(64)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )
        addView(camera, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        camera.visibility = GONE
        bottomBar.onCameraClick = { onCameraToggleRequested?.invoke() }
        bottomBar.bindClimate(VehicleControlProvider.port)
        camera.onAskAi = {
            // Same handler the voice tool uses; the answer is shown on the camera view.
            uiScope.launch { VisionProvider.handler(context).ask(null) }
        }
        overlay.onOpenDeveloperSettings = { onOpenDeveloperSettings?.invoke() }
        choiceOverlay.bind(EmbeddedNavigation.shared(context))
    }

    fun onCreate(savedInstanceState: Bundle?) {
        mapHost.onCreate(savedInstanceState)
        NavigationHostGateway.attach(this, mapHost)
        CameraVisionGateway.attach(this, camera)
    }

    fun onResume() {
        mapHost.onResume()
    }

    fun onPause() {
        mapHost.onPause()
    }

    fun onDestroy() {
        uiScope.cancel()
        camera.hide()
        CameraVisionGateway.detach(this)
        NavigationHostGateway.detach(this)
        mapHost.onDestroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapHost.onSaveInstanceState(outState)
    }

    /**
     * P5: called after the FINE location permission result, because `onResume` has
     * already run by then and would otherwise have skipped the start.
     */
    fun startLocation(): Boolean = mapHost.startLocation()

    /** Objective verification hook for P5; exposes no location value. */
    fun isGpsReady(): Boolean = mapHost.isGpsReady()

    /**
     * Stage 4-6 entry point. Publishes the LIVE map host so a debug trigger can drive
     * route calculation without the LLM. Exposed as methods rather than leaking `mapHost`,
     * so callers cannot hold the view past its lifecycle.
     */
    fun calculateDriveRoute(endLat: Double, endLon: Double, endName: String): Boolean =
        mapHost.calculateDriveRoute(endLat, endLon, endName)

    fun startNavigation(emulator: Boolean): Boolean = mapHost.startNavigation(emulator)

    /** Returns whether an active session was actually stopped (false if already inactive). */
    fun stopNavigation(reason: String = "manual"): Boolean = mapHost.stopNavigation(reason)

    fun isNavigating(): Boolean = mapHost.isNavigating

    fun lastRouteCount(): Int = mapHost.lastRouteIds.size

    internal fun host(): AmapNaviViewHost = mapHost

    fun bindState(state: VoiceUiState, error: String?) {
        overlay.bindState(state, error)
    }

    fun appendTranscript(line: String) {
        overlay.appendTranscript(line)
    }

    fun showError(code: String, message: String) {
        overlay.showError(code, message)
    }

    fun showCamera() {
        camera.show()
    }

    fun hideCamera() {
        camera.hide()
    }

    fun toggleCamera() {
        camera.toggle()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
