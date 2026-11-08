# D05 单项机制改进：early partial 放到前五 full 之后

日期：2026-11-08（Asia/Shanghai）。前置诊断见 `docs/D05_FAILURE_DIAGNOSIS.md`（改变前基线：naturalOnly Top1 39.7% / Top5 59.0%，Top5 未命中 276，failureClass noRecall 112 / pruningLoss 37 / rankedLow 98 / displayLoss 29）。D05 dev 是允许逐条诊断的开发集，**不是验收集；本次没有运行 v3，也没有按 v3 结果选方案**。

## 1. 假设

D05 的 Top5 未命中中 rankedLow + displayLoss 占 127/276（46.0%），说明许多正确完整句已生成，只输在候选展示/排序。诊断发现：对长输入（有效输入 ≥24 字符），旧逻辑会把 1 个 partial pick 插在 3 个 full 候选之后（约 rank4/第 4 位），可能把一个正确 full 句挤出 Top5。假设：**保留 partial pick 的可见性，但把它放到 5 个 full 候选之后（rank6）**，Top5 全部留给 full 候选，同时 partial 仍在前 8 可见，从而改善 Top5/displayLoss 而不牺牲“先选前半句”的发现性。

## 2. 消融（D05 naturalOnly 600 条）

所有变体都通过 `PinyinEngine.EngineTuning` + `withTuning` 在 JVM 内运行；不改词库，不改评分。完整机器可读文件：`build/d05/ablation-tuning.txt`。

| 变体 | Top1 | Top5 | rankCost | oracle64 | fullCoverage | p50/p95 ms |
|---|---:|---:|---:|---:|---:|---:|
| baseline（旧：earlySlotCount=1, earlyAfterFull=3） | 238/600 39.7% | 354/600 59.0% | 4.650 | 471/600 | 600/600 | 2.821 / 6.571 |
| A partialQuota=0（另 earlySlotCount=0） | 238/600 39.7% | **364/600 60.7%** | **4.557** | **479/600** | 600/600 | 3.294 / 7.034 |
| A partialQuota=8 | 238/600 39.7% | 354/600 59.0% | 4.650 | 475/600 | 600/600 | 3.297 / 7.484 |
| A partialQuota=12 | 238/600 39.7% | 354/600 59.0% | 4.650 | 475/600 | 600/600 | 3.321 / 6.807 |
| A earlySlotCount=0 | 238/600 39.7% | **364/600 60.7%** | **4.557** | 471/600 | 600/600 | 3.302 / 7.286 |
| **A earlySlotCount=1 + earlyAfterFull=5（选中）** | **238/600 39.7%** | **364/600 60.7%** | **4.557** | **471/600** | **600/600** | **3.380 / 7.353** |
| B beam=96 | 238/600 39.7% | 354/600 59.0% | 4.650 | 472/600 | 600/600 | 4.408 / 10.213 |
| B beam=128 | 238/600 39.7% | 354/600 59.0% | 4.650 | 472/600 | 600/600 | 5.887 / 12.990 |
| B entriesPerKey=12 | 238/600 39.7% | 354/600 59.0% | 4.652 | 471/600 | 600/600 | 4.873 / 11.063 |
| B entriesPerKey=16 | 238/600 39.7% | 354/600 59.0% | 4.652 | 470/600 | 600/600 | 5.686 / 11.984 |
| C sentenceCorpusWeight=0.05 | 238/600 39.7% | 354/600 59.0% | 4.650 | 471/600 | 600/600 | 3.283 / 6.429 |
| C sentenceCorpusWeight=0.10 | 238/600 39.7% | 354/600 59.0% | 4.650 | 472/600 | 600/600 | 3.238 / 6.638 |
| C sentenceCorpusWeight=0.20 | 236/600 39.3% | 353/600 58.8% | 4.660 | 473/600 | 600/600 | 3.905 / 10.779 |
| C sentenceDictionaryWeight=0.05 | 238/600 39.7% | 355/600 59.2% | 4.643 | 472/600 | 600/600 | 3.674 / 7.937 |
| C sentenceDictionaryWeight=0.10 | 238/600 39.7% | 355/600 59.2% | 4.642 | 472/600 | 600/600 | 3.104 / 7.870 |

结论：

- B 的 beam/entries 扩大没有 Top5 收益，反而显著增加 p50/p95；不选。
- C 的 sentence 权重没有可重复的 Top1/Top5 收益（0.20 反而下降），不选。
- A partialQuota=0 也能把 Top5 提到 60.7%、oracle 到 479，但它实际上移除了 partial 候选配额（且必须同时关闭 early slot），会让“先选前半句”无法使用；存在明确 UX 回归，不选。
- A earlySlotCount=0 同样把 Top5 提到 60.7%，但 partial 会落到 48 个 full 候选之后，用户几乎无法在前 8 发现 partial；存在访问性回归，不选。
- **A earlySlotCount=1 + earlyAfterFull=5 与两个 early-slot 变体得到相同的 Top5 提升（+1.7pp）和 rankCost 改善（4.650→4.557），oracle 不回退（471→471）；同时保留 partial**。选择这一项。

## 3. 选中机制与实现点

实现文件：`app/src/main/java/com/bitap/keyboard/core/PinyinEngine.kt`。

- `EngineTuning` 新增 `partialEarlySlotAfterFull: Int = PARTIAL_EARLY_SLOT_AFTER_FULL`。
- 默认常量：`PARTIAL_EARLY_SLOT_COUNT = 1`，`PARTIAL_EARLY_SLOT_AFTER_FULL = 5`。
- `CandidateCollector.toCandidates` 的 early-partial 分支把 `earlyFull` 从硬编码 `min(3, fullBudget)` 改为 `min(partialEarlySlotAfterFull.coerceAtLeast(0), fullBudget)`。
- 候选排列（limit=8）变为：前 5 个 full → 1 个 early partial（第 6 位，rank6）→ 剩余 full → partial reserve。partialQuota 仍保持 16，partial reserve 不变；没有删除 partial，没有改词表/评分/搜索预算。
- 这是一项候选展示组织机制，不是多个权重混调；`partialEarlySlotAfterFull` 是新增的单一控制点，默认值 5 有消融证据。

### partial 可访问性验证

`reportEarlyPartialPlacement` 对 D05 natural_medium/natural_long 中有效输入 ≥24 字符的 289 条案例统计（文件：`build/d05/early-partial-placement.txt`）：

- 289/289 案例前五名全部是 full 候选；
- 289/289 案例第一个 partial 出现在 index=5（第 6 位，rank6），在引擎返回的前 8 项列表内；
- 因此 Top5 不被 partial 占位。**注意：前 8 项返回不等于屏幕首屏可见**；真机验证（`docs/D05_DEVICE_CHECK.md`）显示折叠行仅 2 chip、展开面板首屏 4 行，rank6 需要 1 次面板上滑或 1 次折叠行横滑，比 rank4 多 1 次滚动操作。

## 4. 选中后的 D05 指标（baseline vs after）

机器可读：`build/d05/ablation-mechanism.txt`。基线为旧 `partialEarlySlotCount=1, partialEarlySlotAfterFull=3`；after 为当前默认 `1,5`。所有数字为同一 D05 dev、同一 JVM 运行；延迟是单次参考值。

### 4.1 总体与 naturalOnly

| 指标 | engineScored before | engineScored after | naturalOnly before | naturalOnly after |
|---|---:|---:|---:|---:|
| n | 900 | 900 | 600 | 600 |
| Top1 | 470/900 52.2% | 470/900 52.2% | 238/600 39.7% | 238/600 39.7% |
| Top5 | 624/900 69.3% | **634/900 70.4%** | 354/600 59.0% | **364/600 60.7%** |
| rankCost | 3.763 | **3.701** | 4.650 | **4.557** |
| oracle64 | 751/900 83.4% | 751/900 83.4% | 471/600 78.5% | 471/600 78.5% |
| fullCoverage64 | 900/900 100% | 900/900 100% | 600/600 100% | 600/600 100% |
| p50 / p95 | 2.318 / 6.428 ms | 2.155 / 6.192 ms | 3.241 / 6.459 ms | 2.873 / 6.347 ms |

naturalOnly 的 Top1 不变、Top5 +1.7pp、rankCost -0.093、oracle 不回退（471/471）、fullCoverage 100%；目标达成。JVM p50/p95 单次有波动，不以本表宣称性能收益。

### 4.2 分类指标（after 与 before）

| 类别 | n | before Top1/Top5 | after Top1/Top5 | before oracle | after oracle | displayLoss before→after |
|---|---:|---:|---:|---:|---:|---:|
| natural_short | 300 | 143/196 | 143/**200** | 248 | 248 | 14→10 |
| natural_medium | 289 | 94/152 | 94/**157** | 215 | 215 | 11→6 |
| natural_long | 11 | 1/6 | 1/**7** | 8 | 8 | 2→1 |
| typo | 100 | 73/79 | 73/79 | 81 | 81 | 0→0 |
| shorthand | 100 | 76/98 | 76/98 | 99 | 99 | 1→1 |
| mixed_pinyin | 100 | 83/93 | 83/93 | 100 | 100 | 1→1 |

special 三类（typo/shorthand/mixed_pinyin）Top1/Top5/oracle 全部完全不变；所有提升来自 natural_short/medium/long 的 displayLoss 减少。

### 4.3 failureClass 变化

| failureClass | before | after | 变化 |
|---|---:|---:|---:|
| noRecall | 112 | 112 | 0 |
| pruningLoss | 37 | 37 | 0 |
| rankedLow | 98 | 98 | 0 |
| displayLoss | 29 | 19 | **-10** |
| Top5 未命中合计 | 276 | 266 | **-10** |

改进正好来自 displayLoss（partial early slot 不再挤占前五），noRecall/pruning/rankedLow 不变；这不是召回或排序总体改善，符合机制假设。

## 5. 回归检查

### 5.1 D05 special 类别

D05 typo/shorthand/mixed_pinyin 的 Top1/Top5/oracle/fullCoverage 全部不变（见 §4.2）；没有用专项模板样例调参。

### 5.2 v1 dev / holdout 回归

`build/d05/ablation-mechanism.txt` 的 v1 回归（排除 mixed_en 交互项，仅回归，不调参）：

| 数据集 | n | before Top1/Top5 | after Top1/Top5 | before/after oracle | before/after full |
|---|---:|---:|---:|---:|---:|
| v1 dev | 119 | 93/119 78.2% · 100/119 84.0% | 93/119 78.2% · 100/119 84.0% | 108 / 108 | 118 / 118 |
| v1 holdout | 119 | 79/119 66.4% · 101/119 84.9% | 79/119 66.4% · 101/119 84.9% | 108 / 108 | 118 / 118 |

v1 dev/holdout 的 Top1/Top5/oracle/fullCoverage 完全不变；v1 holdout rankCost 3.19→3.18，无回归。

### 5.3 相关长句/分段测试

- `PinyinEngineLongSegmentDiagnosticTest`、`PinyinEngineLongSegmentMechanismTest`、`KeyboardR05R06Test`、`MixedInputPathTest` 定向运行通过（BUILD SUCCESSFUL）。
- partial 可访问性统计见 §3；Top5 五名全部 full，partial 在 rank6 仍可见。

## 6. 为什么只选这一项

1. A earlyAfter5 是唯一同时满足“Top5 提升 + oracle 不回退 + partial 仍可见”的机制；A0 去掉 partial（UX 不可接受），A earlySlot0 虽 Top5 相同但 partial 不可发现，B/C 无 Top5 收益或延迟更差。
2. 只改一个候选展示控制点 `partialEarlySlotAfterFull=5`；不改词库、评测数据、AI、主题或其他评分权重。
3. 该机制的解释最直接：failureClass 的 displayLoss 精确减少 10，和 Top5 提升 10 条一致，noRecall/pruning/rankedLow 不变。

## 7. 未解决问题与限制

- natural_long 只有 11 条（Top1 1/11、Top5 7/11），长句结论仍不充分；需要后续独立长句或 v3 长句只读分析，不能按单条波动定方向。
- D05 是 dev 诊断集，所有数字不能写成验收通过或泛化达标；v3 未运行。
- 延迟是 JVM 单次样本，受 JIT/负载影响；不是 Android 端到端反馈，不能宣称 50ms 目标。
- partial 仍在第 6 位、不在 Top5；引擎返回前 8 项不代表屏幕首屏可见。真机实测折叠行仅 2 chip、展开面板首屏 4 行，rank6 比 rank4 多 1 次面板上滑/横滑（见 `docs/D05_DEVICE_CHECK.md`）。如果产品要求打开候选即见 partial，需要重新评估 `partialEarlySlotAfterFull` 或候选布局。
- failureClass 是启发式（latticeFullTexts 4,000 路径上限、collected 消费覆盖近似），不是因果分解。
- 本次未修改词库、v2/v3 数据、生产 AI/主题；未运行 v3 验收。

## 8. 复现

```powershell
python eval/d05/dev/build_d05_dev.py
python eval/d05/dev/check_d05_dev.py

.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05DevEvalTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05AblationTest" --console=plain
```

产物（均在忽略的 build/d05/）：

- `build/d05/ablation-tuning.txt`：A/B/C 消融表（本文件 §2 数字来源）。
- `build/d05/ablation-mechanism.txt`：baseline vs after 的 naturalOnly/分类/oracle/fullCoverage/p50/p95/failureClass/v1 回归。
- `build/d05/early-partial-placement.txt`：partial 在 8 项中的位置分布。
- `build/d05/dev-baseline.txt` / `dev-failures.tsv`：D05 诊断基线；注意 `D05_FAILURE_DIAGNOSIS.md` 记录的是本次机制改变前的历史基线，after 见本文件。

固定 D05 dev SHA-256：`1d628eb46783ffe337dcbe5887b4126f58ba66b638f7d58159b58d235d74b9b4`；词典 65,999 条。若 dev 或词典变化，重跑全部测试并更新本文件，不得把旧数字复制到新数据。
