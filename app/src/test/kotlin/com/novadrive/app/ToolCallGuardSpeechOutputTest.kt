package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.ListeningIntent
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P66: on the mumble 「嗯……那个……」 the model called set_speech_output and it ran with a false ✓.
 * The call runs only when this turn's own words ask her to stop or resume talking.
 */
class ToolCallGuardSpeechOutputTest {
    private fun speech(mode: String) = DomainVoiceEvent.ToolCall("c1", "set_speech_output", mapOf("mode" to mode))

    private fun context(said: String, epoch: Long = 2): DriverContext = DriverContext().apply {
        onSpeechStarted(epoch)
        onDriverUtterance(said, epoch)
    }

    private val mumble = "嗯……那个……"

    @Test
    fun `the mumble is refused in both directions`() {
        listOf("silent", "spoken").forEach {
            assertEquals(ToolCallGuards.SPEECH_OUTPUT_NOT_REQUESTED, ToolCallGuards.speechOutputNotRequested(speech(it), context(mumble)), it)
        }
    }

    @Test
    fun `through the dispatcher the mumble changes nothing`() {
        val executor = CountingExecutor()
        val car = SimulatedVehicleControl()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(car), noCamera(), cabin = car) { context(mumble) }
        val result = dispatcher.dispatch(speech("silent"))
        val output = JSONObject(result.output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals(ToolCallGuards.SPEECH_OUTPUT_NOT_REQUESTED, output.getString("error"))
        assertTrue(output.has("next") && output.getString("next").isNotBlank(), output.toString())
        assertNull(result.successChip)
        assertEquals(ToolCallGuards.SPEECH_OUTPUT_NOT_REQUESTED, result.blockedReason)
        assertTrue(executor.silentRequests.isEmpty())
    }

    @Test
    fun `lines that do not ask about talking are refused`() {
        listOf("嗯", "那个", "等一下", "音乐先关了吧", "你好，你叫什么名字", "还要开多久", "好了，谢谢", "说话能不能嗲一点").forEach {
            assertEquals(ToolCallGuards.SPEECH_OUTPUT_NOT_REQUESTED, ToolCallGuards.speechOutputNotRequested(speech("silent"), context(it)), it)
        }
    }

    @Test
    fun `stop requests run silent mode`() {
        listOf("闭嘴", "小诺闭嘴", "别说了", "不要说了", "你先别说了", "别说话", "停止说话", "停止說話", "安静", "安静点",
            "别出声", "别吵了", "少说两句", "别讲了", "shut up", "stop talking",
        ).forEach { assertEquals(listOf(true), runThrough(it, "silent"), it) }
    }

    @Test
    fun `resume requests run spoken mode`() {
        listOf("继续说", "接着说", "可以说话了", "你可以说了", "繼續說").forEach {
            assertEquals(listOf(false), runThrough(it, "spoken"), it)
        }
    }

    @Test
    fun `every ListeningIntent shut-up phrase is allowed`() {
        val shutUp = listOf("shut up", "be quiet", "keep quiet", "keep silent", "stay silent", "stop talking", "silence", "quiet",
            "闭嘴", "小诺闭嘴", "安静", "安静点", "安静一点", "保持安静", "别说话", "不要说话", "别出声", "不要出声",
            "别吵", "别吵了", "少说话", "别说了", "不要说了", "别讲了", "不要讲了")
        shutUp.forEach {
            assertEquals(ListeningIntent.Decision.SHUT_UP, ListeningIntent.classify(it, ListeningIntent.Context()), it)
            assertNull(ToolCallGuards.speechOutputNotRequested(speech("silent"), context(it)), it)
        }
    }

    @Test
    fun `before the transcript the call is not judged`() {
        val stale = context(mumble, epoch = 1).apply { onSpeechStarted(2) }
        assertNull(ToolCallGuards.speechOutputNotRequested(speech("silent"), stale))
        assertNull(ToolCallGuards.speechOutputNotRequested(speech("silent"), null))
        assertNull(ToolCallGuards.speechOutputNotRequested(speech("silent"), DriverContext()))
    }

    @Test
    fun `other tools are untouched`() {
        listOf(
            DomainVoiceEvent.ToolCall("c2", "control_climate", mapOf("action" to "on")),
            DomainVoiceEvent.ToolCall("c3", "end_conversation", emptyMap()),
            DomainVoiceEvent.ToolCall("c4", "set_speaking_style", mapOf("style" to "gentle")),
        ).forEach { assertNull(ToolCallGuards.speechOutputNotRequested(it, context(mumble)), it.name) }
    }

    @Test
    fun `the advice says nothing was done and does not claim silence`() {
        val advice = ToolFailureAdvice.forCode(ToolCallGuards.SPEECH_OUTPUT_NOT_REQUESTED)!!
        assertTrue(advice.isNotBlank())
        assertTrue("没有执行" in advice)
        assertFalse("已经安静了" in advice.replace("也不要说你会安静或已经安静", ""), advice)
    }

    private fun runThrough(said: String, mode: String): List<Boolean> {
        val executor = CountingExecutor()
        val car = SimulatedVehicleControl()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(car), noCamera(), cabin = car) { context(said) }
        val output = JSONObject(dispatcher.dispatch(speech(mode)).output!!)
        assertTrue(output.getBoolean("ok"), "$said $output")
        return executor.silentRequests
    }

    private class CountingExecutor : AndroidActionExecutor {
        val silentRequests = mutableListOf<Boolean>()
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")
        override fun playMusic() = AndroidActionResult.Accepted("playing")
        override fun stopMusic() = AndroidActionResult.Accepted("stopped")
        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
        override fun setSpeechSilent(silent: Boolean): AndroidActionResult { silentRequests += silent; return AndroidActionResult.Accepted() }
    }
}
