"""Run a build/test command with logs on disk and bounded results in model context.

Examples:
  python scripts/compact_test.py -- ./gradlew :behavior-test:test
  python scripts/compact_test.py -- ./gradlew test
  python scripts/compact_test.py -- .\gradlew.bat :app:testDebugUnitTest
  python scripts/compact_test.py --selftest

The subprocess exit status is preserved. This does not replace the repository's
required release tests or device evidence. Full stdout/stderr remains in a local
temporary log, never printed verbatim by this wrapper.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[1]


def build_directory():
    if os.environ.get("NOVA_BUILD_DIR"):
        return Path(os.environ["NOVA_BUILD_DIR"])
    if os.name == "nt":
        return Path(r"C:\Users\Administrator\tools\nova-drive-build")
    return Path.home() / "nova-drive-build"


def report_files(root):
    return {path: (path.stat().st_mtime_ns, path.stat().st_size)
            for path in root.glob("**/test-results/**/TEST-*.xml")
            if path.is_file()}


def summarize_xml(paths):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failing = []
    parse_errors = []
    for path in paths:
        try:
            suite = ET.parse(path).getroot()
            for field in totals:
                totals[field] += int(suite.attrib.get(field, "0"))
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failing.append(f"{case.attrib.get('classname', '?')}#{case.attrib.get('name', '?')}")
        except (ET.ParseError, OSError, ValueError) as exc:
            parse_errors.append(f"{path.name}: {type(exc).__name__}")
    return totals, failing, parse_errors


def selftest():
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        folder = root / "app" / "test-results" / "testDebugUnitTest"
        folder.mkdir(parents=True)
        xml_path = folder / "TEST-sample.xml"
        xml_path.write_text(
            '<testsuite tests="2" failures="1" errors="0" skipped="0">'
            '<testcase classname="Safe" name="ok"/>'
            '<testcase classname="Safe" name="bad"><failure message="PRIVATE_DATA">secret</failure></testcase>'
            '</testsuite>', encoding="utf-8")
        before = report_files(root)
        assert xml_path in before
        totals, failures, parse_errors = summarize_xml(before)
        assert totals == {"tests": 2, "failures": 1, "errors": 0, "skipped": 0}
        assert failures == ["Safe#bad"] and not parse_errors
        assert "PRIVATE_DATA" not in repr((totals, failures, parse_errors))
    print("compact_test selftest: PASS")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, default=None)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER,
                        help="after --, command and arguments to execute")
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
        return 0
    cmd = args.command
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        parser.error("provide a command after --")
    build_dir = args.build_dir or build_directory()
    before = report_files(build_dir)
    log_dir = Path(tempfile.mkdtemp(prefix="nova-compact-test-"))
    log_path = log_dir / "command.log"
    started = time.monotonic()
    try:
        with log_path.open("wb") as output:
            proc = subprocess.run(cmd, cwd=REPO, stdout=output, stderr=subprocess.STDOUT,
                                  check=False)
        code = proc.returncode
    except OSError as exc:
        log_path.write_text(f"{type(exc).__name__}: cannot start command\n", encoding="utf-8")
        code = 127
    after = report_files(build_dir)
    fresh = [p for p, signature in after.items() if before.get(p) != signature]
    totals, failing, parse_errors = summarize_xml(fresh)
    has_failing_tests = totals["failures"] > 0 or totals["errors"] > 0
    successful = code == 0 and not parse_errors and not has_failing_tests
    status = "PASS" if successful and fresh else ("COMMAND_OK_NO_FRESH_XML" if successful else "FAIL")
    print(f"RESULT: {status}")
    print(f"EXIT_CODE: {code}")
    print(f"DURATION_SECONDS: {time.monotonic() - started:.1f}")
    print(f"FRESH_JUNIT_REPORTS: {len(fresh)}")
    if fresh:
        print("FRESH_JUNIT_COUNTS: " + " ".join(f"{k}={v}" for k, v in totals.items()))
    else:
        print("JUNIT_EVIDENCE: no new XML; tests may be up-to-date or command may not run tests")
    for item in failing[:15]:
        print(f"FAILING_TEST: {item}")
    if len(failing) > 15:
        print(f"ADDITIONAL_FAILURES: {len(failing) - 15}")
    for item in parse_errors[:5]:
        print(f"XML_PARSE_ERROR: {item}")
    print(f"FULL_LOG: {log_path}")
    # Preserve command failure and never call a broken or unreadable result successful.
    return code if code != 0 else (2 if parse_errors or has_failing_tests else 0)


if __name__ == "__main__":
    raise SystemExit(main())
