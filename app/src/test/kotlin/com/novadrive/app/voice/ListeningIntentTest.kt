package com.novadrive.app.voice

import com.novadrive.app.voice.ListeningIntent.Decision.PASS_TO_MODEL
import com.novadrive.app.voice.ListeningIntent.Decision.GO_TO_SLEEP
import com.novadrive.app.voice.ListeningIntent.Decision.SHUT_UP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListeningIntentTest {
    private val idle = ListeningIntent.Context()
    private val picker = ListeningIntent.Context(pickerOpen = true)
    private val working = ListeningIntent.Context(taskPending = true)

    private fun classify(text: String, ctx: ListeningIntent.Context = idle) = ListeningIntent.classify(text, ctx)

    @Test
    fun explicitListeningCommandsTerminate() {
        for (text in listOf(
            "stop listening", "Stop listening.", "go to sleep", "That's all.", "that is all",
            "关闭小诺", "关闭小诺。", "小诺，别听了", "别听了吧", "停止监听", "休息吧", "小诺休息吧", "好的，关闭小诺吧",
        )) {
            assertEquals(GO_TO_SLEEP, classify(text), text)
            // They name the assistant itself, so even an open list does not change their meaning.
            assertEquals(GO_TO_SLEEP, classify(text, picker), "$text with picker")
        }
    }

    @Test
    fun standaloneCloseTerminates() {
        assertEquals(GO_TO_SLEEP, classify("close"))
        assertEquals(GO_TO_SLEEP, classify("Close."))
    }

    @Test
    fun commandsThatMerelyContainCloseOrStopGoToTheModel() {
        for (text in listOf(
            "close the window", "close the air conditioner", "stop the music", "cancel navigation",
            "关闭导航", "关闭空调", "关闭音乐", "停止导航", "关掉空调", "小诺关闭空调",
        )) {
            assertEquals(PASS_TO_MODEL, classify(text), text)
        }
    }

    @Test
    fun existingCancellationPhrasesKeepTheirTaskMeaning() {
        for (text in listOf("算了", "取消", "cancel", "stop", "停", "返回", "结束导航")) {
            assertEquals(PASS_TO_MODEL, classify(text), text)
            assertEquals(PASS_TO_MODEL, classify(text, picker), text)
        }
    }

    @Test
    fun contextualPhrasesDeferToAnOpenTask() {
        for (text in listOf("不用了", "never mind", "Never mind.", "没事了", "close")) {
            assertEquals(GO_TO_SLEEP, classify(text), "$text with nothing going on")
            assertEquals(PASS_TO_MODEL, classify(text, picker), "$text with a list on screen")
            assertEquals(PASS_TO_MODEL, classify(text, working), "$text while a tool is running")
        }
    }

    @Test
    fun ordinaryRequestsAreNotIntercepted() {
        for (text in listOf("导航到澳门大学", "空调调到24度", "你好", "", "   ", "嗯")) {
            assertEquals(PASS_TO_MODEL, classify(text), text)
        }
    }

    @Test
    fun silencePhrasesTurnVoiceOffButKeepListening() {
        for (text in listOf(
            "闭嘴", "闭嘴！", "小诺，闭嘴", "安静", "安静点", "安静一点", "保持安静", "别说话", "别说话了", "不要说话",
            "别出声", "别吵了", "别说了", "shut up", "Shut up!", "be quiet", "keep silent", "Keep quiet.", "stop talking",
        )) {
            assertEquals(SHUT_UP, classify(text), text)
            assertEquals(SHUT_UP, classify(text, picker), "$text with a list open")
        }
    }

    @Test
    fun sleepPhrasesAreExplicit() {
        for (text in listOf("sleep", "Go to sleep.", "stop listening", "休眠", "进入休眠", "睡觉", "去睡觉吧", "小诺，睡吧", "停止监听")) {
            assertEquals(GO_TO_SLEEP, classify(text), text)
        }
    }

    @Test
    fun shutUpAndSleepAreNeverSynonyms() {
        assertEquals(SHUT_UP, classify("shut up"))
        assertEquals(SHUT_UP, classify("闭嘴"))
        assertEquals(SHUT_UP, classify("别说了"))
        assertEquals(GO_TO_SLEEP, classify("go to sleep"))
        assertEquals(GO_TO_SLEEP, classify("休眠"))
        // A request after 「闭嘴」 is an ordinary command, not a wake-up phrase.
        assertEquals(PASS_TO_MODEL, classify("选最快的那条"))
        assertEquals(PASS_TO_MODEL, classify("可以说话了"))
    }

    @Test
    fun commandsMerelyContainingQuietWordsGoToTheModel() {
        for (text in listOf("导航到安静的咖啡馆", "音乐小声一点", "关闭音乐", "暂停音乐", "你说话太快了", "说个笑话")) {
            assertEquals(PASS_TO_MODEL, classify(text), text)
        }
    }

    @Test
    fun meaningfulTurnsExcludeVadNoise() {
        assertFalse(ListeningIntent.isMeaningful(""))
        assertFalse(ListeningIntent.isMeaningful("嗯。"))
        assertFalse(ListeningIntent.isMeaningful("嗯嗯"))
        assertFalse(ListeningIntent.isMeaningful("Hmm."))
        assertTrue(ListeningIntent.isMeaningful("你好"))
        assertTrue(ListeningIntent.isMeaningful("播放音乐"))
        assertTrue(ListeningIntent.isMeaningful("ok"))
    }
}
