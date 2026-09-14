from __future__ import annotations

import asyncio
import base64
import json
from collections.abc import AsyncIterator
from typing import Any, Callable, Awaitable
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from app.config import Settings, require_credentials, validate_model
from app.voice.auth import BaiduAccessTokenClient
from app.voice.models import (
    BLOCKED_BAIDU_FUNCTION_CALLING,
    FUNCTION_CALLING_EVIDENCE,
    CapabilityUnsupported,
    DomainEvent,
    DomainEventType,
    ProviderError,
    ToolResult,
)
from app.voice.protocol import input_audio_append_payload, session_update_payload
from app.voice.provider import RealtimeVoiceProvider
from app.voice.translator import translate_baidu_event

WsConnect = Callable[..., Awaitable[Any]]


class BaiduRealtimeVoiceProvider(RealtimeVoiceProvider):
    def __init__(
        self,
        settings: Settings,
        token_client: BaiduAccessTokenClient | None = None,
        ws_connect: WsConnect | None = None,
    ) -> None:
        self._settings = settings
        self._token_client = token_client or BaiduAccessTokenClient(settings)
        self._ws_connect = ws_connect
        self._ws: Any | None = None
        self._speaking = False
        self._events: asyncio.Queue[DomainEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task[None] | None = None

    @property
    def provider_id(self) -> str:
        return "baidu.e2e.realtime"

    @property
    def supports_custom_tools(self) -> bool:
        return False

    @property
    def supports_server_vad_interrupt(self) -> bool:
        return True

    async def connect(self, model: str) -> None:
        require_credentials(self._settings)
        validate_model(model)
        token = await self._token_client.get_access_token()
        url = authorized_ws_url(self._settings.ws_url, model, token)
        connect = self._ws_connect
        if connect is None:
            import websockets

            connect = websockets.connect
        try:
            self._ws = await asyncio.wait_for(connect(url), timeout=10)
        except CapabilityUnsupported:
            raise
        except ProviderError:
            raise
        except Exception as exc:
            raise ProviderError("BAIDU_WS_FAILED", "websocket connection failed") from exc
        await self._ws.send(json.dumps(session_update_payload()))
        self._reader_task = asyncio.create_task(self._read_loop())

    async def send_audio(self, pcm16le: bytes) -> None:
        if self._ws is None:
            raise ProviderError("BAIDU_WS_FAILED", "not connected")
        audio_b64 = base64.b64encode(pcm16le).decode("ascii")
        await self._ws.send(json.dumps(input_audio_append_payload(audio_b64)))

    async def receive_events(self) -> AsyncIterator[DomainEvent]:
        while True:
            item = await self._events.get()
            if item is None:
                break
            yield item

    async def interrupt(self) -> DomainEvent | None:
        # Documented client events are session.update and input_audio_buffer.append only.
        # Keep the socket open and keep appending microphone audio. Server VAD with
        # interrupt_response=true emits input_audio_buffer.speech_started and
        # response.done cancelled/turn_detected; those server events drive INTERRUPTED.
        return None

    async def send_tool_result(self, result: ToolResult) -> DomainEvent:
        raise CapabilityUnsupported(BLOCKED_BAIDU_FUNCTION_CALLING, FUNCTION_CALLING_EVIDENCE)

    async def close(self) -> None:
        if self._reader_task is not None:
            self._reader_task.cancel()
        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:
                pass
        await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
        await self._events.put(None)

    async def _read_loop(self) -> None:
        assert self._ws is not None
        try:
            async for message in self._ws:
                if isinstance(message, bytes):
                    continue
                raw = json.loads(message)
                for event in translate_baidu_event(raw, speaking=self._speaking):
                    if event.type == DomainEventType.AUDIO_DELTA:
                        self._speaking = True
                    if event.type in {DomainEventType.INTERRUPTED, DomainEventType.RESPONSE_DONE}:
                        if event.type == DomainEventType.INTERRUPTED:
                            self._speaking = False
                        if event.payload.get("status") in {"completed", "cancelled", "failed"}:
                            self._speaking = False
                    await self._events.put(event)
        except asyncio.CancelledError:
            return
        except Exception:
            await self._events.put(
                DomainEvent(
                    DomainEventType.ERROR,
                    {"code": "SERVER_DISCONNECT", "message": "realtime socket closed"},
                )
            )
        finally:
            await self._events.put(DomainEvent(DomainEventType.CLOSED, {}))
            await self._events.put(None)


def authorized_ws_url(base: str, model: str, access_token: str) -> str:
    parts = urlsplit(base)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["model"] = model
    query["access_token"] = access_token
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def redact_ws_url(url: str) -> str:
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    if "access_token" in query:
        query["access_token"] = "***"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
