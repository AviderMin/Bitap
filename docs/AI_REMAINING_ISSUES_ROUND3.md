# Bitap 第三轮审查：交给 AI 的修复任务书

审查时间：2026-11-07（Asia/Shanghai）  
源码基线：`dac6cc2`；审查开始时 Git 工作区干净。

## 执行目标与边界

请先复核当前代码，按 T01–T07 修复仍存在的问题。优先解决丢键、确认等待不结束、学习词库读音错误，再处理 AI 状态与横屏。保持现有离线隐私边界，不做无关主题重构。

第一轮 R、第二轮 S 文件保留为历史材料，不要把已修项目重复实现。本次文档中的“实测失败”指新增 JVM 探针的实际失败；“代码确认”指可追踪执行路径；“ADB 屏幕观察”不等同于完成交互验收。

## 本轮实际验证

1. 重新检查 reducer、controller、拼音候选、学习存储、原文 AI 替换、输入框和真机验收记录。
2. 添加临时 `ReviewRound3ProbeTest` 做三项针对性验证，三项全部失败，详细期望/实际见 T01/T03/T04。该临时文件已删除，没有改动生产源码或留下失败测试；完整可复制代码附在文末。
3. 加入探针的完整测试运行：263 tests / 3 failures，失败全部来自三项新增探针。移除探针后执行 `.\gradlew.bat :app:testDebugUnitTest --rerun --console=plain`，test task 实际执行，现有 **260 tests / 0 failures / 0 errors / 0 skipped**。移除探针后第一次 FROM-CACHE 的结果未当成新执行证据。
4. ADB 已连接设备 `fe4ab855` / model `22021211RC`，查看当前 Via 界面与安装记录。安装时间 `2026-11-07 16:46:27`，版本 0.1.0；设备 base.apk 实际 SHA-256 为 `5A083A7B1D936AB9D789E51ACD73E15F09192AB02AC1025CC923790D67CA03FF`，与当前 dist demo 包一致。本轮未安装 APK、未操作输入框文字、未发送内容或改设备配置。连接中途短暂断开，后恢复。
5. 取得横屏截图：[本轮横屏画面](evidence/bitap-review-r3-landscape.png)。2400×1080 横屏下键盘几乎占满可用高度，编辑区不可见；与仓库 `QA_DEVICE_SMOKE.md` §7 的 PARTIAL/GAP 一致，不能称“横屏已通过”。
6. 未在本轮重新执行 lint、assemble 或模型推理。不把现有 260 项通过解读为新增问题不存在。

## 第二轮修复复核

| 上轮项 | 当前变化 | 本轮结论 |
| --- | --- | --- |
| S01 切层丢候选、确认挂起 | showLayer 保留有效候选，controller 切层重新调度查询 | 原单次切层问题已有修复 |
| S02 等待时继续打字 | 有 pendingComposing 和 queuedActions | 第二次 pending 与同拼音重放仍有 T01/T02 |
| S03 标点丢剩余拼音 | commitCandidateThenText 根据消费范围保留剩余组合 | 不能再按旧描述说后缀必丢；需补多次重放回归 |
| S04 清除学习恢复 | 全局 clear generation、保存检查、淘汰与衰减 | 原功能已有改动；并发原子性仍应保持测试 |
| S05 部分候选 | 单词/多词 partial 配额、显式分隔 partial | 分隔 partial 的读音元数据有 T03 |
| S06 pending 不学习 | Reduction.learnedSelection 统一记录 | 一次提交已接通；多提交仍只能承载一个学习事件，见 T01 |
| S07 AI 原文重复拼音 | raw replacement plan、重读验证、原文替换 | 带空格选区与取消状态仍有 T04/T05 |
| S08 signed+decimal | numericCapabilities 合并，输入字符过滤 | 不再重复要求支持两个 flag |
| S09 验收记录 | 最新状态与历史快照已区分，横屏明确 GAP | 应解决横屏本身，不仅继续补 PASS 文档 |

## T01 / P1：重放过程中出现第二次 pending，会清空其后排队按键

证据等级：**新增 JVM 探针实测失败**。

位置：`app/src/main/java/com/bitap/keyboard/core/KeyboardReducer.kt`，`replayQueued`（约 316–337 行）。

复现 action 链：

```text
composing = nihao，查询未返回
Space → m → a → Space → x
ComposingUpdated(nihao, [你好])
```

重放第一个 Space 后构成 ma；重放第二个 Space 会创建 ma 的 pending，x 被追加到它的 queuedActions。然而 replayQueued 最后无条件 `current.copy(queuedActions = emptyList(), pendingComposing = "")`，把 x 和新 pending 的快照删掉。

探针结果：期望新 pending 的 queuedActions 为 `[AppendInput(text=x)]`，实际 `[]`。这是明确丢键，现有 S02 测试只覆盖一层等待，没覆盖二次等待。

要求：区分本次已消费的队列和重放时新产生的队列；重放遇到新的异步确认时，保留新 pending 的边界和剩余事件，等待其查询完成再续放。不要在循环后统一清空当前状态。

同时修正学习事件模型：replayQueued 只保留一个 learnedSelection（先到者优先）。若一次 reduction 包含多个实际候选提交，后续学习事件会丢失。改为有序事件列表或等价结构，每个真实提交学习一次，取消/原文回退不学习。

验收：两次、三次 pending 串联；字母/标点/数字/退格混合；最后一个事件不丢失；最终编辑文本与输入事件顺序一致；每个候选提交学习一次。

## T02 / P1：一轮确认重放后 composing 恰好与之前相同，不会启动新查询

证据等级：**代码确认，需补 controller 回归**。

位置：`ime/KeyboardController.kt.dispatch`，只以 `reduction.state.composing != previous.composing` 判断是否调度；`KeyboardReducer.replayQueued`。

复现：composing=ni，Space 等候选；等待期间再按 n、i；ni 的结果到达后提交“你”，再重放 n、i，最终 composing 又是 ni。但 candidates/candidatesFor 已清空。这次 action 是 ComposingUpdated，前后 composing 字符串相同，controller 不再 schedulePinyinQuery，当前查询完成后没有新的结果。再按空格会再次等待而不能完成。

影响：快速输入重复音节/重复词，出现“第二个词候选不出来，要再删/补一个字母才恢复”。即使最终字符串相同，也不是同一组合会话。

要求：用组合会话/版本、查询需求或显式 effect 判断调度，不只比较字符串。确认提交后重新建立的组合必须拥有新的版本。也要防止相同字符串的旧结果跨会话回填。

验收：使用真实 controller + 虚拟时钟，ni→Space→ni 及 nihao→Space→nihao；第一次结果到达后第二段仍自动查询且可提交，不靠手工注入第二个 ComposingUpdated 绕过缺失的调度。

## T03 / P1：显式分隔 partial 候选携带整段读音，会污染用户词库

证据等级：**新增 JVM 探针实测失败**。

位置：`core/PinyinEngine.kt.searchSegmented` 最终 Candidate 映射（约 270 行）；`KeyboardReducer.commitCandidate`；`KeyboardController.recordLearnedSelection`。

数据：xi→西、an→安、xian→西安。查询 `xi'an`，部分候选“西”消费 2 个字符，Candidate.pinyin 却是 `xi'an`。探针期望 `xi`，实际 `xi'an`。

后果：用户选择“西”后会把 xian→西 写成用户词。下次查同音输入时，此错误词可作为精确候选排前面，消费整段，让原本应保留的 an 丢失。元数据错误通过学习变成可持续复现的输入错误。

要求：partial candidate 的 pinyin 只描述实际消费部分；明确是否消费后随分隔符，统一原始 composing 坐标。不要仅在 reducer 中截字符串掩盖引擎元数据错误。对旧错误学习项制定可识别的校验/迁移策略，不能盲目清空所有用户数据。

验收：xi'an 选“西”→学习→查询→重建引擎后查询，均不把“西”当完整 xian 候选；ni'hao'ma 逐段选词保存各自读音；用户词 pinyin 与对应 word 兼容。

## T04 / P2：原文 AI 选区被 trim，无法通过原文一致性检查

证据等级：**新增 JVM 探针实测失败**。

位置：`ai/internal/AiRawContextResolver.kt.resolve`；`ime/KeyboardController.kt.handleRawAiCandidateTap`。

复现：selected=`" nihao "`。resolver 先 trim，产生 plan.original=`"nihao"`；提交时 controller 用 getSelectedText() 原样读出的 `" nihao "` 比较，两者不等，拒绝替换。探针验证 original 应保留原选区，实际丢了首尾空格。

要求：分开“精确原文/替换范围”和“规范化查询”。original 保留真实选区，query 可 trim/规范化；定义外侧空白是保留还是替换，UI 与结果一致。不能关闭重新校验来让测试通过。

验收：首尾空格、换行边界、大写拼音、撇号、纯空白及选区变化。合法选区可替换且不重复原文；查询后选区变更必须拒绝。

另需加固非选区替换：当前用多次 deleteBackward 再 commitText，缺少 batch edit/范围替换及编辑器返回值处理。请用明确范围操作或可验证的批处理，避免在宿主异步输入连接中产生中间状态或错误删除。此项为实现风险，未在本轮真机复现损坏。

## T05 / P2：普通选区回调取消 AI 后，loading 状态可能永远保留

证据等级：**代码确认，需补可控 AI 回归**。

位置：`KeyboardController.onSelectionChanged` / `cancelAi`；`KeyboardReducer.selectionChanged`；`BitapImeService.onUpdateSelection`。

链路：AI 请求正在加载、composing 非空 → 编辑器回调 collapsed caret 位于组合末尾 → controller 只要 aiJob 非空就 cancelAi → reducer 认定 normalImeCallback 后原样返回，aiCandidatesLoading 仍为 true。任务已取消，也不会发出终态，UI 可一直“思考中”。

service 收到 oldSel/newSel，但只向 controller 传 newSel；controller 无法识别重复回调或自身编辑引起的正常通知。不能把每次 onUpdateSelection 都当用户改变 AI 上下文。

要求：维护有效上下文/选区版本，区分真实变化与重复/自身回调；所有取消路径同步清除对应 loading/候选/计划，状态与任务生命周期一致。普通末尾回调不应无条件取消有效 AI 请求。

验收：延迟 FakeAiService 下分别发同位置回调、正常 composing 末尾回调、真实移动与选区修改；同位置继续生成，真实变化取消并清 loading；后续仍可重试。

## T06 / P1：横屏仍无法边看文本边输入

证据等级：**本轮 ADB 屏幕观察 + 仓库已有实测 GAP**。

位置：`ime/KeyboardScreen.kt` 固定 54dp 键高、行距与多条候选/组合栏；`BitapImeService.onEvaluateFullscreenMode=false`。

观察：在当前 Via 横屏 2400×1080 中，键盘从接近状态栏一直占到底部，编辑框不可见。[截图](evidence/bitap-review-r3-landscape.png)。键帽完整不能代表输入场景可用。

要求：基于可用窗口高度和方向提供紧凑布局，合理限制候选/AI 面板；必要时在横屏使用可编辑提取区或其他明确方案。不要只缩小文字，也不要为满足截图完整牺牲编辑区。确保退格/回车/原文提交仍可达。

验收：横屏聚焦输入框时同时看到光标、至少一行输入文本和主要按键；能实际输入、修正、选词；展开候选和 AI 反馈仍可退出。小屏、字体1.3×、手势导航与三键导航分别测量窗口占比并记录。不要继续将本项写成 S09“已完成”。

## T07 / P2：Unicode 删除仍按代理对，不按用户可见字符边界

证据等级：**代码确认，未完成本轮手机输入测试**。

位置：`core/TextSession.kt.AndroidTextSession.deleteBackward`。

当前只读取前两个 UTF-16 code unit，处理单个 surrogate pair。家庭 emoji、国旗、肤色修饰、组合重音等由多个 code point 构成，一个退格可能只删后半部分，留下残余标志或人物。此前 S09 任务书已列此项，当前实现未变。

要求：采用适合 Android 的可见字符/emoji 边界策略，覆盖 ZWJ、区域指示符对、修饰符、组合音标。保留选区删除与普通中英文退格行为；避免每次删除扫描整篇文本。

验收：👨‍👩‍👧‍👦、👍🏽、🇨🇳、e+组合重音、普通单 emoji、汉字和已有选区。验证实际编辑框文本与光标，不只测 code point 数。

## 可直接复制的三项失败探针

将以下内容保存为 `app/src/test/java/com/bitap/keyboard/core/ReviewRound3ProbeTest.kt`，执行指定测试。当前基线三项均失败；修复后应全部通过。审查阶段的临时文件已移除，因此最终工作区不含该测试源文件。

```kotlin
package com.bitap.keyboard.core

import org.junit.Assert.*
import org.junit.Test

class ReviewRound3ProbeTest {
    @Test fun secondPendingKeepsItsQueuedSuffix() {
        var state = KeyboardUiState(composing = "nihao")
        for (action in listOf(
            KeyboardAction.Space,
            KeyboardAction.AppendInput("m"),
            KeyboardAction.AppendInput("a"),
            KeyboardAction.Space,
            KeyboardAction.AppendInput("x"),
        )) state = KeyboardReducer.reduce(state, action).state
        val result = KeyboardReducer.reduce(state,
            KeyboardAction.ComposingUpdated("nihao",
                listOf(Candidate("你好", "nihao", 100))))
        assertEquals("ma", result.state.composing)
        assertNotNull(result.state.pendingConfirmation)
        assertEquals(listOf(KeyboardAction.AppendInput("x")),
            result.state.queuedActions)
    }

    @Test fun separatedPartialCandidateHasOnlyItsOwnPronunciation() {
        val engine = PinyinEngine.fromLines(sequenceOf(
            "xi\t西\t100", "an\t安\t100", "xian\t西安\t1000"))
        val partial = engine.search("xi'an").first { it.word == "西" }
        assertEquals(2, partial.consumedInputLength)
        assertEquals("xi", partial.pinyin)
    }

    @Test fun selectedRawPinyinRetainsExactSelectionForValidation() {
        val plan = com.bitap.keyboard.ai.internal.AiRawContextResolver
            .resolve(" nihao ", "prefix")!!
        assertEquals(" nihao ", plan.original)
    }
}
```

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.ReviewRound3ProbeTest --console=plain
```

实际失败信息：

```text
secondPendingKeepsItsQueuedSuffix:
  expected: [AppendInput(text=x)]
  actual:   []
separatedPartialCandidateHasOnlyItsOwnPronunciation:
  expected: xi
  actual:   xi'an
selectedRawPinyinRetainsExactSelectionForValidation:
  expected: " nihao "
  actual:   "nihao"
```

## 修复交付标准

1. 保留上述探针并补 T02/T05 的 controller/虚拟时间测试。
2. 回归完整现有测试，再执行 lint、debug/release build。
3. 使用最终 release/minify 包复测连续确认、分段学习、原文替换和横屏。记录源码 commit、APK SHA-256、设备和实际结果。
4. 交付说明逐条列 T 编号、修改点、验证、仍未验证项。不以原有260项通过替代新场景测试。
5. 不将本轮未实机操作的 T01–T05/T07 写成 ADB 复现；T06 也只声称本轮截图观察与已有 GAP，没有伪造文字输入过程。
