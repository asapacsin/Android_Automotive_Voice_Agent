"""Official Qwen Audio Realtime protocol names.

Sources retrieved 2026-09-14:
- https://docs.qwencloud.com/api-reference/qwen-audio-realtime/websocket-api
- https://docs.qwencloud.com/api-reference/qwen-audio-realtime/client-events
- https://docs.qwencloud.com/api-reference/qwen-audio-realtime/server-events
Do not invent event names.
"""

from __future__ import annotations

from typing import Any

from app.voice.models import DomainEvent, DomainEventType
from app.voice.translator import sanitize_error_message

CLIENT_SESSION_UPDATE = "session.update"
CLIENT_INPUT_AUDIO_APPEND = "input_audio_buffer.append"
CLIENT_INPUT_AUDIO_COMMIT = "input_audio_buffer.commit"
CLIENT_RESPONSE_CREATE = "response.create"
CLIENT_RESPONSE_CANCEL = "response.cancel"
CLIENT_ITEM_CREATE = "conversation.item.create"

SET_TEMPERATURE_TOOL = {
    "type": "function",
    "function": {
        "name": "set_temperature",
        "description": "Set cabin temperature in Celsius after safety verification",
        "parameters": {
            "type": "object",
            "properties": {
                "temperature_c": {"type": "string"},
                "zone": {"type": "string"},
            },
            "required": ["temperature_c"],
        },
    },
}


def session_update_payload(*, include_tools: bool = True) -> dict[str, Any]:
    session: dict[str, Any] = {
        "modalities": ["text", "audio"],
        "voice": "longanqian",
        "input_audio_format": "pcm",
        "output_audio_format": "pcm",
        "turn_detection": {
            "type": "server_vad",
            "threshold": 0.5,
            "silence_duration_ms": 800,
        },
    }
    if include_tools:
        session["tools"] = [SET_TEMPERATURE_TOOL]
    return {"type": CLIENT_SESSION_UPDATE, "session": session}


def input_audio_append_payload(audio_b64: str) -> dict[str, Any]:
    return {"type": CLIENT_INPUT_AUDIO_APPEND, "audio": audio_b64}


def input_audio_commit_payload() -> dict[str, Any]:
    return {"type": CLIENT_INPUT_AUDIO_COMMIT}


def response_cancel_payload() -> dict[str, Any]:
    return {"type": CLIENT_RESPONSE_CANCEL}


def response_create_payload() -> dict[str, Any]:
    return {"type": CLIENT_RESPONSE_CREATE}


def function_call_output_payload(call_id: str, output: str) -> dict[str, Any]:
    return {
        "type": CLIENT_ITEM_CREATE,
        "item": {
            "type": "function_call_output",
            "call_id": call_id,
            "output": output,
        },
    }


def classify_qwen_error(code: str | None, message: str | None) -> str:
    blob = f"{code or ''} {message or ''}".lower()
    if "401" in blob or "403" in blob or "invalid" in blob and "key" in blob or "unauthorized" in blob:
        return "QWEN_AUTH_FAILED"
    if "quota" in blob or "rate" in blob:
        return "QWEN_RATE_LIMIT"
    if "timeout" in blob:
        return "QWEN_TIMEOUT"
    return "QWEN_API_REJECTED"


def translate_qwen_event(raw: dict[str, Any], *, speaking: bool = False) -> list[DomainEvent]:
    event_type = raw.get("type")
    if not event_type:
        return []
    if event_type in {"session.created", "session.updated"}:
        session = raw.get("session") or {}
        return [
            DomainEvent(
                DomainEventType.SESSION_UPDATED if event_type.endswith("updated") else DomainEventType.SESSION_CREATED,
                {
                    "session_id": session.get("id"),
                    "model": session.get("model"),
                    "interrupt_response": True,
                },
            )
        ]
    if event_type == "input_audio_buffer.speech_started":
        events = [DomainEvent(DomainEventType.SPEECH_STARTED, {"item_id": raw.get("item_id")})]
        if speaking:
            events.append(DomainEvent(DomainEventType.INTERRUPTED, {"reason": "turn_detected"}))
        return events
    if event_type == "input_audio_buffer.speech_stopped":
        return [DomainEvent(DomainEventType.SPEECH_STOPPED, {"item_id": raw.get("item_id")})]
    if event_type in {
        "conversation.item.input_audio_transcription.delta",
        "conversation.item.input_audio_transcription.completed",
    }:
        text = raw.get("delta") or raw.get("transcript") or ""
        return [
            DomainEvent(
                DomainEventType.USER_TRANSCRIPT,
                {"text": text, "final": event_type.endswith("completed")},
            )
        ]
    if event_type == "response.audio.delta":
        return [DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": raw.get("delta") or ""})]
    if event_type == "response.audio.done":
        return [DomainEvent(DomainEventType.AUDIO_DONE, {})]
    if event_type in {"response.audio_transcript.delta", "response.audio_transcript.done"}:
        return [
            DomainEvent(
                DomainEventType.ASSISTANT_TRANSCRIPT,
                {"text": raw.get("delta") or raw.get("transcript") or "", "final": event_type.endswith("done")},
            )
        ]
    if event_type == "response.function_call_arguments.done":
        arguments = raw.get("arguments") or "{}"
        parsed: dict[str, Any] = {}
        if isinstance(arguments, dict):
            parsed = {str(k): str(v) for k, v in arguments.items()}
        else:
            import json

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
                    "call_id": raw.get("call_id"),
                    "name": raw.get("name"),
                    "arguments": parsed,
                },
            )
        ]
    if event_type == "response.done":
        response = raw.get("response") or {}
        status = response.get("status") or raw.get("status") or "completed"
        details = response.get("status_details") or {}
        reason = details.get("reason") or raw.get("reason")
        events = [DomainEvent(DomainEventType.RESPONSE_DONE, {"status": status, "reason": reason})]
        if status == "cancelled":
            events.insert(0, DomainEvent(DomainEventType.INTERRUPTED, {"reason": reason or "cancelled"}))
        return events
    if event_type == "error":
        error = raw.get("error") or raw
        code = classify_qwen_error(error.get("code"), error.get("message"))
        return [
            DomainEvent(
                DomainEventType.ERROR,
                {"code": code, "message": sanitize_error_message(error.get("message"))},
            )
        ]
    return []
