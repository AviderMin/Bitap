# Bitap 下一阶段交付报告

版本：0.1 ｜ 日期：2026-11-07 ｜ 源码 commit：`956b5043fdfc8a891093851e445b041acd9216d8`
最终 release APK：`dist/bitap-0.1.0-demo.apk`，28,555,607 bytes，SHA-256 `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC`

本文件按用户要求的五个阶段交付：P0 真机闭环、输入质量基线与失败分类、全拼解码改进、通用简拼/混拼、容错与混输。每阶段列出实现范围、不支持范围、修改依据、开发集/保留集结果、性能变化和未完成项。详细证据见：
- 真机验收：[docs/P0_DEVICE_ACCEPTANCE.md](P0_DEVICE_ACCEPTANCE.md)
- 输入质量评测：[docs/PINYIN_QUALITY_EVAL.md](PINYIN_QUALITY_EVAL.md)
- 评测语料：`eval/pinyin_cases_dev.tsv`、`eval/pinyin_cases_holdout.tsv`

## 0. 最终状态摘要

| 项 | 值 |
| --- | --- |
| 源码 commit | `956b5043fdfc8a891093851e445b041acd9216d8` |
| 最终 APK | `dist/bitap-0.1.0-demo.apk`，28,555,607 bytes |
| APK SHA-256 | `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC` |
| 单元测试 | `:app:testDebugUnitTest` **293 tests / 0 failures / 0 errors / 0 skipped** |
| Lint | `:app:lintDebug` **0 errors / 26 warnings / 1 hint** |
| 评测语料 | dev 132 + holdout 132 = 264 条，11 类 × 12 |
| 引擎口径 top1（dev / holdout） | baseline 42.9% / 33.6% → after **70.6% / 63.9%** |
| 引擎口径 top5（dev / holdout） | baseline 59.7% / 51.3% → after **83.2% / 81.5%** |
| JVM 查询延迟 after | dev p50 0.899 / p95 3.160 ms；holdout p50 0.754 / p95 2.948 ms（同机参考） |
| 拼音词典资产 | 586,746 → 2,175,214 bytes（30,003 → 65,999 条 + Tatoeba CC BY 2.0 FR 派生字符 n-gram 统计；见 docs/LICENSE_CLEANUP.md） |
| 真机 P0 | 见 §1 与 P0 报告（最终包四项结论） |

## 1. 阶段一：P0 真机闭环验收

### 1.1 实现/验收范围

在单一操作者控制的真机 `fe4ab855`（22021211RC / Android 16 / API 36 / 1080×2400）上，针对最终 release APK 执行四项：

1. 连续两次、三次 pending 确认，包含重复拼音、标点、退格。
2. 横屏完整输入、选词、改错、展开/收起候选、旋转恢复。
3. 真实 Android InputConnection 删除家庭 emoji、肤色、国旗、组合重音。
4. AI 生成时移动光标、修改选区、关闭 AI；取消后不残留 loading、不提交旧结果。

每项记录源码 commit、APK SHA-256、设备、操作、预期、实际与证据。**本节结论以最终包报告为准，不能用 JVM 测试替代。**

### 1.2 真机结果（最终 release APK）

| # | 验收项 | 判定 | 关键结果 |
| --- | --- | --- | --- |
| 1 | 连续两次/三次 pending 确认（重复拼音、标点、退格） | **PASS** | A 序列 ni/ni/nihao + 空格 → `你好你好你好`；B 序列 2 次 pending + 逗号 + 句号 + 退格 + nihao → `你好你好，你好`；连续 3 轮一致 |
| 2 | 横屏完整流程（输入、选词、改错、展开/收起、旋转恢复） | **PASS** | jintiantianqihenhao→今天天气很好；退格后“今天天气很”，重输恢复；展开候选不挤出底部功能行、输入行可见，收起后空格可提交；旋转回竖屏文本/光标/键盘正常 |
| 3 | 真实 InputConnection 删除（Unicode 簇） | **PASS（7/7）** | 家庭 emoji 👨‍👩‍👧‍👦、肤色 👍🏽、国旗 🇨🇳、e+U+0301、普通 emoji 一次退格整簇清空；汉字删最后一个；已有选区一次清空 |
| 4 | AI 生成时移动光标/修改选区/关闭 AI | **PASS** | loading 中移光标与改选区均立即取消、20s+ 无残留无提交；loading 中“取消 AI”立即清空、23s 无旧结果；取消后键盘可继续输入 |

- 最终包：`dist/bitap-0.1.0-demo.apk`，28,555,607 bytes，SHA-256 `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC`；源码 commit `956b5043fdfc8a891093851e445b041acd9216d8`；设备安装包拉取后哈希一致。
- 单操作者：device-qa，2026-11-07 19:15–20:45 Asia/Shanghai，设备 fe4ab855；期间无其他 agent 操作设备。pre-check 的 `你好ao` 时序异常最终包 3 轮未复现。
- 仍记 GAP：AI 面板润色入口在 Via WebView 中选中文本仍提示“先输入拼音，或选中一段文字”，当前 KeyboardScreen 无独立 AI 面板入口（不影响 AI 词库候选路径）。
- 完整逐条操作/预期/实际/证据见 [docs/P0_DEVICE_ACCEPTANCE.md](P0_DEVICE_ACCEPTANCE.md)。

### 1.3 不支持/未验证范围

- AI 面板润色入口在 Via WebView 选中文本时可能提示“先输入拼音，或选中一段文字”；当前 P0 只验证 AI 词库候选路径（详见 P0 报告 GAP 项）。
- 真机验收覆盖 22021211RC 单一型号/Android 16；其他厂商 ROM 的 InputConnection 行为未验证。
- 双指同时按键、真实触觉/疲劳度未纳入本轮 P0。

## 2. 阶段二：输入质量基线与失败分类

### 2.1 实现范围

- 新建独立评测语料：dev 132 条、holdout 132 条，合计 264 条，11 类各 12 条：日常聊天、时间地点、工作沟通、未收录整句/多种表达、同音词歧义、长句和分段选词、中英文混输、数字与标点、简拼、混拼、常见错拼。
- 开发集/保留集分离：保留集不参与词条、提示示例或特殊判断；引擎开发只读取开发集失败明细，保留集只提供聚合。
- 新建 JUnit 评测器 `PinyinQualityEvalTest`：整句首选命中率、目标候选进入前五比例、完成输入所需选词/改错次数（rank+1，未命中按 9）、查询 p50/p95、按类别拆分、失败原因分类（缺词/切分/排序/交互/容错）。
- 基线在独立 git worktree（d5616bb）运行，避免被并行引擎改动污染。

### 2.2 基线（d5616bb）

| 数据集 | 口径 | top1 | top5 | avgTaps | p50 / p95 (JVM) |
| --- | --- | ---: | ---: | ---: | ---: |
| 开发集 | 全量 | 40.2% | 55.3% | 4.80 | 4.372 / 17.054 ms |
| 开发集 | 引擎可评分 | 42.9% | 59.7% | 4.48 | — |
| 保留集 | 全量 | 31.1% | 47.0% | 5.45 | 2.607 / 7.657 ms |
| 保留集 | 引擎可评分 | 33.6% | 51.3% | 5.14 | — |

失败原因（未进前五）：dev 缺词 0 / 切分 13 / 排序 11 / 交互 11 / 容错 24；holdout 缺词 0 / 切分 18 / 排序 16 / 交互 12 / 容错 24。**缺词为 0，主要瓶颈是切分与排序**，因此引擎改进优先做通用音节切分/词格与排序，而不是继续加词条。

### 2.3 不支持/未验证范围

- JVM 延迟不等于真机按键延迟；真机性能只做 P0 响应观察，未做高精度插桩。
- 同音词/多合理答案由人工判定，评测允许多个可接受答案；仍存在边界判定主观性。
- 语料为手写自然句，不覆盖所有方言/行业术语/专有名词。

## 3. 阶段三：全拼解码改进

### 3.1 实现范围

- 词典从 30,003 条扩展到 65,999 条（通用 jieba 词表 + 单字读音），补充未收录常用词（逛街、地铁站、面包店、干嘛等）；词条不含评测整句。
- 通用音节切分：从单字词读音构建音节表，覆盖完整/部分音节与 xian=xi+an 等歧义；显式分隔 `xi'an` 保持独立路径。
- 词格/DP 长句组合：按音节边界扩展词格，支持 2-8+ 个已有词条组合成长句，返回完整句与部分选词（`consumedInputLength` 正确）。
- 排序重写：删除原有 TIME_WORDS/COMMON_VERBS 等针对具体句子的硬编码奖励，统一为词频 + 词长 + 单字频率（从词典聚合字符频率）+ 词典字符二元组 + 语料字符 n-gram（`#@c/#@b/#@t`，Tatoeba CC BY 2.0 FR 派生的聚合计数）+ 数字量词通用模式。
- 学习范围：完整覆盖当前输入的候选才有选择 boost；部分候选不获得绝对优先；权重有界并周期衰减。
- 长句预算：词格 beam 有界，56-57 字符输入不再返回空候选；50-57 字符输入 p95 远低于 50ms 预算。

### 3.2 开发集和保留集结果（baseline → after）

| 数据集 | 口径 | top1 | top5 | avgTaps |
| --- | --- | ---: | ---: | ---: |
| 开发集 | 全量 | 40.2% → 65.2% | 55.3% → 81.1% | 4.80 → 2.69 |
| 开发集 | 引擎可评分 | 42.9% → **70.6%** | 59.7% → **83.2%** | 4.48 → 2.45 |
| 保留集 | 全量 | 31.1% → 58.3% | 47.0% → 76.5% | 5.45 → 3.11 |
| 保留集 | 引擎可评分 | 33.6% → **63.9%** | 51.3% → **81.5%** | 5.14 → 2.76 |

- 失败结构：dev 失败 59→25、holdout 70→31；切分失败 dev 13→1、holdout 18→4；缺词保持 0。保留集提升幅度不低于开发集，说明不是只对开发集过拟合。
- 改进依据：基线失败以切分/排序为主；本轮改进集中于音节切分、词格组合和统一评分，没有新增验收原句或手写句子映射。

### 3.3 性能与资产

- JVM 查询：dev p50 0.899 / p95 3.160 ms；holdout p50 0.754 / p95 2.948 ms（含 50-57 字符长句）。
- 词典加载+索引构建：约 1.6s（JVM，65,999 条 + shorthand + n-gram 统计解析）；设备端启动时间未精确测量。
- APK：27,642,199 → 28,555,607 bytes（+0.91 MB）；pinyin_dict.tsv 586,746 → 3,090,305 bytes。

### 3.4 不支持/未完成项

- 长整句首选仍弱：long_segment dev top1 2/12、top5 7/12；holdout top1 3/12、top5 5/12。长句候选能进前五但难以首选，下一步需要更强的句级语言模型或更大语料。
- 专有名词/人名/新词不在词典时仍可能失败（如“宋雨晴”）。
- 语料 n-gram 来源已替换为 Tatoeba Chinese (cmn) detailed sentences export（CC BY 2.0 FR，导出日期 2026-11-07，SHA-256 `DF15B916580D97DB21A4F28C4F49F47AAD7755025BC969B81762BCDE6A47EA6A`）；通过 `docs/TATOEBA_CONTRIBUTORS.txt` 列出全部 976 位作者落实署名，详见 [docs/LICENSE_CLEANUP.md](LICENSE_CLEANUP.md)。

## 4. 阶段四：通用简拼/混拼

### 4.1 支持规则（先定义后实现）

- 简拼：对词典中每个多音节词，按通用音节切分自动生成「每音节首字母」键（如 你好→nh、我们→wm、中国→zg、银行→yh）。
- 混拼：同一音节序列中，每个音节可选择完整音节或首字母，生成组合键（如 中国→zhongg、北京→beij、明天→mingt、上海→shangh）；音节数 2-4 的词生成全组合，更长词生成全简拼 + 前缀完整变体。
- 歧义：zh/ch/sh 与 z/c/s 共享首字母；单字母声母按数据；零声母（a/an/e/er/ou 等）按整音节/首字母通用规则处理；同键多词返回多个候选而不硬编码唯一答案。
- 与英文缩写冲突：保留明确「上屏原文」（`CommitRaw` / 候选条“上屏原文”）与中/英切换路径；简拼候选不会吞掉用户想输入的英文缩写（可显式原文提交）。

### 4.2 结果

- dev 简拼 top1 0/12 → 6/12，top5 0/12 → 8/12；holdout top1 0/12 → 5/12，top5 0/12 → 8/12。
- 混拼 dev top1 8/12 → 9/12、top5 12/12 → 12/12；holdout top1 6/12 → 6/12、top5 12/12 → 12/12。
- 通用索引测试使用开发时未列出的合成词/随机组合（PinyinEngineP2Test），不是固定 nh/wm 映射。

### 4.3 性能/资产

- 简拼索引在词典加载时构建，包含在约 1.6s JVM 加载时间内；单次查询仍为亚毫秒级（并入整体 p50/p95）。
- 无额外资产文件。

### 4.4 不支持/未完成项

- 高频歧义简拼排序不理想（如 sj 可能是手机/时间/世界），dev/holdout top1 约 5-6/12；下一步需词频/上下文重排而非加映射。
- 长词仅生成有限混拼变体，超长词/多音字组合覆盖有限。
- 英文缩写无自动识别，只能显式原文提交或语言切换。

## 5. 阶段五：轻量纠错与中英文混输

### 5.1 轻量纠错

支持范围：
- 整词级编辑距离 1：漏字母、多字母、相邻字母交换、常见邻键误触（替换）。
- 正确拼音候选优先；纠错候选带 `Candidate.isCorrection=true`，UI 显示“纠错”小标记；不强制替换；无正确候选或正确候选弱时才补充。
- dev 错拼 top1 0/12 → 6/12、top5 0/12 → 6/12；holdout 0/12 → 8/12、8/12。
不支持：
- 短语级错拼补全（如 woyao o→我要）未做；仅整词级。
- 相邻键权重未单独建模（所有替换同一代价）；极短词/歧义纠错排序仍有缺口。

### 5.2 中英文混输

明确支持路径：
- 中文拼音候选选中上屏 → 中/英切换输入英文名称/缩写 → 切回中文继续；或中文模式下对无法解码的字母串使用「上屏原文」提交。
- 空格/标点/回车行为：中文组合时空格提交首选、标点先提交候选再插入、英文模式直接上屏、数字/邮箱锁定布局；与现有 reducer 测试一致。
不支持：
- 无切换连续混排（同一 composing 里直接夹 English）不支持；评测中作为交互项，不纳入引擎 top1/top5 口径。
- 自动识别英文/专有名词不实现，避免误把拼音当英文。

## 6. 汇总：开发集/保留集与性能

| 指标 | baseline | after | 变化 |
| --- | ---: | ---: | ---: |
| dev engine top1 | 42.9% | 70.6% | +27.7pp |
| dev engine top5 | 59.7% | 83.2% | +23.5pp |
| holdout engine top1 | 33.6% | 63.9% | +30.3pp |
| holdout engine top5 | 51.3% | 81.5% | +30.2pp |
| dev avgTaps | 4.48 | 2.45 | -2.03 |
| holdout avgTaps | 5.14 | 2.76 | -2.38 |
| dev p50/p95 (JVM) | 4.372/17.054 ms | 0.899/3.160 ms | p50 -79.4% / p95 -81.5% |
| holdout p50/p95 (JVM) | 2.607/7.657 ms | 0.754/2.948 ms | p50 -71.1% / p95 -61.5% |

## 7. 未完成项与风险

1. 长整句首选率仍低（long_segment dev 2/12、holdout 3/12 top1）；需要更强句级 LM/更大语料，不能宣称“日常任意整句首选通过”。
2. 简拼/错拼 top1 约 5-8/12，歧义排序仍是短板；短语级错拼未实现。
3. 中英混输仅支持显式切换/原文提交，不支持无切换连续混排。
4. 真机 P0 四项已在最终包通过；唯 AI 面板润色入口在 Via WebView 为 GAP（不影响 AI 词库候选路径），见 P0 报告。
5. 设备端性能（p50/p95、启动时间、内存）未做高精度插桩；JVM 数值仅是同机参考。
6. 语料 n-gram 许可风险：已替换为 Tatoeba CC BY 2.0 FR（导出日期 2026-11-07）并落实作者署名；外部导入句的额外许可无法完全排除，详见 [docs/LICENSE_CLEANUP.md](LICENSE_CLEANUP.md)。
7. 评测语料为手写自然句，仍有人工判定边界与覆盖限制。

## 8. 复现命令

```powershell
# 全量单元测试（293 tests）
.gradlew.bat :app:testDebugUnitTest --console=plain --rerun-tasks

# 输入质量评测（baseline / after）
$env:PINYIN_EVAL_STAGE='after'; .gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --console=plain --rerun-tasks

# 最终 release APK（zipalign + debug key 签名）
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1

# 真机 P0 脚本（需设备 adb fe4ab855）
powershell -File scripts/p0-device/run_item1.ps1
powershell -File scripts/p0-device/run_item2.ps1
powershell -File scripts/p0-device/run_item3.ps1
powershell -File scripts/p0-device/run_item4.ps1
```
