# 拼音词库许可清理报告（P0）

- 分支：`license-cleanup`
- 完成日期：2026-11-07
- 范围：`app/src/main/assets/pinyin_dict.tsv` 及仓库内的来源说明/许可文档
- 状态：**许可已核验并落实署名**（仅针对本报告所述 Tatoeba 数据集与词库产物；不等于“所有资源均可商用”）

## 1. 结论

`app/src/main/assets/pinyin_dict.tsv` 现由以下数据与工具生成：

1. jieba 中文词频词典（MIT）——词条、词频、单字表；
2. pypinyin（MIT）——汉字转无语调拼音；
3. Tatoeba Chinese (cmn) detailed sentences export（CC BY 2.0 FR，2026-11-07）——**仅派生**字符 n-gram 聚合计数（`#@c` / `#@b` / `#@t`）。

生成物不包含原始句子、不包含 `#@w` 语料词频行、不包含任何许可未确认的数据。Tatoeba 的 CC BY 2.0 FR 允许商业使用，但要求署名；本仓库通过 `docs/TATOEBA_CONTRIBUTORS.txt` 列出 detailed 导出中的全部 976 位作者，并在此文档与 `THIRD_PARTY_NOTICES.md` 中记录署名文本、许可证链接和修改说明，从而落实 BY 条件。

## 2. 数据来源与许可证核验

### 2.1 最终使用的导出

| 项目 | 值 |
| --- | --- |
| 文件名 | `cmn_sentences_detailed.tsv.bz2` |
| 版本/快照 | 2026-11-07 周导出（Tatoeba 导出未提供单独版本号，以导出日期与 SHA-256 标识） |
| 下载 URL | https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2 |
| 导出日期 | 2026-11-07 |
| 文件大小 | 1,728,683 bytes |
| SHA-256 | `DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A` |
| 句子数 | 89,289 |
| 作者数（去重） | 976 |
| 空 username 行 | 0 |
| 列结构 | `sentence id / language / text / username / added / modified`（6 列，无逐句 license 列） |

候选筛选阶段还下载了同日的普通句子导出 `cmn_sentences.tsv.bz2`（1,259,986 bytes，SHA-256 `262A48CACB7693F6E47A2C4C42F216A5C45DD28B9B62014B159628CC908E8FCC`，3 列：id / language / text）。已验证它与 detailed 导出的 89,289 条句子 ID 集合完全一致、逐句文本完全一致；两者唯一区别是 detailed 导出多出 username / added / modified 三列。因此候选筛选所用文本与最终资产所用文本一致，最终资产使用 detailed 导出以取得作者元数据。

### 2.2 官方许可证证据

1. **Tatoeba Terms of Use（法文原文为权威版本）**：https://tatoeba.org/en/terms_of_use
   - 第 6.2 条原文：“L’infrastructure technique de Tatoeba utilise par défaut, pour la contribution de phrases textuelles, la licence Creative Commons Attribution 2.0 France (CC-BY 2.0 FR). La mention BY implique une seule restriction sur l’utilisation, la réutilisation, la modification et la diffusion de la phrase : une condition d’attribution. C’est-à-dire qu’utiliser, réutiliser, modifier et diffuser la phrase n’est autorisé que si le nom de l’auteur·e est cité.”
   - 中文转述：Tatoeba 技术基础设施默认对文本句子贡献使用 **Creative Commons Attribution 2.0 France（CC BY 2.0 FR）**；BY 条件意味着使用、复用、修改和分发句子的唯一限制是**引用作者姓名**。

2. **Tatoeba wiki（Terms of Use 历史版本/英文说明）**：https://en.wiki.tatoeba.org/articles/show/terms-of-use-v1
   - “Please note that this license does allow commercial uses of your contributions, as long as such uses are compliant with the terms.”（该许可允许商业使用，但须遵守条款。）
   - 作者同意被署名的三种方式：“a) through a hyperlink (where possible) or URL to the sentence or sentences to which you contributed, b) through a hyperlink (where possible) or URL to an alternative, stable online copy which is freely accessible, which conforms with the license, and which provides credit to the authors in a manner equivalent to the credit given on this website, or c) through a list of all authors.”
   - 我们采用方式 **(c) 列出所有作者**。

3. **Creative Commons CC BY 2.0 FR 许可证摘要页**：https://creativecommons.org/licenses/by/2.0/fr/
   - “You must give appropriate credit, provide a link to the license, and indicate if changes were made.”（必须适当署名、提供许可证链接并说明是否修改。）

4. **Tatoeba 下载页（导出结构说明）**：https://tatoeba.org/en/downloads
   - “句子”导出：`句子编号 [制表符] 语言 [制表符] 文本`；
   - “Detailed Sentences”导出：`句子编号 [制表符] 语言 [制表符] 文本 [制表符] 用户名 [制表符] 添加日期 [制表符] 最后修改`；
   - 下载页另说明部分日语/英语句子来自田中语料库（公共领域），并另设单独的 CC0 句子导出；对于 cmn 导出未看到额外的单句来源声明。该页面也说明 Tatoeba 句子由贡献者添加，**Tatoeba 可能包含外部导入句**。

### 2.3 逐句许可证字段核查与被排除句数

- detailed 导出共 89,289 行，每行均为 **6 个 tab 分隔字段**，字段名如上；**没有 license 列**，也没有句级许可证标记或公共领域标记。
- 因此无法从该文件按句统计“不是 CC BY 2.0 FR（或公共领域）”的数量；我们采用 Tatoeba Terms of Use 第 6.2 条的**数据集级默认声明**：文本句子默认 CC BY 2.0 FR。
- 按数据集级声明，本次对全部 **89,289 句**生成聚合统计；**被排除句数 = 0**（未做逐句排除）。若未来发现某句存在额外限制，应按“发现即排除/替换”的流程处理，不能以“已聚合统计”为由保留。
- 风险提示：Tatoeba 站点允许句子许可证向更严格方向变更，且可能包含外部导入句；当前导出缺少逐句许可元数据，无法进行逐句法律审计。此为本报告明确保留的边界。

## 3. 处理步骤与脚本命令

### 3.1 生成最终词典

```powershell
$env:PYTHONPATH = 'D:/Projects/Github/Bitap/.tmp/pypinyin_pkg'
python scripts/build_pinyin_dict.py `
  --jieba D:/Projects/Github/Bitap/.tmp/jieba.txt `
  --out app/src/main/assets/pinyin_dict.tsv `
  --words 60000 `
  --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 `
  --corpus-name "Tatoeba Chinese (cmn) detailed sentences export" `
  --corpus-url https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2 `
  --corpus-license "CC BY 2.0 FR" `
  --corpus-date 2026-11-07 `
  --min-bigram 3 `
  --min-trigram 6
```

脚本行为（`scripts/build_pinyin_dict.py`）：

1. 读取 jieba dict.txt，选取词频最高的 60,000 个多字词 + 单字表（最终 65,999 条）；
2. 用 pypinyin 生成无语调拼音，写入 `pinyin_key<TAB>word<TAB>freq`；
3. 读取 `.bz2` 句子导出，只保留第 3 列句子文本，剔除非汉字字符；
4. 统计字符 / 二元组 / 三元组出现次数，分别按 `min_bigram=3`、`min_trigram=6` 阈值写入 `#@c` / `#@b` / `#@t`；
5. **不写 `#@w` 语料词频行**；不写任何原始句子；
6. 在文件头写入 8 行 `#@provenance`（name/url/license/export_date/file/sha256/min_bigram/min_trigram）。

### 3.2 生成作者署名列表

```powershell
python scripts/generate_tatoeba_contributors.py `
  --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2 `
  --out docs/TATOEBA_CONTRIBUTORS.txt
```

该脚本从 detailed 导出机械生成：按用户名排序去重、统计句数、报告空 username 行；输出文件头包含导出文件名、URL、日期、SHA-256、句子数、作者数和生成命令。**禁止手工编辑该文件**（文件头也已注明）。

### 3.3 验证命令

```powershell
python scripts/verify_pinyin_dict_license.py `
  --dict app/src/main/assets/pinyin_dict.tsv `
  --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2
$names = @(('dialog' + '_' + 'corpus'), ('小' + '黄鸡'), ('candle' + 'will'))
foreach ($name in $names) { git grep -n -i -- $name }   # 期望：0 命中
```

## 4. 产物

| 项目 | 值 |
| --- | --- |
| 最终资产 | `app/src/main/assets/pinyin_dict.tsv` |
| 文件大小 | 2,175,214 bytes（git blob，LF；替换前旧词典 git blob 2,915,616 bytes。Windows CRLF checkout 分别为 2,304,382 / 3,090,305 bytes） |
| 总行数 | 129,168（旧词典 174,689） |
| 词条数（非注释数据行） | 65,999 |
| `#@c` 字符行 | 5,296 |
| `#@b` 二元组行 | 43,290 |
| `#@t` 三元组行 | 14,573 |
| `#@w` 语料词频行 | 0（旧词典 9,818） |
| `#@provenance` 行 | 8 |
| 资产 SHA-256 | `C1E25C3F4751769FE02B0455CABCE200138A07EFE18660E8CC61C8CCE5D6F054` |

词条部分与 HEAD 旧词典完全一致：65,999 行逐行相同（`git` 对比可通过）；变化只发生在 n-gram 段（旧 `#@c/#@b/#@t/#@w` → 新 `#@c/#@b/#@t`）与 provenance 头。

`#@provenance` 头部实际内容：

```
#@provenance	corpus.name=Tatoeba Chinese (cmn) detailed sentences export
#@provenance	corpus.url=https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2
#@provenance	corpus.license=CC BY 2.0 FR
#@provenance	corpus.export_date=2026-11-07
#@provenance	corpus.file=cmn_sentences_detailed.tsv.bz2
#@provenance	corpus.sha256=DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A
#@provenance	params.min_bigram=3
#@provenance	params.min_trigram=6
```

## 5. 署名要求与落实

**要求**：CC BY 2.0 FR 要求适当署名、提供许可证链接并说明修改；Tatoeba Terms 6.2 明确“使用/复用/修改/分发句子必须引用作者姓名”。Tatoeba 句子作者为 Tatoeba 贡献者。

**履行方式**：

- `docs/TATOEBA_CONTRIBUTORS.txt`：由 `scripts/generate_tatoeba_contributors.py` 从 detailed 导出机械生成，按用户名排序去重，共 **976 位作者**，含每人句子数；文件头记录导出文件名、URL、导出日期、SHA-256、句子数与生成命令。空 username 行：**0**，无需特殊处理（若未来出现空值，脚本会归入 `(unknown)` 并在文件头报告计数）。
- `THIRD_PARTY_NOTICES.md`：记录 Tatoeba 名称、URL、许可、导出日期、SHA-256、用途与完整署名文本。
- 署名文本（与 THIRD_PARTY_NOTICES 一致）：

> Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR, https://creativecommons.org/licenses/by/2.0/fr/). Authors: see `docs/TATOEBA_CONTRIBUTORS.txt` (976 contributors, generated mechanically from the detailed sentences export). Changes: sentence text was used only to derive aggregate character n-gram counts; no original sentences are redistributed.

**边界**：CC BY 2.0 FR 允许商业使用，但必须履行署名等条件；本仓库不把 Tatoeba 的许可扩展到模型权重、运行时或其他第三方资源，也不声明“所有资源均可商用”。Tatoeba 中可能包含外部导入句，其额外许可无法完全排除；如发现，应排除或替换。

## 6. 替换前后质量与性能对比

评测方法：同一 worktree、同一引擎代码（未修改 `app/src/main/java`），`PinyinQualityEvalTest`，dev 132 条 + holdout 132 条；每组设置不同的 `PINYIN_EVAL_STAGE`，报告位于 `build/pinyin-quality-eval-license-*.txt`。**选择以 dev 为主，holdout 仅用于确认不明显回退**。延迟为 JVM 参考值（非真机）。

| 组别 | 词典大小 (bytes) | 行数 | dev top1 | dev top5 | dev avgTaps | dev p50/p95 (ms) | holdout top1 | holdout top5 | holdout avgTaps | holdout p50/p95 (ms) | 失败数 dev/holdout |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 旧词典（HEAD，before） | 2,915,616 | 174,689 | 86/132 (65.2%) | 107/132 (81.1%) | 2.69 | 0.715 / 2.632 | 77/132 (58.3%) | 101/132 (76.5%) | 3.11 | 0.669 / 2.368 | 25 / 31 |
| cand_no_ngram | 1,301,060 | 66,000 | 70/132 (53.0%) | 97/132 (73.5%) | 3.49 | 0.767 / 2.580 | 56/132 (42.4%) | 83/132 (62.9%) | 4.17 | 0.653 / 2.084 | 35 / 49 |
| cand_2_4（min_bigram=2, min_trigram=4） | 2,698,698 | 166,559 | 95/132 (72.0%) | 109/132 (82.6%) | 2.58 | 0.529 / 2.191 | 81/132 (61.4%) | 102/132 (77.3%) | 3.02 | 0.508 / 1.849 | 23 / 30 |
| **cand_3_6（min_bigram=3, min_trigram=6，选定）** | 2,175,187 | 129,168 | 94/132 (71.2%) | 110/132 (83.3%) | 2.58 | 0.553 / 1.830 | 81/132 (61.4%) | 102/132 (77.3%) | 3.01 | 0.440 / 1.178 | 22 / 30 |
| cand_5_10（min_bigram=5, min_trigram=10） | 1,814,557 | 103,247 | 92/132 (69.7%) | 110/132 (83.3%) | 2.58 | 0.437 / 1.571 | 77/132 (58.3%) | 101/132 (76.5%) | 3.09 | 0.469 / 1.707 | 22 / 31 |
| cand_8_20（min_bigram=8, min_trigram=20） | 1,615,284 | 89,250 | 86/132 (65.2%) | 107/132 (81.1%) | 2.73 | 0.436 / 1.606 | 78/132 (59.1%) | 101/132 (76.5%) | 3.08 | 0.456 / 1.216 | 25 / 31 |
| **最终资产（detailed 导出 3/6，与 cand_3_6 语义相同）** | 2,175,214 | 129,168 | 94/132 (71.2%) | 110/132 (83.3%) | 2.58 | 0.544 / 1.842 | 81/132 (61.4%) | 102/132 (77.3%) | 3.01 | 0.538 / 1.957 | 22 / 30 |

失败原因（dev / holdout，合计为 top5 未命中条数）：

| 组别 | dev 失败原因 | holdout 失败原因 |
| --- | --- | --- |
| 旧词典（before） | 排序=9 切分=1 交互=5 容错=10（共 25） | 排序=10 切分=4 交互=9 容错=8（共 31） |
| cand_no_ngram | 排序=12 切分=6 交互=4 容错=13（共 35） | 排序=23 切分=6 交互=7 容错=13（共 49） |
| cand_2_4 | 排序=8 切分=1 交互=5 容错=9（共 23） | 排序=11 切分=3 交互=8 容错=8（共 30） |
| cand_3_6 / 最终资产 | 排序=7 切分=1 交互=5 容错=9（共 22） | 排序=11 切分=3 交互=8 容错=8（共 30） |
| cand_5_10 | 排序=7 切分=1 交互=5 容错=9（共 22） | 排序=12 切分=3 交互=8 容错=8（共 31） |
| cand_8_20 | 排序=10 切分=1 交互=5 容错=9（共 25） | 排序=11 切分=3 交互=9 容错=8（共 31） |

### 6.1 选型理由

- **cand_3_6 与 cand_2_4 的差距很小**：dev top1 94 vs 95（2-4 多 1 条），dev top5 110 vs 109（3-6 多 1 条），holdout top1/top5 完全相同（81/102）；avgTaps 同为 2.58。
- 按“差距很小优先较小文件/更少阈值泄漏风险者”的原则选择 **cand_3_6**：最终 2,175,214 bytes，比 cand_2_4 的 2,698,698 bytes 小约 19.4%；阈值更高（3/6 对 2/4），保留的低频 n-gram 更少，降低对 dev 集的过拟合与阈值泄漏风险。
- **cand_no_ngram 不满足发布质量**：dev top1 70/132（53.0%）比旧词典少 16 条、top5 少 10 条，holdout top1 少 21 条；仅作为“无第三方语料”备选记录，不进入本次最终资产。
- cand_5_10 / cand_8_20 的 dev top1 更低，不选。

## 7. 验证边界与未决风险

1. **Tatoeba 数据集级许可**：detailed 导出无逐句 license 字段，CC BY 2.0 FR 是 Tatoeba Terms of Use 的数据集级默认声明；无法逐句证明每一句均为 CC BY 2.0 FR 或公共领域。发现外部来源句的额外许可要求时应排除或替换。
2. **外部导入句**：Tatoeba 句子可由贡献者添加，Tatoeba wiki 明确“Text from external sources may attach additional attribution requirements...”；下载页也说明部分日语/英语来自田中语料库（公共领域）。本报告不声称已对每一句完成来源审计。
3. **许可范围**：Tatoeba CC BY 2.0 FR 仅覆盖该数据集；不覆盖 jieba/pypinyin（各自 MIT）、LiteRT-LM / Qwen3 模型权重或其他资源；不构成“所有资源均可商用”的声明。
4. **原始语料不随包分发**：仓库和 APK 均不包含 Tatoeba 原始句子文件；只包含 `#@c/#@b/#@t` 聚合计数。聚合统计仍属于数据集派生结果，本报告依据 CC BY 2.0 FR 使用并署名。
5. **质量评测**：dev/holdout 为项目内手写回归集，不代表所有真实用户输入；延迟为 JVM 参考值，不是设备端性能。设备端启动/输入延迟另由性能任务测量。
6. **未完成**：本报告不包含逐句来源审计与外部来源自动化识别；若后续引入新语料，需重新执行本流程并更新 SHA-256/作者列表。

## 8. 复核命令（摘要）

```powershell
# 结构 + 源句抽样交集（期望 ok=true, sample_sentence_intersections=0）
python scripts/verify_pinyin_dict_license.py --dict app/src/main/assets/pinyin_dict.tsv --corpus .tmp/tatoeba/cmn_sentences_detailed.tsv.bz2

# 历史名称检查（构造匹配串，避免检查命令本身命中；期望 0 命中）
$names = @(('dialog' + '_' + 'corpus'), ('小' + '黄鸡'), ('candle' + 'will'))
foreach ($name in $names) { git grep -n -i -- $name }

# 生成物只含聚合计数：每个 #@c/#@b/#@t 行恰好 3 个 tab 字段
Select-String -Path app/src/main/assets/pinyin_dict.tsv -Pattern '^#@[cbt]\t\d+\t[^\t]+$' | Measure-Object
```
