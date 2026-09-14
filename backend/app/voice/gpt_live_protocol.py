"""Official OpenAI GPT-Live protocol names.

Sources retrieved 2026-09-14:
- https://developers.openai.com/api/docs/guides/voice-websockets
- https://developers.openai.com/api/docs/guides/live
- https://developers.openai.com/api/docs/guides/live-delegation
- https://developers.openai.com/api/docs/guides/live-conversations
Do not invent event names. Speech cancel is not a documented Live client event.
"""

from __future__ import annotations

import json
from typing import Any

from app.voice.models import DomainEvent, DomainEventType
from app.voice.translator import sanitize_error_message

CLIENT_SESSION_START = "session.start"
CLIENT_INPUT_AUDIO_APPEND = "session.input_audio.append"
CLIENT_SESSION_CLOSE = "session.close"
CLIENT_COMMENTARY_APPEND = "session.commentary.append"
CLIENT_THINKING_APPEND = "session.thinking.append"
CLIENT_RESPONSE_CREATE = "response.create"
CLIENT_RESPONSE_ITEM_CREATE = "response.item.create"


def session_start_payload(model: str) -> dict[str, Any]:
    return {
        "type": CLIENT_SESSION_START,
        "event_id": "event_start",
        "session": {
            "model": model,
            "instructions": "Be concise. Delegate vehicle actions to the trusted backend.",
            "audio": {
                "format": {"type": "audio/pcm", "rate": 16000},
                "output": {"voice": "marin"},
            },
            "delegation": {"type": "client"},
        },
    }


def input_audio_append_payload(audio_b64: str) -> dict[str, Any]:
    return {"type": CLIENT_INPUT_AUDIO_APPEND, "audio": audio_b64}


def session_close_payload() -> dict[str, Any]:
    return {"type": CLIENT_SESSION_CLOSE}


def commentary_append_payload(delegation_id: str, content: str) -> dict[str, Any]:
    return {
        "type": CLIENT_COMMENTARY_APPEND,
        "event_id": "result_1",
        "delegation_id": delegation_id,
        "content": content,
    }


def thinking_append_payload(delegation_id: str, content: str) -> dict[str, Any]:
    return {
        "type": CLIENT_THINKING_APPEND,
        "event_id": "progress_1",
        "delegation_id": delegation_id,
        "content": content,
    }


def function_call_output_payload(call_id: str, output: str) -> dict[str, Any]:
    return {
        "type": CLIENT_RESPONSE_ITEM_CREATE,
        "event_id": "tool_result_1",
        "item": {
            "type": "function_call_output",
            "call_id": call_id,
            "output": output,
        },
    }


def response_create_payload() -> dict[str, Any]:
    return {"type": CLIENT_RESPONSE_CREATE, "event_id": "continue_1"}


def classify_gpt_live_error(code: str | None, message: str | None) -> str:
    blob = f"{code or ''} {message or ''}".lower()
    if "auth" in blob or "unauthorized" in blob or "invalid_api_key" in blob:
        return "GPT_LIVE_AUTH_FAILED"
    if "rate" in blob or "quota" in blob:
        return "GPT_LIVE_RATE_LIMIT"
    if "malformed" in blob or "invalid_request" in blob:
        return "GPT_LIVE_MALFORMED"
    if "timeout" in blob:
        return "GPT_LIVE_TIMEOUT"
    return "GPT_LIVE_API_REJECTED"


def translate_gpt_live_event(raw: dict[str, Any]) -> list[DomainEvent]:
    event_type = raw.get("type")
    if not event_type:
        return []
    if event_type in {"session.started", "session.updated"}:
        session = raw.get("session") or {}
        return [
            DomainEvent(
                DomainEventType.SESSION_UPDATED if event_type.endswith("updated") else DomainEventType.SESSION_CREATED,
                {
                    "session_id": session.get("id"),
                    "model": session.get("model") or "gpt-live-1",
                    "interrupt_response": True,
                },
            )
        ]
    if event_type == "session.output_audio.delta":
        return [DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": raw.get("delta") or ""})]
    if event_type == "session.input_transcript.delta":
        return [DomainEvent(DomainEventType.USER_TRANSCRIPT, {"text": raw.get("delta") or "", "final": False})]
    if event_type == "session.output_transcript.delta":
        return [DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": raw.get("delta") or "", "final": False})]
    if event_type == "session.delegation.created":
        delegation = raw.get("delegation") or {}
        return [
            DomainEvent(
                DomainEventType.TOOL_CALL,
                {
                    "call_id": delegation.get("id"),
                    "name": delegation.get("target") or "client",
                    "arguments": {},
                },
            )
        ]
    if event_type == "response.event":
        nested = raw.get("event") or {}
        item = nested.get("item") or {}
        if nested.get("type") == "response.output_item.done" and item.get("type") == "function_call":
            arguments = item.get("arguments") or "{}"
            parsed: dict[str, Any] = {}
            if isinstance(arguments, dict):
                parsed = {str(k): str(v) for k, v in arguments.items()}
            else:
                try:
                    loaded = json.loads(arguments)
                    if isinstance(loaded, dict):
                        parsed = {str(k): str(v) for k, v in loaded.items()}
                except json.JSONDecodeError:
                    parsed = {}
            return [
                DomainEvent(
                    DomainEventType.TOOL_CALL,
                    {
                        "call_id": item.get("call_id"),
                        "name": item.get("name"),
                        "arguments": parsed,
                    },
                )
            ]
        return []
    if event_type == "error":
        error = raw.get("error") or raw
        code = classify_gpt_live_error(error.get("code"), error.get("message"))
        return [
            DomainEvent(
                DomainEventType.ERROR,
                {"code": code, "message": sanitize_error_message(error.get("message"))},
            )
        ]
    if event_type == "session.closed":
        return [DomainEvent(DomainEventType.CLOSED, {})]
    return []
