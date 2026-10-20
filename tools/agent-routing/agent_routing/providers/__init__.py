from .base import CallResult, Provider, ProviderError
from .gemini import GeminiProvider
from .openai_compat import OpenAICompatProvider


def make_provider(pcfg, http_cfg, ledger=None, key_names=(), sleep=None):
    cls = {"gemini": GeminiProvider, "openai": OpenAICompatProvider}.get(pcfg["api"])
    if cls is None:
        raise ProviderError("PROVIDER_UNCONFIGURED", "unknown api %r" % pcfg["api"])
    return cls(pcfg, http_cfg, ledger=ledger, key_names=key_names, sleep=sleep)


__all__ = ["CallResult", "Provider", "ProviderError", "make_provider"]
