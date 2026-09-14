from __future__ import annotations

import asyncio
from collections import deque
from typing import Any


class InjectedTransport:
    """Test/demo WebSocket stand-in. Never opens a network socket."""

    def __init__(self, incoming: list[str] | None = None):
        self.incoming: deque[str] = deque(incoming or [])
        self.sent: list[str] = []
        self.connected_url: str | None = None
        self.headers: dict[str, str] | None = None
        self.closed = False
        self._waiters: deque[asyncio.Future[str]] = deque()

    async def connect(self, url: str, headers: dict[str, str] | None = None) -> None:
        self.connected_url = url
        self.headers = headers or {}
        self.closed = False

    async def send_text(self, message: str) -> None:
        if self.closed:
            raise ConnectionError("transport closed")
        self.sent.append(message)

    async def receive_text(self) -> str:
        if self.incoming:
            return self.incoming.popleft()
        loop = asyncio.get_running_loop()
        waiter: asyncio.Future[str] = loop.create_future()
        self._waiters.append(waiter)
        return await waiter

    def push_incoming(self, message: str) -> None:
        if self._waiters:
            waiter = self._waiters.popleft()
            if not waiter.done():
                waiter.set_result(message)
            return
        self.incoming.append(message)

    async def close(self) -> None:
        self.closed = True
        while self._waiters:
            waiter = self._waiters.popleft()
            if not waiter.done():
                waiter.set_exception(ConnectionError("transport closed"))


class InjectedSessionSupplier:
    def __init__(self, url: str = "wss://injected.local/ws/2.0/speech/v1/realtime?model=audio-mini-realtime-near"):
        self.url = url
        self.calls: list[str] = []

    async def websocket_url(self, model: str) -> tuple[str, dict[str, str]]:
        self.calls.append(model)
        if "model=" not in self.url:
            separator = "&" if "?" in self.url else "?"
            return f"{self.url}{separator}model={model}", {}
        return self.url, {}
