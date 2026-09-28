package com.novadrive.app.vision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays the 2026-09-28 demo (08:37:42-57): the camera was tapped open, 「看看前面有什么。」 came
 * 2 s later while the first frame was still pending, two vision requests went out and the driver
 * heard two answers. One question costs one request and one spoken answer.
 */
class CameraLookMergeTest {
    /** The first frame arrives only when the test says so, as on the device (~5 s after opening). */
    private class SlowCamera : CameraVisionSurface {
        val frame = CompletableDeferred<ByteArray>()
        override fun cameraPermitted() = true
        override fun requestCameraPermission() = Unit
        override suspend fun captureJpeg(maxEdgePx: Int): ByteArray = frame.await()
        override fun showVisionText(text: String) = Unit
    }

    private class GatedVision(private val gate: CompletableDeferred<Unit>? = null) : VisionPort {
        val questions = mutableListOf<String>()
        override suspend fun ask(question: String, jpeg: ByteArray): VisionResult {
            questions += question
            gate?.await()
            return VisionResult.Answer("前方是一个白色柜子。")
        }
    }

    @Test
    fun aQuestionWhileTheOpenLookWaitsForAFrameSendsOneRequestAndTheOpenLookIsNotSpoken() = runBlocking {
        val camera = SlowCamera()
        val vision = GatedVision()
        val handler = CameraQuestionHandler({ camera }, vision)

        val openLook = async { handler.lookOnOpen() }
        yield()
        val question = async { handler.ask("看看前面有什么。") }
        yield()
        camera.frame.complete(byteArrayOf(1, 2, 3))

        assertTrue(question.await().ok)
        assertNull(openLook.await(), "the question took the look over; the open-look must not be spoken")
        assertEquals(listOf("看看前面有什么。"), vision.questions, "exactly one request, with the driver's question")
    }

    @Test
    fun aQuestionWhileTheOpenLookRequestIsInFlightSharesItsAnswer() = runBlocking {
        val camera = SlowCamera().also { it.frame.complete(byteArrayOf(1)) }
        val gate = CompletableDeferred<Unit>()
        val vision = GatedVision(gate)
        val handler = CameraQuestionHandler({ camera }, vision)

        val openLook = async { handler.lookOnOpen() }
        while (vision.questions.isEmpty()) yield()
        val question = async { handler.ask("看看前面有什么。") }
        yield()
        gate.complete(Unit)

        val answer = question.await()
        assertTrue(answer.ok)
        assertEquals("前方是一个白色柜子。", answer.spokenText)
        assertNull(openLook.await(), "one spoken answer: the tool result's")
        assertEquals(1, vision.questions.size, "the question reused the request already in flight")
    }

    @Test
    fun anOpenLookWithNoQuestionIsSpokenAndALaterQuestionLooksAgain() = runBlocking {
        val camera = SlowCamera().also { it.frame.complete(byteArrayOf(1)) }
        val vision = GatedVision()
        val handler = CameraQuestionHandler({ camera }, vision)

        assertNotNull(handler.lookOnOpen(), "opening the camera still looks once and says so")
        assertTrue(handler.ask("有几个人").ok)
        assertEquals(listOf(CameraQuestionHandler.DEFAULT_QUESTION, "有几个人"), vision.questions)
    }
}
