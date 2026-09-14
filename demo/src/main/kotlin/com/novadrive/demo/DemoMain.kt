package com.novadrive.demo

import com.novadrive.contracts.CoordinateSystem
import com.novadrive.contracts.Destination
import com.novadrive.contracts.GeoCoordinate
import com.novadrive.contracts.OrchestrationStatus
import com.novadrive.contracts.StructuredCommand
import com.novadrive.ingress.ReplaySpeechToSpeechPort
import com.novadrive.orchestration.VoiceSessionOrchestrator
import com.novadrive.safety.BootstrapSafetyPolicy
import com.novadrive.simulator.InMemoryVehicleSimulator
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contains("--fake-realtime")) {
        val provider = com.novadrive.ingress.realtime.MockRealtimeVoiceProvider()
        provider.connect()
        provider.sendAudio(ByteArray(2000))
        val interrupted = provider.interrupt()
        val events = provider.receiveEvents()
        println("Nova Drive / 小诺 fake realtime demo")
        println("provider=${provider.providerId}")
        println("events=${events.map { it::class.simpleName }}")
        println("interrupt=${interrupted::class.simpleName}")
        println("bytes=${provider.sentAudioBytes}")
        if (events.none { it is com.novadrive.ingress.realtime.DomainVoiceEvent.AudioDelta }) {
            System.err.println("fake realtime demo failed")
            exitProcess(1)
        }
        if (args.contains("--strict-exit")) {
            exitProcess(0)
        }
        return
    }
    val result = runNavigationDemo()
    println("Nova Drive / 小诺 本地演示")
    println("命令: 结构化导航 StartNavigation -> 人民广场 (GCJ-02)")
    println("策略: ${result.policy?.decision}")
    println("状态: ${result.status}")
    println("已执行: ${result.executed}")
    println("校验: ${result.verification?.status} expected=${result.verification?.expectedSummary}")
    println("观测: nav=${result.observedState?.navigation}")
    println("反馈: ${result.feedbackZhCn}")
    if (result.status != OrchestrationStatus.VERIFIED || !result.feedbackZhCn.contains("人民广场")) {
        System.err.println("演示未得到已校验的成功结果")
        exitProcess(1)
    }
    if (args.contains("--strict-exit")) {
        exitProcess(0)
    }
}

fun runNavigationDemo() = DemoRuntime.navigationHappyPath()

object DemoRuntime {
    fun navigationHappyPath() =
        ReplaySpeechToSpeechPort().startSession(
            VoiceSessionOrchestrator(BootstrapSafetyPolicy(), InMemoryVehicleSimulator()),
        ).onStructuredFunctionCall(
            StructuredCommand.StartNavigation(
                correlationId = "demo-nav-people-square",
                destination = Destination(
                    label = "人民广场",
                    poiName = "人民广场",
                    coordinate = GeoCoordinate(31.2304, 121.4737, CoordinateSystem.GCJ02),
                ),
            ),
        )
}
