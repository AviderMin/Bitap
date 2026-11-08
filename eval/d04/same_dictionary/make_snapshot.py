#!/usr/bin/env python3
"""Assemble the checked-in D04 same-dictionary aggregate snapshot.

Reads the ignored build artifacts produced by:
  build_same_dictionary.py
  rime_probe.py
  compare.py

and writes eval/d04/results/dev-host-same-dict-2026-11-08.json. Only aggregate
numbers and the pinned hashes are committed; raw candidate JSONL stays in
build/ and is never published.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_COMPARE = ROOT / "build" / "d04" / "comparison-samedict-dev.json"
DEFAULT_CONVERSION = ROOT / "eval" / "d04" / "same_dictionary" / "pinyin_conversion_report.json"
DEFAULT_REFERENCE = ROOT / "eval" / "d04" / "results" / "dev-host-2026-11-08.json"
DEFAULT_OUT = ROOT / "eval" / "d04" / "results" / "dev-host-same-dict-2026-11-08.json"
DEFAULT_CORPUS = ROOT / "eval" / "v2" / "corpus" / "pinyin_cases_v2_dev.tsv"
DEFAULT_RIME_EXPORT = ROOT / "build" / "d04" / "rime-samedict-dev.jsonl"
DEFAULT_BASELINE_EXPORT = ROOT / "build" / "d04" / "bitap-dev.jsonl"


def relative(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load_jsonl(path: Path) -> dict[str, dict]:
    records = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line:
            record = json.loads(line)
            records[record["id"]] = record
    return records


def candidate_structure(corpus: Path, rime_export: Path, baseline_export: Path) -> dict:
    """Derived structure facts that explain why natural Top1 == Top5 for Rime."""
    if not (rime_export.is_file() and baseline_export.is_file() and corpus.is_file()):
        return {"available": False}
    categories = {}
    for line in corpus.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            row = line.split("\t")
            if len(row) == 6:
                categories[row[0]] = row[1]
    rime = load_jsonl(rime_export)
    baseline = load_jsonl(baseline_export)
    full_counts: dict[str, dict[str, int]] = {}
    gaps: list[dict] = []
    for case_id, category in categories.items():
        rime_record = rime.get(case_id)
        baseline_record = baseline.get(case_id)
        if rime_record is None or baseline_record is None:
            continue
        rime_full = sum(1 for item in rime_record["candidates"] if item["full"])
        baseline_full = any(item["full"] for item in baseline_record["candidates"])
        bucket = full_counts.setdefault(category, {"zeroFull": 0, "exactlyOneFull": 0, "moreThanOneFull": 0})
        if rime_full == 0:
            bucket["zeroFull"] += 1
        elif rime_full == 1:
            bucket["exactlyOneFull"] += 1
        else:
            bucket["moreThanOneFull"] += 1
        if baseline_full and rime_full == 0:
            gaps.append({"id": case_id, "category": category})
    gap_counts: dict[str, int] = {}
    for gap in gaps:
        gap_counts[gap["category"]] = gap_counts.get(gap["category"], 0) + 1
    natural = {
        category: full_counts.get(category, {})
        for category in ("natural_short", "natural_medium", "natural_long")
    }
    return {
        "available": True,
        "naturalFullCandidateCounts": natural,
        "fullCoverageGap": {
            "total": len(gaps),
            "byCategory": gap_counts,
            "cases": gaps,
            "definition": "baseline has at least one full candidate but same-dictionary Rime has none in its 64-item pool",
        },
        "naturalTop1EqualsTop5": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compare", type=Path, default=DEFAULT_COMPARE)
    parser.add_argument("--conversion", type=Path, default=DEFAULT_CONVERSION)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--rime-export", type=Path, default=DEFAULT_RIME_EXPORT)
    parser.add_argument("--baseline-export", type=Path, default=DEFAULT_BASELINE_EXPORT)
    args = parser.parse_args()

    compare_path = args.compare if args.compare.is_absolute() else ROOT / args.compare
    conversion_path = args.conversion if args.conversion.is_absolute() else ROOT / args.conversion
    reference_path = args.reference if args.reference.is_absolute() else ROOT / args.reference
    out_path = args.out if args.out.is_absolute() else ROOT / args.out
    corpus_path = args.corpus if args.corpus.is_absolute() else ROOT / args.corpus
    rime_export_path = args.rime_export if args.rime_export.is_absolute() else ROOT / args.rime_export
    baseline_export_path = args.baseline_export if args.baseline_export.is_absolute() else ROOT / args.baseline_export

    comparison = json.loads(compare_path.read_text(encoding="utf-8"))
    conversion = json.loads(conversion_path.read_text(encoding="utf-8"))
    reference = json.loads(reference_path.read_text(encoding="utf-8")) if reference_path.is_file() else None

    if comparison["rime"]["metadata"]["dictionarySha256"] != conversion["output"]["dictionarySha256"]:
        raise ValueError("rime export dictionary hash does not match conversion output")
    if comparison["baseline"]["metadata"]["dictionarySha256"] != conversion["source"]["sha256"]:
        raise ValueError("baseline dictionary hash does not match source asset")
    if comparison["baseline"]["metadata"]["corpusSha256"] != comparison["corpusSha256"]:
        raise ValueError("baseline corpus hash mismatch")
    if comparison["rime"]["metadata"]["corpusSha256"] != comparison["corpusSha256"]:
        raise ValueError("rime corpus hash mismatch")

    snapshot = {
        "experiment": "D04 same-dictionary host comparison",
        "date": "2026-11-08",
        "corpusSha256": comparison["corpusSha256"],
        "dictionary": {
            "source": conversion["source"],
            "rimeDictionary": conversion["output"],
            "schema": conversion["schema"],
            "pypinyin": conversion["pypinyin"],
            "conversion": conversion["conversion"],
            "rimeReference": conversion["rimeReference"],
        },
        "host": {
            "librimeRelease": "1.17.0 / 33e7814 (official Windows MSVC x64)",
            "dllSha256": comparison["rime"]["metadata"].get("dllSha256"),
            "startupIncludingDeploymentNanos": comparison["rime"]["metadata"].get("startupIncludingDeploymentNanos"),
        },
        "baseline": comparison["baseline"],
        "rime": comparison["rime"],
        "paired": comparison["paired"],
        "candidateStructure": candidate_structure(corpus_path, rime_export_path, baseline_export_path),
        "referenceDifferentDictionary": {
            "snapshot": relative(reference_path),
            "corpusSha256": reference.get("corpusSha256") if reference else None,
            "rimeEngineScored": reference["rime"]["engineScored"] if reference else None,
        },
        "limits": [
            "host quality experiment on Windows x64; not Android latency/PSS/APK/IME evidence",
            "same source dictionary: Rime uses pinyin_simp.dict.yaml generated from app/src/main/assets/pinyin_dict.tsv; remaining differences are host engine/scheme, candidate enumeration and tokenisation",
            "Rime uses the controlled minimal bitap_d04 schema (no learning/completion/punctuation), not all Rime defaults",
            "Rime script_translator emits at most one full-sentence candidate per natural input in this setup; natural Top1==Top5 and oracle64 is structurally not comparable to a multi-sentence generator",
            "no learning, no context; native diagnostic selection excluded from latency",
            "rankCost uses top5 cap; historical v2 implementation allowed ranks6-8",
            "v2 dev natural sentences overlap the production n-gram training source; exploratory dev-only result, not independent generalization",
        ],
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({
        "snapshot": relative(out_path),
        "baseline": snapshot["baseline"]["engineScored"],
        "rime": snapshot["rime"]["engineScored"],
        "paired": snapshot["paired"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
