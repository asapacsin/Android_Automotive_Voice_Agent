"""Secret redaction for logs and outbound text."""
import os
import re

MASK = "[REDACTED]"
_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----", re.S),
    re.compile(r"AIza[0-9A-Za-z_\-]{30,}"),
    re.compile(r"sk-(?:or-v1-|ant-|proj-)?[A-Za-z0-9_\-]{20,}"),
    re.compile(r"nvapi-[A-Za-z0-9_\-]{20,}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}"),
]
_BEARER = re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-~+/=]{8,}")
_KV = re.compile(r"(?i)((?:api[_-]?key|x-goog-api-key|authorization|password|secret|token)\s*[\"']?\s*[:=]\s*[\"']?)"
                 r"[A-Za-z0-9._\-~+/]{12,}")


def redact(text, key_names=()):
    if not text:
        return text
    for name in key_names:
        v = os.environ.get(name)
        if v and len(v) >= 8:
            text = text.replace(v, MASK)
    for p in _PATTERNS:
        text = p.sub(MASK, text)
    text = _BEARER.sub(lambda m: m.group(1) + MASK, text)
    return _KV.sub(lambda m: m.group(1) + MASK, text)
