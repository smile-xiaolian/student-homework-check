from __future__ import annotations

import hashlib
import json
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import PurePath
from typing import Any

from .config import WorkerConfig, normalize_config
from .text import lsh_banding, minhash_signature, tokenize

logger = logging.getLogger(__name__)

#: Change 3: a task is attempted at most ``max_attempts`` times.  ``claim()`` has
#: already incremented ``attempt`` by the time ``fail()`` runs, so once
#: ``attempt >= max_attempts`` the budget is exhausted and the task must fail.
#: With ``max_attempts = 3`` this yields exactly 3 attempts / 3 failures.
def should_retry(attempt: int, max_attempts: int) -> bool:
    return attempt < max_attempts


#: Change 1: a task whose lease expired more than this many times is moved to a
#: permanent FAILED state instead of being re-queued forever.
REQUEUE_LIMIT = 3


def lsh_index(entries: Iterable[tuple[Any, str]], num_hashes: int = 128,
              num_bands: int = 16) -> dict[tuple[int, ...], list[Any]]:
    """Build an LSH band index over ``(key, text)`` entries (R4, default off).

    Every entry is tokenised into a MinHash signature and its band keys map to
    the entry key, so ``lsh_candidates`` can look up near-duplicate sources in
    O(bands) instead of a full scan.
    """
    index: dict[tuple[int, ...], list[Any]] = {}
    for key, text in entries:
        signature = minhash_signature(tokenize(text), num_hashes=num_hashes)
        for band in lsh_banding(signature, num_bands=num_bands):
            index.setdefault(band, []).append(key)
    return index


def lsh_candidates(sig: Sequence[int], index: dict[tuple[int, ...], list[Any]],
                   threshold: float, num_bands: int = 16) -> set[Any]:
    """Return entry keys whose band-overlap ratio meets ``threshold`` (R4).

    A key is a candidate when ``matched_bands / num_bands >= threshold``, i.e.
    the candidate signature shares enough of its LSH bands with the entry.
    """
    bands = lsh_banding(sig, num_bands=num_bands)
    if not bands:
        return set()
    counts: dict[Any, int] = {}
    for band in bands:
        for key in index.get(band, ()):
            counts[key] = counts.get(key, 0) + 1
    return {key for key, count in counts.items() if count / num_bands >= threshold}


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

    def reap_expired(self) -> int:
        """Re-queue tasks whose lease expired (change 1).

        Idempotent: only tasks in a running state with an expired lease are
        touched.  Tasks that have been re-queued more than ``REQUEUE_LIMIT``
        times are moved to a permanent ``FAILED`` state, their submission is
        marked ``CHECK_FAILED`` and admins/course instructors are notified.
        """
        sql = """
            UPDATE app.check_task
            SET status = 'QUEUED', lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                error_code = 'LEASE_EXPIRED',
                error_detail = '任务租约过期，已自动重新入队',
                requeue_count = requeue_count + 1,
                scheduled_at = now(), updated_at = now()
            WHERE status IN ('PREPROCESSING','COMPARING','REPORTING')
              AND lease_until IS NOT NULL AND lease_until < now()
            RETURNING id, submission_id, submitter_id, course_id, attempt, requeue_count, task_type
        """
        with self.conn.transaction():
            rows = self.conn.execute(sql).fetchall()
            for row in rows:
                if row["requeue_count"] > REQUEUE_LIMIT:
                    self._mark_requeue_limit_failed(row)
            return len(rows)

    def _mark_requeue_limit_failed(self, row: dict[str, Any]) -> None:
        self.conn.execute(
            """
            UPDATE app.check_task
            SET status = 'FAILED', finished_at = now(), lease_owner = NULL, lease_until = NULL,
                heartbeat_at = NULL, error_code = 'REQUEUE_LIMIT',
                error_detail = '任务租约过期次数超过阈值，已终止',
                last_error_category = 'PERMANENT', updated_at = now()
            WHERE id = %s
            """,
            (row["id"],),
        )
        if row["submission_id"]:
            self.conn.execute(
                "UPDATE app.submission SET status = 'CHECK_FAILED', row_version = row_version + 1 WHERE id = %s",
                (row["submission_id"],),
            )
        detail = (f"提交人 {row['submitter_id']} 的任务 {row['id']} 因租约过期超过阈值已终止"
                  f"（错误码 REQUEUE_LIMIT，重试次数 {row['requeue_count']}）。")
        for recipient_id in self._failure_recipients(row["course_id"], row["submitter_id"]):
            self._insert_notification(recipient_id, "CHECK_FAILED_ADMIN", "查重任务失败告警",
                                      detail, "SUBMISSION", row["submission_id"])

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
            WHERE sf.submission_id = %s
            ORDER BY sf.sort_order, sf.created_at
            """,
            (submission_id,),
        ).fetchall()
        files: dict[str, bytes] = {}
        for row in rows:
            try:
                files[row["display_name"]] = self.storage.get(row["object_key"])
            except Exception as exc:
                logger.warning("unable to read source file %s: %s", row["object_key"], exc)
        return files

    def load_sources(self, task: TaskData, limit: int,
                     candidate_text: str = "") -> tuple[list[SourceData], dict[str, str]]:
        """Load comparison sources for the task plus the per-library status.

        Change 4: the CLASS library is read through ``check_library`` /
        ``library_entry`` (real read path).  If the library has no usable entry
        the method falls back to the historical direct ``submission`` query so
        demo data keeps working.

        Change 2 (R4): when ``config.lsh.enabled`` is true and a ``candidate_text``
        is supplied the source pool is first narrowed by MinHash + LSH candidate
        retrieval and then refined by the normal comparison downstream.  When the
        switch is off (default) the original SQL path is taken unchanged.
        """
        normalized = normalize_config(task.config)
        lsh = normalized["lsh"]
        status: dict[str, str] = {}
        if lsh["enabled"] and candidate_text:
            sources = self._load_class_sources_lsh(task, limit, candidate_text, lsh)
        else:
            sources = self._load_class_sources_from_library(task, limit)
            if not sources:
                sources = self._load_class_sources_fallback(task, limit)
        status["CLASS"] = "OK" if sources else "EMPTY"
        libraries = normalized["libraries"]
        if "WHITELIST" in libraries:
            # Determined by a cheap DB check (no storage read); the actual
            # exclusion texts are loaded once by ``load_exclusions``.
            status["WHITELIST"] = "OK" if self._whitelist_has_entries(task.course_id) else "EMPTY"
        if "HISTORICAL" in libraries:
            # HISTORICAL (历届库) is explicitly out of scope for round two;
            # entries are not consumed here yet.  The branch is left as a
            # clear extension point for the next iteration.
            status["HISTORICAL"] = "SKIPPED"
        return sources, status

    def _load_class_sources_lsh(self, task: TaskData, limit: int, candidate_text: str,
                                lsh_cfg: dict[str, Any]) -> list[SourceData]:
        """Load the source pool, LSH-prefilter it, then return the candidates.

        A broader pool (``max_candidates * 4``) is read so candidate retrieval
        has recall; the LSH candidate set is then capped to ``limit`` for the
        downstream fine-grained (精排) comparison.
        """
        pool_limit = max(limit, int(lsh_cfg.get("max_candidates", 200)) * 4)
        pool = self._load_class_sources_from_library(task, pool_limit)
        if not pool:
            pool = self._load_class_sources_fallback(task, pool_limit)
        if not pool:
            return []
        num_hashes = int(lsh_cfg.get("num_hashes", 128))
        num_bands = int(lsh_cfg.get("num_bands", 16))
        candidate_sig = minhash_signature(tokenize(candidate_text), num_hashes=num_hashes)
        index = lsh_index(
            ((position, source.online_text or "") for position, source in enumerate(pool)),
            num_hashes=num_hashes, num_bands=num_bands,
        )
        candidates = lsh_candidates(
            candidate_sig, index, float(lsh_cfg.get("candidate_threshold", 0.7)), num_bands=num_bands
        )
        return [pool[index] for index in sorted(candidates)][:limit]

    def _load_class_sources_from_library(self, task: TaskData, limit: int) -> list[SourceData]:
        owner_hash = hashlib.sha256(str(task.submitter_id).encode("utf-8")).hexdigest()
        rows = self.conn.execute(
            """
            SELECT le.submission_id, s.submitter_id, u.display_name, s.online_text
            FROM app.check_library lib
            JOIN app.library_entry le ON le.library_id = lib.id
            JOIN app.submission s ON s.id = le.submission_id
            JOIN app.user_account u ON u.id = s.submitter_id
            WHERE lib.course_id = %s AND lib.library_type = 'CLASS' AND lib.status = 'ACTIVE'
              AND le.status = 'ACTIVE' AND le.index_status = 'INDEXED'
              AND le.submission_id IS NOT NULL AND le.owner_hash <> %s
              AND s.is_effective = true
              AND s.status IN ('SUBMITTED','CHECKING','CHECK_COMPLETED','REVIEWED','PUBLISHED')
            ORDER BY le.seq
            LIMIT %s
            """,
            (task.course_id, owner_hash, limit),
        ).fetchall()
        return [
            SourceData(row["submission_id"], row["submitter_id"], row["display_name"], row["online_text"])
            for row in rows
        ]

    def _load_class_sources_fallback(self, task: TaskData, limit: int) -> list[SourceData]:
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

    def load_exclusions(self, task: TaskData) -> list[str]:
        """Load WHITELIST exclusion materials (change 4).

        Whitelist entries with a ``file_object`` are read back from object
        storage and returned as text; a source that matches any of these texts
        at >= 0.90 whole-text similarity is excluded from the comparison.
        """
        if "WHITELIST" not in normalize_config(task.config)["libraries"]:
            return []
        rows = self.conn.execute(
            """
            SELECT fo.object_key
            FROM app.check_library lib
            JOIN app.library_entry le ON le.library_id = lib.id
            JOIN app.file_object fo ON fo.id = le.file_object_id
            WHERE lib.library_type = 'WHITELIST' AND lib.status = 'ACTIVE'
              AND le.status = 'ACTIVE' AND le.file_object_id IS NOT NULL
              AND (lib.course_id IS NULL OR lib.course_id = %s)
            ORDER BY le.seq
            """,
            (task.course_id,),
        ).fetchall()
        texts: list[str] = []
        for row in rows:
            try:
                texts.append(self.storage.get(row["object_key"]).decode("utf-8", errors="replace"))
            except Exception as exc:
                logger.warning("unable to read whitelist material %s: %s", row["object_key"], exc)
        return texts

    def _whitelist_has_entries(self, course_id: uuid.UUID) -> bool:
        row = self.conn.execute(
            """
            SELECT 1
            FROM app.check_library lib
            JOIN app.library_entry le ON le.library_id = lib.id
            WHERE lib.library_type = 'WHITELIST' AND lib.status = 'ACTIVE'
              AND le.status = 'ACTIVE' AND le.file_object_id IS NOT NULL
              AND (lib.course_id IS NULL OR lib.course_id = %s)
            LIMIT 1
            """,
            (course_id,),
        ).fetchone()
        return row is not None

    def save_report(self, task: TaskData, report: dict[str, Any], report_json: bytes,
                    input_digest: str, files: dict[str, bytes] | None = None) -> uuid.UUID:
        report_digest = hashlib.sha256(report_json).hexdigest()
        object_key = f"reports/{task.assignment_id}/{task.submission_id}/{task.task_id}/report.json"
        self.storage.put(object_key, report_json, "application/json")
        try:
            with self.conn.transaction():
                report_id = self._persist_report(task, report, report_json, report_digest,
                                                 object_key, input_digest)
                self._write_classifications(task, report_id, files)
                self._notify(task, report_id, report)
            return report_id
        except Exception:
            # Change 7: roll back the object if the DB transaction failed so no
            # orphan S3 object is left behind.
            try:
                self.storage.delete(object_key)
            except Exception as exc:
                logger.warning("unable to delete orphan report object %s: %s", object_key, exc)
            raise

    def _persist_report(self, task: TaskData, report: dict[str, Any], report_json: bytes,
                        report_digest: str, object_key: str, input_digest: str) -> uuid.UUID:
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
        return report_id

    def fail(self, task: TaskData, error_code: str, detail: str) -> None:
        with self.conn.transaction():
            row = self.conn.execute(
                "SELECT attempt, max_attempts FROM app.check_task WHERE id = %s", (task.task_id,)
            ).fetchone()
            attempt = row["attempt"] if row else 0
            max_attempts = row["max_attempts"] if row else 1
            retryable = row is not None and should_retry(attempt, max_attempts)
            if retryable:
                self.conn.execute(
                    """
                    UPDATE app.check_task
                    SET status = 'QUEUED', lease_owner = NULL, lease_until = NULL,
                        error_code = %s, error_detail = %s, last_error_category = 'RETRYABLE',
                        scheduled_at = now() + make_interval(secs => %s), updated_at = now()
                    WHERE id = %s
                    """,
                    (error_code, detail[:4000], min(300, 15 * (2 ** max(0, attempt - 1))),
                     task.task_id),
                )
                return
            self.conn.execute(
                """
                UPDATE app.check_task
                SET status = 'FAILED', finished_at = now(), lease_owner = NULL, lease_until = NULL,
                    error_code = %s, error_detail = %s, last_error_category = 'PERMANENT',
                    updated_at = now()
                WHERE id = %s
                """,
                (error_code, detail[:4000], task.task_id),
            )
            self.conn.execute(
                "UPDATE app.submission SET status = 'CHECK_FAILED', row_version = row_version + 1 WHERE id = %s",
                (task.submission_id,),
            )
            # Change 2: notify the submitter (original behaviour) ...
            self._insert_notification(
                task.submitter_id, "CHECK_FAILED", "查重任务失败",
                f"提交的作业查重未能完成：{detail[:500]}", "SUBMISSION", task.submission_id,
            )
            # ... and every ADMIN plus the course instructors.
            admin_detail = (f"提交人 {task.submitter_id} 的任务 {task.task_id} 查重失败"
                            f"（错误码 {error_code}，已尝试 {attempt} 次，最大 {max_attempts} 次）。")
            for recipient_id in self._failure_recipients(task.course_id, task.submitter_id):
                self._insert_notification(recipient_id, "CHECK_FAILED_ADMIN", "查重任务失败告警",
                                          admin_detail, "SUBMISSION", task.submission_id)

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
        # Change 4: persist the per-library seq watermark so incremental reads
        # know what has already been indexed (this field is otherwise never written).
        watermark = {"seq": sequence, "watermark_at": datetime.now(timezone.utc).isoformat()}
        self.conn.execute(
            """
            UPDATE app.check_task
            SET library_watermarks = jsonb_set(coalesce(library_watermarks, '{}'::jsonb),
                                               '{CLASS}', %s::jsonb),
                updated_at = now()
            WHERE id = %s
            """,
            (json.dumps(watermark), task.task_id),
        )

    def _write_classifications(self, task: TaskData, report_id: uuid.UUID,
                               files: dict[str, bytes] | None) -> None:
        """Change 6: persist the six-bucket classification for the manifest."""
        from .classifier import classify_file

        entries: list[tuple[str, bytes | None, dict[str, Any]]] = []
        for name, content in (files or {}).items():
            entries.append((name, content, classify_file(name, content)))
        if not entries:
            if not task.online_text:
                return
            synthetic = task.online_text.encode("utf-8")
            entries.append(("online-text.txt", synthetic,
                            {"bucket": "A", "language": "TEXT", "included": True, "excludeReason": None}))
        sort_order = 0
        for name, content, cls in entries:
            sort_order += 1
            pure = PurePath(name.replace("\\", "/"))
            extension = pure.suffix.lstrip(".") or None
            if content is not None:
                size_bytes = len(content)
                digest = hashlib.sha256(content).hexdigest()
            else:
                size_bytes = 0
                digest = None
            self.conn.execute(
                """
                INSERT INTO app.file_classification
                    (report_id, submission_id, assignment_id, path, file_name, extension,
                     size_bytes, sha256, bucket, included, exclude_reason, language,
                     is_binary, sort_order)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (report_id, task.submission_id, task.assignment_id, name, pure.name, extension,
                 size_bytes, digest, cls["bucket"], cls["included"], cls["excludeReason"],
                 cls["language"], False, sort_order),
            )

    def _failure_recipients(self, course_id: uuid.UUID, submitter_id: uuid.UUID) -> list[uuid.UUID]:
        admins = self.conn.execute(
            "SELECT id FROM app.user_account WHERE status = 'ACTIVE' AND role = 'ADMIN'"
        ).fetchall()
        instructors = self.conn.execute(
            """
            SELECT DISTINCT user_id FROM app.course_member
            WHERE course_id = %s AND member_role = 'INSTRUCTOR' AND status = 'ACTIVE'
            """,
            (course_id,),
        ).fetchall()
        recipients: list[uuid.UUID] = []
        for row in admins:
            if row["id"] != submitter_id:
                recipients.append(row["id"])
        for row in instructors:
            if row["user_id"] != submitter_id:
                recipients.append(row["user_id"])
        return recipients

    def _insert_notification(self, recipient_id: uuid.UUID, notification_type: str, title: str,
                             content: str, target_type: str | None, target_id: uuid.UUID | None) -> None:
        self.conn.execute(
            """
            INSERT INTO app.notification (recipient_id, notification_type, title, content, target_type, target_id)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (recipient_id, notification_type, title, content, target_type, target_id),
        )

    def _notify(self, task: TaskData, report_id: uuid.UUID, report: dict[str, Any]) -> None:
        score = report["overallScore"]
        level = report["level"]
        self._insert_notification(
            task.submitter_id, "CHECK_COMPLETED", "查重报告已生成",
            f"本次作业总体相似度为 {score:.2f}%，检测等级：{level}。",
            "CHECK_REPORT", report_id,
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
                self._insert_notification(
                    teacher["user_id"], "CHECK_HIGH_RISK", "高风险查重提醒",
                    f"有提交的查重相似度达到 {score:.2f}%，请及时复核。",
                    "CHECK_REPORT", report_id,
                )
