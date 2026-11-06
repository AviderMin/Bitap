#!/usr/bin/env python3
"""Build Bitap's compact offline pinyin dictionary.

Data sources (downloaded by the maintainer, not by the app):
  - jieba/dict.txt for Chinese word frequencies (MIT, https://github.com/fxsjy/jieba)
  - pypinyin for tone-less Mandarin readings (MIT, https://github.com/mozillazg/python-pinyin)

Output columns: pinyin_key<TAB>word<TAB>frequency
The generated file contains no user data and ships inside the APK.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

from pypinyin import Style, lazy_pinyin

HAN = re.compile(r"^[\u4e00-\u9fff]{1,4}$")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jieba", required=True, help="path to jieba dict.txt")
    parser.add_argument("--out", required=True, help="output TSV path")
    parser.add_argument("--words", type=int, default=24000)
    args = parser.parse_args()

    entries: dict[tuple[str, str], int] = {}
    singles: list[tuple[int, str]] = []
    for raw in Path(args.jieba).read_text(encoding="utf-8", errors="ignore").splitlines():
        parts = raw.split()
        if len(parts) < 3:
            continue
        word, freq_s = parts[0], parts[1]
        if not HAN.fullmatch(word):
            continue
        try:
            freq = int(freq_s)
        except ValueError:
            continue
        if len(word) == 1:
            singles.append((freq, word))
            continue
        entries[(word, "")] = max(freq, entries.get((word, ""), 0))

    singles.sort(reverse=True)
    top_words = sorted(entries.items(), key=lambda kv: kv[1], reverse=True)[: args.words]
    selected = [w for (w, _), _f in top_words] + [w for _f, w in singles[:6000]]
    seen: set[tuple[str, str]] = set()
    rows: list[tuple[str, str, int]] = []
    for word in selected:
        try:
            reading = "".join(lazy_pinyin(word, style=Style.NORMAL, errors="ignore"))
        except Exception:
            continue
        reading = re.sub(r"[^a-z]", "", reading.lower())
        if not reading or len(reading) > 24:
            continue
        key = (reading, word)
        if key in seen:
            continue
        seen.add(key)
        freq = next((f for (w, _), f in top_words if w == word), 0)
        if len(word) == 1:
            freq = max(freq, next((f for f, w in singles if w == word), 0))
        rows.append((reading, word, max(freq, 1)))

    rows.sort(key=lambda r: (-r[2], len(r[1]), r[0]))
    header = "# Bitap offline pinyin dictionary | generated from jieba + pypinyin | pinyin\\tword\\tfreq"
    if args.out == "-":
        import sys
        sys.stdout.write(header + "\n")
        for reading, word, freq in rows:
            sys.stdout.write(f"{reading}\t{word}\t{freq}\n")
        return
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(header + "\n")
        for reading, word, freq in rows:
            fh.write(f"{reading}\t{word}\t{freq}\n")
    print(f"wrote {len(rows)} entries to {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
