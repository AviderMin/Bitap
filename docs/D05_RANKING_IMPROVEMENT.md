# D05 排序机制改进与关闭消融

日期：2026-11-08（Asia/Shanghai）。前置：`docs/D05_FAILURE_DIAGNOSIS.md`；D05 dev 已由 task-12 扩充为 1,050 条（natural_short 300 / natural_medium 277 / natural_long 123 / typo 100 / shorthand 100 / mixed_pinyin 100 / mixed_en 50），SHA-256 `63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad`。**D05 是诊断 dev，不是验收；本次没有运行 v3。**

## 1. 假设与口径

D05 的 Top5 未命中仍主要来自“正确完整候选已生成但排名靠后”。本轮固定词库、搜索预算、候选展示（包括 early partial 在 5 个 full 之后）和会话/学习关闭状态，只测试一个排序权重：**词内 corpus character-bigram 权重 `corpusWordNgramWeight`**。假设：把该权重从 0.35 调到 0.50，能让正确完整候选在歧义/长句中更容易进入 Top5，同时不改变召回池。

输入预算：natural_long 123 条中有 **33 条 pinyin 长度 65–70**，超过 `PinyinEngine` 的 64 字符硬上限（`MAX_SEARCH_INPUT_LENGTH`），不属于排序问题。**排序消融分母使用有效输入长度 ≤64 的子集：naturalRankable=667（short 300 + medium 277 + long 90），inputBudgetExcluded=33；fullCoverage/recall 只在可排序子集统计。**
- mixed_en 50 条是交互项，单独统计，不计入引擎分母。
- 延迟为 JVM `search(limit=8)` 单次样本，不是 Android 端到端。

## 2. 排序候选消融（naturalRankable 667，build/d05/ranking-ablation.txt）

所有变体通过 `PinyinEngine.EngineTuning + withTuning` 在 JVM 内运行；base 为 task-12 dev + 旧排序默认（`corpusWordNgramWeight=0.35`），其余权重默认。

| 变体 | Top1 | Top5 | rankCost | oracle64 | fullCoverage |
|---|---:|---:|---:|---:|---:|
| baseline（corpusWordNgramWeight=0.35） | 235/667 35.2% | 363/667 54.4% | 5.025 | 477/667 | 667/667 |
| wordInsertionCompensation=8 | 231/667 34.6% | 359/667 53.8% | 5.081 | 469/667 | 667/667 |
| wordInsertionCompensation=10 | 233/667 34.9% | 363/667 54.4% | 5.040 | 474/667 | 667/667 |
| wordInsertionCompensation=14 | 234/667 35.1% | 364/667 54.6% | 5.022 | 475/667 | 667/667 |
| wordInsertionCompensation=16 | 233/667 34.9% | 355/667 53.2% | 5.091 | 471/667 | 667/667 |
| wordInsertionCompensation=20 | 194/667 29.1% | 315/667 47.2% | 5.561 | 438/667 | 667/667 |
| contextWeight=0.6 | 229/667 34.3% | 355/667 53.2% | 5.096 | 468/667 | 667/667 |
| contextWeight=0.9 | 234/667 35.1% | 363/667 54.4% | 5.021 | 475/667 | 667/667 |
| contextWeight=1.6 | 232/667 34.8% | 359/667 53.8% | 5.084 | 473/667 | 667/667 |
| contextWeight=2.0 | 225/667 33.7% | 355/667 53.2% | 5.135 | 468/667 | 667/667 |
| contextWeight=2.4 | 219/667 32.8% | 350/667 52.5% | 5.208 | 463/667 | 667/667 |
| corpusWordNgramWeight=0.15 | 227/667 34.0% | 358/667 53.7% | 5.102 | 472/667 | 667/667 |
| corpusWordNgramWeight=0.25 | 232/667 34.8% | 361/667 54.1% | 5.051 | 477/667 | 667/667 |
| corpusWordNgramWeight=0.40 | 233/667 34.9% | 361/667 54.1% | 5.030 | 478/667 | 667/667 |
| corpusWordNgramWeight=0.45 | 235/667 35.2% | 363/667 54.4% | 5.007 | 480/667 | 667/667 |
| **corpusWordNgramWeight=0.50（选中）** | **234/667 35.1%** | **366/667 54.9%** | **4.994** | **479/667** | **667/667** |
| corpusWordNgramWeight=0.55 | 234/667 35.1% | 368/667 55.2% | 4.979 | 478/667 | 667/667 |
| corpusWordNgramWeight=0.60 | 236/667 35.4% | 368/667 55.2% | 4.973 | 476/667 | 667/667 |
| corpusWordNgramWeight=0.65 | 238/667 35.7% | 367/667 55.0% | 4.981 | 474/667 | 667/667 |
| corpusWordNgramWeight=0.70 | 239/667 35.8% | 364/667 54.6% | 4.994 | 474/667 | 667/667 |
| dictionaryWordNgramWeight=0.2 | 231/667 34.6% | 359/667 53.8% | 5.075 | 476/667 | 667/667 |
| dictionaryWordNgramWeight=0.6 | 235/667 35.2% | 360/667 54.0% | 5.034 | 475/667 | 667/667 |
| dictionaryWordNgramWeight=0.8 | 235/667 35.2% | 361/667 54.1% | 5.040 | 474/667 | 667/667 |
| sentenceCorpusWeight=0.05 | 235/667 35.2% | 362/667 54.3% | 5.031 | 477/667 | 667/667 |
| sentenceCorpusWeight=0.10 | 235/667 35.2% | 362/667 54.3% | 5.031 | 478/667 | 667/667 |
| sentenceCorpusWeight=0.20 | 233/667 34.9% | 362/667 54.3% | 5.036 | 479/667 | 667/667 |
| sentenceDictionaryWeight=0.05 | 235/667 35.2% | 363/667 54.4% | 5.025 | 478/667 | 667/667 |
| sentenceDictionaryWeight=0.10 | 235/667 35.2% | 363/667 54.4% | 5.024 | 478/667 | 667/667 |

完整 p50/p95 与逐项原始值在 `build/d05/ranking-ablation.txt`。选择结论：
- **corpusWordNgramWeight=0.50**：naturalOnly Top5 363→366（+3 条，+0.4pp），oracle 477→479（+2，不回退），rankCost 5.025→4.994；Top1 235→234（-1 条，0.1pp，不显著）。
- 0.55 的 Top5 更高（368）但 shorthand Top5 98→97（-1），且相对 0.50 没有额外 Top1，选择更保守的 0.50。
- 0.60/0.65/0.70 虽提高 Top1/Top5，但 oracle 476/474/474 低于 477，严格按“oracle 不回退”排除。
- wordInsertion/context/dictionary/sentence 变体没有同时满足 Top1/Top5 提升且 oracle 不回退；B/C 不选。

## 3. 选中机制与关闭对照

实现文件：`app/src/main/java/com/bitap/keyboard/core/PinyinEngine.kt`。只改一个排序权重：
- `EngineTuning.corpusWordNgramWeight` 默认仍由 `WORD_NGRAM_WEIGHT` 控制；
- `WORD_NGRAM_WEIGHT` 从 **0.35 改为 0.50**（只影响 `entryBaseScore` 中词内 corpus character-bigram 权重）；
- 词库、lattice beam/entries、候选上限/partial quota、early partial 排列、学习/上下文/展示策略全部不变。

关闭对照（build/d05/ranking-mechanism.txt）：off=`corpusWordNgramWeight=0.0`，baseline=0.35，after=0.50。

### 3.1 naturalRankable 667

| 变体 | Top1 | Top5 | rankCost | oracle64 | fullCoverage64 | p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|
| off（0.0） | 223/667 33.4% | 351/667 52.6% | 5.166 | 468/667 | 667/667 | 2.246 / 5.705 ms |
| baseline（0.35） | 235/667 35.2% | 363/667 54.4% | 5.025 | 477/667 | 667/667 | 2.311 / 5.607 ms |
| **after（0.50）** | **234/667 35.1%** | **366/667 54.9%** | **4.994** | **479/667** | **667/667** | **2.339 / 5.750 ms** |

关闭机制后 Top1 33.4%、Top5 52.6%、oracle 468，均低于 baseline/after；说明该权重确实参与排序（off 不是“无排序”，是关闭该单一特征）。

### 3.2 分类（naturalRankable + special）

| 类别 | n | baseline Top1/Top5 | after Top1/Top5 | off Top1/Top5 | after oracle |
|---|---:|---:|---:|---:|---:|
| natural_short | 300 | 143/200 | 142/200 | 135/196 | 251/300 |
| natural_medium | 277 | 88/149 | 89/152 | 85/142 | 206/277 |
| natural_long（≤64） | 90 | 4/14 | 3/14 | 3/13 | 22/90 |
| typo | 100 | 73/79 | 73/79 | 74/79 | 81/100 |
| shorthand | 100 | 76/98 | 79/97 | 78/98 | 99/100 |
| mixed_pinyin | 100 | 83/93 | 84/93 | 69/92 | 100/100 |

natural 类提升集中在 medium Top5（149→152）与 short 的 Top5 持平；natural_long 仅有效 90 条且 Top1 4→3、Top5 14→14（小样本，不作为方向）。special 中 typo/mixed_pinyin 不变或略好；shorthand Top5 98→97 属单条边界回退，Top1 76→79；v1 见下。若不接受 shorthand 单条 Top5 回退，应把 0.50 降为 0.45（但 natural Top5 无提升），或下一轮按输入长度分层；本报告不隐藏该权衡。

### 3.3 关闭消融的 failureClass（naturalRankable）

| 变体 | noRecall | pruningLoss | rankedLow | displayLoss |
|---|---:|---:|---:|---:|
| off（0.0） | 164 | 35 | 94 | 23 |
| baseline（0.35） | 163 | 27 | 95 | 19 |
| after（0.50） | 165 | 23 | 92 | 21 |

after 相对 baseline：pruningLoss 27→23（-4）、rankedLow 95→92（-3）、noRecall 163→165（+2）、displayLoss 19→21（+2）；净 Top5 未命中 304→301（-3）。说明收益主要来自候选池/排名转换，仍有小幅 noRecall/display 交换；不能声称全面机制改善。

### 3.4 v1 dev/holdout 回归

| 数据集 | n（去 mixed_en） | baseline Top1/Top5 | after Top1/Top5 | off Top1/Top5 | after oracle |
|---|---:|---:|---:|---:|---:|
| v1 dev | 119 | 93/100 | 93/102 | 85/100 | 108/119 |
| v1 holdout | 119 | 79/101 | 79/103 | 71/98 | 108/119 |

after 与 baseline 相比 Top1 不变、Top5 各 +2，oracle/fullCoverage 不变；off 明显劣于 baseline，说明权重不是无效；v1 回归无退化，且 holdout Top5 小幅改善。v1 只作回归，不用于调参。

## 4. 结果解释与限制

- 正确完整候选是否更容易选中：**是，但幅度有限**。naturalRankable Top5 从 363/667 到 366/667（+3 条，+0.4pp），rankCost 5.025→4.994，oracle 477→479；Top1 234 vs 235（-1 条），不能宣称 Top1 提升。没有设备端输入操作数据，不能宣称真实用户用得更快或更少修正。
- inputBudget：33 条 natural_long pinyin 65–70 超出 64 字符硬上限，已从排序分母排除；其中 fullCoverage 不计入 667。这个上限/预算问题应单独诊断，不能算排序失败。
- D05 dev 是跨来源诊断集，不是验收集；没有运行 v3；没有按 D05/v3 单句加规则，也没有改词库、AI、主题。
- 权重 0.50 对 special shorthand Top5 有 1 条边界回退（98→97），已如实记录；如果发布前要求 special 每个类别 Top5 严格不降，应改选 0.45（natural Top5 不提升）或做输入长度分层的后续实验，不要隐藏本结果。
- JVM p50/p95 为单次样本，受 JIT/负载影响；不是 Android 端到端。
- **真机补充（2026-11-08，见 `docs/D05_DEVICE_CHECK.md`）**：在 2 个 JVM old rank>5→new rank≤5 的 dev 案例上，ranking-old（0.35）到 final（0.50）的候选浏览操作分别减少 3 次和 2 次；仅 2 条 dev 案例，不能外推总体或验收。

## 5. 复现

```powershell
python eval/d05/dev/build_d05_dev.py
python eval/d05/dev/check_d05_dev.py

.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05RankingAblationTest" --console=plain
```

产物（均在忽略的 build/d05/）：

- `build/d05/ranking-ablation.txt`：候选权重消融（本文件 §2）。
- `build/d05/ranking-mechanism.txt`：baseline/after/off 的 naturalRankable、engineScoredRankable、分类、failureClass、v1 回归。
- `build/d05/dev-baseline.txt` / `dev-failures.tsv`：D05 诊断基线；D05_FAILURE_DIAGNOSIS.md 仍是旧 950 条历史基线，本文件使用 task-12 的 1,050 条 dev 与 63983c5f… hash。

固定 D05 dev SHA-256：`63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad`；词典 65,999 条未改。若 dev/词典变化，重跑测试并更新本文件，不得复制旧数字。
