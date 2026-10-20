"""python -m agent_routing <route|execute|review|gate|doctor|install|stop-hook>"""
import argparse
import json
import shlex
import shutil
import sys
from pathlib import Path

from . import config as C
from .executor import execute
from .gate import Gate
from .providers import ProviderError, make_provider

HOOK_SNIPPET = {"hooks": {"Stop": [{"hooks": [{"type": "command", "command":
                "python3 tools/agent-routing/agent_routing_cli.py stop-hook", "timeout": 1800}]}]}}


def _print(obj):
    print(json.dumps(obj, indent=2, sort_keys=True))


def cmd_route(a, cfg):
    out = {}
    for role in C.ROLES:
        try:
            p = C.resolve(cfg, role)
            out[role] = {"provider": p["id"], "model": p["model"], "base_url": p["base_url"], "auth": p.get("auth")}
        except C.RoutingError as e:
            out[role] = {"provider": cfg[role], "error": str(e)}
    out["fallback"] = cfg["fallback"]
    _print(out)
    return 0 if all("error" not in v for k, v in out.items() if k in C.ROLES) else 1


def cmd_execute(a, cfg):
    task = Path(a.task_file).read_text(encoding="utf-8") if a.task_file else a.task
    if not task:
        print("execute: --task or --task-file is required", file=sys.stderr)
        return 2
    findings = json.loads(Path(a.findings).read_text(encoding="utf-8")) if a.findings else None
    tests = shlex.split(a.tests) if a.tests else None
    st = execute(cfg["root"], cfg, task, a.files, a.context or (), tests, findings, use_fallback=a.use_fallback)
    _print(st)
    return 0 if st["status"] == "APPLIED" else 1


def cmd_review(a, cfg):
    g = Gate(cfg)
    if a.use_fallback:
        p = C.resolve(cfg, "reviewer", use_fallback=True, log=g.audit)
        from .budget import Ledger
        prov = make_provider(p, cfg["http"], Ledger(g.dir / "usage.jsonl", cfg["budgets"].get("daily")), cfg["key_names"])
        res = g.run_review(a.summary or "", provider=prov)
    else:
        res = g.run_review(a.summary or "")
    _print(res)
    return 0 if res["verdict"] == "PASS" else 1


def cmd_gate(a, cfg):
    g = Gate(cfg)
    if a.action == "resolve":
        g.resolve_finding(a.id, a.evidence)
        print("recorded resolution for %s" % a.id)
        return 0
    if a.action == "clear":
        g.clear_session()
        print("session cleared")
        return 0
    d = g.evaluate(with_tests=(a.action == "check"))
    _print(d)
    return 0 if d["status"] in ("PASS", "CLEAN") or a.action == "status" else 1


def cmd_doctor(a, root):
    try:
        cfg = C.load_config(root)
    except C.ConfigError as e:
        print("config: INVALID - %s" % e)
        return 2
    print("config: OK (%s)" % Path(cfg["project_file"]).relative_to(root).as_posix())
    rc = 0
    for role in C.ROLES:
        try:
            p = C.resolve(cfg, role)
            print("route %s: %s -> %s" % (role, p["id"], p["model"]))
        except C.RoutingError as e:
            print("route %s: %s" % (role, e))
            rc = 1
    print("gate: scope=%s max_blocks=%s policy=%s" % (cfg["gate"]["scope"], cfg["gate"]["max_blocks"],
                                                     cfg["policy_version"]))
    for pid in sorted(cfg["providers"]):
        p = cfg["providers"][pid]
        label = "provider %s (%s, auth=%s)" % (pid, p["model"], p.get("auth"))
        if not a.live:
            print("%s: SKIPPED (no --live)" % label)
            continue
        try:
            C.resolve(dict(cfg, executor=pid, reviewer=pid), p["role"])
            r = make_provider(p, dict(cfg["http"], max_retries=0), None, cfg["key_names"]).ping()
            print("%s: LIVE OK (%d in / %d out tokens)" % (label, r.input_tokens, r.output_tokens))
        except C.RoutingError as e:
            print("%s: LIVE %s" % (label, e.code))
        except ProviderError as e:
            word = {"AUTH_FAILED": "AUTH FAILED", "PROVIDER_UNCONFIGURED": "UNCONFIGURED"}.get(e.code, "FAILED " + e.code)
            print("%s: LIVE %s (%s)" % (label, word, ("HTTP %s" % e.status) if e.status else str(e)[:160]))
    return rc


def cmd_install(a):
    src = C.TOOL_ROOT
    target = Path(a.target).resolve()
    if not target.is_dir():
        print("install: target directory not found", file=sys.stderr)
        return 2
    dest = target / "tools" / "agent-routing"
    if dest.exists() and not a.force:
        print("install: %s exists (use --force to replace the tool copy)" % dest.relative_to(target), file=sys.stderr)
        return 2
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    pdir = target / C.PROJECT_DIR
    pdir.mkdir(exist_ok=True)
    pj = pdir / "project.json"
    created = []
    if not pj.exists():
        t = json.loads((src / "templates" / "project.json").read_text(encoding="utf-8"))
        t["executor"] = a.executor
        pj.write_text(json.dumps(t, indent=2) + "\n", encoding="utf-8")
        created.append(pj)
    gi = pdir / ".gitignore"
    if not gi.exists():
        gi.write_text("state/\n", encoding="utf-8")
        created.append(gi)
    print("installed tools/agent-routing; created: %s" % (", ".join(p.relative_to(target).as_posix() for p in created)
                                                         or "nothing (config kept)"))
    print("Add this Stop hook to .claude/settings.json yourself (not modified by install):")
    print(json.dumps(HOOK_SNIPPET, indent=2))
    return 0


def build_parser():
    ap = argparse.ArgumentParser(prog="agent_routing")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def proj(p):
        p.add_argument("--project", help="project root (default: git toplevel of cwd)")
        return p

    proj(sub.add_parser("route"))
    e = proj(sub.add_parser("execute"))
    e.add_argument("--task")
    e.add_argument("--task-file")
    e.add_argument("--files", nargs="+", required=True)
    e.add_argument("--context", nargs="*")
    e.add_argument("--tests", help="test command (argv, shell-style quoting)")
    e.add_argument("--findings", help="JSON file with reviewer findings to address")
    e.add_argument("--use-fallback", action="store_true")
    r = proj(sub.add_parser("review"))
    r.add_argument("--summary")
    r.add_argument("--use-fallback", action="store_true")
    g = proj(sub.add_parser("gate"))
    g.add_argument("action", choices=["status", "check", "resolve", "clear"])
    g.add_argument("--id")
    g.add_argument("--evidence")
    d = proj(sub.add_parser("doctor"))
    d.add_argument("--live", action="store_true")
    i = sub.add_parser("install")
    i.add_argument("--target", required=True)
    i.add_argument("--executor", choices=["gemini", "poolside"], default="gemini")
    i.add_argument("--force", action="store_true")
    proj(sub.add_parser("stop-hook"))
    return ap


def main(argv=None):
    a = build_parser().parse_args(argv)
    if a.cmd == "install":
        return cmd_install(a)
    if a.cmd == "stop-hook":
        from .stop_hook import run
        return run(project=a.project)
    try:
        root = C.find_project_root(a.project)
        if a.cmd == "doctor":
            return cmd_doctor(a, root)
        cfg = C.load_config(root)
        return {"route": cmd_route, "execute": cmd_execute, "review": cmd_review, "gate": cmd_gate}[a.cmd](a, cfg)
    except (C.ConfigError, C.RoutingError, ValueError, RuntimeError) as e:
        print("agent_routing: %s" % e, file=sys.stderr)
        return 2
