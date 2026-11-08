# D05 失败诊断工具与可复现基线

日期：2026-11-08（Asia/Shanghai）。数据：D05 跨来源诊断 dev（task-9 / d05-v1），950 条；基线工具：`app/src/test/java/com/bitap/keyboard/core/PinyinD05DevEvalTest.kt`。**D05 是允许逐条诊断的开发集，不是验收集；v3 validation 仍是唯一独立验收，本次没有运行 v3。**

## 1. 数据与方法

- 数据文件：`eval/d05/dev/dev.tsv`，950 行、6 列（id/category/input/expected/note/source），SHA-256 `1d628eb46783ffe337dcbe5887b4126f58ba66b638f7d58159b58d235d74b9b4`。
- 类别：natural_short 300、natural_medium 289、natural_long 11、typo 100、shorthand 100、mixed_pinyin 100、mixed_en 50。自然句合计 600；natural_long 只有 11 条（源数据长句不足），长句结论必须按小样本看待。
- 引擎：`PinyinEngine.fromLines(app/src/main/assets/pinyin_dict.tsv)`，词典 65,999 条；关闭学习；JVM 单进程。
- 指标口径沿用 v3 协议：Top1/Top5 取 `search(input, limit=8)`；rankCost 前五命中 rank+1、未进前五记 9；fullCoverage/oracle 取 `search(input, limit=64)`；Rime 不参与本测试；延迟为 JVM `search(limit=8)`，不是 Android 端到端延迟。
- 引擎可评分：mixed_en 50 条是交互项，单独统计 prefix Top1/Top5，不计入 900 条引擎可评分分母。
- 输出：`build/d05/dev-baseline.txt`（总/分类指标、naturalOnly、interaction、failureClasses）与 `build/d05/dev-failures.tsv`（仅引擎可评分的 Top5 未命中案例）。

复现：

```powershell
python eval/d05/dev/build_d05_dev.py
python eval/d05/dev/check_d05_dev.py

.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05DevEvalTest" --console=plain
```

## 2. 总体指标（900 条引擎可评分）

| 指标 | 结果 |
|---|---:|
| n | 900 |
| Top1 | 470/900 = 52.2% [49.0%, 55.5%] |
| Top5 | 624/900 = 69.3% [66.2%, 72.3%] |
| rankCost 平均 / 总和 | 3.76 / 3387 |
| fullCoverage | 900/900 = 100.0% |
| oracle（limit=64） | 751/900 = 83.4% |
| Top5 错误率 | 30.7% |

**不要用 52.2%/69.3% 与 v3 的 38.8%/59.4% 直接比较**：D05 包含 typo/shorthand/mixed_pinyin 三个模板专项类别，类别构成与 v3 只含自然句不同。供对照的自然句子集（600 条）为 Top1 238/600 = 39.7%、Top5 354/600 = 59.0%、rankCost 4.65、oracle 471/600 = 78.5%、fullCoverage 100%；该自然句子集与 v3 的 38.8%/59.4%/4.61/77.0% 接近，但来源和句子分布不同，只能作为跨来源诊断信号，不能当作独立验收或因果证据。

一次 JVM 运行的延迟（`build/d05/dev-baseline.txt` 为准）：p50=2.732 ms、p95=7.531 ms、p99=9.527 ms、max=16.364 ms。仅供同机参考。

## 3. 分类指标

| 类别 | n | Top1 | Top5 | rankCost | oracle64 | fullCoverage64 |
|---|---:|---:|---:|---:|---:|---:|
| natural_short | 300 | 143/300 = 47.7% | 196/300 = 65.3% | 4.13 | 248/300 = 82.7% | 300/300 |
| natural_medium | 289 | 94/289 = 32.5% | 152/289 = 52.6% | 5.15 | 215/289 = 74.4% | 289/289 |
| natural_long | 11 | 1/11 = 9.1% | 6/11 = 54.5% | 5.64 | 8/11 = 72.7% | 11/11 |
| typo | 100 | 73/100 = 73.0% | 79/100 = 79.0% | 2.75 | 81/100 = 81.0% | 100/100 |
| shorthand | 100 | 76/100 = 76.0% | 98/100 = 98.0% | 1.52 | 99/100 = 99.0% | 100/100 |
| mixed_pinyin | 100 | 83/100 = 83.0% | 93/100 = 93.0% | 1.70 | 100/100 = 100.0% | 100/100 |
| **naturalOnly** | **600** | **238/600 = 39.7%** | **354/600 = 59.0%** | **4.65** | **471/600 = 78.5%** | **600/600** |
| mixed_en（交互，不计入引擎分母） | 50 | prefixTop1 0/50 = 0.0% | prefixTop5 16/50 = 32.0% | — | — | — |

说明：mixed_en 的 prefix 统计只看中文前缀是否正确；英文尾的提交/切换属于 D07 交互链路，不能由本引擎评分代替。natural_long 仅 11 条，Top1 1/11 只能作为线索。

## 4. failureClass 定义与分布

失败分类基于 `PinyinEngine.debugSearch` 的三个诊断池：`finalCandidates`（最终 64 项）、`collected`（收集器快照，含部分消费候选）、`latticeFullTexts`（裁剪前的完整句 lattice 路径）。所有匹配都先做与评分一致的归一化（去标点，保留 CJK/字母/数字）；`collectedHasExpected` 还要求候选 `consumed >= requiredCoverage`，避免把前缀候选误算成完整答案。**failureClass 是启发式线索，不是因果证明。**

- **noRecall**：期望文本既不在 `latticeFullTexts`，也不在 `collected` 的完整覆盖候选中。
- **pruningLoss**：期望文本在 `latticeFullTexts` 或 `collected` 中，但不在 `finalCandidates`（候选上限、部分配额、去重/beam 下游裁剪）。
- **rankedLow**：期望候选在 `finalCandidates` 中但最终 rank >= 8。
- **displayLoss**：期望候选在 `finalCandidates` 中且 rank 为 5..7（生成了，但超出前五；不是 Top5 命中）。

在 900 条引擎可评分案例中，Top5 未命中 276 条：**noRecall 112（40.6%）、pruningLoss 37（13.4%）、rankedLow 98（35.5%）、displayLoss 29（10.5%）**。

按类别拆分：

| 类别 | noRecall | pruningLoss | rankedLow | displayLoss | Top5 失败合计 |
|---|---:|---:|---:|---:|---:|
| natural_short | 35 | 17 | 38 | 14 | 104 |
| natural_medium | 64 | 10 | 52 | 11 | 137 |
| natural_long | 3 | 0 | 0 | 2 | 5 |
| typo | 9 | 10 | 2 | 0 | 21 |
| shorthand | 1 | 0 | 0 | 1 | 2 |
| mixed_pinyin | 0 | 0 | 6 | 1 | 7 |
| **合计** | **112** | **37** | **98** | **29** | **276** |

`build/d05/dev-failures.tsv` 列：`id/category/input/expected/rank/candidateTop8/failureClass/latticeHasExpected/collectedHasExpected`。其中 `rank` 是 `finalCandidates` 的最终排名（-1 表示不在 64 项池），`candidateTop8` 是实际 `search(limit=8)` 的候选文本。该文件只收录 Top5 未命中案例，不收录成功案例或 mixed_en 交互项。

## 5. 每类典型 case（D05 dev 允许逐条查看）

| failureClass | 案例 | 输入/期望 | 现象 |
|---|---|---|---|
| noRecall | `d05-natural_short-002` | `didihejiejiedezhanzheng` → 底迪和姐姐的战争 | 前八是“弟弟和姐姐的战争”等同音/近形；lattice/collected 均无期望（词典/生成路径缺“底迪”或整句） |
| noRecall | `d05-natural_short-006` | `raoqumaiyurongyi` → 绕去买羽绒衣 | 前八是“绕去买于容易”等；lattice/collected 均无期望 |
| pruningLoss | `d05-natural_short-001` | `renzixiaozhoumochuangbanren` → 人资小周末创办人 | lattice/collected 均含期望（collectedHasExpected=true），但 final64 不含；候选池/配额裁剪丢失 |
| pruningLoss | `d05-natural_short-024` | `fapiaocunzaiju` → 发票存载具 | lattice 含期望、collected 的完整覆盖不含，final64 无；属候选组织/去重裁剪 |
| rankedLow | `d05-natural_short-016` | `kongqixiangdongjieyiyang` → 空气像冻结一样 | 期望在 final64 中但 rank=9；前八是“空气向东解一样”等近似候选 |
| rankedLow | `d05-natural_short-034` | `zhijiedajiuduile` → 直接搭就对了 | 期望在 final64 中但 rank=18；排序/语言模型问题 |
| displayLoss | `d05-natural_short-031` | `jintianwodijiaowozhuce` → 今天我弟教我注册 | 期望在 final64 中 rank=6；前五已有近似候选，属候选展示/前五排序 |
| displayLoss | `d05-natural_short-050` | `zheshitaiwandefuqi` → 这是台湾的福气 | 期望在 final64 中 rank=5；Top5 边界案例 |

更多案例见 `build/d05/dev-failures.tsv`（仅 build 产物，不提交）。

## 6. 与 v2/v3 的差异

- **v2 dev（同源探索）**：历史基线约 Top1 51.6% / Top5 72.3% / rankCost 3.58（D04 修正前口径），自然句来自 Tatoeba，与生产字符 n-gram 统计同源；不能作为跨来源泛化证据。
- **v3 validation（独立验收）**：601 条自然句，Top1 233/601 = 38.8%、Top5 357/601 = 59.4%、rankCost 4.61、oracle 77.0%、fullCoverage 100%。v3 只允许聚合验收，不允许逐条诊断；本次**没有运行 v3**，也没有按 v3 结果选方案。
- **D05 dev（本工具）**：自然句 600 条，Top1 39.7% / Top5 59.0% / rankCost 4.65 / oracle 78.5%，与 v3 自然句基线接近，而明显低于同源 v2 dev；这与“跨来源自然句更难、同源数字高估”的风险判断方向一致，但 D05 与 v3 来源/句长分布/筛选不同，不能把两者差值当作训练重合的因果消融。
- **D05 总体 52.2%/69.3% 高于 v3**，原因是 D05 加入 typo/shorthand/mixed_pinyin 模板专项，这些类别 Top1 较高且不代表真实输入分布；报告必须始终分开“naturalOnly”和“special categories”。D05 的用途是定位失败机制，不是发布验收。

## 7. 下一步候选机制清单（按证据排序，不直接改生产）

1. **排序/语言模型（最大块）**：rankedLow 98 + displayLoss 29 = 127/276（46.0%）的 Top5 未命中已在 finalCandidates 中，只是排名不进入前五。优先做重排/上下文/字符 n-gram 权重实验；典型如“空气像冻结一样”“直接搭就对了”。先用 D05 dev 定量，不改词表。
2. **召回与 lattice 路径生成**：noRecall 112/276（40.6%）在 latticeFullTexts 与 collected 都没有完整覆盖期望。用词典做启发式最长匹配发现其中 103/112 的期望字面可被词典词拼出，说明不能简单归因于缺词，应优先诊断 beam/entries 预算、分段和简拼/混拼派生；其余约 9 条存在真实缺词或非词表组合，留作词库/构造诊断，不按 D05 句子改生产词库。
3. **候选池/裁剪组织**：pruningLoss 37/276（13.4%）在 lattice 或 collected 中已有期望却被 finalCandidates 裁掉；检查候选上限、partial quota、去重与 beam 下游裁剪，尤其 natural_short（17）与 natural_medium（10）。
4. **专项类别分别处理**：typo 21 条 Top5 未命中（noRecall 9、pruningLoss 10、rankedLow 2），错拼纠正仍是独立召回通道；shorthand 仅 2 条未命中，mixed_pinyin 7 条中 6 条 rankedLow，先做排序实验；mixed_en prefixTop1 0/50、prefixTop5 16/50，属于 D07 中英混输交互，不应通过改中文候选质量掩盖。
5. **natural_long 仅 11 条（Top1 1/11、Top5 6/11）**：样本不足以定方向；后续需要补独立长句或结合 v3 长句另行分析，不能根据单条变化宣布长句改善。

以上都是诊断线索；不直接修改生产代码或词库。D05 结果不能作为 v3 验收通过依据。

## 8. 限制

1. D05 来源是 CC0 多来源（聊天、政府稿、教材/歌词等）的 zh-TW→简体文本，仍有地区用词与语体差异；不是用户真实输入分布。
2. natural_long 只有 11 条；专项类别为模板构造，不代表真实输入比例。
3. failureClass 是启发式：latticeFullTexts 有 4,000 路径诊断上限；分段/带分隔符输入可能无 lattice 诊断路径；`collected` 对前缀的消费覆盖按 text/consumed 近似；不能把分类比例当成严格因果分解。
4. 延迟为 JVM `search(limit=8)` 的单次样本，受 JIT/机器负载影响，不是 Android 端到端 p50/p95。
5. mixed_en 只统计 prefix，不评价英文尾提交/切换；交互链路属于 D07。
6. 本次未运行 v3、未修改生产代码/词库，D05 只能用于 dev 诊断和后续实现迭代。

## 9. 输出与复现

- `build/d05/dev-baseline.txt`：总/分类/naturalOnly/interaction/latency/failureClasses。
- `build/d05/dev-failures.tsv`：276 条 Top5 未命中（本次运行）。
- `build/d05` 为忽略的构建产物，不提交；本文件与测试源 `app/src/test/java/com/bitap/keyboard/core/PinyinD05DevEvalTest.kt` 为版本库交付。

固定数据哈希：`1d628eb46783ffe337dcbe5887b4126f58ba66b638f7d58159b58d235d74b9b4`；词典条目 65,999。若 D05 dev 文件或词典变化，重跑测试并更新本报告，不得把旧结果复制到新数据。
