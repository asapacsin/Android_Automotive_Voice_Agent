package com.novadrive.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId

class DeveloperSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }
        val urlBox =
            EditText(this).apply {
                setText(DeveloperOptions.backendUrl(this@DeveloperSettingsActivity))
                hint = "Backend URL"
            }
        val selectedModel = DeveloperOptions.model(this)
        val selectedProvider = DeveloperOptions.provider(this)
        val debugProviders =
            listOf(
                VoiceProviderId.BAIDU,
                VoiceProviderId.QWEN,
                VoiceProviderId.GPT_LIVE,
                VoiceProviderId.FAKE,
            )
        val providerGroup = RadioGroup(this)
        debugProviders.forEach { provider ->
            providerGroup.addView(
                RadioButton(this).apply {
                    text = provider.name
                    tag = provider
                    this.id = View.generateViewId()
                    isChecked = provider == selectedProvider
                },
            )
        }
        val modelGroup = RadioGroup(this)
        fun fillModels(provider: VoiceProviderId) {
            modelGroup.removeAllViews()
            VoiceCatalog.modelsFor(provider).forEach { (id, label) ->
                modelGroup.addView(
                    RadioButton(this).apply {
                        text = "$label\n$id"
                        tag = id
                        this.id = View.generateViewId()
                        isChecked = id == selectedModel
                    },
                )
            }
            if (modelGroup.checkedRadioButtonId == -1 && modelGroup.childCount > 0) {
                (modelGroup.getChildAt(0) as RadioButton).isChecked = true
            }
        }
        fillModels(selectedProvider)
        providerGroup.setOnCheckedChangeListener { group, checkedId ->
            val button = group.findViewById<RadioButton>(checkedId)
            val provider = button?.tag as? VoiceProviderId ?: VoiceCatalog.DEFAULT_PROVIDER
            fillModels(provider)
        }
        val status =
            TextView(this).apply {
                text =
                    buildString {
                        appendLine("默认：Qwen Flash（qwen-audio-3.0-realtime-flash）。Qwen Plus 可选。")
                        appendLine("百度为可选兼容适配器；选中百度时默认 Lite Near（audio-mini-realtime-near），也可选 Lite Far / Pro Near / Pro Far。")
                        appendLine("GPT-Live 为可选适配器。Fake 仅用于本地无配额测试，不是产品默认，不需要凭证。")
                        appendLine("连接状态在主界面查看（Idle / Connecting / Listening / User speaking / Thinking or working / Assistant speaking / Reconnecting / Error）")
                        appendLine("麦克风测试在主界面。")
                        appendLine("物理真机请把 Backend URL 改成电脑的局域网 IP，例如 http://192.168.1.8:8000")
                        appendLine("模拟器默认 http://10.0.2.2:8000")
                        appendLine("不要在此输入 API Key 或 Secret Key。凭证只写 backend/.env。")
                    }
            }
        val save =
            Button(this).apply {
                text = "保存"
                setOnClickListener {
                    val providerButton = providerGroup.findViewById<RadioButton>(providerGroup.checkedRadioButtonId)
                    val provider = providerButton?.tag as? VoiceProviderId ?: VoiceCatalog.DEFAULT_PROVIDER
                    val checkedId = modelGroup.checkedRadioButtonId
                    val button = modelGroup.findViewById<RadioButton>(checkedId)
                    val model = button?.tag as? String ?: VoiceCatalog.defaultModel(provider)
                    DeveloperOptions.save(this@DeveloperSettingsActivity, urlBox.text.toString(), model, provider)
                    Toast.makeText(this@DeveloperSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(this@DeveloperSettingsActivity).apply { text = "开发者设置（DEBUG）"; textSize = 22f })
                addView(TextView(this@DeveloperSettingsActivity).apply { text = "Backend URL" })
                addView(urlBox)
                addView(TextView(this@DeveloperSettingsActivity).apply { text = "Provider" })
                addView(providerGroup)
                addView(TextView(this@DeveloperSettingsActivity).apply { text = "Model" })
                addView(modelGroup)
                addView(status)
                addView(save)
            }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
