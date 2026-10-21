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
    def normalize_line(line):
        # Keep leading whitespace
        leading = line[:len(line) - len(line.lstrip())]
        rest = line[len(leading):]
        # Strip trailing whitespace
        rest = rest.rstrip()
        # Remove internal whitespace
        rest = ''.join(rest.split())
        return leading + rest
    def norm(lines):
        normalized = []
        for line in lines:
            n = normalize_line(line)
            if n:
                normalized.append(n)
        return sorted(normalized)
    return norm(added) == norm(removed)


_WORD = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")


def path_words(path):
    """Split a path into lowercase words at /, _, -, . and camelCase boundaries."""
    return {w.lower() for w in _WORD.findall(path)}


def _path_hit(rule, path):
    if match_any(path, rule.get("paths", ())):
        return True
    words = rule.get("path_words")
    return bool(words) and not path_words(path).isdisjoint(words)


def classify(paths, diff, rules, override=None):
    """Return (level, fired_rule_ids)."""
    fired, level = [], "LOW"
    paths = list(paths or [])
    if not paths and not diff:
        return "LOW", []
    added, removed = _changed_lines(diff)
    body = "\n".join(added + removed)
    low_rules = [r for r in rules if r["level"] == "LOW" and "diff_regex" not in r]
    for r in rules:
        hit = False
        if "diff_regex" not in r:
            hit = any(_path_hit(r, p) for p in paths)
        elif body:
            hit = re.search(r["diff_regex"], body) is not None
        if hit and r["level"] != "LOW":
            fired.append(r["id"])
            level = max(level, r["level"], key=ORDER.get)
        elif hit:
            fired.append(r["id"])
    code = [p for p in paths if not any(_path_hit(r, p) for r in low_rules)]
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
