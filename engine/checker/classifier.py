"""Six-bucket file classification for the check manifest (FR-R2-30).

Buckets (A-F) and their default inclusion policy:

* A 核心源码 / 核心正文     -> included=True
* B 构建与脚本              -> included=True
* C 第三方与模板            -> included=False (reason: THIRD_PARTY)
* D 资源与图片              -> included=False (reason: RESOURCE)
* E 数据与测试数据          -> included=False (reason: DATA)
* F 其他                    -> included=False (reason: OTHER)

The classification is purely heuristic (extension + filename/path).  It is
written to ``app.file_classification`` when a report is generated so the
``GET /check-reports/{id}/manifest`` endpoint is never empty.
"""

from __future__ import annotations

from pathlib import PurePath

from .code import LANGUAGES

#: File extensions that carry human-readable document / core content.
CORE_TEXT_SUFFIXES = {".txt", ".md", ".rst", ".tex", ".pdf", ".docx", ".doc", ".odt"}

#: Exact base names recognised as build / packaging / CI scripts.
BUILD_FILES = {
    "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "tsconfig.json",
    "jsconfig.json", "makefile", "cmakelists.txt", "dockerfile", "requirements.txt",
    "pyproject.toml", "composer.json", "composer.lock", "gradlew", "gradlew.bat",
    ".gitignore", "webpack.config.js", "vite.config.ts", "jest.config.js", ".env",
    "setup.py", "setup.cfg",
}

#: Script / build file suffixes (shell, batch, make, gradle, toml...).
BUILD_SUFFIXES = {".sh", ".bat", ".ps1", ".cmd", ".mk", ".gradle", ".toml"}

#: Path markers that indicate third-party / vendored / template code.
THIRD_PARTY_MARKERS = {
    "node_modules", "vendor", "third_party", "third-party", "lib", "dist", "build",
    "target", "site-packages", "venv", ".venv", "__pycache__", ".git", "plugins",
}

#: Compiled / distribution artifacts (third-party, never compared).
THIRD_PARTY_SUFFIXES = {
    ".min.js", ".min.css", ".lock", ".jar", ".dll", ".so", ".a", ".o", ".class",
    ".pyc", ".exe", ".bin", ".wasm",
}

#: Binary resources / media / archives.
RESOURCE_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".svg", ".ico", ".webp", ".avif",
    ".mp3", ".mp4", ".wav", ".ogg", ".mov", ".avi", ".mkv", ".flac",
    ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz",
    ".woff", ".woff2", ".ttf", ".eot", ".otf",
}

#: Data / structured data / database files.
DATA_SUFFIXES = {
    ".csv", ".tsv", ".xls", ".xlsx", ".db", ".sqlite", ".sqlite3", ".sql", ".dat",
    ".json", ".xml", ".yaml", ".yml",
}

#: Path components that indicate test / fixture data.
TEST_DIR_MARKERS = {"test", "tests", "__tests__", "spec", "specs", "fixtures", "testdata"}


def classify_file(name: str, content: bytes | None = None) -> dict:
    """Classify a single submission file into a six-bucket group.

    Returns ``{"bucket": "A".."F", "language": str|None, "included": bool,
    "excludeReason": str|None}``.  ``content`` is accepted for API symmetry and
    may be used by future content-aware heuristics; it is not required today.
    """
    path = (name or "").replace("\\", "/")
    pure = PurePath(path)
    filename = pure.name
    lower = filename.lower()
    suffix = pure.suffix.lower()
    language = LANGUAGES.get(suffix)

    if _is_third_party(path, lower, suffix):
        return {"bucket": "C", "language": language, "included": False, "excludeReason": "THIRD_PARTY"}
    if _is_test_file(path, lower):
        return {"bucket": "E", "language": language, "included": False, "excludeReason": "DATA"}
    if suffix in LANGUAGES:
        return {"bucket": "A", "language": LANGUAGES[suffix], "included": True, "excludeReason": None}
    if lower in BUILD_FILES or suffix in BUILD_SUFFIXES:
        return {"bucket": "B", "language": language, "included": True, "excludeReason": None}
    if suffix in CORE_TEXT_SUFFIXES:
        return {"bucket": "A", "language": language, "included": True, "excludeReason": None}
    if suffix in RESOURCE_SUFFIXES:
        return {"bucket": "D", "language": None, "included": False, "excludeReason": "RESOURCE"}
    if suffix in DATA_SUFFIXES:
        return {"bucket": "E", "language": language, "included": False, "excludeReason": "DATA"}
    return {"bucket": "F", "language": language, "included": False, "excludeReason": "OTHER"}


def _is_third_party(path: str, lower: str, suffix: str) -> bool:
    if suffix in THIRD_PARTY_SUFFIXES:
        return True
    parts = set(path.split("/"))
    if parts & THIRD_PARTY_MARKERS:
        return True
    return any(marker in path for marker in ("node_modules/", "vendor/", "site-packages/"))


def _is_test_file(path: str, lower: str) -> bool:
    parts = set(path.split("/"))
    if parts & TEST_DIR_MARKERS:
        return True
    if lower.startswith("test_") or "_test." in lower or ".test." in lower or ".spec." in lower:
        return True
    return lower in {"conftest.py", "fixtures.py"}
