from __future__ import annotations

import asyncio
import base64
from collections.abc import AsyncIterator

from app.config import validate_model
from app.voice.models import DomainEvent, DomainEventType, ToolResult
from app.voice.provider import RealtimeVoiceProvider


class MockRealtimeVoiceProvider(RealtimeVoiceProvider):
    """Local quota-free provider. Never contacts Baidu."""

    def __init__(self, auto_reply: bool = True, emit_tool_call: bool = False) -> None:
        self.auto_reply = auto_reply
        self.emit_tool_call = emit_tool_call
        self.sent_audio_bytes = 0
        self.closed = False
        self.connected_model: str | None = None
        self._replied = False
        self._speaking = False
        self._events: asyncio.Queue[DomainEvent | None] = asyncio.Queue()

    @property
    def provider_id(self) -> str:
        return "mock.realtime"

    @property
    def supports_custom_tools(self) -> bool:
        return True

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return True

    async def connect(self, model: str) -> None:
        validate_model(model)
        self.connected_model = model
        await self._events.put(
            DomainEvent(DomainEventType.SESSION_CREATED, {"model": model, "tools": []})
        )
        await self._events.put(
            DomainEvent(
                DomainEventType.SESSION_UPDATED,
                {"model": model, "interrupt_response": True},
            )
        )

    async def send_audio(self, pcm16le: bytes) -> None:
        self.sent_audio_bytes += len(pcm16le)
        if self.auto_reply and not self._replied and self.sent_audio_bytes >= 1600:
            self._replied = True
            await self._emit_reply()

    async def inject(self, event: DomainEvent) -> None:
        await self._events.put(event)

    async def receive_events(self) -> AsyncIterator[DomainEvent]:
        while True:
            item = await self._events.get()
            if item is None:
                break
            yield item

    async def interrupt(self) -> DomainEvent:
        self._speaking = False
        event = DomainEvent(DomainEventType.INTERRUPTED, {"reason": "turn_detected"})
        await self._events.put(event)
        await self._events.put(
            DomainEvent(
                DomainEventType.RESPONSE_DONE,
                {"status": "cancelled", "reason": "turn_detected"},
            )
        )
        return event

    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        event = DomainEvent(
            DomainEventType.ASSISTANT_TRANSCRIPT,
            {"text": result.output, "final": True},
        )
        await self._events.put(event)
        return event

    async def close(self) -> None:
        self.closed = True
        await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
        await self._events.put(None)

    async def _emit_reply(self) -> None:
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STARTED, {}))
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STOPPED, {}))
        await self._events.put(
            DomainEvent(
                DomainEventType.USER_TRANSCRIPT,
                {"text": "你好，我正在测试车载语音助手，请简短回复我。", "final": True},
            )
        )
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_CREATED, {"response_id": "mock-1"}))
        silence = base64.b64encode(b"\x00\x00" * 160).decode("ascii")
        self._speaking = True
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": silence}))
        await self._events.put(
            DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": "收到，测试成功。", "final": True})
        )
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DONE, {}))
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "completed"}))
        self._speaking = False
        if self.emit_tool_call:
            await self._events.put(
                DomainEvent(
                    DomainEventType.TOOL_CALL,
                    {
                        "call_id": "call-mock-temp",
                        "name": "set_temperature",
                        "arguments": {"zone": "driver", "temperature_c": 22},
                    },
                )
            )
