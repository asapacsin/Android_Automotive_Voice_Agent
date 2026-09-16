package com.novadrive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class VoiceSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_ID,
                    NotificationManager.IMPORTANCE_LOW,
                )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = buildNotification(this, "小诺语音会话进行中", null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (_: Exception) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_DETAIL = "语音会话进行中"

        @Volatile
        private var running = false

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, VoiceSessionService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, VoiceSessionService::class.java))
            }
        }

        fun update(context: Context, state: String, detail: String?) {
            runCatching {
                if (!running) return
                val app = context.applicationContext
                val notification =
                    buildNotification(
                        app,
                        "小诺 · $state",
                        detail?.takeIf { it.isNotBlank() } ?: DEFAULT_DETAIL,
                    )
                app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String?): Notification {
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val builder =
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(title)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
            if (text != null) builder.setContentText(text)
            return builder.build()
        }
    }
}
