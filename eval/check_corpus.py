# -*- coding: utf-8 -*-
"""Self-checks for the pinyin quality corpus.

Validates: counts/categories/ids, pinyin orthography, multiple-answer
consistency, typo edit distance/type, shorthand and mixed-pinyin derivability,
mixed_en prefix rule, and dictionary polyphone drift for word-level cases.

    python eval/check_corpus.py

Exit code 0 = all checks pass. The script prints only ASCII summaries.
"""
from __future__ import annotations

import io
import sys
from collections import Counter
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pypinyin import lazy_pinyin, Style

ROOT = Path(__file__).resolve().parent.parent
CATEGORIES = [
    "daily_chat", "time_place", "work_comm", "unseen_sentence", "homophone",
    "long_segment", "mixed_en", "number_punct", "shorthand", "mixed_pinyin", "typo",
]
ENGINE_PINYIN = {"daily_chat", "time_place", "work_comm", "unseen_sentence",
                 "homophone", "long_segment", "number_punct"}
CJK = lambda ch: "\u4e00" <= ch <= "\u9fff"


def load(name: str):
    rows = []
    with io.open(ROOT / "eval" / ("pinyin_cases_" + name + ".tsv"), encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            assert len(parts) == 5, line
            rows.append(parts)
    return rows


def py(text: str) -> str:
    return "".join(lazy_pinyin(text, style=Style.NORMAL))


def letters(text: str) -> str:
    return "".join(ch for ch in text.lower() if "a" <= ch <= "z")


def damerau(a: str, b: str) -> int:
    """Damerau-Levenshtein distance with adjacent transposition."""
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


def mixed_variants(pinyin_syllables):
    patterns = [""]
    for syllable in pinyin_syllables:
        next_patterns = []
        for prefix in patterns:
            next_patterns.append(prefix + syllable)
            next_patterns.append(prefix + syllable[0])
        patterns = next_patterns
    return set(patterns)


def typename(input_text: str, correct: str, note: str) -> str | None:
    if "漏字母" in note:
        return "missing" if len(correct) == len(input_text) + 1 else None
    if "多字母" in note:
        return "extra" if len(correct) == len(input_text) - 1 else None
    if "相邻交换" in note:
        if len(correct) != len(input_text):
            return None
        diffs = [i for i, (x, y) in enumerate(zip(correct, input_text)) if x != y]
        return "swap" if len(diffs) == 2 and diffs[1] == diffs[0] + 1 else None
    if "邻键" in note:
        return "adjacent-key" if len(correct) == len(input_text) else None
    return None


def main() -> int:
    errors: list[str] = []
    dev = load("dev")
    hold = load("holdout")
    if len(dev) < 120:
        errors.append("dev < 120")
    if len(hold) < 120:
        errors.append("holdout < 120")
    for name, rows in (("dev", dev), ("holdout", hold)):
        ids = [row[0] for row in rows]
        if len(set(ids)) != len(ids):
            errors.append(name + ": duplicate ids")
        categories = Counter(row[1] for row in rows)
        for category in CATEGORIES:
            if categories[category] != 12:
                errors.append(name + ": category " + category + " count " + str(categories[category]))
        for row in rows:
            expected_parts = row[3].split("|")
            if any(part.strip() == "" for part in expected_parts):
                errors.append(row[0] + ": empty expected alternative")
            for part in expected_parts:
                if any(ord(ch) < 128 and not ch.isalnum() for ch in part):
                    errors.append(row[0] + ": suspicious ASCII punctuation in expected")
    dev_inputs = {row[2] for row in dev}
    for row in hold:
        if row[2] in dev_inputs:
            errors.append("cross-set duplicate input: " + row[2])

    # pinyin orthography for full-pinyin categories
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            case_id, category, input_text, expected, note = row
            if category in ("typo", "shorthand", "mixed_pinyin", "mixed_en"):
                continue
            normalized = letters(input_text)
            for part in expected.split("|"):
                cjk = "".join(ch for ch in part if CJK(ch))
                if not cjk:
                    continue
                expected_py = py(cjk)
                ok = expected_py == normalized
                if not ok and category == "long_segment" and normalized.startswith(expected_py):
                    ok = True
                if not ok and category == "number_punct":
                    ok = normalized.startswith(expected_py)
                if not ok:
                    errors.append(name + " " + case_id + ": pinyin mismatch " + input_text + " != " + expected_py)

    # typo: exactly one edit and subtype consistency
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            if row[1] != "typo":
                continue
            note = row[4]
            marker = "正确拼音 "
            correct = note.split(marker, 1)[1].split("，")[0].strip() if marker in note else ""
            if not correct:
                errors.append(row[0] + ": typo note missing correct pinyin")
                continue
            if damerau(row[2], correct) != 1:
                errors.append(row[0] + ": typo distance != 1 (" + row[2] + " vs " + correct + ")")
            if typename(row[2], correct, note) is None:
                errors.append(row[0] + ": typo subtype mismatch (" + note + ")")

    # shorthand: input equals all initials of at least one expected answer
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            if row[1] != "shorthand":
                continue
            matched = False
            for part in row[3].split("|"):
                syllables = lazy_pinyin(part, style=Style.NORMAL)
                if "".join(s[0] for s in syllables) == row[2]:
                    matched = True
                    break
            if not matched:
                errors.append(row[0] + ": shorthand does not match initials of " + row[3])

    # mixed pinyin: input derivable from full syllable / initial combinations
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            if row[1] != "mixed_pinyin":
                continue
            matched = False
            for part in row[3].split("|"):
                syllables = lazy_pinyin(part, style=Style.NORMAL)
                if row[2] in mixed_variants(syllables):
                    matched = True
                    break
            if not matched:
                errors.append(row[0] + ": mixed pinyin not derivable from " + row[3])

    # mixed_en: Chinese prefix (>=2 chars) is a pinyin prefix; tail is ASCII only
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            if row[1] != "mixed_en":
                continue
            expected = row[3].split("|")[0]
            prefix = ""
            for char in expected:
                if not CJK(char):
                    break
                prefix += char
            if len(prefix) < 2:
                errors.append(row[0] + ": mixed_en Chinese prefix < 2 chars")
            if not letters(row[2]).startswith(py(prefix)):
                errors.append(row[0] + ": mixed_en input does not start with prefix pinyin")
            tail = expected[len(prefix):]
            if tail and not all(ord(ch) < 128 or ch in "·" for ch in tail):
                errors.append(row[0] + ": mixed_en tail is not raw ASCII")
            if not tail and "前缀" not in row[4]:
                errors.append(row[0] + ": mixed_en pure prefix case must be marked 前缀")

    # dictionary polyphone drift for word-level entries
    dict_pinyin: dict[str, set[str]] = {}
    with io.open(ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv", encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                dict_pinyin.setdefault(parts[1], set()).add("".join(ch for ch in parts[0].lower() if "a" <= ch <= "z"))
    warnings: list[str] = []
    for name, rows in (("dev", dev), ("holdout", hold)):
        for row in rows:
            if row[1] != "homophone":
                continue
            for part in row[3].split("|"):
                if part in dict_pinyin and row[2] not in dict_pinyin[part]:
                    warnings.append(name + " " + row[0] + ": dict pinyin " + ",".join(sorted(dict_pinyin[part])) + " != input " + row[2] + " for " + part)

    print("dev=" + str(len(dev)) + " holdout=" + str(len(hold)) + " total=" + str(len(dev) + len(hold)))
    print("categories ok=" + str(all(Counter(r[1] for r in rows)[c] == 12 for rows in (dev, hold) for c in CATEGORIES)))
    print("errors=" + str(len(errors)))
    for error in errors:
        print("ERROR " + error)
    print("polyphone-warnings=" + str(len(warnings)))
    for warning in warnings[:40]:
        print("WARN " + warning)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
