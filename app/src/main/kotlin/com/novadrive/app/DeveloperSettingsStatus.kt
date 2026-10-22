package com.novadrive.app

/**
 * The status card at the top of the developer settings: pure, so it can be unit-tested. Inputs are
 * presence flags and the Qwen config code; secrets never reach this object.
 */
object DeveloperSettingsStatus {
    const val SESSION_LABEL = "语音会话：通义千问 Omni · Maia"

    const val GEMINI_SESSION_LABEL = "语音会话：Gemini Live（通义千问已冻结）"

    fun sessionLine(qwenProblem: String?): String =
        if (qwenProblem == null) "$SESSION_LABEL  ✓ 就绪"
        else "$SESSION_LABEL  ✗ ${QwenSettingsValidator.message(qwenProblem) ?: qwenProblem}"

    /** The session line while Qwen is frozen ([VoiceProviderChoice.QWEN_FROZEN]). */
    fun geminiSessionLine(geminiProblem: String?): String =
        if (geminiProblem == null) "$GEMINI_SESSION_LABEL  ✓ 就绪"
        else "$GEMINI_SESSION_LABEL  ✗ ${GeminiSettingsValidator.screenMessage(geminiProblem) ?: geminiProblem}"

    fun lines(
        qwenProblem: String?,
        amapKey: Boolean,
        wakeAppId: Boolean,
        visionKey: Boolean,
        geminiProblem: String? = null,
    ): List<String> = listOf(
        if (VoiceProviderChoice.QWEN_FROZEN) geminiSessionLine(geminiProblem) else sessionLine(qwenProblem),
        "高德 Web Key  " + if (amapKey) "✓" else "✗",
        "唤醒词 APPID  " + if (wakeAppId) "✓" else "✗",
        "看图 Key  " + if (visionKey) "✓" else "可选",
    )
}
