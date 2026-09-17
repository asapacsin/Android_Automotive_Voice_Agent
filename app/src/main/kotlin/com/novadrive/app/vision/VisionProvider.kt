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

    fun handler(context: Context): CameraQuestionHandler {
        val live = port(context)
        return CameraQuestionHandler(
            surface = { VisionOverrides.surface ?: CameraVisionGateway.current() },
            vision = object : VisionPort {
                override suspend fun ask(question: String, jpeg: ByteArray): VisionResult =
                    (VisionOverrides.port ?: live).ask(question, jpeg)
            },
        )
    }

    /** The live client, for the real-API benchmark suite (which counts its calls). */
    fun livePort(context: Context): VisionPort = port(context)
}

/**
 * Benchmark seam (debug runner only): a simulated camera window and/or vision model replace the
 * real ones. Null in normal use.
 */
object VisionOverrides {
    @Volatile var surface: CameraVisionSurface? = null
    @Volatile var port: VisionPort? = null
}
