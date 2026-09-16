package com.novadrive.app.vision

/**
 * Asks a vision-capable model a question about one image. The camera feature depends only on
 * this interface, so the provider (Baidu Qianfan today) can be swapped without touching the
 * tool, the dispatcher or the UI.
 */
interface VisionPort {
    suspend fun ask(question: String, jpeg: ByteArray): VisionResult
}

sealed interface VisionResult {
    data class Answer(val text: String) : VisionResult

    /** No credential/model is available, so no request was sent. */
    data class NotConfigured(val reason: String) : VisionResult

    /** The provider rejected the credential. */
    data class AuthFailed(val detail: String) : VisionResult

    /** Network, HTTP or response-format failure. [detail] is a provider diagnostic, never image data. */
    data class Failed(val detail: String) : VisionResult
}

/**
 * The live camera surface, as seen by the vision feature. Implemented by the camera view and
 * reached through [CameraVisionGateway], so no other layer holds a View.
 */
interface CameraVisionSurface {
    /** False when the app may not use the camera; checked before anything is opened or sent. */
    fun cameraPermitted(): Boolean

    /** Opens the camera if needed, waits for a settled frame and returns it as JPEG, or null. */
    suspend fun captureJpeg(maxEdgePx: Int): ByteArray?

    /** Shows a short status or answer on the camera view. */
    fun showVisionText(text: String)
}

/** Process-scoped handle on the visible camera surface; identity-guarded like NavigationHostGateway. */
object CameraVisionGateway {
    private val lock = Any()

    @Volatile
    private var surface: CameraVisionSurface? = null
    private var owner: Any? = null

    fun attach(owner: Any, surface: CameraVisionSurface) {
        synchronized(lock) {
            this.owner = owner
            this.surface = surface
        }
    }

    fun detach(owner: Any) {
        synchronized(lock) {
            if (this.owner === owner) {
                this.owner = null
                surface = null
            }
        }
    }

    fun current(): CameraVisionSurface? = surface
}
