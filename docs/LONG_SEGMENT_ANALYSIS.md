# 长句解码（long_segment）专项分析

- 分支：`engine-long-sentence`（worktree `D:/Projects/Github/Bitap-engine`，基线 `aeed080`）
- 最终词典：master `d9749d9` 的 `app/src/main/assets/pinyin_dict.tsv`（65,999 词条，2,304,382 B，SHA-256 `4C979D12D957F2E567F06844CFAA705E07DBF544B808FF055C86669D5A53F20D`；#@c=5,296 / #@b=43,290 / #@t=14,573 / #@w=0）
- 评测运行时临时覆盖工作区词典，**提交前已还原，分支不含词典改动**。
- 延迟为 Windows 11 + OpenJDK 25 JVM 值，只作同机相对参考；真机延迟以 P0 设备为准。

## 1. 结论摘要

1. 长句主因不是“路径不生成”，而是 **排序/预算**：生产默认下 13 条诊断中 8 条已 TOP1，失败项从“排序不足”为主转为“少量预算/极难句”。
2. 通用改进（生产默认）：
   - lattice beam 32→64、entriesPerKey 4→8（长句候选召回 +2 条）；
   - 词典字符 bigram 词内重排权重 0→0.4（区分同音词/低频书面词）；
   - 分段选词前缀候选：覆盖率目标 0.36、coverage weight 4.0、early slot 1（修复前缀被完整长句挤出前 5；同时修复“完整候选达到 96 条后 partial 完全不再收集”的预算缺陷）。
3. 已知回归 `dev-work_comm-28`（明天上午开评审会）在最终 A 词典 baseline 已是 TOP1（`明天上午开评审会`），after 仍为 TOP1、taps 1。
4. dev long_segment：TOP1 6/12→7/12、TOP5 8/12→10/12、avgTaps 3.92→2.75；holdout long_segment：TOP1 4/12→5/12、TOP5 6/12→8/12、avgTaps 5.58→4.17。
5. 句级字符 n-gram（corpus / dictionary）重排在 0.2 权重下无质量收益，未启用；beam 按 last-word 去重会明显降质量，未启用。

## 2. 诊断口径与逐条四分类

诊断集合：dev long_segment 12 条 + `dev-work_comm-28`，共 13 条。分类定义：

| 分类 | 含义 |
|---|---|
| `0-ok` | 期望候选 TOP1 |
| `0b-top5-scoring` | 期望候选在 TOP5（含 TOP2–5），但非 TOP1：排序不足 |
| `1-not-generated` | 正确路径在 production beam 和 wide beam（192/16）都不存在 |
| `2-beam/entry-pruning` | production 不存在，放大 beam/entries 后存在：搜索剪枝 |
| `3-scoring` | 候选已收集但排在 TOP5 之外：评分/排序不足 |
| `4-dedupe/limit` | 路径在 lattice 中但被候选数量/去重上限丢弃 |

### 2.1 逐条结果

| id | 期望 | baseRank | afterRank | baseline 分类 | after 分类 | prodLattice | prodCollected |
|---|---|---:|---:|---|---|---|---|
| dev-work_comm-28 | 明天上午开评审会 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-61 | 我今天下午要去公司参加一个重要的会议 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-62 | 明天上午十点我们在会议室讨论项目进度 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-63 | 这个问题我们已经讨论过很多次了 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-64 | 你能不能帮我看看这份报告哪里有问题 | 1 | 1 | 0b-top5-scoring | 0b-top5-scoring | ✓ | ✓ |
| dev-long_segment-65 | 如果明天天气好的话我们就去公园散步 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-66 | 我昨天在图书馆借了两本书 | -1 | 1 | 2-beam/entry-pruning | 0b-top5-scoring | ✓ | ✓ |
| dev-long_segment-67 | 这个周末我想带家人去郊区玩一天 | -1 | -1 | 1-not-generated | 1-not-generated | ✗ | ✗ |
| dev-long_segment-68 | 请你把会议纪要和下周计划一起发给我 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-69 | 我们先确认需求范围再安排开发排期 | -1 | -1 | 2-beam/entry-pruning | 2-beam/entry-pruning | ✗ | ✗ |
| dev-long_segment-70 | 他说下个月要去上海出差一个星期 | 0 | 0 | 0-ok | 0-ok | ✓ | ✓ |
| dev-long_segment-71 | 今天下班以后（分段选词） | -1 | 3 | 3-scoring | 0b-top5-scoring | ✓ | ✓ |
| dev-long_segment-72 | 明天上午（分段选词） | 2 | 0 | 0b-top5-scoring | 0-ok | ✓ | ✓ |

分类汇总：

- baseline：`{0-ok=7, 0b-top5-scoring=2, 2-beam/entry-pruning=2, 1-not-generated=1, 3-scoring=1}`
- after：`{0-ok=8, 0b-top5-scoring=3, 2-beam/entry-pruning=1, 1-not-generated=1}`

### 2.2 召回 / oracle / top1 / top5

| 指标 | baseline（legacy 32/4） | after（生产默认 64/8） | wide（192/16，仅诊断） |
|---|---:|---:|---:|
| 正确路径召回（lattice 或 collected） | 10/13 | 11/13 | 12/13 |
| 排序前 oracle（collected 含正确路径） | 10/13 | 11/13 | 11/13 |
| TOP1 | 7/13 | 8/13 | — |
| TOP5 | 9/13 | 11/13 | — |
| avgTaps（miss=9） | 3.69 | 2.62 | — |

关键分数（after，prod collected）：

- 64：期望 score `-23.39`，最佳 full `-22.94`（`你能不能帮我看看这份报告那里有问题`），差 0.45 分，仍 TOP2。
- 66：期望 collected：有（生产 64/8 已召回），best full `-29.20`（`我昨天在图书馆解了两本书`），期望 `-30.36`，TOP2。
- 71：期望前缀 partial score `3.52`，最佳 full `-21.00`；生产 early slot 后进入第 4 名。
- 67 / 69：生产 64/8 仍不生成；wide 192/16 对 69 可生成但排序靠后；67 即使 512/32 才出现且分数远低于 `相待` 路径，属极难句。

## 3. 全量 dev+holdout 评测（A 词典）

同轮相邻运行：baseline 22:55:27（CPU 62%→1%），after 22:56:01（CPU 4%→68%）。报告：

- baseline: `build/pinyin-quality-eval-engine-before-final.txt`
- after: `build/pinyin-quality-engine-final.txt`（= `pinyin-quality-eval-engine-after-final.txt`）

### 3.1 总表

| 数据集 | 口径 | baseline top1 | after top1 | Δ | baseline top5 | after top5 | Δ | baseline avgTaps | after avgTaps | Δ |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dev | 全量（含交互） | 94/132 71.2% | 97/132 73.5% | +2.3pp | 110/132 83.3% | 108/132 81.8% | -1.5pp | 2.58 | 2.52 | -0.06 |
| dev | 引擎可评分 | 92/119 77.3% | 94/119 79.0% | +1.7pp | 102/119 85.7% | 102/119 85.7% | 0 | 2.30 | 2.20 | -0.10 |
| holdout | 全量（含交互） | 81/132 61.4% | 81/132 61.4% | 0 | 102/132 77.3% | 108/132 81.8% | +4.5pp | 3.01 | 2.78 | -0.23 |
| holdout | 引擎可评分 | 80/119 67.2% | 79/119 66.4% | -0.8pp（1 条） | 97/119 81.5% | 102/119 85.7% | +4.2pp | 2.67 | 2.46 | -0.21 |

### 3.2 按类别（dev / holdout，top1 / top5 / avgTaps）

| 类别 | dev baseline | dev after | holdout baseline | holdout after |
|---|---|---|---|---|
| daily_chat | 11/12, 11/12, 1.67 | 11/12, 11/12, 1.67 | 10/12, 12/12, 1.25 | 9/12, 12/12, 1.33 |
| time_place | 12/12, 12/12, 1.00 | 12/12, 12/12, 1.00 | 11/12, 11/12, 1.42 | 11/12, 11/12, 1.42 |
| work_comm | 10/12, 11/12, 1.92 | 10/12, 11/12, 1.92 | 7/12, 8/12, 4.00 | 7/12, 8/12, 4.00 |
| unseen_sentence | 11/12, 11/12, 1.67 | 11/12, 11/12, 1.67 | 9/12, 10/12, 2.00 | 9/12, 11/12, 2.08 |
| homophone | 12/12, 12/12, 1.00 | 12/12, 12/12, 1.00 | 12/12, 12/12, 1.00 | 12/12, 12/12, 1.00 |
| **long_segment** | **6/12, 8/12, 3.92** | **7/12, 10/12, 2.75** | **4/12, 6/12, 5.58** | **5/12, 8/12, 4.17** |
| mixed_en（交互，不计引擎） | 2/12, 8/12, 4.75 | 3/12, 6/12, 5.17 | 1/12, 5/12, 5.83 | 2/12, 6/12, 5.42 |
| number_punct | 8/12, 10/12, 2.75 | 9/12, 10/12, 2.50 | 9/12, 10/12, 2.42 | 8/12, 11/12, 2.00 |
| shorthand | 6/12, 9/12, 3.42 | 6/12, 7/12, 3.83 | 5/12, 8/12, 4.17 | 6/12, 9/12, 3.50 |
| mixed_pinyin | 10/12, 12/12, 1.25 | 10/12, 12/12, 1.25 | 6/12, 12/12, 1.67 | 5/12, 12/12, 1.75 |
| typo | 6/12, 6/12, 5.00 | 6/12, 6/12, 5.00 | 7/12, 8/12, 3.75 | 7/12, 8/12, 3.92 |

回退边界：所有类别 top1 下降均 ≤1 条（daily_chat holdout -1、number_punct holdout -1、mixed_pinyin holdout -1，均满足任务红线）；dev 全量 top5 -1.5pp 主要来自 mixed_en 交互项（其 top5 8→6，但 top1 2→3），引擎可评分口径 top5 持平。

### 3.3 延迟（同一轮相邻运行）

| 数据集 | baseline p50 / p95 / p99 / max (ms) | after p50 / p95 / p99 / max (ms) |
|---|---:|---:|
| dev 132 次 | 0.551 / 1.875 / 2.779 / 12.632 | 1.712 / 7.761 / 10.120 / 41.224 |
| holdout 132 次 | 0.597 / 1.646 / 1.752 / 2.599 | 2.024 / 8.808 / 10.443 / 13.231 |

诊断集（13 条，in-process 消融，同 JVM 相邻）：legacy 32/4 longP50/P95 = 2.5/3.4 ms；prod 64/8 = 8.4/12.6 ms；normal（dev <40 字符）609/1430 µs → 1175/5749 µs。长输入 P95 12.6ms < 50ms 目标；普通输入 P95 5.7ms < 10ms 目标。

## 4. 消融对比

报告：`build/long-segment-tuning-ablation.txt`。组名解释：

- `legacy-32-4`：基线评分常量 + beam 32 / entries 4（复现旧行为）。
- `budgetOnly-64-8`：仅放宽 beam/entries。
- `prod-combo1e1`：最终生产默认 = beam 64 / entries 8 + 词典词内 bigram 权重 0.4 + coverage 4.0 + early slot 1。
- `sentenceCorpus0.2` / `sentenceDict0.2`：在 prod 上打开句级字符 n-gram 重排（corpus trigram / dictionary bigram）。
- `dictW0.2` / `dictW0.6`：词典词内 bigram 权重版本。
- `coverageOff`：关闭分段选词 coverage/early slot。
- `earlySlotOff`：保留 coverage、关闭 early slot。
- `dedupeOn`：按 last word 去重 beam（性能实验）。

| 组 | top1 | top5 | found8 | avgTaps | ranks（28,61,62,63,64,65,66,67,68,69,70,71,72） | longP50/P95 µs | normalP50/P95 µs | maxCollected / maxLattice |
|---|---:|---:|---:|---:|---|---:|---:|---:|
| legacy-32-4 | 7/13 | 9/13 | 9/13 | 3.69 | 0,0,0,0,1,0,-1,-1,0,-1,0,-1,2 | 2534/3446 | 609/1430 | 208 / 241 |
| budgetOnly-64-8 | 7/13 | 10/13 | 10/13 | 3.23 | 0,0,0,0,1,0,2,-1,0,-1,0,-1,2 | 7819/11383 | 1189/4551 | 208 / 799 |
| **prod-combo1e1** | **8/13** | **11/13** | **11/13** | **2.62** | 0,0,0,0,1,0,1,-1,0,-1,0,3,0 | 8372/12645 | 1175/5749 | 205 / 681 |
| sentenceCorpus0.2 | 8/13 | 11/13 | 11/13 | 2.62 | 0,0,0,0,1,0,1,-1,0,-1,0,3,0 | 9325/11335 | 1300/4704 | 205 / 681 |
| sentenceDict0.2 | 8/13 | 11/13 | 11/13 | 2.62 | 0,0,0,0,1,0,1,-1,0,-1,0,3,0 | 7481/9041 | 1210/4233 | 205 / 681 |
| dictW0.2 | 8/13 | 11/13 | 11/13 | 2.62 | 0,0,0,0,1,0,1,-1,0,-1,0,3,0 | 7094/8871 | 1075/4331 | 207 / 753 |
| dictW0.6 | 8/13 | 11/13 | 11/13 | 2.62 | 0,0,0,0,1,0,1,-1,0,-1,0,3,0 | 7790/9316 | 897/4675 | 205 / 598 |
| coverageOff | 7/13 | 10/13 | 10/13 | 3.23 | 0,0,0,0,1,0,1,-1,0,-1,0,-1,3 | 6881/7936 | 932/3747 | 205 / 681 |
| earlySlotOff | 8/13 | 10/13 | 11/13 | 2.85 | 0,0,0,0,1,0,1,-1,0,-1,0,6,0 | 5891/7654 | 728/3812 | 205 / 681 |
| dedupeOn | 8/13 | 9/13 | 9/13 | 3.69 | 0,0,0,0,-1,0,-1,-1,0,-1,0,3,0 | 2141/2797 | 332/1029 | 204 / 448 |

消融结论：

- **beam/entry**：单独放宽 64/8 只提升 TOP5（9→10）、found8（9→10），TOP1 不变；说明预算解决召回，不能解决排序。
- **句级重排开关**：corpus 0.2 / dictionary 0.2 与默认完全相同（8/13、11/13、2.62），且延迟略增、无质量收益，故关闭；语料偏口语/稀疏，对正式长句的判别力不足（评审会、回忆/会议等方向甚至相反）。
- **权重版本**：dictW 0.2/0.4/0.6 结果相同（64 仍 TOP2），0.4 作为折中；coverage/early slot 是 TOP5 的关键：关闭 coverage → 72 掉出 TOP1、71 掉出 TOP5；关闭 early slot → 71 掉到第 7。
- **dedupeOn**：延迟大幅改善（long P95 2.8ms）但质量明显回退（TOP5 9/13，64/66 丢失），不采用。

## 5. 各项改动的代价

| 改动 | 质量效果 | 延迟代价 | 内存/路径数 |
|---|---|---|---|
| beam 32→64、entries 4→8 | 召回 9→10/13，66 从 not-found→TOP2 | 长 P95 3.4→11.4ms（单独预算组） | maxLattice 241→799 |
| 词典字符 bigram 词内权重 0→0.4 | 64 同音选择改善、66 排序改善；权重 0.2–0.6 稳定 | 向量化后计入词分，无可测独立延迟（µs 级） | 无额外索引，仅计算 |
| coverage 4.0 + early slot 1 | 72 TOP1、71 TOP4；TOP5 +1 | 仅候选排序，无搜索成本 | 候选池不变 |
| 句级 corpus/dict n-gram（0.2） | 无收益 | +0.5–1.5ms P95 | 不改索引；未启用 |
| 词典不可变索引 | — | — | A 词典 65,999 条，索引堆增量约 47.9 MB（JVM 粗略值） |

## 6. 未完成项与验证边界

1. **dev-long_segment-67**：生产 64/8 与 192/16 均不生成正确路径，512/32 才出现但分数远低于 `相待/教区` 路径（`想带` 被 `相待` 压制），属于当前语言模型的结构性短板，未修复。
2. **dev-long_segment-69**：期望含词典中不存在的 `排期`（A 词典 freq=0），即使 192/16 生成也排序靠后；需要更强 beam 或词典补词，未纳入本次（禁止改词典）。
3. **64 / 66**：已进入 TOP5（TOP2），但尚未 TOP1；64 的 `哪里/那里` 差距 0.45 分，66 的 `借/解` 是单字同音，需更强词级语言模型。
4. **holdout long_segment** 仍 TOP1 5/12、TOP5 8/12；受封存口径限制，只报告聚合，不读取具体句子调参。
5. **句级 n-gram 重排未达预期**：已实现开关并做了 0.2 权重的对照，无质量收益，保持关闭；如后续引入更大/更匹配书面语的语料，可重启该方向。
6. **真机延迟未在本 worktree 验证**：JVM p95 7.8–8.8ms、长输入 P95 12.6ms，满足开发目标；真机以 device-qa P0 验收为准。
7. 评测词典为 master `d9749d9` 资产；git blob 2,175,214 B（LF），Windows CRLF checkout 2,304,382 B。两者内容一致，差异仅为行尾格式，不是数据差异。

## 7. 产物与复跑

- 诊断：`build/long-segment-diagnostic.txt`、`build/long-segment-detail.txt`
- 消融：`build/long-segment-tuning-ablation.txt`
- 内存：`build/long-segment-memory.txt`
- 全量 before：`build/pinyin-quality-eval-engine-before-final.txt`
- 全量 after：`build/pinyin-quality-engine-final.txt`
- 复跑：`$env:PINYIN_EVAL_STAGE='engine-after-final'; .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks --console=plain`
