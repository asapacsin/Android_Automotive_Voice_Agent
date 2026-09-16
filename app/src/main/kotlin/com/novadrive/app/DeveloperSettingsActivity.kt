package com.novadrive.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.novadrive.app.voice.BaiduRealtimeClient
import com.novadrive.app.voice.BaiduFlexClient
import com.novadrive.app.voice.PcmAudioCapture
import com.novadrive.app.voice.StartResult
import com.novadrive.app.voice.VoiceSessionGateway
import com.novadrive.app.wake.WakeWordController
import com.novadrive.app.wake.WakeWordSettings
import com.novadrive.contracts.CoordinateSystem
import com.novadrive.contracts.Destination
import com.novadrive.contracts.GeoCoordinate
import com.novadrive.contracts.StructuredCommand
import com.novadrive.ingress.ReplaySpeechToSpeechPort
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderException
import com.novadrive.orchestration.VoiceSessionOrchestrator
import com.novadrive.safety.BootstrapSafetyPolicy
import com.novadrive.simulator.InMemoryVehicleSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DeveloperSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: BaiduSettingsRepository
    private lateinit var amapRepository: AmapSettingsRepository
    private lateinit var autoPickStatus: TextView
    private lateinit var voiceToggle: Button
    private lateinit var wakeToggle: Button
    private lateinit var result: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closeTestClient: (() -> Unit)? = null
    private var clearCredentials = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = BaiduSettingsRepository(this)
        amapRepository = AmapSettingsRepository(this)
        val saved = repository.loadSettings()

        val legacy = RadioButton(this).apply { text = "App ID + API Key + Secret Key"; id = View.generateViewId() }
        val bearer = RadioButton(this).apply { text = "Bearer API Key"; id = View.generateViewId() }
        val auth = RadioGroup(this).apply {
            addView(legacy); addView(bearer)
            check(if (saved.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) legacy.id else bearer.id)
        }
        val appId = secretField("App ID（留空保留已保存值）")
        val apiKey = secretField("API Key（留空保留已保存值）")
        val secretKey = secretField("Secret Key（留空保留已保存值）")
        val amapKey = secretField("高德 Web服务 Key（可选，留空保留已保存值）")
        val tokenEndpoint = EditText(this).apply { setText(saved.tokenEndpoint); hint = "OAuth token endpoint" }
        val legacyFields = verticalGroup("App ID", appId, "Secret Key", secretKey, "Token endpoint", tokenEndpoint)
        auth.setOnCheckedChangeListener { _, _ -> legacyFields.visibility = if (auth.checkedRadioButtonId == legacy.id) View.VISIBLE else View.GONE }

        val modelButtons = linkedMapOf<Int, String>()
        val modelGroup = RadioGroup(this)
        (VoiceCatalog.baiduFlexModels + VoiceCatalog.baiduModels).forEach { (wire, label) ->
            val button = RadioButton(this).apply { text = "$label · $wire"; id = View.generateViewId() }
            modelButtons[button.id] = wire
            modelGroup.addView(button)
            if (wire == saved.model) modelGroup.check(button.id)
        }
        val endpoint = EditText(this).apply { setText(saved.endpoint); hint = "wss://..." }
        val rateAuto = RadioButton(this).apply { text = "自动 (Flex 24 kHz / Lite 16 kHz)"; id = View.generateViewId() }
        val rate16 = RadioButton(this).apply { text = "16 kHz"; id = View.generateViewId() }
        val rate24 = RadioButton(this).apply { text = "24 kHz"; id = View.generateViewId() }
        val outputRate = RadioGroup(this).apply {
            addView(rateAuto); addView(rate16); addView(rate24)
            check(
                when (saved.outputSampleRate) {
                    OutputSampleRate.AUTO -> rateAuto.id
                    OutputSampleRate.HZ_16000 -> rate16.id
                    OutputSampleRate.HZ_24000 -> rate24.id
                },
            )
        }
        val instructions = EditText(this).apply {
            setText(saved.instructions)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        val resetPersona = Button(this).apply {
            text = "恢复默认人设"
            setOnClickListener { instructions.setText(PersonaProfiles.DEFAULT_INSTRUCTIONS) }
        }
        val voice = EditText(this).apply {
            setText(saved.voice)
            hint = "音色 voice（默认 default；数字音色如 4157/4197 来自百度 TTS 音色表，实时模型未必支持，失败会自动回退 default）"
        }
        val speed = EditText(this).apply {
            setText(saved.speed.toString())
            hint = "语速 speed 0.5–1.5（默认 1.1）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        result = TextView(this).apply { setPadding(0, 20, 0, 12) }
        autoPickStatus = TextView(this)
        refreshAutoPickStatus()
        voiceToggle = Button(this).apply {
            text = voiceToggleLabel()
            setOnClickListener { toggleVoiceSession() }
        }
        // P6: the wake word needs an iFlytek APPID, and until this field existed there was
        // nowhere on the device to put it — `initialize()` failed every time with
        // `wake_init_failure reason=no_appid`. MSC needs ONLY an appId (no apiKey/apiSecret).
        // Saved through WakeWordSettings (Keystore), never through the Baidu repository.
        val iflytekAppId = secretField("讯飞 APPID（唤醒词；留空保留已保存值）")
        wakeToggle = Button(this).apply {
            text = wakeToggleLabel()
            setOnClickListener { toggleWakeWord() }
        }
        val saveWakeAppId = Button(this).apply {
            text = "保存讯飞 APPID"
            setOnClickListener {
                val entered = iflytekAppId.text.toString().trim()
                if (entered.isEmpty()) {
                    result.text = "IFLYTEK_APPID_UNCHANGED\nLeave blank to keep the stored value."
                    return@setOnClickListener
                }
                val settings = WakeWordSettings.from(this@DeveloperSettingsActivity)
                val existing = settings.loadCredentials()
                settings.saveCredentials(existing.copy(appId = entered))
                iflytekAppId.text.clear()
                // Never echo the value back to the screen or the log.
                result.text = "IFLYTEK_APPID_SAVED\nStored in Android Keystore. Toggle Wake Word to re-initialise."
            }
        }
        val micTest = Button(this).apply {
            text = getString(R.string.mic_test)
            setOnClickListener { runMicTest() }
        }
        val demo = Button(this).apply {
            text = getString(R.string.structured_demo)
            setOnClickListener { runStructuredDemo() }
        }
        val openAccessibility = Button(this).apply {
            text = "去开启辅助服务"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val testTiananmen = Button(this).apply {
            text = "测试导航到天安门广场（走真实工具路径）"
            setOnClickListener {
                val action = SafeAndroidActionExecutor(this@DeveloperSettingsActivity).navigate("天安门广场")
                result.text = when (action) {
                    is AndroidActionResult.Accepted -> "NAVIGATION: Accepted"
                    is AndroidActionResult.Rejected -> action.code
                }
            }
        }
        val testMusic = Button(this).apply {
            text = "测试播放音乐（走真实工具路径）"
            setOnClickListener {
                val action = SafeAndroidActionExecutor(this@DeveloperSettingsActivity).playMusic()
                result.text = when (action) {
                    is AndroidActionResult.Accepted -> "MUSIC: Accepted(${action.status})"
                    is AndroidActionResult.Rejected -> "MUSIC: Rejected(${action.code})"
                }
            }
        }

        val clear = Button(this).apply {
            text = "Clear all stored credentials"
            setOnClickListener {
                clearCredentials = true
                appId.text.clear(); apiKey.text.clear(); secretKey.text.clear(); amapKey.text.clear()
                result.text = "Credentials will be cleared when Save is pressed."
            }
        }
        val test = Button(this).apply {
            text = "Test Connection"
            setOnClickListener {
                val candidate = candidate(auth, legacy, appId, apiKey, secretKey, modelButtons, modelGroup, endpoint, tokenEndpoint, outputRate, rate16, rate24, instructions, voice, speed)
                val validation = BaiduSettingsValidator.validate(candidate.settings, candidate.credentials)
                if (validation != null) { result.text = validation; return@setOnClickListener }
                isEnabled = false
                result.text = "Connecting directly to Baidu…"
                scope.launch {
                    closeTestClient?.invoke()
                    try {
                        if (candidate.settings.runtimeProvider == BaiduRuntimeProvider.FLEX) {
                            val client = BaiduFlexClient()
                            closeTestClient = client::disconnect
                            client.connect(candidate)
                        } else {
                            val client = BaiduRealtimeClient()
                            closeTestClient = client::disconnect
                            client.connect(candidate)
                        }
                        result.text = "Connection successful\nAuthentication successful\nModel: ${candidate.settings.model}\nMicrophone was not started."
                    } catch (failure: VoiceProviderException) {
                        result.text = "${failure.code}\n${failure.safeMessage}"
                    } catch (_: Exception) {
                        result.text = "BAIDU_CONNECTION_FAILED\nUnable to establish the realtime session"
                    } finally {
                        closeTestClient?.invoke(); closeTestClient = null; isEnabled = true
                    }
                }
            }
        }
        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val settings = formSettings(auth, legacy, modelButtons, modelGroup, endpoint, tokenEndpoint, outputRate, rate16, rate24, instructions, voice, speed)
                val updates = BaiduCredentialUpdates(
                    update(appId), update(apiKey), update(secretKey),
                )
                try {
                    if (clearCredentials) {
                        repository.clearAllCredentials()
                        if (amapKey.text.toString().trim().isEmpty()) amapRepository.clear()
                    }
                    repository.save(settings, if (clearCredentials) updatesFromFields(appId, apiKey, secretKey) else updates)
                    amapRepository.save(update(amapKey))
                    clearCredentials = false
                    Toast.makeText(this@DeveloperSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (failure: IllegalArgumentException) {
                    result.text = failure.message ?: "INVALID_CONFIGURATION"
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48)
            addView(TextView(this@DeveloperSettingsActivity).apply { text = "Baidu Direct Settings"; textSize = 22f })
            addView(TextView(this@DeveloperSettingsActivity).apply { text = "Authentication" }); addView(auth)
            addView("API Key".label()); addView(apiKey); addView(legacyFields)
            addView(TextView(this@DeveloperSettingsActivity).apply { text = "Provider / model (Flex preferred; Lite/Pro fallback)" }); addView(modelGroup)
            addView("Realtime endpoint".label()); addView(endpoint)
            addView(TextView(this@DeveloperSettingsActivity).apply { text = "回复音频采样率 (reply audio sample rate)" }); addView(outputRate)
            addView(TextView(this@DeveloperSettingsActivity).apply { text = "人设 / Persona instructions (sent as session instructions)" })
            addView(instructions)
            addView(resetPersona)
            addView("音色 voice（默认 default；数字音色如 4157/4197 来自百度 TTS 音色表，实时模型未必支持，失败会自动回退 default）".label())
            addView(voice)
            addView("语速 speed 0.5–1.5（默认 1.1）".label())
            addView(speed)
            addView(TextView(this@DeveloperSettingsActivity).apply {
                text = "Amap Web key (optional: enables hands-free navigation; without it Amap shows a destination list)"
            })
            addView(amapKey)
            addView(autoPickStatus)
            addView(openAccessibility)
            addView(voiceToggle)
            addView("讯飞 APPID（唤醒词 你好小诺；MSC 只需要 APPID，不需要 API Key/Secret）".label())
            addView(iflytekAppId)
            addView(saveWakeAppId)
            addView(wakeToggle)
            addView(micTest)
            addView(demo)
            addView(testTiananmen)
            addView(testMusic)
            addView(clear); addView(result); addView(test); addView(save)
            addView(TextView(this@DeveloperSettingsActivity).apply {
                setPadding(0, 20, 0, 0)
                text = "Credentials are encrypted with Android Keystore. Test Connection opens Baidu WSS and waits for session.updated without using the microphone."
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
        legacyFields.visibility = if (auth.checkedRadioButtonId == legacy.id) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::autoPickStatus.isInitialized) refreshAutoPickStatus()
        if (::voiceToggle.isInitialized) voiceToggle.text = voiceToggleLabel()
        if (::wakeToggle.isInitialized) wakeToggle.text = wakeToggleLabel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_MIC_WAKE) DebugVoiceLog.log("wake_permission_failure")
            result.text = "MIC_PERMISSION_DENIED\nmicrophone permission denied"
            return
        }
        if (requestCode == REQ_MIC) toggleVoiceSession()
        if (requestCode == REQ_MIC_TEST) runMicTest()
        if (requestCode == REQ_MIC_WAKE) {
            WakeWordController.setEnabled(this, true)
            if (::wakeToggle.isInitialized) wakeToggle.text = wakeToggleLabel()
        }
    }

    private fun toggleVoiceSession() {
        if (VoiceSessionGateway.isActive) {
            VoiceSessionGateway.stop()
            voiceToggle.text = voiceToggleLabel()
            return
        }
        when (val outcome = VoiceSessionGateway.start()) {
            StartResult.Started, StartResult.AlreadyActive -> voiceToggle.text = voiceToggleLabel()
            StartResult.MicPermissionMissing ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            StartResult.NotAttached ->
                result.text = "NOT_ATTACHED\nOpen the map screen first so the session owner is attached."
            is StartResult.ConfigInvalid ->
                result.text = outcome.message
        }
    }

    private fun voiceToggleLabel(): String =
        if (VoiceSessionGateway.isActive) getString(R.string.voice_session_stop)
        else getString(R.string.voice_session_start)

    private fun toggleWakeWord() {
        val settings = WakeWordSettings.from(this)
        if (settings.isEnabled()) {
            WakeWordController.pause(this)
            wakeToggle.text = wakeToggleLabel()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WAKE)
            return
        }
        WakeWordController.setEnabled(this, true)
        wakeToggle.text = wakeToggleLabel()
    }

    private fun wakeToggleLabel(): String =
        if (WakeWordSettings.from(this).isEnabled()) getString(R.string.wake_word_disable)
        else getString(R.string.wake_word_enable)

    private fun runMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_TEST)
            return
        }
        result.text = "麦克风测试中…"
        val capture =
            PcmAudioCapture(
                onFrame = { bytes ->
                    var max = 0
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                        max = maxOf(max, kotlin.math.abs(sample.toShort().toInt()))
                        i += 2
                    }
                    mainHandler.post { result.text = "麦克风测试峰值: $max（仅本机测试）" }
                },
                onError = { code -> mainHandler.post { result.text = "$code\nmic test failed" } },
            )
        capture.start()
        mainHandler.postDelayed({ capture.stop() }, 600)
    }

    private fun runStructuredDemo() {
        val demoResult =
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
        result.text = "演示: ${demoResult.feedbackZhCn}"
    }

    override fun onDestroy() { closeTestClient?.invoke(); scope.cancel(); super.onDestroy() }

    private fun refreshAutoPickStatus() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val on = enabled.contains("com.novadrive.app/") && enabled.contains("AmapAutoPickService")
        autoPickStatus.text = "高德自动选点辅助（无高德Key时自动选第一个结果并开始导航）: ${if (on) "已启用" else "未启用"}"
    }

    private fun candidate(
        auth: RadioGroup, legacy: RadioButton, appId: EditText, apiKey: EditText, secretKey: EditText,
        models: Map<Int, String>, modelGroup: RadioGroup, endpoint: EditText, tokenEndpoint: EditText,
        outputRate: RadioGroup, rate16: RadioButton, rate24: RadioButton, instructions: EditText,
        voice: EditText, speed: EditText,
    ): BaiduApiConfig {
        val saved = if (clearCredentials) BaiduCredentials("", "", "") else repository.loadCredentials()
        return BaiduApiConfig(
            formSettings(auth, legacy, models, modelGroup, endpoint, tokenEndpoint, outputRate, rate16, rate24, instructions, voice, speed),
            BaiduCredentials(value(appId, saved.appId), value(apiKey, saved.apiKey), value(secretKey, saved.secretKey)),
        )
    }

    private fun formSettings(auth: RadioGroup, legacy: RadioButton, models: Map<Int, String>, modelGroup: RadioGroup,
                             endpoint: EditText, tokenEndpoint: EditText,
                             outputRate: RadioGroup, rate16: RadioButton, rate24: RadioButton,
                             instructions: EditText, voice: EditText, speed: EditText): BaiduAppSettings {
        val model = models[modelGroup.checkedRadioButtonId] ?: VoiceCatalog.BAIDU_FLEX
        return BaiduAppSettings(
            authMode = if (auth.checkedRadioButtonId == legacy.id) BaiduAuthMode.LEGACY_ACCESS_TOKEN else BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = if (model == VoiceCatalog.BAIDU_FLEX) BaiduRuntimeProvider.FLEX else BaiduRuntimeProvider.LITE,
            model = model,
            endpoint = endpoint.text.toString().trim(), tokenEndpoint = tokenEndpoint.text.toString().trim(),
            outputSampleRate = when (outputRate.checkedRadioButtonId) {
                rate16.id -> OutputSampleRate.HZ_16000
                rate24.id -> OutputSampleRate.HZ_24000
                else -> OutputSampleRate.AUTO
            },
            instructions = instructions.text.toString().trim(),
            voice = voice.text.toString().trim().ifBlank { BaiduAppSettings.DEFAULT_VOICE },
            speed = speed.text.toString().trim().toDoubleOrNull() ?: BaiduAppSettings.DEFAULT_SPEED,
        )
    }

    private fun update(field: EditText): CredentialUpdate =
        field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(CredentialUpdate::Replace)
            ?: CredentialUpdate.Keep
    private fun updatesFromFields(a: EditText, b: EditText, c: EditText) = BaiduCredentialUpdates(
        a.text.toString().trim().takeIf(String::isNotEmpty)?.let(CredentialUpdate::Replace) ?: CredentialUpdate.Clear,
        b.text.toString().trim().takeIf(String::isNotEmpty)?.let(CredentialUpdate::Replace) ?: CredentialUpdate.Clear,
        c.text.toString().trim().takeIf(String::isNotEmpty)?.let(CredentialUpdate::Replace) ?: CredentialUpdate.Clear,
    )
    private fun value(field: EditText, old: String) = field.text.toString().trim().ifBlank { old }
    private fun secretField(hintText: String) = EditText(this).apply {
        hint = hintText; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun String.label() = TextView(this@DeveloperSettingsActivity).apply { text = this@label }
    private fun verticalGroup(vararg children: Any) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { if (it is String) addView(it.label()) else if (it is View) addView(it) }
    }

    companion object {
        private const val REQ_MIC = 21
        private const val REQ_MIC_TEST = 22
        private const val REQ_MIC_WAKE = 23
    }
}
