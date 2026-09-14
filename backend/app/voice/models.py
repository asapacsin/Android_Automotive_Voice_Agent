from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class VoiceUiState(str, Enum):
    DISCONNECTED = "Disconnected"
    CONNECTING = "Connecting"
    LISTENING = "Listening"
    THINKING = "Thinking"
    SPEAKING = "Speaking"
    ERROR = "Error"


class DomainEventType(str, Enum):
    SESSION_CREATED = "session_created"
    SESSION_UPDATED = "session_updated"
    SPEECH_STARTED = "speech_started"
    SPEECH_STOPPED = "speech_stopped"
    USER_TRANSCRIPT = "user_transcript"
    ASSISTANT_TRANSCRIPT = "assistant_transcript"
    RESPONSE_CREATED = "response_created"
    AUDIO_DELTA = "audio_delta"
    AUDIO_DONE = "audio_done"
    RESPONSE_DONE = "response_done"
    INTERRUPTED = "interrupted"
    ERROR = "error"
    CLOSED = "closed"
    TOOL_CALL = "tool_call"
    TOOL_UNSUPPORTED = "tool_unsupported"


@dataclass(frozen=True)
class DomainEvent:
    type: DomainEventType
    payload: dict[str, Any] = field(default_factory=dict)

    def to_client_json(self) -> dict[str, Any]:
        body: dict[str, Any] = {"type": self.type.value}
        body.update(self.payload)
        return body


@dataclass(frozen=True)
class ToolCall:
    call_id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    ok: bool
    output: str


class ProviderError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class CapabilityUnsupported(ProviderError):
    pass


BLOCKED_BAIDU_FUNCTION_CALLING = "BLOCKED_BAIDU_FUNCTION_CALLING"

FUNCTION_CALLING_EVIDENCE = (
    "BLOCKED_BAIDU_FUNCTION_CALLING. Official E2E realtime API (updated 2026-09-04, "
    "retrieved 2026-09-14) documents only two client events "
    "(session.update, input_audio_buffer.append). Returned Session examples include "
    "tool_choice and tools: [], but UpdateSession and session.update do not expose those "
    "fields, so they cannot configure tools or return tool results. ConversationItem.type "
    "allows only 'message'. No function-call request/result client or server events are specified. "
    "https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei"
)
