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
    @Volatile
    private var lastUiStateLabel: String = VoiceUiState.DISCONNECTED.label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugVoiceLog.init(this)
        settingsRepository = BaiduSettingsRepository(this)
        val player = PcmAudioPlayer { code -> mainHandler.post { showError(code, "playback failed") } }
        val toolDispatcher = AndroidToolDispatcher(
            SafeAndroidActionExecutor(this),
            ClimateToolHandler(VehicleControlProvider.port),
            VisionProvider.handler(this),
        )
        controller =
            VoiceSessionController(
                context = this,
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
                onListeningState = { state -> mainHandler.post { if (::screen.isInitialized) screen.bindListening(state) } },
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
                onCameraToggleRequested = { toggleCamera() }
                onCameraPermissionNeeded = { runOnUiThread { requestCameraPermission() } }
                onListeningToggle = { toggleListening() }
            }
        setContentView(screen)
        screen.onCreate(savedInstanceState)
        com.novadrive.app.voice.SpeechOutput.addListener(silentListener)
        screen.bindSilent(com.novadrive.app.voice.SpeechOutput.silent)
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
        com.novadrive.app.voice.SpeechOutput.removeListener(silentListener)
        BundledMusicPlayer.stop()
        VoiceSessionService.stop(this)
        if (::controller.isInitialized) controller.release()
        if (::screen.isInitialized) screen.onDestroy()
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
     * (STANDBY); otherwise it resumes or starts a session exactly like the wake word.
     */
    private val silentListener: (Boolean) -> Unit = { silent ->
        mainHandler.post { if (::screen.isInitialized) screen.bindSilent(silent) }
    }

    private fun toggleListening() {
        // While silent, a tap first gives 小诺 its voice back.
        if (com.novadrive.app.voice.SpeechOutput.silent) {
            com.novadrive.app.voice.SpeechOutput.setSilent(false, "ui")
            return
        }
        if (VoiceSessionGateway.listeningState == com.novadrive.app.voice.ListeningState.ACTIVE) {
            VoiceSessionGateway.standby("ui")
            return
        }
        when (val result = VoiceSessionGateway.start("ui")) {
            com.novadrive.app.voice.StartResult.MicPermissionMissing ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            is com.novadrive.app.voice.StartResult.ConfigInvalid -> showError("CONFIG", result.message)
            else -> Unit
        }
    }

    private fun toggleCamera() {
        if (screen.cameraShowing) {
            screen.hideCamera()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        screen.showCamera()
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
