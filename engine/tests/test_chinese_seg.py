import unittest

from checker.text import cjk_tokenize, tokenize


class CjkTokenizeTests(unittest.TestCase):
    """Change 3 (FR-K-01): optional CJK bigram tokenisation (default off)."""

    def test_bigrams_overlap(self):
        self.assertEqual(cjk_tokenize("数据库原理"), ["数据", "据库", "库原", "原理"])

    def test_default_tokenize_is_unchanged(self):
        # Switch off (default): the historical single-CJK-char tokeniser runs.
        self.assertEqual(tokenize("数据库原理"), ["数", "据", "库", "原", "理"])
        self.assertEqual(tokenize("数据库原理", chinese_seg=True), ["数据", "据库", "库原", "原理"])

    def test_mixed_cjk_and_latin(self):
        self.assertEqual(cjk_tokenize("数据库ABC原理"), ["数据", "据库", "abc", "原理"])

    def test_short_cjk_run_is_single_token(self):
        self.assertEqual(cjk_tokenize("数"), ["数"])


if __name__ == "__main__":
    unittest.main()
