from __future__ import annotations

"""Exact Baidu E2E realtime names from official docs.

Source: https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei
Updated: 2026-09-04 (retrieved 2026-09-14)

Returned Session examples include tool_choice and tools: []. UpdateSession
and session.update do not expose those fields, so they are not a custom-tool
protocol. Do not add OpenAI/Qwen-only client events here (response.cancel,
input_audio_buffer.commit, conversation.item.create, function_call_output).
Those are not documented on this Baidu page.
"""

import json
from typing import Any

# Client events (only these two are documented on the E2E page).
SESSION_UPDATE = "session.update"
INPUT_AUDIO_BUFFER_APPEND = "input_audio_buffer.append"

# Server events documented on the same page.
SESSION_CREATED = "session.created"
SESSION_UPDATED = "session.updated"
CONVERSATION_CREATED = "conversation.created"
CONVERSATION_ITEM_CREATED = "conversation.item.created"
INPUT_AUDIO_TRANSCRIPTION_DELTA = "conversation.item.input_audio_transcription.delta"
INPUT_AUDIO_TRANSCRIPTION_COMPLETED = "conversation.item.input_audio_transcription.completed"
INPUT_AUDIO_TRANSCRIPTION_FAILED = "conversation.item.input_audio_transcription.failed"
INPUT_AUDIO_BUFFER_COMMITTED = "input_audio_buffer.committed"
INPUT_AUDIO_BUFFER_SPEECH_STARTED = "input_audio_buffer.speech_started"
INPUT_AUDIO_BUFFER_SPEECH_STOPPED = "input_audio_buffer.speech_stopped"
RESPONSE_CREATED = "response.created"
RESPONSE_DONE = "response.done"
RESPONSE_OUTPUT_ITEM_ADDED = "response.output_item.added"
RESPONSE_OUTPUT_ITEM_DONE = "response.output_item.done"
RESPONSE_CONTENT_PART_ADDED = "response.content_part.added"
RESPONSE_CONTENT_PART_DONE = "response.content_part.done"
RESPONSE_AUDIO_DELTA = "response.audio.delta"
RESPONSE_AUDIO_DONE = "response.audio.done"
RESPONSE_AUDIO_TRANSCRIPT_DELTA = "response.audio_transcript.delta"
RESPONSE_AUDIO_TRANSCRIPT_DONE = "response.audio_transcript.done"

DOCUMENTED_CLIENT_EVENTS = frozenset({SESSION_UPDATE, INPUT_AUDIO_BUFFER_APPEND})
DOCUMENTED_SERVER_EVENTS = frozenset(
    {
        SESSION_CREATED,
        SESSION_UPDATED,
        CONVERSATION_CREATED,
        CONVERSATION_ITEM_CREATED,
        INPUT_AUDIO_TRANSCRIPTION_DELTA,
        INPUT_AUDIO_TRANSCRIPTION_COMPLETED,
        INPUT_AUDIO_TRANSCRIPTION_FAILED,
        INPUT_AUDIO_BUFFER_COMMITTED,
        INPUT_AUDIO_BUFFER_SPEECH_STARTED,
        INPUT_AUDIO_BUFFER_SPEECH_STOPPED,
        RESPONSE_CREATED,
        RESPONSE_DONE,
        RESPONSE_OUTPUT_ITEM_ADDED,
        RESPONSE_OUTPUT_ITEM_DONE,
        RESPONSE_CONTENT_PART_ADDED,
        RESPONSE_CONTENT_PART_DONE,
        RESPONSE_AUDIO_DELTA,
        RESPONSE_AUDIO_DONE,
        RESPONSE_AUDIO_TRANSCRIPT_DELTA,
        RESPONSE_AUDIO_TRANSCRIPT_DONE,
    }
)

# ConversationItem.type allowed value from official datatype table: message only.
CONVERSATION_ITEM_ALLOWED_TYPES = frozenset({"message"})

OFFICIAL_ENDPOINT = "wss://aip.baidubce.com/ws/2.0/speech/v1/realtime"
OFFICIAL_INPUT_AUDIO = "pcm16"
OFFICIAL_INPUT_SAMPLE_RATE_HZ = 16000
OFFICIAL_TURN_DETECTION_TYPE = "server_vad"


def build_session_update(*, event_id: str | None = None) -> dict[str, Any]:
    """Build session.update using only documented UpdateSession fields."""
    payload: dict[str, Any] = {
        "type": SESSION_UPDATE,
        "session": {
            "input_audio_format": OFFICIAL_INPUT_AUDIO,
            "input_audio_transcription": {"model": "default"},
            "output_audio_format": OFFICIAL_INPUT_AUDIO,
            "turn_detection": {
                "type": OFFICIAL_TURN_DETECTION_TYPE,
                "create_response": True,
                "interrupt_response": True,
            },
        },
    }
    if event_id:
        payload["event_id"] = event_id
    return payload


def build_input_audio_append(pcm16_base64: str, event_id: str | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "type": INPUT_AUDIO_BUFFER_APPEND,
        "audio": pcm16_base64,
    }
    if event_id:
        payload["event_id"] = event_id
    return payload


def parse_server_event(raw: str | dict[str, Any]) -> dict[str, Any]:
    data = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(data, dict) or "type" not in data:
        raise ValueError("not a Baidu realtime event object")
    return data


def dumps(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
