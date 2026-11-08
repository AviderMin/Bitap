# 评测协议 v3（D04 独立验收集）

- 文档版本：v3.0
- 日期：2026-11-08
- 适用任务：D04 审查后的独立泛化验收（task-8）
- 依据：docs/D04_HOST_COMPARISON.md §“阶段一审查发现 2：v2 与生产 n-gram 的训练来源重合”
- 数据：eval/v3/corpus/pinyin_cases_v3_validation.tsv（仅 validation，无 dev）
- 生成/校验：eval/v3/corpus/build_v3_corpus.py / eval/v3/check_v3_corpus.py
- JUnit：app/src/test/java/com/bitap/keyboard/core/PinyinV3EvalTest.kt
- 基线：docs/EVALUATION_V3_BASELINE.md

## 1. 为什么需要 v3

D04 审查确认：v2 的自然句来自 Tatoeba cmn 普通导出（89,289 句），而生产 pinyin_dict.tsv 的字符 n-gram 统计正是对同一批 89,289 句生成（见 docs/LICENSE_CLEANUP.md 与 eval/v2/corpus/manifest.json）。因此 v2 的“整句不在词典 + dev/validation 近重复隔离”**没有隔离生产统计训练源**；v2 validation 不能作为训练源独立的泛化验收。

v3 的目的：用**从未进入生产 n-gram/词库统计的独立来源**建立一次性独立验收集。v3 只用于验收，不提供 dev 调参；任何按 v3 结果修改词库/规则/特征的行为都会使该集合降级为回归集。

## 2. 数据集角色

| 数据集 | 角色 | 用途 |
|---|---|---|
| v1 dev/holdout | 历史小样本 | 历史可比、旧回归 |
| v1 sealed | 回归集 | 只检查旧结果不明显回退 |
| v2 dev/validation | **同源探索/回归**（自然句与生产 n-gram 同源） | dev 调参、同源对照、回归 |
| **v3 validation** | **训练源独立的独立验收集** | 仅最终验收；不做调参，不逐条阅读 |

规则：

1. v3 validation 不设 dev，不参与任何调参；开发/调参只能用 v2 dev 或更早的 dev。若有人按 v3 结果修改实现，v3 只能降级为回归集，必须重找新来源重建新版本。
2. v3 验收报告只保留聚合与分类数字；不得引用具体句子做规则。
3. v3 不修改生产代码、词库或学习数据。
4. 旧 v2 不删除；保留其历史结果，并在所有后续报告中明确标注“同源探索”，不能与 v3 混在一起只报一个总分。

## 3. 数据来源与许可

| 项 | 值 |
|---|---|
| 来源仓库 | irvin/cc0-sentences（https://github.com/irvin/cc0-sentences） |
| 使用目录 | zh-TW（繁体中文日常聊天） |
| 使用文件 | setences.txt、chatlogs.txt、g0v_slack_rand0m.txt、tg_common_voice.txt |
| 许可 | CC0 1.0 Universal（公共领域奉献，https://creativecommons.org/publicdomain/zero/1.0/） |
| 固定压缩包 | .tmp/cc0/cc0-sentences.zip，SHA-256 e229d8e3bef48473d55b3ab1a123555080f855120206ee5a82b34bb9413d9fc5 |
| 转换 | zhconv 1.4.3，zh-TW → zh-CN（简体），输出 UTF-8 |
| 未使用文件 | book.txt、gpt-4.txt、wikidata.txt、wikipedia.txt、places_and_address.txt、gov_press_release.txt、cofacts.txt、cvsprint_201902.txt、exam_text.txt、lms.txt、sayit.txt、web_slang.txt（非句库/生成数据/地址或非日常聊天） |

四个源文件的逐文件 SHA-256、字节数、源行数见 eval/v3/corpus/manifest.json；署名与许可说明见 eval/v3/corpus/ATTRIBUTION.md。

## 4. 生成与过滤

生成命令（仓库根目录，可重复）：

    python eval/v3/corpus/build_v3_corpus.py
    python eval/v3/check_v3_corpus.py

流程：

1. 只读取四个日常聊天文件；逐行 strip 后用 zhconv.convert(..., "zh-cn") 转简体。
2. 只保留汉字（CJK 统一表意文字且 GB2312 可编码）与中文标点；排除拉丁字母、数字、Emoji、引号/括号、内部终止符（。！？ 只能在末尾）。
3. 按汉字数分 natural_short 2–8、natural_medium 9–16、natural_long 17–24；拼音长度 ≤60（避开 PinyinEngine 64 字符硬上限）。
4. 精确去重（汉字序列）；排除与 pinyin_dict.tsv 整词相同的句子（本轮 89 条）；排除与 v1/v2 任一 expected 完全相同的句子（本轮 1 条）。
5. 近重复聚类：字符 2-gram Jaccard ≥0.75，MinHash 64 置换/16 bands×4 rows LSH + Union-Find；每簇最多取 2 条；与 v1/v2 expected 的字符 2-gram Jaccard ≥0.80 的候选跳过（本轮额外剔除近重复）。
6. 目标 320 short / 240 medium / 44 long。源数据本身长句有限（过滤后仅 44 条，另有 3 条因近重复/参考重叠未入），最终 natural_long=41；这是数据来源的客观限制，不通过改写句子补足。

本轮源文件合计 12,412 行 → 通过字符/长度筛选并去重 12,322 条 → 词典/参考排除后候选池 12,229 条 → 聚类 12,127 簇（101 个多成员簇）→ 最终 601 条（short 320 / medium 240 / long 41）。统计见 manifest.json 的 filters.rejected_counts 与 split。

## 5. 数据格式

TSV，6 列，UTF-8，LF：

    id <TAB> category <TAB> input <TAB> expected <TAB> note <TAB> source

- id：v3v-…，唯一。
- category：natural_short / natural_medium / natural_long。
- input：小写拼音（pypinyin 0.55.0 生成，a–z）。
- expected：转简体后的原句（可能含中文标点）。
- note：CC0 日常聊天（zh-TW→简体）。
- source：cc0:<文件名>:<源文件 1-based 行号>；check_v3_corpus.py 会逐条回读源文件并重新 zhconv 转换核对。

## 6. 指标

指标定义与 v2 协议一致；所有 601 条均为引擎可评分（无交互项）：

| 指标 | 定义 |
|---|---|
| Top1 | 期望答案在返回候选第 1 位 |
| Top5 | 期望答案在返回候选前 5 位 |
| rankCost | 前五命中 rank+1，未进前五记 9；平均与总和 |
| fullCoverage | search(input, limit=64) 中存在 consumedInputLength ≥ 输入有效字符数的候选（不论正确） |
| oracle | search(input, limit=64) 中存在期望答案（同一匹配规则） |
| 分类错误率 | 1 − Top1，按类别报告 |
| 延迟 | JVM search(limit=8) p50/p95，只作同机参考 |

同时报告 Wilson 95% 置信区间。任何类别 Top1/Top5 下降 ≥3pp、oracle 下降或正确性回归必须解释；不能只报总分。

## 7. 执行与输出

PinyinV3EvalTest 默认跳过；只有设置 PINYIN_V3_ACCEPTANCE=1 才运行：

    # 默认（全量测试中跳过，不消费 v3）
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV3EvalTest" --console=plain

    # 最终验收（只输出聚合）
    $env:PINYIN_V3_ACCEPTANCE='1'
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV3EvalTest" --console=plain

输出：build/pinyin-v3-eval-acceptance.txt，只含总/分类聚合、交互无（本集无交互项）、延迟与失败原因；不含 case id、输入、期望或候选明细。

## 8. 治理与失效

1. v3 是**一次性独立验收**：首次结果写入 docs/EVALUATION_V3_BASELINE.md；在候选冻结后运行，不允许按结果调参。
2. 若 v3 内容或生成器变更（新增来源、改过滤、改阈值），必须更新 manifest 的 SHA-256、版本号并在协议中记录；旧结果保留为历史。
3. 若有人查看 v3 逐条并据此修改实现，v3 转为回归集；需要新的独立来源生成 v4。
4. v3 不替代 v2 的同源对照；两者不能放在同一分母混合后只报一个总分。

## 9. 局限

1. 来源是台湾网络社群/日常聊天（繁体转简体），存在翻译腔、口语碎片、地区用词；与大陆真实输入分布仍有差异。
2. zhconv 1.4.3 可能对少数多义繁体词转换不完美；已用 GB2312 过滤兜底，但仍可能有转换残留。
3. natural_long 仅 41 条（源数据长句本来稀少），长句统计置信区间宽；不能用单条变化判断方向。
4. 评测句不进入生产词库；v3 不是训练数据。
5. JVM 延迟不是 Android 端到端延迟；真机性能按 device-qa 记录。

## 10. 冻结后独立审查补充（2026-11-08）

对完整生产训练源的汇总审计发现：601 条中 1 条与 Tatoeba 训练句规范化后完全相同，位于 natural_short；其余在字符 bigram Jaccard≥0.80、长度比≥0.5 的规则下未检出近重复。这不等于严格零训练重合，也不是语义级无重合证明。v3 原始文件与首次成绩继续冻结，不删除该条后回写历史数字。

v2/v3 的总分差同时受来源、地区用词、句长和难度分布影响，不能单独证明同源训练导致的高估幅度。本集只覆盖自然句，不能独立验收错拼或混排。发布严格门禁使用 `python eval/v3/check_v3_corpus.py --strict`（要求四个源文件与固定 zip 存在且 SHA-256 一致）；默认模式是离线轻量检查，源缺失时跳过逐句溯源，不能称为完整溯源门禁。完整说明与下一批建议见 `docs/D04_REVIEW_08b5543.md`；复现审计为 `python eval/v3/audit_training_overlap.py`，发现重合时退出 1。
