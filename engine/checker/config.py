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
    exclude: dict[str, bool] = field(default_factory=dict)
    # Change 1 (R19): config snapshot is normalised to a single canonical
    # structure; these fields carry the normalised switch/feature values.
    text_score_weights: dict[str, float] = field(
        default_factory=lambda: {"sentence": 0.6, "full": 0.4}
    )
    lsh_enabled: bool = False
    lsh_num_hashes: int = 128
    lsh_num_bands: int = 16
    lsh_candidate_threshold: float = 0.7
    lsh_max_candidates: int = 200
    chinese_seg_enabled: bool = False
    function_level_enabled: bool = False

    @classmethod
    def from_snapshot(cls, snapshot: dict[str, Any] | None) -> "CheckConfig":
        normalized = normalize_config(snapshot)
        thresholds = normalized["thresholds"]
        return cls(
            text_warning_threshold=thresholds["text"]["warning"],
            text_severe_threshold=thresholds["text"]["severe"],
            structure_warning_threshold=thresholds["code"]["warning"],
            structure_severe_threshold=thresholds["code"]["severe"],
            ngram_size=normalized["ngram_size"],
            simhash_bits=normalized["simhash_bits"],
            max_sources=normalized["max_sources"],
            libraries=list(normalized["libraries"]),
            exclude=dict(normalized["exclude"]),
            text_score_weights=dict(normalized["text_score_weights"]),
            lsh_enabled=normalized["lsh"]["enabled"],
            lsh_num_hashes=normalized["lsh"]["num_hashes"],
            lsh_num_bands=normalized["lsh"]["num_bands"],
            lsh_candidate_threshold=normalized["lsh"]["candidate_threshold"],
            lsh_max_candidates=normalized["lsh"]["max_candidates"],
            chinese_seg_enabled=normalized["chinese_seg"]["enabled"],
            function_level_enabled=normalized["code"]["function_level"]["enabled"],
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


def _integer(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _first(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _nested(mapping: Any, *keys: str) -> Any:
    """Walk ``mapping`` following ``keys``, returning ``None`` when absent."""
    for key in keys:
        if isinstance(mapping, dict) and key in mapping and mapping[key] is not None:
            mapping = mapping[key]
        else:
            return None
    return mapping


def _normalize_thresholds(raw: dict[str, Any], defaults: dict[str, Any]) -> dict[str, Any]:
    """Normalise the threshold block from every historical layout.

    Accepts ``thresholds`` as either the canonical nested form
    (``text.warning`` / ``code.warning``) or the historical flat form
    (``textWarning`` / ``structureWarning``), plus the standalone
    ``text_thresholds``/``textThresholds`` and ``struct_thresholds``/
    ``structure_thresholds``/``structureThresholds`` groups and the flat
    camelCase / snake_case top-level keys.
    """
    thresholds = _dict(raw.get("thresholds"))
    text_group = _dict(raw.get("text_thresholds") or raw.get("textThresholds"))
    struct_group = _dict(
        raw.get("struct_thresholds")
        or raw.get("structure_thresholds")
        or raw.get("structureThresholds")
    )
    text = defaults["text"]
    code = defaults["code"]
    text["warning"] = _number(_first(
        _nested(thresholds, "text", "warning"),
        _nested(thresholds, "textWarning"),
        _nested(text_group, "warning"),
        raw.get("textWarningThreshold"),
        raw.get("text_warning_threshold"),
    ), text["warning"])
    text["severe"] = _number(_first(
        _nested(thresholds, "text", "severe"),
        _nested(thresholds, "textSevere"),
        _nested(text_group, "severe"),
        raw.get("textSevereThreshold"),
        raw.get("text_severe_threshold"),
    ), text["severe"])
    code["warning"] = _number(_first(
        _nested(thresholds, "code", "warning"),
        _nested(thresholds, "structureWarning"),
        _nested(struct_group, "warning"),
        raw.get("structureWarningThreshold"),
        raw.get("structure_warning_threshold"),
    ), code["warning"])
    code["severe"] = _number(_first(
        _nested(thresholds, "code", "severe"),
        _nested(thresholds, "structureSevere"),
        _nested(struct_group, "severe"),
        raw.get("structureSevereThreshold"),
        raw.get("structure_severe_threshold"),
    ), code["severe"])
    return {"text": text, "code": code}


def normalize_config(raw: dict[str, Any] | None) -> dict[str, Any]:
    """Normalise a ``config_snapshot`` into the single canonical structure.

    The canonical shape is::

        {
            "mode": "TEXT",
            "ngram_size": 3,
            "simhash_bits": 64,
            "thresholds": {
                "text": {"warning": 20.0, "severe": 50.0},
                "code": {"warning": 30.0, "severe": 60.0},
            },
            "libraries": ["CLASS"],
            "exclude": {},
            "max_sources": 300,
            "text_score_weights": {"sentence": 0.6, "full": 0.4},
            "lsh": {"enabled": False, "num_hashes": 128, "num_bands": 16,
                    "candidate_threshold": 0.7, "max_candidates": 200},
            "chinese_seg": {"enabled": False},
            "code": {"function_level": {"enabled": False}},
        }

    Historical camelCase / snake_case / nested layouts are tolerated; missing
    keys receive their defaults.  Every engine read path goes through this
    function so callers only ever see one canonical structure.
    """
    raw = _dict(raw)
    canonical: dict[str, Any] = {
        "mode": "TEXT",
        "ngram_size": 3,
        "simhash_bits": 64,
        "thresholds": {
            "text": {"warning": 20.0, "severe": 50.0},
            "code": {"warning": 30.0, "severe": 60.0},
        },
        "libraries": ["CLASS"],
        "exclude": {},
        "max_sources": 300,
        "text_score_weights": {"sentence": 0.6, "full": 0.4},
        "lsh": {"enabled": False, "num_hashes": 128, "num_bands": 16,
                "candidate_threshold": 0.7, "max_candidates": 200},
        "chinese_seg": {"enabled": False},
        "code": {"function_level": {"enabled": False}},
    }

    mode = raw.get("mode")
    if mode is not None:
        canonical["mode"] = mode

    canonical["ngram_size"] = _integer(
        raw.get("ngram_size", raw.get("ngramSize")), canonical["ngram_size"]
    )
    canonical["simhash_bits"] = _integer(
        raw.get("simhash_bits", raw.get("simhashBits")), canonical["simhash_bits"]
    )
    canonical["max_sources"] = _integer(
        raw.get("max_sources", raw.get("maxSources")), canonical["max_sources"]
    )

    libraries = raw.get("libraries")
    if libraries is not None:
        canonical["libraries"] = list(libraries)
    exclude = raw.get("exclude")
    if exclude is not None:
        canonical["exclude"] = dict(exclude)

    canonical["thresholds"] = _normalize_thresholds(raw, canonical["thresholds"])

    weights = _dict(raw.get("text_score_weights") or raw.get("textScoreWeights"))
    if weights:
        base = canonical["text_score_weights"]
        canonical["text_score_weights"] = {
            "sentence": _number(weights.get("sentence", base["sentence"]), base["sentence"]),
            "full": _number(weights.get("full", base["full"]), base["full"]),
        }

    lsh_raw = _dict(raw.get("lsh"))
    if lsh_raw:
        lsh = canonical["lsh"]
        lsh["enabled"] = bool(lsh_raw.get("enabled", lsh["enabled"]))
        lsh["num_hashes"] = _integer(lsh_raw.get("num_hashes", lsh_raw.get("numHashes")), lsh["num_hashes"])
        lsh["num_bands"] = _integer(lsh_raw.get("num_bands", lsh_raw.get("numBands")), lsh["num_bands"])
        lsh["candidate_threshold"] = _number(
            lsh_raw.get("candidate_threshold", lsh_raw.get("candidateThreshold")),
            lsh["candidate_threshold"],
        )
        lsh["max_candidates"] = _integer(
            lsh_raw.get("max_candidates", lsh_raw.get("maxCandidates")), lsh["max_candidates"]
        )

    cs_raw = _dict(raw.get("chinese_seg") or raw.get("chineseSeg"))
    if cs_raw:
        canonical["chinese_seg"]["enabled"] = bool(cs_raw.get("enabled", canonical["chinese_seg"]["enabled"]))

    code_raw = _dict(raw.get("code"))
    if code_raw:
        fl_raw = _dict(code_raw.get("function_level") or code_raw.get("functionLevel"))
        if fl_raw:
            canonical["code"]["function_level"]["enabled"] = bool(
                fl_raw.get("enabled", canonical["code"]["function_level"]["enabled"])
            )

    return canonical
