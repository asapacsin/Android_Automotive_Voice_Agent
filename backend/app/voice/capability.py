from __future__ import annotations

from app.voice.models import BLOCKED_BAIDU_FUNCTION_CALLING, FUNCTION_CALLING_EVIDENCE
from app.voice.protocol import CLIENT_INPUT_AUDIO_APPEND, CLIENT_SESSION_UPDATE, DOCUMENTED_CLIENT_EVENTS

OFFICIAL_DOC = "https://ai.baidu.com/ai-doc/SPEECH/nmcytnwei"
BLOCKED_CODE = BLOCKED_BAIDU_FUNCTION_CALLING

CONVERSATION_ITEM_ALLOWED_TYPES = frozenset({"message"})


def function_calling_capability() -> dict[str, object]:
    return {
        "status": BLOCKED_CODE,
        "supported": False,
        "conversation_item_allowed_types": sorted(CONVERSATION_ITEM_ALLOWED_TYPES),
        "documented_client_events": sorted(DOCUMENTED_CLIENT_EVENTS),
        "official_url": OFFICIAL_DOC,
        "reason": FUNCTION_CALLING_EVIDENCE,
        "client_events": [CLIENT_SESSION_UPDATE, CLIENT_INPUT_AUDIO_APPEND],
    }
