import asyncio
import json

from app.config import load_settings, ConfigError
from app.voice.models import DomainEventType, ToolResult
from app.voice.qwen_protocol import (
    CLIENT_INPUT_AUDIO_APPEND,
    CLIENT_ITEM_CREATE,
    CLIENT_RESPONSE_CANCEL,
    CLIENT_RESPONSE_CREATE,
    CLIENT_SESSION_UPDATE,
    translate_qwen_event,
)
from app.voice.qwen_realtime import QwenRealtimeProvider, StaticBearerSupplier


class FakeWs:
    def __init__(self, incoming: list[str]):
        self.incoming = list(incoming)
        self.sent: list[str] = []
        self.url = None
        self.headers = None

    async def send(self, message: str) -> None:
        self.sent.append(message)

    def __aiter__(self):
        self._iter = iter(self.incoming)
        return self

    async def __anext__(self) -> str:
        try:
            return next(self._iter)
        except StopIteration as exc:
            raise StopAsyncIteration from exc

    async def close(self) -> None:
        return None


def _settings():
    return load_settings(
        environ={
            "VOICE_PROVIDER": "qwen",
            "DASHSCOPE_API_KEY": "sk-injected-qwen",
            "QWEN_MODEL": "qwen-audio-3.0-realtime-flash",
        }
    )


def test_qwen_session_update_audio_cancel_and_tool_result():
    async def body():
        created = json.dumps({"type": "session.created", "session": {"id": "sess_q", "model": "qwen-audio-3.0-realtime-flash"}})
        fake = FakeWs([created])

        async def connect(url, additional_headers=None):
            fake.url = url
            fake.headers = additional_headers
            return fake

        provider = QwenRealtimeProvider(
            _settings(),
            ws_connect=connect,
            credential_supplier=StaticBearerSupplier("sk-injected-qwen"),
        )
        await provider.connect("qwen-audio-3.0-realtime-flash")
        assert "model=qwen-audio-3.0-realtime-flash" in fake.url
        assert fake.headers["Authorization"] == "Bearer sk-injected-qwen"
        sent = [json.loads(item) for item in fake.sent]
        assert sent[0]["type"] == CLIENT_SESSION_UPDATE
        assert sent[0]["session"]["input_audio_format"] == "pcm"
        assert sent[0]["session"]["tools"][0]["function"]["name"] == "set_temperature"
        await provider.send_audio(b"\x00\x01")
        assert json.loads(fake.sent[1])["type"] == CLIENT_INPUT_AUDIO_APPEND
        await provider.cancel_assistant_response()
        assert json.loads(fake.sent[2])["type"] == CLIENT_RESPONSE_CANCEL
        await provider.send_tool_result(ToolResult("call_xxx", True, "{\"ok\":true}"))
        assert json.loads(fake.sent[3])["type"] == CLIENT_ITEM_CREATE
        assert json.loads(fake.sent[3])["item"]["type"] == "function_call_output"
        assert json.loads(fake.sent[4])["type"] == CLIENT_RESPONSE_CREATE
        blob = "".join(fake.sent)
        assert "sk-injected-qwen" not in blob
        await provider.close()

    asyncio.run(body())


def test_qwen_parses_official_function_call_and_unknown_events():
    call = translate_qwen_event(
        {
            "event_id": "event_xxx",
            "type": "response.function_call_arguments.done",
            "call_id": "call_xxx",
            "name": "set_temperature",
            "arguments": "{\"temperature_c\":\"22\",\"zone\":\"driver\"}",
        }
    )
    assert call[0].type == DomainEventType.TOOL_CALL
    assert call[0].payload["call_id"] == "call_xxx"
    assert call[0].payload["arguments"]["temperature_c"] == "22"
    cancelled = translate_qwen_event(
        {"type": "response.done", "response": {"status": "cancelled", "status_details": {"reason": "client_cancelled"}}}
    )
    assert cancelled[0].type == DomainEventType.INTERRUPTED
    assert translate_qwen_event({"type": "voiceprint_audio_list.completed", "item_id": "x"}) == []


def test_qwen_invalid_model_rejected():
    async def body():
        provider = QwenRealtimeProvider(_settings(), ws_connect=lambda url, additional_headers=None: FakeWs([]))
        try:
            await provider.connect("not-a-qwen-model")
            raise AssertionError("expected invalid model")
        except ConfigError as raised:
            assert raised.code == "QWEN_INVALID_MODEL"

    asyncio.run(body())
