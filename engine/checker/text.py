from __future__ import annotations

import hashlib
import math
import re
import unicodedata
import zlib
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Sequence

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_]+|[\u3400-\u9fff]")
SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?；;])|\n{2,}|(?<=\.)\s+(?=[A-Z\u3400-\u9fff])")
#: Contiguous runs of CJK ideographs (for the optional bigram tokeniser).
CJK_RUN = re.compile(r"[\u3400-\u9fff]+")
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


def tokenize(text: str, *, chinese_seg: bool = False) -> list[str]:
    if chinese_seg:
        return cjk_tokenize(text)
    return [token.lower() for token in TOKEN_PATTERN.findall(clean_text(text, remove_references=False))]


def _tokenize_non_cjk(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(clean_text(text, remove_references=False))]


def cjk_tokenize(text: str) -> list[str]:
    """Tokenise text using CJK bigrams (FR-K-01, default off).

    Each contiguous ``[\\u3400-\\u9fff]`` run is emitted as overlapping
    adjacent two-character bigrams (e.g. ``数据库原理`` -> ``数据``, ``据库``,
    ``库原``, ``原理``); everything outside the run uses the regular tokeniser.
    """
    cleaned = clean_text(text, remove_references=False)
    tokens: list[str] = []
    pos = 0
    for match in CJK_RUN.finditer(cleaned):
        if match.start() > pos:
            tokens.extend(_tokenize_non_cjk(cleaned[pos:match.start()]))
        run = match.group(0)
        if len(run) >= 2:
            tokens.extend(run[index:index + 2] for index in range(len(run) - 1))
        else:
            tokens.append(run)
        pos = match.end()
    if pos < len(cleaned):
        tokens.extend(_tokenize_non_cjk(cleaned[pos:]))
    return [token.lower() for token in tokens]


def minhash_signature(tokens: Sequence[str], num_hashes: int = 128, seed: int = 0) -> list[int]:
    """Bottom-k MinHash signature (R4, default off).

    For each of ``num_hashes`` hash lanes a salted ``zlib.crc32`` is computed
    per token and the minimum is kept, yielding a fixed-length signature.
    """
    if num_hashes <= 0:
        return []
    tokens = [str(token) for token in tokens]
    if not tokens:
        return [0] * num_hashes
    signature: list[int] = []
    for lane in range(num_hashes):
        salt = f"{seed + lane}:"
        signature.append(min(zlib.crc32((salt + token).encode("utf-8")) for token in tokens))
    return signature


def lsh_banding(sig: Sequence[int], num_bands: int = 16) -> set[tuple[int, ...]]:
    """Split a MinHash signature into ``num_bands`` band keys (R4, default off).

    Band ``i`` holds the values at positions ``i, i+num_bands, i+2*num_bands, ...``
    so every band is a deterministic tuple key used for LSH candidate lookup.
    """
    if num_bands <= 0:
        raise ValueError("num_bands must be positive")
    values = list(sig)
    if not values:
        return set()
    if len(values) < num_bands:
        return {tuple(values[index:index + 1]) for index in range(len(values))}
    return {tuple(values[index::num_bands]) for index in range(num_bands)}


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


def sentence_similarity(left: str, right: str, *, ngram_size: int = 3,
                        chinese_seg: bool = False) -> float:
    left_tokens = tokenize(left, chinese_seg=chinese_seg)
    right_tokens = tokenize(right, chinese_seg=chinese_seg)
    if not left_tokens or not right_tokens:
        return 0.0
    shingle_score = jaccard(ngrams(left_tokens, ngram_size), ngrams(right_tokens, ngram_size))
    cosine_score = cosine_similarity(Counter(left_tokens), Counter(right_tokens))
    hash_score = hamming_similarity(simhash(left_tokens), simhash(right_tokens))
    return max(0.0, min(1.0, 0.45 * shingle_score + 0.35 * cosine_score + 0.20 * hash_score))


def compare_texts(candidate: str, sources: Sequence[str], *, ngram_size: int = 3,
                  chinese_seg: bool = False) -> dict[str, float]:
    candidate_tokens = tokenize(candidate, chinese_seg=chinese_seg)
    candidate_shingles = set(ngrams(candidate_tokens, ngram_size))
    candidate_counts = Counter(candidate_tokens)
    candidate_hash = simhash(candidate_tokens)
    scores: list[float] = []
    for source in sources:
        source_tokens = tokenize(source, chinese_seg=chinese_seg)
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


def split_paragraphs(text: str) -> list[SentenceLike]:
    """Split cleaned text into paragraphs separated by one or more blank lines.

    Returns ``SentenceLike`` entries carrying the paragraph body and its absolute
    offsets within the cleaned text (after ``clean_text`` normalisation).
    """
    cleaned = clean_text(text)
    paragraphs: list[SentenceLike] = []
    pos = 0
    for match in re.finditer(r"\n\s*\n", cleaned):
        body = cleaned[pos:match.start()]
        _append_paragraph(paragraphs, body, pos)
        pos = match.end()
    tail = cleaned[pos:]
    _append_paragraph(paragraphs, tail, pos)
    return paragraphs


def _append_paragraph(paragraphs: list[SentenceLike], body: str, base: int) -> None:
    stripped = body.strip()
    if not stripped:
        return
    leading = len(body) - len(body.lstrip())
    trailing = len(body) - len(body.rstrip())
    paragraphs.append(SentenceLike(
        text=stripped,
        start=base + leading,
        end=base + len(body) - trailing,
    ))
