"""Official Baidu E2E realtime protocol names.

Source: https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei (updated 2026-09-04, retrieved 2026-09-14)

Returned Session examples include tool_choice and tools: []. UpdateSession and the
documented client events do not expose those fields, so custom-tool request/result
is not a documented protocol. Do not invent OpenAI/Gemini event names here.
"""

from __future__ import annotations

CLIENT_SESSION_UPDATE = "session.update"
CLIENT_INPUT_AUDIO_APPEND = "input_audio_buffer.append"

SERVER_SESSION_CREATED = "session.created"
SERVER_SESSION_UPDATED = "session.updated"
SERVER_CONVERSATION_CREATED = "conversation.created"
SERVER_CONVERSATION_ITEM_CREATED = "conversation.item.created"
SERVER_INPUT_TRANSCRIPT_DELTA = "conversation.item.input_audio_transcription.delta"
SERVER_INPUT_TRANSCRIPT_DONE = "conversation.item.input_audio_transcription.completed"
SERVER_INPUT_TRANSCRIPT_FAILED = "conversation.item.input_audio_transcription.failed"
SERVER_AUDIO_COMMITTED = "input_audio_buffer.committed"
SERVER_SPEECH_STARTED = "input_audio_buffer.speech_started"
SERVER_SPEECH_STOPPED = "input_audio_buffer.speech_stopped"
SERVER_RESPONSE_CREATED = "response.created"
SERVER_RESPONSE_DONE = "response.done"
SERVER_OUTPUT_ITEM_ADDED = "response.output_item.added"
SERVER_OUTPUT_ITEM_DONE = "response.output_item.done"
SERVER_CONTENT_PART_ADDED = "response.content_part.added"
SERVER_CONTENT_PART_DONE = "response.content_part.done"
SERVER_AUDIO_DELTA = "response.audio.delta"
SERVER_AUDIO_DONE = "response.audio.done"
SERVER_AUDIO_TRANSCRIPT_DELTA = "response.audio_transcript.delta"
SERVER_AUDIO_TRANSCRIPT_DONE = "response.audio_transcript.done"
SERVER_ERROR = "error"

DOCUMENTED_CLIENT_EVENTS = frozenset(
    {
        CLIENT_SESSION_UPDATE,
        CLIENT_INPUT_AUDIO_APPEND,
    }
)

PCM16 = "pcm16"
SERVER_VAD = "server_vad"
DEFAULT_TRANSCRIPTION_MODEL = "default"
INPUT_SAMPLE_RATE_HZ = 16000


def session_update_payload() -> dict:
    """Only documented UpdateSession fields.

    turn_detection.interrupt_response is documented as currently only true.
    """
    return {
        "type": CLIENT_SESSION_UPDATE,
        "session": {
            "input_audio_format": PCM16,
            "output_audio_format": PCM16,
            "input_audio_transcription": {"model": DEFAULT_TRANSCRIPTION_MODEL},
            "turn_detection": {
                "type": SERVER_VAD,
                "create_response": True,
                "interrupt_response": True,
            },
        },
    }


def input_audio_append_payload(audio_b64: str) -> dict:
    return {
        "type": CLIENT_INPUT_AUDIO_APPEND,
        "audio": audio_b64,
    }
