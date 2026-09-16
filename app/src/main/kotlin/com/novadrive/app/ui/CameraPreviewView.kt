package com.novadrive.app.ui

import android.content.Context
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
import com.novadrive.app.R

/**
 * Front-lens preview using Camera2 (no extra dependency).
 * Opening or closing this view must not stop the voice session.
 */
class CameraPreviewView(context: Context) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null

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
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val close =
            Button(context).apply {
                text = context.getString(R.string.camera_close)
                setOnClickListener { hide() }
            }
        addView(
            close,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (16 * resources.displayMetrics.density).toInt()
                rightMargin = (16 * resources.displayMetrics.density).toInt()
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

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
    }

    fun show() {
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

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }
}
