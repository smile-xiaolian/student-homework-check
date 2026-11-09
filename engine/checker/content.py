from __future__ import annotations

import io
from pathlib import PurePath

from .code import LANGUAGES

TEXT_SUFFIXES = {
    ".txt", ".md", ".rst", ".csv", ".json", ".xml", ".yaml", ".yml",
    *LANGUAGES.keys(),
}


def decode_source(content: bytes) -> str:
    for encoding in ("utf-8-sig", "utf-8", "gb18030", "latin-1"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("utf-8", errors="replace")


def extract_text(path: str, content: bytes) -> str:
    suffix = PurePath(path).suffix.lower()
    if suffix == ".pdf":
        return _extract_pdf(content)
    if suffix == ".docx":
        return _extract_docx(content)
    if suffix in TEXT_SUFFIXES or not suffix:
        if b"\x00" in content[:4096]:
            return ""
        return decode_source(content)
    return ""


def split_candidate_files(paths: dict[str, bytes]) -> tuple[str, dict[str, str]]:
    text_parts: list[str] = []
    code_files: dict[str, str] = {}
    for path, content in paths.items():
        suffix = PurePath(path).suffix.lower()
        if suffix in LANGUAGES:
            code_files[path] = decode_source(content)
            continue
        text = extract_text(path, content)
        if text:
            text_parts.append(f"# {path}\n{text}")
    return "\n\n".join(text_parts), code_files


def source_files(paths: dict[str, bytes]) -> tuple[str, dict[str, str]]:
    return split_candidate_files(paths)


def _extract_pdf(content: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as exc:
        raise RuntimeError("pypdf is required to inspect PDF files") from exc
    reader = PdfReader(io.BytesIO(content))
    return "\n".join((page.extract_text() or "") for page in reader.pages)


def _extract_docx(content: bytes) -> str:
    try:
        from docx import Document
    except ImportError as exc:
        raise RuntimeError("python-docx is required to inspect DOCX files") from exc
    document = Document(io.BytesIO(content))
    paragraphs = [paragraph.text for paragraph in document.paragraphs if paragraph.text.strip()]
    for table in document.tables:
        for row in table.rows:
            paragraphs.append("\t".join(cell.text for cell in row.cells))
    return "\n".join(paragraphs)
