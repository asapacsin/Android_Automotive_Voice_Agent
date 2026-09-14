from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any, Awaitable, Callable

from app.config import Settings, validate_catalog_model, ConfigError
from app.voice.catalog import PROVIDER_CAPABILITIES, QWEN_WS_URL
from app.voice.models import DomainEvent, DomainEventType, ProviderError, ToolResult
from app.voice.provider import RealtimeVoiceProvider
from app.voice.qwen_protocol import (
    function_call_output_payload,
    input_audio_append_payload,
    input_audio_commit_payload,
    response_cancel_payload,
    response_create_payload,
    session_update_payload,
    translate_qwen_event,
)

WsConnect = Callable[..., Awaitable[Any]]


class StaticBearerSupplier:
    def __init__(self, token: str) -> None:
        self.token = token

    async def bearer(self) -> str:
        if not self.token:
            raise ProviderError("QWEN_CREDENTIALS_MISSING", "Missing DASHSCOPE_API_KEY")
        return self.token


class QwenRealtimeProvider(RealtimeVoiceProvider):
    def __init__(
        self,
        settings: Settings,
        ws_connect: WsConnect | None = None,
        credential_supplier: StaticBearerSupplier | None = None,
        transport: Any | None = None,
    ) -> None:
        self._settings = settings
        self._ws_connect = ws_connect
        self._transport = transport
        self._credentials = credential_supplier or StaticBearerSupplier(settings.qwen_api_key)
        self._ws: Any | None = None
        self._speaking = False
        self._events: asyncio.Queue[DomainEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task[None] | None = None

    @property
    def provider_id(self) -> str:
        return "qwen.audio.realtime"

    @property
    def supports_custom_tools(self) -> bool:
        return True

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return True

    @property
    def capabilities(self) -> dict[str, object]:
        return dict(PROVIDER_CAPABILITIES["qwen"])

    async def connect(self, model: str) -> None:
        validate_catalog_model(model, "qwen")
        token = await self._credentials.bearer()
        url = f"{self._settings.qwen_ws_url or QWEN_WS_URL}?model={model}"
        headers = {"Authorization": f"Bearer {token}"}
        try:
            if self._transport is not None:
                await self._transport.connect(url, headers=headers)
                self._ws = self._transport
            else:
                connect = self._ws_connect
                if connect is None:
                    import websockets

                    connect = websockets.connect
                try:
                    self._ws = await asyncio.wait_for(connect(url, additional_headers=headers), timeout=10)
                except TypeError:
                    self._ws = await asyncio.wait_for(connect(url), timeout=10)
        except ProviderError:
            raise
        except ConfigError:
            raise
        except Exception as exc:
            raise ProviderError("QWEN_WS_FAILED", "websocket connection failed") from exc
        await self._send(session_update_payload(include_tools=True))
        self._reader_task = asyncio.create_task(self._read_loop())

    async def send_audio(self, pcm16le: bytes) -> None:
        import base64

        if self._ws is None:
            raise ProviderError("QWEN_WS_FAILED", "not connected")
        await self._send(input_audio_append_payload(base64.b64encode(pcm16le).decode("ascii")))

    async def commit_audio(self) -> None:
        await self._send(input_audio_commit_payload())

    async def cancel_assistant_response(self) -> DomainEvent:
        await self._send(response_cancel_payload())
        event = DomainEvent(DomainEventType.INTERRUPTED, {"reason": "client_cancelled"})
        await self._events.put(event)
        return event

    async def interrupt(self) -> DomainEvent:
        return await self.cancel_assistant_response()

    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        await self._send(function_call_output_payload(result.call_id, result.output))
        await self._send(response_create_payload())
        event = DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": result.output, "final": False})
        await self._events.put(event)
        return event

    async def inject_work_result(self, result: ToolResult) -> DomainEvent:
        return await self.send_tool_result(result)

    async def receive_events(self) -> AsyncIterator[DomainEvent]:
        while True:
            item = await self._events.get()
            if item is None:
                break
            yield item

    async def close(self) -> None:
        if self._reader_task is not None:
            self._reader_task.cancel()
        if self._ws is not None:
            closer = getattr(self._ws, "close", None)
            if closer is not None:
                try:
                    await closer()
                except Exception:
                    pass
        await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
        await self._events.put(None)

    async def _send(self, payload: dict[str, Any]) -> None:
        message = json.dumps(payload)
        if self._ws is None:
            raise ProviderError("QWEN_WS_FAILED", "not connected")
        sender = getattr(self._ws, "send_text", None)
        if sender is not None:
            await sender(message)
            return
        await self._ws.send(message)

    async def _read_loop(self) -> None:
        try:
            if hasattr(self._ws, "receive_text"):
                while True:
                    message = await self._ws.receive_text()
                    await self._handle_message(message)
            else:
                async for message in self._ws:
                    await self._handle_message(message)
        except asyncio.CancelledError:
            return
        except Exception:
            await self._events.put(
                DomainEvent(DomainEventType.ERROR, {"code": "SERVER_DISCONNECT", "message": "realtime socket closed"})
            )
        finally:
            await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
            await self._events.put(None)

    async def _handle_message(self, message: Any) -> None:
        if isinstance(message, bytes):
            return
        raw = json.loads(message)
        for event in translate_qwen_event(raw, speaking=self._speaking):
            if event.type == DomainEventType.AUDIO_DELTA:
                self._speaking = True
            if event.type == DomainEventType.INTERRUPTED or (
                event.type == DomainEventType.RESPONSE_DONE and event.payload.get("status") in {"completed", "cancelled", "failed"}
            ):
                self._speaking = False
            await self._events.put(event)
