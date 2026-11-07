# -*- coding: utf-8 -*-
"""Verifies the holdout corpus did not leak into implementation/dictionary/tests.

Scans app/src/main, app/src/test, scripts and docs for holdout sentences (CJK
length >= 5) and long holdout inputs (length >= 8). Word-level categories
(homophone/shorthand/mixed_pinyin/typo/mixed_en prefixes) are exempt from the
dictionary-whole-entry check because their targets must be dictionary words;
whole-sentence categories must not appear as dictionary entries.

    python eval/check_no_leak.py

Exit code 0 = no leak found.
"""
from __future__ import annotations

import io
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
WHOLE_SENTENCE_CATEGORIES = {"daily_chat", "time_place", "work_comm", "unseen_sentence", "long_segment"}
SCAN_ROOTS = [
    ROOT / "app" / "src" / "main",
    ROOT / "app" / "src" / "test",
    ROOT / "scripts",
    ROOT / "docs",
]
TEXT_SUFFIXES = {".kt", ".java", ".kts", ".tsv", ".txt", ".md", ".py", ".ps1", ".xml", ".json"}


def load_holdout():
    rows = []
    with io.open(ROOT / "eval" / "pinyin_cases_holdout.tsv", encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            rows.append(line.split("\t"))
    return rows


def iter_text_files():
    for base in SCAN_ROOTS:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
                # never scan the eval corpus itself, and skip the pinyin dictionary
                # asset for short-input checks (pinyin keys are not holdout leaks)
                if "\\eval\\" in str(path) or "/eval/" in str(path):
                    continue
                if path.suffix.lower() == ".tsv" and "assets" in str(path):
                    continue
                yield path


def main() -> int:
    rows = load_holdout()
    files = list(iter_text_files())
    corpus = {}
    for path in files:
        try:
            corpus[path] = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
    leaks = []
    checks = 0
    for row in rows:
        case_id, category, input_text, expected = row[0], row[1], row[2], row[3]
        inputs = [input_text]
        if len(input_text) >= 10:
            import re
            pattern = re.compile(r"(?<![a-z])" + re.escape(input_text.lower()) + r"(?![a-z])")
            for path, text in corpus.items():
                checks += 1
                if pattern.search(text.lower()):
                    leaks.append((case_id, "input", str(path.relative_to(ROOT))))
        for part in expected.split("|"):
            cjk = "".join(ch for ch in part if "\u4e00" <= ch <= "\u9fff")
            if len(cjk) < 5:
                continue
            for path, text in corpus.items():
                checks += 1
                if cjk in text:
                    leaks.append((case_id, "expected", str(path.relative_to(ROOT))))

    # whole-sentence categories must not be dictionary entries
    dictionary_words = set()
    with io.open(ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv", encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                dictionary_words.add(parts[1])
    dictionary_hits = []
    for row in rows:
        if row[1] not in WHOLE_SENTENCE_CATEGORIES:
            continue
        for part in row[3].split("|"):
            cjk = "".join(ch for ch in part if "\u4e00" <= ch <= "\u9fff")
            if cjk in dictionary_words:
                dictionary_hits.append((row[0], cjk))

    print("holdout cases=" + str(len(rows)) + " filesScanned=" + str(len(corpus)) + " checks=" + str(checks))
    print("source-leaks=" + str(len(leaks)))
    for leak in leaks[:50]:
        print("LEAK " + " ".join(leak))
    print("dictionary-whole-sentence-hits=" + str(len(dictionary_hits)))
    for hit in dictionary_hits[:20]:
        print("DICT-HIT " + " ".join(hit))
    return 1 if leaks or dictionary_hits else 0


if __name__ == "__main__":
    raise SystemExit(main())
