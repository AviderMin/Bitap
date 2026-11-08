#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the v2 independent pinyin evaluation corpus.

Checks performed:
  * 6-column TSV format, unique case ids and unique inputs (within and across splits)
  * required category counts from the manifest / minimum floors
  * dev/validation near-duplicate isolation (character 2-gram Jaccard >= 0.75)
  * whole-sentence leakage against app/src/main/assets/pinyin_dict.tsv
  * special-category derivability: homophone pinyin, shorthand initials,
    mixed-pinyin construction, typo Damerau distance, number/punctuation,
    proper-noun pinyin, mixed Chinese/English prefix
  * SHA-256 of both generated files against manifest.json
  * optional source traceability against .tmp/tatoeba/cmn_sentences.tsv

Run from the repository root:

    python eval/v2/check_v2_corpus.py

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

from pypinyin import lazy_pinyin, Style  # noqa: E402

CORPUS_DIR = ROOT / "eval" / "v2" / "corpus"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv"
TATOEBA = ROOT / ".tmp" / "tatoeba" / "cmn_sentences.tsv"
CATEGORY_ORDER = [
    "natural_short",
    "natural_medium",
    "natural_long",
    "homophone",
    "shorthand",
    "typo",
    "mixed_pinyin",
    "number_punct",
    "proper_noun",
    "mixed_en",
]
NATURAL = {"natural_short", "natural_medium", "natural_long"}
MIN_FLOORS = {
    "dev": {
        "natural_short": 200, "natural_medium": 200, "natural_long": 150,
        "homophone": 20, "shorthand": 20, "typo": 20, "mixed_pinyin": 20,
        "number_punct": 20, "proper_noun": 20, "mixed_en": 20,
    },
    "validation": {
        "natural_short": 100, "natural_medium": 100, "natural_long": 75,
        "homophone": 10, "shorthand": 10, "typo": 10, "mixed_pinyin": 10,
        "number_punct": 10, "proper_noun": 10, "mixed_en": 10,
    },
}
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


def minhash_signature(features: set[str], num_hashes: int = 64) -> list[int]:
    pairs = []
    for feature in features:
        raw = feature.encode("utf-8")
        h1 = int.from_bytes(hashlib.blake2b(raw, digest_size=8).digest(), "big")
        h2 = int.from_bytes(hashlib.blake2b(raw + b"\x01", digest_size=8).digest(), "big") | 1
        pairs.append((h1, h2))
    mask = (1 << 64) - 1
    signature = []
    for i in range(num_hashes):
        best = (1 << 65)
        for h1, h2 in pairs:
            value = (h1 + i * h2) & mask
            if value < best:
                best = value
        signature.append(best)
    return signature


def cross_split_near_duplicates(dev_texts: list[str], val_texts: list[str]):
    """Return list of (dev_index, val_index, jaccard) pairs above threshold."""
    dev_features = [char_bigrams(text) for text in dev_texts]
    val_features = [char_bigrams(text) for text in val_texts]
    dev_signatures = [minhash_signature(fs) for fs in dev_features]
    val_signatures = [minhash_signature(fs) for fs in val_features]
    band_rows = 4
    buckets: dict[tuple, tuple[list[int], list[int]]] = {}
    for side, signatures in ((0, dev_signatures), (1, val_signatures)):
        for index, signature in enumerate(signatures):
            for band in range(len(signature) // band_rows):
                key = (band, tuple(signature[band * band_rows:(band + 1) * band_rows]))
                entry = buckets.get(key)
                if entry is None:
                    entry = ([], [])
                    buckets[key] = entry
                entry[side].append(index)
    pairs = set()
    for dev_indexes, val_indexes in buckets.values():
        if not dev_indexes or not val_indexes:
            continue
        for i in dev_indexes:
            for j in val_indexes:
                pairs.add((i, j))
    hits = []
    for i, j in pairs:
        li = len(dev_texts[i])
        lj = len(val_texts[j])
        if min(li, lj) / max(li, lj) < 0.6:
            continue
        value = jaccard(dev_features[i], val_features[j])
        if value >= NEAR_DUP_JACCARD:
            hits.append((i, j, value))
    return hits


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


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    with io.open(path, encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            line = raw.rstrip("\n").rstrip("\r")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 6:
                raise SystemExit(str(path) + ":" + str(line_number) + ": expected 6 tab-separated columns")
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


def load_tatoeba(path: Path) -> dict[int, str]:
    result: dict[int, str] = {}
    if not path.is_file():
        return result
    with io.open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").rstrip("\r").split("\t")
            if len(parts) >= 3 and parts[1] == "cmn":
                try:
                    result[int(parts[0])] = parts[2]
                except ValueError:
                    continue
    return result


def check_format(rows: list[dict], split: str, errors: list[str]) -> None:
    seen_ids = set()
    seen_inputs = {}
    for row in rows:
        prefix = split + ":" + str(row["line"])
        if not row["id"]:
            errors.append(prefix + ": empty id")
        if row["id"] in seen_ids:
            errors.append(prefix + ": duplicate id " + row["id"])
        seen_ids.add(row["id"])
        if row["category"] not in CATEGORY_ORDER:
            errors.append(prefix + ": unknown category " + row["category"])
        if not row["input"]:
            errors.append(prefix + ": empty input")
        if not row["expected"]:
            errors.append(prefix + ": empty expected")
        for alternative in row["expected"].split("|"):
            if not alternative.strip():
                errors.append(prefix + ": empty expected alternative")
        if not row["note"]:
            errors.append(prefix + ": empty note")
        if not row["source"]:
            errors.append(prefix + ": empty source")
        if any(not ("a" <= ch <= "z" or ch.isdigit() or ch in " ,!?.'") for ch in row["input"].lower()):
            errors.append(prefix + ": input has unexpected characters: " + row["input"])
        if row["input"] in seen_inputs:
            errors.append(prefix + ": duplicate input " + row["input"])
        seen_inputs[row["input"]] = row["id"]


def check_category_counts(rows: dict[str, list[dict]], manifest: dict, errors: list[str]) -> dict[str, dict[str, int]]:
    actual: dict[str, dict[str, int]] = {}
    for split in ("dev", "validation"):
        counter = Counter(row["category"] for row in rows[split])
        actual[split] = {category: counter.get(category, 0) for category in CATEGORY_ORDER}
        for category, minimum in MIN_FLOORS[split].items():
            if actual[split][category] < minimum:
                errors.append(split + ": category " + category + " below floor: " + str(actual[split][category]) + " < " + str(minimum))
        recorded = manifest.get("category_counts", {}).get(split)
        if recorded != actual[split]:
            errors.append(split + ": manifest category_counts mismatch")
        manifest_rows = manifest.get("files", {}).get(split, {}).get("rows")
        if manifest_rows != len(rows[split]):
            errors.append(split + ": manifest rows mismatch " + str(manifest_rows) + " != " + str(len(rows[split])))
    return actual


def check_manifest_hashes(manifest: dict, errors: list[str]) -> None:
    for split in ("dev", "validation"):
        path = CORPUS_DIR / ("pinyin_cases_v2_" + split + ".tsv")
        expected_sha = manifest.get("files", {}).get(split, {}).get("sha256", "")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != expected_sha:
            errors.append(split + ": sha256 mismatch " + digest + " != " + expected_sha)


def check_whole_sentence_leak(rows: dict[str, list[dict]], dictionary_words: set[str], errors: list[str]) -> None:
    hits = 0
    checked = 0
    for split in ("dev", "validation"):
        for row in rows[split]:
            if row["category"] not in NATURAL:
                continue
            checked += 1
            expected = row["expected"]
            if norm_text(expected) in dictionary_words or expected.strip() in dictionary_words:
                hits += 1
                if len(errors) < 200:
                    errors.append(split + ": whole-sentence dictionary leak in " + row["id"])
    print("dictionary_whole_sentence_check: natural_rows=" + str(checked) + " hits=" + str(hits))


def letters_only(text: str) -> str:
    return "".join(ch for ch in text.lower() if "a" <= ch <= "z")


def check_semantics(rows: dict[str, list[dict]], errors: list[str]) -> None:
    for split in ("dev", "validation"):
        for row in rows[split]:
            category = row["category"]
            input_text = row["input"]
            expected_alts = [alt for alt in row["expected"].split("|") if alt]
            label = split + ":" + row["id"]
            if category in NATURAL:
                if pinyin_of(norm_text(expected_alts[0])) != input_text:
                    errors.append(label + ": natural pinyin mismatch")
            elif category == "homophone":
                for alt in expected_alts:
                    if pinyin_of(norm_text(alt)) != input_text:
                        errors.append(label + ": homophone pinyin mismatch for " + alt)
            elif category == "shorthand":
                for alt in expected_alts:
                    syllables = syllables_of(norm_text(alt))
                    if not syllables or "".join(s[0] for s in syllables) != input_text:
                        errors.append(label + ": shorthand initials mismatch for " + alt)
            elif category == "mixed_pinyin":
                for alt in expected_alts:
                    syllables = syllables_of(norm_text(alt))
                    if len(syllables) < 2:
                        errors.append(label + ": mixed pinyin needs >=2 syllables")
                        continue
                    derived = "".join(syllables[:-1]) + syllables[-1][0]
                    if derived != input_text:
                        errors.append(label + ": mixed pinyin derivation mismatch for " + alt)
            elif category == "typo":
                note = row["note"]
                marker = "正确拼音 "
                if marker not in note:
                    errors.append(label + ": typo note missing correct pinyin")
                    continue
                correct = note.split(marker, 1)[1].split("，")[0].strip()
                if damerau(input_text, correct) != 1:
                    errors.append(label + ": typo Damerau distance != 1 for " + input_text + " vs " + correct)
                if "漏字母" in note and len(correct) != len(input_text) + 1:
                    errors.append(label + ": typo missing subtype length mismatch")
                if "多字母" in note and len(correct) != len(input_text) - 1:
                    errors.append(label + ": typo extra subtype length mismatch")
                if "相邻交换" in note:
                    if len(correct) != len(input_text):
                        errors.append(label + ": typo swap length mismatch")
                    else:
                        diffs = [i for i, (x, y) in enumerate(zip(correct, input_text)) if x != y]
                        if len(diffs) != 2 or diffs[1] != diffs[0] + 1:
                            errors.append(label + ": typo swap positions invalid")
                if "邻键" in note:
                    diffs = [i for i, (x, y) in enumerate(zip(correct, input_text)) if x != y]
                    if len(correct) != len(input_text) or len(diffs) != 1:
                        errors.append(label + ": typo adjacent-key shape invalid")
            elif category == "number_punct":
                alt = expected_alts[0]
                if "阿拉伯数字" in row["note"]:
                    digit_index = next((i for i, ch in enumerate(input_text) if ch.isdigit()), -1)
                    if digit_index < 0:
                        errors.append(label + ": digit interaction missing digit")
                    else:
                        prefix = input_text[:digit_index]
                        if prefix != pinyin_of(norm_text(alt)):
                            errors.append(label + ": digit interaction prefix pinyin mismatch")
                else:
                    if letters_only(input_text) != pinyin_of(norm_text(alt)):
                        errors.append(label + ": number/punctuation pinyin mismatch")
            elif category == "proper_noun":
                if pinyin_of(norm_text(expected_alts[0])) != input_text:
                    errors.append(label + ": proper noun pinyin mismatch")
            elif category == "mixed_en":
                alt = expected_alts[0]
                tail = ""
                for ch in reversed(alt):
                    if ch.isascii() and (ch.isalnum() or ch in ".-_"):
                        tail = ch + tail
                    else:
                        break
                prefix = alt[: len(alt) - len(tail)]
                if not tail or not prefix:
                    errors.append(label + ": mixed_en shape invalid: " + alt)
                elif input_text != pinyin_of(prefix) + tail.lower():
                    errors.append(label + ": mixed_en pinyin mismatch")


def check_cross_split_inputs(rows: dict[str, list[dict]], errors: list[str]) -> None:
    dev_inputs = {row["input"]: row["id"] for row in rows["dev"]}
    for row in rows["validation"]:
        if row["input"] in dev_inputs:
            errors.append("cross-split duplicate input: " + row["id"] + " vs " + dev_inputs[row["input"]])


def check_source_traceability(rows: dict[str, list[dict]], manifest: dict, errors: list[str]) -> None:
    if not TATOEBA.is_file():
        print("source_traceability: skipped (.tmp/tatoeba/cmn_sentences.tsv not found)")
        return
    actual_sha = hashlib.sha256(TATOEBA.read_bytes()).hexdigest()
    manifest_sha = manifest.get("source", {}).get("sha256", "")
    if actual_sha != manifest_sha:
        errors.append("source file sha256 mismatch: " + actual_sha + " != " + manifest_sha)
    source_rows = load_tatoeba(TATOEBA)
    checked = 0
    mismatches = 0
    for split in ("dev", "validation"):
        for row in rows[split]:
            if row["category"] not in NATURAL:
                continue
            checked += 1
            source = row["source"]
            if not source.startswith("tatoeba:"):
                errors.append(row["id"] + ": natural row has non-Tatoeba source")
                continue
            try:
                sentence_id = int(source.split(":", 1)[1])
            except ValueError:
                errors.append(row["id"] + ": bad source id")
                continue
            if source_rows.get(sentence_id) != row["expected"]:
                mismatches += 1
                if mismatches <= 20:
                    errors.append(row["id"] + ": source sentence mismatch for tatoeba:" + str(sentence_id))
    print("source_traceability: checked=" + str(checked) + " mismatches=" + str(mismatches))


def check_expected_dictionary_coverage(rows: dict[str, list[dict]], dictionary_words: set[str]) -> None:
    for split in ("dev", "validation"):
        stats: dict[str, list[int]] = {}
        for row in rows[split]:
            if row["category"] in NATURAL:
                continue
            entry = stats.setdefault(row["category"], [0, 0])
            for alt in row["expected"].split("|"):
                entry[0] += 1
                if norm_text(alt) in dictionary_words:
                    entry[1] += 1
        parts = []
        for category in CATEGORY_ORDER:
            if category in stats:
                total, hit = stats[category]
                parts.append(category + "=" + str(hit) + "/" + str(total))
        print("special_expected_in_dictionary[" + split + "]: " + " ".join(parts))


def main() -> int:
    errors: list[str] = []
    manifest = load_manifest()
    rows = {
        "dev": load_rows(CORPUS_DIR / "pinyin_cases_v2_dev.tsv"),
        "validation": load_rows(CORPUS_DIR / "pinyin_cases_v2_validation.tsv"),
    }
    if len(rows["dev"]) < 1000:
        errors.append("dev rows below 1000: " + str(len(rows["dev"])))
    if len(rows["validation"]) < 500:
        errors.append("validation rows below 500: " + str(len(rows["validation"])))
    for split in ("dev", "validation"):
        check_format(rows[split], split, errors)
    counts = check_category_counts(rows, manifest, errors)
    check_manifest_hashes(manifest, errors)
    check_cross_split_inputs(rows, errors)
    dictionary_words = load_dictionary_words()
    check_whole_sentence_leak(rows, dictionary_words, errors)
    dev_texts = [norm_text(row["expected"]) for row in rows["dev"] if row["category"] in NATURAL]
    val_texts = [norm_text(row["expected"]) for row in rows["validation"] if row["category"] in NATURAL]
    near_hits = cross_split_near_duplicates(dev_texts, val_texts)
    print("near_duplicate_isolation: dev_natural=" + str(len(dev_texts)) + " validation_natural=" + str(len(val_texts)) +
          " cross_pairs_above_threshold=" + str(len(near_hits)))
    for i, j, value in near_hits[:20]:
        errors.append("cross-split near duplicate: dev[" + str(i) + "] vs validation[" + str(j) + "] jaccard=" + str(round(value, 3)))
    check_semantics(rows, errors)
    check_expected_dictionary_coverage(rows, dictionary_words)
    check_source_traceability(rows, manifest, errors)

    print("rows: dev=" + str(len(rows["dev"])) + " validation=" + str(len(rows["validation"])))
    print("categories_dev: " + json.dumps(counts["dev"], ensure_ascii=False))
    print("categories_validation: " + json.dumps(counts["validation"], ensure_ascii=False))
    if errors:
        print("FAILED errors=" + str(len(errors)))
        for message in errors[:80]:
            print("ERROR " + message)
        return 1
    print("OK all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
