package com.novadrive.app.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import com.novadrive.app.R
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.RecenterOutcome
import com.novadrive.app.nav.amap.AmapNaviViewHost
import com.novadrive.app.ScreenControls
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.app.vision.CameraVisionGateway
import com.novadrive.app.voice.VoiceSessionGateway
import com.novadrive.app.vision.VisionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.novadrive.ingress.realtime.VoiceUiState

class AssistantNavigationScreen(context: Context) : FrameLayout(context) {
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
        addView(mapHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            // Keep Amap's bottom HUD (speed, remaining) above the climate/music bar.
            bottomMargin = dp(64)
        })
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
        // Picture-in-picture, bottom right above the bar. 9:16 matches the portrait preview,
        // so the image is not squashed.
        addView(
            camera,
            LayoutParams(dp(CAMERA_PIP_WIDTH_DP), dp(CAMERA_PIP_WIDTH_DP * 16 / 9)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dp(CAMERA_PIP_BOTTOM_DP)
                rightMargin = dp(12)
            },
        )
        mapHost.attachSpeedHud(this)
        camera.onVisionText = { text -> overlay.appendTranscript("📷 $text") }
        camera.visibility = GONE
        overlay.onOpenDeveloperSettings = { onOpenDeveloperSettings?.invoke() }
        choiceOverlay.bind(EmbeddedNavigation.shared(context))
        uiScope.launch {
            EmbeddedNavigation.shared(context).state().collect { phase ->
                overlay.setDrivingChrome(phase == NavigationPhase.NAVIGATING)
            }
        }
    }

    /**
     * Supplied by the Activity, so this screen never names an executor or a backend itself
     * ([I-6](../../../../../../../docs/INVARIANTS.md)).
     */
    fun bindControls(controls: ScreenControls) {
        bottomBar.bind(controls)
    }

    fun onCreate(savedInstanceState: Bundle?) {
        mapHost.onCreate(savedInstanceState)
        NavigationHostGateway.attach(this, mapHost)
        CameraVisionGateway.attach(this, camera)
    }

    fun onResume() {
        mapHost.onResume()
        camera.onHostResume()
    }

    fun onPause() {
        camera.onHostPause()
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
     * 📍 — the way back to the current position after the driver has panned the map. The
     * automatic startup recentre deliberately gives up once they pan, so without this control
     * there would be no way to return.
     */
    fun recenterMap(): RecenterOutcome {
        val outcome = mapHost.recenterOnCurrentLocation()
        val message = when (outcome) {
            RecenterOutcome.MOVED -> null
            RecenterOutcome.NO_PERMISSION -> R.string.map_recenter_no_permission
            RecenterOutcome.NO_LOCATION_SERVICE -> R.string.map_recenter_no_service
            RecenterOutcome.NO_FIX -> R.string.map_recenter_no_fix
        }
        message?.let { overlay.appendTranscript(context.getString(it)) }
        return outcome
    }

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

    fun bindListening(state: com.novadrive.app.voice.ListeningState) {
        overlay.bindListening(state)
    }

    var onListeningToggle: (() -> Unit)?
        get() = overlay.onListeningToggle
        set(value) {
            overlay.onListeningToggle = value
        }

    fun appendTranscript(line: String) {
        overlay.appendTranscript(line)
    }

    fun showError(code: String, message: String) {
        overlay.showError(code, message)
    }

    /** Driver opened the camera: show it and have the assistant look once, without a button. */
    fun showCamera() {
        val wasShowing = camera.isShowing
        camera.show()
        if (!wasShowing) lookAndSpeak()
    }

    private var lookJob: kotlinx.coroutines.Job? = null

    /**
     * Captures the current view, asks the vision model, shows the answer in the bubble and has
     * the assistant say it in its own voice (the phone has no default system TTS engine).
     * One look at a time. Each look is a paid vision call, so this runs once per opening, not
     * continuously; later questions go through the describe_camera_view voice tool.
     */
    private fun lookAndSpeak() {
        if (lookJob?.isActive == true) return
        lookJob = uiScope.launch {
            val outcome = VisionProvider.handler(context).ask(null)
            com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
            val prompt = if (outcome.ok) {
                CameraQuestionHandler.cameraOpenedPrompt(outcome.spokenText)
            } else {
                CameraQuestionHandler.readAloudPrompt(outcome.spokenText)
            }
            val result = VoiceSessionGateway.speak(prompt)
            DebugVoiceLog.log("vision_speak ok=${outcome.ok} result=${result::class.simpleName}")
        }
    }

    fun hideCamera() {
        camera.hide()
    }

    fun toggleCamera() {
        if (camera.isShowing) hideCamera() else showCamera()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CAMERA_PIP_WIDTH_DP = 135
        const val CAMERA_PIP_BOTTOM_DP = 76
    }
}
