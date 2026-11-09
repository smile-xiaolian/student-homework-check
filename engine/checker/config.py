from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class CheckConfig:
    text_warning_threshold: float = 20.0
    text_severe_threshold: float = 50.0
    structure_warning_threshold: float = 30.0
    structure_severe_threshold: float = 60.0
    ngram_size: int = 3
    simhash_bits: int = 64
    max_sources: int = 300
    libraries: list[str] = field(default_factory=lambda: ["CLASS"])

    @classmethod
    def from_snapshot(cls, snapshot: dict[str, Any] | None) -> "CheckConfig":
        snapshot = snapshot or {}
        thresholds = snapshot.get("thresholds") or {}
        text_thresholds = snapshot.get("text_thresholds") or {}
        struct_thresholds = snapshot.get("struct_thresholds") or snapshot.get("structure_thresholds") or {}
        return cls(
            text_warning_threshold=_number(
                snapshot.get("textWarningThreshold",
                             text_thresholds.get("warning", thresholds.get("textWarning"))), 20.0
            ),
            text_severe_threshold=_number(
                snapshot.get("textSevereThreshold",
                             text_thresholds.get("severe", thresholds.get("textSevere"))), 50.0
            ),
            structure_warning_threshold=_number(
                snapshot.get("structureWarningThreshold",
                             struct_thresholds.get("warning", thresholds.get("structureWarning"))), 30.0
            ),
            structure_severe_threshold=_number(
                snapshot.get("structureSevereThreshold",
                             struct_thresholds.get("severe", thresholds.get("structureSevere"))), 60.0
            ),
            ngram_size=int(snapshot.get("ngramSize", 3)),
            max_sources=int(snapshot.get("maxSources", 300)),
            libraries=list(snapshot.get("libraries", ["CLASS"])),
        )


@dataclass(slots=True)
class WorkerConfig:
    database_url: str
    storage_endpoint: str
    storage_access_key: str
    storage_secret_key: str
    storage_bucket: str
    storage_region: str = "us-east-1"
    worker_id: str = field(default_factory=lambda: f"worker-{os.getpid()}")
    poll_seconds: float = 2.0
    lease_seconds: int = 90
    heartbeat_seconds: int = 20

    @classmethod
    def from_env(cls) -> "WorkerConfig":
        database_url = os.getenv("DATABASE_URL")
        if not database_url:
            database_url = (
                f"postgresql://{os.getenv('DB_USERNAME', 'edu_app')}:"
                f"{os.getenv('DB_PASSWORD', 'change-me-postgres')}@"
                f"{os.getenv('DB_HOST', 'localhost')}:{os.getenv('DB_PORT', '5432')}/"
                f"{os.getenv('POSTGRES_DB', 'edu_submission')}"
            )
        return cls(
            database_url=database_url,
            storage_endpoint=os.getenv("STORAGE_ENDPOINT", "http://localhost:9000"),
            storage_access_key=os.getenv("STORAGE_ACCESS_KEY", "edu_minio"),
            storage_secret_key=os.getenv("STORAGE_SECRET_KEY", "change-me-minio-password"),
            storage_bucket=os.getenv("STORAGE_BUCKET", "edu-submission"),
            storage_region=os.getenv("STORAGE_REGION", "us-east-1"),
            worker_id=os.getenv("CHECK_WORKER_ID", f"worker-{os.getpid()}"),
            poll_seconds=float(os.getenv("CHECK_POLL_SECONDS", "2")),
            lease_seconds=int(os.getenv("CHECK_LEASE_SECONDS", "90")),
            heartbeat_seconds=int(os.getenv("CHECK_HEARTBEAT_SECONDS", "20")),
        )


def _number(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default
