from __future__ import annotations

import difflib
import re
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Sequence

from .text import cosine_similarity, jaccard

IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+(?:\.\d+)?|==|!=|<=|>=|&&|\|\||->|=>|::|[^\s]")
STRING_LITERAL = re.compile(r"\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'", re.S)
NUMBER_LITERAL = re.compile(r"\b\d+(?:\.\d+)?\b")

LANGUAGES = {
    ".java": "JAVA", ".py": "PYTHON", ".c": "C", ".h": "C", ".cpp": "CPP",
    ".cc": "CPP", ".hpp": "CPP", ".js": "JAVASCRIPT", ".ts": "TYPESCRIPT",
    ".go": "GO", ".cs": "CSHARP", ".rs": "RUST",
}

KEYWORDS = {
    "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
    "return", "try", "catch", "finally", "throw", "throws", "new", "class", "interface",
    "extends", "implements", "public", "private", "protected", "static", "final", "void",
    "int", "long", "double", "float", "char", "boolean", "byte", "short", "string", "def",
    "import", "from", "as", "with", "lambda", "yield", "async", "await", "func", "package",
    "struct", "enum", "type", "const", "let", "var", "function", "export", "fn", "use",
}


@dataclass(slots=True)
class CodeComparison:
    source_index: int
    source_path: str
    similarity: float
    matched_excerpt: str


def detect_language(path: str) -> str:
    suffix = "." + path.rsplit(".", 1)[-1].lower() if "." in path else ""
    return LANGUAGES.get(suffix, "TEXT")


def strip_comments(source: str, language: str | None = None) -> str:
    """Remove comments while preserving string literals and line structure."""
    result: list[str] = []
    index = 0
    length = len(source)
    quote: str | None = None
    while index < length:
        char = source[index]
        next_char = source[index + 1] if index + 1 < length else ""
        if quote:
            result.append(char)
            if char == "\\" and index + 1 < length:
                result.append(next_char)
                index += 2
                continue
            if char == quote:
                quote = None
            index += 1
            continue
        if char in ("'", '"'):
            quote = char
            result.append(char)
            index += 1
            continue
        if char == "/" and next_char == "/":
            while index < length and source[index] != "\n":
                index += 1
            continue
        if char == "/" and next_char == "*":
            index += 2
            while index + 1 < length and source[index:index + 2] != "*/":
                if source[index] == "\n":
                    result.append("\n")
                index += 1
            index = min(index + 2, length)
            continue
        if char == "#" and (language or "").upper() == "PYTHON":
            while index < length and source[index] != "\n":
                index += 1
            continue
        result.append(char)
        index += 1
    return "".join(result)


def normalize_source(source: str, language: str | None = None) -> str:
    without_comments = strip_comments(source, language)
    without_strings = STRING_LITERAL.sub(" <STR> ", without_comments)
    without_numbers = NUMBER_LITERAL.sub(" <NUM> ", without_strings)
    identifiers: dict[str, str] = {}

    def replace_identifier(match: re.Match[str]) -> str:
        value = match.group(0)
        if value in {"STR", "NUM"}:
            return value
        if value.lower() in KEYWORDS:
            return value.lower()
        if value not in identifiers:
            identifiers[value] = f"ID{len(identifiers) + 1}"
        return identifiers[value]

    return IDENTIFIER.sub(replace_identifier, without_numbers)


def code_tokens(source: str, language: str | None = None) -> list[str]:
    return TOKEN.findall(normalize_source(source, language))


def token_shingles(tokens: Sequence[str], size: int = 4) -> set[tuple[str, ...]]:
    if len(tokens) < size:
        return {tuple(tokens)} if tokens else set()
    return {tuple(tokens[index:index + size]) for index in range(len(tokens) - size + 1)}


def structure_similarity(left: str, right: str, language: str | None = None) -> float:
    left_tokens = code_tokens(left, language)
    right_tokens = code_tokens(right, language)
    if not left_tokens or not right_tokens:
        return 0.0
    shingle_score = jaccard(token_shingles(left_tokens), token_shingles(right_tokens))
    sequence_score = difflib.SequenceMatcher(a=left_tokens, b=right_tokens, autojunk=False).ratio()
    frequency_score = cosine_similarity(Counter(left_tokens), Counter(right_tokens))
    return max(0.0, min(1.0, 0.50 * shingle_score + 0.30 * sequence_score + 0.20 * frequency_score))


def extract_functions(source: str, language: str | None = None) -> dict[str, str]:
    language = (language or "").upper()
    cleaned = strip_comments(source, language)
    functions: dict[str, str] = {}
    if language == "PYTHON":
        matches = list(re.finditer(r"(?m)^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*:", cleaned))
        for index, match in enumerate(matches):
            end = matches[index + 1].start() if index + 1 < len(matches) else len(cleaned)
            functions[f"python:{match.group(1)}"] = cleaned[match.start():end]
        return functions
    pattern = re.compile(
        r"(?:[A-Za-z_<>\[\],.?]+\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*\{",
        re.MULTILINE,
    )
    for match in pattern.finditer(cleaned):
        brace_index = cleaned.find("{", match.start(), match.end())
        depth = 0
        end = len(cleaned)
        for index in range(brace_index, len(cleaned)):
            if cleaned[index] == "{":
                depth += 1
            elif cleaned[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        functions[f"function:{match.group(1)}"] = cleaned[match.start():end]
    return functions


def compare_code(candidate_path: str, candidate_source: str,
                 sources: Iterable[tuple[int, str, str, str]]) -> list[CodeComparison]:
    language = detect_language(candidate_path)
    candidate_functions = extract_functions(candidate_source, language)
    comparisons: list[CodeComparison] = []
    for source_index, source_path, source_text, _owner in sources:
        source_language = detect_language(source_path)
        function_score = 0.0
        excerpt = ""
        source_functions = extract_functions(source_text, source_language)
        for candidate_name, candidate_body in candidate_functions.items():
            for _, source_body in source_functions.items():
                score = structure_similarity(candidate_body, source_body, language)
                if score > function_score:
                    function_score = score
                    excerpt = candidate_name
        whole_score = structure_similarity(candidate_source, source_text, language)
        combined = max(whole_score, function_score * 0.95)
        comparisons.append(CodeComparison(source_index, source_path, combined, excerpt or source_path))
    return sorted(comparisons, key=lambda item: item.similarity, reverse=True)
