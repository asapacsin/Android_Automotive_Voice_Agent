package com.novadrive.app

import android.content.Context
import android.content.pm.ApplicationInfo

object DebugVoiceLog {
    @Volatile
    private var enabled = false

    fun init(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        enabled = debuggable || context.getString(R.string.nova_force_voice_log).isNotEmpty()
    }

    val isEnabled: Boolean get() = enabled

    fun log(message: String) {
        if (enabled) android.util.Log.d("NovaVoice", message)
    }
}
