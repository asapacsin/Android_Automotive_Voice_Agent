from __future__ import annotations

import asyncio
import base64
from collections.abc import AsyncIterator

from app.voice.models import DomainEvent, DomainEventType, ToolResult
from app.voice.provider import RealtimeVoiceProvider


class FakeRealtimeVoiceProvider(RealtimeVoiceProvider):
    """Quota-free deterministic provider for scenarios and benchmarks."""

    def __init__(self) -> None:
        self.sent_audio_bytes = 0
        self.closed = False
        self.connected_model: str | None = None
        self.commit_count = 0
        self.cancel_count = 0
        self.work_results: list[ToolResult] = []
        self._events: asyncio.Queue[DomainEvent | None] = asyncio.Queue()
        self.metrics: dict[str, object] = {}

    @property
    def provider_id(self) -> str:
        return "fake.realtime"

    @property
    def supports_custom_tools(self) -> bool:
        return True

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return True

    async def connect(self, model: str) -> None:
        self.connected_model = model
        await self._events.put(DomainEvent(DomainEventType.SESSION_CREATED, {"model": model, "tools": ["set_temperature"]}))
        await self._events.put(DomainEvent(DomainEventType.SESSION_UPDATED, {"model": model, "interrupt_response": True}))

    async def send_audio(self, pcm16le: bytes) -> None:
        self.sent_audio_bytes += len(pcm16le)

    async def commit_audio(self) -> None:
        self.commit_count += 1

    async def interrupt(self) -> DomainEvent:
        self.cancel_count += 1
        event = DomainEvent(DomainEventType.INTERRUPTED, {"reason": "client_cancelled"})
        await self._events.put(event)
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "cancelled", "reason": "client_cancelled"}))
        return event

    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        self.work_results.append(result)
        event = DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": result.output, "final": True})
        await self._events.put(event)
        return event

    async def receive_events(self) -> AsyncIterator[DomainEvent]:
        while True:
            item = await self._events.get()
            if item is None:
                break
            yield item

    async def close(self) -> None:
        self.closed = True
        await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
        await self._events.put(None)

    async def play_mandarin(self) -> None:
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STARTED, {}))
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STOPPED, {}))
        await self._events.put(DomainEvent(DomainEventType.USER_TRANSCRIPT, {"text": "打开空调到二十二度。", "final": True}))
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": base64.b64encode(b"\x00\x00").decode()}))
        await self._events.put(DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": "好的，正在设置温度。", "final": True}))
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DONE, {}))
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "completed"}))

    async def play_code_switch(self) -> None:
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STARTED, {}))
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STOPPED, {}))
        await self._events.put(
            DomainEvent(DomainEventType.USER_TRANSCRIPT, {"text": "Navigate to 人民广场 then play music.", "final": True})
        )
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": "AQID"}))
        await self._events.put(DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": "收到，正在导航去人民广场。", "final": True}))
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "completed"}))

    async def play_rapid_interrupt(self) -> None:
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": "AAAA"}))
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STARTED, {}))
        await self._events.put(DomainEvent(DomainEventType.INTERRUPTED, {"reason": "turn_detected"}))
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "cancelled", "reason": "turn_detected"}))
        await self._events.put(DomainEvent(DomainEventType.SPEECH_STOPPED, {}))
        await self._events.put(DomainEvent(DomainEventType.AUDIO_DELTA, {"audio": "AQID"}))
        await self._events.put(DomainEvent(DomainEventType.RESPONSE_DONE, {"status": "completed"}))

    async def play_work_refine(self) -> None:
        await self._events.put(
            DomainEvent(
                DomainEventType.TOOL_CALL,
                {"call_id": "work-1", "name": "set_temperature", "arguments": {"zone": "driver", "temperature_c": 22}},
            )
        )

    async def play_reconnect(self) -> None:
        await self._events.put(DomainEvent(DomainEventType.ERROR, {"code": "SERVER_DISCONNECT", "message": "socket closed"}))
        await self._events.put(DomainEvent(DomainEventType.SESSION_UPDATED, {"model": self.connected_model, "reconnected": True}))
