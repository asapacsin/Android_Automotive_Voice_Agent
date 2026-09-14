from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app


def test_health_and_capabilities_quota_free() -> None:
    client = TestClient(app)
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    caps = client.get("/v1/voice/capabilities")
    assert caps.status_code == 200
    body = caps.json()
    assert body["true_e2e_audio"] is True
    assert body["default_model"] == "qwen-audio-3.0-realtime-flash"
    assert body["default_provider"] == "qwen"
    assert body["providers"]["qwen"]["custom_tools"] is True
    assert body["providers"]["baidu"]["custom_tools"] is False
    assert body["providers"]["baidu"]["client_response_cancel"] is False
    assert body["gpt_live_optional"] is True
    assert "YOUR_API_KEY" not in str(body)
    assert "function_calling_evidence" in body
    evidence = body["function_calling_evidence"]
    assert "2026-09-04" in evidence
    assert "UpdateSession" in evidence
    assert "tool_choice" in evidence
    assert "BLOCKED_BAIDU_FUNCTION_CALLING" in evidence
