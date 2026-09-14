package com.novadrive.feedback

import com.novadrive.contracts.OrchestrationStatus
import com.novadrive.contracts.ResolvedContact
import com.novadrive.contracts.SafetyVerdict
import com.novadrive.contracts.StructuredCommand
import com.novadrive.contracts.VerificationReport

class ZhCnFeedbackRenderer {
    fun render(
        command: StructuredCommand,
        status: OrchestrationStatus,
        policy: SafetyVerdict?,
        verification: VerificationReport?,
        clarification: List<ResolvedContact> = emptyList(),
    ): String =
        when (status) {
            OrchestrationStatus.VERIFIED -> verified(command)
            OrchestrationStatus.DENIED -> policy?.messageZhCn ?: "该操作已被安全策略拒绝。"
            OrchestrationStatus.PENDING_CONFIRMATION -> policy?.messageZhCn ?: "请确认后再执行。"
            OrchestrationStatus.INVALID -> invalid(command)
            OrchestrationStatus.EXECUTION_FAILED -> "执行失败，未完成操作。"
            OrchestrationStatus.VERIFICATION_FAILED -> "未确认到车辆状态变化，不能视为成功。"
            OrchestrationStatus.CANCELLED -> "已取消该请求。"
            OrchestrationStatus.CLARIFICATION_NEEDED -> clarificationMessage(clarification)
        }

    private fun verified(command: StructuredCommand): String =
        when (command) {
            is StructuredCommand.StartNavigation -> "已开始前往${command.destination.label}。"
            is StructuredCommand.CancelNavigation -> "已退出导航。"
            is StructuredCommand.PlayMedia -> "正在播放${command.query}。"
            is StructuredCommand.PauseMedia -> "已暂停播放。"
            is StructuredCommand.SetVolume -> "音量已设为${command.volumePercent}。"
            is StructuredCommand.PlaceCall -> "正在呼叫。"
            is StructuredCommand.EndCall -> "通话已结束。"
            is StructuredCommand.SetCabinTemperature -> "温度已设为${formatTemp(command.celsius)}度。"
            is StructuredCommand.SetFanLevel -> "风速已设为${command.level}档。"
        }

    private fun invalid(command: StructuredCommand): String =
        when (command) {
            is StructuredCommand.SetCabinTemperature -> "温度超出可调范围。"
            is StructuredCommand.SetFanLevel -> "风速超出可调范围。"
            is StructuredCommand.SetVolume -> "音量超出可调范围。"
            is StructuredCommand.StartNavigation -> "目的地无效。"
            is StructuredCommand.PlayMedia -> "缺少播放内容。"
            is StructuredCommand.PlaceCall -> "无法确定要呼叫的联系人。"
            else -> "参数无效，未执行。"
        }

    private fun clarificationMessage(candidates: List<ResolvedContact>): String {
        val names = candidates.joinToString("、") { it.displayName }
        return if (names.isBlank()) {
            "找到多个同名联系人，请说明要打给哪一位。"
        } else {
            "找到多位${names}，请说明要打给哪一位。"
        }
    }

    private fun formatTemp(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
