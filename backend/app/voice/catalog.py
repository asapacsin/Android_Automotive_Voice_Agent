from __future__ import annotations

QWEN_FLASH = "qwen-audio-3.0-realtime-flash"
QWEN_PLUS = "qwen-audio-3.0-realtime-plus"
GPT_LIVE_1 = "gpt-live-1"
FAKE_MODEL = "fake-realtime"

QWEN_MODELS: dict[str, str] = {
    QWEN_FLASH: "Qwen Flash",
    QWEN_PLUS: "Qwen Plus",
}
GPT_LIVE_MODELS: dict[str, str] = {
    GPT_LIVE_1: "GPT Live 1",
}
BAIDU_LITE_NEAR = "audio-mini-realtime-near"
BAIDU_LITE_FAR = "audio-mini-realtime-far"
BAIDU_PRO_NEAR = "audio-realtime-near"
BAIDU_PRO_FAR = "audio-realtime-far"
BAIDU_MODELS: dict[str, str] = {
    BAIDU_LITE_NEAR: "Lite Near",
    BAIDU_LITE_FAR: "Lite Far",
    BAIDU_PRO_NEAR: "Pro Near",
    BAIDU_PRO_FAR: "Pro Far",
}
FAKE_MODELS: dict[str, str] = {
    FAKE_MODEL: "Fake",
}

DEFAULT_PROVIDER = "qwen"
DEFAULT_MODEL = QWEN_FLASH

ALL_MODELS: dict[str, str] = {}
ALL_MODELS.update(QWEN_MODELS)
ALL_MODELS.update(GPT_LIVE_MODELS)
ALL_MODELS.update(BAIDU_MODELS)
ALL_MODELS.update(FAKE_MODELS)

QWEN_WS_URL = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime"
GPT_LIVE_WS_URL = "wss://api.openai.com/v1/live/sessions"

QWEN_DOCS = {
    "github": "https://github.com/QwenAudio/qwen-audio-agent",
    "websocket": "https://docs.qwencloud.com/api-reference/qwen-audio-realtime/websocket-api",
    "client_events": "https://docs.qwencloud.com/api-reference/qwen-audio-realtime/client-events",
    "server_events": "https://docs.qwencloud.com/api-reference/qwen-audio-realtime/server-events",
    "user_guide": "https://help.aliyun.com/en/model-studio/qwen-audio-realtime-user-guides",
    "retrieved": "2026-09-14",
}

GPT_LIVE_DOCS = {
    "typescript": "https://developers.openai.com/api/reference/typescript/resources/live",
    "websockets": "https://developers.openai.com/api/docs/guides/voice-websockets",
    "getting_started": "https://developers.openai.com/api/docs/guides/live",
    "delegation": "https://developers.openai.com/api/docs/guides/live-delegation",
    "sessions": "https://developers.openai.com/api/docs/guides/live-conversations",
    "model": "https://developers.openai.com/api/docs/models/gpt-live-1",
    "retrieved": "2026-09-14",
}

PROVIDER_CAPABILITIES: dict[str, dict[str, object]] = {
    "qwen": {
        "custom_tools": True,
        "server_vad_interrupt": True,
        "client_response_cancel": True,
        "optional_commit": True,
        "work_result_injection": True,
        "requires_credentials": True,
    },
    "gpt_live": {
        "custom_tools": True,
        "server_vad_interrupt": True,
        "client_response_cancel": False,
        "optional_commit": False,
        "work_result_injection": True,
        "requires_credentials": True,
    },
    "baidu": {
        "custom_tools": False,
        "server_vad_interrupt": True,
        "client_response_cancel": False,
        "optional_commit": False,
        "work_result_injection": False,
        "requires_credentials": True,
    },
    "fake": {
        "custom_tools": True,
        "server_vad_interrupt": True,
        "client_response_cancel": True,
        "optional_commit": True,
        "work_result_injection": True,
        "requires_credentials": False,
    },
    "mock": {
        "custom_tools": True,
        "server_vad_interrupt": True,
        "client_response_cancel": True,
        "optional_commit": True,
        "work_result_injection": True,
        "requires_credentials": False,
    },
}


def normalize_provider(raw: str | None) -> str:
    value = (raw or DEFAULT_PROVIDER).strip().lower().replace("-", "_")
    if value in {"gptlive", "openai_live", "openai"}:
        return "gpt_live"
    if value in {"qwen", "gpt_live", "baidu", "fake", "mock"}:
        return value
    raise ValueError("UNKNOWN_VOICE_PROVIDER")


def default_model_for(provider: str) -> str:
    provider = normalize_provider(provider)
    if provider == "qwen":
        return QWEN_FLASH
    if provider == "gpt_live":
        return GPT_LIVE_1
    if provider == "baidu":
        return BAIDU_LITE_NEAR
    return FAKE_MODEL


def models_for(provider: str) -> dict[str, str]:
    provider = normalize_provider(provider)
    if provider == "qwen":
        return QWEN_MODELS
    if provider == "gpt_live":
        return GPT_LIVE_MODELS
    if provider == "baidu":
        return BAIDU_MODELS
    if provider in {"fake", "mock"}:
        return ALL_MODELS
