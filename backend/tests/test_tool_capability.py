from __future__ import annotations

import os

import pytest

from app.voice.baidu_realtime import BaiduRealtimeVoiceProvider
from app.voice.models import BLOCKED_BAIDU_FUNCTION_CALLING, FUNCTION_CALLING_EVIDENCE
from app.config import load_settings


def test_function_calling_officially_unsupported() -> None:
    settings = load_settings(
        {
            "BAIDU_APP_ID": "1",
            "BAIDU_API_KEY": "a",
            "BAIDU_SECRET_KEY": "s",
        }
    )
    provider = BaiduRealtimeVoiceProvider(settings)
    assert provider.supports_custom_tools is False
    assert BLOCKED_BAIDU_FUNCTION_CALLING in FUNCTION_CALLING_EVIDENCE
    assert "session.update" in FUNCTION_CALLING_EVIDENCE
    assert "https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei" in FUNCTION_CALLING_EVIDENCE
    assert "2026-09-04" in FUNCTION_CALLING_EVIDENCE
    assert "UpdateSession" in FUNCTION_CALLING_EVIDENCE
    assert "tool_choice" in FUNCTION_CALLING_EVIDENCE
    assert "tools: []" in FUNCTION_CALLING_EVIDENCE or "tools:[]" in FUNCTION_CALLING_EVIDENCE.replace(" ", "")


@pytest.mark.skipif(
    os.environ.get("RUN_BAIDU_LIVE_TESTS", "").lower() != "true",
    reason="live Baidu tests are opt-in via RUN_BAIDU_LIVE_TESTS=true",
)
@pytest.mark.asyncio
async def test_live_baidu_connect_is_opt_in() -> None:
    pytest.skip("executed only when credentials and quota are explicitly opted in")
