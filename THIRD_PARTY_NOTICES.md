# Third-Party Notices

Bitap 代码以 Apache-2.0 发布。以下依赖/数据分别遵循其许可：

| 组件 | 版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| Miuix | 0.9.4 | Apache-2.0 | Miuix 主题与组件 |
| Glasmio | 0.1.2 | Apache-2.0 | Liquid Glass 主题与组件 |
| LiteRT-LM | 0.19.1 | Apache-2.0 | 本地模型运行时 |
| Qwen3-0.6B | - | Apache-2.0 | 可选本地模型（不随 APK 分发） |
| AndroidX / Jetpack Compose | BOM 2026.09.00 | Apache-2.0 | UI 基础 |
| Kotlin / kotlinx.coroutines | 2.4.21 / 1.11.0 | Apache-2.0 | 语言与协程 |

## 拼音词库数据与工具

拼音词库 `app/src/main/assets/pinyin_dict.tsv` 由公开中文词频数据、拼音转换工具与公开中文句子导出生成，仅包含词语、拼音、频次以及聚合字符 n-gram 计数，不含用户数据，也不包含原始句子。

| 数据/工具 | 来源 | 版本/导出日期 | 许可 | 用途 |
| --- | --- | --- | --- | --- |
| jieba 词频词典 | https://github.com/fxsjy/jieba | dict.txt（构建时使用，不随包分发） | MIT | 通用中文词频（词条与频次）、单字表 |
| pypinyin | https://github.com/mozillazg/python-pinyin | - | MIT | 汉字转无语调拼音 |
| Tatoeba Chinese (cmn) detailed sentences export | https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2 | 2026-11-07；SHA-256 `DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A` | CC BY 2.0 FR（https://creativecommons.org/licenses/by/2.0/fr/） | 仅派生字符 n-gram 聚合计数（#@c/#@b/#@t）；不随包分发原始句子 |

### Tatoeba 署名与许可证说明

Tatoeba 的中文普通话（cmn）句子由 Tatoeba 贡献者提供，按 **Creative Commons Attribution 2.0 France（CC BY 2.0 FR）** 许可使用。该许可**允许商业使用**，但要求署名、提供许可证链接并说明修改。

**要求的署名文本（本仓库实际使用）**：

> Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR, https://creativecommons.org/licenses/by/2.0/fr/). Authors: see `docs/TATOEBA_CONTRIBUTORS.txt` (976 contributors, generated mechanically from the detailed sentences export). Changes: sentence text was used only to derive aggregate character n-gram counts; no original sentences are redistributed.

署名履行方式：Tatoeba wiki 的 Terms of Use 允许通过“列出所有作者”（a list of all authors）方式署名；`docs/TATOEBA_CONTRIBUTORS.txt` 由 `scripts/generate_tatoeba_contributors.py` 从 detailed 导出机械生成，按用户名排序去重，共 976 位作者，含导出文件名、SHA-256 与生成命令。

**边界与保留**：

- Tatoeba 的 detailed 导出没有逐句 license 列；上述许可是 Tatoeba Terms of Use 第 6.2 条的数据集级默认声明（文本句子默认 CC BY 2.0 FR）。
- Tatoeba 中可能包含从外部来源导入的句子，其额外署名/许可要求无法完全排除；若发现此类句子，应将其排除或替换，不能仅以“聚合统计”或“demo”作为许可已解决的依据。
- Tatoeba 的 CC BY 2.0 FR 仅覆盖该数据集；不覆盖 jieba/pypinyin（各自 MIT）、LiteRT-LM / Qwen3-0.6B 模型权重或其他第三方资源，也不构成“所有资源均可商用”的声明。

具体处理过程、验证命令、产物与评测对比见 [docs/LICENSE_CLEANUP.md](docs/LICENSE_CLEANUP.md)。
