from __future__ import annotations

from pathlib import Path

from app.voice.baidu_protocol import SESSION_UPDATE
from app.voice.models import DomainEventType
from app.voice.protocol import CLIENT_INPUT_AUDIO_APPEND, CLIENT_SESSION_UPDATE, session_update_payload
from app.voice.translator import classify_error, sanitize_error_message, translate_baidu_event

ROOT = Path(__file__).resolve().parents[1]


def test_session_created_maps_without_inventing_tools() -> None:
    events = translate_baidu_event(
        {
            "type": "session.created",
            "session": {
                "id": "sess_1",
                "model": "audio-mini-realtime-near",
                "tools": [],
                "turn_detection": {"interrupt_response": True, "type": "server_vad"},
            },
        }
    )
    assert events[0].type == DomainEventType.SESSION_CREATED
    assert events[0].payload["tools"] == []
    assert events[0].payload["interrupt_response"] is True


def test_audio_delta_and_transcript_use_official_field_names() -> None:
    audio = translate_baidu_event({"type": "response.audio.delta", "delta": "abc"})
    assert audio[0].type == DomainEventType.AUDIO_DELTA
    assert audio[0].payload["audio"] == "abc"
    text = translate_baidu_event(
        {"type": "response.audio_transcript.delta", "delta": "你好"}
    )
    assert text[0].type == DomainEventType.ASSISTANT_TRANSCRIPT
    assert text[0].payload["text"] == "你好"


def test_speech_started_while_speaking_is_interrupted() -> None:
    events = translate_baidu_event(
        {"type": "input_audio_buffer.speech_started", "item_id": "item_1"},
        speaking=True,
    )
    types = [e.type for e in events]
    assert DomainEventType.SPEECH_STARTED in types
    assert DomainEventType.INTERRUPTED in types


def test_response_done_cancelled_turn_detected() -> None:
    events = translate_baidu_event(
        {
            "type": "response.done",
            "response": {
                "status": "cancelled",
                "status_details": {"type": "cancelled", "reason": "turn_detected"},
            },
        }
    )
    assert events[0].type == DomainEventType.INTERRUPTED
    assert events[1].payload["status"] == "cancelled"


def test_quota_and_auth_errors_are_coded_without_secrets() -> None:
    assert classify_error("billing", "quota exceeded") == "BAIDU_QUOTA_EXHAUSTED"
    assert classify_error("invalid_client", "unknown client id") == "BAIDU_AUTH_FAILED"
    sanitized = sanitize_error_message("Secret Key abc rejected")
    assert "secret" not in sanitized.lower()
    assert "abc" not in sanitized
    assert sanitized == "provider rejected the request"
    events = translate_baidu_event(
        {"type": "error", "error": {"code": "invalid_client", "message": "Secret Key xyz rejected"}}
    )
    assert events[0].payload["code"] == "BAIDU_AUTH_FAILED"
    assert "xyz" not in events[0].payload["message"]


def test_non_message_item_is_tool_unsupported_not_parsed_prose() -> None:
    events = translate_baidu_event(
        {
            "type": "response.output_item.added",
            "item": {"type": "function_call", "name": "set_temperature"},
        }
    )
    assert events[0].type == DomainEventType.TOOL_UNSUPPORTED
    assert events[0].payload["reason"] == "BLOCKED_BAIDU_FUNCTION_CALLING"


def test_documented_client_session_update_has_no_tools_field() -> None:
    payload = session_update_payload()
    assert payload["type"] == CLIENT_SESSION_UPDATE == SESSION_UPDATE
    assert "tools" not in payload["session"]
    assert "tool_choice" not in payload["session"]
    assert payload["session"]["turn_detection"]["interrupt_response"] is True
    assert CLIENT_INPUT_AUDIO_APPEND == "input_audio_buffer.append"


def test_official_baidu_docs_metadata_is_2026_09_04() -> None:
    protocol_src = (ROOT / "app" / "voice" / "protocol.py").read_text(encoding="utf-8")
    baidu_protocol_src = (ROOT / "app" / "voice" / "baidu_protocol.py").read_text(encoding="utf-8")
    models_src = (ROOT / "app" / "voice" / "models.py").read_text(encoding="utf-8")
    for text in (protocol_src, baidu_protocol_src, models_src):
        assert "2026-09-04" in text
        assert "2026-07-10" not in text
    assert "client_notify_only" not in (ROOT / "app" / "voice" / "baidu_realtime.py").read_text(
        encoding="utf-8"
    )
