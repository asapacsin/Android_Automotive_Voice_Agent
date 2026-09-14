from __future__ import annotations

from typing import Any

from app.voice import protocol
from app.voice.models import DomainEvent, DomainEventType


_QUOTA_MARKERS = (
    "quota",
    "qps",
    "rate limit",
    "limit exceeded",
    "insufficient",
    "配额",
    "超量",
    "次数用尽",
    "余额不足",
)


def sanitize_error_message(message: str | None) -> str:
    if not message:
        return "provider error"
    lowered = message.lower()
    if "secret" in lowered or "api key" in lowered or "access_token" in lowered:
        return "provider rejected the request"
    return message[:240]


def classify_error(code: str | None, message: str | None) -> str:
    blob = f"{code or ''} {message or ''}".lower()
    if any(marker in blob for marker in _QUOTA_MARKERS):
        return "BAIDU_QUOTA_EXHAUSTED"
    if code in {"invalid_client", "unauthorized", "invalid_api_key"}:
        return "BAIDU_AUTH_FAILED"
    if "auth" in blob or "unauthorized" in blob or "invalid_client" in blob:
        return "BAIDU_AUTH_FAILED"
    if "timeout" in blob:
        return "BAIDU_TIMEOUT"
    if code in {"invalid_model", "model_not_found"}:
        return "BAIDU_INVALID_MODEL"
    return "BAIDU_API_REJECTED"


def translate_baidu_event(raw: dict[str, Any], *, speaking: bool = False) -> list[DomainEvent]:
    event_type = raw.get("type")
    if not event_type:
        return []

    if event_type == protocol.SERVER_SESSION_CREATED:
        session = raw.get("session") or {}
        return [
            DomainEvent(
                DomainEventType.SESSION_CREATED,
                {
                    "session_id": session.get("id"),
                    "model": session.get("model"),
                    "tools": session.get("tools") or [],
                    "interrupt_response": _interrupt_flag(session),
                },
            )
        ]
    if event_type == protocol.SERVER_SESSION_UPDATED:
        session = raw.get("session") or {}
        return [
            DomainEvent(
                DomainEventType.SESSION_UPDATED,
                {
                    "session_id": session.get("id"),
                    "model": session.get("model"),
                    "tools": session.get("tools") or [],
                    "interrupt_response": _interrupt_flag(session),
                },
            )
        ]
    if event_type == protocol.SERVER_SPEECH_STARTED:
        events = [DomainEvent(DomainEventType.SPEECH_STARTED, {"item_id": raw.get("item_id")})]
        if speaking:
            events.append(
                DomainEvent(
                    DomainEventType.INTERRUPTED,
                    {"reason": "turn_detected"},
                )
            )
        return events
    if event_type == protocol.SERVER_SPEECH_STOPPED:
        return [DomainEvent(DomainEventType.SPEECH_STOPPED, {"item_id": raw.get("item_id")})]
    if event_type in {protocol.SERVER_INPUT_TRANSCRIPT_DELTA, protocol.SERVER_INPUT_TRANSCRIPT_DONE}:
        text = raw.get("delta") or raw.get("transcript") or ""
        return [DomainEvent(DomainEventType.USER_TRANSCRIPT, {"text": text, "final": event_type.endswith("completed")})]
    if event_type == protocol.SERVER_RESPONSE_CREATED:
        response = raw.get("response") or {}
        return [DomainEvent(DomainEventType.RESPONSE_CREATED, {"response_id": response.get("id")})]
    if event_type == protocol.SERVER_AUDIO_DELTA:
        return [DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": raw.get("delta") or raw.get("audio") or ""})]
    if event_type == protocol.SERVER_AUDIO_DONE:
        return [DomainEvent(DomainEventType.AUDIO_DONE, {"item_id": raw.get("item_id")})]
    if event_type in {protocol.SERVER_AUDIO_TRANSCRIPT_DELTA, protocol.SERVER_AUDIO_TRANSCRIPT_DONE}:
        text = raw.get("delta") or raw.get("transcript") or ""
        return [
            DomainEvent(
                DomainEventType.ASSISTANT_TRANSCRIPT,
                {"text": text, "final": event_type.endswith("done")},
            )
        ]
    if event_type == protocol.SERVER_RESPONSE_DONE:
        response = raw.get("response") or {}
        status = response.get("status")
        details = response.get("status_details") or {}
        events = [
            DomainEvent(
                DomainEventType.RESPONSE_DONE,
                {"status": status, "reason": details.get("reason")},
            )
        ]
        if status == "cancelled" and details.get("reason") in {"turn_detected", "client_cancelled"}:
            events.insert(
                0,
                DomainEvent(DomainEventType.INTERRUPTED, {"reason": details.get("reason")}),
            )
        if status == "failed":
            error = details.get("error") or {}
            code = classify_error(error.get("code"), error.get("message"))
            events.append(
                DomainEvent(
                    DomainEventType.ERROR,
                    {"code": code, "message": sanitize_error_message(error.get("message"))},
                )
            )
        return events
    if event_type == protocol.SERVER_ERROR or "error" in raw and event_type in {None, "error"}:
        error = raw.get("error") or raw
        code = classify_error(error.get("code"), error.get("message"))
        return [
            DomainEvent(
                DomainEventType.ERROR,
                {"code": code, "message": sanitize_error_message(error.get("message"))},
            )
        ]
    if event_type == protocol.SERVER_INPUT_TRANSCRIPT_FAILED:
        error = raw.get("error") or {}
        return [
            DomainEvent(
                DomainEventType.ERROR,
                {
                    "code": classify_error(error.get("code"), error.get("message")),
                    "message": sanitize_error_message(error.get("message")),
                },
            )
        ]
    # conversation.item.created / output_item / content_part are ignored unless they
    # become a documented function-call item. Official ConversationItem.type is message only.
    item = raw.get("item") or {}
    if item.get("type") and item.get("type") != "message":
        return [
            DomainEvent(
                DomainEventType.TOOL_UNSUPPORTED,
                {"item_type": item.get("type"), "reason": "BLOCKED_BAIDU_FUNCTION_CALLING"},
            )
        ]
    return []


def _interrupt_flag(session: dict[str, Any]) -> bool:
    turn = session.get("turn_detection") or {}
    return bool(turn.get("interrupt_response", False))
