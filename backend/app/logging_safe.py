from __future__ import annotations

import logging
import re
from typing import Iterable

_SECRET_KEYS = (
    "BAIDU_API_KEY",
    "BAIDU_SECRET_KEY",
    "BAIDU_APP_ID",
    "DASHSCOPE_API_KEY",
    "OPENAI_API_KEY",
    "access_token",
    "client_secret",
    "client_id",
    "SECRET_KEY",
    "API_KEY",
)

_ASSIGNMENT = re.compile(
    r"(?i)\b(BAIDU_API_KEY|BAIDU_SECRET_KEY|BAIDU_APP_ID|DASHSCOPE_API_KEY|OPENAI_API_KEY|"
    r"access_token|client_secret|client_id|SECRET_KEY|API_KEY)\b\s*[=:]\s*([^\s,;]+)"
)
_QUERY_SECRET = re.compile(r"(?i)(access_token|client_secret|client_id)=([^&\\s]+)")


def configured_flag(name: str, present: bool) -> str:
    return f"{name} configured: {'yes' if present else 'no'}"


def redact_text(text: str, extra_values: Iterable[str] = ()) -> str:
    redacted = _ASSIGNMENT.sub(lambda m: f"{m.group(1)}=***", text)
    redacted = _QUERY_SECRET.sub(lambda m: f"{m.group(1)}=***", redacted)
    for value in extra_values:
        if value and len(value) >= 4 and value not in ("yes", "no", "true", "false"):
            redacted = redacted.replace(value, "***")
    return redacted


class RedactingFilter(logging.Filter):
    def __init__(self, extra_values: Iterable[str] = ()) -> None:
        super().__init__()
        self._extra = [v for v in extra_values if v]

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = redact_text(str(record.msg), self._extra)
        if record.args:
            record.args = tuple(
                redact_text(str(arg), self._extra) if isinstance(arg, str) else arg
                for arg in record.args
            )
        return True


def install_redacting_logging(extra_values: Iterable[str] = ()) -> logging.Logger:
    logger = logging.getLogger("novadrive")
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)s %(name)s %(message)s"))
        logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.addFilter(RedactingFilter(extra_values))
    return logger


def configure_logging(extra_values: Iterable[str] = ()) -> logging.Logger:
    return install_redacting_logging(extra_values)


def looks_like_placeholder(value: str | None) -> bool:
    if not value:
        return True
    stripped = value.strip()
    if not stripped:
        return True
    upper = stripped.upper()
    return upper.startswith("YOUR_") or upper in {"CHANGE_ME", "TODO", "REPLACE_ME"}
