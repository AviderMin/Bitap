"""One scorer for D04 native/JVM exports; never loads validation implicitly."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


def normalize(text):
    # Same Unicode letter/digit matching rule as PinyinV2EvalTest for this BMP corpus.
    return "".join(char for char in text if char.isalnum())


def interaction(case):
    return case[1] == "mixed_en" or (case[1] == "number_punct" and any(c.isdigit() for c in case[2]))


def matches(candidate, case):
    if not candidate["full"]:
        return False
    return normalize(candidate["word"]) in {normalize(item) for item in case[3].split("|")}


def wilson(hits, n):
    if not n:
        return None
    z = 1.96
    p = hits / n
    denominator = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denominator
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
    return [round(100 * (center - half), 2), round(100 * (center + half), 2)]


def summarize(results):
    n = len(results)
    top1 = sum(row["rank"] == 0 for row in results)
    top5 = sum(0 <= row["rank"] < 5 for row in results)
    latencies = sorted(row["latency8Nanos"] / 1e6 for row in results)
    return {
        "n": n, "top1": top1, "top5": top5,
        "top1Pct": round(top1 * 100 / n, 2) if n else 0,
        "top5Pct": round(top5 * 100 / n, 2) if n else 0,
        "top1Ci95": wilson(top1, n), "top5Ci95": wilson(top5, n),
        "oracle64": sum(row["oracle"] for row in results),
        "fullCoverage64": sum(row["full"] for row in results),
        "rankCost": round(sum(row["rank"] + 1 if 0 <= row["rank"] < 5 else 9
                                  for row in results) / n, 4) if n else 0,
        "latency8Ms": {f"p{p}": round(latencies[(n - 1) * p // 100], 4)
                       for p in (50, 95, 99)} if n else {},
    }


def load_results(path, cases):
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
    if len(records) != len(cases) or len({r["id"] for r in records}) != len(cases):
        raise ValueError("export count/unique IDs do not match corpus")
    by_id = {row["id"]: row for row in records}
    if set(by_id) != {row[0] for row in cases}:
        raise ValueError("export IDs do not match corpus")
    meta = json.loads(path.with_suffix(".meta.json").read_text(encoding="utf-8"))
    results, excluded = [], []
    for case in cases:
        record = by_id[case[0]]
        if interaction(case):
            excluded.append(case[0])
            continue
        pool = record["candidates"]
        top = record.get("top8", pool[:8])
        rank = next((i for i, item in enumerate(top) if matches(item, case)), -1)
        results.append({
            "id": case[0], "category": case[1], "rank": rank,
            "oracle": any(matches(item, case) for item in pool),
            "full": any(item["full"] for item in pool),
            "latency8Nanos": record["latency8Nanos"],
        })
    categories = sorted({row["category"] for row in results})
    return {
        "metadata": meta, "engineScored": summarize(results),
        "byCategory": {category: summarize([r for r in results if r["category"] == category])
                       for category in categories},
        "interactionExcluded": len(excluded),
    }, results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--rime", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--acceptance", action="store_true")
    args = parser.parse_args()
    if "validation" in args.corpus.name and not args.acceptance:
        parser.error("validation requires explicit acceptance")
    cases = [line.split("\t") for line in args.corpus.read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("#")]
    corpus_hash = hashlib.sha256(args.corpus.read_bytes()).hexdigest()
    baseline, left = load_results(args.baseline, cases)
    rime, right = load_results(args.rime, cases)
    for result in (baseline, rime):
        if result["metadata"]["corpusSha256"] != corpus_hash:
            raise ValueError("corpus hash mismatch")
    paired = {
        "bothTop1": sum(a["rank"] == 0 and b["rank"] == 0 for a, b in zip(left, right)),
        "baselineOnlyTop1": sum(a["rank"] == 0 and b["rank"] != 0 for a, b in zip(left, right)),
        "rimeOnlyTop1": sum(a["rank"] != 0 and b["rank"] == 0 for a, b in zip(left, right)),
        "neitherTop1": sum(a["rank"] != 0 and b["rank"] != 0 for a, b in zip(left, right)),
    }
    report = {"corpusSha256": corpus_hash, "baseline": baseline, "rime": rime, "paired": paired,
              "limits": ["host quality experiment, not Android latency/PSS/APK evidence",
                         "different dictionaries: system comparison, not engine-only ablation",
                         "no learning, no context, native diagnostic selection excluded from latency",
                         "rankCost uses top5 cap; historical v2 implementation allowed ranks6-8"]}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"baseline": baseline["engineScored"], "rime": rime["engineScored"],
                      "paired": paired}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
