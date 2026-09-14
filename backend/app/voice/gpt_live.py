from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any, Awaitable, Callable

from app.config import Settings, validate_catalog_model, ConfigError
from app.voice.catalog import GPT_LIVE_WS_URL, PROVIDER_CAPABILITIES
from app.voice.gpt_live_protocol import (
    commentary_append_payload,
    function_call_output_payload,
    input_audio_append_payload,
    response_create_payload,
    session_close_payload,
    session_start_payload,
    translate_gpt_live_event,
)
from app.voice.models import DomainEvent, DomainEventType, ProviderError, ToolResult
from app.voice.provider import RealtimeVoiceProvider

WsConnect = Callable[..., Awaitable[Any]]


class StaticOpenAITokenSupplier:
    def __init__(self, token: str) -> None:
        self.token = token

    async def bearer(self) -> str:
        if not self.token:
            raise ProviderError("GPT_LIVE_CREDENTIALS_MISSING", "Missing OPENAI_API_KEY")
        return self.token


class GPTLiveProvider(RealtimeVoiceProvider):
    def __init__(
        self,
        settings: Settings,
        ws_connect: WsConnect | None = None,
        credential_supplier: StaticOpenAITokenSupplier | None = None,
        transport: Any | None = None,
    ) -> None:
        self._settings = settings
        self._ws_connect = ws_connect
        self._transport = transport
        self._credentials = credential_supplier or StaticOpenAITokenSupplier(settings.openai_api_key)
        self._ws: Any | None = None
        self._events: asyncio.Queue[DomainEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task[None] | None = None
        self._started = False

    @property
    def provider_id(self) -> str:
        return "openai.gpt.live"

    @property
    def supports_custom_tools(self) -> bool:
        return True

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return True

    @property
    def capabilities(self) -> dict[str, object]:
        return dict(PROVIDER_CAPABILITIES["gpt_live"])

    async def connect(self, model: str) -> None:
        validate_catalog_model(model, "gpt_live")
        token = await self._credentials.bearer()
        url = self._settings.gpt_live_ws_url or GPT_LIVE_WS_URL
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
            raise ProviderError("GPT_LIVE_WS_FAILED", "websocket connection failed") from exc
        await self._send(session_start_payload(model))
        self._reader_task = asyncio.create_task(self._read_loop())

    async def send_audio(self, pcm16le: bytes) -> None:
        import base64

        if self._ws is None:
            raise ProviderError("GPT_LIVE_WS_FAILED", "not connected")
        await self._send(input_audio_append_payload(base64.b64encode(pcm16le).decode("ascii")))

    async def commit_audio(self) -> None:
        return None

    async def cancel_assistant_response(self) -> DomainEvent:
        # Official Live docs do not document a speech-cancel client event.
        event = DomainEvent(
            DomainEventType.INTERRUPTED,
            {"reason": "local_playback_flush", "client_cancel_documented": False},
        )
        await self._events.put(event)
        return event

    async def interrupt(self) -> DomainEvent:
        return await self.cancel_assistant_response()

    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        await self._send(commentary_append_payload(result.call_id, result.output))
        event = DomainEvent(DomainEventType.ASSISTANT_TRANSCRIPT, {"text": result.output, "final": False})
        await self._events.put(event)
        return event

    async def inject_work_result(self, result: ToolResult) -> DomainEvent:
        return await self.send_tool_result(result)

    async def send_responses_tool_result(self, result: ToolResult) -> None:
        await self._send(function_call_output_payload(result.call_id, result.output))
        await self._send(response_create_payload())

    async def receive_events(self) -> AsyncIterator[DomainEvent]:
        while True:
            item = await self._events.get()
            if item is None:
                break
            yield item

    async def close(self) -> None:
        if self._ws is not None:
            try:
                await self._send(session_close_payload())
            except Exception:
                pass
        if self._reader_task is not None:
            self._reader_task.cancel()
        closer = getattr(self._ws, "close", None) if self._ws is not None else None
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
            raise ProviderError("GPT_LIVE_WS_FAILED", "not connected")
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
        for event in translate_gpt_live_event(raw):
            await self._events.put(event)
