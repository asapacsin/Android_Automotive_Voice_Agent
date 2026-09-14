from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from urllib.parse import urlencode

import httpx

from app.config import TOKEN_URL
from app.logging_safe import configure_logging

logger = configure_logging()


class AuthError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class CachedToken:
    access_token: str
    expires_at: datetime

    def valid(self, now: datetime, skew: timedelta = timedelta(minutes=5)) -> bool:
        return now + skew < self.expires_at


class BaiduAccessTokenClient:
    """OAuth2 client_credentials token cache.

    Official:
    POST https://aip.baidubce.com/oauth/2.0/token
      grant_type=client_credentials
      client_id=API Key
      client_secret=Secret Key
    https://cloud.baidu.com/doc/SPEECH/s/Em8snejw1
    """

    def __init__(self, api_key: str, secret_key: str, http: httpx.AsyncClient | None = None):
        self._api_key = api_key
        self._secret_key = secret_key
        self._http = http
        self._owns_http = http is None
        self._cached: CachedToken | None = None

    def configured(self) -> bool:
        return bool(self._api_key) and bool(self._secret_key)

    async def get_token(self, now: datetime | None = None) -> str:
        current = now or datetime.now(timezone.utc)
        if self._cached and self._cached.valid(current):
            return self._cached.access_token
        token, expires_in = await self._fetch()
        self._cached = CachedToken(
            access_token=token,
            expires_at=current + timedelta(seconds=max(expires_in - 1, 1)),
        )
        logger.info("BAIDU_API_KEY configured: yes")
        return token

    async def _fetch(self) -> tuple[str, int]:
        if not self.configured():
            raise AuthError("BAIDU_CREDENTIALS_MISSING", "backend/.env credentials are missing")
        query = urlencode(
            {
                "grant_type": "client_credentials",
                "client_id": self._api_key,
                "client_secret": self._secret_key,
            }
        )
        url = f"{TOKEN_URL}?{query}"
        client = self._http or httpx.AsyncClient(timeout=20.0)
        try:
            response = await client.post(
                url,
                headers={"Content-Type": "application/json", "Accept": "application/json"},
                content=b"",
            )
        except httpx.HTTPError as exc:
            raise AuthError("BAIDU_AUTH_FAILED", "token HTTP request failed") from exc
        finally:
            if self._owns_http:
                await client.aclose()
        try:
            payload: dict[str, Any] = response.json()
        except ValueError as exc:
            raise AuthError("BAIDU_AUTH_FAILED", "token response was not JSON") from exc
        if "error" in payload:
            raise AuthError("BAIDU_AUTH_FAILED", "authentication rejected")
        token = payload.get("access_token")
        expires_in = int(payload.get("expires_in") or 0)
        if not token or expires_in <= 0:
            raise AuthError("BAIDU_AUTH_FAILED", "token response missing access_token")
        return str(token), expires_in

    def websocket_url(self, base_ws_url: str, model: str, access_token: str) -> str:
        separator = "&" if "?" in base_ws_url else "?"
        if "model=" in base_ws_url:
            return f"{base_ws_url}{separator}access_token={access_token}"
        return f"{base_ws_url}?model={model}&access_token={access_token}"
