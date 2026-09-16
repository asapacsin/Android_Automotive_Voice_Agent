package com.novadrive.app

import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.app.vision.VisionPort
import com.novadrive.app.vision.VisionResult

/** A camera handler with no camera attached, for tests that do not exercise vision. */
internal fun noCamera(): CameraQuestionHandler =
    CameraQuestionHandler(
        surface = { null },
        vision = object : VisionPort {
            override suspend fun ask(question: String, jpeg: ByteArray): VisionResult =
                error("vision must not be called without a camera")
        },
    )
