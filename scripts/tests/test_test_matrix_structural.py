"""test_matrix.validate(check_binds=False) (--structural, Tier A) skips only bind currency."""
import copy
import os
import sys
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import test_matrix as tm  # noqa: E402


class StructuralValidateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = tm.load()

    def doc_with_stale_bind(self):
        doc = copy.deepcopy(self.base)
        bound = next(e for e in doc["tests"] if tm.need_runtime_bind(e))
        bound["evidence_bind"] = {"code_digest": "deliberately-stale"}
        return doc, bound["id"]

    def test_structural_ignores_stale_bind(self):
        doc, tid = self.doc_with_stale_bind()
        full = tm.validate(copy.deepcopy(doc))
        self.assertTrue(any(p.startswith(tid + ": STALE_BIND") for p in full), full)
        structural = tm.validate(doc, check_binds=False)
        self.assertFalse(any("STALE_BIND" in p for p in structural), structural)

    def test_structural_still_fails_on_structure(self):
        doc, _ = self.doc_with_stale_bind()
        row = next(e for e in doc["tests"] if not tm.need_runtime_bind(e))
        row["status"] = "NOT_A_STATUS"
        problems = tm.validate(doc, check_binds=False)
        self.assertTrue(any("%s: status 'NOT_A_STATUS'" % row["id"] in p for p in problems), problems)
        doc2 = copy.deepcopy(self.base)
        del doc2["tests"][0]["owner"]
        problems = tm.validate(doc2, check_binds=False)
        self.assertTrue(any("missing owner" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
