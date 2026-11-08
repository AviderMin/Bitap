"""Aggregate-only frozen-v3 audit against the complete production text source.

Does not run the engine, change the corpus, or print IDs/text/answers.
Exit 1 means overlap was found; exit 2 means provenance cannot be verified.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def normalize(text):
    return "".join(char for char in text if "\u4e00" <= char <= "\u9fff")


def bigrams(text):
    return {text[i:i + 2] for i in range(len(text) - 1)}


def audit(training, cases, threshold=0.8):
    training = {normalize(text) for text in training} - {""}
    items = [(text, bigrams(text)) for text in sorted(training)]
    index = defaultdict(set)
    for i, (_, grams) in enumerate(items):
        for gram in grams:
            index[gram].add(i)
    total = Counter()
    categories = defaultdict(Counter)
    for category, expected in cases:
        text = normalize(expected)
        grams = bigrams(text)
        exact = text in training
        possible = set()
        for gram in grams:
            possible.update(index[gram])
        near = exact
        for i in possible:
            other, othergrams = items[i]
            if not text or min(len(text), len(other)) / max(len(text), len(other)) < 0.5:
                continue
            union = len(grams | othergrams)
            if union and len(grams & othergrams) / union >= threshold:
                near = True
                break
        for bucket in (total, categories[category]):
            bucket["rows"] += 1
            bucket["exactTrainingOverlap"] += int(exact)
            bucket["nearTrainingOverlapIncludingExact"] += int(near)
    return {"counts": dict(total), "byCategory": {k: dict(v) for k, v in categories.items()},
            "normalization": "CJK U+4E00..U+9FFF only; no script conversion",
            "nearDuplicate": {"charBigramJaccard": threshold, "minimumLengthRatio": 0.5}}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "build/v3-training-overlap-audit.json")
    args = parser.parse_args()
    source = ROOT / ".tmp/tatoeba/cmn_sentences.tsv"
    corpus = ROOT / "eval/v3/corpus/pinyin_cases_v3_validation.tsv"
    training_manifest = json.loads((ROOT / "eval/v2/corpus/manifest.json").read_text(encoding="utf-8"))
    corpus_manifest = json.loads((corpus.parent / "manifest.json").read_text(encoding="utf-8"))
    if not source.is_file() or sha(source) != training_manifest["source"]["sha256"]:
        parser.error("complete training source missing or hash mismatch; audit not performed")
    if sha(corpus) != corpus_manifest["files"]["validation"]["sha256"]:
        parser.error("frozen corpus hash mismatch; audit not performed")
    train_rows = [line.split("\t") for line in source.read_text(encoding="utf-8").splitlines() if line]
    rows = [line.split("\t") for line in corpus.read_text(encoding="utf-8").splitlines() if line]
    if any(len(row) < 3 for row in train_rows) or any(len(row) != 6 for row in rows):
        parser.error("source/corpus format mismatch")
    result = audit((row[2] for row in train_rows), ((row[1], row[3]) for row in rows))
    result.update({"trainingSourceSha256": sha(source), "corpusSha256": sha(corpus),
                   "trainingSourceRows": len(train_rows), "engineExecuted": False})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return int(result["counts"]["nearTrainingOverlapIncludingExact"] > 0)


if __name__ == "__main__":
    raise SystemExit(main())
