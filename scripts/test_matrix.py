"""The test registry: validate it, generate its views, and decide whether a human is needed yet.

The behaviour this exists to change: a test only a person can run used to end the run. The agent
hit it, stopped, asked, waited, resumed, hit the next one, stopped again. Every human-dependent
case was its own interruption.

Here a HUMAN_REQUIRED test is a **queued item with an owner**, not a wall. Autonomous execution
walks straight past it. The human is asked once, at a phase boundary, with everything collected
(harness/PHASES.md).

    python scripts/test_matrix.py --validate   schema and internal consistency
    python scripts/test_matrix.py --gate       is HUMAN_VALIDATION_READY legal yet, and if not why
    python scripts/test_matrix.py --status     regenerate TEST_STATUS.md
    python scripts/test_matrix.py --packet     regenerate HUMAN_VALIDATION.md
    python scripts/test_matrix.py --verdict ID verdict the evidence for one entry actually earns
    python scripts/test_matrix.py --coverage   per-requirement scope coverage
    python scripts/test_matrix.py --selftest   the rules above, exercised
"""
import argparse
import copy
import datetime
import hashlib
import io
import json
import os
import re
import subprocess
import sys

import yaml

import acceptance

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MATRIX = os.path.join(REPO, "TEST_MATRIX.yaml")
CAPABILITIES = os.path.join(REPO, "config", "capabilities.yaml")
STATUS_DOC = os.path.join(REPO, "TEST_STATUS.md")
PACKET_DOC = os.path.join(REPO, "HUMAN_VALIDATION.md")
LOCAL_DEVICE_DOC = os.path.join(REPO, "LOCAL_DEVICE_REQUIRED.md")
PASSES = os.path.join(REPO, "state", "DISCOVERY_PASSES.json")

OWNERS = (
    "AUTONOMOUS", "HUMAN_PHYSICAL", "HUMAN_ACCOUNT", "HUMAN_CREDENTIAL",
    "HUMAN_DECISION", "EXTERNAL_RESOURCE",
)
STATUSES = (
    "NOT_RUN", "RUNNING", "PASS", "FAIL", "BLOCKED_AUTONOMOUS", "HUMAN_REQUIRED",
    "BLOCKED_EXTERNAL", "NOT_APPLICABLE", "HUMAN_PASS", "HUMAN_FAIL",
    "INCOMPLETE", "PARTIAL_PASS",
)
HUMAN_OWNERS = tuple(o for o in OWNERS if o.startswith("HUMAN_") or o == "EXTERNAL_RESOURCE")

# HUMAN_REQUIRED is legal only with one of these. ADB, taps, emulator, logs, screenshots,
# video, and inconvenience are not blockers (CONSTITUTION rule 17).
AUTOMATION_BLOCKERS = (
    "subjective_perception",
    "physical_world",
    "credential_permission",
    "hardware_interface",
    "safety",
)
_INVALID_REASON = re.compile(
    r"\b(adb|logcat|screenshot|screencap|screenrecord|emulator navi|tapping the device|"
    r"requires tapping|watching logs|taking screenshots|inconvenient|multi-step)\b",
    re.I,
)
_VALID_REASON = re.compile(
    r"cabin|acoustics|road noise|gps|drive|vehicle|keystore|password|credential|\bsim\b|"
    r"timbre|loudness|intelligib|perception|ear check|policy|choose|consent|"
    r"hardware|safety|moving|signing",
    re.I,
)

# Statuses an autonomous test may hold when the human gate opens. Anything else is work.
# INCOMPLETE is deliberately absent: a run that never reached a verdict is unfinished work,
# never a pass. PARTIAL_PASS is settled — a finished verdict at an explicitly declared scope,
# with the remainder tracked by its parent entry.
AUTONOMOUS_SETTLED = ("PASS", "NOT_APPLICABLE", "BLOCKED_EXTERNAL", "PARTIAL_PASS")
# Statuses that mean a human still owes us a result.
AWAITING_HUMAN = ("HUMAN_REQUIRED", "HUMAN_FAIL")

REQUIRED = ("id", "capability", "name", "purpose", "type", "owner", "status",
            "scope", "release_blocking", "prerequisites", "procedure", "pass_criteria")

# Any stored verdict — autonomous or human. Only these are checked against evidence.
VERDICT_STATUSES = ("PASS", "FAIL", "PARTIAL_PASS", "INCOMPLETE",
                    "HUMAN_PASS", "HUMAN_FAIL")


def load(path=MATRIX):
    with io.open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def tests(doc=None):
    return (doc or load())["tests"]


def capability_requirements(path=CAPABILITIES):
    """{dotted.key: required_scope} from the capability registry.

    The registry owns the bar: a test author can scope their own test, but cannot lower
    what a requirement demands. Absent `required_scope` means COMPONENT — a single
    behaviour with no flow bar.
    """
    with io.open(path, encoding="utf-8") as handle:
        doc = yaml.safe_load(handle)
    out = {}
    for group, entries in doc.items():
        if not isinstance(entries, dict):
            continue
        for name, cap in entries.items():
            if not isinstance(cap, dict) or "verified" not in cap:
                continue
            out["%s.%s" % (group, name)] = cap.get("required_scope", "COMPONENT")
    return out


REGISTRY_SKIP = ("meta", "human_verification_pending", "change_impact")
FEATURE_PRESENCE = os.path.join(
    REPO, "behavior-test/src/test/kotlin/com/novadrive/architecture/FeaturePresenceRegressionTest.kt")


def load_capability_registry(path=CAPABILITIES):
    with io.open(path, encoding="utf-8") as handle:
        doc = yaml.safe_load(handle)
    caps = {}
    for group, entries in doc.items():
        if group in REGISTRY_SKIP or not isinstance(entries, dict):
            continue
        for name, cap in entries.items():
            if isinstance(cap, dict) and "verified" in cap:
                caps["%s.%s" % (group, name)] = cap
    return caps, doc.get("change_impact") or {}


# ---- runtime evidence binding ---------------------------------------------------------------

RUNTIME_BIND_STATUSES = ("PASS", "HUMAN_PASS", "PARTIAL_PASS")
_SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea"}
_SOURCE_INDEX = None
_BIND_COMPUTE_CACHE = {}


def need_runtime_bind(entry):
    """Device/flow/E2E verdicts must carry evidence_bind digests."""
    if entry.get("status") not in RUNTIME_BIND_STATUSES:
        return False
    if entry.get("type") in ("device", "human"):
        return True
    return entry.get("scope") in ("INTERMEDIATE_FLOW", "END_TO_END")


def _source_index():
    global _SOURCE_INDEX
    if _SOURCE_INDEX is not None:
        return _SOURCE_INDEX
    index = {}
    decl = re.compile(r"\b(?:class|object|interface)\s+(\w+)")
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in _SKIP_DIRS]
        for name in files:
            if not name.endswith((".kt", ".java")):
                continue
            path = os.path.join(root, name)
            base = os.path.splitext(name)[0]
            index.setdefault(base, []).append(path)
            try:
                with io.open(path, encoding="utf-8") as handle:
                    text = handle.read()
            except Exception:
                continue
            for match in decl.finditer(text):
                index.setdefault(match.group(1), []).append(path)
    _SOURCE_INDEX = {k: sorted(set(v)) for k, v in index.items()}
    return _SOURCE_INDEX


_IMPACT_META = ("watched_roots", "impact_exempt")
BIND_GUARD_FILES = (
    "config/capabilities.yaml",
    "scripts/test_matrix.py",
    "scripts/acceptance.py",
)
HARNESS_BIND_FILES = (
    os.path.join(REPO, "scripts", "test_matrix.py"),
    os.path.join(REPO, "scripts", "acceptance.py"),
)


class BindRefused(Exception):
    def __init__(self, message, code=2):
        super(BindRefused, self).__init__(message)
        self.code = code


def _areas(change_impact):
    for name, spec in (change_impact or {}).items():
        if name in _IMPACT_META or not isinstance(spec, dict):
            continue
        yield name, spec


def _repo_rel(path):
    if os.path.isabs(path):
        path = os.path.relpath(path, REPO)
    return path.replace("\\", "/")


def _product_source(path):
    return "/src/test/" not in _repo_rel(path)


def impact_capability_ids(entry, caps=None):
    """Capabilities this PASS protects.

    Explicit ``covers`` plus every registry id that lists the row in ``protected_by``.
    A device row can be the protection anchor without repeating those ids on the row.
    """
    ids = set(entry.get("covers") or [])
    tid = entry.get("id")
    if tid:
        for cap_id, cap in (caps or {}).items():
            if isinstance(cap, dict) and tid in (cap.get("protected_by") or []):
                ids.add(cap_id)
    return sorted(ids)


def impact_files(change_impact, covers):
    """Kotlin/Java paths for change_impact areas whose retest intersects covers."""
    covers_set = set(covers or [])
    components = set()
    for _, spec in _areas(change_impact):
        retest = set(spec.get("retest") or [])
        if retest & covers_set:
            for comp in spec.get("components") or []:
                components.add(comp)
    index = _source_index()
    paths = []
    for comp in sorted(components):
        for path in index.get(comp, []):
            if _product_source(path):
                paths.append(path)
    return sorted(set(paths))


def unmapped_product_files(change_impact=None, extra_files=None):
    """Product sources under watched_roots that no component names."""
    if change_impact is None:
        _, change_impact = load_capability_registry()
    roots = change_impact.get("watched_roots") or []
    exempt = set((change_impact.get("impact_exempt") or []))
    index = _source_index()
    mapped = set()
    for _, spec in _areas(change_impact):
        for comp in spec.get("components") or []:
            for path in index.get(comp, []):
                if _product_source(path):
                    mapped.add(_repo_rel(path))
    found = []
    for root in roots:
        abs_root = os.path.join(REPO, root.replace("/", os.sep))
        if not os.path.isdir(abs_root):
            found.append(root)
            continue
        for dirpath, _, files in os.walk(abs_root):
            for name in files:
                if not name.endswith((".kt", ".java")):
                    continue
                rel = _repo_rel(os.path.join(dirpath, name))
                if rel not in mapped and rel not in exempt:
                    found.append(rel)
    for extra in extra_files or []:
        rel = str(extra).replace("\\", "/")
        if rel not in mapped and rel not in exempt:
            found.append(rel)
    return sorted(set(found))


def _sha256_bytes(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _sha256_file(path):
    digest = hashlib.sha256()
    with io.open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def _sha256_files(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
        with io.open(path, "rb") as handle:
            digest.update(handle.read())
    return "sha256:" + digest.hexdigest()


def _procedure_payload(entry):
    payload = {
        "procedure": entry.get("procedure") or [],
        "pass_criteria": entry.get("pass_criteria") or [],
        "terminal_success": entry.get("terminal_success") or [],
        "ended_by": entry.get("ended_by"),
        "acceptance": [
            {"criterion": row.get("criterion"), "required": row.get("required")}
            for row in (entry.get("acceptance") or [])
            if isinstance(row, dict)
        ],
    }
    return yaml.dump(payload, sort_keys=True, default_flow_style=False)


def _evidence_strings(entry):
    strings = list(entry.get("evidence") or [])
    for row in entry.get("acceptance") or []:
        if isinstance(row, dict) and row.get("evidence"):
            strings.append(row["evidence"])
    return strings


def _apk_path():
    env = os.environ.get("NOVA_BUILD_DIR")
    if env:
        root = env
    elif os.name == "nt":
        root = r"C:\Users\Administrator\tools\nova-drive-build"
    else:
        root = os.path.join(os.path.expanduser("~"), "nova-drive-build")
    return os.path.join(root, "app", "outputs", "apk", "debug", "app-debug.apk")


def _git_head_short():
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return None


def _registry_payload(covers, change_impact, caps):
    covers = list(covers or [])
    areas = {}
    cover_set = set(covers)
    for name, spec in _areas(change_impact):
        if set(spec.get("retest") or []) & cover_set:
            areas[name] = spec
    subset = {cid: (caps or {}).get(cid) for cid in sorted(covers)}
    return yaml.dump({"areas": areas, "caps": subset}, sort_keys=True, default_flow_style=False)


def _harness_digest(extra=b""):
    return _sha256_bytes(_sha256_files(list(HARNESS_BIND_FILES)).encode("utf-8") + (extra or b""))


def compute_bind(entry, change_impact=None, use_cache=True, harness_extra=b"", caps=None):
    """Fresh evidence_bind digests from the current tree and entry procedure."""
    cache_key = (entry.get("id"), harness_extra, id(caps) if caps is not None else None)
    if use_cache and cache_key in _BIND_COMPUTE_CACHE:
        return _BIND_COMPUTE_CACHE[cache_key]
    if change_impact is None or caps is None:
        loaded_caps, loaded_change = load_capability_registry()
        if change_impact is None:
            change_impact = loaded_change
        if caps is None:
            caps = loaded_caps
    covered = impact_capability_ids(entry, caps)
    files = impact_files(change_impact, covered)
    artifact_digests = {}
    for path in acceptance.local_artifact_paths(_evidence_strings(entry)):
        if os.path.isfile(path):
            artifact_digests[path] = _sha256_file(path)
    apk = _apk_path()
    apk_digest = _sha256_file(apk) if os.path.isfile(apk) else None
    result = {
        "code_digest": _sha256_files(files),
        "procedure_digest": _sha256_bytes(_procedure_payload(entry).encode("utf-8")),
        "registry_digest": _sha256_bytes(
            _registry_payload(covered, change_impact, caps).encode("utf-8")),
        "harness_digest": _harness_digest(harness_extra),
        "artifact_digests": artifact_digests,
        "apk_digest": apk_digest,
        "bound_commit": _git_head_short(),
    }
    if use_cache:
        _BIND_COMPUTE_CACHE[cache_key] = result
    return result


def bind_problems(entry, change_impact=None):
    """STALE_BIND / missing-bind problems for device/flow/E2E verdict rows."""
    if not need_runtime_bind(entry):
        return []
    tid = entry.get("id", "<no id>")
    stored = entry.get("evidence_bind")
    if not isinstance(stored, dict) or not stored:
        return ["%s: STALE_BIND missing evidence_bind" % tid]
    if change_impact is None:
        _, change_impact = load_capability_registry()
    current = compute_bind(entry, change_impact, use_cache=False)
    problems = []
    for key in ("code_digest", "procedure_digest", "registry_digest", "harness_digest"):
        if stored.get(key) != current.get(key):
            problems.append("%s: STALE_BIND %s mismatch" % (tid, key))
    if stored.get("apk_digest") is not None and stored.get("apk_digest") != current.get("apk_digest"):
        problems.append("%s: STALE_BIND apk_digest mismatch" % tid)
    stored_art = stored.get("artifact_digests") or {}
    current_art = current.get("artifact_digests") or {}
    for path in acceptance.local_artifact_paths(_evidence_strings(entry)):
        if not os.path.isfile(path):
            problems.append("%s: STALE_BIND missing artifact %s" % (tid, path))
        elif stored_art.get(path) != current_art.get(path):
            problems.append("%s: STALE_BIND artifact %s mismatch" % (tid, path))
    for path, _digest in stored_art.items():
        if path not in current_art and os.path.isfile(path):
            problems.append("%s: STALE_BIND artifact %s extra/mismatch" % (tid, path))
    return problems


def _bind_current(entry, change_impact=None):
    return not bind_problems(entry, change_impact)


def _needs_runtime_protection(cap, cap_id, requirements):
    if cap.get("verified") in ("device", "human"):
        return True
    need = requirements.get(cap_id, "COMPONENT")
    return need in ("INTERMEDIATE_FLOW", "END_TO_END")


def _format_bind_yaml(bind):
    lines = ["    evidence_bind:"]
    for key in ("code_digest", "procedure_digest", "registry_digest", "harness_digest",
                "artifact_digests", "apk_digest", "bound_commit"):
        val = bind.get(key)
        if key == "artifact_digests":
            art = val or {}
            if not art:
                lines.append("      artifact_digests: {}")
            else:
                lines.append("      artifact_digests:")
                for path, digest in sorted(art.items()):
                    lines.append('        "%s": %s' % (path.replace("\\", "/"), digest))
        elif val is None:
            lines.append("      %s: null" % key)
        else:
            lines.append("      %s: %s" % (key, val))
    return "\n".join(lines)


def write_evidence_bind(entry_id, bind, path=MATRIX):
    """Insert or replace evidence_bind on one matrix row without rewriting the whole file."""
    block = _format_bind_yaml(bind) + "\n"
    text = io.open(path, encoding="utf-8").read()
    pattern = re.compile(
        r"(  - id: %s\r?\n)(.*?)(?=\n  - id: |\n# ----|\Z)" % re.escape(entry_id),
        re.DOTALL)
    match = pattern.search(text)
    if not match:
        raise SystemExit("no such test: %s" % entry_id)
    body = match.group(2)
    if re.search(r"^    evidence_bind:", body, re.MULTILINE):
        body = re.sub(
            r"^    evidence_bind:\n(?:    [ ].*\n)*",
            "",
            body,
            count=1,
            flags=re.MULTILINE,
        )
    anchors = [a for a in ("    related:", "    last_run:", "    covers:") if a in body]
    if anchors:
        insert_at = min(body.index(a) for a in anchors)
        body = body[:insert_at] + block + body[insert_at:]
    else:
        body = body.rstrip("\n") + "\n" + block
    new_text = text[:match.start(2)] + body + text[match.end(2):]
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)


def _porcelain_paths(lines):
    paths = []
    for line in lines or []:
        if len(line) < 4:
            continue
        path = line[3:].strip().replace("\\", "/")
        if " -> " in path:
            path = path.split(" -> ", 1)[1].strip()
        paths.append(path)
    return paths


def dirty_bind_blockers(impact_paths, porcelain_lines):
    """Paths that must be clean before --bind writes a digest."""
    dirty = set(_porcelain_paths(porcelain_lines))
    watched = [_repo_rel(path) for path in impact_paths] + list(BIND_GUARD_FILES)
    return [path for path in watched if path in dirty]


def git_porcelain():
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=REPO, capture_output=True, text=True, check=True,
        )
    except Exception:
        return []
    return proc.stdout.splitlines()


def bind_entry(entry):
    """Compute evidence_bind when the stored verdict still earns PASS."""
    if not need_runtime_bind(entry):
        raise BindRefused("%s does not need runtime bind" % entry.get("id"), code=1)
    caps, change_impact = load_capability_registry()
    blockers = dirty_bind_blockers(
        impact_files(change_impact, impact_capability_ids(entry, caps)),
        git_porcelain(),
    )
    if blockers:
        raise BindRefused("refuse bind; dirty inputs: %s" % ", ".join(blockers), code=2)
    for path in acceptance.local_artifact_paths(_evidence_strings(entry)):
        if not os.path.isfile(path):
            raise BindRefused("missing artifact %s" % path, code=1)
    status = entry.get("status")
    if status in ("PASS", "HUMAN_PASS", "PARTIAL_PASS") and entry.get("type") == "device":
        rows = entry.get("acceptance") or []
        provenance = entry.get("scope") != "COMPONENT"
        earned = acceptance.evaluate(
            entry.get("scope"), rows,
            terminal_success=entry.get("terminal_success") or (),
            terminal_observed=entry.get("terminal_observed") or (),
            failure_observed=entry.get("failure_observed") or (),
            ended_by=entry.get("ended_by"),
            regressions=entry.get("regressions") or [],
            require_provenance=provenance)["verdict"]
        if earned != "PASS":
            raise BindRefused("%s: evidence earns %s, cannot bind" % (entry.get("id"), earned), code=1)
    return compute_bind(entry)


def apply_stale(doc, dry_run=False):
    """Requeue rows whose evidence_bind no longer matches the tree."""
    _, change_impact = load_capability_registry()
    changed = []
    for entry in doc["tests"]:
        problems = bind_problems(entry, change_impact)
        if not problems:
            continue
        note = "STALE_BIND %s" % problems[0].split("STALE_BIND", 1)[-1].strip()
        changed.append(entry["id"])
        if dry_run:
            continue
        entry["status"] = "NOT_RUN"
        evidence = list(entry.get("evidence") or [])
        evidence.append(note)
        entry["evidence"] = evidence
        entry.pop("evidence_bind", None)
    return changed


def _append_evidence_note(body, note):
    line = "      - \"%s\"\n" % note.replace('"', "'")
    if re.search(r"^    evidence:\n", body, re.M):
        return re.sub(r"^    evidence:\n", "    evidence:\n" + line, body, count=1, flags=re.M)
    inline = re.search(r"^    evidence: \[(.*)\]\s*$", body, re.M)
    if inline:
        inner = inline.group(1).strip()
        added = '"%s"' % note.replace('"', "'")
        repl = "    evidence: [%s]" % (added if not inner else "%s, %s" % (inner, added))
        return body[:inline.start()] + repl + body[inline.end():]
    block = "    evidence:\n" + line
    anchors = [a for a in ("    related:", "    last_run:", "    covers:") if a in body]
    if anchors:
        insert_at = min(body.index(a) for a in anchors)
        return body[:insert_at] + block + body[insert_at:]
    return body.rstrip("\n") + "\n" + block


def persist_stale_row(entry_id, note, path=MATRIX):
    """Set one matrix row to NOT_RUN and drop its evidence_bind. Never writes PASS."""
    text = io.open(path, encoding="utf-8").read()
    pattern = re.compile(
        r"(  - id: %s\r?\n)(.*?)(?=\n  - id: |\n# ----|\Z)" % re.escape(entry_id),
        re.DOTALL)
    match = pattern.search(text)
    if not match:
        raise SystemExit("no such test: %s" % entry_id)
    body = match.group(2)
    body = re.sub(r"^    evidence_bind:\n(?:    [ ].*\n)*", "", body, count=1, flags=re.M)
    if not re.search(r"^    status: ", body, re.M):
        raise SystemExit("%s: no status line" % entry_id)
    body = re.sub(r"^    status: .*$", "    status: NOT_RUN", body, count=1, flags=re.M)
    body = _append_evidence_note(body, note)
    new_text = text[:match.start(2)] + body + text[match.end(2):]
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)


def protection_audit(doc=None, registry_path=CAPABILITIES):
    """Every supported capability must keep PASS regression protection."""
    caps, change = load_capability_registry(registry_path)
    requirements = capability_requirements(registry_path)
    doc = doc or load()
    by_id = {t["id"]: t for t in doc["tests"]}
    cover_pass = {}
    for entry in doc["tests"]:
        if entry.get("status") not in PASSING:
            continue
        if not _bind_current(entry, change):
            continue
        for key in entry.get("covers") or []:
            cover_pass.setdefault(key, []).append(entry["id"])
    regression = open(FEATURE_PRESENCE, encoding="utf-8").read() if os.path.isfile(FEATURE_PRESENCE) else ""
    index = _source_index()
    problems = []
    for path in unmapped_product_files(change):
        problems.append("UNKNOWN_IMPACT %s" % path)
    for cap_id, cap in sorted(caps.items()):
        if cap.get("verified") == "unsupported":
            continue
        protected = set(cover_pass.get(cap_id, []))
        for tid in cap.get("protected_by") or []:
            if tid not in by_id:
                problems.append("%s: protected_by references unknown test %s" % (cap_id, tid))
            else:
                ent = by_id[tid]
                if ent.get("status") in PASSING and _bind_current(ent, change):
                    protected.add(tid)
                elif cap.get("verified") == "human" and ent.get("status") in AWAITING_HUMAN:
                    protected.add("queued:" + tid)
        rt = cap.get("regression_test")
        runtime_bar = _needs_runtime_protection(cap, cap_id, requirements)
        if rt:
            if rt not in regression:
                problems.append("%s: regression_test %r missing from FeaturePresenceRegressionTest"
                                % (cap_id, rt))
            elif not runtime_bar:
                protected.add("regression:" + rt)
        if not protected:
            if runtime_bar and rt and rt in regression:
                problems.append("%s: regression_test alone insufficient for device/flow/E2E"
                                % cap_id)
            else:
                problems.append("%s: no bind-current PASS cover, protected_by, or regression_test"
                                % cap_id)
    for area, spec in _areas(change):
        for rid in spec.get("retest") or []:
            if rid not in caps:
                problems.append("change_impact.%s retest unknown capability %r" % (area, rid))
        for comp in spec.get("components") or []:
            paths = [p for p in index.get(comp, []) if _product_source(p)]
            if not paths:
                problems.append("change_impact.%s component %r resolves to no source file"
                                % (area, comp))
    return problems


def protection_summary(doc=None):
    """Counts for reporting: total supported, fully protected, gaps."""
    caps, _ = load_capability_registry()
    doc = doc or load()
    problems = protection_audit(doc)
    unprotected = {p.split(":")[0] for p in problems if "no PASS cover" in p}
    supported = [cid for cid, cap in caps.items() if cap.get("verified") != "unsupported"]
    return {
        "supported": len(supported),
        "unprotected": len(unprotected),
        "problems": len(problems),
    }


# ---- validation ------------------------------------------------------------------------------


def validate(doc=None, requirements=None, audit_protection=None, extra_unmapped=None):
    """Everything that would make the registry lie. Returns a list of problems."""
    _BIND_COMPUTE_CACHE.clear()
    doc = doc or load()
    found = []
    seen = set()
    ids = {entry.get("id") for entry in doc["tests"]}
    if requirements is None:
        try:
            requirements = capability_requirements()
        except Exception as exc:
            return ["capabilities.yaml unreadable: %r" % (exc,)]
    for key, need in sorted(requirements.items()):
        if need not in acceptance.SCOPES:
            found.append("capabilities.yaml: %s has required_scope %r" % (key, need))
    for entry in doc["tests"]:
        tid = entry.get("id", "<no id>")
        for field in REQUIRED:
            if field not in entry:
                found.append("%s: missing %s" % (tid, field))
        if tid in seen:
            found.append("%s: duplicate id" % tid)
        seen.add(tid)
        owner, status = entry.get("owner"), entry.get("status")
        if owner not in OWNERS:
            found.append("%s: owner %r is not one of %s" % (tid, owner, ", ".join(OWNERS)))
        if status not in STATUSES:
            found.append("%s: status %r is not one of %s" % (tid, status, ", ".join(STATUSES)))
        # A human owner with an autonomous status, or the reverse, means nobody knows who is
        # waiting for whom.
        if owner == "AUTONOMOUS" and status in ("HUMAN_REQUIRED", "HUMAN_PASS", "HUMAN_FAIL"):
            found.append("%s: AUTONOMOUS cannot be %s" % (tid, status))
        if owner in HUMAN_OWNERS and status in ("PASS", "FAIL"):
            found.append("%s: %s must use HUMAN_PASS/HUMAN_FAIL, not %s" % (tid, owner, status))
        if entry.get("status") == "PASS" and not entry.get("evidence"):
            found.append("%s: PASS with no evidence" % tid)
        if owner in HUMAN_OWNERS:
            found.extend(human_entry_problems(entry))
        found.extend(verdict_problems(entry, ids, requirements))
        if need_runtime_bind(entry):
            found.extend(bind_problems(entry))
    if audit_protection is None:
        audit_protection = requirements is None
    if audit_protection:
        found.extend(protection_audit(doc))
    else:
        for path in unmapped_product_files():
            found.append("UNKNOWN_IMPACT %s" % path)
    for path in extra_unmapped or []:
        found.append("UNKNOWN_IMPACT %s" % path)
    return found


def verdict_problems(entry, known_ids=(), requirements=None):
    """A stored verdict may not claim more than its scope and evidence earn.

    This is the 0.6.4 rule: intermediate health (launch, route, guidance, a clean manual
    stop) must never produce an END_TO_END PASS. Unit/sim verdicts keep the plain evidence
    rule — each assertion is already a checked criterion — while device flows and E2E
    verdicts must carry an acceptance matrix the evidence is checked against, with every
    observed row citing an artifact, not bare prose.
    """
    tid = entry.get("id", "<no id>")
    problems = []
    scope = entry.get("scope")
    status = entry.get("status")
    etype = entry.get("type")
    if scope not in acceptance.SCOPES:
        problems.append("%s: scope %r must be one of %s"
                        % (tid, scope, ", ".join(acceptance.SCOPES)))
        return problems
    for key in entry.get("covers") or []:
        if requirements is None or key not in requirements:
            problems.append("%s: covers unknown capability %r" % (tid, key))
        elif (acceptance.SCOPE_RANK[scope]
                < acceptance.SCOPE_RANK[requirements[key]]):
            problems.append(
                "%s: scope laundering: covers %s (requires %s) at %s"
                % (tid, key, requirements[key], scope))
    rows = entry.get("acceptance") or []
    for row in rows:
        if not isinstance(row, dict) or "criterion" not in row or "required" not in row:
            problems.append("%s: acceptance rows need criterion/required/observed/evidence"
                            % tid)
            break
    device_flow_verdict = (status in VERDICT_STATUSES and scope != "COMPONENT"
                           and etype == "device")
    if device_flow_verdict and not rows:
        problems.append("%s: %s %s device verdict needs an acceptance matrix" % (tid, scope, status))
    if device_flow_verdict and rows:
        earned = acceptance.evaluate(
            scope, rows,
            terminal_success=entry.get("terminal_success") or (),
            terminal_observed=entry.get("terminal_observed") or (),
            failure_observed=entry.get("failure_observed") or (),
            ended_by=entry.get("ended_by"),
            regressions=entry.get("regressions") or [],
            require_provenance=True)["verdict"]
        if status in ("PASS", "HUMAN_PASS", "PARTIAL_PASS") and earned != "PASS":
            problems.append("%s: stored %s but the evidence earns %s" % (tid, status, earned))
        if status == "FAIL" and earned == "PASS":
            problems.append("%s: stored FAIL but no criterion was violated" % tid)
    if scope == "END_TO_END":
        if not entry.get("terminal_success"):
            problems.append("%s: END_TO_END must declare terminal_success" % tid)
    elif entry.get("terminal_success"):
        problems.append("%s: only END_TO_END may declare terminal_success" % tid)
    if device_flow_verdict and entry.get("ended_by") not in acceptance.ENDED_BY:
        problems.append("%s: %s device verdict needs ended_by natural/manual/timeout/crash"
                        % (tid, scope))
    if (status in VERDICT_STATUSES and scope == "END_TO_END" and rows
            and entry.get("capability") in acceptance.MIN_CRITERIA):
        want = set(acceptance.MIN_CRITERIA[entry["capability"]])
        have = {row.get("criterion") for row in rows if isinstance(row, dict)}
        if not want <= have:
            problems.append("%s: E2E %s verdict misses criteria: %s"
                            % (tid, entry["capability"], ", ".join(sorted(want - have))))
    if entry.get("regressions") and status in ("PASS", "PARTIAL_PASS", "HUMAN_PASS"):
        problems.append("%s: stored %s with unexplained regressions" % (tid, status))
    if status in ("FAIL", "HUMAN_FAIL", "INCOMPLETE") and not entry.get("evidence"):
        problems.append("%s: %s with no evidence" % (tid, status))
    if status == "PARTIAL_PASS":
        if not entry.get("parent"):
            problems.append("%s: PARTIAL_PASS needs a parent entry" % tid)
        elif known_ids and entry["parent"] not in known_ids:
            problems.append("%s: parent %r does not exist" % (tid, entry["parent"]))
    if scope != "END_TO_END":
        claimed = "%s %s" % (entry.get("id", ""), entry.get("name", ""))
        if re.search(r"e2e|end.to.end", claimed, re.I):
            problems.append("%s: a %s entry must not claim end-to-end in id/name"
                            % (tid, scope))
        if (re.search(r"baseline", claimed, re.I)
                and not re.search(r"mid.route|component|intermediate", claimed, re.I)):
            problems.append("%s: unscoped 'baseline' claim at %s - qualify it (e.g. mid-route)"
                            % (tid, scope))
    return problems


def human_entry_problems(entry):
    """What a queued human item must carry so the person never has to come back and ask."""
    tid = entry["id"]
    problems = []
    blocker = entry.get("automation_blocker")
    if blocker not in AUTOMATION_BLOCKERS:
        problems.append(
            "%s: HUMAN_REQUIRED needs automation_blocker in %s"
            % (tid, ", ".join(AUTOMATION_BLOCKERS)))
    if not entry.get("human_reason"):
        problems.append("%s: no human_reason - say why an agent cannot do it" % tid)
    else:
        reason = entry["human_reason"]
        if _INVALID_REASON.search(reason) and not _VALID_REASON.search(reason):
            problems.append(
                "%s: human_reason is only ADB/taps/emulator/logs/screenshots/"
                "inconvenience - that is not a legal automation blocker" % tid)
    if not entry.get("procedure"):
        problems.append("%s: no procedure" % tid)
    if not entry.get("pass_criteria"):
        problems.append("%s: no pass_criteria" % tid)
    if entry["owner"] == "HUMAN_DECISION":
        if not entry.get("decision_options"):
            problems.append("%s: a decision with no options to choose between" % tid)
        if not entry.get("quantified"):
            problems.append("%s: a decision with nothing measured - reduce it to evidence first" % tid)
    else:
        if not entry.get("returns"):
            problems.append("%s: does not say what result to report back" % tid)
        if not entry.get("autonomous_evidence"):
            problems.append("%s: no record of what was already established without a human" % tid)
        if not entry.get("remaining_uncertainty"):
            problems.append("%s: does not say what is still unknown" % tid)
    return problems


# ---- the gate --------------------------------------------------------------------------------


def clean_passes():
    try:
        with io.open(PASSES, encoding="utf-8") as handle:
            return int(json.load(handle).get("consecutive_clean", 0))
    except Exception:
        return 0


def record_pass(clean):
    """A discovery/review pass that found no autonomous work increments; anything else resets."""
    count = clean_passes() + 1 if clean else 0
    os.makedirs(os.path.dirname(PASSES), exist_ok=True)
    with io.open(PASSES, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({
            "consecutive_clean": count,
            "updated": datetime.date.today().isoformat(),
            "note": "Two consecutive clean passes are required before HUMAN_VALIDATION_READY. "
                    "Finding any autonomous work resets this to 0.",
        }, handle, indent=2)
        handle.write("\n")
    return count


def gate(doc=None, passes=None, requirements=None):
    """HUMAN_VALIDATION_READY, and every reason it is not.

    Deliberately not `AUTONOMOUS_ACTION_AVAILABLE = NO`, which was a judgement call about whether
    anything was left. This is a property of the registry: either every autonomous test has
    settled and every remaining case names a human, or it has not.
    """
    doc = doc or load()
    entries = doc["tests"]
    passes = clean_passes() if passes is None else passes
    reasons = []

    problems = validate(doc)
    if problems:
        reasons.append("the registry does not validate: %d problem(s)" % len(problems))

    unsettled = [e for e in entries
                 if e["owner"] == "AUTONOMOUS" and e["status"] not in AUTONOMOUS_SETTLED]
    for entry in unsettled:
        reasons.append("%s is AUTONOMOUS and %s - run it or fix it" % (entry["id"], entry["status"]))

    for entry in entries:
        if entry["status"] == "BLOCKED_EXTERNAL" and not entry.get("evidence"):
            reasons.append("%s is BLOCKED_EXTERNAL with no evidence for the block" % entry["id"])

    for key, cov in coverage(doc, requirements).items():
        if cov["verdict"] == "UNCOVERED":
            reasons.append("%s requires %s but has no passing cover (linked: %s)"
                           % (key, cov["required_scope"],
                              ", ".join(cov["linked"]) or "none"))

    if passes < 2:
        reasons.append("only %d consecutive clean discovery/review pass(es); two are required" % passes)

    queued = [e for e in entries if e["status"] in AWAITING_HUMAN]
    return {
        "HUMAN_VALIDATION_READY": "FALSE" if reasons else "TRUE",
        "reasons": reasons,
        "queued_human_items": len(queued),
        "autonomous_unsettled": len(unsettled),
        "consecutive_clean_passes": passes,
    }


def autonomous_work(doc=None):
    """Autonomous tests that are not settled. This is what the frontier picks up as work.

    HUMAN_REQUIRED entries are deliberately absent: they are queued, and a queued item is not a
    reason to stop.
    """
    return [e for e in (doc or load())["tests"]
            if e["owner"] == "AUTONOMOUS" and e["status"] not in AUTONOMOUS_SETTLED]


PASSING = ("PASS", "HUMAN_PASS", "PARTIAL_PASS")


def coverage(doc=None, requirements=None):
    """Per-requirement scope coverage: required_scope vs the best passing cover.

    A requirement counts as covered only by a passing verdict scoped at or above its bar —
    lower-scope passes never satisfy it, however green. QUEUED means a human validation is
    already linked; only UNCOVERED with no queued human validation holds the gate shut.
    """
    doc = doc or load()
    if requirements is None:
        requirements = capability_requirements()
    entries = doc["tests"]
    out = {}
    for key, need in sorted(requirements.items()):
        if need == "COMPONENT" or need not in acceptance.SCOPES:
            continue
        linked = [e for e in entries if key in (e.get("covers") or [])]
        passing = sorted(e["id"] for e in linked
                         if e.get("status") in PASSING
                         and _bind_current(e)
                         and acceptance.SCOPE_RANK.get(e.get("scope"), -1)
                         >= acceptance.SCOPE_RANK[need])
        queued = sorted(e["id"] for e in linked
                        if e.get("owner") in HUMAN_OWNERS
                        and e.get("status") in AWAITING_HUMAN)
        verdict = "COVERED" if passing else ("QUEUED" if queued else "UNCOVERED")
        out[key] = {"required_scope": need, "passing": passing,
                    "queued_human": queued,
                    "linked": sorted(e["id"] for e in linked), "verdict": verdict}
    return out


# ---- verdict self-check --------------------------------------------------------------------------


def verdict_report(entry):
    """What verdict the evidence for one entry actually earns (§8 self-check).

    Run this before reporting PASS/STABLE on anything the matrix tracks. A stored verdict
    above the earned one is a harness violation, not a result.
    """
    rows = entry.get("acceptance") or []
    provenance = entry.get("scope") != "COMPONENT" and entry.get("type") == "device"
    result = acceptance.evaluate(
        entry.get("scope"), rows,
        terminal_success=entry.get("terminal_success") or (),
        terminal_observed=entry.get("terminal_observed") or (),
        failure_observed=entry.get("failure_observed") or (),
        ended_by=entry.get("ended_by"),
        regressions=entry.get("regressions") or [],
        require_provenance=provenance)
    lines = ["%s — %s" % (entry.get("id"), entry.get("name")),
             "stored: %s at %s (ended_by=%s)" % (entry.get("status"), entry.get("scope"),
                                                entry.get("ended_by")),
             "earned: %s" % result["verdict"]]
    for reason in result["reasons"]:
        lines.append("  - %s" % reason)
    if rows:
        lines.append("")
        lines.append(result["table"])
    return "\n".join(lines)


# ---- generated views ---------------------------------------------------------------------


def counts(entries):
    out = {s: 0 for s in STATUSES}
    for entry in entries:
        out[entry["status"]] = out.get(entry["status"], 0) + 1
    return out


def _display_status(entry):
    """YAML keeps PASS; a stale bind must not render as a current pass."""
    if entry.get("status") in RUNTIME_BIND_STATUSES and bind_problems(entry):
        return "STALE"
    return entry.get("status")


def status_doc(doc=None):
    doc = doc or load()
    entries = doc["tests"]
    tally = counts(entries)
    g = gate(doc)
    blocking_fail = [e for e in entries
                     if e.get("release_blocking") and e["status"] in ("FAIL", "HUMAN_FAIL")]

    lines = []
    lines.append("# Test status")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --status`. **Do not edit by hand** — the registry "
                 "is the source of truth and this is a view of it.")
    lines.append("")
    lines.append("Updated %s · %d tests" % (doc["meta"]["updated"], len(entries)))
    lines.append("")
    lines.append("| | |")
    lines.append("| --- | --- |")
    lines.append("| autonomous PASS | %d |" % tally.get("PASS", 0))
    lines.append("| autonomous FAIL | %d |" % tally.get("FAIL", 0))
    lines.append("| incomplete | %d |" % tally.get("INCOMPLETE", 0))
    lines.append("| partial pass | %d |" % tally.get("PARTIAL_PASS", 0))
    lines.append("| not run | %d |" % tally.get("NOT_RUN", 0))
    lines.append("| human required | %d |" % tally.get("HUMAN_REQUIRED", 0))
    lines.append("| human pass | %d |" % tally.get("HUMAN_PASS", 0))
    lines.append("| human fail | %d |" % tally.get("HUMAN_FAIL", 0))
    lines.append("| blocked external | %d |" % tally.get("BLOCKED_EXTERNAL", 0))
    lines.append("| not applicable | %d |" % tally.get("NOT_APPLICABLE", 0))
    lines.append("| **release-blocking failures** | **%d** |" % len(blocking_fail))
    lines.append("")
    lines.append("**HUMAN_VALIDATION_READY = %s**" % g["HUMAN_VALIDATION_READY"])
    if g["reasons"]:
        lines.append("")
        for reason in g["reasons"]:
            lines.append("- %s" % reason)
    lines.append("")

    lines.append("## By capability")
    lines.append("")
    lines.append("| Capability | Tests | Passing | Awaiting a human | Not run |")
    lines.append("| --- | --- | --- | --- | --- |")
    caps = {}
    for entry in entries:
        caps.setdefault(entry["capability"], []).append(entry)
    for cap in sorted(caps):
        group = caps[cap]
        lines.append("| %s | %d | %d | %d | %d |" % (
            cap, len(group),
            sum(1 for e in group if e["status"] in ("PASS", "HUMAN_PASS")),
            sum(1 for e in group if e["status"] in AWAITING_HUMAN),
            sum(1 for e in group if e["status"] == "NOT_RUN"),
        ))
    lines.append("")

    lines.append("## Every test")
    lines.append("")
    lines.append("| ID | Capability | Test | Owner | Status | Release blocking | Evidence |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for entry in sorted(entries, key=lambda e: (e["capability"], e["id"])):
        evidence = entry.get("evidence") or entry.get("autonomous_evidence") or []
        first = evidence[0] if evidence else "—"
        if len(first) > 90:
            first = first[:87] + "…"
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            entry["id"], entry["capability"], entry["name"], entry["owner"],
            _display_status(entry),
            "yes" if entry.get("release_blocking") else "no",
            first.replace("|", "\\|"),
        ))
    lines.append("")
    return "\n".join(lines)


def packet_doc(doc=None):
    doc = doc or load()
    entries = [e for e in doc["tests"] if e["status"] in AWAITING_HUMAN]
    g = gate(doc)

    groups = [
        ("A. Physical tests (LOCAL_DEVICE_REQUIRED)", "HUMAN_PHYSICAL"),
        ("B. Account and real-service tests", "HUMAN_ACCOUNT"),
        ("C. Required credentials", "HUMAN_CREDENTIAL"),
        ("D. Product decisions", "HUMAN_DECISION"),
        ("E. External blockers", "EXTERNAL_RESOURCE"),
    ]

    lines = []
    lines.append("# Human validation packet")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --packet`. **Do not edit by hand.**")
    lines.append("")
    if g["HUMAN_VALIDATION_READY"] == "TRUE":
        lines.append("Everything an agent could do has been done. What follows is the whole of what "
                     "needs a person — **in one batch, to be handled in one sitting**, rather than "
                     "one interruption per test.")
    else:
        lines.append("> **Not ready yet.** This is a preview of the queue; autonomous work remains:")
        lines.append(">")
        for reason in g["reasons"][:8]:
            lines.append("> - %s" % reason)
    lines.append("")
    lines.append("%d item(s) queued." % len(entries))
    lines.append("")
    lines.append("Physical-device cases are also collected in "
                 "[LOCAL_DEVICE_REQUIRED.md](LOCAL_DEVICE_REQUIRED.md).")
    lines.append("")

    for title, owner in groups:
        group = [e for e in entries if e["owner"] == owner]
        if not group:
            continue
        lines.append("## %s" % title)
        lines.append("")
        for entry in group:
            lines.extend(_entry_section(entry, local_device=(owner == "HUMAN_PHYSICAL")))
    lines.append("---")
    lines.append("")
    lines.append("When you have results for any of these, give me all of them at once. They will be "
                 "applied together, every resulting failure triaged together, and every fix that "
                 "follows made in one autonomous cycle before anything is asked of you again "
                 "([harness/PHASES.md](harness/PHASES.md)).")
    lines.append("")
    return "\n".join(lines)


def _entry_section(entry, local_device=False):
    lines = []
    label = "LOCAL_DEVICE_REQUIRED — " if local_device else ""
    lines.append("### %s%s — %s" % (label, entry["id"], entry["name"]))
    lines.append("")
    if local_device:
        lines.append("**Tag:** `LOCAL_DEVICE_REQUIRED`")
        lines.append("")
    lines.append("**Why this needs you.** %s" % entry.get("human_reason", ""))
    lines.append("")
    if entry.get("automation_blocker"):
        lines.append("**Automation blocker:** `%s`" % entry["automation_blocker"])
        lines.append("")
    if entry.get("autonomous_evidence"):
        lines.append("**Already established without you:**")
        lines.append("")
        for item in entry["autonomous_evidence"]:
            lines.append("- %s" % item)
        lines.append("")
    if entry.get("quantified"):
        lines.append("**Measured, so this is a choice and not a question:**")
        lines.append("")
        for item in entry["quantified"]:
            lines.append("- %s" % item)
        lines.append("")
    if entry.get("decision_options"):
        lines.append("**Options:**")
        lines.append("")
        for item in entry["decision_options"]:
            lines.append("- %s" % item)
        lines.append("")
    if entry.get("prerequisites"):
        lines.append("**You will need:** %s" % "; ".join(entry["prerequisites"]))
        lines.append("")
    lines.append("**What to do:**")
    lines.append("")
    for i, step in enumerate(entry["procedure"], 1):
        lines.append("%d. %s" % (i, step))
    lines.append("")
    lines.append("**It passes if:**")
    lines.append("")
    for item in entry["pass_criteria"]:
        lines.append("- %s" % item)
    lines.append("")
    if entry.get("returns"):
        lines.append("**Tell me back:** %s" % "; ".join(entry["returns"]))
        lines.append("")
    if entry.get("remaining_uncertainty"):
        lines.append("**Still unknown until you do:** %s"
                     % "; ".join(entry["remaining_uncertainty"]))
        lines.append("")
    if entry.get("release_blocking"):
        lines.append("*Release-blocking.*")
        lines.append("")
    return lines


def local_device_doc(doc=None):
    """One consolidated batch of every physical-device case for local execution."""
    doc = doc or load()
    entries = [e for e in doc["tests"]
               if e["status"] in AWAITING_HUMAN and e["owner"] == "HUMAN_PHYSICAL"]
    g = gate(doc)
    lines = []
    lines.append("# LOCAL_DEVICE_REQUIRED — consolidated device-test batch")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --local-device`. **Do not edit by hand.**")
    lines.append("")
    lines.append("Every case below **requires a physical Android device** (and usually a real "
                 "cabin / GPS / human voice). Cloud agents must not block on these: record them "
                 "here and continue autonomous work.")
    lines.append("")
    if g["HUMAN_VALIDATION_READY"] == "TRUE":
        lines.append("Autonomous cloud work for the current frontier is settled. Run this batch "
                     "locally in one sitting.")
    else:
        lines.append("> Autonomous work may still be open. Prefer finishing cloud-verifiable work "
                     "first; this file is still the device queue.")
        lines.append(">")
        for reason in g["reasons"][:8]:
            lines.append("> - %s" % reason)
    lines.append("")
    lines.append("**%d LOCAL_DEVICE_REQUIRED item(s).**" % len(entries))
    lines.append("")
    lines.append("Install tip (from a cloud-built APK, when one exists):")
    lines.append("")
    lines.append("```bash")
    lines.append("adb install -r \"$NOVA_BUILD_DIR/app/outputs/apk/debug/app-debug.apk\"")
    lines.append("adb logcat -s NovaVoice:D")
    lines.append("```")
    lines.append("")
    for entry in entries:
        lines.extend(_entry_section(entry, local_device=True))
    lines.append("---")
    lines.append("")
    lines.append("Return every result together. Non-device human items remain in "
                 "[HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).")
    lines.append("")
    return "\n".join(lines)


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return path


# ---- selftest --------------------------------------------------------------------------------


def _entry(**over):
    base = {
        "id": "X-001", "capability": "c", "name": "n", "purpose": "p", "type": "unit",
        "owner": "AUTONOMOUS", "status": "PASS", "scope": "COMPONENT",
        "release_blocking": False,
        "prerequisites": [], "procedure": ["do it"], "pass_criteria": ["it works"],
        "evidence": ["seen"],
    }
    base.update(over)
    return base


def _rows(*names, **kw):
    observed = kw.get("observed", True)
    return [{"criterion": name, "required": True, "observed": observed,
             "evidence": kw.get("evidence", "log:NovaVoice/%s" % name)}
            for name in names]


def _bound(entry, change_impact=None):
    """Selftest helper: attach a fresh evidence_bind when the row needs one."""
    if not need_runtime_bind(entry):
        return entry
    row = dict(entry)
    row["evidence_bind"] = compute_bind(row, change_impact)
    return row


def selftest():
    results = []

    def check(name, ok):
        results.append((name, ok))

    # The rule this harness exists for.
    doc = {"meta": {"updated": "x"}, "tests": [
        _entry(id="A-1"),
        _entry(id="H-1", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED", evidence=None,
               human_reason="a person must drive the vehicle in a real cabin",
               automation_blocker="physical_world",
               returns=["what happened"],
               autonomous_evidence=["everything short of driving"],
               remaining_uncertainty=["the road"]),
    ]}
    check("a queued human item is not autonomous work", autonomous_work(doc) == [])
    # requirements={} isolates the batching mechanics; coverage has its own checks below.
    check("the gate opens with human items queued",
          gate(doc, passes=2, requirements={})["HUMAN_VALIDATION_READY"] == "TRUE")

    # An autonomous test that has not run is work, and holds the gate shut.
    doc2 = {"meta": {"updated": "x"}, "tests": [_entry(id="A-2", status="NOT_RUN", evidence=[])]}
    check("a NOT_RUN autonomous test is work", len(autonomous_work(doc2)) == 1)
    check("and holds the gate shut", gate(doc2, passes=2)["HUMAN_VALIDATION_READY"] == "FALSE")

    # An autonomous failure is work, not a reason to call a human.
    doc3 = {"meta": {"updated": "x"}, "tests": [_entry(id="A-3", status="FAIL", evidence=["red"])]}
    check("a FAIL autonomous test is work", len(autonomous_work(doc3)) == 1)
    check("and holds the gate shut", gate(doc3, passes=2)["HUMAN_VALIDATION_READY"] == "FALSE")

    # One clean pass is not two.
    check("one clean pass is not enough", gate(doc, passes=1)["HUMAN_VALIDATION_READY"] == "FALSE")
    check("zero clean passes is not enough", gate(doc, passes=0)["HUMAN_VALIDATION_READY"] == "FALSE")

    # A human item that would send the person away uninformed.
    thin = _entry(id="H-2", owner="HUMAN_ACCOUNT", status="HUMAN_REQUIRED", evidence=None)
    check("a human item with no reason or return is rejected",
          len(human_entry_problems(thin)) >= 3)

    # A decision with no numbers is not ready to be asked.
    undecided = _entry(id="D-1", owner="HUMAN_DECISION", status="HUMAN_REQUIRED", evidence=None,
                       human_reason="policy choice the owner must make",
                       automation_blocker="subjective_perception",
                       decision_options=["A", "B"])
    check("a decision with nothing measured is rejected",
          any("reduce it to evidence" in p for p in human_entry_problems(undecided)))
    illegal = _entry(
        id="H-adb", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED", evidence=None,
        human_reason="requires ADB and watching logs",
        automation_blocker="physical_world",
        returns=["ok"], autonomous_evidence=["e"], remaining_uncertainty=["u"])
    check("ADB/log watching is not a legal human_reason",
          any("not a legal automation blocker" in p for p in human_entry_problems(illegal)))
    missing_blocker = _entry(
        id="H-nb", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED", evidence=None,
        human_reason="a person must drive the vehicle",
        returns=["ok"], autonomous_evidence=["e"], remaining_uncertainty=["u"])
    check("HUMAN_REQUIRED without automation_blocker is rejected",
          any("automation_blocker" in p for p in human_entry_problems(missing_blocker)))

    # Owner and status must agree about who is waiting.
    mixed = {"meta": {"updated": "x"}, "tests": [
        _entry(id="M-1", owner="AUTONOMOUS", status="HUMAN_REQUIRED"),
        _entry(id="M-2", owner="HUMAN_PHYSICAL", status="PASS", human_reason="r",
               returns=["r"], autonomous_evidence=["e"], remaining_uncertainty=["u"]),
    ]}
    problems = validate(mixed)
    check("AUTONOMOUS cannot be HUMAN_REQUIRED",
          any("cannot be HUMAN_REQUIRED" in p for p in problems))
    check("a human owner cannot report a bare PASS",
          any("HUMAN_PASS/HUMAN_FAIL" in p for p in problems))

    # PASS must carry evidence.
    check("PASS with no evidence is rejected",
          any("PASS with no evidence" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="E-1", evidence=[])]})))

    # The 0.6.4 rule: intermediate health must never produce an END_TO_END PASS.
    e2e_overclaim = _entry(
        id="V-1", capability="navigation", type="device", scope="END_TO_END",
        terminal_success=["arrival_callback"],
        acceptance=_rows("navigation_start", "route_resolved", "guidance_progress"),
        ended_by="manual")
    check("an E2E PASS without the terminal state is rejected",
          any("earns INCOMPLETE" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [e2e_overclaim]})))
    mid_ok = _bound(_entry(
        id="V-2", type="device", scope="INTERMEDIATE_FLOW",
        acceptance=_rows("navigation_start", "route_resolved", "guidance_progress"),
        ended_by="manual"))
    check("the same evidence as an intermediate PASS is accepted",
          validate({"meta": {"updated": "x"}, "tests": [mid_ok]}) == [])
    check("INCOMPLETE is unsettled work",
          len(autonomous_work({"meta": {"updated": "x"},
                               "tests": [_entry(id="V-3", status="INCOMPLETE")]})) == 1)
    check("INCOMPLETE with no evidence is rejected",
          any("INCOMPLETE with no evidence" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="V-4", status="INCOMPLETE",
                                                  evidence=[])]})))
    check("PARTIAL_PASS without a parent is rejected",
          any("needs a parent" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="V-5", status="PARTIAL_PASS")]})))
    check("PARTIAL_PASS with a parent is settled",
          autonomous_work({"meta": {"updated": "x"}, "tests": [
              _entry(id="V-6a", status="INCOMPLETE", evidence=["partial run"]),
              _entry(id="V-6b", status="PARTIAL_PASS", parent="V-6a"),
          ]}) == [_entry(id="V-6a", status="INCOMPLETE", evidence=["partial run"])])
    check("an unscoped baseline name below E2E is rejected",
          any("unscoped 'baseline'" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-7", name="Stable navigation baseline")]})))
    check("E2E without declared terminal states is rejected",
          any("must declare terminal_success" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-8", type="device", scope="END_TO_END",
                         status="NOT_RUN", evidence=[])]})))
    check("PASS with unexplained regressions is rejected",
          any("unexplained regressions" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-9", regressions=["15 m -> 3.7 km"])]})))
    check("FAIL with no evidence is rejected",
          any("FAIL with no evidence" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-10", status="FAIL", evidence=[])]})))
    check("a device flow verdict without ended_by is rejected",
          any("needs ended_by" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-11", type="device", scope="INTERMEDIATE_FLOW",
                         acceptance=_rows("navigation_start"))]})))

    # Scope laundering: the registry owns required_scope; a test below the bar cannot
    # cover the requirement, however it is labelled.
    reqs = {"drive.task": "END_TO_END", "guide.live": "INTERMEDIATE_FLOW"}
    launder = {"meta": {"updated": "x"}, "tests": [
        _entry(id="L-1", type="device", scope="INTERMEDIATE_FLOW",
               covers=["drive.task"],
               acceptance=_rows("navigation_start"), ended_by="manual")]}
    check("covering an E2E requirement at intermediate scope is rejected",
          any("scope laundering" in p for p in validate(launder, reqs)))
    check("covering an unknown capability is rejected",
          any("unknown capability" in p for p in validate(
              {"meta": {"updated": "x"}, "tests": [
                  _entry(id="L-2", covers=["no.such.thing"])]}, reqs)))
    honest = {"meta": {"updated": "x"}, "tests": [
        _bound(_entry(id="L-3", type="device", scope="END_TO_END", covers=["drive.task"],
                      terminal_success=["done"], terminal_observed=["done"],
                      acceptance=_rows("done"), ended_by="natural"))]}
    check("covering at sufficient scope is accepted",
          validate(honest, reqs) == [])
    check("an E2E requirement with only lower-scope passes is UNCOVERED",
          coverage({"meta": {"updated": "x"}, "tests": [
              _entry(id="L-4", type="device", scope="INTERMEDIATE_FLOW")]},
              reqs)["drive.task"]["verdict"] == "UNCOVERED")
    check("an E2E requirement with a passing E2E cover is COVERED",
          coverage(honest, reqs)["drive.task"]["verdict"] == "COVERED")
    human_queued = {"meta": {"updated": "x"}, "tests": [
        _entry(id="L-5", type="device", scope="END_TO_END", owner="HUMAN_PHYSICAL",
               status="HUMAN_REQUIRED", evidence=None, covers=["drive.task"],
               terminal_success=["done"], human_reason="a person must drive the vehicle",
               automation_blocker="physical_world",
               returns=["done?"], autonomous_evidence=["sim"], remaining_uncertainty=["road"])]}
    check("a human-queued requirement is QUEUED, not a gate reason",
          coverage(human_queued, reqs)["drive.task"]["verdict"] == "QUEUED"
          and not any("drive.task" in r
                      for r in gate(human_queued, passes=2, requirements=reqs)["reasons"]))
    check("an uncovered requirement holds the gate with a reason",
          any("drive.task" in r and "no passing cover" in r
              for r in gate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="L-6", status="NOT_RUN", evidence=[])]},
                  passes=2, requirements=reqs)["reasons"]))

    # Evidence provenance: prose about evidence is not evidence.
    prose = {"meta": {"updated": "x"}, "tests": [
        _entry(id="P-1", type="device", scope="END_TO_END",
               terminal_success=["done"], terminal_observed=["done"],
               acceptance=_rows("done", evidence="done was observed"),
               ended_by="natural")]}
    check("prose-only device E2E evidence cannot earn PASS",
          any("earns INCOMPLETE" in p for p in validate(prose, reqs)))
    backed = {"meta": {"updated": "x"}, "tests": [
        _bound(_entry(id="P-2", type="device", scope="END_TO_END",
                      terminal_success=["done"], terminal_observed=["done"],
                      acceptance=_rows("done", evidence="log:NovaVoice/done@12:00"),
                      ended_by="natural"))]}
    check("artifact-backed device E2E evidence can earn PASS",
          validate(backed, reqs) == [])

    # Adversarial evidence-bind cases A–N. In-memory fixtures; digests use the real tree.
    _, change_impact = load_capability_registry()
    caps, _ = load_capability_registry()
    nav_e2e = _entry(
        id="ADV-A", type="device", scope="END_TO_END", covers=["navigation.arrival_lifecycle"],
        terminal_success=["arrival_callback"], terminal_observed=["arrival_callback"],
        acceptance=_rows("arrival_callback", evidence="log:NovaVoice/nav@1"),
        ended_by="natural")
    nav_e2e["evidence_bind"] = compute_bind(nav_e2e, change_impact, use_cache=False)
    check("A bound row with unchanged digests stays valid",
          bind_problems(nav_e2e, change_impact) == [])
    nav_e2e["evidence_bind"]["code_digest"] = "sha256:dead"
    check("B navigation code_digest mismatch is stale",
          any("code_digest mismatch" in p for p in bind_problems(nav_e2e, change_impact)))
    proc_row = _entry(id="ADV-C", type="device", scope="INTERMEDIATE_FLOW",
                      covers=["navigation.arrival_lifecycle"],
                      acceptance=_rows("navigation_start"), ended_by="manual")
    proc_row["evidence_bind"] = compute_bind(proc_row, change_impact, use_cache=False)
    proc_row["procedure"] = ["weakened step"]
    check("C procedure change mismatches procedure_digest",
          any("procedure_digest mismatch" in p for p in bind_problems(proc_row, change_impact)))
    stale_doc = {"meta": {"updated": "x"}, "tests": [dict(proc_row)]}
    apply_stale(stale_doc)
    check("C apply-stale sets NOT_RUN and drops the bind",
          stale_doc["tests"][0]["status"] == "NOT_RUN"
          and "evidence_bind" not in stale_doc["tests"][0])
    wake_files = set(impact_files(change_impact, ["speech.wake_word"]))
    check("D files outside watched roots are not impact inputs",
          not any(path.endswith("README.md") or path.endswith("AGENTS.md") for path in wake_files))
    check("D unmapped scan ignores files outside watched roots",
          not any(path.endswith("README.md") for path in unmapped_product_files(change_impact)))
    arrival_files = set(_repo_rel(p) for p in impact_files(
        change_impact, ["navigation.arrival_lifecycle"]))
    speed_files = set(_repo_rel(p) for p in impact_files(
        change_impact, ["navigation.speed_hud"]))
    check("E shared navigation file is in every navigation-lifecycle cover",
          any(path.endswith("AmapDrivingPresentation.kt") for path in arrival_files)
          and any(path.endswith("AmapDrivingPresentation.kt") for path in speed_files)
          and not any(path.endswith("AmapDrivingPresentation.kt") for path in
                      (_repo_rel(p) for p in wake_files)))
    voice_row = _entry(id="ADV-E", type="device", scope="COMPONENT", covers=["speech.wake_word"])
    voice_row["evidence_bind"] = compute_bind(voice_row, change_impact, use_cache=False)
    voice_row["evidence_bind"]["code_digest"] = "sha256:dead"
    nav_only = _entry(id="ADV-E2", type="device", scope="COMPONENT", covers=["navigation.speed_hud"])
    nav_only["evidence_bind"] = compute_bind(nav_only, change_impact, use_cache=False)
    check("E voice drift does not stale an untouched navigation bind",
          bind_problems(voice_row, change_impact) and not bind_problems(nav_only, change_impact))
    harness_row = _entry(id="ADV-F", type="device", scope="COMPONENT", covers=["speech.wake_word"])
    plain = compute_bind(harness_row, change_impact, use_cache=False, harness_extra=b"")
    mutated = compute_bind(harness_row, change_impact, use_cache=False, harness_extra=b"mutated")
    harness_row["evidence_bind"] = dict(plain)
    harness_row["evidence_bind"]["harness_digest"] = mutated["harness_digest"]
    check("F harness byte change mismatches harness_digest",
          plain["harness_digest"] != mutated["harness_digest"]
          and any("harness_digest mismatch" in p
                  for p in bind_problems(harness_row, change_impact)))
    extra = "app/src/main/kotlin/com/novadrive/app/nav/NewUnmapped.kt"
    check("G unmapped watched-root file is UNKNOWN_IMPACT",
          extra in unmapped_product_files(change_impact, extra_files=[extra])
          and any("UNKNOWN_IMPACT" in p and "NewUnmapped.kt" in p
                  for p in validate(
                      {"meta": {"updated": "x"}, "tests": [
                          _entry(id="ADV-G0", status="NOT_RUN", evidence=[])]},
                      {"speech.wake_word": "COMPONENT"},
                      extra_unmapped=[extra])))
    missing_bind = _entry(id="ADV-H", type="device", scope="COMPONENT", covers=["speech.wake_word"])
    check("H device PASS without evidence_bind is STALE_BIND",
          any("missing evidence_bind" in p for p in bind_problems(missing_bind, change_impact)))
    shown = _entry(id="ADV-I", type="device", scope="COMPONENT", covers=["speech.wake_word"])
    shown["evidence_bind"] = compute_bind(shown, change_impact, use_cache=False)
    shown["evidence_bind"]["code_digest"] = "sha256:dead"
    rendered = status_doc({"meta": {"updated": "x"}, "tests": [shown]})
    check("I status view prints STALE while YAML status is PASS",
          "| ADV-I |" in rendered and "| STALE |" in rendered)
    restored = _entry(id="ADV-J", type="device", scope="COMPONENT", covers=["speech.wake_word"])
    restored["evidence_bind"] = compute_bind(restored, change_impact, use_cache=False)
    restored["evidence_bind"]["code_digest"] = "sha256:dead"
    apply_stale({"meta": {"updated": "x"}, "tests": [restored]})
    check("J apply-stale clears a stale PASS",
          restored["status"] == "NOT_RUN" and "evidence_bind" not in restored)
    rebound = dict(restored)
    rebound["status"] = "PASS"
    rebound["evidence_bind"] = compute_bind(rebound, change_impact, use_cache=False)
    check("J a fresh bind is valid again", bind_problems(rebound, change_impact) == [])
    check("K dirty impact path blocks bind",
          dirty_bind_blockers(
              [os.path.join(REPO, "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt")],
              [" M app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt"],
          ) == ["app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt"])
    check("K dirty file outside impact and harness guards does not block bind",
          dirty_bind_blockers(
              [os.path.join(REPO, "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt")],
              [" M README.md"],
          ) == [])
    reg_row = _entry(id="ADV-L", type="device", scope="COMPONENT",
                     covers=["navigation.arrival_lifecycle"])
    base_caps = copy.deepcopy(caps)
    first = compute_bind(reg_row, change_impact, use_cache=False, caps=base_caps)
    changed_caps = copy.deepcopy(base_caps)
    changed_caps["navigation.arrival_lifecycle"] = dict(changed_caps["navigation.arrival_lifecycle"])
    changed_caps["navigation.arrival_lifecycle"]["notes"] = "changed-for-bind-test"
    second = compute_bind(reg_row, change_impact, use_cache=False, caps=changed_caps)
    unrelated = copy.deepcopy(base_caps)
    if "speech.tts" in unrelated:
        unrelated["speech.tts"] = dict(unrelated["speech.tts"])
        unrelated["speech.tts"]["notes"] = "unrelated"
    third = compute_bind(reg_row, change_impact, use_cache=False, caps=unrelated)
    check("L covered capability text changes registry_digest only for that cover",
          first["registry_digest"] != second["registry_digest"]
          and first["registry_digest"] == third["registry_digest"])
    pb_caps = copy.deepcopy(caps)
    pb_row = _entry(id="ADV-PB", type="device", scope="COMPONENT")
    search = dict(pb_caps["navigation.search_place"])
    search["protected_by"] = list(search.get("protected_by") or []) + ["ADV-PB"]
    pb_caps["navigation.search_place"] = search
    with_pb = compute_bind(pb_row, change_impact, use_cache=False, caps=pb_caps)
    without_pb = compute_bind(
        _entry(id="ADV-PB2", type="device", scope="COMPONENT"),
        change_impact, use_cache=False, caps=pb_caps)
    pb_files = impact_files(change_impact, impact_capability_ids(pb_row, pb_caps))
    check("protected_by without covers still hashes that area",
          with_pb["code_digest"] != without_pb["code_digest"]
          and any(path.endswith("AmapDrivingPresentation.kt") for path in pb_files))
    missing_comp = {
        "voice_session_lifecycle": {"components": ["NoSuchTypeEver"], "retest": ["speech.wake_word"]},
        "watched_roots": [],
        "impact_exempt": [],
    }
    missing_names = []
    for area, spec in _areas(missing_comp):
        for comp in spec.get("components") or []:
            paths = [p for p in _source_index().get(comp, []) if _product_source(p)]
            if not paths:
                missing_names.append(comp)
    check("M deleted component name resolves to no source file",
          missing_names == ["NoSuchTypeEver"])
    art_row = _entry(
        id="ADV-M2", type="device", scope="END_TO_END", covers=["navigation.arrival_lifecycle"],
        evidence=["video:/no/such/nav-evidence.mp4"],
        terminal_success=["done"], terminal_observed=["done"],
        acceptance=_rows("done", evidence="video:/no/such/nav-evidence.mp4"), ended_by="natural")
    art_row["evidence_bind"] = compute_bind(art_row, change_impact, use_cache=False)
    check("M missing artifact path is STALE_BIND",
          any("missing artifact" in p for p in bind_problems(art_row, change_impact)))
    apk_row = _entry(id="ADV-APK", type="device", scope="COMPONENT", covers=["navigation.speed_hud"])
    apk_row["evidence_bind"] = compute_bind(apk_row, change_impact, use_cache=False)
    apk_row["evidence_bind"]["apk_digest"] = "sha256:dead"
    check("apk digest mismatch is stale when a digest was recorded",
          any("apk_digest mismatch" in p for p in bind_problems(apk_row, change_impact)))
    apk_row["evidence_bind"]["apk_digest"] = None
    check("null apk_digest does not stale when an APK appears later",
          not any("apk_digest" in p for p in bind_problems(apk_row, change_impact)))
    check("M resolver returns nothing for a deleted component name",
          _source_index().get("NoSuchTypeEver", []) == [])

    real = load()
    help_device = next(t for t in real["tests"] if t["id"] == "HELP-001")
    help_unit = next(t for t in real["tests"] if t["id"] == "HELP-UNIT-001")
    help_device_current = (
        help_device["status"] == "PASS" and need_runtime_bind(help_device)
        and not bind_problems(help_device)
    )
    help_device_requeued = (
        help_device["status"] == "NOT_RUN"
        and any("STALE_BIND" in str(item) for item in help_device.get("evidence") or [])
        and "evidence_bind" not in help_device
    )
    check("N HELP-001 is current or accurately requeued after its bind goes stale",
          help_device_current or help_device_requeued)
    check("N HELP-UNIT-001 is an unbound unit PASS",
          help_unit["status"] == "PASS" and not need_runtime_bind(help_unit)
          and "evidence_bind" not in help_unit)
    real_problems = validate(real, audit_protection=True)
    check("N the repository registry validates", real_problems == [])
    runtime_pass = [t for t in real["tests"] if need_runtime_bind(t)]
    check("N every runtime PASS bind matches the tree",
          all(not bind_problems(t) for t in runtime_pass))

    width = max(len(n) for n, _ in results)

    width = max(len(n) for n, _ in results)
    for name, ok in results:
        print("  %-4s %s" % ("ok" if ok else "FAIL", name.ljust(width)))
    failed = [n for n, ok in results if not ok]
    print("\n%s (%d checks)" % ("SELFTEST PASSED" if not failed else "SELFTEST FAILED", len(results)))
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--gate", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--packet", action="store_true")
    ap.add_argument("--local-device", action="store_true",
                    help="regenerate LOCAL_DEVICE_REQUIRED.md (physical-device batch)")
    ap.add_argument("--work", action="store_true", help="autonomous tests that are not settled")
    ap.add_argument("--verdict", metavar="ID",
                    help="verdict the evidence for one entry actually earns")
    ap.add_argument("--coverage",
                    help="per-requirement scope coverage", action="store_true")
    ap.add_argument("--protection", action="store_true",
                    help="capability regression-protection audit")
    ap.add_argument("--record-pass", choices=["clean", "dirty"],
                    help="record a discovery/review pass result")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--bind", metavar="ID", help="write evidence_bind for one runtime-bound row")
    ap.add_argument("--apply-stale", action="store_true",
                    help="requeue rows whose evidence_bind no longer matches")
    ap.add_argument("--dry-run", action="store_true", help="with --apply-stale, report only")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.record_pass:
        count = record_pass(args.record_pass == "clean")
        print("consecutive clean passes: %d" % count)
        return 0

    doc = load()

    if args.bind:
        matches = [e for e in doc["tests"] if e["id"] == args.bind]
        if not matches:
            print("no such test: %s" % args.bind)
            return 1
        try:
            bind = bind_entry(matches[0])
        except BindRefused as exc:
            print(exc)
            return exc.code
        write_evidence_bind(args.bind, bind)
        print("bound %s at %s" % (args.bind, bind.get("bound_commit")))
        return 0

    if args.apply_stale:
        stale = apply_stale(doc, dry_run=args.dry_run)
        if not args.dry_run:
            for entry in doc["tests"]:
                if entry["id"] not in stale:
                    continue
                note = (entry.get("evidence") or ["STALE_BIND"])[-1]
                persist_stale_row(entry["id"], note)
        for tid in stale:
            print("  stale: %s" % tid)
        print("%d stale row(s)%s" % (len(stale), " (dry-run)" if args.dry_run else ""))
        return 0

    if args.validate:
        problems = validate(doc)
        for problem in problems:
            print("  %s" % problem)
        print("%d test(s), %d problem(s)" % (len(doc["tests"]), len(problems)))
        return 1 if problems else 0

    if args.work:
        for entry in autonomous_work(doc):
            print("  %-18s %-14s %s" % (entry["id"], entry["status"], entry["name"]))
        return 0

    if args.verdict:
        matches = [e for e in doc["tests"] if e["id"] == args.verdict]
        if not matches:
            print("no such test: %s" % args.verdict)
            return 1
        print(verdict_report(matches[0]))
        return 0

    if args.coverage:
        cov = coverage(doc)
        if not cov:
            print("no requirement declares above-COMPONENT required_scope")
            return 0
        print("Requirement | Required | Passing cover | Queued human | Verdict")
        print("--- | --- | --- | --- | ---")
        for key, row in cov.items():
            print("%s | %s | %s | %s | %s" % (
                key, row["required_scope"], ", ".join(row["passing"]) or "—",
                ", ".join(row["queued_human"]) or "—", row["verdict"]))
        return 0

    if args.protection:
        problems = protection_audit(doc)
        summary = protection_summary(doc)
        print("supported=%d unprotected=%d problems=%d"
              % (summary["supported"], summary["unprotected"], summary["problems"]))
        for problem in problems:
            print("  %s" % problem)
        return 1 if problems else 0

    if args.status:
        print("wrote %s" % write(STATUS_DOC, status_doc(doc)))
        return 0

    if args.packet:
        print("wrote %s" % write(PACKET_DOC, packet_doc(doc)))
        print("wrote %s" % write(LOCAL_DEVICE_DOC, local_device_doc(doc)))
        return 0

    if args.local_device:
        print("wrote %s" % write(LOCAL_DEVICE_DOC, local_device_doc(doc)))
        return 0

    g = gate(doc)
    print("HUMAN_VALIDATION_READY = %s" % g["HUMAN_VALIDATION_READY"])
    print("  queued for a human      %d" % g["queued_human_items"])
    print("  autonomous unsettled    %d" % g["autonomous_unsettled"])
    print("  consecutive clean passes %d" % g["consecutive_clean_passes"])
    for reason in g["reasons"]:
        print("  - %s" % reason)
    return 0


if __name__ == "__main__":
    sys.exit(main())
