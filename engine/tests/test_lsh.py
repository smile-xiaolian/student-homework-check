import unittest
import uuid
from unittest import mock

from checker.config import CheckConfig
from checker.repository import (
    SourceData,
    TaskData,
    TaskRepository,
    lsh_candidates,
    lsh_index,
)
from checker.text import lsh_banding, minhash_signature, tokenize


class MinHashTests(unittest.TestCase):
    """Change 2 (R4): deterministic MinHash signature and banding."""

    def test_signature_is_deterministic(self):
        tokens = ["数据库", "事务", "隔离", "提交"]
        self.assertEqual(minhash_signature(tokens), minhash_signature(tokens))
        self.assertEqual(len(minhash_signature(tokens)), 128)

    def test_different_texts_have_different_signatures(self):
        left = minhash_signature(["数据库", "事务", "隔离"])
        right = minhash_signature(["操作系统", "调度", "进程"])
        self.assertNotEqual(left, right)

    def test_banding_segment_count_is_correct(self):
        sig = minhash_signature(["a", "b", "c", "d", "e", "f", "g", "h"], num_hashes=128)
        bands = lsh_banding(sig, num_bands=16)
        self.assertEqual(len(bands), 16)
        for band in bands:
            self.assertEqual(len(band), 8)

    def test_empty_tokens_produce_zero_signature(self):
        self.assertEqual(minhash_signature([]), [0] * 128)


class LshCandidateTests(unittest.TestCase):
    """Change 2 (R4): band-intersection counting and threshold judgment."""

    def test_identical_text_is_a_candidate(self):
        text = "数据库系统需要保证事务的原子性和持久性。"
        index = lsh_index([("a", text), ("b", "今天食堂供应米饭面条和新鲜水果。")])
        sig = minhash_signature(tokenize(text))
        candidates = lsh_candidates(sig, index, 0.5)
        self.assertIn("a", candidates)
        self.assertNotIn("b", candidates)

    def test_threshold_boundary(self):
        sig = minhash_signature(["x", "y", "z"], num_hashes=128)
        bands = lsh_banding(sig, num_bands=16)
        index = {band: [1] for band in bands}
        # 16/16 = 1.0 bands match.
        self.assertEqual(lsh_candidates(sig, index, 1.0), {1})
        # A threshold above 1.0 admits nothing.
        self.assertEqual(lsh_candidates(sig, index, 1.01), set())

    def test_partial_band_match_meets_threshold(self):
        sig = minhash_signature(["a", "b", "c", "d"], num_hashes=128)
        bands = sorted(lsh_banding(sig, num_bands=16))
        # Only 8 of the 16 bands map to key 7 -> ratio 0.5.
        index = {band: [7] for band in bands[:8]}
        self.assertIn(7, lsh_candidates(sig, index, 0.5))
        self.assertNotIn(7, lsh_candidates(sig, index, 0.6))


class LshPathTests(unittest.TestCase):
    """Change 2 (R4): the load_sources path is gated by the config switch."""

    @staticmethod
    def _task(config: dict) -> TaskData:
        return TaskData(
            task_id=uuid.uuid4(), submission_id=uuid.uuid4(), assignment_id=uuid.uuid4(),
            submitter_id=uuid.uuid4(), course_id=uuid.uuid4(), requester_id=None,
            task_type="CHECK", config=config, online_text=None,
        )

    def test_disabled_uses_original_sql_path(self):
        repo = TaskRepository.__new__(TaskRepository)
        task = self._task({})
        source = SourceData(uuid.uuid4(), uuid.uuid4(), "学生A", "some source text")
        with mock.patch.object(repo, "_load_class_sources_from_library",
                               return_value=[source]) as library_path, \
             mock.patch.object(repo, "_load_class_sources_fallback",
                               return_value=[]) as fallback_path:
            sources, status = repo.load_sources(task, 10)
        library_path.assert_called_once()
        fallback_path.assert_not_called()
        self.assertEqual(status["CLASS"], "OK")
        self.assertEqual(sources, [source])

    def test_enabled_uses_lsh_prefilter(self):
        repo = TaskRepository.__new__(TaskRepository)
        text = "数据库系统需要保证事务的原子性和持久性。"
        task = self._task({"lsh": {"enabled": True}})
        source = SourceData(uuid.uuid4(), uuid.uuid4(), "学生A", text)
        with mock.patch.object(repo, "_load_class_sources_from_library",
                               return_value=[source]) as library_path, \
             mock.patch.object(repo, "_load_class_sources_fallback",
                               return_value=[]) as fallback_path:
            sources, status = repo.load_sources(task, 10, candidate_text=text)
        # LSH prefilter loads the broad pool through the same library query.
        library_path.assert_called_once()
        fallback_path.assert_not_called()
        self.assertEqual(status["CLASS"], "OK")
        self.assertEqual(sources, [source])

    def test_lsh_config_flag_surfaces_on_check_config(self):
        config = CheckConfig.from_snapshot({"lsh": {"enabled": True}})
        self.assertTrue(config.lsh_enabled)


if __name__ == "__main__":
    unittest.main()
