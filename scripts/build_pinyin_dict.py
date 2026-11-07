#!/usr/bin/env python3
"""Build Bitap's compact offline pinyin dictionary.

Data sources (downloaded by the maintainer, not by the app):
  - jieba/dict.txt for Chinese word frequencies (MIT, https://github.com/fxsjy/jieba)
  - pypinyin for tone-less Mandarin readings (MIT, https://github.com/mozillazg/python-pinyin)
  - a --corpus Chinese sentence file supplied on the command line, used only
    to derive aggregate character n-gram counts (no source sentences are
    shipped and no corpus path or source name is hard-coded here). For
    Tatoeba, the detailed sentences export is used so the contributor
    attribution list can be generated from the same source snapshot.

Output columns: pinyin_key<TAB>word<TAB>frequency
The generated file contains no user data and ships inside the APK.

Corpus provenance is written into the asset as machine-readable comment lines
so every generated file states exactly which corpus produced its statistics:

  #@provenance<TAB>corpus.name=<name>
  #@provenance<TAB>corpus.url=<url>
  #@provenance<TAB>corpus.license=<license>
  #@provenance<TAB>corpus.export_date=<YYYY-MM-DD>
  #@provenance<TAB>corpus.file=<basename>
  #@provenance<TAB>corpus.sha256=<sha256 of the input file>
  #@provenance<TAB>params.min_bigram=<n>
  #@provenance<TAB>params.min_trigram=<n>

Character n-gram section (appended after the word entries):

  #@c<TAB>count<TAB>char          character frequency
  #@b<TAB>count<TAB>two-chars     character bigram frequency
  #@t<TAB>count<TAB>three-chars   character trigram frequency

The engine parses these as a generic collocation language model; they are not
dictionary entries. Only aggregate counts are stored, never source sentences.

Example:
  python scripts/build_pinyin_dict.py \
    --jieba .tmp/jieba.txt \
    --out app/src/main/assets/pinyin_dict.tsv \
    --words 60000 \
    --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 \
    --corpus-name "Tatoeba Chinese (cmn) detailed sentences export" \
    --corpus-url https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2 \
    --corpus-license "CC BY 2.0 FR" \
    --corpus-date 2026-11-07 \
    --min-bigram 8 \
    --min-trigram 20
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import re
from collections import Counter
from pathlib import Path

from pypinyin import Style, lazy_pinyin

HAN = re.compile(r"^[\u4e00-\u9fff]{1,4}$")
HAN_CHAR = re.compile(r"[\u4e00-\u9fff]")


def read_corpus_text(corpus_path: str) -> str:
    """Read a plain UTF-8 or bzip2-compressed UTF-8 corpus file."""
    raw = Path(corpus_path).read_bytes()
    if raw.startswith(b"BZh"):
        raw = bz2.decompress(raw)
    return raw.decode("utf-8", errors="ignore")


def build_ngrams(corpus_path: str, min_bigram: int, min_trigram: int) -> list[str]:
    """Derive aggregate character n-gram counts from a declared corpus."""
    text = read_corpus_text(corpus_path)
    chars: Counter[str] = Counter()
    bigrams: Counter[str] = Counter()
    trigrams: Counter[str] = Counter()
    for line in text.splitlines():
        # Tatoeba exports are TSV; keep the sentence column when present.
        parts = line.split("\t")
        sentence = parts[2] if len(parts) >= 3 and parts[1] == "cmn" else line
        sequence = "".join(HAN_CHAR.findall(sentence))
        if not sequence:
            continue
        for index, char in enumerate(sequence):
            chars[char] += 1
            if index + 1 < len(sequence):
                bigrams[sequence[index:index + 2]] += 1
            if index + 2 < len(sequence):
                trigrams[sequence[index:index + 3]] += 1
    lines = ["#@meta\tcharacter n-grams derived from the declared corpus; aggregate counts only, no source sentences stored"]
    for char, count in sorted(chars.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append("#@c\t" + str(count) + "\t" + char)
    for gram, count in sorted(bigrams.items(), key=lambda kv: (-kv[1], kv[0])):
        if count >= min_bigram:
            lines.append("#@b\t" + str(count) + "\t" + gram)
    for gram, count in sorted(trigrams.items(), key=lambda kv: (-kv[1], kv[0])):
        if count >= min_trigram:
            lines.append("#@t\t" + str(count) + "\t" + gram)
    return lines


def provenance_lines(args: argparse.Namespace) -> list[str]:
    """Build the machine-readable provenance header for the n-gram section."""
    corpus_path = Path(args.corpus)
    digest = hashlib.sha256(corpus_path.read_bytes()).hexdigest().upper()
    return [
        "#@provenance\tcorpus.name=" + args.corpus_name,
        "#@provenance\tcorpus.url=" + args.corpus_url,
        "#@provenance\tcorpus.license=" + args.corpus_license,
        "#@provenance\tcorpus.export_date=" + args.corpus_date,
        "#@provenance\tcorpus.file=" + corpus_path.name,
        "#@provenance\tcorpus.sha256=" + digest,
        "#@provenance\tparams.min_bigram=" + str(args.min_bigram),
        "#@provenance\tparams.min_trigram=" + str(args.min_trigram),
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jieba", required=True, help="path to jieba dict.txt")
    parser.add_argument("--out", required=True, help="output TSV path")
    parser.add_argument("--words", type=int, default=60000)
    parser.add_argument("--corpus", default=None, help="plain UTF-8 or .bz2 Chinese sentence corpus for character n-grams")
    parser.add_argument("--corpus-name", default=None, help="human-readable corpus name recorded in the asset")
    parser.add_argument("--corpus-url", default=None, help="corpus download URL recorded in the asset")
    parser.add_argument("--corpus-license", default=None, help="corpus license recorded in the asset")
    parser.add_argument("--corpus-date", default=None, help="corpus export/download date (YYYY-MM-DD) recorded in the asset")
    parser.add_argument("--min-bigram", type=int, default=8)
    parser.add_argument("--min-trigram", type=int, default=20)
    args = parser.parse_args()

    if args.corpus:
        missing = [
            flag
            for flag, value in (
                ("--corpus-name", args.corpus_name),
                ("--corpus-url", args.corpus_url),
                ("--corpus-license", args.corpus_license),
                ("--corpus-date", args.corpus_date),
            )
            if not value
        ]
        if missing:
            parser.error("--corpus requires explicit provenance: " + ", ".join(missing))
    elif args.corpus_name or args.corpus_url or args.corpus_license or args.corpus_date:
        parser.error("corpus provenance flags require --corpus")

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
    header = [
        "# Bitap offline pinyin dictionary | generated from jieba + pypinyin | pinyin\tword\tfreq",
    ]
    ngram_lines: list[str] = []
    if args.corpus:
        header.extend(provenance_lines(args))
        ngram_lines = build_ngrams(args.corpus, args.min_bigram, args.min_trigram)

    output_lines = header + [f"{reading}\t{word}\t{freq}" for reading, word, freq in rows] + ngram_lines

    if args.out == "-":
        import sys
        sys.stdout.write("\n".join(output_lines) + "\n")
        return
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(output_lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {len(rows)} entries + {len(ngram_lines)} ngram rows to {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
