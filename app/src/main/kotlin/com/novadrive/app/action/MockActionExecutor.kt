package com.novadrive.app.action

class MockActionExecutor : ActionExecutor {
    private val offlineTargets = mutableSetOf<String>()

    fun injectDeviceOffline(target: String) {
        offlineTargets += target
    }

    fun clearInjectedFailures() {
        offlineTargets.clear()
    }

    override suspend fun execute(request: ActionRequest): ActionResult {
        if (request.target in offlineTargets) {
            return ActionResult(
                success = false,
                message = offlineMessage(request.target),
                errorCode = ERROR_DEVICE_OFFLINE,
            )
        }
        val message = SUCCESS_MESSAGES[request.target to request.action]
        if (message == null) {
            return ActionResult(
                success = false,
                message = "不支持的操作",
                errorCode = ERROR_UNSUPPORTED_ACTION,
            )
        }
        return ActionResult(success = true, message = message)
    }

    private fun offlineMessage(target: String): String = when (target) {
        "air_conditioner" -> "空调连接失败"
        "bluetooth" -> "蓝牙连接失败"
        "light" -> "灯光连接失败"
        "music" -> "音乐连接失败"
        else -> "${target}连接失败"
    }

    private companion object {
        const val ERROR_DEVICE_OFFLINE = "DEVICE_OFFLINE"
        const val ERROR_UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
        val SUCCESS_MESSAGES = mapOf(
            ("air_conditioner" to "turn_on") to "空调已打开",
            ("air_conditioner" to "turn_off") to "空调已关闭",
            ("bluetooth" to "turn_on") to "蓝牙已打开",
            ("bluetooth" to "turn_off") to "蓝牙已关闭",
            ("light" to "turn_on") to "灯光已打开",
            ("light" to "turn_off") to "灯光已关闭",
            ("music" to "pause") to "音乐已暂停",
            ("music" to "resume") to "音乐已继续",
        )
    }
}
