from pathlib import Path

from app.config import load_settings
from app.logging_safe import redact_text
from app.voice.routes import public_status

ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


def test_env_example_has_no_real_looking_secrets():
    text = ENV_EXAMPLE.read_text(encoding="utf-8")
    assert "YOUR_API_KEY" in text
    assert "24." not in text


def test_health_status_redacts_like_logs():
    settings = load_settings(
        environ={
            "BAIDU_API_KEY": "real-looking-key",
            "BAIDU_SECRET_KEY": "real-looking-secret",
            "BAIDU_APP_ID": "123456",
            "VOICE_PROVIDER": "mock",
        }
    )
    public = str(public_status(settings))
    assert "real-looking-key" not in public
    assert "real-looking-secret" not in redact_text("BAIDU_SECRET_KEY=real-looking-secret")
