from pathlib import Path

from app.config import load_settings, require_credentials, require_provider_credentials, ConfigError, DEFAULT_MODEL
from app.voice.catalog import DEFAULT_PROVIDER
from app.voice.routes import public_status

ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


def test_example_env_lists_required_keys():
    text = ENV_EXAMPLE.read_text(encoding="utf-8")
    for key in (
        "BAIDU_APP_ID",
        "BAIDU_API_KEY",
        "BAIDU_SECRET_KEY",
        "BAIDU_E2E_MODEL",
        "BAIDU_E2E_WS_URL",
        "VOICE_PROVIDER",
        "DASHSCOPE_API_KEY",
        "OPENAI_API_KEY",
        "QWEN_MODEL",
    ):
        assert key in text
    assert "audio-mini-realtime-near" in text
    assert "qwen-audio-3.0-realtime-flash" in text
    assignments = [
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    assert "VOICE_PROVIDER=qwen" in assignments
    assert "VOICE_PROVIDER=baidu" not in assignments


def test_qwen_flash_is_default_optional_providers_remain_selectable():
    settings = load_settings(environ={})
    assert settings.voice_provider == DEFAULT_PROVIDER == "qwen"
    assert settings.model == DEFAULT_MODEL == "qwen-audio-3.0-realtime-flash"
    qwen = load_settings(environ={"VOICE_PROVIDER": "qwen", "DASHSCOPE_API_KEY": "sk-local-qwen"})
    assert qwen.voice_provider == "qwen"
    assert qwen.model == "qwen-audio-3.0-realtime-flash"
    assert qwen.credentials_ready
    gpt = load_settings(environ={"VOICE_PROVIDER": "gpt_live", "OPENAI_API_KEY": "sk-local-gpt"})
    assert gpt.voice_provider == "gpt_live"
    assert gpt.model == "gpt-live-1"
    baidu = load_settings(environ={"VOICE_PROVIDER": "baidu"})
    assert baidu.voice_provider == "baidu"
    assert baidu.model == "audio-mini-realtime-near"
    assert baidu.ws_url.startswith("wss://aip.baidubce.com/ws/2.0/speech/v1/realtime")


def test_placeholders_are_not_credentials():
    settings = load_settings(
        environ={
            "VOICE_PROVIDER": "baidu",
            "BAIDU_APP_ID": "YOUR_APP_ID",
            "BAIDU_API_KEY": "YOUR_API_KEY",
            "BAIDU_SECRET_KEY": "YOUR_SECRET_KEY",
        }
    )
    assert settings.voice_provider == "baidu"
    assert not settings.credentials_ready
    try:
        require_credentials(settings)
        raise AssertionError("placeholders must fail")
    except ConfigError as exc:
        assert exc.code == "BAIDU_CREDENTIALS_MISSING"
    try:
        require_provider_credentials(settings)
        raise AssertionError("default placeholders must fail")
    except ConfigError as exc:
        assert exc.code == "BAIDU_CREDENTIALS_MISSING"


def test_empty_environ_default_start_is_qwen_credentials_missing():
    settings = load_settings(environ={})
    assert settings.voice_provider == "qwen"
    assert settings.model == "qwen-audio-3.0-realtime-flash"
    try:
        require_provider_credentials(settings)
        raise AssertionError("missing default credentials must fail")
    except ConfigError as exc:
        assert exc.code == "QWEN_CREDENTIALS_MISSING"


def test_public_status_does_not_include_secrets():
    settings = load_settings(
        environ={
            "BAIDU_APP_ID": "secret-app",
            "BAIDU_API_KEY": "secret-key",
            "BAIDU_SECRET_KEY": "secret-secret",
            "DASHSCOPE_API_KEY": "sk-qwen-secret",
            "OPENAI_API_KEY": "sk-openai-secret",
            "BAIDU_E2E_MODEL": "audio-mini-realtime-near",
            "VOICE_PROVIDER": "mock",
        }
    )
    blob = str(public_status(settings))
    assert "secret-key" not in blob
    assert "secret-secret" not in blob
    assert "secret-app" not in blob
    assert "sk-qwen-secret" not in blob
    assert "sk-openai-secret" not in blob
    status = public_status(settings)
    assert status["default_model"] == "qwen-audio-3.0-realtime-flash"
    assert status["default_provider"] == "qwen"


def test_fake_and_qwen_start_without_gpt_live_key():
    qwen = load_settings(environ={"VOICE_PROVIDER": "qwen", "DASHSCOPE_API_KEY": "sk-local-qwen"})
    assert qwen.credentials_ready
    fake = load_settings(environ={"VOICE_PROVIDER": "fake"})
    assert fake.credentials_ready
    assert not qwen.gpt_live_configured
