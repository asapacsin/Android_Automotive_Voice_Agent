from __future__ import annotations

from app.logging_safe import configure_logging

logger = configure_logging()


class LiveWebSocketTransport:
    """Real WebSocket client used only when VOICE_PROVIDER=baidu and credentials exist.

    Tests and default demos must inject InjectedTransport instead of this class.
    """

    def __init__(self):
        self._ws = None

    async def connect(self, url: str, headers: dict[str, str] | None = None) -> None:
        import websockets

        logger.info("opening provider websocket (url redacted)")
        self._ws = await websockets.connect(url, additional_headers=headers or {}, max_size=8_000_000)

    async def send_text(self, message: str) -> None:
        if self._ws is None:
            raise ConnectionError("not connected")
        await self._ws.send(message)

    async def receive_text(self) -> str:
        if self._ws is None:
            raise ConnectionError("not connected")
        data = await self._ws.recv()
        if isinstance(data, bytes):
            return data.decode("utf-8")
        return str(data)

    async def close(self) -> None:
        if self._ws is not None:
            await self._ws.close()
            self._ws = None
