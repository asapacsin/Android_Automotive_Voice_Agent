package com.novadrive.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

object AudioPlaybackProbe {
    @Volatile
    private var callback: AudioManager.AudioPlaybackCallback? = null

    fun start(context: Context) {
        try {
            stop(context)
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    try {
                        for (config in configs) {
                            describe(config, "probe_playback")
                        }
                        Log.d("NovaVoice", "probe_playback total=${configs.size}")
                    } catch (_: Exception) {
                    }
                }
            }
            am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
            callback = cb
            try {
                val active = am.activePlaybackConfigurations
                for (config in active) {
                    describe(config, "probe_initial")
                }
                Log.d("NovaVoice", "probe_initial total=${active.size}")
            } catch (_: Exception) {
                Log.d("NovaVoice", "probe_initial unavailable")
            }
        } catch (_: Exception) {
        }
    }

    fun stop(context: Context) {
        try {
            val cb = callback ?: return
            callback = null
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioPlaybackCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun describe(config: AudioPlaybackConfiguration, prefix: String) {
        try {
            val uid = clientUidOrMinusOne(config)
            val attrs = try {
                config.audioAttributes
            } catch (_: Exception) {
                null
            }
            val usage = attrs?.usage ?: -1
            val contentType = attrs?.contentType ?: -1
            val ours = when {
                uid == -1 -> "unknown"
                uid == Process.myUid() -> "true"
                else -> "false"
            }
            Log.d(
                "NovaVoice",
                "$prefix uid=$uid usage=$usage contentType=$contentType ours=$ours usageName=${usageName(usage)}",
            )
        } catch (_: Exception) {
        }
    }

    private fun usageName(usage: Int): String {
        return when (usage) {
            0 -> "USAGE_UNKNOWN"
            1 -> "USAGE_MEDIA"
            2 -> "USAGE_VOICE_COMMUNICATION"
            4 -> "USAGE_ALARM"
            5 -> "USAGE_NOTIFICATION"
            6 -> "USAGE_NOTIFICATION_RINGTONE"
            11 -> "USAGE_ASSISTANCE_SONIFICATION"
            12 -> "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"
            13 -> "USAGE_ASSISTANCE_ACCESSIBILITY"
            14 -> "USAGE_GAME"
            16 -> "USAGE_ASSISTANT"
            else -> "USAGE_$usage"
        }
    }

    private fun clientUidOrMinusOne(config: AudioPlaybackConfiguration): Int {
        return try {
            val uid = config.javaClass.getMethod("getClientUid").invoke(config) as? Int ?: return -1
            if (uid < 0) -1 else uid
        } catch (_: Exception) {
            -1
        }
    }
}
