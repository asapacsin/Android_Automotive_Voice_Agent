package com.novadrive.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vehicle.VehicleControlProvider
import com.novadrive.app.vision.VisionProvider
import android.os.Handler
import android.os.Looper
import com.novadrive.app.ui.AssistantNavigationScreen
import com.novadrive.app.voice.PcmAudioPlayer
import com.novadrive.app.voice.SessionServiceControl
import com.novadrive.app.voice.VoiceSessionController
import com.novadrive.app.voice.VoiceSessionGateway
import com.novadrive.ingress.realtime.VoiceUiState

class MainActivity : Activity() {
    private lateinit var controller: VoiceSessionController
    private lateinit var settingsRepository: BaiduSettingsRepository
    private lateinit var screen: AssistantNavigationScreen
    private val mainHandler = Handler(Looper.getMainLooper())
    private val affordanceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private lateinit var affordanceRunner: ScreenAffordanceRunner
    @Volatile
    private var lastUiStateLabel: String = VoiceUiState.DISCONNECTED.label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugVoiceLog.init(this)
        // The wake detector's process-lifetime owner. It used to be bound inside
        // DebugVoiceLog.init, which is a logging initialiser: one guard added there for a
        // sensible logging reason would have silently taken the wake word with it.
        com.novadrive.app.wake.WakeWordController.bind(this)
        settingsRepository = BaiduSettingsRepository(this)
        val player = PcmAudioPlayer { code -> mainHandler.post { showError(code, "playback failed") } }
        val actionExecutor = SafeAndroidActionExecutor(this)
        val climateHandler = ClimateToolHandler(VehicleControlProvider.port)
        val savedPlaces = com.novadrive.app.nav.SavedPlaceStore(this)
        val placeLookup = com.novadrive.app.nav.LiveDestinationCandidateSource(this)
        val toolDispatcher = AndroidToolDispatcher(
            actionExecutor,
            climateHandler,
            VisionProvider.handler(this),
            phone = PhoneCallTool(com.novadrive.app.phone.PhoneProvider.port(this)),
            places = SavedPlaceTool(
                read = savedPlaces::get,
                write = savedPlaces::set,
                // The same search the driver's own 「导航去X」 uses, so a saved place lands where
                // navigating to that address would have landed.
                resolve = { address ->
                    kotlinx.coroutines.runBlocking { placeLookup.resolve(address) }.firstOrNull()
                },
            ),
        )
        // The screen reaches the executors the same way a spoken command does (I-6, TECH_DEBT D-3):
        // the same instances, not a second route to the same ports.
        val screenControls = ExecutorScreenControls(
            executor = actionExecutor,
            climatePort = VehicleControlProvider.port,
            climateHandler = climateHandler,
            musicPlaying = BundledMusicPlayer.playing,
            // Tap and voice both come here (I-6); the screen only renders the result.
            recenterMap = { screen.recenterMap() },
            cameraToggle = { toggleCamera() },
        )
        affordanceRunner = ScreenAffordanceRunner(
            affordances = com.novadrive.app.ui.ScreenAffordances.shared,
            controls = screenControls,
            context = { com.novadrive.app.voice.DriverContext.currentOrNull() },
            scope = affordanceScope,
            log = { DebugVoiceLog.log(it) },
            reportFailure = { code -> controller.sendText(ScreenAffordanceRunner.failureMessage(code)) },
        )
        controller =
            VoiceSessionController(
                appContext = this,
                player = player,
                onUiState = { state, error ->
                    DebugVoiceLog.log("state=$state err=${error ?: "-"}")
                    lastUiStateLabel = state.label
                    VoiceSessionService.update(this, lastUiStateLabel, error)
                    mainHandler.post { renderState(state, error) }
                },
                onTranscript = { line ->
                    DebugVoiceLog.log("transcript=$line")
                    VoiceSessionService.update(this, lastUiStateLabel, line)
                    mainHandler.post { appendTranscript(line) }
                },
                onError = { code, message ->
                    DebugVoiceLog.log("error=$code $message")
                    mainHandler.post { showError(code, message) }
                },
                onToolCall = { call ->
                    val dispatched = toolDispatcher.dispatch(call)
                    DebugVoiceLog.log(
                        "tool=${call.name} args=${call.arguments.keys} result=${dispatched.successChip ?: dispatched.blockedReason}",
                    )
                    VoiceSessionService.update(
                        this,
                        lastUiStateLabel,
                        dispatched.blockedReason ?: "已执行 ${call.name}",
                    )
                    dispatched
                },
                onListeningState = { state ->
                    com.novadrive.app.wake.WakeWordController.reconcile(this)
                    mainHandler.post { if (::screen.isInitialized) screen.bindListening(state) }
                },
                onLocalNavigationPick = { choice ->
                    val args = when (choice) {
                        is com.novadrive.app.nav.NavigationChoice.Name -> mapOf("name" to choice.text)
                        is com.novadrive.app.nav.NavigationChoice.Index -> mapOf("index" to choice.position.toString())
                        is com.novadrive.app.nav.NavigationChoice.Preference -> mapOf("preference" to choice.kind.wire)
                    }
                    val call = com.novadrive.ingress.realtime.DomainVoiceEvent.ToolCall(
                        callId = "local_nav_${System.nanoTime()}",
                        name = AndroidToolDispatcher.CHOOSE_NAVIGATION_OPTION,
                        arguments = args,
                    )
                    toolDispatcher.dispatch(call)
                },
                onScreenAffordance = { text -> affordanceRunner.tryHandle(text) },
            )

        VoiceSessionGateway.attach(
            controller,
            object : SessionServiceControl {
                override fun start() = VoiceSessionService.start(this@MainActivity)
                override fun stop() = VoiceSessionService.stop(this@MainActivity)
                override fun hasMicPermission() =
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                override fun baiduConfig() = settingsRepository.config()
            },
        )

        screen =
            AssistantNavigationScreen(this).apply {
                onOpenDeveloperSettings = {
                    startActivity(Intent(this@MainActivity, DeveloperSettingsActivity::class.java))
                }
                onCameraPermissionNeeded = { runOnUiThread { requestCameraPermission() } }
                onListeningToggle = { toggleListening() }
            }
        setContentView(screen)
        screen.bindControls(screenControls)
        screen.onCreate(savedInstanceState)
        renderState(VoiceUiState.DISCONNECTED, null)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        // P5: the Amap SDK uses GNSS and needs FINE. Requesting only COARSE left FINE
        // declared-but-never-granted, so the SDK threw from addNmeaListener on every install.
        // Camera is asked for up front too (「看看前面有什么」), in the SAME request: Android shows
        // one permission dialog at a time and cancels a competing request.
        val startupPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (startupPermissions.isNotEmpty()) {
            requestPermissions(startupPermissions.toTypedArray(), REQ_LOCATION)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.onResume()
    }

    override fun onPause() {
        if (::screen.isInitialized) screen.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::screen.isInitialized) screen.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) VoiceSessionGateway.detach(controller)
        BundledMusicPlayer.stop()
        VoiceSessionService.stop(this)
        if (::controller.isInitialized) controller.release()
        if (::screen.isInitialized) screen.onDestroy()
        affordanceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) return
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) VoiceSessionGateway.start("ui")
            return
        }
        if (requestCode == REQ_LOCATION) {
            // The startup request may carry only CAMERA when location was already granted.
            if (Manifest.permission.ACCESS_FINE_LOCATION !in permissions) return
            // onResume has already run and skipped the start, so kick it here on grant.
            val granted =
                permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION).let { i ->
                    i >= 0 && i < grantResults.size && grantResults[i] == PackageManager.PERMISSION_GRANTED
                }
            if (granted && ::screen.isInitialized) {
                screen.startLocation()
            } else if (!granted) {
                // Log once; do not loop or crash. The map simply stays unlocated.
                DebugVoiceLog.log("amap_gps denied=fine_location")
            }
            return
        }
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                screen.showCamera()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // Measured 2026-09-17: after an earlier refusal Android (MIUI) denies instantly and
                // shows no dialog, so tapping 📷 appeared to do nothing. Send the driver to the one
                // place the permission can still be granted.
                DebugVoiceLog.log("camera_permission denied_without_dialog=true")
                android.widget.Toast.makeText(this, "请在设置中允许小诺使用相机", android.widget.Toast.LENGTH_LONG).show()
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    ),
                )
            } else {
                DebugVoiceLog.log("camera_permission denied=true")
            }
        }
    }

    /**
     * The listening indicator doubles as push-to-talk: while streaming it stops listening
     * (SLEEP); otherwise it resumes or starts a session exactly like the wake word.
     */
    private fun toggleListening() {
        if (VoiceSessionGateway.listeningState == com.novadrive.app.voice.ListeningState.ACTIVE) {
            VoiceSessionGateway.sleep("ui")
            return
        }
        // SILENT_WAIT, SLEEP, DEEP_IDLE: the tap wakes 小诺 like the wake word (push-to-talk).
        when (val result = VoiceSessionGateway.start("ui")) {
            com.novadrive.app.voice.StartResult.MicPermissionMissing ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            is com.novadrive.app.voice.StartResult.ConfigInvalid -> showError("CONFIG", result.message)
            else -> Unit
        }
    }

    private fun toggleCamera(): ScreenControls.Outcome {
        if (screen.cameraShowing) {
            screen.hideCamera()
            return ScreenControls.Outcome(true)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return ScreenControls.Outcome(false, "CAMERA_PERMISSION_REQUIRED")
        }
        screen.showCamera()
        return ScreenControls.Outcome(true)
    }

    /**
     * Pops the camera permission up without the driver hunting for it: the system dialog when
     * Android still allows one, otherwise (handled in onRequestPermissionsResult) the app's
     * settings page, which is the only place a permanently refused permission can be granted.
     */
    private fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun renderState(state: VoiceUiState, error: String?) {
        if (::screen.isInitialized) screen.bindState(state, error)
        if (!controller.sessionActiveNow) VoiceSessionService.stop(this)
    }

    private fun appendTranscript(line: String) {
        if (::screen.isInitialized) screen.appendTranscript(line)
    }

    private fun showError(code: String, message: String) {
        if (::screen.isInitialized) screen.showError(code, message)
    }

    companion object {
        private const val REQ_NOTIF = 23
        private const val REQ_LOCATION = 24
        private const val REQ_CAMERA = 25
        private const val REQ_MIC = 26
    }
}
