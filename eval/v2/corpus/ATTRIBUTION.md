# Evaluation corpus v2 - data attribution and license

This directory contains an evaluation-only pinyin corpus. It must never be
copied into the production dictionary or used as a training lexicon.

## Tatoeba (natural sentences)

Natural sentences are selected from the Tatoeba Chinese (cmn) sentences
export (https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences.tsv.bz2),
licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR,
https://creativecommons.org/licenses/by/2.0/fr/).

Source file: .tmp/tatoeba/cmn_sentences.tsv
SHA-256: a11463da289d918050e441a003848a795a97dcc47ffd1b9ed5345d1d70e0c84f

Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed
under CC BY 2.0 FR. Authors: see legal/TATOEBA_CONTRIBUTORS.txt (976
contributors, generated mechanically from the 2026-11-07 detailed export).
Changes: a subset of sentences was filtered, punctuation-normalized for
near-duplicate comparison, and redistributed verbatim as evaluation
expectations. No original sentence was added to the production dictionary.

## jieba and pypinyin (special-category construction)

Special-category word seeds were taken from the jieba dictionary
(https://github.com/fxsjy/jieba, MIT License); pinyin strings were produced
with pypinyin (https://github.com/mozillazg/python-pinyin, MIT License).
Neither library's output is used to select natural evaluation sentences.

## Generated files

- pinyin_cases_v2_dev.tsv - development split.
- pinyin_cases_v2_validation.tsv - independent validation split; use only
  for acceptance, never for tuning.
- manifest.json - SHA-256 hashes, source hashes, category counts and the
  exact generation command.
