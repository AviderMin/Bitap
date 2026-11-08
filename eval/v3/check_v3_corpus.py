#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the v3 training-source-independent (CC0) acceptance corpus.

Checks:
  * 6-column TSV format, unique ids/inputs, length-category consistency
  * category counts and manifest SHA-256 / row counts
  * whole-sentence leakage against app/src/main/assets/pinyin_dict.tsv
  * exact and near-duplicate overlap with v1 and v2 expected sentences
  * source traceability back to the four CC0 zh-TW files (via zhconv)
  * source file / archive SHA-256 against manifest.json
  * pinyin derivation (pypinyin) for every expected sentence

Run from the repository root:

    python eval/v3/check_v3_corpus.py

Exit code 0 means all checks passed. Output is ASCII only.
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_pp = ROOT / ".tmp" / "pypinyin_pkg"
if _pp.is_dir():
    sys.path.insert(0, str(_pp))

import zhconv  # noqa: E402
from pypinyin import lazy_pinyin, Style  # noqa: E402

CORPUS_DIR = ROOT / "eval" / "v3" / "corpus"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv"
SOURCE_DIR = ROOT / ".tmp" / "cc0" / "extract" / "cc0-sentences-master" / "zh-TW"
ARCHIVE = ROOT / ".tmp" / "cc0" / "cc0-sentences.zip"
SOURCE_FILES = ["setences.txt", "chatlogs.txt", "g0v_slack_rand0m.txt", "tg_common_voice.txt"]
CATEGORY_ORDER = ["natural_short", "natural_medium", "natural_long"]
MIN_COUNTS = {"natural_short": 200, "natural_medium": 150, "natural_long": 20}
LENGTH_RANGES = {"natural_short": (2, 8), "natural_medium": (9, 16), "natural_long": (17, 24)}


def cjk(ch: str) -> bool:
    return 0x4E00 <= ord(ch) <= 0x9FFF


def norm_text(text: str) -> str:
    return "".join(ch for ch in text if cjk(ch))


def pinyin_of(text: str) -> str:
    out = []
    for syllable in lazy_pinyin(text, style=Style.NORMAL):
        out.append("".join(ch for ch in syllable.lower() if "a" <= ch <= "z"))
    return "".join(out)


def char_bigrams(text: str) -> set[str]:
    if len(text) < 2:
        return {text} if text else set()
    return {text[i:i + 2] for i in range(len(text) - 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    union = left | right
    return len(left & right) / len(union) if union else 0.0


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with io.open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    with io.open(path, encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            line = raw.rstrip("\n").rstrip("\r")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 6:
                raise SystemExit(str(path) + ":" + str(line_number) + ": expected 6 columns")
            rows.append({
                "id": parts[0].strip(),
                "category": parts[1].strip(),
                "input": parts[2].strip(),
                "expected": parts[3],
                "note": parts[4],
                "source": parts[5],
                "line": line_number,
            })
    return rows


def load_manifest() -> dict:
    path = CORPUS_DIR / "manifest.json"
    if not path.is_file():
        raise SystemExit("missing manifest: " + str(path))
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load_dictionary_words() -> set[str]:
    words: set[str] = set()
    with io.open(DICTIONARY, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2 and parts[1].strip():
                words.add(parts[1].strip())
    return words


def load_reference_texts() -> list[str]:
    texts: list[str] = []
    paths = [
        ROOT / "eval" / "pinyin_cases_dev.tsv",
        ROOT / "eval" / "pinyin_cases_holdout.tsv",
        ROOT / "eval" / "pinyin_cases_sealed.tsv",
        ROOT / "eval" / "v2" / "corpus" / "pinyin_cases_v2_dev.tsv",
        ROOT / "eval" / "v2" / "corpus" / "pinyin_cases_v2_validation.tsv",
    ]
    for path in paths:
        if not path.is_file():
            continue
        with io.open(path, encoding="utf-8") as handle:
            for line in handle:
                parts = line.rstrip("\n").rstrip("\r").split("\t")
                if len(parts) >= 4:
                    for alternative in parts[3].split("|"):
                        normalized = norm_text(alternative)
                        if normalized:
                            texts.append(normalized)
    return texts


def check_format(rows: list[dict], errors: list[str]) -> None:
    seen_ids: set[str] = set()
    seen_inputs: set[str] = set()
    for row in rows:
        label = "v3:" + str(row["line"])
        if not row["id"].startswith("v3v-"):
            errors.append(label + ": bad id prefix")
        if row["id"] in seen_ids:
            errors.append(label + ": duplicate id")
        seen_ids.add(row["id"])
        if row["category"] not in CATEGORY_ORDER:
            errors.append(label + ": unknown category " + row["category"])
        if not row["input"] or not row["expected"] or not row["note"] or not row["source"]:
            errors.append(label + ": empty required field")
        if row["input"] != row["input"].lower():
            errors.append(label + ": input must be lowercase")
        if any(not ("a" <= ch <= "z") for ch in row["input"]):
            errors.append(label + ": input must be pure pinyin letters")
        if row["input"] in seen_inputs:
            errors.append(label + ": duplicate input")
        seen_inputs.add(row["input"])
        normalized = norm_text(row["expected"])
        length = len(normalized)
        low, high = LENGTH_RANGES[row["category"]]
        if not (low <= length <= high):
            errors.append(label + ": category length mismatch " + str(length) + " for " + row["category"])
        if pinyin_of(normalized) != row["input"]:
            errors.append(label + ": pinyin mismatch")


def check_manifest(rows: list[dict], manifest: dict, errors: list[str]) -> dict[str, int]:
    path = CORPUS_DIR / "pinyin_cases_v3_validation.tsv"
    expected_sha = manifest.get("files", {}).get("validation", {}).get("sha256", "")
    actual_sha = sha256_file(path)
    if actual_sha != expected_sha:
        errors.append("manifest validation sha256 mismatch: " + actual_sha + " != " + expected_sha)
    expected_rows = manifest.get("files", {}).get("validation", {}).get("rows")
    if expected_rows != len(rows):
        errors.append("manifest rows mismatch: " + str(expected_rows) + " != " + str(len(rows)))
    counts = Counter(row["category"] for row in rows)
    actual_counts = {category: counts.get(category, 0) for category in CATEGORY_ORDER}
    if manifest.get("category_counts") != actual_counts:
        errors.append("manifest category_counts mismatch")
    for category, minimum in MIN_COUNTS.items():
        if actual_counts[category] < minimum:
            errors.append("category " + category + " below floor: " + str(actual_counts[category]))
    if len(rows) < 500:
        errors.append("validation rows below 500")
    return actual_counts


def check_dictionary_leak(rows: list[dict], dict_words: set[str], errors: list[str]) -> int:
    hits = 0
    for row in rows:
        if norm_text(row["expected"]) in dict_words or row["expected"].strip() in dict_words:
            hits += 1
            if hits <= 20:
                errors.append(row["id"] + ": whole-sentence dictionary leak")
    print("dictionary_whole_sentence_check: rows=" + str(len(rows)) + " hits=" + str(hits))
    return hits


def check_reference_overlap(rows: list[dict], reference_texts: list[str], errors: list[str]) -> None:
    reference_set = set(reference_texts)
    exact = 0
    for row in rows:
        if norm_text(row["expected"]) in reference_set:
            exact += 1
            if exact <= 20:
                errors.append(row["id"] + ": exact v1/v2 overlap")
    reference_features = [(len(text), char_bigrams(text)) for text in reference_texts]
    near = 0
    for row in rows:
        normalized = norm_text(row["expected"])
        features = char_bigrams(normalized)
        for other_length, other_features in reference_features:
            if min(len(normalized), other_length) / max(len(normalized), other_length) < 0.5:
                continue
            if jaccard(features, other_features) >= 0.80:
                near += 1
                if near <= 20:
                    errors.append(row["id"] + ": v1/v2 near-duplicate overlap")
                break
    print("v1_v2_overlap_check: exact=" + str(exact) + " near_dup_0.80=" + str(near))


def load_source_lines() -> dict[str, list[str]]:
    content: dict[str, list[str]] = {}
    for filename in SOURCE_FILES:
        path = SOURCE_DIR / filename
        if not path.is_file():
            continue
        lines: list[str] = []
        with io.open(path, encoding="utf-8-sig", errors="replace") as handle:
            for raw in handle:
                lines.append(raw.rstrip("\n").rstrip("\r"))
        content[filename] = lines
    return content


def check_source(rows: list[dict], manifest: dict, errors: list[str]) -> None:
    source_lines = load_source_lines()
    files_manifest = {entry["name"]: entry for entry in manifest.get("source", {}).get("files", [])}
    if not source_lines:
        print("source_traceability: skipped (CC0 zh-TW files not found)")
        return
    for filename, entry in files_manifest.items():
        path = SOURCE_DIR / filename
        if not path.is_file():
            errors.append("source file missing: " + filename)
            continue
        if sha256_file(path) != entry.get("sha256"):
            errors.append("source file sha256 mismatch: " + filename)
    if ARCHIVE.is_file():
        if sha256_file(ARCHIVE) != manifest.get("source", {}).get("archive_sha256"):
            errors.append("source archive sha256 mismatch")
    checked = 0
    mismatches = 0
    for row in rows:
        source = row["source"]
        if not source.startswith("cc0:"):
            errors.append(row["id"] + ": source is not CC0")
            continue
        parts = source.split(":")
        if len(parts) != 3:
            errors.append(row["id"] + ": bad source format")
            continue
        filename = parts[1]
        try:
            line_number = int(parts[2])
        except ValueError:
            errors.append(row["id"] + ": bad source line number")
            continue
        if filename not in source_lines:
            errors.append(row["id"] + ": unknown source file " + filename)
            continue
        if not (1 <= line_number <= len(source_lines[filename])):
            errors.append(row["id"] + ": source line out of range")
            continue
        checked += 1
        converted = zhconv.convert(source_lines[filename][line_number - 1].strip(), "zh-cn")
        if converted != row["expected"]:
            mismatches += 1
            if mismatches <= 20:
                errors.append(row["id"] + ": source text mismatch")
    print("source_traceability: checked=" + str(checked) + " mismatches=" + str(mismatches))


def main() -> int:
    errors: list[str] = []
    manifest = load_manifest()
    rows = load_rows(CORPUS_DIR / "pinyin_cases_v3_validation.tsv")
    check_format(rows, errors)
    counts = check_manifest(rows, manifest, errors)
    dict_words = load_dictionary_words()
    check_dictionary_leak(rows, dict_words, errors)
    check_reference_overlap(rows, load_reference_texts(), errors)
    check_source(rows, manifest, errors)
    print("rows: " + str(len(rows)))
    print("category_counts: " + json.dumps(counts, ensure_ascii=False))
    if errors:
        print("FAILED errors=" + str(len(errors)))
        for message in errors[:80]:
            print("ERROR " + message)
        return 1
    print("OK all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
