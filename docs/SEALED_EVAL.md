# 封存验收集评估（Sealed Evaluation）

> 状态：**已执行（一次性）**，执行于候选冻结 commit `6aa15a58dae4e78ddbc4b8007190962d380e57ee`（A+B 合并，engine 与词典自此冻结）。
>
> 语料：`.tmp/sealed/pinyin_cases_sealed.tsv`，已归档为 [eval/pinyin_cases_sealed.tsv](../eval/pinyin_cases_sealed.tsv)；评测器：[PinyinSealedEvalTest.kt](../app/src/test/java/com/bitap/keyboard/core/PinyinSealedEvalTest.kt)。
>
> 封存信息：132 条，SHA-256 `d9e2d946ca131ac452654bfac66d44a21c9cf1757f59e5ed2e5a66aff3a5b987`（见 `.tmp/sealed/SEALED_MANIFEST.txt`）。

## 1. 目的与封存规则

现有 dev/holdout 已多轮用于开发和调参，不能代表“未见数据”。本封存集在候选版本冻结前完成并封存，用来做一次性的泛化验收：

1. **封存前不展示**：语料的具体句子和逐条结果不向 engine-dev、Lead 或其他成员展示；封存期只公布规模、类别分布和文件哈希。
2. **不参与调参**：engine-dev 不得读取/使用该集合；评估前不根据任何引擎输出选句或改句。评估后若查看逐条并按结果修改引擎，该集合即转为回归集，不能再宣称“未见数据”。
3. **一次性运行**：Lead 通知候选版本冻结后，用同一评测器在主工作区运行一次；报告只给聚合与类别数字，不列句子、不列逐条结果。
4. **归档**：最终评估结束后把语料与报告提交主仓库（由 Lead 在 final docs 阶段合并/提交），并在本报告注明“本集合首次使用于 commit xxx”。

## 2. 语料设计

### 2.1 规模与类别分布（不含原句）

| 类别 | 覆盖点 | 条数 |
|---|---|---:|
| long_natural | 自然长句：生活/工作/出行等 15–35 字整句，检验未收录整句的连续解码与整句首选 | 22 |
| shorthand_ambiguity | 简拼歧义：同一全声母对应 2–4 个常用词，expected 用 `|` 列多个可接受答案 | 22 |
| mixed_pinyin | 混拼：完整音节 + 后续声母组合（如“完整音节+声母”），覆盖常用双字词 | 22 |
| typo_correction | 纠错：漏字母/多字母/相邻交换/邻键各若干，expected 为自然中文，note 标注正确拼音 | 22 |
| english_abbrev | 英文缩写：中文前缀 + 句尾英文缩写/产品名（原文提交路径） | 22 |
| number_punct | 数字与标点：中文数词/金额/日期/时间、阿拉伯数字原文、撇号/空格/尾标点 | 22 |
| **合计** |  | **132** |

### 2.2 设计原则

- 全部句子为人工编写的真实手机输入场景，**不**从 `pinyin_dict.tsv`、现有 dev/holdout 或 engine 输出生成；构建脚本用 `pypinyin` 仅校验拼音拼写，不调用 `PinyinEngine`。
- 与现有 dev/holdout 的 264 条无重复：输入无重复、长句不与现有长句互相包含；长句不是词典整条词条（已脚本校验）。
- `expected` 允许多个合理答案（同音词/简拼歧义用 `|`），避免把另一个同样正确的写法判错。
- 英文缩写与阿拉伯数字条目按“中文前缀选词 + 原文提交”的交互路径处理，不强制连续拼音整句。

## 3. 评测器与指标

- 评测器：`app/src/test/java/com/bitap/keyboard/core/PinyinSealedEvalTest.kt`，只在 `PINYIN_SEALED_RUN=1` 时执行（防止普通全量测试误消费封存集）。
- 运行命令（冻结后执行一次）：

```powershell
$env:PINYIN_SEALED_RUN='1'
$env:PINYIN_SEALED_STAGE='final'
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinSealedEvalTest" --rerun-tasks --console=plain
```

- 指标：
  - top1：期望任一答案在返回候选第 1 位
  - top5：期望任一答案在返回候选前 5 位
  - avgTaps：命中时 rank+1，未进前五记 9，取平均；**这是候选页排序的模拟估算，不是设备实测按键次数**
  - p50/p95/p99：单次 `search(input, limit=8)` 的 JVM 纳秒计时（先做 24 条预热），仅作同机参考
  - 按类别：每类 n/top1/top5/avgTaps
  - 失败分类：缺词、切分、排序、交互（不计入引擎）、容错
- 输出：`build/pinyin-sealed-eval-<stage>.txt`，只含聚合与类别数字，不含句子/逐条结果。
- **avgTaps 与真机核对**：avgTaps 是 JVM 候选排序的模拟估算；真机实际选词次数受候选页布局、分页、误触、学习权重等影响。最终报告会与 device-qa 的真机抽样结果对照，以真机数据为准。

## 4. 执行记录

| 项 | 值 |
|---|---|
| 封存日期 | 2026-11-07 |
| 首次使用 commit | `6aa15a58dae4e78ddbc4b8007190962d380e57ee`（A+B；P2 交互修复不改引擎） |
| 执行时间 | 2026-11-07 23:06 Asia/Shanghai |
| 执行环境 | Windows 11 / OpenJDK 25.0.4.1（JVM 评测）、设备 22021211RC / Android 16 仅作参考 |
| 语料 SHA-256 | `d9e2d946ca131ac452654bfac66d44a21c9cf1757f59e5ed2e5a66aff3a5b987` |

## 5. 聚合结果（待填）

> 仅列聚合与类别数字，不列具体句子和逐条结果。

| 口径 | 用例数 | top1 | top5 | avgTaps |
|---|---:|---:|---:|---:|
| 全量（含交互项） | 132 | 51/132 (38.6%) | 79/132 (59.8%) | 4.61 |
| 引擎可评分 | 105 | 51/105 (48.6%) | 78/105 (74.3%) | 3.53 |

按类别（英文缩写/数字标点含交互项；括号内 engine 口径）：

| 类别 | n | top1 | top5 | avgTaps |
|---|---:|---:|---:|---:|
| long_natural 自然长句 | 22 | 4/22 (18.2%) | 12/22 (54.5%) | 5.32 |
| shorthand_ambiguity 简拼歧义 | 22 | 10/22 (45.5%) | 19/22 (86.4%) | 2.86 |
| mixed_pinyin 混拼 | 22 | 13/22 (59.1%) | 20/22 (90.9%) | 2.27 |
| typo_correction 纠错 | 22 | 11/22 (50.0%) | 12/22 (54.5%) | 4.68 |
| english_abbrev 英文缩写（交互） | 22 | 0/22 | 1/22 | 8.73 |
| number_punct 数字与标点 | 22 | 13/22 (59.1%) | 15/22 (68.2%) | 3.77（engine 13/17、15/17、2.24） |

失败原因数量（未进 top5 启发式分类）：排序=9、切分=3、容错=15、交互=26；缺词=0。

延迟（JVM 参考，含 132 次查询）：p50=1.820 ms、p95=18.311 ms、p99=23.974 ms、max=58.908 ms。

## 6. 与 dev/holdout 的对比

- 对比对象：dev/holdout 的最终 after（`build/pinyin-quality-eval-batchb-after.txt`；引擎口径 dev top1 79.0%、top5 85.7%；holdout top1 66.4%、top5 85.7%）。
- sealed（引擎口径）：top1 48.6%、top5 74.3%、avgTaps 3.53；sealed 比 dev/holdout 的 after 低约 18–30pp（top1），**差距明显，应优先相信 sealed**。
- 类别结构：sealed 的 long_natural 仅 4/22 top1、12/22 top5；shorthand 10/22、mixed 13/22、typo 11/22、number 13/17（engine 口径）。这说明 dev/holdout 的 after 数字偏乐观，仍不能宣称“真实连续输入质量达标”。
- 本集合执行后未查看逐条结果、未据此修改任何词条/规则/奖励；本报告只保留聚合与类别数字，因此仍可作为一次性的未见数据证据。

## 7. 局限

1. 132 条覆盖 6 类，每类 22 条，适合作为封存红线，但单类置信区间较宽。
2. 多答案只能枚举有限合理写法；未列出的自然表达会被记为失败。
3. 失败分类为词典/字符重叠启发式，边界处有人工判定余地。
4. avgTaps 为模拟估算，非设备实测；真机按键次数由 device-qa 抽样核对。
5. 封存集一旦在评估后被查看逐条并用于修改，只能作为回归集，不能再作为“未见数据”宣称。
6. JVM 延迟不等于真机延迟；真机性能以 P0/P1 设备记录为准。

## 8. 归档

已于 2026-11-07 将 `.tmp/sealed/pinyin_cases_sealed.tsv` 归档为 `eval/pinyin_cases_sealed.tsv`（13,587 bytes，SHA-256 与封存清单一致），并随本报告提交；首次使用 commit 为 `6aa15a58dae4e78ddbc4b8007190962d380e57ee`。
