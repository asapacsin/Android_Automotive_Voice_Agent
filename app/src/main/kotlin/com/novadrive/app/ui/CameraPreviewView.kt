package com.novadrive.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.R
import com.novadrive.app.vision.CameraVisionSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Front-lens preview using Camera2 (no extra dependency).
 * Opening or closing this view must not stop the voice session.
 */
class CameraPreviewView(context: Context) : FrameLayout(context), CameraVisionSurface {
    var onClose: (() -> Unit)? = null

    /** Raised when a vision request needs the camera permission; the Activity pops it up. */
    var onPermissionNeeded: (() -> Unit)? = null

    /** 「问AI」: ask the vision model about the current frame without using voice. */
    var onAskAi: (() -> Unit)? = null

    /** Answers and status go to the assistant's speech bubble: the small window has no room for text. */
    var onVisionText: ((String) -> Unit)? = null

    /** Completed once the preview has delivered enough frames for exposure to settle. */
    @Volatile
    private var framesReady = CompletableDeferred<Unit>()
    private var framesSeen = 0

    private val textureView = TextureView(context)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pendingOpen = false

    val isShowing: Boolean
        get() = visibility == VISIBLE

    init {
        visibility = GONE
        // Small picture-in-picture window: rounded, lifted above the map.
        background =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.parseColor("#CCFFFFFF"))
            }
        clipToOutline = true
        elevation = dp(8).toFloat()
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val close = chip(context.getString(R.string.camera_close_short)) { hide() }
        val ask = chip(context.getString(R.string.camera_ask_ai)) { onAskAi?.invoke() }
        addView(
            close,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(4)
                rightMargin = dp(4)
            },
        )
        addView(
            ask,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            },
        )
        textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (pendingOpen || isShowing) openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    framesSeen += 1
                    if (framesSeen >= SETTLE_FRAMES) framesReady.complete(Unit)
                }
            }
    }

    fun show() {
        if (!isShowing) {
            framesSeen = 0
            framesReady = CompletableDeferred()
        }
        visibility = VISIBLE
        startBackgroundThread()
        pendingOpen = true
        if (textureView.isAvailable) openCamera()
    }

    fun hide() {
        pendingOpen = false
        closeCamera()
        stopBackgroundThread()
        visibility = GONE
        onClose?.invoke()
    }

    override fun cameraPermitted(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun requestCameraPermission() {
        onPermissionNeeded?.invoke()
    }

    override suspend fun captureJpeg(maxEdgePx: Int): ByteArray? {
        if (!cameraPermitted()) return null
        val bitmap = withContext(Dispatchers.Main.immediate) {
            if (!isShowing) show()
            withTimeoutOrNull(FRAME_TIMEOUT_MS) { framesReady.await() } ?: return@withContext null
            textureView.bitmap
        } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scale = maxEdgePx.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    if (scaled !== bitmap) scaled.recycle()
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun showVisionText(text: String) {
        if (text.isBlank()) return
        post { onVisionText?.invoke(text) }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#B3000000"))
                    cornerRadius = dp(14).toFloat()
                }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    private fun openCamera() {
        val cameraId = frontCameraId() ?: return
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startPreview(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                    }
                },
                backgroundHandler,
            )
        } catch (_: SecurityException) {
            hide()
        } catch (_: Exception) {
            hide()
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        try {
            val request =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        } catch (_: Exception) {
                            hide()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        hide()
                    }
                },
                backgroundHandler,
            )
        } catch (_: Exception) {
            hide()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun frontCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("camera-preview").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Frames to wait after opening, so auto-exposure has settled before capture. */
        const val SETTLE_FRAMES = 8
        const val FRAME_TIMEOUT_MS = 5_000L
        const val JPEG_QUALITY = 80
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }
}
