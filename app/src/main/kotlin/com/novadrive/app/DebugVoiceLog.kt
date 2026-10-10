package com.novadrive.app

import android.content.Context
import android.content.pm.ApplicationInfo

object DebugVoiceLog {
    @Volatile
    private var enabled = false

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val isEnabled: Boolean get() = enabled

    /**
     * JVM-only observer for the live-demo harness (tools/demo/live). Never set by the app, so
     * release behaviour is unchanged; independent of [enabled] because the JVM has no logcat.
     */
    @Volatile
    internal var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        sink?.invoke(message)
        if (enabled) android.util.Log.d("NovaVoice", message)
    }
}
