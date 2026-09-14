from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOTS = [
    ROOT / "app" / "src",
    ROOT / "ingress" / "src",
    ROOT / "contracts" / "src",
    ROOT / "orchestration" / "src",
]
FORBIDDEN_SUBSTRINGS = (
    "BAIDU_SECRET_KEY=",
    "BAIDU_API_KEY=",
    "DASHSCOPE_API_KEY=",
    "OPENAI_API_KEY=",
    "client_secret=",
)
PLACEHOLDERS = {"YOUR_APP_ID", "YOUR_API_KEY", "YOUR_SECRET_KEY"}


def test_android_and_core_sources_have_no_baidu_secrets() -> None:
    hits: list[str] = []
    for folder in ANDROID_ROOTS:
        if not folder.exists():
            continue
        for path in folder.rglob("*"):
            if path.suffix.lower() not in {".kt", ".xml", ".properties", ".kts", ".json"}:
                continue
            text = path.read_text(encoding="utf-8")
            for needle in FORBIDDEN_SUBSTRINGS:
                if needle in text:
                    hits.append(f"{path}: {needle}")
            if "wss://aip.baidubce.com" in text and "access_token=" in text:
                hits.append(f"{path}: access_token in client source")
    assert hits == []


def test_gitignore_ignores_backend_env() -> None:
    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert "backend/.env" in gitignore
    example = (ROOT / "backend" / ".env.example").read_text(encoding="utf-8")
    assert "YOUR_API_KEY" in example
    env_path = ROOT / "backend" / ".env"
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            if "=" not in line or line.strip().startswith("#"):
                continue
            key, _, value = line.partition("=")
            if key.strip() in {
                "BAIDU_API_KEY",
                "BAIDU_SECRET_KEY",
                "BAIDU_APP_ID",
                "DASHSCOPE_API_KEY",
                "OPENAI_API_KEY",
            }:
                assert value.strip().startswith("YOUR_") or value.strip() == ""


def test_live_flag_defaults_off() -> None:
    assert os.environ.get("RUN_BAIDU_LIVE_TESTS", "").lower() != "true"
