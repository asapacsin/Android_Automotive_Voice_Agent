package com.novadrive.ingress.realtime

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayoutClockBargeInTest {
    @Test
    fun bargeInLogIncludesDiagnosticsFromCallback() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = FakeRealtimeVoiceProvider()
            val playback = InMemoryPlaybackPort()
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.BAIDU_FLEX),
                    callbacks =
                        VoiceSessionCallbacks(
                            bargeInDiagnostics = {
                                mapOf("uplinkGateOpen" to true, "lastRms" to 240)
                            },
                        ),
                )
            controller.start()
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(
                controller.logs.any {
                    it.contains("playout_barge_in") && it.contains("uplinkGateOpen=true") && it.contains("lastRms=240")
                },
            )
            controller.stop()
        }

    @Test
    fun speechStartedWithEmptyPlaybackDoesNotFlush() =
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
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertEquals(0, playback.flushCount)
            controller.stop()
        }

    @Test
    fun generationAndPlaybackActiveFlushCancelAndBumpEpoch() =
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
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.BAIDU_FLEX),
                )
            controller.start()
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertTrue(playback.playbackActive)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.flushCount >= 1)
            assertEquals(1, provider.cancelCount)
            assertTrue(controller.logs.any { it.contains("playout_barge_in") })
            controller.stop()
        }

    @Test
    fun generationDonePlaybackStillActiveFlushesWithoutCancel() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(5_000)
            val provider = FakeRealtimeVoiceProvider(clock)
            val playback =
                InMemoryPlaybackPort().also {
                    it.clock = clock
                    it.trackBufferedFrames = 480
                }
            val controller =
                VoiceSessionController(
                    provider = provider,
                    microphone = InMemoryMicrophonePort(),
                    playback = playback,
                    scope = this,
                    clock = clock,
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.BAIDU_FLEX),
                )
            controller.start()
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertEquals(VoiceUiState.LISTENING, controller.machine.state)
            assertTrue(playback.playbackActive)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.flushCount >= 1)
            assertEquals(0, provider.cancelCount)
            assertTrue(
                controller.logs.any {
                    it.contains("playout_barge_in") && it.contains("generationActive=false") && it.contains("cancel=false")
                },
            )
            controller.stop()
        }

    @Test
    fun lateChunkFromOldEpochIsNotPlayed() =
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
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            val playedAfterInterrupt = playback.played.size
            provider.emit(DomainVoiceEvent.AudioDelta("AQID"))
            assertEquals(playedAfterInterrupt, playback.played.size)
            controller.stop()
        }

    @Test
    fun nextReplyChunkAfterInterruptUsesNewEpoch() =
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
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            provider.emit(DomainVoiceEvent.ResponseDone("cancelled"))
            provider.emit(DomainVoiceEvent.ResponseStarted)
            provider.emit(DomainVoiceEvent.AudioDelta("BAUG"))
            assertEquals(1, playback.played.size)
            controller.stop()
        }

    @Test
    fun responseDoneWhileQueuedKeepsPlaybackActive() =
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
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertTrue(playback.playbackActive)
            provider.emit(DomainVoiceEvent.ResponseDone("completed"))
            assertTrue(playback.playbackActive)
            controller.stop()
        }

    @Test
    fun flushClearsQueuedOnlyAudio() =
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
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            assertTrue(playback.played.isEmpty())
            controller.stop()
        }

    @Test
    fun twoInterruptCyclesDropFirstEpochChunkOnSecondReply() =
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
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            provider.emit(DomainVoiceEvent.ResponseStarted)
            provider.emit(DomainVoiceEvent.AudioDelta("BAUG"))
            assertEquals(1, playback.played.size)
            provider.emit(DomainVoiceEvent.SpeechStarted)
            provider.emit(DomainVoiceEvent.ResponseStarted)
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertEquals(1, playback.played.size)
            controller.stop()
        }

    @Test
    fun userFramesBeforeAndAfterSpeechStartedStillUpload() =
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
                    config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.BAIDU_FLEX),
                )
            controller.start()
            mic.emit(byteArrayOf(1, 2))
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            provider.emit(DomainVoiceEvent.SpeechStarted)
            mic.emit(byteArrayOf(3, 4))
            assertEquals(
                listOf(byteArrayOf(1, 2).toList(), byteArrayOf(3, 4).toList()),
                provider.sentChunks.map { it.toList() },
            )
            controller.stop()
        }
}
