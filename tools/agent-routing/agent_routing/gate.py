"""Gate lifecycle: fingerprint, risk, required tests, review requirement, review cache, audit log."""
import datetime
import json
from pathlib import Path

from . import config as C
from .budget import Ledger
from .executor import refusal, run_tests
from .providers import make_provider
from .redact import redact
from .review import actionable, review
from .risk import classify
from .util import changed_paths, diff_text, head, is_git_repo, match_any, sha256_file, sha256_text



class Gate:
    def __init__(self, cfg):
        self.cfg = cfg
        self.root = Path(cfg["root"])
        self.dir = C.state_dir(cfg)

    # storage --------------------------------------------------------------------------------------
    def _load(self, name, default):
        p = self.dir / name
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return default

    def _save(self, name, obj):
        (self.dir / name).write_text(json.dumps(obj, indent=1, sort_keys=True), encoding="utf-8")

    def audit(self, event):
        event = dict(event, ts=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"))
        line = redact(json.dumps(event, sort_keys=True), self.cfg["key_names"])
        with open(self.dir / "audit.jsonl", "a", encoding="utf-8") as fh:
            fh.write(line + "\n")

    # snapshot -------------------------------------------------------------------------------------
    def session(self):
        return self._load("sessions.json", {})

    def tests_command(self):
        t = self.cfg["tests"]
        if self.cfg["gate"]["scope"] == "sessions" and t.get("from_session", True):
            cmd = self.session().get("tests_command")
            if cmd:
                return cmd
        return t.get("command")

    def _stored_base(self):
        return self._load("gate_base.json", {}).get("base")

    def snapshot(self):
        if not is_git_repo(self.root):
            raise RuntimeError("project is not a git repository")
        if self.cfg["gate"]["scope"] == "sessions":
            s = self.session()
            scope, base = s.get("files", []), s.get("base") or head(self.root)
            if not scope:
                return {"paths": [], "diff": "", "fingerprint": None, "risk": "LOW", "fired": [], "base": base,
                        "refused": []}
        else:
            scope, base = None, self._stored_base() or head(self.root)
        paths, _ = changed_paths(self.root, base, scope)
        paths = [p for p in paths if not match_any(p, self.cfg["ignore"])]
        refused = [p for p in paths if refusal(self.root, self.cfg, p)]
        sendable = [p for p in paths if p not in refused]
        diff = diff_text(self.root, base, sendable)
        level, fired = classify(paths, diff, self.cfg["risk_rules"], self.cfg.get("risk_override"))
        opaque = []
        for p in refused:  # never content: only a hash enters the fingerprint
            f = self.root / p
            opaque.append("%s:%s" % (p, sha256_file(f) if f.is_file() else "absent"))
        fp = sha256_text("\0".join(paths) + "\0" + diff + "\0" + "\0".join(opaque)) if paths else None
        return {"paths": paths, "diff": diff, "fingerprint": fp, "risk": level, "fired": fired, "base": base,
                "refused": refused}

    def cache_key(self, fp, model=None):
        model = model or self.cfg["providers"][self.cfg["reviewer"]]["model"]
        return sha256_text("%s|%s|%s" % (fp, self.cfg["policy_version"], model))

    # actions --------------------------------------------------------------------------------------
    def run_review(self, task_summary="", provider=None, snap=None):
        snap = snap or self.snapshot()
        if not snap["paths"]:
            return {"verdict": "ERROR", "errors": ["no changes in gate scope"]}
        if snap["refused"]:
            res = {"verdict": "ERROR", "risk": snap["risk"], "fingerprint": snap["fingerprint"],
                   "errors": ["path cannot be sent for review: %s" % ", ".join(snap["refused"])]}
            self.audit({"event": "review", "fingerprint": snap["fingerprint"], "verdict": "ERROR",
                        "errors": res["errors"]})
            return res
        if provider is None:
            try:
                pcfg = C.resolve(self.cfg, "reviewer", log=self.audit)
            except C.RoutingError as e:
                res = {"verdict": "ERROR", "errors": [str(e)], "risk": snap["risk"]}
                self.audit({"event": "review", "fingerprint": snap["fingerprint"], "verdict": "ERROR",
                            "errors": res["errors"]})
                return res
            ledger = Ledger(self.dir / "usage.jsonl", self.cfg["budgets"].get("daily"))
            provider = make_provider(pcfg, self.cfg["http"], ledger, self.cfg["key_names"])
        res = review(provider, snap["diff"], snap["risk"], snap["fired"], task_summary,
                     self.cfg["limits"]["max_diff_bytes"], self.cfg["key_names"])
        res["fingerprint"] = snap["fingerprint"]
        if res["verdict"] in ("PASS", "FAIL"):
            cache = self._load("reviews.json", {})
            cache[self.cache_key(snap["fingerprint"], res["model"])] = res
            self._save("reviews.json", cache)
            hist = self._load("findings.json", {})
            for f in actionable(res["findings"]):
                hist.setdefault(f["id"], {"file": f["file"], "severity": f["severity"], "evidence": f["evidence"]})
            self._save("findings.json", hist)
        last = self._load("last_review.json", {})
        last.update(fingerprint=snap["fingerprint"], verdict=res["verdict"])
        self._save("last_review.json", last)
        self.audit({"event": "review", "fingerprint": snap["fingerprint"], "risk": snap["risk"],
                    "verdict": res["verdict"], "provider": res.get("provider"), "model": res.get("model"),
                    "findings": [f.get("id") for f in res.get("findings", [])], "errors": res.get("errors", [])})
        return res

    def resolve_finding(self, finding_id, evidence):
        if not evidence or not evidence.strip():
            raise ValueError("resolution evidence is required")
        r = self._load("resolutions.json", {})
        r[finding_id] = {"evidence": redact(evidence.strip(), self.cfg["key_names"])}
        self._save("resolutions.json", r)
        self.audit({"event": "finding_resolved", "finding": finding_id})

    def _tests(self, snap, cmd):
        cache = self._load("tests.json", {})
        key = sha256_text("%s|%s" % (snap["fingerprint"], json.dumps(cmd)))
        if key in cache:
            return cache[key]
        t = run_tests(self.root, cmd, self.cfg["limits"]["test_timeout_s"], self.cfg["key_names"])
        if not t.get("timed_out"):
            self._save("tests.json", {key: t})
        return t

    def evaluate(self, with_tests=True):
        snap = self.snapshot()
        d = {"status": "CLEAN", "unverified": None, "fingerprint": snap["fingerprint"], "risk": snap["risk"], "fired": snap["fired"],
             "paths": snap["paths"], "reasons": [], "tests": None, "review": None}
        if not snap["paths"]:
            self._advance_base()
            self.audit({"event": "gate", "status": "CLEAN"})
            return d
        d["unverified"] = self._load("unverified.json", {}).get(snap["fingerprint"])
        cmd = self.tests_command()
        if cmd and with_tests:
            t = self._tests(snap, cmd)
            d["tests"] = {"passed": t["passed"], "returncode": t["returncode"], "command": t["command"],
                          "timed_out": bool(t.get("timed_out"))}
            if not t["passed"]:
                d["reasons"].append("tests failing (%s)" % " ".join(cmd))
        elif not cmd and snap["risk"] in ("MEDIUM", "HIGH"):
            d["reasons"].append("%s risk requires tests but no test command is configured" % snap["risk"])
        if snap["refused"]:
            d["reasons"].append("path cannot be sent for review: %s" % ", ".join(snap["refused"]))
        if snap["risk"] in ("MEDIUM", "HIGH"):
            rv = self._load("reviews.json", {}).get(self.cache_key(snap["fingerprint"]))
            if rv is None:
                last = self._load("last_review.json", {})
                stale = last.get("fingerprint") and last["fingerprint"] != snap["fingerprint"]
                d["reasons"].append("review stale (code changed after the last review)" if stale
                                    else "review missing (risk %s)" % snap["risk"])
                if last.get("fingerprint") == snap["fingerprint"] and last.get("verdict") == "ERROR":
                    d["reasons"].append("last review attempt returned ERROR (reviewer unavailable or invalid)")
            elif rv["verdict"] != "PASS":
                d["reasons"].append("review %s: %s" % (rv["verdict"], "; ".join(
                    "%s %s %s" % (f["id"], f["severity"], f["file"]) for f in actionable(rv["findings"]))))
            d["review"] = rv and {"verdict": rv["verdict"], "model": rv.get("model"), "provider": rv.get("provider")}
            if rv and rv["verdict"] == "PASS" and snap["risk"] == "HIGH":
                hist = dict(self._load("findings.json", {}))
                for f in actionable(rv["findings"]):
                    hist.setdefault(f["id"], {})
                res = self._load("resolutions.json", {})
                open_ = sorted(k for k in hist if k not in res)
                if open_:
                    d["reasons"].append("HIGH risk: no recorded resolution evidence for findings %s" % ", ".join(open_))
        d["status"] = "BLOCK" if d["reasons"] else "PASS"
        if d["status"] == "PASS":
            self._advance_base()
        elif self.cfg["gate"]["scope"] == "all" and not self._stored_base():
            self._save("gate_base.json", {"base": snap["base"]})
        self.audit({"event": "gate", "status": d["status"], "fingerprint": d["fingerprint"], "risk": d["risk"],
                    "fired": d["fired"], "reasons": d["reasons"]})
        return d

    def _advance_base(self):
        """scope=all: drop the stored base once nothing in scope differs from HEAD (next base = HEAD)."""
        if self.cfg["gate"]["scope"] != "all" or not self._stored_base():
            return
        paths, _ = changed_paths(self.root, head(self.root))
        if not [p for p in paths if not match_any(p, self.cfg["ignore"])]:
            (self.dir / "gate_base.json").unlink()

    def clear(self, force=False, reason=None):
        """Clear session/base state. Without force only when the gate is PASS or CLEAN."""
        d = self.evaluate()
        if d["status"] not in ("PASS", "CLEAN"):
            if not force or not (reason or "").strip():
                raise ValueError("gate is %s (%s); refusing to clear without --force --reason"
                                 % (d["status"], "; ".join(d["reasons"])))
            marks = self._load("unverified.json", {})
            marks[d["fingerprint"]] = redact(reason.strip(), self.cfg["key_names"])
            self._save("unverified.json", marks)
        for name in ("sessions.json", "findings.json", "resolutions.json", "blocks.json", "gate_base.json"):
            p = self.dir / name
            if p.exists():
                p.unlink()
        self.audit({"event": "gate_cleared", "forced": d["status"] not in ("PASS", "CLEAN"),
                    "status": d["status"], "fingerprint": d["fingerprint"], "reason": reason})
        return d
