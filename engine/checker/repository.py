from __future__ import annotations

import hashlib
import json
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from .config import WorkerConfig

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class TaskData:
    task_id: uuid.UUID
    submission_id: uuid.UUID
    assignment_id: uuid.UUID
    submitter_id: uuid.UUID
    course_id: uuid.UUID
    requester_id: uuid.UUID | None
    task_type: str
    config: dict[str, Any]
    online_text: str | None


@dataclass(slots=True)
class SourceData:
    submission_id: uuid.UUID
    submitter_id: uuid.UUID
    owner_name: str | None
    online_text: str | None


class TaskRepository:
    def __init__(self, config: WorkerConfig, storage: Any) -> None:
        self.config = config
        self.storage = storage
        self.conn = None
        self._connect()

    def _connect(self) -> None:
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError as exc:
            raise RuntimeError("psycopg 3 is required for the check worker") from exc
        self.conn = psycopg.connect(self.config.database_url, row_factory=dict_row)
        self.conn.autocommit = True

    def close(self) -> None:
        if self.conn is not None:
            self.conn.close()
            self.conn = None

    def claim(self) -> dict[str, Any] | None:
        sql = """
            WITH candidate AS (
                SELECT id FROM app.check_task
                WHERE status = 'QUEUED' AND subject_type = 'SUBMISSION' AND scheduled_at <= now()
                ORDER BY scheduled_at, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE app.check_task task
            SET status = 'PREPROCESSING',
                attempt = task.attempt + 1,
                started_at = coalesce(task.started_at, now()),
                finished_at = NULL,
                lease_owner = %s,
                lease_until = now() + make_interval(secs => %s),
                heartbeat_at = now(),
                error_code = NULL,
                error_detail = NULL,
                updated_at = now()
            FROM candidate
            WHERE task.id = candidate.id
            RETURNING task.*
        """
        with self.conn.transaction():
            return self.conn.execute(sql, (self.config.worker_id, self.config.lease_seconds)).fetchone()

    def heartbeat(self, task_id: uuid.UUID, status: str | None = None) -> bool:
        sql = """
            UPDATE app.check_task
            SET heartbeat_at = now(),
                lease_until = now() + make_interval(secs => %s),
                status = coalesce(%s, status),
                updated_at = now()
            WHERE id = %s AND lease_owner = %s
              AND status IN ('PREPROCESSING','COMPARING','REPORTING')
        """
        with self.conn.transaction():
            cursor = self.conn.execute(sql, (self.config.lease_seconds, status, task_id, self.config.worker_id))
            return cursor.rowcount > 0

    def load_task(self, task: dict[str, Any]) -> TaskData:
        row = self.conn.execute(
            """
            SELECT s.id AS submission_id, s.assignment_id, s.submitter_id, s.online_text,
                   a.course_id, t.requester_id, t.task_type, t.config_snapshot
            FROM app.check_task t
            JOIN app.submission s ON s.id = t.submission_id
            JOIN app.assignment a ON a.id = s.assignment_id
            WHERE t.id = %s
            """,
            (task["id"],),
        ).fetchone()
        if row is None:
            raise RuntimeError(f"task {task['id']} has no submission")
        return TaskData(
            task_id=task["id"], submission_id=row["submission_id"],
            assignment_id=row["assignment_id"], submitter_id=row["submitter_id"],
            course_id=row["course_id"], requester_id=row["requester_id"],
            task_type=row["task_type"], config=row["config_snapshot"] or {},
            online_text=row["online_text"],
        )

    def load_submission_files(self, submission_id: uuid.UUID) -> dict[str, bytes]:
        rows = self.conn.execute(
            """
            SELECT sf.display_name, fo.object_key
            FROM app.submission_file sf
            JOIN app.file_object fo ON fo.id = sf.file_object_id
            WHERE sf.submission_id = ?
            ORDER BY sf.sort_order, sf.created_at
            """.replace("?", "%s"),
            (submission_id,),
        ).fetchall()
        files: dict[str, bytes] = {}
        for row in rows:
            try:
                files[row["display_name"]] = self.storage.get(row["object_key"])
            except Exception as exc:
                logger.warning("unable to read source file %s: %s", row["object_key"], exc)
        return files

    def load_sources(self, task: TaskData, limit: int) -> list[SourceData]:
        rows = self.conn.execute(
            """
            SELECT DISTINCT ON (s.submitter_id)
                   s.id AS submission_id, s.submitter_id, u.display_name, s.online_text
            FROM app.submission s
            JOIN app.user_account u ON u.id = s.submitter_id
            WHERE s.assignment_id = %s AND s.id <> %s AND s.is_effective = true
              AND s.status IN ('SUBMITTED','CHECKING','CHECK_COMPLETED','REVIEWED','PUBLISHED')
            ORDER BY s.submitter_id, s.version_no DESC
            LIMIT %s
            """,
            (task.assignment_id, task.submission_id, limit),
        ).fetchall()
        return [
            SourceData(row["submission_id"], row["submitter_id"], row["display_name"], row["online_text"])
            for row in rows
        ]

    def save_report(self, task: TaskData, report: dict[str, Any], report_json: bytes,
                    input_digest: str) -> uuid.UUID:
        report_digest = hashlib.sha256(report_json).hexdigest()
        object_key = f"reports/{task.assignment_id}/{task.submission_id}/{task.task_id}/report.json"
        self.storage.put(object_key, report_json, "application/json")
        with self.conn.transaction():
            file_row = self.conn.execute(
                """
                INSERT INTO app.file_object
                    (bucket, object_key, original_name, mime_type, size_bytes, sha256, scan_status, created_by)
                VALUES (%s, %s, 'check-report.json', 'application/json', %s, %s, 'CLEAN', %s)
                RETURNING id
                """,
                (self.config.storage_bucket, object_key, len(report_json), report_digest,
                 task.requester_id or task.submitter_id),
            ).fetchone()
            file_id = file_row["id"]
            version_row = self.conn.execute(
                "SELECT coalesce(max(report_version), 0) + 1 AS version FROM app.check_report WHERE submission_id = %s",
                (task.submission_id,),
            ).fetchone()
            version = version_row["version"]
            self.conn.execute(
                "UPDATE app.check_report SET is_current = false WHERE submission_id = %s AND is_current = true",
                (task.submission_id,),
            )
            params = report.get("params", {})
            params_json = json.dumps(params, ensure_ascii=False, sort_keys=True)
            top_source = report.get("sources", [{}])
            top = top_source[0] if top_source else {}
            report_row = self.conn.execute(
                """
                INSERT INTO app.check_report
                    (check_task_id, submission_id, assignment_id, requester_id, report_version, is_current,
                     overall_score, text_score, structure_score, level, algorithm_version, params,
                     params_digest, source_count, top_source_type, top_source_similarity,
                     report_file_id, report_digest)
                VALUES (%s, %s, %s, %s, %s, true, %s, %s, %s, %s, %s, %s::jsonb,
                        %s, %s, %s, %s, %s, %s)
                RETURNING id
                """,
                (task.task_id, task.submission_id, task.assignment_id, task.requester_id, version,
                 report["overallScore"], report.get("textScore"), report.get("structureScore"),
                 report["level"], report["algorithmVersion"], params_json,
                 hashlib.sha256(params_json.encode("utf-8")).hexdigest(), report["sourceCount"],
                 report.get("topSourceType"), report.get("topSourceSimilarity"), file_id, report_digest),
            ).fetchone()
            report_id = report_row["id"]
            self.conn.execute(
                """
                UPDATE app.check_task
                SET status = 'SUCCEEDED', finished_at = now(), heartbeat_at = now(),
                    lease_until = NULL, input_text_digest = %s, error_code = NULL,
                    error_detail = NULL, updated_at = now()
                WHERE id = %s AND lease_owner = %s
                """,
                (input_digest, task.task_id, self.config.worker_id),
            )
            self.conn.execute(
                """
                UPDATE app.submission
                SET status = CASE WHEN status IN ('REVIEWED','PUBLISHED') THEN status ELSE 'CHECK_COMPLETED' END,
                    row_version = row_version + 1
                WHERE id = %s
                """,
                (task.submission_id,),
            )
            self._insert_library_entry(task, report_digest, input_digest)
            self._notify(task, report_id, report)
        return report_id

    def fail(self, task: TaskData, error_code: str, detail: str) -> None:
        with self.conn.transaction():
            row = self.conn.execute(
                "SELECT attempt, max_attempts FROM app.check_task WHERE id = %s", (task.task_id,)
            ).fetchone()
            retryable = row is not None and row["attempt"] < row["max_attempts"]
            if retryable:
                self.conn.execute(
                    """
                    UPDATE app.check_task
                    SET status = 'QUEUED', lease_owner = NULL, lease_until = NULL,
                        error_code = %s, error_detail = %s,
                        scheduled_at = now() + make_interval(secs => %s), updated_at = now()
                    WHERE id = %s
                    """,
                    (error_code, detail[:4000], min(300, 15 * (2 ** max(0, row["attempt"] - 1))),
                     task.task_id),
                )
                return
            self.conn.execute(
                """
                UPDATE app.check_task
                SET status = 'FAILED', finished_at = now(), lease_owner = NULL, lease_until = NULL,
                    error_code = %s, error_detail = %s, updated_at = now()
                WHERE id = %s
                """,
                (error_code, detail[:4000], task.task_id),
            )
            self.conn.execute(
                "UPDATE app.submission SET status = 'CHECK_FAILED', row_version = row_version + 1 WHERE id = %s",
                (task.submission_id,),
            )
            self.conn.execute(
                """
                INSERT INTO app.notification (recipient_id, notification_type, title, content, target_type, target_id)
                VALUES (%s, 'CHECK_FAILED', '查重任务失败', %s, 'SUBMISSION', %s)
                """,
                (task.submitter_id, f"提交的作业查重未能完成：{detail[:500]}", task.submission_id),
            )

    def _insert_library_entry(self, task: TaskData, fingerprint_key: str, content_hash: str) -> None:
        existing = self.conn.execute(
            "SELECT 1 FROM app.library_entry WHERE submission_id = %s LIMIT 1", (task.submission_id,)
        ).fetchone()
        if existing:
            return
        library = self.conn.execute(
            """
            INSERT INTO app.check_library (name, library_type, scope_key, course_id, status)
            VALUES (%s, 'CLASS', %s, %s, 'ACTIVE')
            ON CONFLICT (library_type, scope_key) DO UPDATE SET updated_at = now()
            RETURNING id
            """,
            (f"课程比对库 {task.course_id}", str(task.course_id), task.course_id),
        ).fetchone()
        library_id = library["id"]
        sequence = self.conn.execute(
            "SELECT coalesce(max(seq), 0) + 1 AS seq FROM app.library_entry WHERE library_id = %s",
            (library_id,),
        ).fetchone()["seq"]
        owner_hash = hashlib.sha256(str(task.submitter_id).encode("utf-8")).hexdigest()
        self.conn.execute(
            """
            INSERT INTO app.library_entry
                (library_id, submission_id, owner_hash, content_hash, fingerprint_key, seq, status, index_status)
            VALUES (%s, %s, %s, %s, %s, %s, 'ACTIVE', 'INDEXED')
            """,
            (library_id, task.submission_id, owner_hash, content_hash, fingerprint_key, sequence),
        )

    def _notify(self, task: TaskData, report_id: uuid.UUID, report: dict[str, Any]) -> None:
        score = report["overallScore"]
        level = report["level"]
        self.conn.execute(
            """
            INSERT INTO app.notification
                (recipient_id, notification_type, title, content, target_type, target_id)
            VALUES (%s, 'CHECK_COMPLETED', '查重报告已生成', %s, 'CHECK_REPORT', %s)
            """,
            (task.submitter_id, f"本次作业总体相似度为 {score:.2f}%，检测等级：{level}。", report_id),
        )
        if level == "SEVERE":
            teachers = self.conn.execute(
                """
                SELECT DISTINCT user_id FROM app.course_member
                WHERE course_id = %s AND member_role = 'INSTRUCTOR' AND status = 'ACTIVE'
                  AND user_id <> %s
                """,
                (task.course_id, task.submitter_id),
            ).fetchall()
            for teacher in teachers:
                self.conn.execute(
                    """
                    INSERT INTO app.notification
                        (recipient_id, notification_type, title, content, target_type, target_id)
                    VALUES (%s, 'CHECK_HIGH_RISK', '高风险查重提醒', %s, 'CHECK_REPORT', %s)
                    """,
                    (teacher["user_id"], f"有提交的查重相似度达到 {score:.2f}%，请及时复核。", report_id),
                )
