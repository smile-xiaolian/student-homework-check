"""Originality checking engine for text and source-code assignments."""

from .report import build_report
from .text import (
    clean_text,
    compare_texts,
    sentence_similarity,
    simhash,
    tokenize,
)

__all__ = [
    "build_report",
    "clean_text",
    "compare_texts",
    "sentence_similarity",
    "simhash",
    "tokenize",
]
