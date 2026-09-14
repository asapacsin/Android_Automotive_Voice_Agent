from __future__ import annotations

import asyncio
import base64
import logging
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.config import (
    CATALOG_MODELS,
    DEFAULT_MODEL,
    OFFICIAL_AUTH_DOC,
    OFFICIAL_REALTIME_DOC,
    Settings,
    load_settings,
    require_provider_credentials,
    validate_catalog_model,
    ConfigError,
)
from app.logging_safe import configured_flag, install_redacting_logging
from app.voice.catalog import (
    DEFAULT_PROVIDER,
    GPT_LIVE_DOCS,
    PROVIDER_CAPABILITIES,
    QWEN_DOCS,
    default_model_for,
)
from app.voice.factory import create_provider
from app.voice.models import (
    BLOCKED_BAIDU_FUNCTION_CALLING,
    FUNCTION_CALLING_EVIDENCE,
    DomainEventType,
    ProviderError,
    ToolResult,
    VoiceUiState,
)
from app.voice.provider import RealtimeVoiceProvider

router = APIRouter()
logger = logging.getLogger("novadrive.voice")


def _settings() -> Settings:
    return load_settings()


def public_status(settings: Settings | None = None) -> dict[str, Any]:
    cfg = settings or _settings()
    caps = PROVIDER_CAPABILITIES.get(cfg.voice_provider, PROVIDER_CAPABILITIES["baidu"])
    return {
        "provider": cfg.voice_provider,
        "provider_display": cfg.voice_provider,
        "default_provider": DEFAULT_PROVIDER,
        "model": cfg.model,
        "model_label": cfg.model_label,
        "default_model": DEFAULT_MODEL,
        "allowed_models": CATALOG_MODELS,
        "provider_models": {
            "qwen": default_model_for("qwen"),
            "gpt_live": default_model_for("gpt_live"),
            "baidu": default_model_for("baidu"),
            "fake": default_model_for("fake"),
        },
        "credentials": {
            "BAIDU_APP_ID": configured_flag("BAIDU_APP_ID", cfg.app_id_configured).endswith("yes"),
            "BAIDU_API_KEY": configured_flag("BAIDU_API_KEY", cfg.api_key_configured).endswith("yes"),
            "BAIDU_SECRET_KEY": configured_flag("BAIDU_SECRET_KEY", cfg.secret_key_configured).endswith("yes"),
            "DASHSCOPE_API_KEY": configured_flag("DASHSCOPE_API_KEY", cfg.qwen_configured).endswith("yes"),
            "OPENAI_API_KEY": configured_flag("OPENAI_API_KEY", cfg.gpt_live_configured).endswith("yes"),
        },
        "ws_host": {
            "qwen": "dashscope-intl.aliyuncs.com",
            "gpt_live": "api.openai.com",
            "baidu": "aip.baidubce.com",
            "fake": "local",
            "mock": "local",
        }.get(cfg.voice_provider, "local"),
        "true_e2e_audio": True,
        "custom_function_calling": bool(caps.get("custom_tools")),
        "function_calling_status": (
            BLOCKED_BAIDU_FUNCTION_CALLING if cfg.voice_provider == "baidu" else "supported"
        ),
        "capabilities": caps,
        "providers": PROVIDER_CAPABILITIES,
        "interruption": "provider_native",
        "docs": {
            "realtime": OFFICIAL_REALTIME_DOC,
            "auth": OFFICIAL_AUTH_DOC,
            "qwen": QWEN_DOCS,
            "gpt_live": GPT_LIVE_DOCS,
        },
        "gpt_live_optional": True,
    }


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/v1/voice/status")
async def voice_status() -> dict[str, Any]:
    return public_status()


@router.get("/v1/voice/capabilities")
async def voice_capabilities() -> dict[str, Any]:
    status = public_status()
    status["function_calling_evidence"] = FUNCTION_CALLING_EVIDENCE
    return status


@router.websocket("/v1/voice/realtime")
async def voice_realtime(websocket: WebSocket) -> None:
    await websocket.accept()
    settings = _settings()
    install_redacting_logging(settings.secret_values())
    provider: RealtimeVoiceProvider | None = None
    pump: asyncio.Task[None] | None = None
    session_active = False
    try:
        while True:
            message = await websocket.receive_json()
            msg_type = message.get("type")
            if msg_type == "start":
                if pump is not None:
                    pump.cancel()
                if provider is not None:
                    await provider.close()
                requested_provider = (message.get("provider") or settings.voice_provider).strip().lower()
                if requested_provider and requested_provider != settings.voice_provider:
                    settings = _override_provider(settings, requested_provider)
                model = validate_catalog_model(message.get("model") or settings.model, settings.voice_provider)
                try:
                    require_provider_credentials(settings)
                    provider = create_provider(settings)
                    await websocket.send_json(
                        {"type": "state", "state": VoiceUiState.CONNECTING.value}
                    )
                    logger.info(
                        "%s ; %s ; provider=%s model=%s",
                        configured_flag("DASHSCOPE_API_KEY", settings.qwen_configured),
                        configured_flag("OPENAI_API_KEY", settings.gpt_live_configured),
                        settings.voice_provider,
                        model,
                    )
                    await provider.connect(model)
                    session_active = True
                    pump = asyncio.create_task(_pump_events(websocket, provider))
                except ConfigError as exc:
                    await websocket.send_json(_error_payload(exc.code, exc.message))
                    await websocket.send_json(
                        {"type": "state", "state": VoiceUiState.ERROR.value, "code": exc.code}
                    )
                except ProviderError as exc:
                    await websocket.send_json(_error_payload(exc.code, exc.message))
                    await websocket.send_json(
                        {"type": "state", "state": VoiceUiState.ERROR.value, "code": exc.code}
                    )
            elif msg_type == "audio":
                if not session_active or provider is None:
                    continue
                audio_b64 = message.get("audio") or ""
                await provider.send_audio(base64.b64decode(audio_b64))
            elif msg_type == "commit":
                if provider is not None:
                    await provider.commit_audio()
            elif msg_type == "interrupt":
                if provider is not None:
                    event = await provider.interrupt()
                    if event is not None:
                        await websocket.send_json(event.to_client_json())
            elif msg_type == "work_result":
                if provider is not None:
                    result = ToolResult(
                        call_id=str(message.get("call_id") or ""),
                        ok=bool(message.get("ok")),
                        output=str(message.get("output") or ""),
                    )
                    event = await provider.inject_work_result(result)
                    await websocket.send_json(event.to_client_json())
            elif msg_type == "stop":
                session_active = False
                if provider is not None:
                    await provider.close()
                await websocket.send_json(
                    {"type": "state", "state": VoiceUiState.DISCONNECTED.value}
                )
            else:
                await websocket.send_json(
                    _error_payload("PROVIDER_REJECTED", f"unknown client message {msg_type}")
                )
    except WebSocketDisconnect:
        session_active = False
    except ConfigError as exc:
        await websocket.send_json(_error_payload(exc.code, exc.message))
    except ProviderError as exc:
        await websocket.send_json(_error_payload(exc.code, exc.message))
    finally:
        session_active = False
        if pump is not None:
            pump.cancel()
        if provider is not None:
            await provider.close()


def _override_provider(settings: Settings, provider: str) -> Settings:
    from dataclasses import replace

    return replace(settings, voice_provider=provider)


async def _pump_events(websocket: WebSocket, provider: RealtimeVoiceProvider) -> None:
    speaking = False
    try:
        async for event in provider.receive_events():
            if event.type == DomainEventType.SESSION_UPDATED:
                await websocket.send_json({"type": "state", "state": VoiceUiState.LISTENING.value})
            elif event.type == DomainEventType.SPEECH_STOPPED:
                await websocket.send_json({"type": "state", "state": VoiceUiState.THINKING.value})
            elif event.type == DomainEventType.AUDIO_DELTA:
                speaking = True
                await websocket.send_json({"type": "state", "state": VoiceUiState.SPEAKING.value})
            elif event.type == DomainEventType.INTERRUPTED:
                speaking = False
                await websocket.send_json({"type": "state", "state": VoiceUiState.LISTENING.value})
            elif event.type == DomainEventType.RESPONSE_DONE:
                speaking = False
                if event.payload.get("status") != "failed":
                    await websocket.send_json(
                        {"type": "state", "state": VoiceUiState.LISTENING.value}
                    )
            elif event.type == DomainEventType.ERROR:
                await websocket.send_json(
                    {"type": "state", "state": VoiceUiState.ERROR.value, "code": event.payload.get("code")}
                )
            await websocket.send_json(event.to_client_json())
            _ = speaking
    except asyncio.CancelledError:
        return
    except Exception:
        await websocket.send_json(_error_payload("SERVER_DISCONNECT", "event pump failed"))


def _error_payload(code: str, message: str) -> dict[str, str]:
    return {"type": "error", "code": code, "message": message}
