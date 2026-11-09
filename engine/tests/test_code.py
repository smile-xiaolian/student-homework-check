import unittest

from checker.code import (
    detect_language,
    normalize_source,
    strip_comments,
    structure_similarity,
)
from checker.report import SourceDocument, build_report


class CodeAlgorithmTests(unittest.TestCase):
    def test_comments_are_stripped(self):
        source = "int main() { // comment\n /* block */ return 0; }"
        cleaned = strip_comments(source, "C")
        self.assertNotIn("comment", cleaned)
        self.assertNotIn("block", cleaned)
        self.assertIn("return 0", cleaned)

    def test_identifier_renaming_preserves_structure(self):
        left = "int sum(int[] values) { int total = 0; for (int v : values) total += v; return total; }"
        right = "int aggregate(int[] items) { int result = 0; for (int item : items) result += item; return result; }"
        self.assertGreater(structure_similarity(left, right, "JAVA"), 0.75)

    def test_language_detection(self):
        self.assertEqual(detect_language("src/Main.java"), "JAVA")
        self.assertEqual(detect_language("solution.py"), "PYTHON")

    def test_normalization_replaces_literals(self):
        normalized = normalize_source('int x = 42; String y = "secret";', "JAVA")
        self.assertIn("NUM", normalized)
        self.assertIn("STR", normalized)
        self.assertNotIn("42", normalized)

    def test_code_report_has_structure_score(self):
        code = "def total(values):\n    result = 0\n    for value in values:\n        result += value\n    return result\n"
        source = SourceDocument("src-1", "CLASS", "submission-1", None,
                                files={"solution.py": code.replace("values", "items")})
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text="",
            candidate_files={"solution.py": code},
            sources=[source],
        )
        self.assertEqual(report["mode"], "CODE")
        self.assertIsNotNone(report["structureScore"])
        self.assertGreater(report["structureScore"], 40)


if __name__ == "__main__":
    unittest.main()
