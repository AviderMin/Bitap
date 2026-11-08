# Evaluation corpus v3 - attribution and license

This directory contains a validation-only pinyin evaluation corpus.
It must never be copied into the production dictionary or used for tuning.

## Source

Sentences: irvin/cc0-sentences, zh-TW daily-chat files
(setences.txt, chatlogs.txt, g0v_slack_rand0m.txt, tg_common_voice.txt).
Repository: https://github.com/irvin/cc0-sentences
License: CC0 1.0 Universal (public domain dedication),
https://creativecommons.org/publicdomain/zero/1.0/
Source archive: .tmp/cc0/cc0-sentences.zip
SHA-256: e229d8e3bef48473d55b3ab1a123555080f855120206ee5a82b34bb9413d9fc5

The source files are traditional Chinese (zh-TW). Sentence text was
converted to simplified Chinese with zhconv 1.4.3 before filtering and is
redistributed in simplified form for evaluation only. No Tatoeba text is
used by this corpus.

## Generated files

- pinyin_cases_v3_validation.tsv - acceptance-only validation split.
- manifest.json - source file SHA-256 values, category counts, filter and
  generation details.
