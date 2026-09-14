import asyncio
import json

from app.config import ConfigError, load_settings
from app.voice.baidu_realtime import BaiduRealtimeVoiceProvider
from app.voice.models import BLOCKED_BAIDU_FUNCTION_CALLING, CapabilityUnsupported, DomainEventType, ToolResult
from app.voice.protocol import CLIENT_INPUT_AUDIO_APPEND, CLIENT_SESSION_UPDATE


class FakeWs:
    def __init__(self, incoming: list[str]):
        self.incoming = list(incoming)
        self.sent: list[str] = []
        self.url = None

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


def _baidu_settings():
    return load_settings(
        environ={
            "BAIDU_APP_ID": "app-1",
            "BAIDU_API_KEY": "ak",
            "BAIDU_SECRET_KEY": "sk",
            "VOICE_PROVIDER": "baidu",
        }
    )


def test_baidu_provider_uses_injected_websocket_and_official_client_events():
    async def body():
        created = json.dumps(
            {
                "type": "session.created",
                "session": {"id": "sess_test", "object": "realtime.session", "tools": []},
            }
        )
        fake = FakeWs([created])

        async def connect(url):
            fake.url = url
            return fake

        class Token:
            async def get_access_token(self, now=None):
                return "injected-token"

        provider = BaiduRealtimeVoiceProvider(_baidu_settings(), token_client=Token(), ws_connect=connect)
        await provider.connect("audio-mini-realtime-near")
        assert "access_token=injected-token" in fake.url
        assert "model=audio-mini-realtime-near" in fake.url
        sent = [json.loads(item) for item in fake.sent]
        assert sent[0]["type"] == CLIENT_SESSION_UPDATE
        assert "tools" not in sent[0]["session"]
        await provider.send_audio(b"\x00\x01")
        assert json.loads(fake.sent[1])["type"] == CLIENT_INPUT_AUDIO_APPEND
        event = None
        async for item in provider.receive_events():
            event = item
            break
        assert event.type == DomainEventType.SESSION_CREATED
        try:
            await provider.send_tool_result(ToolResult("call-1", True, "{}"))
            raise AssertionError("expected CapabilityUnsupported")
        except CapabilityUnsupported as raised:
            assert raised.code == BLOCKED_BAIDU_FUNCTION_CALLING
        sent_before_interrupt = list(fake.sent)
        interrupt_event = await provider.interrupt()
        assert interrupt_event is None
        assert fake.sent == sent_before_interrupt
        await provider.send_audio(b"\x02\x03")
        assert json.loads(fake.sent[-1])["type"] == CLIENT_INPUT_AUDIO_APPEND
        await provider.close()

    asyncio.run(body())


def test_invalid_model_is_rejected_locally():
    async def body():
        provider = BaiduRealtimeVoiceProvider(_baidu_settings(), ws_connect=lambda url: FakeWs([]))
        try:
            await provider.connect("not-a-documented-model")
            raise AssertionError("expected invalid model")
        except ConfigError as raised:
            assert raised.code == "BAIDU_INVALID_MODEL"

    asyncio.run(body())
