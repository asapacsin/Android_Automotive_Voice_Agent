"""Reviewer: send the actual diff + risk + task summary; strictly validate the review contract."""
import json

from .executor import parse_json_reply
from .providers import ProviderError
from .redact import redact

SEVERITIES = ("critical", "major", "minor", "info")
FINDING_KEYS = {"id": str, "severity": str, "file": str, "evidence": str, "recommendation": str}
SYSTEM = (
    "You are a strict code reviewer. Review ONLY the diff given. Reply with ONLY one JSON object, no prose:\n"
    '{"verdict": "PASS"|"FAIL", "risk": "LOW"|"MEDIUM"|"HIGH", "findings": [{"id": str, "severity": '
    '"critical"|"major"|"minor"|"info", "file": str, "line": int|null, "evidence": str, "recommendation": str, '
    '"actionable": bool}], "confidence": number 0..1, "model": str, "provider": str}\n'
    "FAIL requires at least one finding. PASS must not contain an actionable critical or major finding.")


def _is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def validate_review(obj):
    errs = []
    if not isinstance(obj, dict):
        return ["review is not a JSON object"]
    for k in ("verdict", "risk", "findings", "confidence", "model", "provider"):
        if k not in obj:
            errs.append("missing field %s" % k)
    if errs:
        return errs
    if obj["verdict"] not in ("PASS", "FAIL"):
        errs.append("verdict must be PASS or FAIL")
    if obj["risk"] not in ("LOW", "MEDIUM", "HIGH"):
        errs.append("risk must be LOW|MEDIUM|HIGH")
    if not _is_num(obj["confidence"]) or not 0 <= obj["confidence"] <= 1:
        errs.append("confidence must be a number in 0..1")
    if not isinstance(obj["model"], str) or not isinstance(obj["provider"], str):
        errs.append("model and provider must be strings")
    if not isinstance(obj["findings"], list):
        return errs + ["findings must be a list"]
    for i, f in enumerate(obj["findings"]):
        if not isinstance(f, dict):
            errs.append("finding %d is not an object" % i)
            continue
        for k, t in FINDING_KEYS.items():
            if not isinstance(f.get(k), t):
                errs.append("finding %d: %s must be a string" % (i, k))
        if "line" not in f or not (f["line"] is None or (isinstance(f["line"], int) and not isinstance(f["line"], bool))):
            errs.append("finding %d: line must be int or null" % i)
        if not isinstance(f.get("actionable"), bool):
            errs.append("finding %d: actionable must be bool" % i)
        if f.get("severity") not in SEVERITIES:
            errs.append("finding %d: bad severity" % i)
    if errs:
        return errs
    if obj["verdict"] == "FAIL" and not obj["findings"]:
        errs.append("FAIL verdict without findings")
    if obj["verdict"] == "PASS" and any(f["actionable"] and f["severity"] in ("critical", "major")
                                        for f in obj["findings"]):
        errs.append("PASS verdict with an actionable critical/major finding")
    return errs


def actionable(findings):
    return [f for f in findings or [] if f.get("actionable") and f.get("evidence", "").strip()]


def review(provider, diff, risk, fired, task_summary, max_diff_bytes, key_names=(), max_output_tokens=8192):
    """Return a result dict. verdict is PASS/FAIL only from a validated reviewer reply; otherwise ERROR."""
    out = {"verdict": "ERROR", "risk": risk, "findings": [], "confidence": None, "provider": provider.id,
           "model": provider.model, "errors": [], "truncated": False}
    if not diff.strip():
        out["errors"].append("empty diff; nothing to review")
        return out
    d = redact(diff, key_names)
    if len(d) > max_diff_bytes:
        out["truncated"] = True
        out["errors"].append("change too large to review (%d > %d bytes); split it" % (len(d), max_diff_bytes))
        return out
    user = "TASK SUMMARY:\n%s\n\nDETERMINISTIC RISK: %s (rules: %s)\n\nDIFF:\n%s" % (
        redact(task_summary or "(none given)", key_names), risk, ", ".join(fired) or "none", d)
    try:
        res = provider.chat(SYSTEM, user, max_tokens=max_output_tokens, json_mode=True)
    except ProviderError as e:
        out["errors"].append(str(e))
        out["error_code"] = e.code
        return out
    out["tokens"] = {"input": res.input_tokens, "output": res.output_tokens}
    out["cost_usd"] = res.cost_usd
    try:
        obj = parse_json_reply(res.text)
    except (json.JSONDecodeError, ValueError):
        out["errors"].append("REJECTED: reviewer reply is not JSON")
        return out
    errs = validate_review(obj)
    if errs:
        out["errors"] = ["REJECTED: " + e for e in errs]
        return out
    if obj["model"] != provider.model or obj["provider"] != provider.id:
        out["reported_identity"] = {"model": obj["model"], "provider": obj["provider"]}
    out.update(verdict=obj["verdict"], findings=obj["findings"], confidence=obj["confidence"],
               reviewer_risk=obj["risk"], provider=provider.id, model=provider.model)
    return out
