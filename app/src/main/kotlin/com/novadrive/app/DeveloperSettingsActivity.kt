package com.novadrive.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.novadrive.app.voice.PcmAudioCapture
import com.novadrive.app.voice.QwenOmniClient
import com.novadrive.app.voice.StartResult
import com.novadrive.app.voice.VoiceSessionGateway
import com.novadrive.app.vision.QianfanVisionClient
import com.novadrive.app.vision.VisionSettings
import com.novadrive.app.wake.WakeWordController
import com.novadrive.app.wake.WakeWordSettings
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Developer settings, grouped as cards (ADR-017: the product session is always Qwen-Omni + Maia).
 * Each card saves itself. Secrets are never displayed, echoed, logged or toasted; a blank secret
 * field keeps the stored value.
 */
class DeveloperSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var voiceToggle: Button
    private lateinit var wakeToggle: Button
    private lateinit var result: TextView
    private lateinit var statusLines: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closeTestClient: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = TextView(this).apply { setPadding(0, 16, 0, 0); setTextIsSelectable(true) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
            addView(TextView(this@DeveloperSettingsActivity).apply {
                text = "开发者设置 / Developer settings"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            })
            addView(statusCard())
            addView(qwenCard())
            addView(personaCard())
            addView(amapCard())
            addView(wakeCard())
            addView(visionCard())
            addView(card("权限 · Permissions", button("去开启辅助服务") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }))
            addView(diagnosticsCard())
            addView(dangerCard())
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (::voiceToggle.isInitialized) voiceToggle.text = voiceToggleLabel()
        if (::wakeToggle.isInitialized) wakeToggle.text = wakeToggleLabel()
        refreshStatus()
    }

    override fun onDestroy() { closeTestClient?.invoke(); scope.cancel(); super.onDestroy() }

    // ---- 0. Status ----

    private fun statusCard(): View {
        statusLines = TextView(this)
        voiceToggle = button(voiceToggleLabel()) { toggleVoiceSession() }
        refreshStatus()
        return card("状态 · Status", statusLines, voiceToggle, result)
    }

    private fun refreshStatus() {
        if (!::statusLines.isInitialized) return
        statusLines.text = DeveloperSettingsStatus.lines(
            qwenProblem = QwenSettingsRepository(this).configProblem(),
            amapKey = AmapSettingsRepository(this).isConfigured(),
            wakeAppId = WakeWordSettings.from(this).loadCredentials().isComplete(),
            visionKey = VisionSettings.from(this).hasDedicatedKey(),
        ).joinToString("\n")
    }

    // ---- 1. Qwen-Omni ----

    private fun qwenCard(): View {
        val qwen = QwenSettingsRepository(this)
        val saved = qwen.loadSettings()
        val consent = CheckBox(this).apply { text = "我已阅读并同意 / I accept"; isChecked = saved.consentAccepted }
        val key = secretField(if (qwen.keyPresent()) "DashScope API Key（已配置；留空保留）" else "DashScope API Key")
        val workspace = EditText(this).apply { setText(saved.workspaceId); hint = "Workspace ID（ap-southeast-1）" }
        val save = button("保存 / Save") {
            val settings = qwen.loadSettings().copy(
                consentAccepted = consent.isChecked,
                workspaceId = workspace.text.toString().trim(),
                voice = QwenAppSettings.DEFAULT_VOICE,
            )
            show(try {
                qwen.save(settings, update(key))
                key.text.clear(); key.hint = if (qwen.keyPresent()) "DashScope API Key（已配置；留空保留）" else "DashScope API Key"
                "QWEN_SETTINGS_SAVED\nkeyPresent=${qwen.keyPresent()}"
            } catch (failure: IllegalArgumentException) {
                qwenMessage(failure.message ?: "QWEN_SETTINGS_INVALID")
            })
        }
        val clearKey = button("清除 Key / Clear key") {
            qwen.clearKey(); key.hint = "DashScope API Key"; show("QWEN_KEY_CLEARED")
        }
        val test = button("测试连接 / Test connection") { testQwenConnection(it) }
        return card(
            "语音服务 · Qwen-Omni",
            label(QwenAppSettings.CONSENT_NOTICE), consent, label("API Key"), key, label("Workspace ID"), workspace,
            label("声音：Maia（固定）/ Voice: ${QwenAppSettings.DEFAULT_VOICE} (fixed)"),
            save, clearKey, test, label("下次开始会话时生效 / Takes effect at the next session start."),
        )
    }

    /** Opens the Qwen session without the microphone, reports success or the QWEN_* code, then disconnects. */
    private fun testQwenConnection(trigger: Button) {
        val config = try {
            QwenSettingsRepository(this).config(instructions = BaiduSettingsRepository(this).loadSettings().instructions)
        } catch (failure: IllegalArgumentException) {
            show(qwenMessage(failure.message ?: "QWEN_SETTINGS_INVALID")); return
        }
        trigger.isEnabled = false
        show("正在连接通义千问… / Connecting…")
        scope.launch {
            closeTestClient?.invoke()
            try {
                val client = QwenOmniClient()
                closeTestClient = client::disconnect
                client.connect(config)
                show("QWEN_CONNECTION_OK\n连接成功，未使用麦克风 / Connected; microphone was not started.")
            } catch (failure: VoiceProviderException) {
                show("${failure.code}\n${failure.safeMessage}")
            } catch (_: Exception) {
                show("QWEN_CONNECTION_FAILED\n无法建立会话 / Unable to establish the realtime session")
            } finally {
                closeTestClient?.invoke(); closeTestClient = null; trigger.isEnabled = true
            }
        }
    }

    private fun qwenMessage(code: String) = QwenSettingsValidator.message(code)?.let { "$code\n$it" } ?: code

    // ---- 2. Persona (stored with the Baidu settings; the Qwen session reads it from there) ----

    private fun personaCard(): View {
        val repository = BaiduSettingsRepository(this)
        val instructions = EditText(this).apply {
            setText(repository.loadSettings().instructions)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        val reset = button("恢复默认 / Reset") { instructions.setText(PersonaProfiles.DEFAULT_INSTRUCTIONS) }
        val save = button("保存人设 / Save persona") {
            val current = repository.loadSettings()
            show(try {
                // Only the persona changes; every other stored value and credential is kept.
                repository.save(
                    current.copy(instructions = instructions.text.toString().trim()),
                    BaiduCredentialUpdates(CredentialUpdate.Keep, CredentialUpdate.Keep, CredentialUpdate.Keep),
                )
                "PERSONA_SAVED"
            } catch (failure: IllegalArgumentException) {
                failure.message ?: "PERSONA_INVALID"
            })
        }
        return card("人设 · Persona", instructions, reset, save)
    }

    // ---- 3. Amap ----

    private fun amapCard(): View {
        val amap = AmapSettingsRepository(this)
        fun hint() = if (amap.isConfigured()) "高德 Web服务 Key（已配置；留空保留）" else "高德 Web服务 Key"
        val key = secretField(hint())
        val save = button("保存 / Save") {
            amap.save(update(key)); key.text.clear(); key.hint = hint(); refreshStatus()
            show("AMAP_KEY_SAVED\nconfigured=${amap.isConfigured()}")
        }
        val clear = button("清除 / Clear") {
            amap.clear(); key.hint = hint(); refreshStatus(); show("AMAP_KEY_CLEARED")
        }
        return card(
            "地图与导航 · Map & navigation",
            label("用于免手动导航；未配置时高德显示目的地列表 / Enables hands-free navigation"), key, save, clear,
        )
    }

    // ---- 4. Wake word ----

    private fun wakeCard(): View {
        // MSC needs ONLY an appId (no apiKey/apiSecret); stored through WakeWordSettings (Keystore).
        val appId = secretField("讯飞 APPID（留空保留已保存值）")
        val save = button("保存 APPID / Save") {
            val entered = appId.text.toString().trim()
            if (entered.isEmpty()) {
                show("IFLYTEK_APPID_UNCHANGED\nLeave blank to keep the stored value."); return@button
            }
            val settings = WakeWordSettings.from(this)
            settings.saveCredentials(settings.loadCredentials().copy(appId = entered))
            appId.text.clear(); refreshStatus()
            show("IFLYTEK_APPID_SAVED\nStored in Android Keystore. Toggle Wake Word to re-initialise.")
        }
        wakeToggle = button(wakeToggleLabel()) { toggleWakeWord() }
        return card("唤醒词「你好小诺」· Wake word", label("讯飞 APPID"), appId, save, wakeToggle)
    }

    // ---- 5. Vision ----

    private fun visionCard(): View {
        val vision = VisionSettings.from(this)
        fun hint() = if (vision.hasDedicatedKey()) "视觉 API Key（已配置；留空保留）" else "视觉 API Key（千帆 bce-v3…，可选）"
        val key = secretField(hint())
        val model = EditText(this).apply { setText(vision.model()); hint = QianfanVisionClient.DEFAULT_MODEL }
        val save = button("保存 / Save") {
            val entered = key.text.toString().trim()
            if (entered.isNotEmpty()) vision.saveDedicatedKey(entered)
            vision.saveModel(model.text.toString().ifBlank { QianfanVisionClient.DEFAULT_MODEL })
            key.text.clear(); key.hint = hint(); refreshStatus()
            show("VISION_SETTINGS_SAVED\nmodel=${vision.model()} dedicatedKey=${vision.hasDedicatedKey()}")
        }
        val clear = button("清除 Key / Clear key") {
            vision.clearDedicatedKey(); key.hint = hint(); refreshStatus()
            show("VISION_KEY_CLEARED\nThe Baidu voice credential will be tried instead.")
        }
        return card(
            "摄像头看图 · Camera vision",
            label("打开相机时看一次、之后你问画面问题时再看；图片只在这两种情况下发送到百度千帆视觉模型。未填专用 Key 时尝试已保存的百度语音凭据。"),
            key, label("视觉模型 / Model"), model, save, clear,
        )
    }

    // ---- 7. Diagnostics (collapsed) ----

    private fun diagnosticsCard(): View {
        val music = button("测试播放音乐（走真实工具路径）") {
            show(when (val action = SafeAndroidActionExecutor(this).playMusic()) {
                is AndroidActionResult.Accepted -> "MUSIC: Accepted(${action.status})"
                is AndroidActionResult.Rejected -> "MUSIC: Rejected(${action.code})"
            })
        }
        val body = vertical(button(getString(R.string.mic_test)) { runMicTest() }, music, guidanceRelayToggle())
            .apply { visibility = View.GONE }
        val toggle = button("显示诊断工具") {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            it.text = if (show) "隐藏诊断工具" else "显示诊断工具"
        }
        return card("诊断工具 · Diagnostics", toggle, body)
    }

    /**
     * SPEC-018 (experimental, default off). Qwen does not declare verbatimPromptSpeech, so 小诺 never
     * speaks the guidance; but a stored `true` still mutes Amap's inner voice and installs the relay
     * (AmapGuidanceVoice.enable), so the stored value stays reachable here.
     */
    private fun guidanceRelayToggle(): CheckBox {
        val prefs = getSharedPreferences(com.novadrive.app.nav.amap.AmapGuidanceVoice.PREFS, MODE_PRIVATE)
        val key = com.novadrive.app.nav.amap.AmapGuidanceVoice.PREF_ASSISTANT_VOICE
        return CheckBox(this).apply {
            text = "助手播报导航（实验）"
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
    }

    // ---- 8. Danger zone ----

    private fun dangerCard(): View = card("危险操作 · Danger zone", button("清除所有密钥 / Clear all keys") {
        AlertDialog.Builder(this)
            .setTitle("清除所有密钥？")
            .setMessage("将删除本机保存的所有密钥（语音、高德、唤醒词、看图等）。此操作不可撤销。")
            .setPositiveButton("清除") { _, _ ->
                CredentialWipe.clearAll(this); refreshStatus(); show("ALL_KEYS_CLEARED")
            }
            .setNegativeButton("取消", null)
            .show()
    })

    // ---- Session, wake word, mic test ----

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_MIC_WAKE) DebugVoiceLog.log("wake_permission_failure")
            show("MIC_PERMISSION_DENIED\nmicrophone permission denied")
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
            StartResult.MicPermissionMissing -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            StartResult.NotAttached -> show("NOT_ATTACHED\nOpen the map screen first so the session owner is attached.")
            is StartResult.ConfigInvalid -> show(outcome.message)
        }
    }

    private fun voiceToggleLabel(): String =
        getString(if (VoiceSessionGateway.isActive) R.string.voice_session_stop else R.string.voice_session_start)

    private fun toggleWakeWord() {
        if (WakeWordSettings.from(this).isEnabled()) {
            WakeWordController.pause(this)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WAKE)
            return
        } else {
            WakeWordController.setEnabled(this, true)
        }
        wakeToggle.text = wakeToggleLabel()
    }

    private fun wakeToggleLabel(): String =
        getString(if (WakeWordSettings.from(this).isEnabled()) R.string.wake_word_disable else R.string.wake_word_enable)

    private fun runMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_TEST)
            return
        }
        show("麦克风测试中…")
        val capture = PcmAudioCapture(
            onFrame = { bytes ->
                var max = 0
                var i = 0
                while (i + 1 < bytes.size) {
                    val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                    max = maxOf(max, kotlin.math.abs(sample.toShort().toInt()))
                    i += 2
                }
                mainHandler.post { show("麦克风测试峰值: $max（仅本机测试）") }
            },
            onError = { code -> mainHandler.post { show("$code\nmic test failed") } },
        )
        capture.start()
        mainHandler.postDelayed({ capture.stop() }, 600)
    }

    // ---- View helpers ----

    private fun show(text: String) { result.text = text }

    private fun update(field: EditText): CredentialUpdate =
        field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(CredentialUpdate::Replace) ?: CredentialUpdate.Keep

    private fun secretField(hintText: String) = EditText(this).apply {
        hint = hintText; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, 8, 0, 4) }

    private fun button(text: String, onClick: (Button) -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { onClick(this) }
    }

    private fun vertical(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach(::addView)
    }

    /** A card: bold header, then rows, on a light rounded background. */
    private fun card(title: String, vararg rows: View) = vertical(
        TextView(this).apply {
            text = title; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12)
        },
        *rows,
    ).apply {
        setPadding(32, 24, 32, 24)
        background = GradientDrawable().apply { setColor(Color.argb(18, 0, 0, 0)); cornerRadius = 16f }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 24 }
    }

    companion object {
        private const val REQ_MIC = 21
        private const val REQ_MIC_TEST = 22
        private const val REQ_MIC_WAKE = 23
    }
}
