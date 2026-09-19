package com.novadrive.ingress.realtime

object BaiduRealtimeCapabilities {
    const val CUSTOM_FUNCTION_CALLING = false
    const val BLOCKED_CODE = "BLOCKED_BAIDU_FUNCTION_CALLING"
    const val OFFICIAL_DOC = "https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei"
    const val EVIDENCE =
        "BLOCKED_BAIDU_FUNCTION_CALLING. Official E2E realtime API (updated 2026-09-04) " +
            "documents only session.update and input_audio_buffer.append as client events. " +
            "Returned Session examples include tool_choice and tools: [], but UpdateSession " +
            "does not expose those fields, so they are not a documented custom-tool protocol. " +
            "ConversationItem.type allows message only. $OFFICIAL_DOC"
}

object BaiduFlexRealtimeCapabilities {
    const val CUSTOM_FUNCTION_CALLING = true
    const val MODEL = "qianfan-realtime-flex-v1"
    const val OFFICIAL_DOC = "https://cloud.baidu.com/doc/SPEECH/s/Wmtlcgi7c"
    const val PUBLIC_BETA = true
}

object GptLiveCapabilities {
    const val CUSTOM_FUNCTION_CALLING = true
    const val OFFICIAL_DOC = "https://developers.openai.com/api/docs/guides/voice-websockets"
    const val DELEGATION_DOC = "https://developers.openai.com/api/docs/guides/live-delegation"
    const val CLIENT_SPEECH_CANCEL_DOCUMENTED = false
}
