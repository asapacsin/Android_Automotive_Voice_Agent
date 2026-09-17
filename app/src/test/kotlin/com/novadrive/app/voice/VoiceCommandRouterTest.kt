package com.novadrive.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCommandRouterTest {
    private object NoControls : ListeningControls {
        override fun setCloudUpload(enabled: Boolean) = Unit
        override fun cancelAssistantReply() = Unit
        override fun closeCloudSession() = Unit
        override fun openCloudSession() = true
    }

    /** Only a real request opens the navigation reply window: noise and control phrases never do. */
    @Test
    fun onlyARealRequestLetsTheAnswerBeSpoken() = runTest(UnconfinedTestDispatcher()) {
        val lifecycle = ListeningLifecycle(backgroundScope, NoControls, nowMs = { testScheduler.currentTime })
        lifecycle.onSessionStarted("wake_word")
        var requests = 0
        val router = VoiceCommandRouter(lifecycle, context = { ListeningIntent.Context() }, onDriverRequest = { requests++ })

        router.onUserUtterance("嗯")
        router.onUserUtterance("")
        router.onUserUtterance("闭嘴")
        assertEquals(0, requests)

        router.onUserUtterance("今天温度怎么样啊。")
        assertEquals(1, requests)
        assertEquals(ListeningState.ACTIVE, lifecycle.state.value, "the question also ends SILENT_WAIT")

        router.onUserUtterance("休眠")
        assertEquals(1, requests)
    }
}
