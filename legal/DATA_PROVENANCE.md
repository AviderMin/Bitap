# 数据来源、署名与保留事项

本文件从已有仓库记录整理，不构成一次新的法律审查或“所有资源可任意商用”的声明。根项目代码许可见 [LICENSE](../LICENSE)，依赖声明见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。

## 1. 生产拼音资产

资产：`app/src/main/assets/pinyin_dict.tsv`；65,999词条；词条由jieba词频词典（MIT）与pypinyin（MIT）生成。当前资产还包含Tatoeba派生的字符/二元/三元计数，不包含原始句子或`#@w`语料词频。

Tatoeba来源为Chinese(cmn) detailed sentences export，导出日期2026-11-07；provenance声明SHA-256 `DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A`；bigram/trigram最小计数3/6。原记录对全部89,289句生成统计；同日普通导出与detailed的ID/文本一致，普通导出解压后hash为 `a11463da289d918050e441a003848a795a97dcc47ffd1b9ed5345d1d70e0c84f`。

Tatoeba数据集级许可记录为 [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/)。976位作者列表见 [TATOEBA_CONTRIBUTORS](TATOEBA_CONTRIBUTORS.txt)，此次从docs迁出并保留。不得因重建开发文档删除署名。

> Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR, https://creativecommons.org/licenses/by/2.0/fr/). Authors: see legal/TATOEBA_CONTRIBUTORS.txt. Changes: production resources contain derived character n-gram counts, not the original sentences. Evaluation expectations redistribute selected source sentences under the same attribution requirements.

边界：detailed没有逐句license列；可能有外部导入句的额外要求，发现后排除/替换并重建来源记录，不以“只是聚合”代替许可核验。上述声明不覆盖其他依赖和模型。

原先无明确许可的旧实验语料不再用于生产；本轮将相关临时文件移到本地备份，不提交或重新引入。不能从备份恢复旧语料作为新数据源。

## 2. 评测资产

| 资产 | 来源与许可 | 保留要求 |
|---|---|---|
| v2自然句 | Tatoeba，CC BY 2.0 FR | eval/v2/corpus/ATTRIBUTION.md与作者列表；它与生产统计同源 |
| v3自然句 | irvin/cc0-sentences，CC0-1.0，zhconv转简体 | 固定zip/源文件hash、文件/行号追溯；有1条已知训练句重合 |
| D05 CC0自然句 | 同CC0仓库剩余行，排除已选验收文本/近重复 | manifest、转换方法、来源路径，不宣称完全不同领域分布 |
| D05 Wikipedia长句 | 中文维基百科，CC BY-SA 4.0 | eval/d05/dev/wikipedia_long_source.tsv逐行pageid/oldid/URL和ATTRIBUTION；快照不进生产词库 |
| 专项词种子/拼音生成 | jieba/pypinyin，MIT | 版本与来源说明；不从验收答案写生产特例 |
| Rime宿主实验 | librime BSD-3-Clause；pinyin-simp Apache-2.0 | eval/d04中的license、方案改动署名；传递依赖未完成生产分发审查 |

Wikipedia快照具有自己的署名/相同方式共享要求，不因仓库代码采用Apache-2.0而改变。语料和代码许可分开解释；评测句不随APK打包。

## 3. 核验工具与当前原件边界

```powershell
python scripts/verify_pinyin_dict_license.py --dict app/src/main/assets/pinyin_dict.tsv --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2
python scripts/generate_tatoeba_contributors.py --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 --out legal/TATOEBA_CONTRIBUTORS.txt
python eval/v3/check_v3_corpus.py --strict
python eval/d05/dev/check_d05_dev.py
```

生产detailed压缩原件当前盘点缺失，需使用与asset声明hash匹配的冻结原件补齐，不能以普通导出替代来宣称校验通过。已存在的作者清单和生产资产保留，不重新生成不明来源数据。

## 4. 本地模型

Qwen3-0.6B与LiteRT依赖分别受其许可约束，模型权重不随仓库/应用分发。本地 `models/` 是用户下载的忽略资源，本轮整理保留；不能把源码许可证作为第三方权重许可证。
