from __future__ import annotations

from app.config import Settings
from app.voice.baidu_realtime import BaiduRealtimeVoiceProvider
from app.voice.fake_provider import FakeRealtimeVoiceProvider
from app.voice.gpt_live import GPTLiveProvider
from app.voice.mock_provider import MockRealtimeVoiceProvider
from app.voice.provider import RealtimeVoiceProvider
from app.voice.qwen_realtime import QwenRealtimeProvider


def create_provider(settings: Settings, *, force_mock: bool = False) -> RealtimeVoiceProvider:
    if force_mock or settings.voice_provider == "mock":
        return MockRealtimeVoiceProvider()
    if settings.voice_provider == "fake":
        return FakeRealtimeVoiceProvider()
    if settings.voice_provider == "qwen":
        return QwenRealtimeProvider(settings)
    if settings.voice_provider == "gpt_live":
        return GPTLiveProvider(settings)
    return BaiduRealtimeVoiceProvider(settings)
