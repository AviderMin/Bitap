#!/usr/bin/env python3
"""Generate the Tatoeba attribution list mechanically from the detailed export.

Tatoeba's CC BY 2.0 FR requires credit to the sentence authors. The plain
"sentences" export only contains id / language / text, so this repository uses
the "detailed sentences" export, whose documented six columns are:

    sentence id <TAB> language <TAB> text <TAB> username <TAB> added <TAB> modified

There is no per-row license column in this export; the license is the
dataset-level default declared by Tatoeba's Terms of Use (section 6.2):
Creative Commons Attribution 2.0 France (CC BY 2.0 FR).

This script writes a deterministic, alphabetically sorted list of all distinct
usernames with their sentence counts. It does not edit or invent names.

Usage:
  python scripts/generate_tatoeba_contributors.py \
    --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 \
    --out docs/TATOEBA_CONTRIBUTORS.txt
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
from collections import Counter
from pathlib import Path

EXPORT_DATE = "2026-11-07"
SOURCE_URL = "https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2"


def read_export(path: Path) -> tuple[list[list[str]], str]:
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest().upper()
    if raw.startswith(b"BZh"):
        raw = bz2.decompress(raw)
    text = raw.decode("utf-8")
    rows = [line.split("	") for line in text.splitlines() if line.strip()]
    return rows, digest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True, help="Tatoeba detailed sentences .tsv.bz2")
    parser.add_argument("--out", required=True, help="output attribution file")
    args = parser.parse_args()

    corpus = Path(args.corpus)
    rows, digest = read_export(corpus)
    field_counts = Counter(len(row) for row in rows)
    if field_counts != Counter({6: len(rows)}):
        raise SystemExit("unexpected column count in detailed export: " + repr(field_counts))

    authors: Counter[str] = Counter()
    empty_usernames = 0
    for row in rows:
        name = row[3].strip()
        if not name:
            empty_usernames += 1
            name = "(unknown)"
        authors[name] += 1

    lines = [
        "# Tatoeba Chinese (cmn) sentence contributors",
        "# Generated mechanically by scripts/generate_tatoeba_contributors.py; do not edit by hand.",
        "# Source file: " + corpus.name,
        "# Source URL: " + SOURCE_URL,
        "# Export date: " + EXPORT_DATE,
        "# SHA-256: " + digest,
        "# Sentences: " + str(len(rows)) + " | Authors: " + str(len(authors)) + " | Empty username rows: " + str(empty_usernames),
        "# Columns: sentence id, language, text, username, added, modified (no per-row license column)",
        "# Generation command: python scripts/generate_tatoeba_contributors.py --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 --out docs/TATOEBA_CONTRIBUTORS.txt",
        "# username\tsentence_count",
    ]
    lines.extend(name + "\t" + str(count) for name, count in sorted(authors.items(), key=lambda kv: kv[0].lower()))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(
        "wrote " + str(len(authors)) + " authors (" + str(len(rows)) + " sentences, "
        + str(empty_usernames) + " empty usernames) to " + str(out)
        + " (" + str(out.stat().st_size) + " bytes)"
    )


if __name__ == "__main__":
    main()
