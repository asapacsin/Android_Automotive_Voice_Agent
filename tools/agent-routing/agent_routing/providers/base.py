"""Common provider base: timeout, bounded retries (exp backoff + jitter, Retry-After), usage + cost."""
import json
import os
import random
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

from ..budget import BudgetExceeded
from ..redact import redact

LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


class ProviderError(Exception):
    def __init__(self, code, message, status=None):
        super().__init__("%s: %s" % (code, message))
        self.code = code
        self.status = status


@dataclass
class CallResult:
    text: str
    provider: str
    model: str
    input_tokens: int
    output_tokens: int
    cost_usd: object
    attempts: int


def _ssl_context():
    for var in ("SSL_CERT_FILE", "REQUESTS_CA_BUNDLE"):
        f = os.environ.get(var)
        if f and os.path.isfile(f):
            return ssl.create_default_context(cafile=f)
    return ssl.create_default_context()


class Provider:
    def __init__(self, pcfg, http_cfg, ledger=None, key_names=(), sleep=None):
        self.cfg = pcfg
        self.id = pcfg["id"]
        self.model = pcfg["model"]
        self.http = http_cfg
        self.ledger = ledger
        self.key_names = tuple(key_names) or (pcfg["key_env"],)
        self.sleep = sleep or time.sleep

    # subclass hooks -------------------------------------------------------------------------------
    def _key_headers(self, key):
        raise NotImplementedError

    def _build(self, system, user, max_tokens, json_mode):
        raise NotImplementedError

    def _parse(self, data):
        raise NotImplementedError

    # ----------------------------------------------------------------------------------------------
    def auth_headers(self):
        key = os.environ.get(self.cfg["key_env"])
        if key:
            return self._key_headers(key)
        if self.cfg.get("auth") == "proxy-injected":
            return {}
        raise ProviderError("PROVIDER_UNCONFIGURED", "%s is not set and auth is not proxy-injected" % self.cfg["key_env"])

    def chat(self, system, user, max_tokens=4096, json_mode=False, retries=None):
        headers = self.auth_headers()
        if self.ledger:
            try:
                self.ledger.check(self.id, (len(system) + len(user)) // 4 + max_tokens)
            except BudgetExceeded as e:
                raise ProviderError("BUDGET_EXCEEDED", str(e))
        url, body = self._build(system, user, max_tokens, json_mode)
        try:
            data, attempts = self._request(url, body, headers, retries)
            text, tin, tout = self._parse(data)
        except ProviderError as e:
            if self.ledger:
                self.ledger.record(self.id, self.model, 0, 0, 0.0, ok=False, error=e.code)
            raise
        price = self.cfg.get("price_per_mtok")
        cost = None
        if isinstance(price, dict):
            cost = round((tin * price.get("input", 0) + tout * price.get("output", 0)) / 1e6, 6)
        if self.ledger:
            self.ledger.record(self.id, self.model, tin, tout, cost)
        return CallResult(text, self.id, self.model, tin, tout, cost, attempts)

    def _opener(self, url):
        host = urllib.parse.urlsplit(url).hostname or ""
        handlers = [urllib.request.HTTPSHandler(context=_ssl_context())]
        if host in LOCAL_HOSTS:
            handlers.append(urllib.request.ProxyHandler({}))
        return urllib.request.build_opener(*handlers)

    def _delay(self, attempt, retry_after):
        cap = float(self.http.get("backoff_max_s", 30))
        if retry_after is not None:
            return min(max(retry_after, 0.0), cap)
        base = float(self.http.get("backoff_base_s", 1.0))
        return min(cap, base * (2 ** attempt) + random.uniform(0, base))

    def _request(self, url, body, headers, retries=None):
        retries = int(self.http.get("max_retries", 3) if retries is None else retries)
        timeout = float(self.cfg.get("timeout_s") or self.http.get("timeout_s", 180))
        payload = json.dumps(body).encode("utf-8")
        hdrs = {"Content-Type": "application/json", "Accept": "application/json"}
        hdrs.update(headers)
        opener = self._opener(url)
        last = None
        for attempt in range(retries + 1):
            retry_after = None
            try:
                req = urllib.request.Request(url, data=payload, headers=hdrs, method="POST")
                with opener.open(req, timeout=timeout) as resp:
                    raw = resp.read()
                try:
                    return json.loads(raw.decode("utf-8")), attempt + 1
                except (UnicodeDecodeError, json.JSONDecodeError):
                    raise ProviderError("BAD_RESPONSE", "provider returned non-JSON body")
            except urllib.error.HTTPError as e:
                snippet = redact(e.read(400).decode("utf-8", "replace"), self.key_names)
                if e.code in (401, 403):
                    raise ProviderError("AUTH_FAILED", "HTTP %d %s" % (e.code, snippet[:200]), e.code)
                if e.code == 429 or e.code >= 500:
                    last = ProviderError("RATE_LIMITED" if e.code == 429 else "SERVER_ERROR",
                                         "HTTP %d after %d attempt(s): %s" % (e.code, attempt + 1, snippet[:200]), e.code)
                    ra = e.headers.get("Retry-After") if e.headers else None
                    try:
                        retry_after = float(ra) if ra is not None else None
                    except ValueError:
                        retry_after = None
                else:
                    raise ProviderError("HTTP_ERROR", "HTTP %d %s" % (e.code, snippet[:200]), e.code)
            except (urllib.error.URLError, socket.timeout, ConnectionError, TimeoutError) as e:
                reason = getattr(e, "reason", e)
                last = ProviderError("CONNECTION_ERROR", "%s after %d attempt(s)" % (
                    redact(str(reason), self.key_names)[:200], attempt + 1))
            if attempt < retries:
                self.sleep(self._delay(attempt, retry_after))
        raise last

    def ping(self):
        """Tiny live call used by `doctor --live`."""
        return self.chat("", "Reply with the single word OK.", max_tokens=64, retries=0)
