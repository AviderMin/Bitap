#!/usr/bin/env python3
"""Build Bitap's compact offline pinyin dictionary.

Data sources (downloaded by the maintainer, not by the app):
  - jieba/dict.txt for Chinese word frequencies (MIT, https://github.com/fxsjy/jieba)
  - pypinyin for tone-less Mandarin readings (MIT, https://github.com/mozillazg/python-pinyin)
  - optional --corpus plain-text Chinese dialogue corpus used only to derive
    anonymous character n-gram counts (no source sentences are shipped).

Output columns: pinyin_key<TAB>word<TAB>frequency
The generated file contains no user data and ships inside the APK.

Optional character n-gram section (appended as comment-looking metadata so the
same asset stays self-contained):

  #@c<TAB>count<TAB>char          character frequency
  #@b<TAB>count<TAB>two-chars     character bigram frequency
  #@t<TAB>count<TAB>three-chars   character trigram frequency

The engine parses these as a generic collocation language model; they are not
dictionary entries.
"""
from __future__ import annotations

import argparse
import re
from collections import Counter
from pathlib import Path

from pypinyin import Style, lazy_pinyin

HAN = re.compile(r"^[\u4e00-\u9fff]{1,4}$")
HAN_CHAR = re.compile(r"[\u4e00-\u9fff]")


def build_ngrams(corpus_path: str, min_bigram: int, min_trigram: int) -> list[str]:
    """Derive anonymous character n-grams and word frequencies from a corpus."""
    text = Path(corpus_path).read_text(encoding="utf-8", errors="ignore")
    chars: Counter[str] = Counter()
    bigrams: Counter[str] = Counter()
    trigrams: Counter[str] = Counter()
    word_counts: Counter[str] = Counter()
    try:
        import jieba
    except ImportError:
        jieba = None
    for line in text.splitlines():
        sequence = "".join(HAN_CHAR.findall(line))
        if not sequence:
            continue
        for index, char in enumerate(sequence):
            chars[char] += 1
            if index + 1 < len(sequence):
                bigrams[sequence[index:index + 2]] += 1
            if index + 2 < len(sequence):
                trigrams[sequence[index:index + 3]] += 1
        if jieba is not None:
            for token in jieba.cut(line):
                if HAN.fullmatch(token):
                    word_counts[token] += 1
    lines = ["#@meta\tcharacter n-grams and word frequencies derived from a public Chinese dialogue corpus; no sentences stored"]
    for char, count in sorted(chars.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append("#@c\t" + str(count) + "\t" + char)
    for gram, count in sorted(bigrams.items(), key=lambda kv: (-kv[1], kv[0])):
        if count >= min_bigram:
            lines.append("#@b\t" + str(count) + "\t" + gram)
    for gram, count in sorted(trigrams.items(), key=lambda kv: (-kv[1], kv[0])):
        if count >= min_trigram:
            lines.append("#@t\t" + str(count) + "\t" + gram)
    for word, count in sorted(word_counts.items(), key=lambda kv: (-kv[1], kv[0]))[:30000]:
        if count >= 3:
            lines.append("#@w\t" + str(count) + "\t" + word)
    return lines


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jieba", required=True, help="path to jieba dict.txt")
    parser.add_argument("--out", required=True, help="output TSV path")
    parser.add_argument("--words", type=int, default=60000)
    parser.add_argument("--corpus", default=None, help="optional plain-text corpus for character n-grams")
    parser.add_argument("--min-bigram", type=int, default=8)
    parser.add_argument("--min-trigram", type=int, default=20)
    args = parser.parse_args()

    # Generic word frequencies from jieba's public dictionary, plus every
    # single character (needed to derive the generic syllable inventory and
    # character-bigram statistics). No sentence-specific entries are added.
    word_freq: dict[str, int] = {}
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
        word_freq[word] = max(freq, word_freq.get(word, 0))

    singles.sort(reverse=True)
    top_words = sorted(
        ((w, f) for w, f in word_freq.items() if len(w) > 1),
        key=lambda kv: (-kv[1], kv[0]),
    )[: args.words]
    selected = [w for w, _f in top_words] + [w for _f, w in singles[:6000]]

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
        rows.append((reading, word, max(word_freq.get(word, 0), 1)))

    rows.sort(key=lambda r: (-r[2], len(r[1]), r[0]))
    header = "# Bitap offline pinyin dictionary | generated from jieba + pypinyin | pinyin\\tword\\tfreq"
    ngram_lines = build_ngrams(args.corpus, args.min_bigram, args.min_trigram) if args.corpus else []
    if args.out == "-":
        import sys
        sys.stdout.write(header + "\n")
        for reading, word, freq in rows:
            sys.stdout.write(f"{reading}\t{word}\t{freq}\n")
        for line in ngram_lines:
            sys.stdout.write(line + "\n")
        return
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(header + "\n")
        for reading, word, freq in rows:
            fh.write(f"{reading}\t{word}\t{freq}\n")
        for line in ngram_lines:
            fh.write(line + "\n")
    print(f"wrote {len(rows)} entries + {len(ngram_lines)} ngram rows to {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
