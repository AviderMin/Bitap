#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the v3 training-source-independent pinyin acceptance corpus (CC0).

Why v3 exists: the v2 natural sentences come from the same Tatoeba cmn export
(89,289 sentences) whose aggregate character n-grams were used to build the
production pinyin dictionary. That makes v2 unsuitable as an independent
generalisation check. v3 uses only the zh-TW daily-chat files of the
irvin/cc0-sentences repository (CC0-1.0), converted to simplified Chinese with
zhconv. No Tatoeba text is used. The production PinyinEngine is never called.

Run from the repository root:

    python eval/v3/corpus/build_v3_corpus.py

Outputs (deterministic, LF, UTF-8):
    eval/v3/corpus/pinyin_cases_v3_validation.tsv
    eval/v3/corpus/manifest.json
    eval/v3/corpus/ATTRIBUTION.md
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
_pp = ROOT / ".tmp" / "pypinyin_pkg"
if _pp.is_dir():
    sys.path.insert(0, str(_pp))

import zhconv  # noqa: E402
from pypinyin import lazy_pinyin, Style  # noqa: E402

PUNCT = set("，。！？、；：（）《》〈〉“”‘’…—～·")
FORBIDDEN = set("“”‘’《》〈〉（）【】[]{}")
SOURCE_FILES = ["setences.txt", "chatlogs.txt", "g0v_slack_rand0m.txt", "tg_common_voice.txt"]
CATEGORY_ORDER = ["natural_short", "natural_medium", "natural_long"]
BUCKET_TARGETS = {"short": 320, "medium": 240, "long": 44}
BUCKET_RANGES = {"short": (2, 8), "medium": (9, 16), "long": (17, 24)}
MAX_PINYIN_LENGTH = 60
MAX_CLUSTER_TAKE = 2
NEAR_DUP_JACCARD = 0.75
REFERENCE_NEAR_DUP_JACCARD = 0.80


def cjk(ch: str) -> bool:
    return 0x4E00 <= ord(ch) <= 0x9FFF


def gb2312_ok(ch: str) -> bool:
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def allowed_char(ch: str) -> bool:
    return (cjk(ch) and gb2312_ok(ch)) or ch in PUNCT


def norm_text(text: str) -> str:
    return "".join(ch for ch in text if cjk(ch))


def pinyin_of(text: str) -> str:
    out = []
    for syllable in lazy_pinyin(text, style=Style.NORMAL):
        out.append("".join(ch for ch in syllable.lower() if "a" <= ch <= "z"))
    return "".join(out)


def stable_key(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with io.open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_text_lines(path: Path):
    with io.open(path, encoding="utf-8-sig") as handle:
        for raw in handle:
            yield raw.rstrip("\n").rstrip("\r")


def classify_bucket(length: int) -> str | None:
    for bucket, (lo, hi) in BUCKET_RANGES.items():
        if lo <= length <= hi:
            return bucket
    return None


def convert_to_simplified(text: str) -> str:
    return zhconv.convert(text, "zh-cn")


def clean_source_line(text: str) -> str | None:
    if not text:
        return None
    if any(not allowed_char(ch) for ch in text):
        return None
    if any(ch in FORBIDDEN for ch in text):
        return None
    if any(ch in "。！？" for ch in text[:-1]):
        return None
    normalized = norm_text(text)
    if classify_bucket(len(normalized)) is None:
        return None
    return normalized


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


def build_clusters(items: list[dict]) -> list[list[int]]:
    features = [char_bigrams(item["norm"]) for item in items]
    signatures = [minhash_signature(fs) for fs in features]
    band_rows = 4
    bands: dict[tuple, list[int]] = defaultdict(list)
    for index, signature in enumerate(signatures):
        for band in range(len(signature) // band_rows):
            bands[(band, tuple(signature[band * band_rows:(band + 1) * band_rows]))].append(index)
    parent = list(range(len(items)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    checked: set[tuple[int, int]] = set()
    for indexes in bands.values():
        if len(indexes) < 2:
            continue
        for ai in range(len(indexes)):
            i = indexes[ai]
            for bi in range(ai + 1, len(indexes)):
                j = indexes[bi]
                pair = (i, j) if i < j else (j, i)
                if pair in checked:
                    continue
                checked.add(pair)
                li, lj = len(items[i]["norm"]), len(items[j]["norm"])
                if min(li, lj) / max(li, lj) < 0.6:
                    continue
                if jaccard(features[i], features[j]) >= NEAR_DUP_JACCARD:
                    union(i, j)
    clusters: dict[int, list[int]] = defaultdict(list)
    for index in range(len(items)):
        clusters[find(index)].append(index)
    result = list(clusters.values())
    result.sort(key=lambda cluster: min(items[i]["line"] for i in cluster))
    return result


def load_dictionary_words(path: Path) -> set[str]:
    words: set[str] = set()
    for line in read_text_lines(path):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].strip():
            words.add(parts[1].strip())
    return words


def load_reference_norms() -> set[str]:
    norms: set[str] = set()
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
        for line in read_text_lines(path):
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 4:
                for alternative in parts[3].split("|"):
                    norms.add(norm_text(alternative))
    return norms


def load_source_items(source_dir: Path):
    items: list[dict] = []
    seen: set[str] = set()
    stats = Counter()
    file_info: list[dict] = []
    for filename in SOURCE_FILES:
        path = source_dir / filename
        if not path.is_file():
            raise SystemExit("missing CC0 source file: " + str(path))
        file_info.append({
            "name": filename,
            "sha256": sha256_file(path),
            "bytes": path.stat().st_size,
        })
        for line_number, raw in enumerate(read_text_lines(path), start=1):
            stats["source_lines"] += 1
            stripped = raw.strip()
            converted = convert_to_simplified(stripped) if stripped else ""
            if not converted:
                stats["rejected_empty"] += 1
                continue
            normalized = clean_source_line(converted)
            if normalized is None:
                stats["rejected_filter"] += 1
                continue
            if normalized in seen:
                stats["rejected_duplicate"] += 1
                continue
            seen.add(normalized)
            items.append({
                "norm": normalized,
                "text": converted,
                "file": filename,
                "line": line_number,
                "bucket": classify_bucket(len(normalized)),
            })
    stats["unique_texts"] = len(items)
    return items, file_info, stats


def near_reference(item: dict, reference_items: list[tuple[int, set[str]]], threshold: float = REFERENCE_NEAR_DUP_JACCARD) -> bool:
    features = char_bigrams(item["norm"])
    length = len(item["norm"])
    for other_length, other_features in reference_items:
        if other_length == 0:
            continue
        if min(length, other_length) / max(length, other_length) < 0.5:
            continue
        if jaccard(features, other_features) >= threshold:
            return True
    return False


def select_items(items: list[dict], reference_items: list[tuple[int, set[str]]]):
    clusters = build_clusters(items)
    cluster_buckets: dict[str, list[list[int]]] = defaultdict(list)
    for cluster in clusters:
        counts = Counter(items[i]["bucket"] for i in cluster)
        cluster_buckets[counts.most_common(1)[0][0]].append(cluster)
    chosen: dict[str, list[dict]] = {bucket: [] for bucket in BUCKET_TARGETS}
    stats = Counter()
    for bucket in ("short", "medium", "long"):
        target = BUCKET_TARGETS[bucket]
        ordered = sorted(
            cluster_buckets[bucket],
            key=lambda cluster: stable_key(min(items[i]["norm"] for i in cluster)),
        )
        for cluster in ordered:
            members = sorted(
                [i for i in cluster if items[i]["bucket"] == bucket],
                key=lambda i: stable_key(items[i]["norm"]),
            )
            for index in members[:MAX_CLUSTER_TAKE]:
                if len(chosen[bucket]) >= target:
                    break
                item = items[index]
                if near_reference(item, reference_items):
                    stats["rejected_reference_near_duplicate"] += 1
                    continue
                chosen[bucket].append(item)
            if len(chosen[bucket]) >= target:
                break
        if len(chosen[bucket]) < target:
            stats["bucket_below_target_" + bucket] = target - len(chosen[bucket])
    stats["clusters"] = len(clusters)
    stats["multi_member_clusters"] = sum(1 for cluster in clusters if len(cluster) > 1)
    return chosen, stats


def render_rows(rows: list[dict]) -> tuple[str, int]:
    rows = sorted(rows, key=lambda row: (CATEGORY_ORDER.index(row["category"]), stable_key(row["input"])))
    counters: Counter = Counter()
    lines: list[str] = []
    for row in rows:
        category = row["category"]
        counters[category] += 1
        case_id = "v3v-" + category + "-" + str(counters[category]).zfill(3)
        fields = [case_id, category, row["input"], row["expected"], row["note"], row["source"]]
        for field in fields:
            if "\t" in field or "\n" in field:
                raise SystemExit("field contains tab/newline")
        lines.append("\t".join(fields))
    return "\n".join(lines) + "\n", len(rows)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def attribution_text(source_archive_sha: str) -> str:
    return (
        "# Evaluation corpus v3 - attribution and license\n"
        "\n"
        "This directory contains a validation-only pinyin evaluation corpus.\n"
        "It must never be copied into the production dictionary or used for tuning.\n"
        "\n"
        "## Source\n"
        "\n"
        "Sentences: irvin/cc0-sentences, zh-TW daily-chat files\n"
        "(setences.txt, chatlogs.txt, g0v_slack_rand0m.txt, tg_common_voice.txt).\n"
        "Repository: https://github.com/irvin/cc0-sentences\n"
        "License: CC0 1.0 Universal (public domain dedication),\n"
        "https://creativecommons.org/publicdomain/zero/1.0/\n"
        "Source archive: .tmp/cc0/cc0-sentences.zip\n"
        "SHA-256: " + source_archive_sha + "\n"
        "\n"
        "The source files are traditional Chinese (zh-TW). Sentence text was\n"
        "converted to simplified Chinese with zhconv 1.4.3 before filtering and is\n"
        "redistributed in simplified form for evaluation only. No Tatoeba text is\n"
        "used by this corpus.\n"
        "\n"
        "## Generated files\n"
        "\n"
        "- pinyin_cases_v3_validation.tsv - acceptance-only validation split.\n"
        "- manifest.json - source file SHA-256 values, category counts, filter and\n"
        "  generation details.\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the v3 CC0 acceptance corpus.")
    parser.add_argument("--source-dir", default=".tmp/cc0/extract/cc0-sentences-master/zh-TW")
    parser.add_argument("--dictionary", default="app/src/main/assets/pinyin_dict.tsv")
    parser.add_argument("--out-dir", default="eval/v3/corpus")
    parser.add_argument("--zip", default=".tmp/cc0/cc0-sentences.zip")
    args = parser.parse_args()

    source_dir = (ROOT / args.source_dir).resolve()
    dictionary_path = (ROOT / args.dictionary).resolve()
    out_dir = (ROOT / args.out_dir).resolve()
    zip_path = (ROOT / args.zip).resolve()
    if not dictionary_path.is_file():
        raise SystemExit("missing dictionary: " + str(dictionary_path))

    dict_words = load_dictionary_words(dictionary_path)
    reference_norms = load_reference_norms()
    items, file_info, load_stats = load_source_items(source_dir)
    filtered: list[dict] = []
    stats = Counter(load_stats)
    for item in items:
        if item["norm"] in dict_words or item["text"] in dict_words:
            stats["rejected_dictionary_leak"] += 1
            continue
        if item["norm"] in reference_norms:
            stats["rejected_v1_v2_overlap"] += 1
            continue
        pinyin = pinyin_of(item["norm"])
        if not pinyin or len(pinyin) > MAX_PINYIN_LENGTH:
            stats["rejected_pinyin_length"] += 1
            continue
        item["pinyin"] = pinyin
        filtered.append(item)
    stats["filtered_pool"] = len(filtered)
    reference_items = [(len(text), char_bigrams(text)) for text in reference_norms if text]
    chosen, selection_stats = select_items(filtered, reference_items)
    stats.update(selection_stats)
    rows: list[dict] = []
    for bucket, bucket_items in chosen.items():
        for item in bucket_items:
            rows.append({
                "category": "natural_" + bucket,
                "input": item["pinyin"],
                "expected": item["text"],
                "note": "CC0 日常聊天（zh-TW→简体）",
                "source": "cc0:" + item["file"] + ":" + str(item["line"]),
            })
    content, row_count = render_rows(rows)
    if row_count < 500:
        raise SystemExit("v3 validation below 500 rows: " + str(row_count))
    out_path = out_dir / "pinyin_cases_v3_validation.tsv"
    write_text(out_path, content)
    file_sha = sha256_bytes(content.encode("utf-8"))
    category_counts = Counter(row["category"] for row in rows)
    ordered_counts = {category: category_counts.get(category, 0) for category in CATEGORY_ORDER}
    source_archive_sha = sha256_file(zip_path) if zip_path.is_file() else ""
    manifest = {
        "schema_version": 3,
        "generator": "eval/v3/corpus/build_v3_corpus.py",
        "generator_version": "1.0.0",
        "command": "python eval/v3/corpus/build_v3_corpus.py",
        "purpose": "training-source-independent acceptance-only corpus; v2 remains regression/same-source exploration",
        "source": {
            "name": "irvin/cc0-sentences zh-TW daily chat files",
            "repository": "https://github.com/irvin/cc0-sentences",
            "license": "CC0-1.0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "archive": ".tmp/cc0/cc0-sentences.zip",
            "archive_sha256": source_archive_sha,
            "files": file_info,
            "conversion": "zhconv 1.4.3 zh-TW -> zh-CN (simplified), UTF-8",
            "excluded_files": ["book.txt", "gpt-4.txt", "wikidata.txt", "wikipedia.txt", "places_and_address.txt", "gov_press_release.txt", "cofacts.txt", "cvsprint_201902.txt", "exam_text.txt", "lms.txt", "sayit.txt", "web_slang.txt"],
        },
        "files": {
            "validation": {
                "path": "eval/v3/corpus/pinyin_cases_v3_validation.tsv",
                "sha256": file_sha,
                "rows": row_count,
            }
        },
        "category_counts": ordered_counts,
        "filters": {
            "script": "simplified Chinese (CJK Unified Ideographs encodable with GB2312) plus Chinese punctuation",
            "length_chars": {"natural_short": [2, 8], "natural_medium": [9, 16], "natural_long": [17, 24]},
            "max_pinyin_length": MAX_PINYIN_LENGTH,
            "dedup": "exact normalized character sequence",
            "excluded": {
                "dictionary_whole_sentence": "normalized sentence equals a whole pinyin_dict.tsv word",
                "v1_v2_overlap": "normalized sentence equals any expected text in eval/pinyin_cases_{dev,holdout,sealed}.tsv or eval/v2/corpus/*.tsv",
                "reference_near_duplicate": "character 2-gram Jaccard >= 0.80 against v1/v2 expected text",
            },
            "rejected_counts": dict(stats),
        },
        "split": {
            "mode": "validation-only (no dev split)",
            "near_duplicate": {
                "features": "character 2-gram Jaccard",
                "jaccard_threshold": NEAR_DUP_JACCARD,
                "lsh": "MinHash 64 permutations, 16 bands x 4 rows",
                "max_members_per_cluster": MAX_CLUSTER_TAKE,
            },
        },
        "engine_independence": "no PinyinEngine call; pinyin generated with pypinyin 0.55.0",
    }
    write_text(out_dir / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    write_text(out_dir / "ATTRIBUTION.md", attribution_text(source_archive_sha))
    print("rows: " + str(row_count) + " sha256=" + file_sha)
    print("category counts: " + json.dumps(ordered_counts, ensure_ascii=False))
    print("filter stats: " + json.dumps(dict(stats), ensure_ascii=False))
    print("wrote: " + str(out_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
