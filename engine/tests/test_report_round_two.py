import unittest

from checker.config import CheckConfig
from checker.report import (
    SourceDocument,
    _match_ranges,
    aggregate_text_score,
    build_report,
)
from checker.text import split_paragraphs


class HighlightRangesTests(unittest.TestCase):
    """Change 5: real overlapping ranges located via difflib."""

    def test_locates_known_overlap(self):
        ranges = _match_ranges("AAAAABBBBB", "XXXXXBBBBB")
        self.assertEqual(ranges, [{"start": 5, "end": 10}])

    def test_ranges_are_sorted_and_non_overlapping(self):
        text = "alpha beta gamma"
        source = "XXXX alpha YYYY beta ZZZZ"
        ranges = _match_ranges(text, source, min_block=4)
        starts = [item["start"] for item in ranges]
        self.assertEqual(starts, sorted(starts))
        for left, right in zip(ranges, ranges[1:]):
            self.assertLessEqual(left["end"], right["start"])


class ParagraphSplitTests(unittest.TestCase):
    """Change 5: paragraph-level granularity split by blank lines."""

    def test_blank_line_separated_paragraphs(self):
        text = "第一段内容。\n\n第二段内容。\n\n\n第三段内容。"
        paragraphs = split_paragraphs(text)
        self.assertEqual(len(paragraphs), 3)
        self.assertEqual(paragraphs[0].text, "第一段内容。")
        self.assertEqual(paragraphs[1].text, "第二段内容。")
        self.assertEqual(paragraphs[2].text, "第三段内容。")

    def test_single_paragraph(self):
        paragraphs = split_paragraphs("只有一段。")
        self.assertEqual(len(paragraphs), 1)
        self.assertEqual(paragraphs[0].text, "只有一段。")


class ScoreAggregationTests(unittest.TestCase):
    """Change 5: weighted text-score aggregation."""

    def test_weighted_score_within_range(self):
        sentence = 0.5
        full = 0.8
        score = aggregate_text_score(sentence, full)
        expected = round((0.6 * sentence + 0.4 * full) * 100, 2)
        self.assertEqual(score, expected)
        self.assertGreaterEqual(score, min(sentence, full) * 100)
        self.assertLessEqual(score, max(sentence, full) * 100)

    def test_weighted_score_is_two_decimals(self):
        score = aggregate_text_score(0.333, 0.777)
        self.assertEqual(score, round(score, 2))


class ReportIntegrationTests(unittest.TestCase):
    """Change 4 + 5: exclusion filtering, library status, real highlights."""

    def test_whitelist_exclusion_drops_similar_source(self):
        text = "数据库系统需要保证事务的原子性和持久性。并发控制用于避免脏读和不可重复读。"
        source = SourceDocument("src-1", "CLASS", "submission-1", "学生A", text=text)
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text=text,
            candidate_files=None,
            sources=[source],
            config=CheckConfig(libraries=["CLASS", "WHITELIST"]),
            exclusions=[text],
            library_status={"CLASS": "OK", "WHITELIST": "OK"},
        )
        self.assertEqual(report["sourceCount"], 0)
        whitelist = [item for item in report["excludedItems"] if item["type"] == "WHITELIST"]
        self.assertEqual(whitelist[0]["count"], 1)
        self.assertEqual(report["libraryStatus"], {"CLASS": "OK", "WHITELIST": "OK"})
        self.assertEqual(report["params"]["libraries"], ["CLASS", "WHITELIST"])

    def test_params_record_score_weights(self):
        text = "数据库系统需要保证事务的原子性和持久性。"
        source = SourceDocument("src-1", "CLASS", "submission-1", "学生A", text=text)
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text=text,
            candidate_files=None,
            sources=[source],
            config=CheckConfig(),
        )
        self.assertEqual(report["params"]["scoreWeights"], {"sentence": 0.6, "full": 0.4})

    def test_text_report_has_paragraph_and_sentence_segments(self):
        text = "数据库系统需要保证事务的原子性和持久性。\n\n并发控制用于避免脏读和不可重复读。"
        source = SourceDocument("src-1", "CLASS", "submission-1", "学生A", text=text)
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text=text,
            candidate_files=None,
            sources=[source],
            config=CheckConfig(text_warning_threshold=20, text_severe_threshold=50),
        )
        types = {segment["matchType"] for segment in report["segments"]}
        self.assertIn("SENTENCE", types)
        self.assertIn("PARAGRAPH", types)

    def test_code_report_has_non_empty_highlights(self):
        code = "def total(values):\n    result = 0\n    for value in values:\n        result += value\n    return result\n"
        source = SourceDocument("src-1", "CLASS", "submission-1", None,
                                files={"solution.py": code})
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text="",
            candidate_files={"solution.py": code},
            sources=[source],
        )
        self.assertEqual(report["mode"], "CODE")
        segment = report["segments"][0]
        self.assertTrue(segment["highlightRanges"])
        self.assertTrue(segment["matchedExcerpt"])
        self.assertLessEqual(len(segment["matchedExcerpt"]), 240)


if __name__ == "__main__":
    unittest.main()
