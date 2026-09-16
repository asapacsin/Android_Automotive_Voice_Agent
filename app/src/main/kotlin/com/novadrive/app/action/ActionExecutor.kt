package com.novadrive.app.action

data class ActionRequest(
    val target: String,
    val action: String,
    val value: String? = null,
)

data class ActionResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
)

interface ActionExecutor {
    suspend fun execute(request: ActionRequest): ActionResult
}
