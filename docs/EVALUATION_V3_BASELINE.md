# 评测 v3 首轮独立验收基线（D04 / CC0）

- 日期：2026-11-08
- 状态：**首次训练源独立的独立验收集，未针对 v3 调参**；本次结果只作基线，不作发布通过结论
- 基线 commit：f07383638d36edd24666a11326095bcd2c9cbfb7
- 环境：Windows 11 / OpenJDK 25.0.4.1 / Gradle 9.7.1 / Kotlin 2.4.0 / Python 3.14.7 / pypinyin 0.55.0 / zhconv 1.4.3
- 词典：app/src/main/assets/pinyin_dict.tsv，条目 65,999；工作区文件 SHA-256（Windows checkout）4C979D12D957F2E567F06844CFAA705E07DBF544B808FF055C86669D5A53F20D
- 评测器：app/src/test/java/com/bitap/keyboard/core/PinyinV3EvalTest.kt
- 协议：docs/EVALUATION_V3_PROTOCOL.md

## 1. 语料与独立性

- 来源：irvin/cc0-sentences 的 zh-TW 日常聊天文件（setences.txt、chatlogs.txt、g0v_slack_rand0m.txt、tg_common_voice.txt），CC0-1.0；zhconv 1.4.3 转简体。
- 源压缩包 SHA-256：e229d8e3bef48473d55b3ab1a123555080f855120206ee5a82b34bb9413d9fc5。
- 生成文件：eval/v3/corpus/pinyin_cases_v3_validation.tsv，601 行，SHA-256 355d1e7ee611eed492c6d937a7d6261b015c3b7ef8df22a083a5c97b8ab6c8f2。
- 类别：natural_short 320、natural_medium 240、natural_long 41。长句只有 41 条：源数据过滤后仅 44 条 17–24 字句，另有 3 条因参考近重复未入选；不通过改写来源补足。
- 这是 validation-only 集合，没有 dev 调参集。生成器与校验器均不调用 PinyinEngine；v2 与 v1 只用于重叠排除，不作为 v3 的训练/生成来源。
- 校验器：python eval/v3/check_v3_corpus.py → OK all checks passed：
  - dictionary_whole_sentence_check: rows=601 hits=0
  - v1_v2_overlap_check: exact=0 near_dup_0.80=0
  - source_traceability: checked=601 mismatches=0
  - category_counts 与 manifest 一致。

## 2. 执行命令

    # 生成（仓库根目录；可重复）
    python eval/v3/corpus/build_v3_corpus.py

    # 校验
    python eval/v3/check_v3_corpus.py

    # 默认跳过（不消费 v3）
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV3EvalTest" --console=plain

    # 首次独立验收（只输出聚合）
    $env:PINYIN_V3_ACCEPTANCE='1'
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV3EvalTest" --rerun-tasks --console=plain

执行结果：默认跳过 BUILD SUCCESSFUL（11s，test task 实际执行但 assumption skip）；acceptance BUILD SUCCESSFUL（46s，--rerun-tasks）。报告：build/pinyin-v3-eval-acceptance.txt（仅聚合/分类，无 case id、输入、期望、候选明细）。

## 3. 首轮结果

### 3.1 总览（601 条，全部引擎可评分）

| 指标 | 结果 |
|---|---:|
| Top1 | 233/601 = 38.8% [35.0%, 42.7%] |
| Top5 | 357/601 = 59.4% [55.4%, 63.3%] |
| rankCost 平均 | 4.61（总 2773） |
| fullCoverage | 601/601 = 100.0% |
| oracle（limit=64） | 463/601 = 77.0% |
| Top5 错误率 | 40.6% |

### 3.2 分类

| 类别 | n | Top1 | Top5 | rankCost | fullCoverage | oracle |
|---|---:|---:|---:|---:|---:|---:|
| natural_short 自然短句 | 320 | 152/320 = 47.5% [42.1%, 53.0%] | 220/320 = 68.8% | 3.87 | 100.0% | 83.4% |
| natural_medium 自然中句 | 240 | 71/240 = 29.6% [24.2%, 35.6%] | 119/240 = 49.6% | 5.36 | 100.0% | 70.8% |
| natural_long 自然长句 | 41 | 10/41 = 24.4% [13.8%, 39.3%] | 18/41 = 43.9% | 6.05 | 100.0% | 63.4% |

### 3.3 延迟与失败原因

- JVM 参考延迟：p50=4.554 ms、p95=9.519 ms、p99=12.635 ms、max=17.904 ms（601 次 search，limit=8）。
- 失败原因（未进前五）：切分=54、排序=188、缺词=2。

## 4. 与 v2 / 旧 sealed 的对比

| 数据集 | Top1 | Top5 | rankCost | 说明 |
|---|---:|---:|---:|---|
| v2 dev（同源探索） | 51.6% | 72.3% | 3.50 | 自然句来自 Tatoeba；与生产 n-gram 同源 |
| v2 validation（同源探索） | 52.4% | 72.7% | 3.49 | 同上，只用于历史对照 |
| 旧 sealed | 48.6% | 74.3% | 3.53 | 旧一次性集合；公开后只能回归 |
| **v3 validation（本报告）** | **38.8%** | **59.4%** | **4.61** | 首次训练源独立的独立验收 |

说明：v2/旧 sealed 的 rankCost 是 D04 修正前口径（rank≥0 均计 rank+1）；v3 使用修正后定义（前五外记 9），跨版本不要直接比较 rankCost，Top1/Top5 可比。

- v3 明显低于 v2 的 51–52% / 72–73%，也低于旧 sealed；这提示跨来源泛化不足，但 v3 同时更换来源、地区用词与句长分布，当前实验不能把全部差值归因于训练重合。也不能解释为引擎在 v3 上突然变差（PinyinEngine 未改）。
- v3 的 fullCoverage=100% 但 oracle 只有 77.0%：引擎能给出覆盖全输入的候选几乎总是成立，但正确路径有 23.0% 根本不在 64 个候选中，主要问题在召回/词典覆盖与排序，而不是无法出整句。失败原因中排序=188 远大于切分=54、缺词=2，进一步指向排序与候选组织。
- 与 §9.3 的 0.2 内测目标（Top1 ≥75%、Top5 ≥90%）相比，v3 独立基线差距很大：Top1 38.8%、Top5 59.4%。这是阶段一必须记录的真实差距，不能再用 v2 数字宣称达标。

## 5. 局限与未完成

1. v3 来源是台湾网络社群/日常聊天，繁体转简体后仍有地区用词与口语碎片；与大陆真实输入分布有差异，不能外推到所有用户。
2. natural_long 仅 41 条，长句置信区间宽；单条变化不构成方向。
3. zhconv 1.4.3 对少数多义繁体词可能转换不完美；已用 GB2312 过滤兜底，但仍有转换噪声。
4. v3 只作独立验收，不进入调参；本报告未查看逐条结果，只记录聚合。
5. JVM 延迟不是 Android 端到端延迟；真机性能以 device-qa 记录为准。
6. 本报告未修改生产代码、词库或学习数据；v3 不能作为训练数据。

## 6. 冻结后审查勘误（2026-11-08）

首次数字与 601 条冻结语料保持不变。后续完整训练源审计确认存在 1 条规范化精确训练句重合，不能表述为严格零重合；本报告 §4 中“此前分数被同源数据高估”应理解为风险判断，当前跨数据集对比未隔离训练重合与地区/句长/难度变化，不能证明因果或高估幅度。审计证据和后续要求见 `docs/D04_REVIEW_08b5543.md` 与 v3 协议 §10。
