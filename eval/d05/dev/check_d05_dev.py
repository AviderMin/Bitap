#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the D05 cross-source diagnostic dev set.

Checks:
  * 6-column TSV, unique ids/inputs, category counts vs manifest
  * natural sentence length categories and pinyin derivation
  * whole-sentence dictionary leakage (natural only)
  * exact + near-duplicate isolation from v3/v1/v2 expected sentences (natural)
  * source traceability to CC0 zh-TW files (natural)
  * typo / shorthand / mixed_pinyin / mixed_en derivability
  * manifest file hashes and source hashes

Run from the repository root:

    python eval/d05/dev/check_d05_dev.py

Exit code 0 means all checks passed. Output is ASCII only.
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
_pp = ROOT / ".tmp" / "pypinyin_pkg"
if _pp.is_dir():
    sys.path.insert(0, str(_pp))

import zhconv  # noqa: E402
from pypinyin import lazy_pinyin, Style  # noqa: E402

DEV_DIR = ROOT / "eval" / "d05" / "dev"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv"
SOURCE_DIR = ROOT / ".tmp" / "cc0" / "extract" / "cc0-sentences-master" / "zh-TW"
ARCHIVE = ROOT / ".tmp" / "cc0" / "cc0-sentences.zip"
WIKIPEDIA_SOURCE = ROOT / "eval" / "d05" / "dev" / "wikipedia_long_source.tsv"
NATURAL_CATEGORIES = ["natural_short", "natural_medium", "natural_long"]
CATEGORY_ORDER = NATURAL_CATEGORIES + ["typo", "shorthand", "mixed_pinyin", "mixed_en"]
SPECIAL_TARGETS = {"typo": 100, "shorthand": 100, "mixed_pinyin": 100, "mixed_en": 50}
NATURAL_LENGTH_RANGES = {"natural_short": (2, 8), "natural_medium": (9, 16), "natural_long": (17, 30)}
NEAR_DUP_JACCARD = 0.75


def cjk(ch: str) -> bool:
    return 0x4E00 <= ord(ch) <= 0x9FFF


def norm_text(text: str) -> str:
    return "".join(ch for ch in text if cjk(ch))


def pinyin_of(text: str) -> str:
    out = []
    for syllable in lazy_pinyin(text, style=Style.NORMAL):
        out.append("".join(ch for ch in syllable.lower() if "a" <= ch <= "z"))
    return "".join(out)


def syllables_of(text: str) -> list[str]:
    return ["".join(ch for ch in s.lower() if "a" <= ch <= "z")
            for s in lazy_pinyin(text, style=Style.NORMAL)]


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


def read_lines(path: Path):
    with io.open(path, encoding="utf-8") as handle:
        for raw in handle:
            yield raw.rstrip("\n").rstrip("\r")


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_number, line in enumerate(read_lines(path), start=1):
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
        })
    return rows


def load_manifest() -> dict:
    path = DEV_DIR / "manifest.json"
    if not path.is_file():
        raise SystemExit("missing manifest: " + str(path))
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load_dictionary_words() -> set[str]:
    words: set[str] = set()
    for line in read_lines(DICTIONARY):
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
        ROOT / "eval" / "v3" / "corpus" / "pinyin_cases_v3_validation.tsv",
    ]
    for path in paths:
        if not path.is_file():
            continue
        for line in read_lines(path):
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 4:
                for alternative in parts[3].split("|"):
                    normalized = norm_text(alternative)
                    if normalized:
                        texts.append(normalized)
    return texts


def damerau(a: str, b: str) -> int:
    n, m = len(a), len(b)
    infinity = n + m
    matrix = [[0] * (m + 2) for _ in range(n + 2)]
    matrix[0][0] = infinity
    for i in range(0, n + 1):
        matrix[i + 1][0] = infinity
        matrix[i + 1][1] = i
    for j in range(0, m + 1):
        matrix[0][j + 1] = infinity
        matrix[1][j + 1] = j
    last_row = {}
    for i in range(1, n + 1):
        last_match_col = 0
        for j in range(1, m + 1):
            i1 = last_row.get(b[j - 1], 0)
            j1 = last_match_col
            cost = 0 if a[i - 1] == b[j - 1] else 1
            if cost == 0:
                last_match_col = j
            matrix[i + 1][j + 1] = min(
                matrix[i][j] + cost,
                matrix[i + 1][j] + 1,
                matrix[i][j + 1] + 1,
                matrix[i1][j1] + (i - i1 - 1) + 1 + (j - j1 - 1),
            )
        last_row[a[i - 1]] = i
    return matrix[n + 1][m + 1]


def check_format(rows: list[dict], errors: list[str]) -> None:
    seen_ids: set[str] = set()
    seen_inputs: set[str] = set()
    for row in rows:
        label = row["id"]
        if not row["id"].startswith("d05-"):
            errors.append(label + ": bad id prefix")
        if row["id"] in seen_ids:
            errors.append(label + ": duplicate id")
        seen_ids.add(row["id"])
        if row["category"] not in CATEGORY_ORDER:
            errors.append(label + ": unknown category")
        if not row["input"] or not row["expected"] or not row["note"] or not row["source"]:
            errors.append(label + ": empty required field")
        if row["input"] != row["input"].lower():
            errors.append(label + ": input must be lowercase")
        if row["input"] in seen_inputs:
            errors.append(label + ": duplicate input")
        seen_inputs.add(row["input"])
        if row["category"] in NATURAL_CATEGORIES:
            normalized = norm_text(row["expected"])
            low, high = NATURAL_LENGTH_RANGES[row["category"]]
            if not (low <= len(normalized) <= high):
                errors.append(label + ": natural length mismatch " + str(len(normalized)))
            if pinyin_of(normalized) != row["input"]:
                errors.append(label + ": natural pinyin mismatch")


def check_manifest(rows: list[dict], manifest: dict, errors: list[str]) -> dict[str, int]:
    path = DEV_DIR / "dev.tsv"
    expected_sha = manifest.get("files", {}).get("dev", {}).get("sha256", "")
    actual_sha = sha256_file(path)
    if actual_sha != expected_sha:
        errors.append("manifest dev sha256 mismatch: " + actual_sha + " != " + expected_sha)
    if manifest.get("files", {}).get("dev", {}).get("rows") != len(rows):
        errors.append("manifest rows mismatch")
    counts = Counter(row["category"] for row in rows)
    actual_counts = {category: counts.get(category, 0) for category in CATEGORY_ORDER}
    if manifest.get("category_counts") != actual_counts:
        errors.append("manifest category_counts mismatch")
    if sum(actual_counts[c] for c in NATURAL_CATEGORIES) < 700:
        errors.append("natural rows below 700")
    if len(rows) < 1050:
        errors.append("total rows below 1050")
    if len(rows) > 1200:
        errors.append("total rows above 1200")
    if actual_counts["natural_long"] < 80:
        errors.append("natural_long below minimum 80")
    for category, target in SPECIAL_TARGETS.items():
        if actual_counts[category] != target:
            errors.append("special " + category + " count " + str(actual_counts[category]) + " != " + str(target))
    return actual_counts


def check_dictionary_leak(rows: list[dict], words: set[str], errors: list[str]) -> None:
    hits = 0
    for row in rows:
        if row["category"] not in NATURAL_CATEGORIES:
            continue
        if norm_text(row["expected"]) in words or row["expected"].strip() in words:
            hits += 1
            if hits <= 20:
                errors.append(row["id"] + ": whole-sentence dictionary leak")
    print("dictionary_whole_sentence_check: natural_hits=" + str(hits))


def check_reference_isolation(rows: list[dict], reference: list[str], errors: list[str]) -> None:
    reference_set = set(reference)
    exact = 0
    near = 0
    reference_items = [(len(text), char_bigrams(text)) for text in reference]
    for row in rows:
        if row["category"] not in NATURAL_CATEGORIES:
            continue
        normalized = norm_text(row["expected"])
        if normalized in reference_set:
            exact += 1
            if exact <= 20:
                errors.append(row["id"] + ": exact v1/v2/v3 overlap")
            continue
        features = char_bigrams(normalized)
        for other_length, other_features in reference_items:
            if min(len(normalized), other_length) / max(len(normalized), other_length) < 0.5:
                continue
            if jaccard(features, other_features) >= NEAR_DUP_JACCARD:
                near += 1
                if near <= 20:
                    errors.append(row["id"] + ": near-duplicate v1/v2/v3 overlap")
                break
    print("v1_v2_v3_isolation: exact=" + str(exact) + " near_dup_0.75=" + str(near))


def check_special(rows: list[dict], errors: list[str]) -> None:
    for row in rows:
        category = row["category"]
        label = row["id"]
        if category == "typo":
            marker = "正确拼音 "
            if marker not in row["note"]:
                errors.append(label + ": typo note missing correct pinyin")
                continue
            correct = row["note"].split(marker, 1)[1].split("，")[0].strip()
            if damerau(row["input"], correct) != 1:
                errors.append(label + ": typo Damerau distance != 1")
            if "漏字母" in row["note"] and len(correct) != len(row["input"]) + 1:
                errors.append(label + ": typo missing length")
            if "多字母" in row["note"] and len(correct) != len(row["input"]) - 1:
                errors.append(label + ": typo extra length")
            if "相邻交换" in row["note"]:
                diffs = [i for i, (x, y) in enumerate(zip(correct, row["input"])) if x != y]
                if len(correct) != len(row["input"]) or len(diffs) != 2 or diffs[1] != diffs[0] + 1:
                    errors.append(label + ": typo swap shape")
            if "邻键" in row["note"]:
                diffs = [i for i, (x, y) in enumerate(zip(correct, row["input"])) if x != y]
                if len(correct) != len(row["input"]) or len(diffs) != 1:
                    errors.append(label + ": typo adjacent shape")
            if norm_text(row["expected"]) not in row["expected"]:
                errors.append(label + ": typo expected malformed")
        elif category == "shorthand":
            for alternative in row["expected"].split("|"):
                syllables = syllables_of(norm_text(alternative))
                if not syllables or "".join(s[0] for s in syllables) != row["input"]:
                    errors.append(label + ": shorthand initials mismatch")
                    break
        elif category == "mixed_pinyin":
            for alternative in row["expected"].split("|"):
                syllables = syllables_of(norm_text(alternative))
                if len(syllables) < 2 or "".join(syllables[:-1]) + syllables[-1][0] != row["input"]:
                    errors.append(label + ": mixed pinyin derivation mismatch")
                    break
        elif category == "mixed_en":
            alternative = row["expected"]
            tail = ""
            for ch in reversed(alternative):
                if ch.isascii() and (ch.isalnum() or ch in ".-_"):
                    tail = ch + tail
                else:
                    break
            prefix = alternative[: len(alternative) - len(tail)]
            if not tail or not prefix or row["input"] != pinyin_of(prefix) + tail.lower():
                errors.append(label + ": mixed_en derivation mismatch")


def load_source_lines() -> dict[str, list[str]]:
    content: dict[str, list[str]] = {}
    for name in ["setences.txt", "g0v_slack_rand0m.txt", "tg_common_voice.txt",
                 "sayit.txt", "lms.txt", "cvsprint_201902.txt", "exam_text.txt",
                 "gov_press_release.txt", "cofacts.txt", "web_slang.txt", "chatlogs.txt"]:
        path = SOURCE_DIR / name
        if not path.is_file():
            continue
        lines = []
        with io.open(path, encoding="utf-8-sig", errors="replace") as handle:
            for raw in handle:
                lines.append(raw.rstrip("\n").rstrip("\r"))
        content[name] = lines
    return content


def check_sources(rows: list[dict], manifest: dict, errors: list[str]) -> None:
    source_lines = load_source_lines()
    if not source_lines:
        print("source_traceability: skipped (CC0 files not found)")
        return
    entries = {entry["name"]: entry for entry in manifest.get("source", {}).get("files", [])}
    for name, entry in entries.items():
        path = SOURCE_DIR / name
        if not path.is_file():
            errors.append("source file missing: " + name)
            continue
        if sha256_file(path) != entry.get("sha256"):
            errors.append("source sha256 mismatch: " + name)
    if ARCHIVE.is_file() and sha256_file(ARCHIVE) != manifest.get("source", {}).get("archive_sha256"):
        errors.append("source archive sha256 mismatch")
    checked = 0
    mismatches = 0
    for row in rows:
        if row["category"] not in NATURAL_CATEGORIES:
            continue
        source = row["source"]
        if source.startswith("wikipedia:"):
            continue
        if not source.startswith("cc0:"):
            errors.append(row["id"] + ": natural source is not CC0")
            continue
        parts = source.split(":")
        if len(parts) != 3:
            errors.append(row["id"] + ": bad source format")
            continue
        filename = parts[1]
        try:
            line_number = int(parts[2])
        except ValueError:
            errors.append(row["id"] + ": bad source line")
            continue
        if filename not in source_lines or not (1 <= line_number <= len(source_lines[filename])):
            errors.append(row["id"] + ": source out of range")
            continue
        checked += 1
        converted = zhconv.convert(source_lines[filename][line_number - 1].strip(), "zh-cn")
        if row["expected"] != converted and row["expected"] not in converted:
            mismatches += 1
            if mismatches <= 20:
                errors.append(row["id"] + ": source text mismatch")
    print("source_traceability: checked=" + str(checked) + " mismatches=" + str(mismatches))


def check_wikipedia_source(rows: list[dict], manifest: dict, errors: list[str]) -> None:
    if not WIKIPEDIA_SOURCE.is_file():
        if any(row["source"].startswith("wikipedia:") for row in rows):
            errors.append("wikipedia snapshot missing but wikipedia rows exist")
        else:
            print("wikipedia_traceability: skipped (snapshot not found)")
        return
    expected_sha = manifest.get("wikipedia_source", {}).get("sha256", "")
    actual_sha = sha256_file(WIKIPEDIA_SOURCE)
    if actual_sha != expected_sha:
        errors.append("wikipedia snapshot sha256 mismatch")
    records: dict[tuple[str, str], set[str]] = {}
    snapshot_rows = 0
    with io.open(WIKIPEDIA_SOURCE, encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            line = raw.rstrip("\n").rstrip("\r")
            if not line or line.startswith("pageid\t"):
                continue
            parts = line.split("\t")
            if len(parts) != 5:
                continue
            snapshot_rows += 1
            records.setdefault((parts[0].strip(), parts[1].strip()), set()).add(parts[4].strip())
    if manifest.get("wikipedia_source", {}).get("rows") != snapshot_rows:
        errors.append("wikipedia snapshot rows mismatch")
    checked = 0
    mismatches = 0
    for row in rows:
        if not row["source"].startswith("wikipedia:"):
            continue
        parts = row["source"].split(":")
        if len(parts) != 3:
            errors.append(row["id"] + ": bad wikipedia source")
            continue
        lookup = (parts[1], parts[2])
        if lookup not in records:
            errors.append(row["id"] + ": wikipedia pageid/oldid not in snapshot")
            continue
        checked += 1
        if row["expected"] not in records[lookup]:
            mismatches += 1
            if mismatches <= 20:
                errors.append(row["id"] + ": wikipedia sentence mismatch")
    print("wikipedia_traceability: checked=" + str(checked) + " mismatches=" + str(mismatches))


def main() -> int:
    errors: list[str] = []
    manifest = load_manifest()
    rows = load_rows(DEV_DIR / "dev.tsv")
    check_format(rows, errors)
    counts = check_manifest(rows, manifest, errors)
    check_dictionary_leak(rows, load_dictionary_words(), errors)
    check_reference_isolation(rows, load_reference_texts(), errors)
    check_special(rows, errors)
    check_sources(rows, manifest, errors)
    check_wikipedia_source(rows, manifest, errors)
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
