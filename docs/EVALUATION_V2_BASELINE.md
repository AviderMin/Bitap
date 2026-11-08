# 评测 v2 首轮基线（D01）

- 日期：2026-11-08
- 状态：**首轮基线（未针对 v2 调参）**；用于后续 D04–D07 的同口径对照
- 基线 commit：54bc9f33e99fe670b4bab2e39400b7b816fc4676（D00 记录基线；工作区同时存在其他成员的 D01/D02/D03 未提交改动，PinyinEngine.kt 与 pinyin_dict.tsv 本轮未改动）
- 环境：Windows 11 / OpenJDK 25.0.4.1（Microsoft build）/ Gradle 9.7.1 / Kotlin 2.4.0 / Python 3.14.7 / pypinyin 0.55.0
- 词典：app/src/main/assets/pinyin_dict.tsv，条目 65,999；工作区文件 SHA-256（Windows checkout）4C979D12D957F2E567F06844CFAA705E07DBF544B808FF055C86669D5A53F20D
- 评测器：app/src/test/java/com/bitap/keyboard/core/PinyinV2EvalTest.kt
- 协议：docs/EVALUATION_PROTOCOL_V2.md

## 1. 语料与可重复性

| 项 | dev | validation |
|---|---|---|
| 行数 | 1,430 | 710 |
| 文件 | eval/v2/corpus/pinyin_cases_v2_dev.tsv | eval/v2/corpus/pinyin_cases_v2_validation.tsv |
| SHA-256 | 3421ca0837d3b1726532c186810deab759a42fcdf622f4157c6f76216bbe53d6 | 9097f226f1709d8b5e20d0faf4a661419d46d051e04271e5d302b0ce7f072994 |
| 自然句 / 专项 | 1,150 / 280 | 570 / 140 |

- 源数据：.tmp/tatoeba/cmn_sentences.tsv，89,289 行，SHA-256 a11463da289d918050e441a003848a795a97dcc47ffd1b9ed5345d1d70e0c84f，Tatoeba CC BY 2.0 FR。
- manifest：eval/v2/corpus/manifest.json（类别计数、源 hash、生成命令、切分参数）。
- 复跑生成两次，dev/validation 四个 hash 前后完全一致（REPRODUCIBLE=YES），确认“同输入同 hash”。
- 校验器结果：python eval/v2/check_v2_corpus.py → OK。关键输出：
  - dictionary_whole_sentence_check: natural_rows=1720 hits=0
  - near_duplicate_isolation: dev_natural=1150 validation_natural=570 cross_pairs_above_threshold=0
  - source_traceability: checked=1720 mismatches=0
  - rows: dev=1430 validation=710；10 类计数与 manifest 一致。

## 2. 执行命令

    # 生成（仓库根目录；可重复）
    python eval/v2/corpus/build_v2_corpus.py

    # 校验
    python eval/v2/check_v2_corpus.py

    # dev 评测（默认；只跑 dev）
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest.evaluateV2DevCorpus" --console=plain

    # validation 评测（仅验收；只输出聚合）
    $env:PINYIN_V2_VALIDATION='1'
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest.evaluateV2ValidationCorpus" --console=plain

执行结果：dev BUILD SUCCESSFUL（25s，含编译），validation BUILD SUCCESSFUL（23s）。输出：
- build/pinyin-v2-eval-dev.txt（含聚合、分类、caseDetail 明细）
- build/pinyin-v2-eval-validation.txt（只含聚合、分类与交互项，不含 case id/输入/期望/候选明细）

## 3. dev 首轮结果

### 3.1 总览

| 口径 | n | Top1 | Top5 | rankCost 平均 | fullCoverage | oracle |
|---|---:|---:|---:|---:|---:|---:|
| 全量（含交互项） | 1,430 | 720/1430 = 50.3% [47.8%, 52.9%] | 1,025/1430 = 71.7% [69.3%, 74.0%] | 3.57 | — | — |
| **引擎可评分** | **1,384** | **714/1384 = 51.6% [49.0%, 54.2%]** | **1,001/1384 = 72.3% [69.9%, 74.6%]** | **3.50** | 1,383/1384 = 99.9% | 1,169/1384 = 84.5% |

- 交互项 46 条（mixed_en 40 + number_punct 阿拉伯数字 6），不进入引擎指标分母。
- 引擎 Top5 错误率 27.7%；fullCoverage 99.9% 说明解码器几乎总能给出覆盖全输入的候选，主要问题是排序与召回（oracle 84.5%，即 15.5% 的正确路径根本不在 64 候选内）。

### 3.2 分类

| 类别 | n（scored） | Top1 | Top5 | rankCost | fullCoverage | oracle |
|---|---:|---:|---:|---:|---:|---:|
| natural_short 自然短句 | 400 (400) | 228/400 = 57.0% | 328/400 = 82.0% | 2.83 | 100.0% | 92.0% |
| natural_medium 自然中句 | 450 (450) | 222/450 = 49.3% | 318/450 = 70.7% | 3.58 | 449/450 = 99.8% | 84.4% |
| natural_long 自然长句 | 300 (300) | 111/300 = 37.0% | 155/300 = 51.7% | 5.10 | 100.0% | 69.7% |
| homophone 同音词歧义 | 40 (40) | 40/40 = 100.0% | 40/40 = 100.0% | 1.00 | 100.0% | 100.0% |
| shorthand 简拼 | 40 (40) | 31/40 = 77.5% | 38/40 = 95.0% | 1.53 | 100.0% | 100.0% |
| typo 错拼 | 40 (40) | 15/40 = 37.5% | 21/40 = 52.5% | 5.00 | 100.0% | 57.5% |
| mixed_pinyin 混拼 | 40 (40) | 20/40 = 50.0% | 38/40 = 95.0% | 2.20 | 100.0% | 97.5% |
| number_punct 数字与标点 | 40 (34) | 19/34 = 55.9% | 30/34 = 88.2% | 2.38 | 100.0% | 97.1% |
| proper_noun 专有名词 | 40 (40) | 28/40 = 70.0% | 33/40 = 82.5% | 2.60 | 100.0% | 92.5% |
| mixed_en 中英混输（交互） | 40 (0) | prefixTop1 0/40 | prefixTop5 18/40 | — | — | — |

### 3.3 交互项

| 项 | n | prefix Top1 | prefix Top5 |
|---|---:|---:|---:|
| mixed_en | 40 | 0/40 = 0.0% | 18/40 = 45.0% |
| number_punct 阿拉伯数字 | 6 | 6/6 = 100.0% | 6/6 = 100.0% |

mixed_en 的中文前缀候选通常排名第 2 位以后，首项被单字或短词占据，说明“中文前缀一次选中”的交互路径在当前引擎下明显不足；该结论不计入引擎 Top1/Top5，但必须作为端到端失败项保留。

### 3.4 延迟与失败原因

- 延迟（JVM 参考）：p50=2.557 ms、p95=6.661 ms、p99=7.927 ms、max=11.843 ms（1,430 次 search，limit=8；非 Android 端到端）。
- 失败原因（未进前五）：切分=65、排序=293、缺词=2、容错=23、交互=22。

## 4. validation 首轮结果（只读聚合）

| 口径 | n | Top1 | Top5 | rankCost 平均 | fullCoverage | oracle |
|---|---:|---:|---:|---:|---:|---:|
| 全量（含交互项） | 710 | 363/710 = 51.1% [47.5%, 54.8%] | 507/710 = 71.4% [68.0%, 74.6%] | 3.59 | — | — |
| **引擎可评分** | **685** | **359/685 = 52.4% [48.7%, 56.1%]** | **498/685 = 72.7% [69.2%, 75.9%]** | **3.49** | 684/685 = 99.9% | 581/685 = 84.8% |

| 类别 | n（scored） | Top1 | Top5 | rankCost | fullCoverage | oracle |
|---|---:|---:|---:|---:|---:|---:|
| natural_short | 200 (200) | 123/200 = 61.5% | 164/200 = 82.0% | 2.79 | 100.0% | 91.0% |
| natural_medium | 220 (220) | 103/220 = 46.8% | 156/220 = 70.9% | 3.68 | 100.0% | 85.0% |
| natural_long | 150 (150) | 56/150 = 37.3% | 82/150 = 54.7% | 4.86 | 149/150 = 99.3% | 72.0% |
| homophone | 20 (20) | 20/20 = 100.0% | 20/20 = 100.0% | 1.00 | 100.0% | 100.0% |
| shorthand | 20 (20) | 16/20 = 80.0% | 20/20 = 100.0% | 1.25 | 100.0% | 100.0% |
| typo | 20 (20) | 9/20 = 45.0% | 10/20 = 50.0% | 5.05 | 100.0% | 60.0% |
| mixed_pinyin | 20 (20) | 5/20 = 25.0% | 15/20 = 75.0% | 3.80 | 100.0% | 95.0% |
| number_punct | 20 (15) | 11/15 = 73.3% | 14/15 = 93.3% | 1.80 | 100.0% | 93.3% |
| proper_noun | 20 (20) | 16/20 = 80.0% | 17/20 = 85.0% | 2.25 | 100.0% | 95.0% |
| mixed_en（交互） | 20 (0) | prefixTop1 0/20 | prefixTop5 5/20 | — | — | — |

- 交互项：mixed_en 20 条 prefixTop1 0/20、prefixTop5 5/20；数字交互 5 条 prefixTop1 4/5。
- 延迟：p50=3.548 ms、p95=8.084 ms、p99=9.962 ms、max=28.110 ms。
- 失败原因：切分=23、排序=148、缺词=1、容错=15、交互=16。

validation 与 dev 的引擎口径差：Top1 52.4% vs 51.6%（+0.8pp）、Top5 72.7% vs 72.3%（+0.4pp），量级一致，说明近重复簇切分没有把 dev/validation 拉开成两个分布。validation 只运行了这一次；未查看逐条结果、未据此调参。

## 5. 与旧评测的对比与解释

| 数据集/口径 | Top1 | Top5 | rankCost | 备注 |
|---|---:|---:|---:|---|
| v1 dev after（历史，引擎可评分） | 79.0% | 85.7% | — | docs/PINYIN_QUALITY_EVAL.md 历史记录 |
| v1 holdout after（历史，引擎可评分） | 66.4% | 85.7% | — | 同上 |
| 旧 sealed（一次性，引擎可评分） | 48.6% | 74.3% | 3.53 | docs/SEALED_EVAL.md |
| **v2 dev（本报告）** | **51.6%** | **72.3%** | **3.50** | 1,384 条引擎可评分 |
| **v2 validation（本报告）** | **52.4%** | **72.7%** | **3.49** | 685 条引擎可评分 |

- v2 dev 比 v1 dev after 低 **27.4pp Top1 / 13.4pp Top5**，但 v2 dev/validation 与旧 sealed 几乎同一水平（51–52% / 72–73%）。v1 dev/holdout 的 79%/66% 属于小样本、以开发集反复调参后的乐观数字；v2 数据更接近旧 sealed 的泛化水平。**这是诚实基线，不应被解释为引擎在 v2 上变差**（PinyinEngine 未改）。
- 与阶段目标（§9.3，0.2 内测中文 Top1 ≥75%、Top5 ≥90%）差距很大：当前 v2 Top1 约 52%、Top5 约 73%。尤其 natural_long Top1 37%、typo Top1 37–45%、mixed_pinyin validation Top1 25%，是下一阶段优先项。
- 旧 sealed 仅作为回归集：若后续引擎在 v2 上提升但旧 sealed 明显回退，必须解释；不能再用旧 sealed 宣称“未见数据验收”。

## 6. 局限与未完成

1. 本报告是**首轮基线**，不是发布验收。v2 validation 仍需按协议只用于最终验收；本报告只保留聚合。
2. Tatoeba 自然句有翻译腔、部分句子含“著”等繁体共用字；pypinyin 多音字读音与引擎词典可能不一致，均按协议作为独立来源的固有噪声接受。
3. natural_long 限制 17–24 字/拼音 ≤60，是为避开 PinyinEngine 的 64 字符硬上限；更长输入的 UX 由事件序列集/设备检查覆盖。
4. 专项类别为模板/人工构造，不代表真实分布；mixed_en 与数字交互项不进入引擎分母，单独报告。
5. JVM 延迟仅作同机参考，不等于 Android 端到端按键延迟；真机性能以 D00/设备任务记录为准。
6. PinyinV2EvalTest 不设质量门槛断言（避免把当前基线锁死）；质量门槛由后续验收报告按 §9.3 判定。
7. 事件序列集（eval/v2/events/、EventSequenceReplayTest）由 task-3 单独交付；本报告不含其执行结果。
