from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Sequence

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_]+|[\u3400-\u9fff]")
SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?；;])|\n{2,}|(?<=\.)\s+(?=[A-Z\u3400-\u9fff])")
REFERENCE_PATTERNS = [
    re.compile(r"\[(?:\d{1,3})(?:\s*[,，\-–]\s*\d{1,3})*\]"),
    re.compile(r"【(?:\d{1,3})(?:\s*[,，\-–]\s*\d{1,3})*】"),
    re.compile(r"\((?:[A-Z][A-Za-z\-]+(?:\s+et\s+al\.?)?,?\s*\d{4}[a-z]?)\)"),
    re.compile(r"（[^（）]{1,60}[，,]\s*\d{4}[a-z]?）"),
    re.compile(r"\([^()]{1,60}[，,]\s*\d{4}[a-z]?\)"),
]


@dataclass(slots=True)
class SentenceMatch:
    source_index: int
    similarity: float
    excerpt: str


def clean_text(text: str, *, remove_references: bool = True) -> str:
    normalized = unicodedata.normalize("NFKC", text or "")
    normalized = normalized.replace("\r\n", "\n").replace("\r", "\n")
    if remove_references:
        for pattern in REFERENCE_PATTERNS:
            normalized = pattern.sub(" ", normalized)
    normalized = re.sub(r"[ \t\f\v]+", " ", normalized)
    normalized = re.sub(r"\n[ \t]+", "\n", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


def tokenize(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(clean_text(text, remove_references=False))]


def ngrams(tokens: Sequence[str], size: int = 3) -> list[tuple[str, ...]]:
    if size <= 0:
        raise ValueError("ngram size must be positive")
    if len(tokens) < size:
        return [tuple(tokens)] if tokens else []
    return [tuple(tokens[index:index + size]) for index in range(len(tokens) - size + 1)]


def jaccard(left: Iterable[object], right: Iterable[object]) -> float:
    left_set = set(left)
    right_set = set(right)
    if not left_set and not right_set:
        return 1.0
    if not left_set or not right_set:
        return 0.0
    return len(left_set & right_set) / len(left_set | right_set)


def cosine_similarity(left: Counter[str], right: Counter[str]) -> float:
    if not left or not right:
        return 0.0
    common = set(left) & set(right)
    numerator = sum(left[token] * right[token] for token in common)
    left_norm = math.sqrt(sum(value * value for value in left.values()))
    right_norm = math.sqrt(sum(value * value for value in right.values()))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return numerator / (left_norm * right_norm)


def _hash64(value: str) -> int:
    return int.from_bytes(hashlib.blake2b(value.encode("utf-8"), digest_size=8).digest(), "big")


def simhash(tokens: Sequence[str], bits: int = 64) -> int:
    if not tokens:
        return 0
    vector = [0] * bits
    counts = Counter(ngrams(tokens, 2))
    for feature, weight in counts.items():
        digest = _hash64(" ".join(feature))
        for bit in range(bits):
            vector[bit] += weight if digest & (1 << bit) else -weight
    result = 0
    for bit, value in enumerate(vector):
        if value >= 0:
            result |= 1 << bit
    return result


def hamming_similarity(left: int, right: int, bits: int = 64) -> float:
    if bits <= 0:
        return 0.0
    distance = (left ^ right).bit_count()
    return max(0.0, 1.0 - distance / bits)


def split_sentences(text: str, *, min_chars: int = 8) -> list[str]:
    cleaned = clean_text(text)
    pieces = [piece.strip() for piece in SENTENCE_SPLIT.split(cleaned) if piece and piece.strip()]
    return [piece for piece in pieces if len(piece) >= min_chars]


def sentence_similarity(left: str, right: str, *, ngram_size: int = 3) -> float:
    left_tokens = tokenize(left)
    right_tokens = tokenize(right)
    if not left_tokens or not right_tokens:
        return 0.0
    shingle_score = jaccard(ngrams(left_tokens, ngram_size), ngrams(right_tokens, ngram_size))
    cosine_score = cosine_similarity(Counter(left_tokens), Counter(right_tokens))
    hash_score = hamming_similarity(simhash(left_tokens), simhash(right_tokens))
    return max(0.0, min(1.0, 0.45 * shingle_score + 0.35 * cosine_score + 0.20 * hash_score))


def compare_texts(candidate: str, sources: Sequence[str], *, ngram_size: int = 3) -> dict[str, float]:
    candidate_tokens = tokenize(candidate)
    candidate_shingles = set(ngrams(candidate_tokens, ngram_size))
    candidate_counts = Counter(candidate_tokens)
    candidate_hash = simhash(candidate_tokens)
    scores: list[float] = []
    for source in sources:
        source_tokens = tokenize(source)
        if not source_tokens:
            scores.append(0.0)
            continue
        score = (
            0.45 * jaccard(candidate_shingles, ngrams(source_tokens, ngram_size))
            + 0.35 * cosine_similarity(candidate_counts, Counter(source_tokens))
            + 0.20 * hamming_similarity(candidate_hash, simhash(source_tokens))
        )
        scores.append(max(0.0, min(1.0, score)))
    return {
        "max": max(scores, default=0.0),
        "average": sum(scores) / len(scores) if scores else 0.0,
    }


def best_source_for_sentence(candidate: SentenceLike, sources: Sequence[str]) -> SentenceMatch:
    best_index = -1
    best_score = 0.0
    best_excerpt = ""
    for index, source in enumerate(sources):
        for source_sentence in split_sentences(source):
            score = sentence_similarity(candidate.text, source_sentence)
            if score > best_score:
                best_index = index
                best_score = score
                best_excerpt = source_sentence
    return SentenceMatch(best_index, best_score, best_excerpt)


@dataclass(slots=True)
class SentenceLike:
    text: str
    start: int
    end: int
