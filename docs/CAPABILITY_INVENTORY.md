# Bitap 能力清单（唯一版本）

- 日期：2026-11-08
- 适用版本：0.1.0（基线 commit `54bc9f3`）
- 维护规则：本文件是 Bitap 能力的**唯一清单**；其他文档、宣传文案、演示脚本里的功能描述与本文件冲突时，以本文件为准。新增/移除能力必须同步更新本文件。
- 判定依据：以当前生产源码中的**用户可达入口**为准；没有生产入口的代码只算半成品，不算产品能力。

## 0. 判定口径

| 状态 | 含义 | 判定标准 |
|---|---|---|
| **已发布** | 0.1 默认交付、用户可从正常界面到达、无需额外隐藏条件、可作为产品承诺 | 生产 UI 有入口 + 相关回归测试/文档 |
| **实验** | 有生产入口，但依赖额外条件（如外部模型）、质量/资源未达到承诺，或仅作为可选增强 | 生产入口存在，但不得计入基础体验承诺；需标注限制与后续任务 |
| **未接通** | 代码、状态机或服务存在，但生产 UI 没有入口、没有触发路径 | 不计入产品能力；只能作为开发资产 |

依据研发报告 F06：生产 AI 入口只有拼音词库候选；`RewriteTone` / rewrite 面板不是生产入口。

## 1. AI 专项结论（先看这里）

- **唯一生产 AI 入口**：候选条右侧的 `✦ AI`，即「AI 拼音词库候选」。它读取当前拼音（或光标前/选中的拼音 token）与受限上下文，调用本地 Qwen3 0.6B 生成中文候选词；用户点击候选后才上屏，永不自动触发、永不自动发送。
  - 入口：`app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt:105-115`（语义定义）、`KeyboardScreen.kt:430-459`（`✦ AI` 按钮，loading 时同一入口变「取消 AI」）
  - 状态机：`KeyboardReducer.kt:15-24`（AI 词库提示语）、`KeyboardReducer.kt:660-790`（AI 候选相关分支）
  - 控制器：`ime/KeyboardController.kt:330-390`（`startAiCandidates`，含原始上下文替换校验）
  - 服务契约：`ai/AiContract.kt:42-63`（`suggestCandidates`）、`ai/internal/DefaultAiRewriteService.kt`
- **不是生产入口**：`RewriteTone`（润色/精简/正式/译为英文）、`AiPanelOpened` / `AiToneChosen` / `AiInsert` / `AiDelta` / `AiCompleted` 等改写面板状态机，以及 `AiRewriteService.rewrite()` 的调用路径。
  - 证据：全 `app/src/main` 中 `AiPanelOpened` 只出现在 `KeyboardReducer` / `KeyboardUiState` / `KeyboardController` 的分发与处理里，没有任何生产 UI 会 dispatch 它；`KeyboardScreen` 没有任何 AI 面板/改写组件。`AiToneChosen` 的处理分支在 `KeyboardController.kt:455-484`，但入口不可达。
  - 结论：0.1 的「AI 改写/润色」是历史代码资产（文档 `docs/08-delivery.md` 中的「润色」记录属于早期版本），当前不可用，不是产品能力。

## 2. 已发布

| 能力 | 生产入口 | 代码/证据 | 边界与已知限制 |
|---|---|---|---|
| **中文拼音候选（全拼 / 简拼 / 混拼）** | 字母键盘输入 → 候选条；`ComposingStrip` 显示拼音 | `core/PinyinEngine.kt:102-140`（search、lattice、shorthand/mixed 合流）、`core/KeyboardReducer.kt`（`AppendInput` / `ComposingUpdated`）、`ime/KeyboardController.kt`（异步查询与版本防护） | 长句、错拼、简拼泛化弱：sealed 引擎可评分 top1 51/105（48.6%）；不承诺任意整句首选正确 |
| **分段选词 / 前缀候选** | 长输入时候选可只消费前缀，剩余继续可编辑 | `core/PinyinEngine.kt`（partial/lattice 路径、`consumedInputLength`）、`core/KeyboardUiState.kt:250-263`（`Candidate.consumedInputLength`） | 部分长句仍不生成正确路径（`dev-long_segment-67` 等），见 `docs/LONG_SEGMENT_ANALYSIS.md` |
| **轻量错拼纠错（受限）** | 候选条中带「纠错」标记的候选；用户可正常点选 | `core/PinyinEngine.kt:508/1053`（`collectCorrections`）、`PinyinEngine.kt:1105`（`isCorrection=true`）、`KeyboardScreen.kt:367-380`（纠错标签） | 只覆盖长度 2–12 的查询、触发条件受限；不使用触点坐标/邻键距离；不保证所有错拼可召回（F02）。按「已发布但受限」对待，不能宣传为完整纠错 |
| **英文 QWERTY** | 键盘字母区；`Shift` 切换大小写 | `ime/KeyboardScreen.kt`（LetterLayout / Shift）、`core/KeyboardReducer.kt`（Shift/输入分支） | 无英文预测、联想、自动纠错；仅基础 QWERTY |
| **手动中英文切换** | 键盘语言切换键；有 composing 时先提交 raw composing | `core/KeyboardReducer.kt:59-80`（`toggleLanguage`）、`KeyboardScreen.kt`（语言切换键） | 仅手动切换；自然连续中英混排未交付 |
| **数字与符号输入** | 数字/符号层（`KeyboardLayer.Numbers` / `Symbols`）；数字输入类型自动切换数字键盘 | `ime/KeyboardScreen.kt`（NumericLayout / SymbolLayout）、`core/KeyboardUiState.kt`（`NumericInputMode` / `NumericCapabilities`） | 最近符号、表情、剪贴板历史未实现 |
| **编辑基础：Unicode 退格 / 空格 / 回车 / 标点 / 选区同步** | 键盘功能键；`Backspace` / `Space` / `Enter`；标点智能配对 | `core/TextSession.kt:8-89`（Unicode grapheme 删除）、`core/KeyboardReducer.kt`（`backspace` / `space` / `enter`）、`TextSession.kt`（selection） | 无空格拖动光标、长按删除加速、剪贴板操作；删除按用户可见字符（grapheme）而非 UTF-16 码元 |
| **本地候选学习（用户词库）** | 选词后自动记忆（可关闭）；设置页「清除本地学习词库」 | `core/UserLexiconStore.kt:7-24`（仅存拼音/词/次数/source）、`core/PinyinEngine.kt:1163`（`recordSelection`）、`PinyinEngine.kt:1605`（`MAX_USER_ENTRIES=200`）、`ui/BitapApp.kt:658-670` | 最多 200 条，有衰减与清除 generation；学习价值/淘汰策略未验证（F07）；密码框不学习 |
| **主题与键盘高度** | App 主题 Miuix / Glasmio、深色模式；键盘主题纸白/墨玉/极光；高度 85%–135% 滑杆 | `ui/theme/*`、`ui/BitapApp.kt:185-235`（Appearance/KeyboardHeight）、`settings/BitapSettings.kt:14-31` | 高度只调整键盘底部留白/抬升，不改变固定键帽高度；主题不串联 App 与键盘 |
| **输入法生命周期 / 设置入口** | App 首页三步启用清单（启用键盘→设为默认→试打）、IME 服务 | `ime/BitapImeService.kt`、`ui/BitapApp.kt:352-424`（Checklist）、`AndroidManifest.xml:26-37` | 仅单型号单 ROM 验证；多 ROM/TalkBack/字体放大未验收 |
| **隐私边界** | 无网络权限；无分析 SDK；备份关闭；模型私有目录可删除 | `AndroidManifest.xml`（无 INTERNET、`allowBackup=false`）、`ai/internal/ModelStore.kt`、`docs/05-privacy-security.md` | 本地候选学习会保存主动选词与次数（不是整段输入）；不承诺「任何输入都绝不落盘」与学习行为矛盾的说法 |
| **本地模型管理** | 设置页「本地模型」导入/删除 `.litertlm`（SAF） | `ui/BitapApp.kt:698-800`（ModelSection）、`ai/internal/ModelStore.kt`、`ai/AiContract.kt:46-53` | 模型不随 APK 分发；无下载；导入无字节进度；无模型时 `✦ AI` 禁用并提示 |

## 3. 实验

| 能力 | 生产入口 | 代码/证据 | 为什么是实验 / 限制 |
|---|---|---|---|
| **AI 拼音词库候选（本地大模型，唯一生产 AI 入口）** | 候选条 `✦ AI`（需先在设置导入约 328 MiB 的 Qwen3 0.6B `.litertlm`） | `KeyboardScreen.kt:430-459`、`KeyboardController.kt:330-420`、`ai/AiContract.kt:57-58`、`ai/internal/DefaultAiRewriteService.kt` | 有生产入口且可用，但仍按「实验/可选增强」管理：依赖外部模型；D02 已让关闭 AI 真正卸载 native 引擎（设备 Native Heap 1.34 GB→35–68 MB，见 `docs/PHASE1_AI_RESOURCE.md`），但生成峰值仍约 1.9 GB，「关闭后 10s PSS 回到基线+100MiB」只在部分采样口径达标（8411 设置页口径超 23.7 MiB），需 release/正式包复测。不得计入基础输入承诺，不得在无模型设备上宣称可用 |
| **AI 原始文本触发（光标前/选中拼音 token）** | 无 composing 时点 `✦ AI`，从光标前或选区解析拼音 token 并替换 | `ai/internal/AiRawContextResolver.kt:1-68`、`KeyboardController.kt:335-395`、`ai/internal/AiRawContextReplacementTest.kt` | 属于 AI 词库候选的子路径，随主能力保持实验状态；仅接受字母/撇号形式的拼音，中文选区不走此路径（提示「当前选区不是拼音」） |

> 说明：把 AI 拼音词库候选列在「实验」不矛盾于「唯一生产 AI 入口」——「实验」描述成熟度与承诺级别，不描述是否有入口。0.1 的推荐基础体验应在关闭 AI / 无模型时完整可用。

## 4. 未接通（有代码无生产入口，不算能力）

| 能力/代码资产 | 代码位置 | 未接通证据 | 结论 |
|---|---|---|---|
| **AI 文本改写（RewriteTone：润色/精简/正式/译为英文）** | `ai/AiContract.kt:24-29`（`RewriteTone`）、`AiContract.kt:55`（`rewrite`）、`ai/internal/AiPromptBuilder.kt:27-32`、`ai/internal/DefaultAiRewriteService.kt:82-110/178-250`（改写实现） | `AiPanelOpened` / `AiToneChosen` 无任何生产 UI dispatch；`KeyboardScreen` 无改写面板组件；`KeyboardController.kt:455-484` 的处理分支不可达 | **不是生产入口，不算产品能力**。若未来需要，必须重新定义选区、预览、应用、撤销、过期检测流程（F06） |
| **AI 结果写入面板（AiInsert / AiDelta / AiCompleted / AiFailed / AiDismiss）** | `core/KeyboardUiState.kt:265-292`（`AiPanelUiState`）、`KeyboardUiState.kt:434-452`（动作）、`core/KeyboardReducer.kt:767-840`（`aiToneChosen` / `aiInsert` 等） | 同一改写面板链路，生产 UI 无入口；`AiInsert` 只在 reducer 内处理，无调用方 | 半成品开发资产，不可用 |
| **AI 候选自动插入（若有）** | — | 无实现；产品原则明确不自动插入 | 不成立 |

> 注意：`docs/08-delivery.md` §4 记录的「放入模型后『润色』返回 hello、手动插入成功」属于 0.1 早期版本的历史行为；当前生产 UI 已没有改写入口。历史记录保留不改写，但不能作为当前能力。

## 5. 未实现（不在能力清单，供路线图对照）

以下项目在研发报告中被列为未来能力或差距，当前没有可交付实现，**既不是「已发布」也不是「未接通」**：

- 自然连续中英混排（当前只有手动中英切换）。
- 英文预测/补全、下一词联想。
- 空格拖动光标、长按删除加速、撤销、最近符号、常用表情、剪贴板历史。
- 语音、手写、滑行输入。
- 九宫格、双拼、模糊音。
- 多 ROM / 折叠屏 / 分屏 / TalkBack / 字体放大等兼容性验收。
- 云端 AI、账号、联网下载模型（明确不做）。

## 6. 维护与证据

- 本清单的源码路径均相对于 `app/src/main/java/com/bitap/keyboard/`；行号以基线 `54bc9f3` 为准，重构后需同步更新。
- 历史交付记录：`docs/08-delivery.md`（0.1 交付）、`docs/FINAL_DELIVERY.md`（2026-11-07 最终交付）、`docs/INTERACTION_ACCEPTANCE.md`（AI 入口语义与显式切换验收）。
- 基线数字与命令见 [docs/PHASE1_BASELINE.md](PHASE1_BASELINE.md)。
- 后续任务：D02（AI 关闭/卸载/并发，F05）已完成 native 卸载与并发串行化；待 release/正式包在固定场景复测「关闭后 10s PSS 回到基线+100MiB」稳定达标后，再评估「AI 拼音词库候选」是否从实验升为已发布。`RewriteTone` 除非有明确产品需求与完整交互设计，否则保持未接通/不对外。
