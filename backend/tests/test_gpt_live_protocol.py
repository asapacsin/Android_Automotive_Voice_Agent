import asyncio
import json

from app.config import load_settings, ConfigError
from app.voice.gpt_live import GPTLiveProvider, StaticOpenAITokenSupplier
from app.voice.gpt_live_protocol import (
    CLIENT_COMMENTARY_APPEND,
    CLIENT_INPUT_AUDIO_APPEND,
    CLIENT_SESSION_START,
    translate_gpt_live_event,
)
from app.voice.models import DomainEventType, ToolResult


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
            "VOICE_PROVIDER": "gpt_live",
            "OPENAI_API_KEY": "sk-injected-openai",
        }
    )


def test_gpt_live_session_start_audio_and_work_result():
    async def body():
        started = json.dumps({"type": "session.started", "session": {"id": "sess_live", "model": "gpt-live-1"}})
        fake = FakeWs([started])

        async def connect(url, additional_headers=None):
            fake.url = url
            fake.headers = additional_headers
            return fake

        provider = GPTLiveProvider(
            _settings(),
            ws_connect=connect,
            credential_supplier=StaticOpenAITokenSupplier("sk-injected-openai"),
        )
        await provider.connect("gpt-live-1")
        assert fake.url.endswith("/v1/live/sessions") or "live/sessions" in fake.url
        assert fake.headers["Authorization"] == "Bearer sk-injected-openai"
        sent = [json.loads(item) for item in fake.sent]
        assert sent[0]["type"] == CLIENT_SESSION_START
        assert sent[0]["session"]["model"] == "gpt-live-1"
        assert sent[0]["session"]["audio"]["format"]["rate"] == 16000
        assert sent[0]["session"]["delegation"]["type"] == "client"
        await provider.send_audio(b"\x00\x01")
        assert json.loads(fake.sent[1])["type"] == CLIENT_INPUT_AUDIO_APPEND
        await provider.send_tool_result(ToolResult("item_9tA2", True, "温度已设为22度。"))
        assert json.loads(fake.sent[2])["type"] == CLIENT_COMMENTARY_APPEND
        assert json.loads(fake.sent[2])["delegation_id"] == "item_9tA2"
        blob = "".join(fake.sent)
        assert "sk-injected-openai" not in blob
        await provider.close()

    asyncio.run(body())


def test_gpt_live_unknown_and_nested_function_call():
    assert translate_gpt_live_event({"type": "session.moderation.updated"}) == []
    nested = translate_gpt_live_event(
        {
            "type": "response.event",
            "delegation_id": "item_9tA2",
            "event": {
                "type": "response.output_item.done",
                "item": {
                    "type": "function_call",
                    "call_id": "call_123",
                    "name": "set_temperature",
                    "arguments": "{\"temperature_c\":\"22\"}",
                    "status": "completed",
                },
            },
        }
    )
    assert nested[0].type == DomainEventType.TOOL_CALL
    assert nested[0].payload["call_id"] == "call_123"
    audio = translate_gpt_live_event({"type": "session.output_audio.delta", "delta": "AA=="})
    assert audio[0].type == DomainEventType.AUDIO_DELTA


def test_gpt_live_invalid_model_rejected():
    async def body():
        provider = GPTLiveProvider(_settings(), ws_connect=lambda url, additional_headers=None: FakeWs([]))
        try:
            await provider.connect("gpt-realtime-2.1")
            raise AssertionError("expected invalid model")
        except ConfigError as raised:
            assert raised.code == "GPT_LIVE_INVALID_MODEL"

    asyncio.run(body())
