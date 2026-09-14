from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

from app.logging_safe import looks_like_placeholder
from app.voice.catalog import (
    ALL_MODELS,
    BAIDU_MODELS,
    DEFAULT_MODEL,
    DEFAULT_PROVIDER,
    GPT_LIVE_1,
    GPT_LIVE_WS_URL,
    QWEN_FLASH,
    QWEN_WS_URL,
    default_model_for,
    models_for,
    normalize_provider,
)

OFFICIAL_REALTIME_DOC = "https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei"
OFFICIAL_AUTH_DOC = "https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii"
OFFICIAL_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
OFFICIAL_WS_URL = "wss://aip.baidubce.com/ws/2.0/speech/v1/realtime"

ALLOWED_MODELS: dict[str, str] = dict(BAIDU_MODELS)
CATALOG_MODELS: dict[str, str] = dict(ALL_MODELS)

_BACKEND_DIR = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Settings:
    app_id: str
    api_key: str
    secret_key: str
    model: str
    ws_url: str
    token_url: str
    voice_provider: str
    token_refresh_skew_seconds: int
    qwen_api_key: str = ""
    qwen_ws_url: str = QWEN_WS_URL
    openai_api_key: str = ""
    gpt_live_ws_url: str = GPT_LIVE_WS_URL

    @property
    def app_id_configured(self) -> bool:
        return bool(self.app_id) and not looks_like_placeholder(self.app_id)

    @property
    def api_key_configured(self) -> bool:
        return bool(self.api_key) and not looks_like_placeholder(self.api_key)

    @property
    def secret_key_configured(self) -> bool:
        return bool(self.secret_key) and not looks_like_placeholder(self.secret_key)

    @property
    def qwen_configured(self) -> bool:
        return bool(self.qwen_api_key) and not looks_like_placeholder(self.qwen_api_key)

    @property
    def gpt_live_configured(self) -> bool:
        return bool(self.openai_api_key) and not looks_like_placeholder(self.openai_api_key)

    @property
    def credentials_ready(self) -> bool:
        if self.voice_provider in {"fake", "mock"}:
            return True
        if self.voice_provider == "qwen":
            return self.qwen_configured
        if self.voice_provider == "gpt_live":
            return self.gpt_live_configured
        return self.app_id_configured and self.api_key_configured and self.secret_key_configured

    @property
    def model_label(self) -> str:
        return CATALOG_MODELS.get(self.model, self.model)

    def secret_values(self) -> tuple[str, ...]:
        return tuple(
            v
            for v in (
                self.app_id,
                self.api_key,
                self.secret_key,
                self.qwen_api_key,
                self.openai_api_key,
            )
            if v
        )


class ConfigError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def _load_env_file() -> None:
    load_dotenv(_BACKEND_DIR / ".env", override=False)


def load_settings(environ: dict[str, str] | None = None) -> Settings:
    if environ is None:
        _load_env_file()
        source = os.environ
    else:
        source = environ
    provider = normalize_provider(source.get("VOICE_PROVIDER") or DEFAULT_PROVIDER)
    explicit_model = (source.get("VOICE_MODEL") or "").strip()
    if provider == "baidu":
        model = explicit_model or (source.get("BAIDU_E2E_MODEL") or default_model_for(provider)).strip()
    elif provider == "qwen":
        model = explicit_model or (source.get("QWEN_MODEL") or default_model_for(provider)).strip()
    elif provider == "gpt_live":
        model = explicit_model or (source.get("GPT_LIVE_MODEL") or GPT_LIVE_1).strip()
    else:
        model = explicit_model or default_model_for(provider)
    ws_url = (source.get("BAIDU_E2E_WS_URL") or OFFICIAL_WS_URL).strip()
    token_url = (source.get("BAIDU_TOKEN_URL") or OFFICIAL_TOKEN_URL).strip()
    skew = int(source.get("BAIDU_TOKEN_REFRESH_SKEW_SECONDS") or "86400")
    return Settings(
        app_id=(source.get("BAIDU_APP_ID") or "").strip(),
        api_key=(source.get("BAIDU_API_KEY") or "").strip(),
        secret_key=(source.get("BAIDU_SECRET_KEY") or "").strip(),
        model=model,
        ws_url=ws_url,
        token_url=token_url,
        voice_provider=provider,
        token_refresh_skew_seconds=skew,
        qwen_api_key=(source.get("DASHSCOPE_API_KEY") or "").strip(),
        qwen_ws_url=(source.get("QWEN_REALTIME_WS_URL") or QWEN_WS_URL).strip(),
        openai_api_key=(source.get("OPENAI_API_KEY") or "").strip(),
        gpt_live_ws_url=(source.get("GPT_LIVE_WS_URL") or GPT_LIVE_WS_URL).strip(),
    )


def require_credentials(settings: Settings) -> None:
    missing = []
    if looks_like_placeholder(settings.app_id):
        missing.append("BAIDU_APP_ID")
    if looks_like_placeholder(settings.api_key):
        missing.append("BAIDU_API_KEY")
    if looks_like_placeholder(settings.secret_key):
        missing.append("BAIDU_SECRET_KEY")
    if missing:
        raise ConfigError(
            "BAIDU_CREDENTIALS_MISSING",
            "Missing backend credentials: " + ", ".join(missing),
        )


def require_provider_credentials(settings: Settings) -> None:
    if settings.voice_provider in {"fake", "mock"}:
        return
    if settings.voice_provider == "qwen":
        if looks_like_placeholder(settings.qwen_api_key):
            raise ConfigError("QWEN_CREDENTIALS_MISSING", "Missing DASHSCOPE_API_KEY")
        return
    if settings.voice_provider == "gpt_live":
        if looks_like_placeholder(settings.openai_api_key):
            raise ConfigError("GPT_LIVE_CREDENTIALS_MISSING", "Missing OPENAI_API_KEY")
        return
    require_credentials(settings)


def validate_model(model: str) -> str:
    if model not in ALLOWED_MODELS:
        raise ConfigError("BAIDU_INVALID_MODEL", f"Unsupported model '{model}'")
    return model


def validate_catalog_model(model: str, provider: str | None = None) -> str:
    if provider:
        allowed = models_for(provider)
        if model not in allowed:
            code = {
                "qwen": "QWEN_INVALID_MODEL",
                "gpt_live": "GPT_LIVE_INVALID_MODEL",
                "baidu": "BAIDU_INVALID_MODEL",
                "fake": "FAKE_INVALID_MODEL",
                "mock": "FAKE_INVALID_MODEL",
            }.get(normalize_provider(provider), "UNKNOWN_MODEL")
            raise ConfigError(code, f"Unsupported model '{model}' for {provider}")
        return model
    if model not in CATALOG_MODELS:
        raise ConfigError("UNKNOWN_MODEL", f"Unsupported model '{model}'")
    return model
