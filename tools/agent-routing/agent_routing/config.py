"""Load shipped defaults + <project>/.agent-routing/project.json, validate, resolve routes."""
import copy
import datetime
import json
import re
import subprocess
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parent.parent
PROJECT_DIR = ".agent-routing"
LEVELS = ("LOW", "MEDIUM", "HIGH")
ROLES = ("executor", "reviewer")
PROVIDER_OVERRIDABLE = {"auth", "base_url", "price_per_mtok", "timeout_s"}
PROJECT_KEYS = {"executor", "reviewer", "fallback", "providers", "never_send", "risk_rules", "risk_override",
                "tests", "gate", "budgets", "http", "limits", "executor_rounds", "$comment"}


class ConfigError(Exception):
    pass


class RoutingError(Exception):
    def __init__(self, code, message):
        super().__init__("%s: %s" % (code, message))
        self.code = code


def find_project_root(project=None):
    if project:
        p = Path(project).resolve()
        if not p.is_dir():
            raise ConfigError("project directory not found: %s" % project)
        return p
    r = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if r.returncode != 0:
        raise ConfigError("not inside a git repository; pass --project")
    return Path(r.stdout.strip()).resolve()


def _load_json(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        raise ConfigError("missing file: %s" % path)
    except json.JSONDecodeError as e:
        raise ConfigError("invalid JSON in %s: %s" % (path, e))


def load_defaults():
    return _load_json(TOOL_ROOT / "defaults.json")


def _is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _check_rules(rules, where, errs):
    if not isinstance(rules, list):
        errs.append("%s must be a list" % where)
        return
    for i, r in enumerate(rules):
        w = "%s[%d]" % (where, i)
        if not isinstance(r, dict) or not isinstance(r.get("id"), str) or r.get("level") not in LEVELS:
            errs.append("%s needs id (str) and level in %s" % (w, LEVELS))
            continue
        has_p, has_d = "paths" in r, "diff_regex" in r
        if has_p == has_d:
            errs.append("%s needs exactly one of paths / diff_regex" % w)
        elif has_p and (not isinstance(r["paths"], list) or not all(isinstance(x, str) for x in r["paths"])):
            errs.append("%s.paths must be a list of globs" % w)
        elif has_d:
            try:
                re.compile(r["diff_regex"])
            except (re.error, TypeError) as e:
                errs.append("%s.diff_regex invalid: %s" % (w, e))


def validate(defaults, project):
    errs = []
    provs = defaults.get("providers", {})
    for pid, p in provs.items():
        for k in ("api", "base_url", "model", "role", "key_env"):
            if not isinstance(p.get(k), str):
                errs.append("defaults provider %s: %s missing" % (pid, k))
        if p.get("role") not in ROLES:
            errs.append("defaults provider %s: bad role" % pid)
    _check_rules(defaults.get("risk_rules", []), "defaults.risk_rules", errs)
    if not isinstance(project, dict):
        return errs + ["project.json must be an object"]
    for k in project:
        if k not in PROJECT_KEYS:
            errs.append("project.json: unknown key %r" % k)
    for role in ROLES:
        pid = project.get(role)
        if pid not in provs or provs[pid].get("role") != role:
            errs.append("project.json: %s must be one of %s" % (
                role, sorted(i for i, p in provs.items() if p.get("role") == role)))
    fb = project.get("fallback", {"executor": None, "reviewer": None})
    if not isinstance(fb, dict) or set(fb) - set(ROLES):
        errs.append("project.json: fallback must be {executor, reviewer}")
    else:
        for role, pid in fb.items():
            if pid is None:
                continue
            if pid not in provs or provs[pid].get("role") != role or pid == project.get(role):
                errs.append("project.json: fallback.%s must name a different %s provider or null" % (role, role))
    po = project.get("providers", {})
    if not isinstance(po, dict):
        errs.append("project.json: providers must be an object")
    else:
        for pid, ov in po.items():
            if pid not in provs or not isinstance(ov, dict):
                errs.append("project.json: providers.%s unknown" % pid)
                continue
            bad = set(ov) - PROVIDER_OVERRIDABLE
            if bad:
                errs.append("project.json: providers.%s may not override %s (models are fixed)" % (pid, sorted(bad)))
            if "auth" in ov and ov["auth"] not in ("env", "proxy-injected"):
                errs.append("project.json: providers.%s.auth must be env|proxy-injected" % pid)
            if "base_url" in ov and not (isinstance(ov["base_url"], str) and ov["base_url"].startswith(("http://", "https://"))):
                errs.append("project.json: providers.%s.base_url must be http(s)" % pid)
    ns = project.get("never_send", [])
    if not isinstance(ns, list) or not all(isinstance(x, str) for x in ns):
        errs.append("project.json: never_send must be a list of globs")
    _check_rules(project.get("risk_rules", []), "project.json risk_rules", errs)
    ro = project.get("risk_override")
    if ro is not None and not (isinstance(ro, dict) and ro.get("level") in LEVELS
                               and isinstance(ro.get("reason"), str) and ro["reason"].strip()):
        errs.append("project.json: risk_override must be null or {level, reason}")
    t = project.get("tests", {})
    if not isinstance(t, dict):
        errs.append("project.json: tests must be an object")
    else:
        c = t.get("command")
        if c is not None and not (isinstance(c, list) and c and all(isinstance(x, str) for x in c)):
            errs.append("project.json: tests.command must be null or a non-empty argv list")
        if not isinstance(t.get("from_session", True), bool):
            errs.append("project.json: tests.from_session must be bool")
    g = project.get("gate", {})
    if not isinstance(g, dict) or g.get("scope", "all") not in ("all", "sessions") or not (
            isinstance(g.get("max_blocks", 3), int) and g.get("max_blocks", 3) >= 1):
        errs.append("project.json: gate must be {scope: all|sessions, max_blocks: int >= 1}")
    b = project.get("budgets", {"daily": {}})
    if not isinstance(b, dict) or not isinstance(b.get("daily", {}), dict):
        errs.append("project.json: budgets must be {daily: {provider: {tokens, usd}}}")
    else:
        for pid, lim in b.get("daily", {}).items():
            if pid not in provs or not isinstance(lim, dict) or any(
                    v is not None and not _is_num(v) for v in lim.values()):
                errs.append("project.json: budgets.daily.%s invalid" % pid)
    for sect in ("http", "limits"):
        if not isinstance(project.get(sect, {}), dict) or not all(_is_num(v) for v in project.get(sect, {}).values()):
            errs.append("project.json: %s values must be numbers" % sect)
    return errs


def load_config(root):
    root = Path(root).resolve()
    defaults = load_defaults()
    pfile = root / PROJECT_DIR / "project.json"
    project = _load_json(pfile)
    errs = validate(defaults, project)
    if errs:
        raise ConfigError("; ".join(errs))
    cfg = copy.deepcopy(defaults)
    for pid, ov in project.get("providers", {}).items():
        cfg["providers"][pid].update(ov)
    for pid, p in cfg["providers"].items():
        p["id"] = pid
    cfg["root"] = str(root)
    cfg["project_file"] = str(pfile)
    cfg["executor"] = project["executor"]
    cfg["reviewer"] = project["reviewer"]
    cfg["fallback"] = dict(cfg["fallback"], **project.get("fallback", {}))
    cfg["risk_rules"] = list(project.get("risk_rules", [])) + list(defaults["risk_rules"])
    cfg["risk_override"] = project.get("risk_override")
    cfg["never_send"] = list(defaults.get("never_send", [])) + list(project.get("never_send", []))
    for sect in ("http", "limits", "gate", "tests", "budgets"):
        cfg[sect] = dict(cfg[sect], **project.get(sect, {}))
    if "executor_rounds" in project:
        cfg["executor_rounds"] = int(project["executor_rounds"])
    cfg["key_names"] = sorted({p["key_env"] for p in cfg["providers"].values()})
    return cfg


def state_dir(cfg):
    d = Path(cfg["root"]) / PROJECT_DIR / "state"
    d.mkdir(parents=True, exist_ok=True)
    return d


def resolve(cfg, role, today=None, use_fallback=False, log=None):
    """Return the provider config for a role. No automatic switching: fallback only when asked explicitly."""
    if role not in ROLES:
        raise RoutingError("BAD_ROLE", role)
    pid = cfg[role]
    if use_fallback:
        fb = cfg["fallback"].get(role)
        if not fb:
            raise RoutingError("NO_FALLBACK", "no fallback %s configured" % role)
        if log:
            log({"event": "fallback_used", "role": role, "from": pid, "to": fb})
        pid = fb
    p = cfg["providers"][pid]
    until = p.get("available_until")
    today = today or datetime.date.today()
    if until and today > datetime.date.fromisoformat(until):
        raise RoutingError("PROVIDER_EXPIRED", "%s (%s) was available until %s" % (pid, p["model"], until))
    return p
