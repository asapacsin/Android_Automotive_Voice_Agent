from __future__ import annotations

import time
from dataclasses import dataclass

import httpx

from app.config import Settings
from app.voice.models import ProviderError


@dataclass
class CachedToken:
    access_token: str
    expires_at_epoch: float


class BaiduAccessTokenClient:
    """OAuth client_credentials token cache.

    Official: POST https://aip.baidubce.com/oauth/2.0/token
    with grant_type=client_credentials, client_id=API Key, client_secret=Secret Key.
    https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii
    """

    def __init__(self, settings: Settings, http: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._http = http
        self._cached: CachedToken | None = None

    async def get_access_token(self, now: float | None = None) -> str:
        from app.config import require_credentials

        require_credentials(self._settings)
        clock = now if now is not None else time.time()
        if self._cached and clock < self._cached.expires_at_epoch:
            return self._cached.access_token
        token, expires_in = await self._fetch()
        skew = max(60, self._settings.token_refresh_skew_seconds)
        self._cached = CachedToken(token, clock + max(expires_in - skew, 60))
        return token

    async def _fetch(self) -> tuple[str, int]:
        params = {
            "grant_type": "client_credentials",
            "client_id": self._settings.api_key,
            "client_secret": self._settings.secret_key,
        }
        client = self._http or httpx.AsyncClient(timeout=15.0)
        owns_client = self._http is None
        try:
            response = await client.post(
                self._settings.token_url,
                params=params,
                headers={"Accept": "application/json", "Content-Type": "application/json"},
            )
        except httpx.HTTPError as exc:
            raise ProviderError("BAIDU_AUTH_FAILED", "token request failed") from exc
        finally:
            if owns_client:
                await client.aclose()
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderError("BAIDU_AUTH_FAILED", "token response was not JSON") from exc
        if "error" in payload or "access_token" not in payload:
            error = payload.get("error") or "invalid_client"
            raise ProviderError("BAIDU_AUTH_FAILED", f"oauth error: {error}")
        token = payload["access_token"]
        expires_in = int(payload.get("expires_in") or 2592000)
        return token, expires_in
