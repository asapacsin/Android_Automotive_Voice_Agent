package com.novadrive.ingress.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerTest {
    @Test
    fun catalogDefaultsAreBaiduFlexAndAllProvidersSelectable() {
        assertEquals(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.DEFAULT_PROVIDER)
        assertEquals("baidu_flex", VoiceCatalog.DEFAULT_PROVIDER_WIRE)
        assertEquals("qianfan-realtime-flex-v1", VoiceCatalog.DEFAULT_MODEL)
        assertEquals("audio-mini-realtime-near", VoiceCatalog.defaultModel(VoiceProviderId.BAIDU))
        assertEquals("Lite Near", VoiceCatalog.baiduModels[VoiceCatalog.BAIDU_LITE_NEAR])
        assertEquals("Lite Far", VoiceCatalog.baiduModels[VoiceCatalog.BAIDU_LITE_FAR])
        assertEquals("Pro Near", VoiceCatalog.baiduModels[VoiceCatalog.BAIDU_PRO_NEAR])
        assertEquals("Pro Far", VoiceCatalog.baiduModels[VoiceCatalog.BAIDU_PRO_FAR])
        assertTrue(VoiceCatalog.capabilities(VoiceProviderId.QWEN).customTools)
        assertTrue(VoiceCatalog.capabilities(VoiceProviderId.GPT_LIVE).customTools)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.BAIDU).customTools)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.BAIDU).clientResponseCancel)
        assertTrue(VoiceCatalog.capabilities(VoiceProviderId.BAIDU_FLEX).customTools)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.FAKE).requiresCredentials)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.GPT_LIVE).clientResponseCancel)
        assertEquals("gpt-live-1", VoiceCatalog.defaultModel(VoiceProviderId.GPT_LIVE))
        assertEquals("qwen-audio-3.0-realtime-flash", VoiceCatalog.defaultModel(VoiceProviderId.QWEN))
    }

    @Test
    fun connectDisconnectDoesNotDuplicateCollectors() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val mic = InMemoryMicrophonePort()
            val playback = InMemoryPlaybackPort()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = mic,
                    playback = playback,
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.start()
            assertEquals(1, controller.collectorStarts)
            controller.stop()
            controller.start()
            assertEquals(2, controller.collectorStarts)
            controller.stop()
            controller.stop()
            assertEquals(2, provider.disconnectCount)
            assertTrue(provider.closed)
            assertTrue(mic.stopped)
            assertTrue(playback.stopped)
        }

    @Test
    fun stopDisconnectsOnceEvenWhenCallerScopeIsCancelledImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            provider.disconnectDelayMs = 40
            val parent = SupervisorJob()
            val sessionScope = CoroutineScope(coroutineContext.minusKey(Job) + parent)
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = sessionScope,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.stop()
            controller.stop()
            parent.cancel()
            advanceTimeBy(80)
            assertEquals(1, provider.disconnectCount)
            assertTrue(provider.closed)
        }

    @Test
    fun reconnectResumesCaptureOnceWithoutDuplicateCollectors() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val mic = InMemoryMicrophonePort()
            val policy = ReconnectPolicy(ReconnectPlan(maxAttempts = 2, initialDelayMs = 200, maxDelayMs = 1_000))
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = mic,
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    reconnectPolicy = policy,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            assertEquals(1, controller.collectorStarts)
            assertEquals(1, mic.startCount)
            assertTrue(mic.started)
            provider.emit(DomainVoiceEvent.Error("SERVER_DISCONNECT", "socket closed"))
            assertFalse(mic.started)
            advanceTimeBy(250)
            assertEquals(1, controller.collectorStarts)
            assertEquals(2, mic.startCount)
            assertTrue(mic.started)
            mic.emit(byteArrayOf(5, 6))
            assertEquals(listOf(byteArrayOf(5, 6).toList()), provider.sentChunks.map { it.toList() })
            controller.stop()
        }

    @Test
    fun asyncWorkRunsWhileAudioContinuesAndCancelIsIsolated() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val playback = InMemoryPlaybackPort()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("slow", "nav") {
                delay(5_000)
                "route-ready"
            }
            controller.submitWork("fast", "lookup") {
                progress("50%")
                "fast-ok"
            }
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertEquals(1, playback.played.size)
            assertEquals(WorkStatus.RUNNING, controller.work.snapshot("slow")?.status)
            assertEquals(WorkStatus.COMPLETED, controller.work.snapshot("fast")?.status)
            assertEquals("50%", controller.work.snapshot("fast")?.progress)
            controller.cancelWork("slow")
            assertEquals(WorkStatus.CANCELLED, controller.work.snapshot("slow")?.status)
            advanceTimeBy(5_000)
            assertEquals(WorkStatus.CANCELLED, controller.work.snapshot("slow")?.status)
            assertEquals("fast-ok", controller.work.snapshot("fast")?.result)
            controller.stop()
        }

    @Test
    fun stopCancelsOutstandingAsyncWorkAndLeavesTerminalSnapshots() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicBoolean(false)
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("running", "nav") {
                started.complete(Unit)
                try {
                    delay(10_000)
                    "done"
                } catch (ex: CancellationException) {
                    cancelled.set(true)
                    throw ex
                }
            }
            controller.submitWork("done", "lookup")
            controller.work.complete("done", "ok")
            started.await()
            controller.stop()
            assertEquals(WorkStatus.CANCELLED, controller.work.snapshot("running")?.status)
            assertTrue(cancelled.get())
            assertEquals(WorkStatus.COMPLETED, controller.work.snapshot("done")?.status)
            assertEquals("ok", controller.work.snapshot("done")?.result)
        }

    @Test
    fun releaseCancelsOutstandingAsyncWork() =
        runTest(UnconfinedTestDispatcher()) {
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicBoolean(false)
            val controller =
                VoiceSessionController(
                    provider = FakeRealtimeVoiceProvider(),
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("running", "nav") {
                started.complete(Unit)
                try {
                    delay(10_000)
                    "done"
                } catch (ex: CancellationException) {
                    cancelled.set(true)
                    throw ex
                }
            }
            started.await()
            controller.release()
            assertEquals(WorkStatus.CANCELLED, controller.work.snapshot("running")?.status)
            assertTrue(cancelled.get())
        }

    @Test
    fun deferredToolOutputDoesNotBlockEventsAndIsDeliveredOnceWhenReady() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val gate = CompletableDeferred<String>()
            val transcripts = mutableListOf<String>()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                    callbacks = VoiceSessionCallbacks(
                        onTranscript = { transcripts += it },
                        onToolCall = { ToolDispatchResult(null, null, deferredOutput = { gate.await() }) },
                    ),
                )
            controller.start()
            provider.emit(DomainVoiceEvent.ToolCall("cam1", "describe_camera_view", mapOf("question" to "前面有什么")))
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            // The slow tool is still running: nothing delivered, and the event loop keeps working.
            assertEquals(0, provider.workInjections.size)
            provider.emit(DomainVoiceEvent.UserTranscript("还在吗", final = true))
            assertTrue(transcripts.any { it.contains("还在吗") })

            gate.complete("""{"ok":true,"answer":"前方是一条道路"}""")
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(1, provider.workInjections.size)
            assertEquals("cam1", provider.workInjections.single().callId)
            assertTrue(provider.workInjections.single().output.contains("前方是一条道路"))
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(1, provider.workInjections.size)
            controller.stop()
        }

    @Test
    fun sendTextIsDeliveredAfterConnectAndIgnoredWhenIdle() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            fun sentTexts() = provider.receiveEvents()
                .filterIsInstance<DomainVoiceEvent.UserTranscript>()
                .map { it.text }

            controller.sendText("idle, dropped")
            assertTrue(sentTexts().isEmpty())

            // Start and send immediately: queued until connect, then delivered exactly once.
            controller.start()
            controller.sendText("读出看图结果")
            assertEquals(listOf("读出看图结果"), sentTexts())

            controller.stop()
            controller.sendText("after stop")
            assertTrue(sentTexts().isEmpty())
        }

    @Test
    fun workResultInjectionRetriesAfterFailureAndAcknowledgesOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            provider.failNextInjectWith = "INJECT_FAILED"
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("w1", "first")
            controller.work.complete("w1", "温度已设为22度。")
            assertEquals(0, provider.workInjections.size)
            assertFalse(controller.work.snapshot("w1")!!.delivered)
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(1, provider.workInjections.size)
            assertEquals("w1", provider.workInjections.single().callId)
            assertTrue(controller.work.snapshot("w1")!!.delivered)
            provider.emit(DomainVoiceEvent.SpeechStopped)
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(1, provider.workInjections.size)
            controller.stop()
        }

    @Test
    fun slowWorkInjectionDoesNotBlockRealtimeAudioEvents() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val gate = CompletableDeferred<Unit>()
            provider.injectGate = gate
            val playback = InMemoryPlaybackPort()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            assertEquals(VoiceUiState.LISTENING, controller.machine.state)
            controller.submitWork("w-slow", "nav")
            controller.work.complete("w-slow", "ok")
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertEquals(1, playback.played.size)
            assertTrue(provider.workInjections.isEmpty())
            gate.complete(Unit)
            assertEquals(1, provider.workInjections.size)
            controller.stop()
        }

    @Test
    fun continuousChunksForwardInOrderAndPlaybackIsOrdered() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(1_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val mic = InMemoryMicrophonePort()
            val playback = InMemoryPlaybackPort().also { it.clock = clock }
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = mic,
                    playback = playback,
                    scope = this,
                    clock = clock,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            mic.emit(byteArrayOf(1, 2))
            mic.emit(byteArrayOf(3, 4))
            assertEquals(listOf(byteArrayOf(1, 2).toList(), byteArrayOf(3, 4).toList()), provider.sentChunks.map { it.toList() })
            provider.emit(DomainVoiceEvent.AudioDelta("AQID"))
            provider.emit(DomainVoiceEvent.AudioDelta("BAUG"))
            assertEquals(2, playback.played.size)
            controller.stop()
        }

    @Test
    fun bargeInFlushesPlaybackCancelsProviderAndLeavesWorkRunning() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(5_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val playback = InMemoryPlaybackPort().also { it.clock = clock }
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    clock = clock,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("nav-1", "plan_route")
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            clock.advance(40)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.flushCount >= 1)
            assertEquals(1, provider.cancelCount)
            assertEquals(WorkStatus.RUNNING, controller.work.snapshot("nav-1")?.status)
            assertNotNull(controller.diagnostics.metrics.interruptToPlaybackStoppedMs)
            controller.stop()
        }

    @Test
    fun flexSpeechStartedFlushesPlaybackCancelsProviderAndKeepsMic() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(5_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val mic = InMemoryMicrophonePort()
            val playback = InMemoryPlaybackPort().also { it.clock = clock }
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = mic,
                    playback = playback,
                    scope = this,
                    clock = clock,
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.BAIDU_FLEX),
                )
            controller.start()
            mic.emit(byteArrayOf(9, 8))
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            clock.advance(40)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.flushCount >= 1)
            assertEquals(1, provider.cancelCount)
            mic.emit(byteArrayOf(7, 6))
            assertEquals(
                listOf(byteArrayOf(9, 8).toList(), byteArrayOf(7, 6).toList()),
                provider.sentChunks.map { it.toList() },
            )
            controller.stop()
        }

    @Test
    fun baiduSpeechStartedFlushesPlaybackWithoutClientCancelAndKeepsMic() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(5_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val mic = InMemoryMicrophonePort()
            val playback = InMemoryPlaybackPort().also { it.clock = clock }
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = mic,
                    playback = playback,
                    scope = this,
                    clock = clock,
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU, VoiceCatalog.BAIDU_LITE_NEAR),
                )
            controller.start()
            mic.emit(byteArrayOf(9, 8))
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            clock.advance(40)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.flushCount >= 1)
            assertEquals(0, provider.cancelCount)
            mic.emit(byteArrayOf(7, 6))
            assertEquals(
                listOf(byteArrayOf(9, 8).toList(), byteArrayOf(7, 6).toList()),
                provider.sentChunks.map { it.toList() },
            )
            controller.stop()
        }

    @Test
    fun workSubmitRefineProgressResultErrorCancelAndOnceOnlySafeDelivery() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            controller.submitWork("w1", "first")
            controller.refineWork("w1", "first-refined")
            controller.work.updateProgress("w1", "30%")
            controller.work.complete("w1", "温度已设为22度。")
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(1, provider.workInjections.size)
            assertEquals("w1", provider.workInjections.single().callId)
            assertTrue(controller.work.snapshot("w1")!!.delivered)
            assertNull(controller.work.claimForDelivery(true))
            controller.cancelWork("w2")
            controller.work.submit("w3", "boom")
            controller.work.fail("w3", "tool error")
            provider.emit(DomainVoiceEvent.SpeechStopped)
            assertTrue(provider.workInjections.any { it.callId == "w3" && !it.ok })
            controller.stop()
        }

    @Test
    fun boundedReconnectUsesFakeClockAndClassifiesErrors() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            provider.failNextConnectWith = "SERVER_DISCONNECT"
            val policy = ReconnectPolicy(ReconnectPlan(maxAttempts = 2, initialDelayMs = 200, maxDelayMs = 1_000))
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    reconnectPolicy = policy,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            advanceTimeBy(250)
            assertTrue(policy.attempts >= 1)
            assertEquals(ErrorClass.RETRYABLE, classifyVoiceError("SERVER_DISCONNECT"))
            assertEquals(ErrorClass.AUTH, classifyVoiceError("QWEN_AUTH_FAILED"))
            assertEquals(ErrorClass.RATE_LIMIT, classifyVoiceError("GPT_LIVE_RATE_LIMIT"))
            assertEquals(ErrorClass.MALFORMED, classifyVoiceError("QWEN_MALFORMED"))
            assertEquals(ErrorClass.TERMINAL, classifyVoiceError("BAIDU_API_REJECTED"))
            controller.stop()
        }

    @Test
    fun latencyMetricsAreDerivedFromFakeClock() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(10_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val playback = InMemoryPlaybackPort().also { it.clock = clock }
            val diagnostics = LatencyDiagnostics(clock)
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    clock = clock,
                    diagnostics = diagnostics,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                )
            controller.start()
            clock.advance(15)
            provider.emit(DomainVoiceEvent.SpeechStopped)
            clock.advance(30)
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            clock.advance(5)
            provider.emit(DomainVoiceEvent.Interrupted("turn_detected"))
            assertEquals(30, diagnostics.metrics.speechEndToFirstAudioMs)
            assertEquals(0, diagnostics.metrics.interruptToPlaybackStoppedMs)
            assertFalse(controller.logs.any { it.contains("AAAA") })
            controller.stop()
        }

    @Test
    fun providerSpecificTypesStayOutOfUiStateMachine() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        machine.apply(DomainVoiceEvent.SessionReady(VoiceCatalog.QWEN_FLASH, true))
        machine.apply(DomainVoiceEvent.SpeechStarted)
        assertEquals(VoiceUiState.USER_SPEAKING, machine.state)
        machine.apply(DomainVoiceEvent.Reconnecting)
        assertEquals(VoiceUiState.RECONNECTING, machine.state)
        assertEquals("Reconnecting", machine.state.label)
        assertEquals("Thinking or working", VoiceUiState.THINKING.label)
        assertEquals("Idle", VoiceUiState.DISCONNECTED.label)
    }

    @Test
    fun transcriptCallbackShowsOnlyFinalUtterances() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val transcripts = mutableListOf<String>()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                    callbacks = VoiceSessionCallbacks(onTranscript = { transcripts += it }),
                )
            controller.start()
            provider.emit(DomainVoiceEvent.UserTranscript("哎帮", false))
            provider.emit(DomainVoiceEvent.UserTranscript("哎帮我导航", false))
            provider.emit(DomainVoiceEvent.UserTranscript("帮我导航到天安门广场。", true))
            provider.emit(DomainVoiceEvent.AssistantTranscript("好的", false))
            provider.emit(DomainVoiceEvent.AssistantTranscript("好的，已为您导航。", true))
            assertEquals(
                listOf("你: 帮我导航到天安门广场。", "小诺: 好的，已为您导航。"),
                transcripts,
            )
            controller.stop()
        }

    @Test
    fun gptLiveAbsenceDoesNotBlockFakeStartup() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val states = mutableListOf<VoiceUiState>()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = InMemoryPlaybackPort(),
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                    callbacks = VoiceSessionCallbacks(onUiState = { state, _ -> states += state }),
                )
            controller.start()
            assertTrue(provider.connectCount >= 1)
            assertTrue(states.contains(VoiceUiState.CONNECTING) || machineListening(controller))
            controller.stop()
        }

    private fun machineListening(controller: VoiceSessionController): Boolean =
        controller.machine.state == VoiceUiState.LISTENING ||
            controller.machine.state == VoiceUiState.CONNECTING
}
