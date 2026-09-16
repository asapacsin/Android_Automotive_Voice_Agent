package com.novadrive.app.vision

import android.content.Context

/** Selects the vision backend. The only production file that names a concrete vision client. */
object VisionProvider {
    @Volatile
    private var instance: VisionPort? = null

    fun port(context: Context): VisionPort =
        instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                QianfanVisionClient(
                    auth = LiveVisionAuth(app),
                    model = { VisionSettings.from(app).model() },
                )
            }.also { instance = it }
        }

    fun handler(context: Context): CameraQuestionHandler =
        CameraQuestionHandler(surface = { CameraVisionGateway.current() }, vision = port(context))
}
