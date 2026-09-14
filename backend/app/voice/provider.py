from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator

from app.voice.models import DomainEvent, ToolResult


class RealtimeVoiceProvider(ABC):
    """Provider-neutral realtime speech-to-speech port.

    Implementations must translate vendor events into DomainEvent values.
    """

    @property
    @abstractmethod
    def provider_id(self) -> str:
        raise NotImplementedError

    @property
    def supports_custom_tools(self) -> bool:
        return False

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return False

    @property
    def capabilities(self) -> dict[str, object]:
        return {
            "custom_tools": self.supports_custom_tools,
            "server_vad_interrupt": self.supports_server_vad_interrupt,
            "client_response_cancel": False,
            "optional_commit": False,
            "work_result_injection": self.supports_custom_tools,
            "requires_credentials": True,
        }

    @abstractmethod
    async def connect(self, model: str) -> None:
        raise NotImplementedError

    @abstractmethod
    async def send_audio(self, pcm16le: bytes) -> None:
        raise NotImplementedError

    @abstractmethod
    def receive_events(self) -> AsyncIterator[DomainEvent]:
        raise NotImplementedError

    @abstractmethod
    async def interrupt(self) -> DomainEvent | None:
        raise NotImplementedError

    @abstractmethod
    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        raise NotImplementedError

    async def commit_audio(self) -> None:
        return None

    async def cancel_assistant_response(self) -> DomainEvent:
        return await self.interrupt()

    async def send_text(self, text: str) -> None:
        return None

    async def inject_work_result(self, result: ToolResult) -> DomainEvent:
        return await self.send_tool_result(result)

    @abstractmethod
    async def close(self) -> None:
        raise NotImplementedError
