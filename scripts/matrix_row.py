"""Read targeted TEST_MATRIX.yaml entries without putting the full registry in model context.

Examples:
    python scripts/matrix_row.py --row WAKE-ENGINE-001
    python scripts/matrix_row.py --rows-for wake_word
    python scripts/matrix_row.py --rows-for navigation --limit 20
    python scripts/matrix_row.py --selftest

This is read-only. Keep queries here rather than changing scripts/test_matrix.py:
edits to that script may invalidate existing evidence binds.
"""
import argparse
import json
from pathlib import Path
import sys
import tempfile

import yaml

DEFAULT_MATRIX = Path(__file__).resolve().parents[1] / "TEST_MATRIX.yaml"


def load_rows(path):
    with Path(path).open(encoding="utf-8") as handle:
        document = yaml.safe_load(handle)
    rows = document.get("tests") if isinstance(document, dict) else None
    if not isinstance(rows, list):
        raise ValueError("matrix must have a 'tests' list")
    return rows


def row_by_id(rows, row_id):
    matches = [row for row in rows if isinstance(row, dict) and row.get("id") == row_id]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one row with id {row_id!r}, found {len(matches)}")
    return matches[0]


def rows_for(rows, capability, limit):
    matching = [r for r in rows if isinstance(r, dict) and r.get("capability") == capability]
    # Summaries only: evidence/procedures may contain tens of thousands of tokens.
    fields = ("id", "capability", "name", "type", "scope", "status", "owner", "release_blocking")
    items = [{k: row[k] for k in fields if k in row} for row in matching[:limit]]
    return {"capability": capability, "total": len(matching), "shown": len(items),
            "truncated": len(matching) > len(items), "rows": items}


def selftest():
    sample = {"tests": [
        {"id": "A-001", "capability": "audio", "name": "Audio works",
         "status": "PASS", "evidence": ["do not expose this in capability summaries"]},
        {"id": "B-001", "capability": "navigation", "status": "NOT_RUN"},
        {"id": "A-002", "capability": "audio", "status": "FAIL"},
    ]}
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "matrix.yaml"
        fixture.write_text(yaml.safe_dump(sample), encoding="utf-8")
        rows = load_rows(fixture)
        assert row_by_id(rows, "A-001")["evidence"]
        summary = rows_for(rows, "audio", 1)
        assert summary["total"] == 2 and summary["shown"] == 1 and summary["truncated"]
        assert "evidence" not in summary["rows"][0]
        try:
            row_by_id(rows, "MISSING")
        except ValueError:
            pass
        else:
            raise AssertionError("missing IDs must be rejected")
    print("matrix_row selftest: PASS")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument("--row", metavar="ID", help="full YAML for exactly one test ID")
    choice.add_argument("--rows-for", metavar="CAPABILITY", help="compact list of test IDs and statuses")
    choice.add_argument("--selftest", action="store_true")
    parser.add_argument("--limit", type=int, default=25, help="capability summaries, default 25 (max 100)")
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
        return 0
    if not 1 <= args.limit <= 100:
        parser.error("--limit must be between 1 and 100")
    try:
        rows = load_rows(args.matrix)
        if args.row:
            print(yaml.safe_dump(row_by_id(rows, args.row), sort_keys=False, allow_unicode=True).rstrip())
        else:
            print(json.dumps(rows_for(rows, args.rows_for, args.limit),
                             ensure_ascii=False, indent=2))
        return 0
    except (OSError, yaml.YAMLError, ValueError) as exc:
        print(f"matrix_row: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
