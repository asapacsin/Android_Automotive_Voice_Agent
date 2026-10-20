"""Official Gemini REST generateContent adapter (x-goog-api-key header)."""
from .base import Provider, ProviderError


class GeminiProvider(Provider):
    def _key_headers(self, key):
        return {"x-goog-api-key": key}

    def _build(self, system, user, max_tokens, json_mode):
        gen = {"maxOutputTokens": int(max_tokens)}
        if json_mode:
            gen["responseMimeType"] = "application/json"
        body = {"contents": [{"role": "user", "parts": [{"text": user}]}], "generationConfig": gen}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        return "%s/models/%s:generateContent" % (self.cfg["base_url"].rstrip("/"), self.model), body

    def _parse(self, data):
        cands = data.get("candidates") if isinstance(data, dict) else None
        if not isinstance(cands, list) or not cands:
            raise ProviderError("BAD_RESPONSE", "no candidates in Gemini response")
        parts = ((cands[0] or {}).get("content") or {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts if isinstance(p, dict) and not p.get("thought"))
        um = data.get("usageMetadata") or {}
        return text, int(um.get("promptTokenCount") or 0), int(um.get("candidatesTokenCount") or 0) + int(
            um.get("thoughtsTokenCount") or 0)
