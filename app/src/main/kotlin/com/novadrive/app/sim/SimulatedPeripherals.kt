package com.novadrive.app.sim

import com.novadrive.app.MusicBackend
import com.novadrive.app.vision.CameraVisionSurface
import com.novadrive.app.vision.VisionPort
import com.novadrive.app.vision.VisionResult
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/** Music player stand-in: state is observable and a failure can be injected. */
class SimulatedMusic : MusicBackend {
    @Volatile override var isPlaying: Boolean = false
        private set
    @Volatile var failNext = false
    val plays = AtomicInteger()

    override fun play(): Boolean {
        if (failNext) {
            failNext = false
            return false
        }
        plays.incrementAndGet()
        isPlaying = true
        return true
    }

    override fun stop() {
        isPlaying = false
    }

    fun reset(playing: Boolean) {
        isPlaying = playing
        failNext = false
        plays.set(0)
    }
}

/**
 * Camera window stand-in. The "frame" is the fixture's JPEG when one is provided (real-API
 * suite), otherwise a marker the [FixtureVision] model understands.
 */
class SimulatedCamera(
    private val jpegFor: (String) -> ByteArray? = { null },
) : CameraVisionSurface {
    @Volatile var open = false
    @Volatile var fixture = "road_clear"
    @Volatile var permitted = true
    @Volatile var lastShown: String? = null

    override fun cameraPermitted(): Boolean = permitted
    override fun requestCameraPermission() = Unit

    override suspend fun captureJpeg(maxEdgePx: Int): ByteArray? {
        open = true
        val name = fixture
        return if (name.startsWith("real:")) jpegFor(name.removePrefix("real:")) else "FIXTURE:$name".toByteArray()
    }

    override fun showVisionText(text: String) {
        lastShown = text
    }

    override val isOpen: Boolean get() = open
}

/**
 * Deterministic vision model for logic tests: fixed answers per fixture. It never sees a real
 * image, so VISION_SIMULATED results say nothing about the real model (see VISION_REAL).
 */
class FixtureVision : VisionPort {
    val requests = AtomicInteger()
    val completed = AtomicInteger()
    val delaySleptMs = java.util.concurrent.atomic.AtomicLong()
    @Volatile var failNext = false
    @Volatile var delayMs = 0L

    override suspend fun ask(question: String, jpeg: ByteArray): VisionResult {
        requests.incrementAndGet()
        try {
            if (delayMs > 0) {
                delaySleptMs.addAndGet(delayMs)
                delay(delayMs)
            }
            if (failNext) {
                failNext = false
                return VisionResult.Failed("simulated vision outage")
            }
            val name = String(jpeg).removePrefix("FIXTURE:")
            return VisionResult.Answer(ANSWERS[name] ?: "画面里没有特别的东西。")
        } finally {
            completed.incrementAndGet()
        }
    }

    fun reset() {
        requests.set(0)
        completed.set(0)
        delaySleptMs.set(0)
        failNext = false
        delayMs = 0
    }

    companion object {
        val ANSWERS = mapOf(
            "road_clear" to "前方道路畅通，没有车辆和行人。",
            "pedestrian" to "前方人行横道上有两位行人正在过马路。",
            "car_front" to "正前方有一辆白色轿车，距离较近。",
            "parking_lot" to "这是一个停车场，停着很多车辆。",
            "dark_scene" to "画面很暗，看不清楚前方的情况。",
        )
    }
}

/** Counts real-API vision calls for the probe without changing them. */
class CountingVision(private val delegate: VisionPort) : VisionPort {
    val requests = AtomicInteger()
    override suspend fun ask(question: String, jpeg: ByteArray): VisionResult {
        requests.incrementAndGet()
        return delegate.ask(question, jpeg)
    }
}
