from __future__ import annotations

import difflib
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Sequence

from .code import (
    compare_code,
    detect_language,
    structure_similarity,
)
from .config import CheckConfig
from .text import SentenceLike, clean_text, compare_texts, split_paragraphs

# Change 4 (R11): function-level and whole-file weights when the function-level
# matcher is enabled.
FUNCTION_WEIGHT = 0.4
WHOLE_FILE_WEIGHT = 0.6

ALGORITHM_VERSION = "check-1.0.0"
SENTENCE_PATTERN = re.compile(r"[^。！？!?；;\n]+[。！？!?；;]?")

# Change 5: weighted text-score aggregation (was max(sentence, full*0.85)).
TEXT_SCORE_SENTENCE_WEIGHT = 0.6
TEXT_SCORE_FULL_WEIGHT = 0.4

# Change 4: a source whose text matches a WHITELIST material at/above this
# whole-text similarity is excluded from the comparison as 排除素材.
WHITELIST_EXCLUDE_SIMILARITY = 0.90


@dataclass(slots=True)
class SourceDocument:
    source_id: str
    source_type: str
    submission_id: str | None
    owner_name: str | None
    text: str = ""
    files: dict[str, str] = field(default_factory=dict)


def aggregate_text_score(sentence_score: float, full_max: float,
                         sentence_weight: float = TEXT_SCORE_SENTENCE_WEIGHT,
                         full_weight: float = TEXT_SCORE_FULL_WEIGHT) -> float:
    """Weighted text score (percentage) from sentence- and full-text similarity.

    ``text_score = sentence_weight * sentence_score + full_weight * full_max``,
    returned on the 0-100 scale rounded to two decimals.  The weights are
    recorded in ``params.scoreWeights`` so the score is reproducible.
    """
    raw = sentence_weight * sentence_score + full_weight * full_max
    return _bounded(round(raw * 100, 2))


def build_report(
    *,
    assignment_id: str,
    submission_id: str,
    candidate_text: str,
    candidate_files: dict[str, str] | None,
    sources: Sequence[SourceDocument],
    config: CheckConfig | None = None,
    exclusions: Sequence[str] | None = None,
    library_status: dict[str, str] | None = None,
) -> dict[str, Any]:
    config = config or CheckConfig()
    candidate_files = candidate_files or {}
    kept_sources, excluded_count = _filter_excluded(sources, exclusions, config)
    if candidate_files and _mostly_code(candidate_files):
        return _code_report(assignment_id, submission_id, candidate_files, kept_sources,
                            config, excluded_count, library_status)
    return _text_report(assignment_id, submission_id, candidate_text, kept_sources,
                        config, excluded_count, library_status)


def _filter_excluded(sources: Sequence[SourceDocument], exclusions: Sequence[str] | None,
                     config: CheckConfig) -> tuple[list[SourceDocument], int]:
    """Drop sources whose text is too similar to WHITELIST exclusion material."""
    materials = [item for item in (exclusions or []) if item and item.strip()]
    if not materials:
        return list(sources), 0
    kept: list[SourceDocument] = []
    excluded = 0
    for source in sources:
        text = source.text or "\n".join(source.files.values())
        if not text.strip():
            kept.append(source)
            continue
        similarity = compare_texts(text, materials, ngram_size=config.ngram_size,
                                   chinese_seg=config.chinese_seg_enabled)["max"]
        if similarity >= WHITELIST_EXCLUDE_SIMILARITY:
            excluded += 1
        else:
            kept.append(source)
    return kept, excluded


def _text_report(assignment_id: str, submission_id: str, candidate_text: str,
                 sources: Sequence[SourceDocument], config: CheckConfig,
                 excluded_count: int, library_status: dict[str, str] | None) -> dict[str, Any]:
    cleaned = clean_text(candidate_text, remove_references=not config.exclude.get("references", True))
    source_texts = [source.text for source in sources]
    full_comparison = compare_texts(cleaned, source_texts, ngram_size=config.ngram_size,
                                    chinese_seg=config.chinese_seg_enabled)
    source_sentences = [_sentences(source.text) for source in sources]
    source_paragraphs = [split_paragraphs(source.text) for source in sources]

    segments: list[dict[str, Any]] = []
    best_by_source = [0.0 for _ in sources]
    matched_chars = [0 for _ in sources]

    # ---- sentence level (SENTENCE) ---------------------------------------
    sentence_weighted_total = 0.0
    sentence_weight_total = 0
    for match in SENTENCE_PATTERN.finditer(cleaned):
        text = match.group(0).strip()
        if len(text) < 8:
            continue
        sentence = SentenceLike(text=text, start=match.start(), end=match.end())
        best_index, best_score, best_excerpt = _best_sentence_match(sentence, source_sentences, config)
        weight = max(1, len(text))
        sentence_weighted_total += best_score * weight
        sentence_weight_total += weight
        if best_index >= 0:
            best_by_source[best_index] = max(best_by_source[best_index], best_score)
            matched_chars[best_index] += len(text)
        if best_score >= max(0.15, config.text_warning_threshold / 100 * 0.65):
            segments.append({
                "segmentId": f"seg-{len(segments) + 1:04d}",
                "section": _section_name(cleaned, sentence.start),
                "originalText": text,
                "charStart": sentence.start,
                "charEnd": sentence.end,
                "similarity": round(best_score * 100, 2),
                "sourceId": sources[best_index].source_id if best_index >= 0 else None,
                "matchType": "SENTENCE",
                "highlightRanges": _match_ranges(text, best_excerpt) if best_score >= 0.35 else [],
                "matchedExcerpt": best_excerpt[:500],
            })
    sentence_score = sentence_weighted_total / sentence_weight_total if sentence_weight_total else 0.0

    # ---- paragraph level (PARAGRAPH) --------------------------------------
    para_weighted_total = 0.0
    para_weight_total = 0
    for paragraph in split_paragraphs(cleaned):
        if len(paragraph.text) < 8:
            continue
        best_index, best_score, best_excerpt = _best_paragraph_match(paragraph, source_paragraphs, config)
        weight = max(1, len(paragraph.text))
        para_weighted_total += best_score * weight
        para_weight_total += weight
        if best_index >= 0:
            best_by_source[best_index] = max(best_by_source[best_index], best_score)
            matched_chars[best_index] += len(paragraph.text)
        if best_score >= max(0.15, config.text_warning_threshold / 100 * 0.65):
            segments.append({
                "segmentId": f"para-{len(segments) + 1:04d}",
                "section": _section_name(cleaned, paragraph.start),
                "originalText": paragraph.text,
                "charStart": paragraph.start,
                "charEnd": paragraph.end,
                "similarity": round(best_score * 100, 2),
                "sourceId": sources[best_index].source_id if best_index >= 0 else None,
                "matchType": "PARAGRAPH",
                "highlightRanges": _match_ranges(paragraph.text, best_excerpt) if best_score >= 0.35 else [],
                "matchedExcerpt": best_excerpt[:500],
            })
    paragraph_score = para_weighted_total / para_weight_total if para_weight_total else 0.0

    # ---- full-text level (FULLTEXT) ---------------------------------------
    full_max = full_comparison["max"]
    if full_max >= max(0.15, config.text_warning_threshold / 100 * 0.65):
        full_best_index = _best_full_source(cleaned, source_texts, config)
        segments.append({
            "segmentId": "fulltext-0001",
            "section": "全文比对",
            "originalText": cleaned[:2000],
            "charStart": 0,
            "charEnd": min(len(cleaned), 2000),
            "similarity": round(full_max * 100, 2),
            "sourceId": sources[full_best_index].source_id if full_best_index >= 0 else None,
            "matchType": "FULLTEXT",
            "highlightRanges": [],
            "matchedExcerpt": sources[full_best_index].text[:500] if full_best_index >= 0 else "",
        })

    text_score = aggregate_text_score(sentence_score, full_max)
    structure_score = _bounded(round(paragraph_score * 100, 2)) if para_weight_total else None
    source_views = _source_views(sources, best_by_source, matched_chars, "TEXT")
    return _envelope(
        assignment_id=assignment_id,
        submission_id=submission_id,
        mode="TEXT",
        overall_score=text_score,
        text_score=text_score,
        structure_score=structure_score,
        config=config,
        segments=segments,
        sources=source_views,
        excluded_count=excluded_count,
        library_status=library_status,
        score_weights={"sentence": TEXT_SCORE_SENTENCE_WEIGHT, "full": TEXT_SCORE_FULL_WEIGHT},
    )


def _code_report(assignment_id: str, submission_id: str, candidate_files: dict[str, str],
                 sources: Sequence[SourceDocument], config: CheckConfig,
                 excluded_count: int, library_status: dict[str, str] | None) -> dict[str, Any]:
    segments: list[dict[str, Any]] = []
    best_by_source = [0.0 for _ in sources]
    matched_chars = [0 for _ in sources]
    weighted_total = 0.0
    weight_total = 0
    for path, content in sorted(candidate_files.items()):
        language = detect_language(path)
        function_matches: list[dict[str, object]] = []
        best_index = -1
        best_score = 0.0
        best_path = ""
        best_content = ""
        if config.function_level_enabled:
            # Change 4 (R11): function-level matching via ``compare_code``; the
            # per-file structure score is the 0.4/0.6 weighted blend of the best
            # function-level similarity and the whole-file similarity.
            sources_iterable = [
                (source_index, source_path, source_content, source.owner_name)
                for source_index, source in enumerate(sources)
                for source_path, source_content in source.files.items()
            ]
            comparisons = compare_code(path, content, sources_iterable)
            if comparisons:
                top = comparisons[0]
                best_index = top.source_index
                best_path = top.source_path
                best_content = _file_content(sources[top.source_index], top.source_path)
                best_score = FUNCTION_WEIGHT * top.function_score + WHOLE_FILE_WEIGHT * top.whole_score
                function_matches = top.function_matches
        else:
            for source_index, source in enumerate(sources):
                for source_path, source_content in source.files.items():
                    score = structure_similarity(content, source_content, language)
                    if score > best_score:
                        best_index = source_index
                        best_score = score
                        best_path = source_path
                        best_content = source_content
        weight = max(1, len(content))
        weighted_total += best_score * weight
        weight_total += weight
        if best_index >= 0:
            best_by_source[best_index] = max(best_by_source[best_index], best_score)
            matched_chars[best_index] += len(content)
        highlight_ranges = _match_ranges(content, best_content) if best_score >= 0.5 else []
        excerpt = ""
        if highlight_ranges:
            first = highlight_ranges[0]
            excerpt = content[first["start"]:first["end"]][:240]
        segment: dict[str, Any] = {
            "segmentId": f"file-{len(segments) + 1:04d}",
            "section": path,
            "pageNo": None,
            "originalText": content[:2000],
            "charStart": 0,
            "charEnd": min(len(content), 2000),
            "similarity": round(best_score * 100, 2),
            "sourceId": sources[best_index].source_id if best_index >= 0 else None,
            "matchType": "CODE",
            "language": language,
            "highlightRanges": highlight_ranges,
            "matchedExcerpt": excerpt,
            "matchedFile": best_path,
        }
        if function_matches:
            segment["functionMatches"] = function_matches
        segments.append(segment)
    structure_score = _bounded(round(weighted_total / weight_total * 100, 2) if weight_total else 0.0)
    source_views = _source_views(sources, best_by_source, matched_chars, "CODE")
    return _envelope(
        assignment_id=assignment_id,
        submission_id=submission_id,
        mode="CODE",
        overall_score=structure_score,
        text_score=None,
        structure_score=structure_score,
        config=config,
        segments=segments,
        sources=source_views,
        excluded_count=excluded_count,
        library_status=library_status,
    )


def _envelope(*, assignment_id: str, submission_id: str, mode: str, overall_score: float,
              text_score: float | None, structure_score: float | None, config: CheckConfig,
              segments: list[dict[str, Any]], sources: list[dict[str, Any]],
              excluded_count: int, library_status: dict[str, str] | None,
              score_weights: dict[str, float] | None = None) -> dict[str, Any]:
    level = _level(mode, overall_score, config)
    top = sources[0] if sources else None
    params: dict[str, Any] = {
        "ngramSize": config.ngram_size,
        "simhashBits": config.simhash_bits,
        "libraries": config.libraries,
        "exclude": config.exclude,
        "maxSources": config.max_sources,
    }
    if score_weights:
        params["scoreWeights"] = score_weights
    report = {
        "schemaVersion": "1.0",
        "assignmentId": assignment_id,
        "submissionId": submission_id,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "algorithmVersion": ALGORITHM_VERSION,
        "mode": mode,
        "overallScore": overall_score,
        "textScore": text_score,
        "structureScore": structure_score,
        "level": level,
        "thresholds": {
            "textWarning": config.text_warning_threshold,
            "textSevere": config.text_severe_threshold,
            "structureWarning": config.structure_warning_threshold,
            "structureSevere": config.structure_severe_threshold,
        },
        "params": params,
        "sourceCount": len(sources),
        "topSourceType": top["sourceType"] if top else None,
        "topSourceSimilarity": top["similarity"] if top else None,
        "segments": segments,
        "sources": sources,
        "excludedItems": _excluded_items(config, excluded_count),
        "partial": False,
        "libraryStatus": library_status or {"CLASS": "OK"},
    }
    return report


def _excluded_items(config: CheckConfig, excluded_count: int) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    if config.exclude.get("references", True):
        items.append({"type": "REFERENCE", "description": "参考文献标注与空白噪声已剔除", "count": -1})
    if excluded_count:
        items.append({
            "type": "WHITELIST",
            "description": f"与白名单素材相似度≥{WHITELIST_EXCLUDE_SIMILARITY:.2f}，已排除 {excluded_count} 个来源",
            "count": excluded_count,
        })
    if not items:
        items.append({"type": "NONE", "description": "无排除项", "count": 0})
    return items


def _source_views(sources: Sequence[SourceDocument], scores: Sequence[float],
                  matched_chars: Sequence[int], match_type: str) -> list[dict[str, Any]]:
    views: list[dict[str, Any]] = []
    for index, source in enumerate(sources):
        score = scores[index]
        if score <= 0.001:
            continue
        title = "同班同学提交（匿名）" if source.source_type == "CLASS" else source.source_type
        locator = f"匹配 {matched_chars[index]} 字符"
        if source.files and match_type == "CODE":
            locator = f"代码结构匹配 · {len(source.files)} 个文件"
        views.append({
            "sourceId": source.source_id,
            "sourceType": source.source_type,
            "similarity": round(score * 100, 2),
            "title": title,
            "submissionId": source.submission_id,
            "studentVisible": False,
            "locator": locator,
            "matchedChars": matched_chars[index],
            "excerpt": _excerpt(source),
        })
    return sorted(views, key=lambda item: item["similarity"], reverse=True)


def _level(mode: str, score: float, config: CheckConfig) -> str:
    warning = config.text_warning_threshold if mode == "TEXT" else config.structure_warning_threshold
    severe = config.text_severe_threshold if mode == "TEXT" else config.structure_severe_threshold
    if score >= severe:
        return "SEVERE"
    if score >= warning:
        return "WARNING"
    return "PASS"


def _best_sentence_match(sentence: SentenceLike, source_sentences: Sequence[Sequence[str]],
                         config: CheckConfig) -> tuple[int, float, str]:
    best_index = -1
    best_score = 0.0
    best_excerpt = ""
    for source_index, sentences in enumerate(source_sentences):
        for source_sentence in sentences:
            from .text import sentence_similarity
            score = sentence_similarity(sentence.text, source_sentence, ngram_size=config.ngram_size,
                                        chinese_seg=config.chinese_seg_enabled)
            if score > best_score:
                best_index = source_index
                best_score = score
                best_excerpt = source_sentence
    return best_index, best_score, best_excerpt


def _best_paragraph_match(paragraph: SentenceLike, source_paragraphs: Sequence[Sequence[SentenceLike]],
                          config: CheckConfig) -> tuple[int, float, str]:
    best_index = -1
    best_score = 0.0
    best_excerpt = ""
    for source_index, paragraphs in enumerate(source_paragraphs):
        for source_paragraph in paragraphs:
            from .text import sentence_similarity
            score = sentence_similarity(paragraph.text, source_paragraph.text,
                                        ngram_size=config.ngram_size,
                                        chinese_seg=config.chinese_seg_enabled)
            if score > best_score:
                best_index = source_index
                best_score = score
                best_excerpt = source_paragraph.text
    return best_index, best_score, best_excerpt


def _best_full_source(cleaned: str, source_texts: Sequence[str],
                      config: CheckConfig) -> int:
    best_index = -1
    best_score = 0.0
    for index, source_text in enumerate(source_texts):
        if not source_text.strip():
            continue
        score = compare_texts(cleaned, [source_text], ngram_size=config.ngram_size,
                              chinese_seg=config.chinese_seg_enabled)["max"]
        if score > best_score:
            best_index = index
            best_score = score
    return best_index


def _match_ranges(text: str, source: str, min_block: int | None = None) -> list[dict[str, int]]:
    """Locate the real overlapping ranges of ``text`` against ``source``.

    Uses ``difflib.SequenceMatcher.get_matching_blocks`` (``autojunk=False``)
    so the longest common substrings are located in the original text.  Ranges
    are returned sorted by ``start`` and are guaranteed non-overlapping.
    """
    if not text or not source:
        return []
    if min_block is None:
        min_block = max(3, len(text) // 30)
    matcher = difflib.SequenceMatcher(a=text, b=source, autojunk=False)
    ranges: list[dict[str, int]] = []
    for block in matcher.get_matching_blocks():
        if block.size >= min_block:
            ranges.append({"start": block.a, "end": block.a + block.size})
    merged: list[dict[str, int]] = []
    for item in ranges:
        if merged and item["start"] < merged[-1]["end"]:
            merged[-1]["end"] = max(merged[-1]["end"], item["end"])
        else:
            merged.append(item)
    return merged


def _sentences(text: str) -> list[str]:
    cleaned = clean_text(text)
    return [match.group(0).strip() for match in SENTENCE_PATTERN.finditer(cleaned)
            if len(match.group(0).strip()) >= 8]


def _section_name(text: str, offset: int) -> str:
    prefix = text[:offset]
    headings = list(re.finditer(r"(?m)^(?:#{1,6}\s*)?([^\n]{1,60}(?:章|节|部分|Part|Chapter)[^\n]{0,30})$", prefix))
    if headings:
        return headings[-1].group(1).strip()
    return "正文片段"


def _mostly_code(files: dict[str, str]) -> bool:
    code_files = [path for path in files if detect_language(path) != "TEXT"]
    return bool(code_files) and len(code_files) >= max(1, len(files) // 2)


def _file_content(source: SourceDocument, path: str) -> str:
    return source.files.get(path, "")


def _excerpt(source: SourceDocument) -> str:
    text = source.text or next(iter(source.files.values()), "")
    return clean_text(text)[:240]


def _bounded(value: float) -> float:
    return max(0.0, min(100.0, value))
