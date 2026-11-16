import unittest

from checker.repository import should_retry


class RetrySemanticsTests(unittest.TestCase):
    """Change 3: with max_attempts = 3 the task runs exactly 3 attempts before
    being marked FAILED (3 failures, 2 re-queues)."""

    def test_should_retry_comparison(self):
        # claim() has already incremented attempt, so attempt < max_attempts
        # means there is still budget left.
        self.assertTrue(should_retry(1, 3))
        self.assertTrue(should_retry(2, 3))
        self.assertFalse(should_retry(3, 3))
        self.assertFalse(should_retry(4, 3))

    def test_exactly_max_attempts_attempts_before_failed(self):
        max_attempts = 3
        attempt = 0
        attempts = 0
        while True:
            attempt += 1  # what claim() does
            attempts += 1
            if not should_retry(attempt, max_attempts):
                break  # permanent failure
        self.assertEqual(attempts, 3)
        self.assertEqual(attempt, 3)

    def test_single_attempt_when_max_attempts_is_one(self):
        max_attempts = 1
        attempt = 0
        attempts = 0
        while True:
            attempt += 1
            attempts += 1
            if not should_retry(attempt, max_attempts):
                break
        self.assertEqual(attempts, 1)


if __name__ == "__main__":
    unittest.main()
