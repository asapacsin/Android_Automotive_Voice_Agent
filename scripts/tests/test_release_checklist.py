"""Tier C release checklist: pure-function tests on synthetic matrices."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
import test_matrix as tm  # noqa: E402


def row(tid, owner="AUTONOMOUS", status="PASS", rb=True, **kw):
    entry = {"id": tid, "owner": owner, "status": status, "release_blocking": rb,
             "name": "row " + tid, "type": "unit", "scope": "COMPONENT"}
    entry.update(kw)
    return entry


def run(*rows, stale=()):
    return tm.release_checklist({"tests": list(rows)},
                                stale_check=lambda e: ["STALE_BIND x"] if e["id"] in stale else [])


def eff(result, tid):
    return [r for r in result["rows"] if r["id"] == tid][0]["effective"]


class ReleaseChecklistTest(unittest.TestCase):
    def test_all_verified_ready(self):
        res = run(row("A"), row("H", owner="HUMAN_PHYSICAL", status="HUMAN_PASS"))
        self.assertEqual(res["verdict"], "READY")
        self.assertIn("RELEASE READY verified=2/2", tm.release_text(res))

    def test_human_required_not_ready(self):
        res = run(row("A"), row("H", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED"))
        self.assertEqual(res["verdict"], "NOT_READY")
        self.assertEqual(eff(res, "H"), "UNVERIFIED")

    def test_human_row_with_pass_not_verified(self):
        res = run(row("H", owner="HUMAN_PHYSICAL", status="PASS"))
        self.assertNotEqual(eff(res, "H"), "VERIFIED")
        self.assertEqual(res["verdict"], "NOT_READY")

    def test_human_row_with_emulator_evidence_unverified(self):
        res = run(row("H", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED",
                      autonomous_evidence=["emulator PASS run 1", "emulator PASS run 2"]))
        self.assertEqual(eff(res, "H"), "UNVERIFIED")

    def test_fail_failed(self):
        res = run(row("A", status="FAIL"), row("H", owner="HUMAN_ACCOUNT", status="HUMAN_FAIL"))
        self.assertEqual(eff(res, "A"), "FAILED")
        self.assertEqual(eff(res, "H"), "FAILED")
        self.assertEqual(res["counts"]["FAILED"], 2)

    def test_stale_pass_with_injected_check(self):
        res = run(row("A"), stale=("A",))
        self.assertEqual(eff(res, "A"), "STALE")
        self.assertEqual(res["verdict"], "NOT_READY")

    def test_stale_pass_with_real_bind_check(self):
        # Device PASS without evidence_bind: existing bind_problems reports STALE_BIND.
        res = tm.release_checklist({"tests": [row("D", type="device")]})
        self.assertEqual(eff(res, "D"), "STALE")

    def test_non_release_blocking_ignored(self):
        res = run(row("A"), row("X", status="FAIL", rb=False))
        self.assertEqual([r["id"] for r in res["rows"]], ["A"])
        self.assertEqual(res["ignored"], 1)
        self.assertEqual(res["verdict"], "READY")

    def test_string_true_counts(self):
        res = run(row("A", rb="True", status="NOT_RUN"))
        self.assertEqual(eff(res, "A"), "UNVERIFIED")

    def test_sort_order(self):
        res = run(row("V"), row("U", status="NOT_RUN"), row("F", status="FAIL"),
                  row("S"), stale=("S",))
        self.assertEqual([r["effective"] for r in res["rows"]],
                         ["FAILED", "STALE", "UNVERIFIED", "VERIFIED"])


if __name__ == "__main__":
    unittest.main()
