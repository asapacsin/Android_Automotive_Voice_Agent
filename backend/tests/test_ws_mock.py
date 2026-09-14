from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import load_settings
from app.main import app


def test_default_start_with_placeholders_is_qwen_credentials_missing(monkeypatch) -> None:
    settings = load_settings(
        environ={
            "BAIDU_APP_ID": "YOUR_APP_ID",
            "BAIDU_API_KEY": "YOUR_API_KEY",
            "BAIDU_SECRET_KEY": "YOUR_SECRET_KEY",
        }
    )
    monkeypatch.setattr("app.voice.routes.load_settings", lambda: settings)
    with TestClient(app) as client:
        with client.websocket_connect("/v1/voice/realtime") as ws:
            ws.send_json({"type": "start"})
            first = ws.receive_json()
            assert first["type"] == "error"
            assert first["code"] == "QWEN_CREDENTIALS_MISSING"
            second = ws.receive_json()
            assert second["type"] == "state"
            assert second["code"] == "QWEN_CREDENTIALS_MISSING"


def test_baidu_interrupt_message_does_not_claim_cancel_success(monkeypatch) -> None:
    settings = load_settings(
        environ={
            "VOICE_PROVIDER": "baidu",
            "BAIDU_APP_ID": "app-1",
            "BAIDU_API_KEY": "ak",
            "BAIDU_SECRET_KEY": "sk",
        }
    )
    monkeypatch.setattr("app.voice.routes.load_settings", lambda: settings)
    from app.voice.baidu_realtime import BaiduRealtimeVoiceProvider

    class QuietBaidu(BaiduRealtimeVoiceProvider):
        async def connect(self, model: str) -> None:
            return None

        async def close(self) -> None:
            return None

    monkeypatch.setattr(
        "app.voice.routes.create_provider",
        lambda _settings, force_mock=False: QuietBaidu(_settings),
    )
    with TestClient(app) as client:
        with client.websocket_connect("/v1/voice/realtime") as ws:
            ws.send_json({"type": "start", "model": "audio-mini-realtime-near"})
            connecting = ws.receive_json()
            assert connecting.get("state") == "Connecting"
            ws.send_json({"type": "interrupt"})
            ws.send_json({"type": "stop"})
            follow_up = ws.receive_json()
            assert follow_up.get("type") != "interrupted"
            assert follow_up.get("reason") != "client_notify_only"


def test_mock_websocket_session_does_not_need_baidu(monkeypatch) -> None:
    monkeypatch.setenv("VOICE_PROVIDER", "mock")
    monkeypatch.setenv("BAIDU_E2E_MODEL", "audio-mini-realtime-near")
    with TestClient(app) as client:
        with client.websocket_connect("/v1/voice/realtime") as ws:
            ws.send_json({"type": "start", "model": "audio-mini-realtime-near"})
            first = ws.receive_json()
            ws.send_json({"type": "stop"})
            assert first.get("type") in {"state", "error"}
            if first.get("type") == "state":
                assert first["state"] == "Connecting"
            assert "YOUR_SECRET_KEY" not in str(first)
