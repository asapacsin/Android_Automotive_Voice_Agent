"""collect_state.open_issues and discover_work.open_problems must report the same open problems: they
disagreed on statuses such as "OPEN ... Fixed at JVM level" (P47, P66, P67)."""
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
import collect_state  # noqa: E402
import discover_work  # noqa: E402

SAMPLE = """# Open problems

## P1 — closed one

**Status:** ✅ RESOLVED 2026-10-01.

## P2 — open, but fixed at JVM level

**Status:** OPEN 2026-10-17. Fixed at JVM level, closes after the live run.

## P3 — no status line

Text only.

## P4 — dropped

**Status:** DROPPED - superseded.
"""


class Readers(unittest.TestCase):
    def test_committed_file_agrees(self):
        c = sorted(i["id"] for i in collect_state.open_issues())
        d = sorted(x["item"] for x in discover_work.open_problems())
        self.assertEqual(c, d)

    def test_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "OPEN_PROBLEMS.md"), "w", encoding="utf-8") as f:
                f.write(SAMPLE)
            with mock.patch.object(collect_state, "REPO", tmp):
                ids = [i["id"] for i in collect_state.open_issues()]
        self.assertEqual(ids, ["P2", "P3"])


if __name__ == "__main__":
    unittest.main()
