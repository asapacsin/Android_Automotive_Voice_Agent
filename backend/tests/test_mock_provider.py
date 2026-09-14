from __future__ import annotations

import pytest

from app.voice.mock_provider import MockRealtimeVoiceProvider
from app.voice.models import (
    BLOCKED_BAIDU_FUNCTION_CALLING,
    CapabilityUnsupported,
    DomainEvent,
    DomainEventType,
    ToolResult,
)
from app.config import load_settings
from app.voice.baidu_realtime import BaiduRealtimeVoiceProvider
from app.voice.factory import create_provider


@pytest.mark.asyncio
async def test_mock_provider_connect_send_interrupt_close() -> None:
    provider = MockRealtimeVoiceProvider(auto_reply=True)
    await provider.connect("audio-mini-realtime-near")
    await provider.send_audio(b"\x00\x00" * 2000)
    events = []
    await provider.close()
    async for event in provider.receive_events():
        events.append(event)
        if len(events) > 20:
            break
    types = [e.type for e in events]
    assert DomainEventType.SESSION_UPDATED in types
    assert DomainEventType.AUDIO_DELTA in types
    assert DomainEventType.CLOSED in types
    assert provider.sent_audio_bytes > 0
    assert provider.closed


@pytest.mark.asyncio
async def test_mock_interrupt_cancels_output() -> None:
    provider = MockRealtimeVoiceProvider(auto_reply=False)
    await provider.connect("audio-mini-realtime-near")
    event = await provider.interrupt()
    assert event.type == DomainEventType.INTERRUPTED
    assert event.payload["reason"] == "turn_detected"
    await provider.inject(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": "AA=="}))
    await provider.close()


@pytest.mark.asyncio
async def test_baidu_provider_refuses_tool_results() -> None:
    settings = load_settings(
        {
            "BAIDU_APP_ID": "123",
            "BAIDU_API_KEY": "ak",
            "BAIDU_SECRET_KEY": "sk",
            "BAIDU_E2E_MODEL": "audio-mini-realtime-near",
            "VOICE_PROVIDER": "baidu",
        }
    )
    provider = BaiduRealtimeVoiceProvider(settings)
    assert provider.supports_custom_tools is False
    assert provider.supports_server_vad_interrupt is True
    with pytest.raises(CapabilityUnsupported) as exc:
        await provider.send_tool_result(ToolResult("c1", True, "ok"))
    assert exc.value.code == BLOCKED_BAIDU_FUNCTION_CALLING


def test_factory_mock_vs_baidu() -> None:
    mock_settings = load_settings({"VOICE_PROVIDER": "mock", "BAIDU_E2E_MODEL": "audio-mini-realtime-near"})
    assert create_provider(mock_settings).provider_id == "mock.realtime"
    baidu_settings = load_settings(
        {
            "VOICE_PROVIDER": "baidu",
            "BAIDU_APP_ID": "1",
            "BAIDU_API_KEY": "a",
            "BAIDU_SECRET_KEY": "s",
        }
    )
    assert create_provider(baidu_settings).provider_id == "baidu.e2e.realtime"
    assert create_provider(baidu_settings, force_mock=True).provider_id == "mock.realtime"
