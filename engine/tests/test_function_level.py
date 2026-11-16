import unittest

from checker.code import compare_code
from checker.config import CheckConfig
from checker.report import FUNCTION_WEIGHT, WHOLE_FILE_WEIGHT, SourceDocument, build_report


class FunctionLevelMatchingTests(unittest.TestCase):
    """Change 4 (R11): function-level matching wired into the code report."""

    CODE = (
        "def total(values):\n"
        "    result = 0\n"
        "    for value in values:\n"
        "        result += value\n"
        "    return result\n"
    )

    @staticmethod
    def _source():
        source_code = FunctionLevelMatchingTests.CODE.replace("values", "items").replace("value", "item")
        return SourceDocument("src-1", "CLASS", "submission-1", None,
                              files={"solution.py": source_code})

    def test_function_matches_non_empty_and_score_is_weighted(self):
        source = self._source()
        config = CheckConfig(function_level_enabled=True)
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text="",
            candidate_files={"solution.py": self.CODE},
            sources=[source],
            config=config,
        )
        self.assertEqual(report["mode"], "CODE")
        segment = report["segments"][0]
        matches = segment.get("functionMatches")
        self.assertTrue(matches)
        self.assertEqual(matches[0]["functionName"], "total")
        self.assertGreater(matches[0]["startLine"], 0)
        self.assertGreaterEqual(matches[0]["similarity"], 0.5)

        # Verify the 0.4 / 0.6 weighted structure score.
        top = compare_code("solution.py", self.CODE, [(0, "solution.py", source.files["solution.py"], None)])[0]
        expected = round((FUNCTION_WEIGHT * top.function_score + WHOLE_FILE_WEIGHT * top.whole_score) * 100, 2)
        self.assertAlmostEqual(report["structureScore"], expected, delta=1.0)

    def test_disabled_matches_legacy(self):
        source = self._source()
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text="",
            candidate_files={"solution.py": self.CODE},
            sources=[source],
            config=CheckConfig(),
        )
        segment = report["segments"][0]
        self.assertNotIn("functionMatches", segment)


if __name__ == "__main__":
    unittest.main()
