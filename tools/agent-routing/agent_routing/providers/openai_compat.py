"""OpenAI-compatible chat/completions adapter (OpenRouter, NVIDIA NIM); Bearer auth."""
from .base import Provider, ProviderError


class OpenAICompatProvider(Provider):
    def _key_headers(self, key):
        return {"Authorization": "Bearer " + key}

    def _build(self, system, user, max_tokens, json_mode):
        msgs = ([{"role": "system", "content": system}] if system else []) + [{"role": "user", "content": user}]
        body = {"model": self.model, "messages": msgs, "max_tokens": int(max_tokens), "temperature": 0}
        return self.cfg["base_url"].rstrip("/") + "/chat/completions", body

    def _parse(self, data):
        ch = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(ch, list) or not ch or not isinstance(ch[0], dict):
            raise ProviderError("BAD_RESPONSE", "no choices in chat/completions response")
        text = (ch[0].get("message") or {}).get("content") or ""
        u = data.get("usage") or {}
        return text, int(u.get("prompt_tokens") or 0), int(u.get("completion_tokens") or 0)
