"""Deterministic LOW/MEDIUM/HIGH classification from changed paths + diff content."""
import re

from .util import match_any

ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def _changed_lines(diff):
    added, removed = [], []
    for ln in (diff or "").splitlines():
        if ln.startswith("+++") or ln.startswith("---"):
            continue
        if ln.startswith("+"):
            added.append(ln[1:])
        elif ln.startswith("-"):
            removed.append(ln[1:])
    return added, removed


def _formatting_only(diff):
    added, removed = _changed_lines(diff)
    if not added and not removed:
        return False
    norm = lambda xs: sorted(s for s in ("".join(x.split()) for x in xs) if s)  # noqa: E731
    return norm(added) == norm(removed)


def classify(paths, diff, rules, override=None):
    """Return (level, fired_rule_ids)."""
    fired, level = [], "LOW"
    paths = list(paths or [])
    if not paths and not diff:
        return "LOW", []
    added, removed = _changed_lines(diff)
    body = "\n".join(added + removed)
    low_rules = [r for r in rules if r["level"] == "LOW" and "paths" in r]
    for r in rules:
        hit = False
        if "paths" in r:
            hit = any(match_any(p, r["paths"]) for p in paths)
        elif body:
            hit = re.search(r["diff_regex"], body) is not None
        if hit and r["level"] != "LOW":
            fired.append(r["id"])
            level = max(level, r["level"], key=ORDER.get)
        elif hit:
            fired.append(r["id"])
    code = [p for p in paths if not any(match_any(p, r["paths"]) for r in low_rules)]
    if code and level == "LOW":
        if _formatting_only(diff):
            fired.append("formatting-only")
        else:
            fired.append("default-code")
            level = "MEDIUM"
    if override:
        fired.append("override:%s" % override["level"])
        level = override["level"]
    return level, fired
