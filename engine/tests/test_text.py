import unittest

from checker.config import CheckConfig
from checker.report import SourceDocument, build_report
from checker.text import clean_text, compare_texts, sentence_similarity, simhash


class TextAlgorithmTests(unittest.TestCase):
    def test_clean_text_removes_reference_markers(self):
        text = "数据库事务需要满足 ACID 特性[1]。参见（张三，2024）。"
        cleaned = clean_text(text)
        self.assertNotIn("[1]", cleaned)
        self.assertNotIn("张三", cleaned)
        self.assertIn("ACID", cleaned)

    def test_identical_sentences_are_similar(self):
        sentence = "系统应支持学生提交作业并进行自动查重。"
        self.assertGreater(sentence_similarity(sentence, sentence), 0.95)

    def test_unrelated_sentences_have_low_similarity(self):
        left = "数据库通过锁与多版本并发控制保证事务隔离性。"
        right = "今天食堂供应米饭、面条和新鲜水果。"
        self.assertLess(sentence_similarity(left, right), 0.35)

    def test_simhash_is_stable(self):
        first = simhash(["数据库", "事务", "隔离", "提交"])
        second = simhash(["数据库", "事务", "隔离", "提交"])
        self.assertEqual(first, second)

    def test_compare_texts_ranks_copy_higher(self):
        candidate = "事务具有原子性、一致性、隔离性和持久性。数据库通过日志保证故障恢复。"
        copied = compare_texts(candidate, [candidate])
        unrelated = compare_texts(candidate, ["操作系统通过时间片轮转调度进程。"])
        self.assertGreater(copied["max"], unrelated["max"])


class ReportTests(unittest.TestCase):
    def test_identical_text_produces_severe_report(self):
        text = "数据库系统需要保证事务的原子性和持久性。并发控制用于避免脏读和不可重复读。"
        source = SourceDocument("src-1", "CLASS", "submission-1", "学生A", text=text)
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text=text,
            candidate_files=None,
            sources=[source],
            config=CheckConfig(text_warning_threshold=20, text_severe_threshold=50),
        )
        self.assertEqual(report["mode"], "TEXT")
        self.assertEqual(report["level"], "SEVERE")
        self.assertGreaterEqual(report["overallScore"], 50)
        self.assertTrue(report["segments"])
        self.assertTrue(report["sources"])

    def test_original_text_can_pass(self):
        report = build_report(
            assignment_id="assignment-1",
            submission_id="submission-2",
            candidate_text="本实验使用二分查找统计有序数组中的目标元素。",
            candidate_files=None,
            sources=[SourceDocument("src-1", "CLASS", "submission-1", None,
                                    text="本项目研究城市轨道交通的客流预测方法。")],
            config=CheckConfig(),
        )
        self.assertEqual(report["level"], "PASS")


if __name__ == "__main__":
    unittest.main()
