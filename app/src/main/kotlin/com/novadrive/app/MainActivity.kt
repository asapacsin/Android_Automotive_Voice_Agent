package com.novadrive.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.novadrive.app.voice.BackendVoiceClient
import com.novadrive.app.voice.PcmAudioCapture
import com.novadrive.app.voice.PcmAudioPlayer
import com.novadrive.app.voice.VoiceSessionController
import com.novadrive.contracts.CoordinateSystem
import com.novadrive.contracts.Destination
import com.novadrive.contracts.GeoCoordinate
import com.novadrive.contracts.StructuredCommand
import com.novadrive.ingress.ReplaySpeechToSpeechPort
import com.novadrive.ingress.realtime.VoiceUiState
import com.novadrive.orchestration.VoiceSessionOrchestrator
import com.novadrive.safety.BootstrapSafetyPolicy
import com.novadrive.simulator.InMemoryVehicleSimulator

class MainActivity : Activity() {
    private lateinit var stateView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var errorView: TextView
    private lateinit var micButton: Button
    private lateinit var controller: VoiceSessionController
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player =
            PcmAudioPlayer { code ->
                mainHandler.post { showError(code, "playback failed") }
            }
        val client =
            BackendVoiceClient(
                onEvent = { },
                onStateLabel = { },
                onRawError = { code, message -> mainHandler.post { showError(code, message) } },
            )
        controller =
            VoiceSessionController(
                context = this,
                player = player,
                client = client,
                onUiState = { state, error ->
                    mainHandler.post { renderState(state, error) }
                },
                onTranscript = { line ->
                    mainHandler.post { appendTranscript(line) }
                },
                onError = { code, message ->
                    mainHandler.post { showError(code, message) }
                },
            )

        stateView = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE) }
        transcriptView = TextView(this).apply { textSize = 16f }
        errorView = TextView(this).apply { setTextColor(Color.parseColor("#FF8A80")) }
        micButton =
            Button(this).apply {
                text = getString(R.string.mic_start)
                setOnClickListener { toggleSession() }
            }
        val micTest =
            Button(this).apply {
                text = "麦克风测试"
                setOnClickListener { runMicTest() }
            }
        val demo =
            Button(this).apply {
                text = "结构化演示（人民广场）"
                setOnClickListener { runStructuredDemo() }
            }
        val settings =
            Button(this).apply {
                text = getString(R.string.developer_settings)
                visibility = if (isDebuggable()) View.VISIBLE else View.GONE
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, DeveloperSettingsActivity::class.java))
                }
            }

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(this@MainActivity).apply { text = "小诺"; textSize = 28f })
                addView(stateView)
                addView(errorView)
                addView(transcriptView)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "测试语句：${getString(R.string.test_phrase)}"
                        setPadding(0, 24, 0, 12)
                    },
                )
                addView(micButton)
                addView(micTest)
                addView(demo)
                addView(settings)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "凭证只写在 backend/.env。默认百度 Lite Near。App 不输入 API Key。"
                        setPadding(0, 24, 0, 0)
                    },
                )
            }
        setContentView(ScrollView(this).apply { addView(column) })
        renderState(VoiceUiState.DISCONNECTED, null)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.release()
        }
        super.onDestroy()
    }

    private fun toggleSession() {
        if (controller.sessionActiveNow) {
            controller.stop()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        controller.start(DeveloperOptions.backendUrl(this), DeveloperOptions.model(this))
        micButton.text = getString(R.string.mic_stop)
    }

    private fun runMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_TEST)
            return
        }
        errorView.text = "麦克风测试中…"
        val capture =
            PcmAudioCapture(
                onFrame = { bytes ->
                    var max = 0
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                        val mag = kotlin.math.abs(sample.toShort().toInt())
                        if (mag > max) max = mag
                        i += 2
                    }
                    mainHandler.post { errorView.text = "麦克风测试峰值: $max（未发送到后端）" }
                },
                onError = { code -> mainHandler.post { showError(code, "mic test failed") } },
            )
        capture.start()
        mainHandler.postDelayed({ capture.stop() }, 600)
    }

    private fun runStructuredDemo() {
        val result =
            ReplaySpeechToSpeechPort().startSession(
                VoiceSessionOrchestrator(BootstrapSafetyPolicy(), InMemoryVehicleSimulator()),
            ).onStructuredFunctionCall(
                StructuredCommand.StartNavigation(
                    correlationId = "app-nav-people-square",
                    destination = Destination(
                        label = "人民广场",
                        poiName = "人民广场",
                        coordinate = GeoCoordinate(31.2304, 121.4737, CoordinateSystem.GCJ02),
                    ),
                ),
            )
        appendTranscript("演示: ${result.feedbackZhCn}")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showError("MIC_PERMISSION_DENIED", "microphone permission denied")
            return
        }
        if (requestCode == REQ_MIC) toggleSession()
        if (requestCode == REQ_MIC_TEST) runMicTest()
    }

    private fun renderState(state: VoiceUiState, error: String?) {
        stateView.text = "状态: ${state.label}"
        micButton.text = if (controller.sessionActiveNow) getString(R.string.mic_stop) else getString(R.string.mic_start)
        if (state != VoiceUiState.ERROR) {
            errorView.text = ""
        } else if (error != null) {
            errorView.text = error
        }
    }

    private fun appendTranscript(line: String) {
        val current = transcriptView.text?.toString().orEmpty()
        transcriptView.text = if (current.isBlank()) line else "$current\n$line"
    }

    private fun showError(code: String, message: String) {
        errorView.text = "$code\n$message"
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        private const val REQ_MIC = 21
        private const val REQ_MIC_TEST = 22
    }
}
