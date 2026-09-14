from __future__ import annotations

import asyncio
import time

from app.config import load_settings
from app.voice.fake_provider import FakeRealtimeVoiceProvider
from app.voice.factory import create_provider
from app.voice.models import DomainEventType


def test_factory_selects_qwen_gpt_live_baidu_fake_and_mock() -> None:
    qwen = load_settings({"VOICE_PROVIDER": "qwen", "DASHSCOPE_API_KEY": "sk-x"})
    assert create_provider(qwen).provider_id == "qwen.audio.realtime"
    gpt = load_settings({"VOICE_PROVIDER": "gpt_live", "OPENAI_API_KEY": "sk-y"})
    assert create_provider(gpt).provider_id == "openai.gpt.live"
    fake = load_settings({"VOICE_PROVIDER": "fake"})
    assert create_provider(fake).provider_id == "fake.realtime"
    mock = load_settings({"VOICE_PROVIDER": "mock"})
    assert create_provider(mock).provider_id == "mock.realtime"
    baidu = load_settings(
        {
            "VOICE_PROVIDER": "baidu",
            "BAIDU_APP_ID": "1",
            "BAIDU_API_KEY": "a",
            "BAIDU_SECRET_KEY": "s",
        }
    )
    assert create_provider(baidu).provider_id == "baidu.e2e.realtime"
    assert create_provider(qwen, force_mock=True).provider_id == "mock.realtime"


def test_fake_scenarios_cover_required_cases() -> None:
    async def body() -> dict[str, object]:
        provider = FakeRealtimeVoiceProvider()
        await provider.connect("fake-realtime")
        await provider.send_audio(b"\x00\x00" * 200)
        started = time.perf_counter()
        await provider.play_mandarin()
        await provider.play_code_switch()
        await provider.play_rapid_interrupt()
        await provider.play_work_refine()
        await provider.play_reconnect()
        await provider.interrupt()
        await provider.close()
        kinds: list[str] = []
        async for event in provider.receive_events():
            kinds.append(event.type.value)
        elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
        return {
            "kinds": kinds,
            "elapsed_ms": elapsed_ms,
            "audio_bytes": provider.sent_audio_bytes,
            "cancel_count": provider.cancel_count,
        }

    result = asyncio.run(body())
    kinds = result["kinds"]
    assert DomainEventType.USER_TRANSCRIPT.value in kinds
    assert DomainEventType.INTERRUPTED.value in kinds
    assert DomainEventType.TOOL_CALL.value in kinds
    assert DomainEventType.ERROR.value in kinds
    assert result["audio_bytes"] > 0
    assert isinstance(result["elapsed_ms"], float)
