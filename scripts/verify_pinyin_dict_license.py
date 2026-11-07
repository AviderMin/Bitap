#!/usr/bin/env python3
"""Verify that the generated pinyin dictionary is license-clean and sentence-free.

Checks performed:
  1. exactly 8 machine-readable #@provenance lines, with the required keys;
  2. no #@w (corpus-derived word frequency) rows;
  3. every #@c / #@b / #@t row has exactly three tab-separated fields and the
     payload is 1 / 2 / 3 Han characters respectively (aggregate counts only,
     never sentences);
  4. the corpus SHA-256 recorded in the asset matches the supplied corpus;
  5. a deterministic sample of long source sentences (>= 6 Han characters)
     does not occur verbatim in the dictionary file nor as a dictionary word.

Usage:
  python scripts/verify_pinyin_dict_license.py \
    --dict app/src/main/assets/pinyin_dict.tsv \
    --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import json
import random
import re
import sys
from pathlib import Path

HAN = re.compile(r"^[\u4e00-\u9fff]+$")

REQUIRED_PROVENANCE_KEYS = {
    "corpus.name",
    "corpus.url",
    "corpus.license",
    "corpus.export_date",
    "corpus.file",
    "corpus.sha256",
    "params.min_bigram",
    "params.min_trigram",
}


def read_corpus(path: Path) -> list[str]:
    raw = path.read_bytes()
    if raw.startswith(b"BZh"):
        raw = bz2.decompress(raw)
    return raw.decode("utf-8", errors="ignore").splitlines()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dict", dest="dict_path", required=True)
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--sample-size", type=int, default=500)
    parser.add_argument("--seed", type=int, default=20261107)
    args = parser.parse_args()

    dict_path = Path(args.dict_path)
    corpus_path = Path(args.corpus)
    text = dict_path.read_text(encoding="utf-8")
    lines = text.splitlines()
    errors: list[str] = []

    provenance = {}
    for line in lines:
        if line.startswith("#@provenance\t"):
            key, _, value = line[len("#@provenance\t"):].partition("=")
            provenance[key] = value
    missing = sorted(REQUIRED_PROVENANCE_KEYS - provenance.keys())
    if missing:
        errors.append("missing provenance keys: " + ", ".join(missing))
    if len([l for l in lines if l.startswith("#@provenance\t")]) != 8:
        errors.append("expected exactly 8 #@provenance lines")

    corpus_hash = hashlib.sha256(corpus_path.read_bytes()).hexdigest().upper()
    if provenance.get("corpus.sha256") != corpus_hash:
        errors.append("corpus.sha256 mismatch: asset=" + provenance.get("corpus.sha256", "<none>") + " actual=" + corpus_hash)

    if any(line.startswith("#@w\t") for line in lines):
        errors.append("#@w rows must not exist")

    data_words: set[str] = set()
    data_rows = 0
    ngram_counts = {"c": 0, "b": 0, "t": 0}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            errors.append("malformed data row: " + line[:80])
            continue
        data_rows += 1
        word = parts[1].strip()
        if word:
            data_words.add(word)

    for line in lines:
        if line.startswith("#@c\t"):
            ngram_counts["c"] += 1
            parts = line.split("\t")
            if len(parts) != 3 or not parts[1].isdigit() or not HAN.fullmatch(parts[2]) or len(parts[2]) != 1:
                errors.append("bad #@c row: " + line[:80])
        elif line.startswith("#@b\t"):
            ngram_counts["b"] += 1
            parts = line.split("\t")
            if len(parts) != 3 or not parts[1].isdigit() or not HAN.fullmatch(parts[2]) or len(parts[2]) != 2:
                errors.append("bad #@b row: " + line[:80])
        elif line.startswith("#@t\t"):
            ngram_counts["t"] += 1
            parts = line.split("\t")
            if len(parts) != 3 or not parts[1].isdigit() or not HAN.fullmatch(parts[2]) or len(parts[2]) != 3:
                errors.append("bad #@t row: " + line[:80])

    sentences: list[str] = []
    for raw in read_corpus(corpus_path):
        parts = raw.split("\t")
        sentence = parts[2] if len(parts) >= 3 and parts[1] == "cmn" else raw
        sentences.append(sentence)

    long_sentences = []
    for sentence in sentences:
        han = "".join(ch for ch in sentence if "\u4e00" <= ch <= "\u9fff")
        if len(han) >= 6:
            long_sentences.append((sentence.strip(), han))

    rng = random.Random(args.seed)
    sample = rng.sample(long_sentences, min(args.sample_size, len(long_sentences)))
    intersections = 0
    for raw_sentence, han_sentence in sample:
        if raw_sentence and raw_sentence in text:
            intersections += 1
        if han_sentence in data_words:
            intersections += 1
    if intersections:
        errors.append("sample sentence intersection is not zero: " + str(intersections))

    summary = {
        "dict": str(dict_path),
        "dict_bytes": dict_path.stat().st_size,
        "dict_lines": len(lines),
        "data_rows": data_rows,
        "provenance_lines": 8 - len(missing) if len([l for l in lines if l.startswith("#@provenance\t")]) == 8 else len([l for l in lines if l.startswith("#@provenance\t")]),
        "ngram_counts": ngram_counts,
        "corpus_sha256": corpus_hash,
        "long_sentences": len(long_sentences),
        "sample_checked": len(sample),
        "sample_sentence_intersections": intersections,
        "errors": errors,
        "ok": not errors,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
