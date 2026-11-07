# Bitap 下一轮交付报告（A/B/C 三批）

- 日期：2026-11-07
- 仓库：D:/Projects/Github/Bitap
- 最终代码状态：A（资源许可）+ B（长句排序）+ C（交互修复与真机验收）
- 最终 release APK：dist/bitap-0.1.0-final.apk；28,301,655 B；SHA-256 `CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0`；代码 commit `b5caf39`
- 结论先行：**本轮完成了资源许可闭环、长句专项分析和部分改善、以及交互缺口的设备验收；但封存验收集显示真实泛化仍明显低于开发/保留集，不能把“五阶段工程交付”表述为“产品体验已经达标”。**

## 0. 最终状态摘要

| 项 | 值 |
|---|---|
| 批 A 源码 commit | `d9749d9a90c31764154b5527dca20fdb57af929a`（license-cleanup，已并入 master） |
| 批 A APK | dist/bitap-0.1.0-batchA.apk；28,301,655 B；SHA-256 `A66472A2E22AFF69CE88CA404CC74C2EA8B6B4E4771EDCB88EB8645537E43EB2` |
| 批 B 源码 commit | `6aa15a58dae4e78ddbc4b8007190962d380e57ee`（engine-long-sentence，已 rebase 到 A 上并合入 master） |
| 批 B APK | dist/bitap-0.1.0-batchB.apk；28,301,655 B；SHA-256 `EEFB3838207FF88BED402BFE9D4E0E43295357E373531A3C6049E7D912664EA9` |
| 批 C 代码 commit | `b5caf3964f3eae731ca3fbc6e1ed9a209e18722f`（interaction-p2 合并） |
| 批 C 文档提交 | `docs: record next-round delivery, sealed evaluation and device performance`（见 git log；docs-only，不改变 APK 二进制） |
| 批 C / 最终 APK | dist/bitap-0.1.0-final.apk；28,301,655 B；SHA-256 `CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0`（构建于代码 commit `b5caf39`；其后 docs-only 提交不改变二进制） |
| 单元测试 | `:app:testDebugUnitTest` BUILD SUCCESSFUL（311 tests / 0 failures / 0 errors / 1 skipped（sealed 默认 skipped），含新增机制与交互测试） |
| 封存验收集 | 132 条，一次性执行于 `6aa15a5`；引擎口径 top1 51/105 (48.6%)、top5 78/105 (74.3%)、avgTaps 3.53；long_natural 仅 4/22 top1 |
| 设备性能 | 基线 vs 最终包见 §3.2（0.1.0 / commit 956b504 → final / b5caf39）；同设备 22021211RC / Android 16 / 1080×2400 / user build |
| 总判断 | 资源许可可闭环；长句首选仍弱（dev 7/12、holdout 5/12、sealed long_natural 4/22）；真机性能与交互验收见 §3，封存见 §4；不宣称产品体验达标 |

## 1. 批 A：资源来源与许可证闭环（P0）

### 1.1 问题与依据

旧 `app/src/main/assets/pinyin_dict.tsv` 的词条来自 jieba（MIT）+ pypinyin（MIT），但字符 n-gram（`#@c/#@b/#@t`）与 `#@w` 派生自 candlewill/Dialog_Corpus（小黄鸡）。该仓库未声明许可证，原标注“所有原始语料归原作者所有”；不能以“匿名统计量”或“仅 demo”作为许可已解决。

### 1.2 修改内容

- 改用 Tatoeba Chinese (cmn) **detailed sentences export**（2026-11-07，https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2，1,728,683 B，SHA-256 `DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A`）。
- 仅派生字符 n-gram 聚合计数；不包含原始句子、不包含 `#@w` 语料词频；生成脚本 `scripts/build_pinyin_dict.py` 强制写入 8 行 `#@provenance`（名称/URL/许可/导出日期/文件/SHA-256/阈值）。
- 许可：Tatoeba Terms of Use §6.2 默认 CC BY 2.0 FR；采用“列出所有作者”方式署名，`docs/TATOEBA_CONTRIBUTORS.txt` 机械生成，976 位作者、89,289 句、无空用户名。
- 资源与代码核查：`app/src/main/assets`、`scripts`、`eval` 中旧名称 0 命中；`docs` 仅本报告保留历史来源说明。`scripts/verify_pinyin_dict_license.py` 校验：ok=true，500 条源句抽样交集 0。
- 产物：`pinyin_dict.tsv` git blob 2,175,214 B / 129,168 行 / 65,999 词条 / `#@c=5,296`、`#@b=43,290`、`#@t=14,573`、`#@w=0`；词条部分与旧 HEAD 逐行完全一致，变化仅在 n-gram 与 provenance。
- 文档：`docs/LICENSE_CLEANUP.md`、`THIRD_PARTY_NOTICES.md`、`README.md`。

### 1.3 同口径质量对比（同一引擎、dev/holdout 132 条；JVM）

| 组别 | 词典字节 | dev top1 | dev top5 | dev avgTaps | holdout top1 | holdout top5 | holdout avgTaps |
|---|---:|---:|---:|---:|---:|---:|---:|
| 旧词典 HEAD（before） | 2,915,616（git blob） | 86/132 (65.2%) | 107/132 (81.1%) | 2.69 | 77/132 (58.3%) | 101/132 (76.5%) | 3.11 |
| **Tatoeba detailed 3/6（after，选定）** | 2,175,214（git blob） | **94/132 (71.2%)** | **110/132 (83.3%)** | **2.58** | **81/132 (61.4%)** | **102/132 (77.3%)** | **3.01** |
| 对照：cand_no_ngram（无第三方语料） | 1,301,060 | 70/132 (53.0%) | 97/132 (73.5%) | 3.49 | 56/132 (42.4%) | 83/132 (62.9%) | 4.17 |
| 对照：cand_2_4 | 2,698,698 | 95/132 (72.0%) | 109/132 (82.6%) | 2.58 | 81/132 (61.4%) | 102/132 (77.3%) | 3.02 |
| 对照：cand_5_10 | 1,814,557 | 92/132 (69.7%) | 110/132 (83.3%) | 2.58 | 77/132 (58.3%) | 101/132 (76.5%) | 3.09 |
| 对照：cand_8_20 | 1,615,284 | 86/132 (65.2%) | 107/132 (81.1%) | 2.73 | 78/132 (59.1%) | 101/132 (76.5%) | 3.08 |

**回归必须记录**：相对旧词典，Tatoeba 3/6 在 dev daily_chat top5 12→11、dev unseen_sentence top5 12→11、holdout unseen_sentence top1 11→9/top5 12→10、holdout typo top1 8→7；总体为 +6 dev top1、+4 holdout top1。选 3/6 而非 2/4：dev top1 少 1 条、top5 多 1 条，holdout 相同，文件小 19.4%、阈值更保守。`cand_no_ngram` 因 dev top1 少 16 条，仅作无第三方语料备选记录，不进入发布资产。

### 1.4 交付与边界

- commit：`d9749d9a90c31764154b5527dca20fdb57af929a`；APK：dist/bitap-0.1.0-batchA.apk，28,301,655 B，SHA-256 `A66472A2E22AFF69CE88CA404CC74C2EA8B6B4E4771EDCB88EB8645537E43EB2`。
- 边界：detailed 导出无逐句 license 字段，CC BY 2.0 FR 为 Tatoeba Terms 的数据集级默认声明，不能逐句法律审计；Tatoeba 可能含外部导入句，其额外许可无法完全排除；不宣称“所有资源均可商用”，不覆盖模型权重/其他第三方资源。上述边界已写入 `docs/LICENSE_CLEANUP.md` §7。

## 2. 批 B：长句召回/排序专项（P1）

### 2.1 失败路径分析与分型（dev long_segment 12 + dev-work_comm-28 = 13 条）

定义 4+2 类：0-ok、0b-top5-scoring（候选在 2–5 名）、3-scoring（候选已收集但排前 5 外）、2-beam/entry-pruning（生产 beam 无、放大后存在）、1-not-generated（放大也不生成）、4-dedupe/limit（被去重/数量丢弃）。

- baseline 分类：`{0-ok=7, 0b-top5-scoring=2, 2-beam/entry-pruning=2, 1-not-generated=1, 3-scoring=1}`
- after 分类：`{0-ok=8, 0b-top5-scoring=3, 2-beam/entry-pruning=1, 1-not-generated=1}`
- 正确路径召回：10/13 → 11/13；排序前 oracle：10/13 → 11/13；wide（192/16）召回 12/13。
- top1：7/13 → 8/13；top5：9/13 → 11/13；avgTaps（rank+1，miss=9）：3.69 → 2.62。

### 2.2 修改内容（无句子专用奖励/词条）

1. lattice beam 32→64、entriesPerKey 4→8（生产默认，长句召回 +2 条）。
2. 词典字符 bigram 词内重排权重 0→0.4（仅用 jieba 词典派生的 `charAggregate/ bigramModel`，不依赖语料 n-gram）。
3. 分段选词前缀：coverage 目标 0.36、coverage weight 4.0、early slot 1；并修复“完整候选达到 96 条后 partial 前缀候选被提前 return 完全丢弃”的通用缺陷。
4. 未启用句级 corpus/dictionary n-gram 重排（0.2 权重消融无收益、延迟略增）；未启用按 last-word 去重（质量回退）。
5. 禁止项核查：生产 diff 无新增中文词条/句子字符串；未修改 `pinyin_dict.tsv`；未使用 sealed 数据。

### 2.3 全量 dev/holdout（A 词典基线 → after，同一引擎、相邻运行）

| 数据集/口径 | baseline top1 | after top1 | baseline top5 | after top5 | baseline avgTaps | after avgTaps |
|---|---:|---:|---:|---:|---:|---:|
| dev 全量 | 94/132 (71.2%) | **97/132 (73.5%)** | 110/132 (83.3%) | 108/132 (81.8%) | 2.58 | 2.52 |
| dev 引擎可评分 | 92/119 (77.3%) | **94/119 (79.0%)** | 102/119 (85.7%) | 102/119 (85.7%) | 2.30 | 2.20 |
| holdout 全量 | 81/132 (61.4%) | 81/132 (61.4%) | 102/132 (77.3%) | **108/132 (81.8%)** | 3.01 | 2.78 |
| holdout 引擎可评分 | 80/119 (67.2%) | 79/119 (66.4%，-1 条) | 97/119 (81.5%) | **102/119 (85.7%)** | 2.67 | 2.46 |

长句类别：dev top1 6/12→**7/12**、top5 8/12→**10/12**、avgTaps 3.92→2.75；holdout top1 4/12→**5/12**、top5 6/12→**8/12**、avgTaps 5.58→4.17。已知回归 dev-work_comm-28「明天上午开评审会」在 A 词典 baseline 已 TOP1，after 仍 TOP1、taps=1。

其他类别：所有类别 top1 下降 ≤1 条（holdout daily_chat/number_punct/mixed_pinyin 各 -1）；dev 全量 top5 83.3%→81.8%（-2 条）来自 mixed_en 交互项 8→6 与 shorthand 9→7（长句 +2、mixed_pinyin +1 抵消一部分）；引擎可评分口径 top5 持平。shorthand 的 top5 回退需在后续简拼排序专项处理。

### 2.4 消融与代价

| 组 | top1 | top5 | avgTaps | long p95 (ms) |
|---|---:|---:|---:|---:|
| legacy 32/4 | 7/13 | 9/13 | 3.69 | 3.4 |
| budgetOnly 64/8 | 7/13 | 10/13 | 3.23 | 11.4 |
| **prod-combo1e1（最终默认）** | **8/13** | **11/13** | **2.62** | **12.6** |
| sentenceCorpus 0.2 | 8/13 | 11/13 | 2.62 | 13.5（无收益，关闭） |
| coverageOff | 7/13 | 10/13 | 3.23 | — |
| earlySlotOff | 8/13 | 10/13 | 2.85 | — |
| dedupeOn | 8/13 | 9/13 | 3.69 | 2.8（回退，不采用） |

JVM 全量延迟：dev p50/p95 0.551/1.875 → 1.712/7.761 ms；holdout 0.597/1.646 → 2.024/8.808 ms。长输入（诊断集）p95 12.6 ms <50 ms；普通输入 p95 5.7 ms <10 ms。索引堆增量约 47.9 MB（JVM 粗略，含词典 65,999 条 + shorthand）。详见 `docs/LONG_SEGMENT_ANALYSIS.md`。

### 2.5 交付与边界

- commit：`6aa15a58dae4e78ddbc4b8007190962d380e57ee`；APK：dist/bitap-0.1.0-batchB.apk，28,301,655 B，SHA-256 `EEFB3838207FF88BED402BFE9D4E0E43295357E373531A3C6049E7D912664EA9`。
- 未完成：dev-long_segment-67 生产/宽 beam 都不生成（512/32 才出现且分数低）；dev-long_segment-69 的「排期」在 A 词典 freq=0，192/16 可生成但排序靠后；64/66 仍 TOP2 未 TOP1；holdout long_segment 仍 5/12 top1；句级 n-gram 重排无收益保持关闭。不得把这些说成已解决。

## 3. 批 C：交互缺口与真机验收（P2 + P1 设备性能）

### 3.1 交互缺口修复

- 结论：当前 ✦ AI 入口是**AI 词库候选（仅拼音）**，不是文本改写/润色入口；代码中没有生产 UI 打开 `AiPanelOpened`，旧文案“先输入拼音，或选中一段文字”会误导用户去选中任意中文，属于不可兑现承诺。
- 修改：`KeyboardReducer.kt` 新增 `AI_LEXICON_QUERY_REQUIRED_MESSAGE` / `AI_LEXICON_SELECTION_NOT_PINYIN_MESSAGE`；`KeyboardController.kt` 按有无选区/是否拼音选提示；`KeyboardScreen.kt` 统一 `aiEntryContentDescription` / `aiEntryLabel`（“AI 词库候选（只根据拼音）”）；`BitapApp.kt` 模型删除弹窗“AI 改写不可用”→“AI 词库候选不可用”。
- 设备验收：基线 0.1.0 中文选区/无选区均 FAIL（“先输入拼音，或选中一段文字”）；P2 预览包 PASS（中文选区提示“当前选区不是拼音，请先输入拼音或选中一段拼音”；无选区提示“请先输入拼音”；长按选中已上屏 nihao 可正常触发 AI 词库请求）。详见 `docs/INTERACTION_ACCEPTANCE.md`。
- 显式中英切换：基线/预览均 PASS——有 composing 切英文只提交一次 raw composing（nihao len=5，无丢字/重复）；空格/英文/回车/半角逗号/全角逗号/光标均正确；“无切换连续混排”不在本轮完成声明内。
- 最终 C 批 APK（CFA88667…）复验：中文选区/无选区提示均 PASS；显式中英切换 PASS；证据见 `docs/INTERACTION_ACCEPTANCE.md` §6 与 `.tmp/p2device/final-*`。

### 3.2 设备性能测量（基线 vs 最终包）

> 设备：22021211RC / Android 16 / API 36 / 1080×2400@440dpi / user build；battery 温度约 29–31°C；亮度 20 手动；AI 模型已导入（343,490,560 B）。方法、样本量、原始数据见 `docs/DEVICE_PERFORMANCE.md` 与 `.tmp/perf2/`。

| 指标 | 基线 0.1.0（A8D8AFE8…，commit 956b504） | 最终包（CFA88667…，code b5caf39） |
|---|---:|---:|
| 冷启动 p50（同脚本 n=12，基线有效 n=11） | 201 ms | **134 ms** |
| 冷启动 p90 / p95 | 513 / 517.5 ms | **142 / 144.2 ms** |
| 热启动 p50（n=12） | 92 ms | 91.5 ms |
| 按键→帧完成（应用侧，142 帧，p50/p95） | 11.0 / 15.5 ms | 11.5 / 17.6 ms |
| 按键→DisplayPresent（系统 present，142 帧，p50/p95） | 31.3 / 64.3 ms | 31.4 / 64.6 ms |
| 连续输入 jank 87 字符（173 vs 175 帧） | 75.14% janky；p50/p95 15.3/27.0 ms | **68.00% janky；13.8/25.5 ms**（p99/最大帧略升） |
| 空闲 PSS（AI on，刚显示） | 117,691 KB | **98,510 KB** |
| AI 关闭 fresh 进程 PSS / Java / Native / Graphics | 95,444 / 38,992 / 28,800 / 8,984 KB | **65,159 / 33,696 / 9,388 / 10,992 KB** |
| AI 生成中 PSS 峰值 / Native 峰值 | 1,735,188 / 1,256,952 KB | **1,793,781 / 1,321,276 KB（最大瓶颈）** |
| 生成后 18s（未退出） PSS / Native | 1,735,188 / 1,256,952 KB | 1,718,397 / 1,250,452 KB（关闭 AI 不释放 native，需杀进程） |

avgTaps 口径：JVM 模拟值 = 命中候选 rank+1（第1位=1次选择），未进前5记 9；只计候选选择，不含拼音字母键入。真机抽样记录选择次数、翻页、修正次数，见 §3.3 / DEVICE_PERFORMANCE。

### 3.3 真机 avgTaps 抽样与模拟值核对

设备基线包实测 3 条 dev long_segment（61/62/63）：均成功选到期望整句；拼音字母键 50/56/40，候选选择各 1 次，展开各 1 次，纠错/退格 0；JVM rank+1 分别为 2/3/1。差异：JVM 的 rank+1 只计候选选择（第 2 位记 2 次），不含拼音字母输入与候选面板展开（折叠态需先点 v 展开 +1 UI 操作）；设备实际每条 1 展开 + 1 选择。原始记录 `.tmp/perf2/avgtaps-baseline.csv`。

## 4. 封存验收集（一次性）

- 语料：132 条，6 类各 22；SHA-256 `d9e2d946ca131ac452654bfac66d44a21c9cf1757f59e5ed2e5a66aff3a5b987`；已归档 `eval/pinyin_cases_sealed.tsv`。
- 首次使用代码 commit：`6aa15a58dae4e78ddbc4b8007190962d380e57ee`（A+B；P2 不改引擎）；评测器与语料随后在 docs 提交中归档。
- 结果（只列聚合）：全量 top1 51/132 (38.6%)、top5 79/132 (59.8%)、avgTaps 4.61；引擎可评分 top1 51/105 (48.6%)、top5 78/105 (74.3%)、avgTaps 3.53。
- 类别：long_natural 4/22 top1、12/22 top5；shorthand_ambiguity 10/22、19/22；mixed_pinyin 13/22、20/22；typo_correction 11/22、12/22；number_punct 13/22、15/22（engine 13/17、15/17）；english_abbrev（交互）0/22、1/22。
- 与 dev/holdout after（引擎口径 79.0%/85.7%）相比，sealed 低约 18–30pp；**差距明显，应优先相信 sealed**。未查看逐条结果、未据此调参，因此仍是一次性未见数据证据；但这也意味着不能宣称“真实连续输入质量达标”。

## 5. 汇总：改善、回归与未完成

### 5.1 同口径总表

| 阶段 | 数据集 | 指标 | baseline | after | Δ |
|---|---|---|---:|---:|---:|
| A 资源替换 | dev 引擎 | top1 / top5 / avgTaps | 70.6% / 83.2% / 2.45 | **77.3% / 85.7% / 2.30** | +6.7pp / +2.5pp / -0.15 |
| A 资源替换 | holdout 引擎 | top1 / top5 / avgTaps | 63.9% / 81.5% / 2.76 | **67.2% / 81.5% / 2.67** | +3.3pp / 0 / -0.09 |
| B 长句专项 | dev 引擎 | top1 / top5 / avgTaps | 77.3% / 85.7% / 2.30 | **79.0% / 85.7% / 2.20** | +1.7pp / 0 / -0.10 |
| B 长句专项 | holdout 引擎 | top1 / top5 / avgTaps | 67.2% / 81.5% / 2.67 | **66.4% / 85.7% / 2.46** | -0.8pp(1条) / +4.2pp / -0.21 |
| B 长句专项 | dev long_segment | top1 / top5 | 6/12 / 8/12 | **7/12 / 10/12** | +1 / +2 |
| B 长句专项 | holdout long_segment | top1 / top5 | 4/12 / 6/12 | **5/12 / 8/12** | +1 / +2 |
| 封存验收 | sealed 引擎 | top1 / top5 / avgTaps | — | 48.6% / 74.3% / 3.53 | **低于 dev/holdout，真实短板** |

### 5.2 明确未完成 / 不可宣称

1. 长整句首选仍弱：dev 7/12、holdout 5/12、sealed long_natural 4/22；不能宣称“任意整句首选通过”。
2. 封存集显示 typo_correction top5 仅 12/22、long_natural top5 12/22；真实输入质量未达标。
3. dev-long_segment-67 不生成；dev-long_segment-69「排期」词典缺词；64/66 仍 TOP2。
4. 简拼歧义、混合输入、错拼等类别仍有明显缺口；B 阶段 dev shorthand top5 9→7 为负向变化；无切换连续混排不支持（不在本轮声明内）。
5. 设备性能：基线 vs 最终包见 §3.2；冷启动为“进程重启后首次显示”（Android 16 系统 IME 保活，无法获得进程不存在状态），基线有效 n=11、final n=12；重启后 n=1 仅参考。
6. 设备仅 22021211RC / Android 16 单一型号；其他 ROM 未验证。
7. 设备最大瓶颈是 AI 内存：生成峰值 PSS 1,793,781 KB（Native 1,321,276 KB），生成后 18s 仍 1.72GB；关闭 AI 不释放 native，需杀进程。连续输入 jank 仍 68%（p99 28.5ms），属已知短板。
8. 许可：Tatoeba 数据集级许可已核验并落实 976 位作者署名，但 detailed 导出无逐句 license 列、外部导入句额外许可无法完全排除；不宣称“所有资源均可商用”。

## 6. 复现命令

```powershell
# 单测
.\gradlew.bat :app:testDebugUnitTest --console=plain --rerun-tasks
# 质量评测（dev/holdout）
$env:PINYIN_EVAL_STAGE='final'; .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks --console=plain
# 封存（仅一次）
$env:PINYIN_SEALED_RUN='1'; $env:PINYIN_SEALED_STAGE='final'; .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinSealedEvalTest" --rerun-tasks --console=plain
# release APK
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1 -OutputName 'bitap-0.1.0-final.apk'
```

## 7. 证据索引

- 资源许可：`docs/LICENSE_CLEANUP.md`、`docs/TATOEBA_CONTRIBUTORS.txt`、`THIRD_PARTY_NOTICES.md`
- 长句：`docs/LONG_SEGMENT_ANALYSIS.md`、`build/long-segment-diagnostic.txt`、`build/long-segment-detail.txt`、`build/long-segment-tuning-ablation.txt`
- 质量：`build/pinyin-quality-eval-batcha-after.txt`、`build/pinyin-quality-eval-batchb-after.txt`、`build/pinyin-quality-engine-final.txt`
- 封存：`docs/SEALED_EVAL.md`、`build/pinyin-sealed-eval-final.txt`、`eval/pinyin_cases_sealed.tsv`
- 交互：`docs/INTERACTION_ACCEPTANCE.md`、`.tmp/p2device/`
- 性能：`docs/DEVICE_PERFORMANCE.md`、`.tmp/perf2/`
