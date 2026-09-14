import asyncio
import io
import logging

from app.config import ConfigError, load_settings
from app.logging_safe import configured_flag, install_redacting_logging, redact_text
from app.voice.auth import BaiduAccessTokenClient
from app.voice.models import ProviderError


def test_redact_strips_secret_assignments_and_query_tokens():
    raw = "BAIDU_API_KEY=abcd SECRET_KEY=zzzz access_token=24.abc client_secret=nope"
    text = redact_text(raw)
    assert "abcd" not in text
    assert "zzzz" not in text
    assert "24.abc" not in text
    assert "nope" not in text
    assert "***" in text


def test_logger_does_not_emit_token_url_secrets():
    logger = install_redacting_logging()
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    logger.addHandler(handler)
    logger.info("token url access_token=24.super-secret&client_secret=hunter2")
    logger.removeHandler(handler)
    output = stream.getvalue()
    assert "super-secret" not in output
    assert "hunter2" not in output


def test_missing_credentials_do_not_call_network():
    async def body():
        settings = load_settings(
            environ={
                "BAIDU_APP_ID": "YOUR_APP_ID",
                "BAIDU_API_KEY": "YOUR_API_KEY",
                "BAIDU_SECRET_KEY": "YOUR_SECRET_KEY",
            }
        )
        client = BaiduAccessTokenClient(settings, http=_FakeHttp({"access_token": "nope", "expires_in": 10}))
        try:
            await client.get_access_token()
        except ConfigError as raised:
            assert raised.code == "BAIDU_CREDENTIALS_MISSING"
            assert not client._http.calls
            return
        raise AssertionError("expected ConfigError")

    asyncio.run(body())


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def json(self):
        return self._payload


class _FakeHttp:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    async def post(self, url, params=None, headers=None, content=None):
        self.calls.append({"url": url, "params": params})
        return _FakeResponse(self.payload)

    async def aclose(self):
        return None


def test_token_client_caches_and_maps_auth_failure():
    async def body():
        settings = load_settings(
            environ={
                "BAIDU_APP_ID": "app-1",
                "BAIDU_API_KEY": "ak",
                "BAIDU_SECRET_KEY": "sk",
            }
        )
        http = _FakeHttp({"access_token": "tok-1", "expires_in": 2592000})
        client = BaiduAccessTokenClient(settings, http=http)
        first = await client.get_access_token()
        second = await client.get_access_token()
        assert first == second == "tok-1"
        assert len(http.calls) == 1
        assert http.calls[0]["params"]["grant_type"] == "client_credentials"
        assert http.calls[0]["params"]["client_id"] == "ak"
        assert http.calls[0]["params"]["client_secret"] == "sk"
        assert configured_flag("BAIDU_API_KEY", True) == "BAIDU_API_KEY configured: yes"

        failing = BaiduAccessTokenClient(
            settings,
            http=_FakeHttp({"error": "invalid_client", "error_description": "unknown client id"}),
        )
        try:
            await failing.get_access_token()
        except ProviderError as raised:
            assert raised.code == "BAIDU_AUTH_FAILED"
            assert "unknown client id" not in str(raised)
            return
        raise AssertionError("expected ProviderError")

    asyncio.run(body())
