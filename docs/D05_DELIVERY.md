# D05 交付：跨来源开发集与输入质量改进

- 日期：2026-11-08（Asia/Shanghai）
- 范围：D05 跨来源诊断 dev、失败诊断、单项机制改进；不含 Android/JNI 集成，不新增主题/AI 功能
- 说明：D05 dev 是**允许逐条诊断的开发集**，不是验收；v3 validation 仍是独立验收，本批未运行 v3

## 1. 交付物

| 项 | 文件 | 结果 |
|---|---|---|
| 跨来源 dev 数据 | `eval/d05/dev/dev.tsv`、`manifest.json`、`ATTRIBUTION.md`、`check_d05_dev.py` | 950 条（自然 600 + 专项 350），CC0-1.0，SHA-256 `1d628eb4…b9b4` |
| 数据协议 | `docs/D05_DEV_PROTOCOL.md` | 允许逐条诊断、禁止验收、专项构造规则 |
| 可复现基线 | `app/src/test/java/com/bitap/keyboard/core/PinyinD05DevEvalTest.kt`、`docs/D05_FAILURE_DIAGNOSIS.md` | naturalOnly Top1 39.7% / Top5 59.0% / rankCost 4.65 / oracle 78.5% / fullCoverage 100% |
| 失败诊断 | `docs/D05_FAILURE_DIAGNOSIS.md`、`build/d05/dev-failures.tsv`（忽略） | 276 条 Top5 未命中：noRecall 112、pruningLoss 37、rankedLow 98、displayLoss 29 |
| 单项机制改进 | `app/src/main/java/com/bitap/keyboard/core/PinyinEngine.kt`、`PinyinD05AblationTest.kt`、`docs/D05_MECHANISM_IMPROVEMENT.md` | early partial 放到 5 个 full 之后（rank6）：naturalOnly Top5 59.0%→60.7%，oracle 不回退 |

## 2. 数据来源与隔离

- 来源：`irvin/cc0-sentences` 的 zh-TW 日常聊天/文本文件（CC0-1.0，固定 zip SHA-256 `e229d8e3…d9fc5`），`zhconv` 转简体；生成器不调用 `PinyinEngine`，不使用 Tatoeba。
- 自然句主来源：`setences.txt`、`g0v_slack_rand0m.txt` 的 v3 剩余行（v3 已选 601 条按 exact + 字符 2-gram Jaccard≥0.75 排除），补充 `tg_common_voice`、`sayit`、`lms`、`cvsprint`、`exam_text`、`gov_press_release`、`cofacts`、`web_slang`、`chatlogs`。
- 与 v1/v2/v3 全部 expected 做 exact + 近重复隔离（本轮 exact=0、near=0）；词典整句泄漏 0；源句逐条可追溯 600/600。
- natural_long 只有 11 条：排除 v3 后源数据长句本身不足，已在 manifest `long_shortfall_note` 与协议中如实记录，不补造句子。

## 3. 基线失败结构（改变前）

| 类别 | n | Top1 | Top5 | rankCost | oracle64 | fullCoverage |
|---|---:|---:|---:|---:|---:|---:|
| naturalOnly | 600 | 39.7% | 59.0% | 4.65 | 78.5% | 100% |
| typo | 100 | 73.0% | 79.0% | 2.75 | 81.0% | 100% |
| shorthand | 100 | 76.0% | 98.0% | 1.52 | 99.0% | 100% |
| mixed_pinyin | 100 | 83.0% | 93.0% | 1.70 | 100% | 100% |
| mixed_en（交互） | 50 | prefixTop1 0% | prefixTop5 32% | — | — | — |

Top5 未命中 276 条的启发式分解：**noRecall 112（40.6%）**、**pruningLoss 37（13.4%）**、**rankedLow 98（35.5%）**、**displayLoss 29（10.5%）**。分类是线索，不是因果证明。

## 4. 机制改进：early partial 位置

### 假设

D05 的 rankedLow + displayLoss = 127/276（46.0%），说明很多正确完整句已生成，只输在候选展示/排序。长输入时旧逻辑把 1 个 partial pick 插在 3 个 full 候选之后（约 rank4），可能挤掉一个正确 full 句。

### 消融（D05 naturalOnly 600）

| 变体 | Top1 | Top5 | rankCost | oracle64 | p50/p95 ms |
|---|---:|---:|---:|---:|---:|
| baseline（earlySlot=1, earlyAfterFull=3） | 39.7% | 59.0% | 4.650 | 471/600 | 4.10/8.54（单次参考） |
| **earlySlot=1, earlyAfterFull=5（选中）** | **39.7%** | **60.7%** | **4.557** | **471/600** | **3.73/7.92** |
| earlySlot=0 | 39.7% | 60.7% | 4.557 | 471/600 | 3.38/7.42 |
| partialQuota=0 | 39.7% | 60.7% | 4.557 | 479/600 | 3.45/7.70 |
| beam=96/128、entries=12/16 | 39.7% | 59.0% | 4.65 | 470–472/600 | 5.8–12.8 / 11.5–20.8 |
| sentenceCorpus/Dictionary 权重 | 39.3–39.7% | 58.8–59.2% | 4.64–4.66 | 471–473/600 | 3.2–3.5 / 6.8–8.0 |

选择理由：`earlyAfterFull=5` 与两个 early-slot 变体得到相同的 Top5 +1.7pp、rankCost -0.093、oracle 不回退；但只有它保留 partial。`earlySlot=0` 会让 partial 落到 48 个 full 之后、用户不可发现；`partialQuota=0` 直接移除 partial；B/C 无 Top5 收益或延迟更差。

### after 指标与回归

| 指标 | before | after |
|---|---:|---:|
| D05 engineScored Top5 | 624/900 69.3% | **634/900 70.4%** |
| D05 naturalOnly Top5 | 354/600 59.0% | **364/600 60.7%** |
| D05 naturalOnly rankCost | 4.650 | **4.557** |
| D05 naturalOnly oracle64 | 471/600 | 471/600（不回退） |
| failureClass displayLoss | 29 | **19**（Top5 未命中 276→266） |
| typo/shorthand/mixed_pinyin Top1/Top5 | — | 全部不变 |
| v1 dev/holdout Top1/Top5/oracle/fullCoverage | — | 全部不变（v1 holdout rankCost 3.19→3.18） |

UX 可访问性：D05 natural_medium/long 中有效输入 ≥24 字符的 **289/289** 条案例，前五名全部为 full 候选，第一个 partial 在 index=5（第 6 位，rank6），仍在 8 项可见区。

## 5. 复现

```powershell
python eval/d05/dev/build_d05_dev.py
python eval/d05/dev/check_d05_dev.py
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05DevEvalTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinD05AblationTest" --console=plain
```

## 6. 验证与限制

- 全量：`:app:testDebugUnitTest` **360 tests / 0 failures / 0 errors / 4 skipped**；`:app:lintDebug` **0 errors / 26 warnings / 1 hint**。
- D05 是 dev，不是验收；本批未运行 v3，避免用验收聚合反复选优。
- natural_long 仅 11 条，长句结论不充分；来源为台湾社群文本转简体，存在地区用词/口语碎片。
- JVM 延迟不是 Android 端到端；本批未做设备/Android/JNI 工作。
- 下一批建议：按 D05 诊断继续做排序/召回/候选组织，每次只改一个机制；候选冻结后才安排 v3/新验收集验收。
