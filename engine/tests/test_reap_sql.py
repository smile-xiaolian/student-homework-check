import inspect
import re
import unittest

from checker.repository import REQUEUE_LIMIT, TaskRepository


# Real columns of the three tables the re-queue statement may touch.  Kept in
# sync with V1__init_schema.sql / V6__round_two_fixes.sql.  A test failure here
# means the statement references a column that does not exist, which aborts the
# whole worker loop before ``claim()`` ever runs and therefore blocks every
# plagiarism check for every submission.
CHECK_TASK_COLUMNS = {
    "id", "subject_type", "submission_id", "assignment_id", "requester_id",
    "task_type", "status", "task_version", "attempt", "max_attempts",
    "idempotency_key", "config_snapshot", "config_digest", "input_file_object_id",
    "input_text_digest", "library_watermarks", "scheduled_at", "started_at",
    "finished_at", "lease_owner", "lease_until", "heartbeat_at", "error_code",
    "error_detail", "created_by", "created_at", "updated_at", "requeue_count",
    "last_error_category",
}
SUBMISSION_COLUMNS = {
    "id", "assignment_id", "submitter_id", "online_text", "status",
    "is_effective", "is_late", "version_no",
}
ASSIGNMENT_COLUMNS = {
    "id", "course_id", "title", "description", "status", "publish_at", "open_at",
    "deadline_at", "late_deadline_at", "allow_late", "allow_multi_version",
    "effective_rule", "check_enabled", "check_config", "rubric_definition", "weight",
    "created_by", "created_at", "updated_at", "row_version", "allowed_file_types",
    "scheduled_publish_at", "results_visible",
}


def _reap_source() -> str:
    return inspect.getsource(TaskRepository.reap_expired)


def _returning_tokens(source: str) -> list[str]:
    match = re.search(r"RETURNING\s+(.*?)\n\s*\"\"\"", source, re.DOTALL)
    if not match:
        return []
    tokens: list[str] = []
    for raw in match.group(1).split(","):
        fragment = raw.strip()
        if not fragment:
            continue
        # keep only the trailing identifier, dropping table aliases such as
        # ``t.`` / ``s.`` / ``a.``
        tokens.append(fragment.split(".")[-1].split()[0])
    return tokens


class ReapExpiredSqlTests(unittest.TestCase):
    """Guards the ``reap_expired`` statement against a regression to referencing
    a column that does not exist on ``app.check_task`` (``submitter_id`` and
    ``course_id`` live on ``app.submission`` / ``app.assignment`` and must come
    from a JOIN)."""

    def test_submitter_and_course_are_joined(self):
        source = _reap_source()
        self.assertIn("FROM app.submission", source,
                      "submitter_id must come from app.submission")
        self.assertIn("JOIN app.assignment", source,
                      "course_id must come from app.assignment")

    def test_returning_columns_exist(self):
        tokens = _returning_tokens(_reap_source())
        self.assertTrue(tokens, "could not locate the RETURNING clause")
        allowed = CHECK_TASK_COLUMNS | SUBMISSION_COLUMNS | ASSIGNMENT_COLUMNS
        unknown = [token for token in tokens if token not in allowed]
        self.assertEqual(unknown, [],
                         f"reap_expired references unknown columns: {unknown}")

    def test_required_fields_returned(self):
        tokens = _returning_tokens(_reap_source())
        for required in ("id", "submission_id", "submitter_id", "course_id",
                         "attempt", "requeue_count", "task_type"):
            self.assertIn(required, tokens)

    def test_only_affected_rows_are_requeued(self):
        source = _reap_source()
        self.assertIn("lease_until < now()", source)
        self.assertIn("requeue_count = t.requeue_count + 1", source)

    def test_requeue_limit_is_a_positive_constant(self):
        self.assertGreater(REQUEUE_LIMIT, 0)


if __name__ == "__main__":
    unittest.main()