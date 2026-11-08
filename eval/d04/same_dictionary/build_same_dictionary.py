#!/usr/bin/env python3
"""Build a Rime pinyin_simp.dict.yaml from Bitap's production pinyin asset.

The aim of the D04 same-dictionary experiment is to remove dictionary coverage
as an explanation: the Rime host gets exactly the words and weights that the
Bitap baseline used, while scheme/engine/tokenisation differences remain.

Input:  app/src/main/assets/pinyin_dict.tsv  (pinyin<TAB>word<TAB>frequency)
Output: <out-shared>/pinyin_simp.dict.yaml   (word<TAB>syllables<TAB>weight)
        <out-shared>/bitap_d04.schema.yaml   (copied from eval/d04)
        <out-shared>/default.yaml            (copied from eval/d04)
        <report>                             (deterministic conversion report)

Pinyin conversion:
- The Bitap asset stores one flat pinyin key per word (e.g. "zhanlve").
- pypinyin lazy_pinyin(word, style=Style.NORMAL) is used only to recover the
  syllable boundaries (e.g. ["zhan", "lve"]). The flat concatenation is compared
  with the asset key for every entry and every mismatch is reported; the
  dictionary is NOT adjusted against any evaluation answer.
- Rime's pinyin-simp convention spells üe as "ue" ("lue"/"nue") while pypinyin
  emits "lve"/"nve"; these are canonicalised mechanically and reported.

No evaluation corpus is read by this script. Build outputs are ignored by git.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

from pypinyin import Style, lazy_pinyin, pinyin as pypinyin_heteronym
import pypinyin

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_DICT = ROOT / "app" / "src" / "main" / "assets" / "pinyin_dict.tsv"
DEFAULT_SCHEMA_DIR = ROOT / "eval" / "d04"
DEFAULT_OUT_SHARED = ROOT / "build" / "d04" / "same-dictionary" / "shared"
DEFAULT_REPORT = ROOT / "eval" / "d04" / "same_dictionary" / "pinyin_conversion_report.json"
DEFAULT_RIME_REFERENCE = ROOT / ".tmp" / "d04" / "bootstrap" / "pinyin-simp" / "pinyin_simp.dict.yaml"

HAN_WORD = re.compile(r"^[\u4e00-\u9fff]+$")
PINYIN = re.compile(r"^[a-z]+$")
CANONICAL_SYLLABLE = {
    # pypinyin uses lve/nve for üe; Rime pinyin-simp stores lue/nue and
    # derives the typed lve/nve forms through speller algebra.
    "lve": "lue",
    "nve": "nue",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_bitap_dictionary(path: Path) -> list[tuple[str, str, int]]:
    entries: list[tuple[str, str, int]] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        for line_number, raw in enumerate(handle, 1):
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 3:
                raise ValueError(f"{path}:{line_number}: expected 3 columns, got {len(fields)}")
            pinyin, word, frequency = fields
            if not PINYIN.fullmatch(pinyin):
                raise ValueError(f"{path}:{line_number}: invalid pinyin key {pinyin!r}")
            if not HAN_WORD.fullmatch(word):
                raise ValueError(f"{path}:{line_number}: non-Han word {word!r}")
            try:
                weight = int(frequency)
            except ValueError as error:
                raise ValueError(f"{path}:{line_number}: invalid frequency {frequency!r}") from error
            if weight <= 0:
                raise ValueError(f"{path}:{line_number}: non-positive frequency {weight}")
            entries.append((pinyin, word, weight))
    if not entries:
        raise ValueError(f"{path}: no entries")
    return entries


def pypinyin_version() -> str:
    return getattr(pypinyin, "__version__", "unknown")


def read_rime_reference(path: Path) -> tuple[set[str], list[tuple[str, int]]]:
    """Syllable inventory and word entries of the baseline pinyin-simp dictionary."""
    syllables: set[str] = set()
    entries: list[tuple[str, int]] = []
    if not path.is_file():
        return syllables, entries
    in_entries = False
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if line == "...":
                in_entries = True
                continue
            if not in_entries or not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) >= 3:
                syllables.update(fields[1].split())
                try:
                    entries.append((fields[0], int(fields[2])))
                except ValueError:
                    pass
    return syllables, entries


def rime_reference_report(path: Path, entries: list[tuple[str, int]], project_words: set[str]) -> dict:
    if not path.is_file():
        return {"path": None, "available": False}
    words = [word for word, _weight in entries]
    unique_words = set(words)
    return {
        "path": path.relative_to(ROOT).as_posix(),
        "available": True,
        "entries": len(entries),
        "uniqueWords": len(unique_words),
        "minWeight": min((weight for _word, weight in entries), default=None),
        "maxWeight": max((weight for _word, weight in entries), default=None),
        "overlapWithProjectWords": len(unique_words & project_words),
        "projectOnlyWords": len(project_words - unique_words),
        "rimeOnlyWords": len(unique_words - project_words),
        "duplicateWordEntries": len(words) - len(unique_words),
    }


def convert(entries: list[tuple[str, str, int]], reference_syllables: set[str]) -> tuple[list[tuple[str, str, int]], dict]:
    """Return (rime_rows, conversion_report_fragment)."""
    rows: list[tuple[str, str, int]] = []
    flat_mismatches: list[dict] = []
    count_mismatches: list[dict] = []
    invalid: list[dict] = []
    canonicalized: list[dict] = []
    unknown_syllables: list[dict] = []
    polyphonic_entries: list[str] = []
    syllable_counter: dict[str, int] = {}

    for pinyin, word, weight in entries:
        raw_syllables = [str(item).lower() for item in lazy_pinyin(word, style=Style.NORMAL)]
        if len(raw_syllables) != len(word):
            count_mismatches.append({"word": word, "pinyin": pinyin, "syllables": raw_syllables})
        flat = "".join(raw_syllables)
        if flat != pinyin:
            flat_mismatches.append({"word": word, "pinyin": pinyin, "pypinyinFlat": flat})
        local_canonicalized = False
        canonical_syllables: list[str] = []
        for syllable in raw_syllables:
            canonical = CANONICAL_SYLLABLE.get(syllable, syllable)
            if canonical != syllable:
                local_canonicalized = True
            canonical_syllables.append(canonical)
            if not PINYIN.fullmatch(canonical):
                invalid.append({"word": word, "pinyin": pinyin, "syllable": syllable})
            if reference_syllables and canonical not in reference_syllables:
                unknown_syllables.append({"word": word, "pinyin": pinyin, "syllable": canonical})
        if local_canonicalized:
            canonicalized.append({
                "word": word,
                "pinyin": pinyin,
                "pypinyinSyllables": " ".join(raw_syllables),
                "rimeSyllables": " ".join(canonical_syllables),
            })
        for syllable in canonical_syllables:
            syllable_counter[syllable] = syllable_counter.get(syllable, 0) + 1
        readings = pypinyin_heteronym(word, style=Style.NORMAL, heteronym=True)
        if any(len(reading) > 1 for reading in readings):
            polyphonic_entries.append(word)
        rows.append((word, " ".join(canonical_syllables), weight))

    # A deterministic order that matches "sort: by_weight" and keeps equal
    # weights alphabetical; source order is already frequency-sorted, but an
    # explicit key makes the output stable if the asset changes.
    rows.sort(key=lambda row: (-row[2], len(row[0]), row[0], row[1]))
    fragment = {
        "checked": len(entries),
        "flatPinyinMismatchCount": len(flat_mismatches),
        "flatPinyinMismatchSamples": flat_mismatches[:20],
        "syllableCountMismatchCount": len(count_mismatches),
        "syllableCountMismatchSamples": count_mismatches[:20],
        "invalidEntryCount": len(invalid),
        "invalidEntrySamples": invalid[:20],
        "canonicalization": {k: v for k, v in sorted(CANONICAL_SYLLABLE.items())},
        "canonicalizedEntryCount": len(canonicalized),
        "canonicalizedSamples": canonicalized[:10],
        "distinctSyllables": len(syllable_counter),
        "unknownSyllableCount": len(unknown_syllables),
        "unknownSyllableSamples": unknown_syllables[:20],
        "polyphonicEntryCount": len(polyphonic_entries),
        "polyphonicEntrySamples": polyphonic_entries[:20],
    }
    return rows, fragment


def write_rime_dictionary(path: Path, rows: list[tuple[str, str, int]], source: Path, source_hash: str) -> None:
    header = [
        "# Rime dictionary generated from the Bitap production pinyin asset.",
        "# source: " + source.relative_to(ROOT).as_posix(),
        "# source-sha256: " + source_hash,
        "# entries: " + str(len(rows)),
        "# pypinyin: " + pypinyin_version(),
        "# The source stores flat pinyin keys; pypinyin provides syllable boundaries.",
        "# lve/nve are canonicalised to Rime pinyin-simp's lue/nue; see the conversion report.",
        "# This file is a host experiment artifact, not shipped in the APK.",
        "",
        "---",
        "name: pinyin_simp",
        'version: "0.1-bitap-same-dictionary"',
        "sort: by_weight",
        "...",
        "",
    ]
    body = [f"{word}\t{syllables}\t{weight}" for word, syllables, weight in rows]
    path.write_text("\n".join(header + body) + "\n", encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dict", type=Path, default=DEFAULT_DICT, help="Bitap pinyin_dict.tsv")
    parser.add_argument("--schema-dir", type=Path, default=DEFAULT_SCHEMA_DIR, help="directory with bitap_d04.schema.yaml/default.yaml")
    parser.add_argument("--out-shared", type=Path, default=DEFAULT_OUT_SHARED, help="output Rime shared data directory")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT, help="conversion report JSON path")
    parser.add_argument(
        "--rime-reference",
        type=Path,
        default=DEFAULT_RIME_REFERENCE,
        help="optional original pinyin-simp dict used only for syllable inventory validation",
    )
    args = parser.parse_args()

    source = args.dict if args.dict.is_absolute() else (ROOT / args.dict)
    schema_dir = args.schema_dir if args.schema_dir.is_absolute() else (ROOT / args.schema_dir)
    out_shared = args.out_shared if args.out_shared.is_absolute() else (ROOT / args.out_shared)
    report_path = args.report if args.report.is_absolute() else (ROOT / args.report)
    reference = args.rime_reference if args.rime_reference.is_absolute() else (ROOT / args.rime_reference)

    entries = read_bitap_dictionary(source)
    reference_syllables, reference_entries = read_rime_reference(reference)
    rows, conversion = convert(entries, reference_syllables)

    out_shared.mkdir(parents=True, exist_ok=True)
    for name in ("bitap_d04.schema.yaml", "default.yaml"):
        shutil.copyfile(schema_dir / name, out_shared / name)
    dictionary = out_shared / "pinyin_simp.dict.yaml"
    write_rime_dictionary(dictionary, rows, source, sha256(source))

    report = {
        "source": {
            "path": source.relative_to(ROOT).as_posix(),
            "sha256": sha256(source),
            "entries": len(entries),
            "totalFrequency": sum(row[2] for row in entries),
        },
        "output": {
            "sharedDir": out_shared.relative_to(ROOT).as_posix(),
            "dictionary": dictionary.relative_to(ROOT).as_posix(),
            "dictionarySha256": sha256(dictionary),
            "entries": len(rows),
        },
        "schema": {
            "bitap_d04Sha256": sha256(out_shared / "bitap_d04.schema.yaml"),
            "defaultSha256": sha256(out_shared / "default.yaml"),
        },
        "pypinyin": {
            "version": pypinyin_version(),
            "api": "lazy_pinyin(word, style=Style.NORMAL)",
        },
        "conversion": conversion,
        "rimeReference": {
            **rime_reference_report(reference, reference_entries, {word for _pinyin, word, _weight in entries}),
            "distinctSyllables": len(reference_syllables),
        },
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    print(json.dumps({
        "dictionary": dictionary.relative_to(ROOT).as_posix(),
        "dictionarySha256": report["output"]["dictionarySha256"],
        "entries": len(rows),
        "report": report_path.relative_to(ROOT).as_posix(),
        "flatPinyinMismatchCount": conversion["flatPinyinMismatchCount"],
        "canonicalizedEntryCount": conversion["canonicalizedEntryCount"],
        "unknownSyllableCount": conversion["unknownSyllableCount"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
