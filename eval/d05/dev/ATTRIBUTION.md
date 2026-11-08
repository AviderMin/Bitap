# D05 diagnostic dev set - attribution

This is a development/diagnostic set, not an acceptance set. It must
never be copied into the production dictionary or used as validation.

## CC0 sentences

Natural sentences are selected from irvin/cc0-sentences, zh-TW files.
Primary (remaining lines after v3 exclusion): setences.txt,
g0v_slack_rand0m.txt, tg_common_voice.txt. Supplement: sayit.txt,
lms.txt, cvsprint_201902.txt, exam_text.txt, gov_press_release.txt,
cofacts.txt, web_slang.txt, chatlogs.txt.
Repository: https://github.com/irvin/cc0-sentences
License: CC0 1.0 Universal, https://creativecommons.org/publicdomain/zero/1.0/
Archive: .tmp/cc0/cc0-sentences.zip
SHA-256: e229d8e3bef48473d55b3ab1a123555080f855120206ee5a82b34bb9413d9fc5

The source is traditional Chinese (zh-TW); text was converted with
zhconv 1.4.3 to simplified Chinese before filtering. No Tatoeba text
is used. The set excludes v3/v1/v2 exact and near-duplicate sentences.

## Chinese Wikipedia long sentences

natural_long sentences also include text from Chinese Wikipedia
(https://zh.wikipedia.org/), licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0).
Source snapshot: wikipedia_long_source.tsv. Each row records pageid,
oldid, title, stable URL (oldid URL) and sentence; attribution is given
through those revision links. The Wikipedia text is evaluation-only and
is not copied into the production dictionary.

## Special categories

typo/shorthand/mixed_pinyin word candidates come from the jieba
dictionary (MIT, https://github.com/fxsjy/jieba); pinyin strings use
pypinyin (MIT, https://github.com/mozillazg/python-pinyin). They are
constructed from generic dictionary words, not rewritten from v3/v2
expected sentences.
