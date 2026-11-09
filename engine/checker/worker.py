from __future__ import annotations

import hashlib
import json
import logging
import os
import signal
import time
from typing import Any

from .config import CheckConfig, WorkerConfig
from .content import split_candidate_files
from .report import SourceDocument, build_report
from .repository import SourceData, TaskData, TaskRepository
from .storage import StorageClient

logger = logging.getLogger("checker.worker")
_running = True


def configure_logging() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


def process_task(repository: TaskRepository, claimed: dict[str, Any]) -> None:
    task = repository.load_task(claimed)
    logger.info("processing task=%s submission=%s attempt=%s", task.task_id, task.submission_id,
                claimed.get("attempt"))
    config = CheckConfig.from_snapshot(task.config)
    candidate_text, candidate_files = _load_candidate(repository, task)
    if not candidate_text.strip() and not candidate_files:
        raise ValueError("submission contains no readable text or supported source-code files")
    repository.heartbeat(task.task_id, "COMPARING")
    source_documents = _load_sources(repository, task, config.max_sources)
    repository.heartbeat(task.task_id, "REPORTING")
    report = build_report(
        assignment_id=str(task.assignment_id),
        submission_id=str(task.submission_id),
        candidate_text=candidate_text,
        candidate_files=candidate_files,
        sources=source_documents,
        config=config,
    )
    report_json = json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    digest_input = candidate_text if candidate_text else "\n".join(
        f"{path}:{content}" for path, content in sorted(candidate_files.items())
    )
    input_digest = hashlib.sha256(digest_input.encode("utf-8")).hexdigest()
    report_id = repository.save_report(task, report, report_json, input_digest)
    logger.info("completed task=%s report=%s score=%s level=%s", task.task_id, report_id,
                report["overallScore"], report["level"])


def _load_candidate(repository: TaskRepository, task: TaskData) -> tuple[str, dict[str, str]]:
    text = task.online_text or ""
    files = repository.load_submission_files(task.submission_id)
    file_text, code_files = split_candidate_files(files)
    if file_text:
        text = f"{text}\n\n{file_text}" if text.strip() else file_text
    return text, code_files


def _load_sources(repository: TaskRepository, task: TaskData, limit: int) -> list[SourceDocument]:
    documents: list[SourceDocument] = []
    for source in repository.load_sources(task, limit):
        own_text = source.online_text or ""
        files = repository.load_submission_files(source.submission_id)
        file_text, code_files = split_candidate_files(files)
        if file_text:
            own_text = f"{own_text}\n\n{file_text}" if own_text.strip() else file_text
        if not own_text.strip() and not code_files:
            continue
        documents.append(SourceDocument(
            source_id=f"src-{len(documents) + 1:04d}",
            source_type="CLASS",
            submission_id=str(source.submission_id),
            owner_name=source.owner_name,
            text=own_text,
            files=code_files,
        ))
    return documents


def run() -> None:
    config = WorkerConfig.from_env()
    storage = StorageClient(
        endpoint=config.storage_endpoint,
        access_key=config.storage_access_key,
        secret_key=config.storage_secret_key,
        bucket=config.storage_bucket,
        region=config.storage_region,
    )
    repository = TaskRepository(config, storage)
    logger.info("check worker %s started, poll=%ss lease=%ss", config.worker_id,
                config.poll_seconds, config.lease_seconds)
    try:
        while _running:
            task: TaskData | None = None
            try:
                claimed = repository.claim()
                if not claimed:
                    time.sleep(config.poll_seconds)
                    continue
                task = repository.load_task(claimed)
                process_task(repository, claimed)
            except Exception as exc:
                logger.exception("task processing failed")
                if task is not None:
                    try:
                        repository.fail(task, _error_code(exc), str(exc))
                    except Exception:
                        logger.exception("unable to record task failure")
                else:
                    time.sleep(config.poll_seconds)
    finally:
        repository.close()
        logger.info("check worker stopped")


def _error_code(error: Exception) -> str:
    message = str(error).lower()
    if "storage" in message or "s3" in message or "object" in message:
        return "STORAGE_READ_FAILED"
    if "readable" in message or "parse" in message or "decode" in message:
        return "PREPROCESS_FAILED"
    if isinstance(error, ValueError):
        return "INVALID_INPUT"
    return "CHECK_EXECUTION_FAILED"


def _stop(_signum: int, _frame: object) -> None:
    global _running
    _running = False


def main() -> None:
    configure_logging()
    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    run()


if __name__ == "__main__":
    main()
