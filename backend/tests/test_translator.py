import json
from pathlib import Path

from app.voice.capability import BLOCKED_CODE, function_calling_capability
from app.voice.models import DomainEventType
from app.voice.protocol import CLIENT_INPUT_AUDIO_APPEND, CLIENT_SESSION_UPDATE, input_audio_append_payload, session_update_payload
from app.voice.translator import translate_baidu_event

FIXTURES = Path(__file__).parent / "fixtures"


def test_session_update_uses_only_documented_fields():
    payload = session_update_payload()
    assert payload["type"] == CLIENT_SESSION_UPDATE
    assert "tools" not in payload["session"]
    assert payload["session"]["turn_detection"]["interrupt_response"] is True
    assert payload["session"]["turn_detection"]["type"] == "server_vad"
    assert input_audio_append_payload("audio_base64") == {
        "type": CLIENT_INPUT_AUDIO_APPEND,
        "audio": "audio_base64",
    }


def test_official_session_created_fixture():
    raw = json.loads((FIXTURES / "official_session_created.json").read_text(encoding="utf-8"))
    events = translate_baidu_event(raw)
    assert events[0].type == DomainEventType.SESSION_CREATED
    assert events[0].payload["session_id"] == "sess_ywqGIVMsrQKh8jY4WhYZ"
    assert events[0].payload["tools"] == []


def test_official_audio_delta_fixture():
    raw = json.loads((FIXTURES / "official_response_audio_delta.json").read_text(encoding="utf-8"))
    events = translate_baidu_event(raw)
    assert events[0].type == DomainEventType.AUDIO_DELTA
    assert events[0].payload["audio"] == "audio_base64"


def test_official_interrupt_round_fixture():
    events = json.loads((FIXTURES / "baidu_interrupt_round.json").read_text(encoding="utf-8"))
    kinds = []
    speaking = False
    for raw in events:
        for domain in translate_baidu_event(raw, speaking=speaking):
            kinds.append(domain.type)
            if domain.type == DomainEventType.AUDIO_DELTA:
                speaking = True
            if domain.type == DomainEventType.INTERRUPTED:
                speaking = False
    assert DomainEventType.INTERRUPTED in kinds
    assert events[-1]["response"]["status_details"]["reason"] == "turn_detected"


def test_function_calling_is_blocked_from_official_docs():
    capability = function_calling_capability()
    assert capability["status"] == BLOCKED_CODE
    assert capability["supported"] is False
    excerpt = (FIXTURES / "OFFICIAL_BAIDU_FUNCTION_CALLING.txt").read_text(encoding="utf-8")
    assert "message" in excerpt
    assert "function_call_output" in excerpt
