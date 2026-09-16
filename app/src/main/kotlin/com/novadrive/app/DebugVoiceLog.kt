package com.novadrive.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.novadrive.app.wake.WakeWordController

object DebugVoiceLog {
    @Volatile
    private var enabled = false

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // MainActivity cannot be edited for this migration; it already calls init()
        // on every launch, so this is the process-lifetime hook for the single owner.
        WakeWordController.bind(context)
    }

    fun log(message: String) {
        if (enabled) android.util.Log.d("NovaVoice", message)
    }
}
