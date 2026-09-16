package com.novadrive.app.vision

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.FlexFunctionCallAssembler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraQuestionHandlerTest {
    private class FakeSurface(
        var frame: ByteArray? = byteArrayOf(1, 2, 3),
        var permitted: Boolean = true,
    ) : CameraVisionSurface {
        val shown = mutableListOf<String>()
        var captures = 0
        override fun cameraPermitted() = permitted
        override suspend fun captureJpeg(maxEdgePx: Int): ByteArray? {
            captures++
            assertEquals(CameraQuestionHandler.MAX_EDGE_PX, maxEdgePx)
            return frame
        }
        override fun showVisionText(text: String) {
            shown += text
        }
    }

    private class FakeVision(var result: VisionResult = VisionResult.Answer("前方是一条马路，有两辆车。")) : VisionPort {
        val questions = mutableListOf<String>()
        override suspend fun ask(question: String, jpeg: ByteArray): VisionResult {
            questions += question
            assertTrue(jpeg.isNotEmpty())
            return result
        }
    }

    @Test
    fun answerIsReturnedAndShownOnTheCameraView() = runBlocking {
        val surface = FakeSurface()
        val vision = FakeVision()
        val outcome = CameraQuestionHandler({ surface }, vision).ask("前面有什么？")
        assertTrue(outcome.ok)
        val json = JSONObject(outcome.output)
        assertTrue(json.getBoolean("ok"))
        assertEquals("前方是一条马路，有两辆车。", json.getString("answer"))
        assertEquals(listOf("前面有什么？"), vision.questions)
        assertEquals(listOf(CameraQuestionHandler.LOOKING, "前方是一条马路，有两辆车。"), surface.shown)
    }

    @Test
    fun blankOrMissingQuestionUsesDefaultAndLongOnesAreTruncated() = runBlocking {
        val vision = FakeVision()
        val handler = CameraQuestionHandler({ FakeSurface() }, vision)
        handler.ask(null)
        handler.ask("   ")
        handler.ask("问".repeat(500))
        assertEquals(CameraQuestionHandler.DEFAULT_QUESTION, vision.questions[0])
        assertEquals(CameraQuestionHandler.DEFAULT_QUESTION, vision.questions[1])
        assertEquals(CameraQuestionHandler.MAX_QUESTION_CHARS, vision.questions[2].length)
    }

    @Test
    fun noCameraMeansNoRequestAndNoDescription() = runBlocking {
        val vision = FakeVision()
        val outcome = CameraQuestionHandler({ null }, vision).ask("前面有什么")
        assertFalse(outcome.ok)
        assertEquals("CAMERA_UNAVAILABLE", outcome.errorCode)
        assertTrue(vision.questions.isEmpty())
        assertTrue(JSONObject(outcome.output).getString("instruction").contains("不要描述"))
    }

    @Test
    fun missingCameraPermissionFailsFastWithoutCaptureOrRequest() = runBlocking {
        val surface = FakeSurface(permitted = false)
        val vision = FakeVision()
        val outcome = CameraQuestionHandler({ surface }, vision).ask("前面有什么")
        assertEquals("CAMERA_PERMISSION_DENIED", outcome.errorCode)
        assertEquals(0, surface.captures)
        assertTrue(vision.questions.isEmpty())
        assertTrue(JSONObject(outcome.output).getString("message").contains("相机权限"))
    }

    @Test
    fun noFrameMeansNoRequest() = runBlocking {
        val surface = FakeSurface(frame = null)
        val vision = FakeVision()
        val outcome = CameraQuestionHandler({ surface }, vision).ask("前面有什么")
        assertEquals("NO_CAMERA_FRAME", outcome.errorCode)
        assertTrue(vision.questions.isEmpty())
        assertEquals(1, surface.captures)
    }

    @Test
    fun everyVisionFailureIsNotOkAndCarriesNoAnswer() = runBlocking {
        val cases = mapOf(
            VisionResult.NotConfigured("x") to "VISION_NOT_CONFIGURED",
            VisionResult.AuthFailed("HTTP 401") to "VISION_AUTH_FAILED",
            VisionResult.Failed("HTTP 500") to "VISION_REQUEST_FAILED",
        )
        for ((result, code) in cases) {
            val outcome = CameraQuestionHandler({ FakeSurface() }, FakeVision(result)).ask("前面有什么")
            val json = JSONObject(outcome.output)
            assertFalse(json.getBoolean("ok"), code)
            assertEquals(code, json.getString("error"))
            assertFalse(json.has("answer"), code)
            assertFalse(outcome.output.contains("HTTP"), "provider diagnostics stay out of the model's context")
        }
    }

    @Test
    fun dispatcherDefersTheVisionCallInsteadOfBlocking() = runBlocking {
        val vision = FakeVision()
        val surface = FakeSurface()
        val dispatcher = AndroidToolDispatcher(
            NoopExecutor,
            ClimateToolHandler(SimulatedVehicleControl()),
            CameraQuestionHandler({ surface }, vision),
        )
        val assembler = FlexFunctionCallAssembler()
        assembler.consume(
            """{"type":"response.output_item.added","item":{"id":"i","type":"function_call","call_id":"c1","name":"describe_camera_view"}}""",
        )
        val call = assembler.consume(
            JSONObject().put("type", "response.function_call_arguments.done").put("call_id", "c1")
                .put("arguments", """{"question":"看看前面有什么"}""").toString(),
        ).single() as DomainVoiceEvent.ToolCall

        val result = dispatcher.dispatch(call)
        assertNull(result.output)
        assertEquals(0, surface.captures, "dispatch itself must not capture or call the network")
        assertNotNull(result.deferredOutput)
        val deferred = result.deferredOutput!!
        val output = JSONObject(deferred())
        assertTrue(output.getBoolean("ok"))
        assertEquals(listOf("看看前面有什么"), vision.questions)
    }

    @Test
    fun malformedToolArgumentsAreRejectedBeforeTheCamera() {
        val bad = listOf("""{}""", """{"question":""}""", """{"question":7}""", """{"question":"x","zoom":2}""")
        for (arguments in bad) {
            val assembler = FlexFunctionCallAssembler()
            assembler.consume(
                """{"type":"response.output_item.added","item":{"id":"i","type":"function_call","call_id":"c2","name":"describe_camera_view"}}""",
            )
            val call = assembler.consume(
                JSONObject().put("type", "response.function_call_arguments.done").put("call_id", "c2")
                    .put("arguments", arguments).toString(),
            ).single() as DomainVoiceEvent.ToolCall
            assertTrue(call.arguments.containsKey("_validation_error"), arguments)
            val surface = FakeSurface()
            val result = AndroidToolDispatcher(
                NoopExecutor,
                ClimateToolHandler(SimulatedVehicleControl()),
                CameraQuestionHandler({ surface }, FakeVision()),
            ).dispatch(call)
            assertNull(result.deferredOutput, arguments)
            assertFalse(JSONObject(result.output!!).getBoolean("ok"), arguments)
            assertEquals(0, surface.captures)
        }
    }

    private object NoopExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Rejected("unused")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Rejected("unused")
        override fun playMusic() = AndroidActionResult.Rejected("unused")
        override fun stopMusic() = AndroidActionResult.Rejected("unused")
        override fun exitNavigationMode() = AndroidActionResult.Rejected("unused")
    }
}
