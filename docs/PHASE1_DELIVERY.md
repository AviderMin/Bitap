# Bitap 阶段一交付报告（D00–D03）

- 日期：2026-11-08（Asia/Shanghai）
- 代码基线：`54bc9f33e99fe670b4bab2e39400b7b816fc4676`（本轮开始时工作区仅新增研发文档）
- 报告范围：`docs/PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md` §11 阶段一，即 D00、D01、D02、D03
- 状态：**阶段一交付完成；阶段二 D04–D08 尚未开始**
- 说明：本轮实测与历史数据严格分开；JVM 结果不等于真机结果，未测项目逐项标明。

## 1. 结论摘要

| 任务 | 交付 | 验收结果 |
|---|---|---|
| D00 基线、能力清单、指标口径 | `docs/PHASE1_BASELINE.md`、`docs/CAPABILITY_INVENTORY.md`、README 修正、评测输出 avgTaps→rankCost | 通过（数字可追溯到命令与文件） |
| D01 新评测协议、事件集、独立验收集 | `docs/EVALUATION_PROTOCOL_V2.md`、v2 语料 dev 1,430 / validation 710、`PinyinV2EvalTest`、32 条事件序列回放 | 通过（校验器全绿；首轮基线 51.6% / 72.3% Top1/Top5） |
| D02 AI 取消/关闭/卸载与并发 | `AiEngine` 接口、串行化生命周期、`setEnabled/unload/requestUnload`、JVM 测试 11 个、设备 PSS 实测 | 部分通过：native 卸载稳定成立（1.34 GB→35–68 MB）；10 s PSS 门槛仅在部分口径达标（E068 设置页 PASS、8411 设置页超 23.7 MiB、8411 返回键盘态 PASS），不能宣称全场景稳定达标；取消生成设备端未验证 |
| D03 InputEngine 适配与候选版本 | `core/engine/InputEngine.kt`、`PinyinInputEngine`、`Candidate.compositionVersion/sessionId`、reducer 版本校验 | 通过（适配器与旧引擎逐字段一致；stale 候选无任何 ImeCommand） |

阶段一退出条件「能准确回答哪里失败、如何复现、使用什么口径」已满足：v2 dev/validation 分层指标与 95% 置信区间、32 条事件序列回放、设备证据均有记录。

## 2. D00：可信基线（`54bc9f3`）

| 项 | 值 |
|---|---|
| 单测（--rerun-tasks） | **311 tests / 0 failures / 0 errors / 1 skipped**（PinyinSealedEvalTest 默认跳过） |
| lint | **0 errors / 26 warnings / 1 hint** |
| debug APK（基线现存） | 107,796,359 B，SHA-256 `DBAE3A9F230A24A901E867623D922DC9FBE1E819463D35B29FAFDD887CDE52D4` |
| 词库 | 65,999 条（1字 5,999 / 2字 40,538 / 3字 13,272 / 4字 6,190），2,304,382 B |
| 语料 | v1 dev 132 / holdout 132 / sealed 132；check_corpus、check_no_leak 通过 |
| 设备 | `fe4ab855` 22021211RC / LineageOS 23.2 / Android 16 / 1080×2400@440dpi；Bitap 默认输入法 |

- 历史 `avgTaps` 统一改名 **`rankCost`**：命中时 rank+1，未进前五记 9；只计候选选择，不含拼音字母、展开/翻页/退格。历史 md 保留旧名，新测试输出与新文档用 rankCost。
- 能力清单明确唯一生产 AI 入口是候选条 `✦ AI`（AI 拼音词库候选）；`RewriteTone`/`rewrite` 面板无生产 UI 入口，属「未接通」，不计产品能力。

## 3. D01：评测协议 v2 与独立验收集

- 自然句来源：Tatoeba Chinese (cmn) export（CC BY 2.0 FR，89,289 行），仅作评测；生成器不调用 `PinyinEngine`，不按引擎输出选句。
- 筛选：CJK+GB2312 简体代理、中文标点、2–24 字、拼音 ≤60；剔除词典整句 288 条、v1 重叠 8 条、精确重复 95 条；近重复按字符 2-gram Jaccard 0.75 + MinHash/LSH 整簇切分，dev×validation 近重复对 0。
- 规模：**dev 1,430 条、validation 710 条**（超过规划下限 1,000/500）；10 类：natural_short/medium/long、homophone、shorthand、typo、mixed_pinyin、number_punct、proper_noun、mixed_en。
- 哈希：dev `3421ca08…53d6`、validation `9097f226…2994`；`manifest.json` 记录来源/许可/类别/生成命令，`ATTRIBUTION.md` 落实 Tatoeba、jieba、pypinyin 署名。
- 校验：`python eval/v2/check_v2_corpus.py` → `OK all checks passed`；词典整句泄漏 0、近重复隔离 0、源句追溯 1,720 条 0 mismatch。
- 治理：v2 validation 只看聚合、不用于调参；旧 sealed 降级为回归集。

### 3.1 首轮基线（未针对 v2 调参）

| 数据集 | 口径 | n | Top1 | Top5 | rankCost | fullCoverage | oracle |
|---|---|---:|---:|---:|---:|---:|---:|
| v2 dev | 引擎可评分 | 1,384 | **51.6%** [49.0,54.2] | **72.3%** [69.9,74.6] | 3.50 | 99.9% | 84.5% |
| v2 validation | 引擎可评分 | 685 | **52.4%** [48.7,56.1] | **72.7%** [69.2,75.9] | 3.49 | 99.9% | 84.8% |

- v2 的 51–52% 接近旧 sealed 48.6%，属**同源探索基线**，不是引擎回退（PinyinEngine 未改）；v1 dev/holdout 的 79.0%/66.4% 是旧小样本调参后的乐观数字。
- **D04 审查补充（重要）**：v2 自然句来自 Tatoeba，而生产 pinyin_dict.tsv 的字符 n-gram 统计正是对同一批 89,289 句生成，二者训练源重合；因此 v2 不能作为训练源独立的泛化验收。新建的 v3 CC0 独立验收集（601 条，CC0-1.0）首轮基线为 Top1 **38.8%** / Top5 **59.4%** / rankCost 4.61 / oracle 77.0%（见 [v3 首轮基线](docs/EVALUATION_V3_BASELINE.md)），明显低于 v2，说明 v2 被同源数据高估。v3 只作验收，不用于调参。
- 关键失败项：natural_long Top1 37%（v2）、typo Top1 37.5%、mixed_pinyin validation Top1 25%、mixed_en 前缀 Top1 0/40（交互项单列，不计引擎分母）。这些是 D05/D06/D07 输入。

### 3.2 事件序列集

- `eval/v2/events/event_cases.jsonl`：32 条、11 类（连续输入+选词、pending、Unicode 退格、选区/光标、字段切换、重建 controller、中英切换、标点、空格、快速键序）。
- `EventSequenceReplayTest.kt` 用真实 `KeyboardController` + `RecordingTextSession` JVM 回放：**32/32 通过**（`docs/EVALUATION_V2_EVENTS.md`）。
- 边界：不代表真机 IME 生命周期、Compose 渲染、多指触控；AI 由 D02/设备任务覆盖。

## 4. D02：AI 生命周期与资源释放

### 4.1 实现

- `AiRewriteService` 新增（全部带默认实现，旧 fake 无需改）：`isEngineLoaded`、`suspend setEnabled(false)`、`suspend unload()`、`requestUnload()`。
- 语义区分：`cancel()` 只取消当前生成；`setEnabled(false)/unload()` 取消→等待推理退出→释放 native 引擎；`close()` 永久关闭且幂等；重新开启只恢复懒加载。
- `DefaultAiRewriteService`：单一 `lifecycleMutex` 串行化注册/取消/卸载/删除/导入；teardown 在锁外 join，`epoch` 令牌防止旧 teardown 释放新请求引擎；无模型时绝不初始化 native 引擎。
- `LiteRtQwenEngine`：`engineLock + generation` 修复初始化/释放竞态；`release()` 先取得 `requestLock` 等待推理退出再释放。
- `KeyboardController` 设置开关 true→false 调 `setEnabled(false)`、false→true 调 `setEnabled(true)`；`dispose()` 调 `requestUnload()`；`BitapApp` 设置页开关也直接走 `setEnabled`。

### 4.2 JVM 测试

- `AiServiceLifecycleTest`（8 tests）：cancel 只停不释放；关闭 AI release 恰好一次；快速开/关循环不泄漏；close 幂等；deleteModel+close 并发只 release 一次；无模型不初始化；requestUnload；idle close。
- `KeyboardControllerAiLifecycleTest`（3 tests）：设置关闭触发卸载、重新开启懒加载、重复 off 不重复卸载、dispose 不 close 共享 service。

### 4.3 设备实测（E068 debug 包，详见 `docs/PHASE1_AI_RESOURCE.md`）

| 项 | 数值 |
|---|---|
| 生成前基线（键盘可见、引擎未加载） | 211,763 KB |
| 生成中峰值（pingguo→✦ AI→「苹果」） | **1,904,872 KB**（Native 1,344,003 KB） |
| 关闭 AI 后 10 s（E068 run2，设置页口径） | **280,602 KB**（基线 211,763 + 100 MiB = 313,911 KB，**PASS**） |
| 关闭 AI 后 10 s（8411，设置页口径） | 284,661 KB（基线 157,955 + 100 MiB = 260,355 KB，**超 23.7 MiB，不达标**） |
| 关闭后返回键盘态（8411，补充） | 215,247 KB（基线 157,955 + 100 MiB = 260,355 KB，**PASS**） |
| Native Heap 变化 | 1,344,003 KB → 63,778 KB（E068 10 s）；8411 峰值 1,340,863 → 10 s 67,566 / 返回键盘 35,148 KB（-95%以上） |
| 辅助 nihao | 峰值 1,804,268 KB，关闭后 10 s 260,917 KB（PASS）；模型输出被质量门禁 fallback |
| 取消生成（设备） | **未验证**：IME 候选行上移导致 uiautomator/坐标点击无法稳定命中「取消 AI」；JVM 已覆盖取消语义 |

结论（必须分口径）：D02 的 **native 引擎卸载在设备上稳定成立**（Native Heap 1.34 GB → 约 35–68 MB，AI 生成可正常触发）。但「关闭 AI 后 10 秒 PSS 回到基线+100 MiB」**只在部分采样口径达标**：E068 run2 设置页口径达标，final 8411 设置页口径超出 23.7 MiB，8411 返回键盘态达标。不能宣称该门槛在所有采样状态稳定达标；需在 release/正式包固定场景复测后才能作为产品验收结论。峰值约 1.9 GB 的低内存风险仍在。

## 5. D03：可替换引擎与候选版本

- 新增 `core/engine/InputEngine.kt`：`InputEngine`（engineId/isReady/query/recordSelection/setLearningEnabled/clearLearning/persistSelectionWeights/close）；`QueryRequest`（sessionId、compositionVersion、rawInput、limit、segments、受限 QueryContext、shouldContinue）；`QueryResult`（sessionId、compositionVersion、candidates、segments、QueryDiagnostics：recallCount/returnedCount/elapsedNanos/cancelled/truncated）；`EngineCandidate`、`SelectionEvent`。KDoc 禁止引擎写文本、访问网络/剪贴板/任意编辑器内容。
- `PinyinInputEngine` 包装现有 `PinyinEngine`：候选顺序与 word/pinyin/frequency/consumedInputLength/isCorrection 逐字段一致；`PinyinEngine` 保留为 baseline，未改算法。
- `KeyboardController` 已接到 `InputEngine`（保留 `PinyinEngine` 兼容构造）：查询带 sessionId+compositionVersion，结果回填候选并再次校验；学习/清除/持久化全部走引擎接口。
- `Candidate` 增加 `compositionVersion`（默认 -1=legacy）与 `sessionId`；`KeyboardUiState` 增加 `compositionSessionId`。
- `KeyboardReducer` 在 CandidateTapped、Space、PendingConfirmation.Candidate/Space、标点提交路径统一校验；stale 版本/session 返回 `Reduction(state)`，不产生任何 `ImeCommand`、不写 composing。
- 部分候选只消费 `consumedInputLength` 前缀，剩余 composing 继续可编辑（nihaoma → 你好 + ma）。
- 测试：`InputEngineTest`（12 tests）逐字段对拍旧引擎；`CandidateVersionTest`（17 tests）覆盖 stale tap/space/pending/punctuation、防版本洗白、session 切换、部分消费。

## 6. 工程验证（最终工作树）

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | **356 tests / 0 failures / 0 errors / 4 skipped**（56 个测试类；跳过 = PinyinSealedEvalTest、PinyinV2EvalTest validation、PinyinV3EvalTest 默认验收、D04BaselineExportTest 默认导出） |
| `:app:lintDebug` | **0 errors / 26 warnings / 1 hint**（与基线一致） |
| `:app:assembleDebug` | BUILD SUCCESSFUL；最终 debug APK 108,812,306 B，SHA-256 `841126E455B675A67FD0BD2D5E03BF6F6C4D82CADBD8D50ADD9A30A063EB4510` |
| `python eval/check_corpus.py` | dev=132 holdout=132 total=264 errors=0 |
| `python eval/check_no_leak.py` | source-leaks=0 dictionary-whole-sentence-hits=0 |
| `python eval/v2/check_v2_corpus.py` | OK all checks passed |
| 权限 | 无 INTERNET / ACCESS_NETWORK_STATE（`scripts/verify-phase1.ps1` 内置 aapt 检查） |
| 设备 smoke（最终 8411 debug 包） | **10/10 PASS**：键盘显示、nihao→你好、退格、标点、连续输入、设置页、AI 开关关闭/重开无崩溃、密码框英文态（无中文候选） |

复现门禁：`.\scripts\verify-phase1.ps1`（串行跑单测/lint/assemble/v1+v2 语料检查/权限检查）。

## 7. 交付文件

### 文档

- `docs/PHASE1_BASELINE.md`、`docs/CAPABILITY_INVENTORY.md`
- `docs/EVALUATION_PROTOCOL_V2.md`、`docs/EVALUATION_V2_BASELINE.md`、`docs/EVALUATION_V2_EVENTS.md`
- `docs/PHASE1_DEVICE_BASELINE.md`、`docs/PHASE1_AI_RESOURCE.md`、`README.md`

### 代码

- AI：`ai/AiContract.kt`、`ai/internal/AiEngine.kt`（新）、`DefaultAiRewriteService.kt`、`LiteRtQwenEngine.kt`、`ModelStore.kt`
- 引擎：`core/engine/InputEngine.kt`（新）、`core/engine/PinyinInputEngine.kt`（新）、`core/KeyboardUiState.kt`、`core/KeyboardReducer.kt`、`ime/KeyboardController.kt`、`ui/BitapApp.kt`
- 评测：`PinyinV2EvalTest.kt`（新）、`InputEngineTest.kt`（新）、`CandidateVersionTest.kt`（新）、`qa/EventSequenceReplayTest.kt`（新）、4 个既有评测测试的 rankCost 改名
- 设备：`scripts/device-qa/`（collect_baseline/pss/set_ai/ai_resource_test/smoke）

### 评测资产

- `eval/v2/corpus/`：build_v2_corpus.py、manifest.json、ATTRIBUTION.md、dev/validation TSV
- `eval/v2/check_v2_corpus.py`、`eval/v2/events/`（event_cases.jsonl、README.md）
- `scripts/verify-phase1.ps1`（新门禁脚本）

## 8. 风险与未完成（按优先级）

1. **引擎质量仍远低于 0.2 门槛**：v2 dev Top1 51.6%、Top5 72.3% 是同源探索；训练源独立的 v3 首轮 Top1 **38.8%**、Top5 **59.4%**，与阶段目标 Top1≥75%、Top5≥90% 差距更大。natural_long、typo、mixed_pinyin、mixed_en 前缀是下一阶段首要项；D04 宿主 PoC 已完成但 Android/JNI 与设备预算未验证。
2. **AI 峰值内存约 1.9 GB**：native 引擎关闭后可卸载（1.34 GB→35–68 MB），但「10 秒内 PSS 回到基线+100 MiB」只在部分采样口径达标（8411 设置页口径超 23.7 MiB）；低内存设备仍可能被 LMK。D02 只解决「关闭释放」，未解决「生成期峰值」，该 PSS 门槛需 release/正式包固定场景复测。
3. **取消生成设备端未验证**：UI 自动化无法稳定点击「取消 AI」；JVM 单测覆盖语义，真机需人工/无障碍复核。
4. **评测数据固有噪声**：Tatoeba 翻译腔、少量共用繁体字（著）、pypinyin 多音字；按独立来源接受并在 v2 协议 §8 记录，不能按引擎输出改语料。
5. **事件集是 JVM 回放**：不代表真机 IME 生命周期、Compose 渲染、多指触控/滑行；D08/D09/D12 仍需设备专项。
6. **`PinyinInputEngine` 暂不消费 QueryContext**：合同已留受限上下文接口，D05/D06 才实现上下文收益验证（F03）。
7. **单设备单 ROM**：只有 22021211RC / Android 16 一台设备；Android 13–15、其他 ROM、折叠/分屏、TalkBack 未验收。
8. **构建警告**：Gradle 旧 DSL/variant API 弃用警告仍在；D14 单独迁移，不与引擎/交互改动混提。

## 9. 下一步建议（阶段二）

1. **D04（已启动，宿主部分完成）**：librime 1.17.0 + pinyin-simp 宿主对照、同词典对照（Rime Top1 32.15% vs 自研 51.59%）与独立 v3 验收集（自研 Top1 38.8%）已完成；结论暂保留自研默认（[ADR 0002](docs/adr/0002-input-engine-d04.md)）。剩余 Android/JNI、同机时延/PSS/APK 增量、消费范围/取消契约与独立验收。
2. **D05**：获选引擎的召回、语言模型、分段排序；以 v2 dev 调参、v2 validation 验收；重点 natural_long、typo、mixed_pinyin。
3. **D06/D07**：错拼图与混排 tokenizer；D08 触控事件回放与双拇指试打。
4. 每个类别报告 Top1/Top5/rankCost/oracle 与回退；任何类别下降 ≥3pp 或正确性回归必须解释并人工审阅。

## 10. 复现命令

```powershell
# 阶段一总门禁（单测 --rerun-tasks + lint + assembleDebug + v1/v2 语料检查 + 权限）
.\scripts\verify-phase1.ps1

# 分项
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:lintDebug
python eval/check_corpus.py
python eval/check_no_leak.py
python eval/v2/check_v2_corpus.py

# v2 dev 评测（默认只跑 dev）
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest.evaluateV2DevCorpus" --rerun-tasks
# v2 validation（仅发布验收；聚合 only）
$env:PINYIN_V2_VALIDATION='1'
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest.evaluateV2ValidationCorpus" --rerun-tasks
```
