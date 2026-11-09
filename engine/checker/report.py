from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Sequence

from .code import detect_language, structure_similarity
from .config import CheckConfig
from .text import best_source_for_sentence, clean_text, compare_texts, SentenceLike

ALGORITHM_VERSION = "check-1.0.0"
SENTENCE_PATTERN = re.compile(r"[^。！？!?；;\n]+[。！？!?；;]?")


@dataclass(slots=True)
class SourceDocument:
    source_id: str
    source_type: str
    submission_id: str | None
    owner_name: str | None
    text: str = ""
    files: dict[str, str] = field(default_factory=dict)


def build_report(
    *,
    assignment_id: str,
    submission_id: str,
    candidate_text: str,
    candidate_files: dict[str, str] | None,
    sources: Sequence[SourceDocument],
    config: CheckConfig | None = None,
) -> dict[str, Any]:
    config = config or CheckConfig()
    candidate_files = candidate_files or {}
    if candidate_files and _mostly_code(candidate_files):
        return _code_report(assignment_id, submission_id, candidate_files, sources, config)
    return _text_report(assignment_id, submission_id, candidate_text, sources, config)


def _text_report(assignment_id: str, submission_id: str, candidate_text: str,
                 sources: Sequence[SourceDocument], config: CheckConfig) -> dict[str, Any]:
    cleaned = clean_text(candidate_text)
    source_texts = [source.text for source in sources]
    full_comparison = compare_texts(cleaned, source_texts, ngram_size=config.ngram_size)
    source_sentences = [_sentences(source.text) for source in sources]
    segments: list[dict[str, Any]] = []
    best_by_source = [0.0 for _ in sources]
    matched_chars = [0 for _ in sources]
    weighted_total = 0.0
    weight_total = 0
    for match in SENTENCE_PATTERN.finditer(cleaned):
        text = match.group(0).strip()
        if len(text) < 8:
            continue
        sentence = SentenceLike(text=text, start=match.start(), end=match.end())
        best_index = -1
        best_score = 0.0
        best_excerpt = ""
        for source_index, sentences in enumerate(source_sentences):
            for source_sentence in sentences:
                from .text import sentence_similarity
                score = sentence_similarity(sentence.text, source_sentence, ngram_size=config.ngram_size)
                if score > best_score:
                    best_index = source_index
                    best_score = score
                    best_excerpt = source_sentence
        weight = max(1, len(text))
        weighted_total += best_score * weight
        weight_total += weight
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
                "matchType": "TEXT",
                "highlightRanges": [{"start": 0, "end": len(text)}] if best_score >= 0.35 else [],
                "matchedExcerpt": best_excerpt[:500],
            })
    sentence_score = weighted_total / weight_total if weight_total else 0.0
    text_score = round(max(sentence_score, full_comparison["max"] * 0.85) * 100, 2)
    text_score = _bounded(text_score)
    source_views = _source_views(sources, best_by_source, matched_chars, "TEXT")
    return _envelope(
        assignment_id=assignment_id,
        submission_id=submission_id,
        mode="TEXT",
        overall_score=text_score,
        text_score=text_score,
        structure_score=None,
        config=config,
        segments=segments,
        sources=source_views,
    )


def _code_report(assignment_id: str, submission_id: str, candidate_files: dict[str, str],
                 sources: Sequence[SourceDocument], config: CheckConfig) -> dict[str, Any]:
    segments: list[dict[str, Any]] = []
    best_by_source = [0.0 for _ in sources]
    matched_chars = [0 for _ in sources]
    weighted_total = 0.0
    weight_total = 0
    for path, content in sorted(candidate_files.items()):
        language = detect_language(path)
        best_index = -1
        best_score = 0.0
        best_path = ""
        for source_index, source in enumerate(sources):
            for source_path, source_content in source.files.items():
                score = structure_similarity(content, source_content, language)
                if score > best_score:
                    best_index = source_index
                    best_score = score
                    best_path = source_path
        weight = max(1, len(content))
        weighted_total += best_score * weight
        weight_total += weight
        if best_index >= 0:
            best_by_source[best_index] = max(best_by_source[best_index], best_score)
            matched_chars[best_index] += len(content)
        segments.append({
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
            "highlightRanges": [],
            "matchedFile": best_path,
        })
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
    )


def _envelope(*, assignment_id: str, submission_id: str, mode: str, overall_score: float,
              text_score: float | None, structure_score: float | None, config: CheckConfig,
              segments: list[dict[str, Any]], sources: list[dict[str, Any]]) -> dict[str, Any]:
    level = _level(mode, overall_score, config)
    top = sources[0] if sources else None
    return {
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
        "params": {
            "ngramSize": config.ngram_size,
            "simhashBits": config.simhash_bits,
            "libraries": config.libraries,
            "maxSources": config.max_sources,
        },
        "sourceCount": len(sources),
        "topSourceType": top["sourceType"] if top else None,
        "topSourceSimilarity": top["similarity"] if top else None,
        "segments": segments,
        "sources": sources,
        "excludedItems": [{"type": "REFERENCE", "description": "参考文献标注与空白噪声"}],
        "partial": False,
        "libraryStatus": {"CLASS": "OK"},
    }


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


def _excerpt(source: SourceDocument) -> str:
    text = source.text or next(iter(source.files.values()), "")
    return clean_text(text)[:240]


def _bounded(value: float) -> float:
    return max(0.0, min(100.0, value))
