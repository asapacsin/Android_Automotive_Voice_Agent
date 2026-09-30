package com.novadrive.app.media

import android.service.notification.NotificationListenerService

/** Exists only so the user can grant media-session access (SPEC-017 now-playing readback). */
class NovaMediaListener : NotificationListenerService()
