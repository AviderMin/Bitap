# Bitap 第二轮剩余问题与 AI 执行任务书

日期：2026-11-07（Asia/Shanghai）  
源码基线：`eb80b54`，审查开始时工作区干净。本文替代第一轮文件中的待办状态，旧文件保留为历史记录。

## 执行说明

请核对最新代码后，按 S01–S09 顺序处理仍存在的问题。先补能够暴露问题的测试，再修复实现；每项记录源码/安装包、验证方式、结果与未验证项。保持无网络权限，不输出输入内容或 AI 原始文本到日志，不做主题重构。不要把已有单元测试通过当成端到端输入合格。

本次只交付审查文档与截图，不修改生产实现。下文“代码确认”代表可从当前执行路径确定的缺陷；“ADB 实测”代表本轮直接操作手机观察到的结果；“体验改进”不是崩溃或数据损坏缺陷。

## 本轮验证

- 已执行 `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain`：BUILD SUCCESSFUL，213 tests，0 failures / errors / skipped，26 tasks 实际执行。第一次不带 rerun 的 UP-TO-DATE 结果不作为新测试执行证据。
- ADB：设备 `fe4ab855`，model `22021211RC` / munch；默认输入法 `com.bitap.keyboard/.ime.BitapImeService`；前台 Via 浏览器；手机解锁，无需安装或清除应用数据。
- 安装版本：0.1.0，lastUpdateTime `2026-11-07 14:45:23`。通过 pm path 拉取已安装 base.apk，SHA-256 为 `33DC1AE2E40B74D58AC932DE6D9EBBF39341F7C74EC71F73EB6397A23EEC8665`，与当前 `dist/bitap-0.1.0-demo.apk` 一致。没有将相同 versionName 当成相同产物。
- 真机仅编辑 Via 地址栏里的临时测试文字，未按搜索/前往发送测试内容；测试后清空临时文字并退出编辑。
- 本轮读取 crash buffer 无输出；这仅代表本次读取未看到崩溃，不等于全面稳定性验收。

## 已有修复，避免重复施工

| 第一轮项 | 当前代码已有内容 | 本轮判断 |
| --- | --- | --- |
| R01/R02 多指与长按 | KeyRowPointerTracker，每指状态/任务；重复标志不再由 stopRepeat 清除 | 原缺陷已有针对性修复；真实手势覆盖仍需补，尤其 Shift 重叠 |
| R03 候选没回来就提交原拼音 | pendingConfirmation + 最新结果后确认 | 单次等待已修，但串行输入和切层仍有 S01/S02 |
| R04 组合区内移动光标 | 非末尾折叠光标时安全结束组合 | 原逻辑已有修复；不要恢复成仅检查范围内 |
| R05/R06 分隔与部分选词 | 长按逗号输入撇号、保留组合切层、consumedInputLength | 能力已有实现，组合边界仍有 S03/S05 |
| R07 AI 失效 | 设置关闭取消、选区更新取消、候选归属检查 | 原设置问题已有修复，raw-context 插入语义仍见 S07 |
| R08 学习 | 开关、存读音映射、限量、IO 批量保存、清除按钮 | UI 清除没有接到运行中的引擎，见 S04 |
| R09 输入框 | URL/Email 可主动切中文、数字模式、邮箱 @ | signed+decimal 丢符号，见 S08 |
| R10 AI 质量 | 内置读音字典校验，拒绝任意不匹配中文 | 原任意中文放行已修；新词/整句增益受限，见 S07 |
| R11/R12 UI 与预算 | composing 条、独立 AI 条、滚动复位、长词边界、取消检查和预算 | 原多数项已有实现；仍需性能和小屏实际测量 |

## S01 / P1：切层清空候选但不重查，确认后永久等待（ADB 实测）

位置：`core/KeyboardReducer.kt.showLayer`（约 87 行）、`space`、`commitCompositionThen`；`ime/KeyboardController.kt.dispatch` / `schedulePinyinQuery`。

操作：在 Via 地址栏用 Bitap 实际按键输入 nihao → 点击 123 → 点击空格 → 等待 → 点击数字 1。

实际：编辑框仍是带下划线的 nihao，数字 1 未进入；候选条只有原拼音。两次截图及随后 uiautomator 读取均确认编辑框为 nihao。未按前往键。

证据：[切层后等待](evidence/bitap-review-r2-layer-pending.png)、[继续按数字仍未输入](evidence/bitap-review-r2-layer-stuck.png)。

根因：showLayer 保留 composing，却清空 candidates/candidatesFor；controller 只在 composing 改变时重新查询。若原查询已经完成，就不会再产生结果。空格排入 pendingConfirmation 后，普通符号/数字遇到非空 pending 又直接返回，形成无法自行完成的等待。

要求：切层不破坏仍有效的查询结果，或明确触发同 composing 的重查；任何待确认动作都必须有可完成/可取消路径。不要用超时后提交原拼音绕过，也不要取消刚加上的分隔符跨层能力。

验收：最新查询完成后，Letters→Numbers→Symbols→Letters 各路径，空格/数字/标点/候选均能继续；不需要先删一个字母才能恢复。新增 controller 集成测试，不只测 showLayer reducer 返回值。

## S02 / P1：pendingConfirmation 没冻结输入边界，继续打字会改写确认对象或吞操作（代码确认）

位置：`KeyboardReducer.kt.appendInput`、`queueConfirmation`、`resolvePending`、`space`、`commitCompositionThen`。

根因：pending 只存 Space/Candidate(index)/Punctuation(text)，没保存目标 composing 或版本。普通字母仍追加到 composing，且保留 pending；第二次空格或标点却直接忽略。

可复现 action 链：nihao → 查询完成前按逗号 → 立即输入 ma → 新查询返回 nihaoma。当前逗号会等到 nihaoma 再提交，得到“你好吗，”一类结果，而不是“你好，”后继续 ma。nihao→空格→m 同样让空格确认对象变为 nihaom。连续逗号/句号也可能丢掉后一次操作。

要求：给确认动作绑定输入快照/版本，对后续按键有序排队并在前段确认后重放；或采用其他保序策略。不能静默丢键，也不能让旧确认操作消费新字母。Candidate(index) 不应不加校验地指向另一版候选相同索引；列表变化后不要擅自 fallback 到首选。

验收：虚拟时钟覆盖 nihao→Space→ma、nihao→，→ma、nihao→，→。及期间退格/切中英/回车。分别验证精确文本、每段 composing、顺序和无重复提交。模拟 0/10/29ms 确认，查询完成前继续至少两个按键。

## S03 / P1：部分候选遇到标点会丢剩余拼音，空格也可能提前截断整段（代码确认）

位置：`KeyboardReducer.kt.commitCompositionThen`（约 177 行）、`resolvePending.Punctuation`、`commitCandidate`；`PinyinEngine.kt.phraseCandidates`。

根因：普通候选点击尊重 consumedInputLength；两条标点路径只拿 first().word，直接清空整个 composing，忽略消费范围。phraseCandidates 在无法组成完整输入时也返回部分路径；Space 直接 commitCandidate(first)，会只提交前缀，留下后缀，而后续标点则把后缀丢掉。

确定性测试输入：composing=nihaox，最新候选为 Candidate(你好, pinyin=nihao, consumedInputLength=5)。按逗号当前会只提交“你好，”，x 消失。该状态不需要真实词库概率即可复现。

要求：统一处理完整、补全、部分候选；标点确认不得消费候选覆盖之外的输入。明确定义“整段确认但无法完整解码”的策略：保留剩余内容供修正，或有序提交可见的未解码原文，禁止静默丢弃。UI 应能分辨部分候选和完整候选。

验收：上述固定候选在新鲜结果及 pending 结果两条路径都不丢 x；中文+未知英文缩写/错拼音后接数字、标点亦不丢字。

## S04 / P1：清除学习只清磁盘，运行中的引擎会把旧数据重新保存（代码确认）

位置：`ui/BitapApp.kt` 清除对话框（约 259 行）、`KeyboardController.clearUserLexicon`、`BitapImeService`、`PinyinEngine.clearSelectionMemory` / `persistSelectionWeights`。

证据：UI 调用 UserLexiconStore.create(context).clear()，没有调用当前 controller.clearUserLexicon，也没有通知 IME。引擎按服务生命周期持有，普通收起/重新弹出不重新 loadFromAssets。当前对话框声称“重新打开键盘后生效”，实际上旧词可能仍在；下次保存或 dispose 会把内存旧数据写回。

要求：建立 App→运行中 IME 的可靠清除机制，清理内存、存储、排队持久化及正在读取的旧快照；若无 IME 存活也能清除。给 clear/save/load 加代际或串行化，防止 IO 保存已抓取旧快照后在 clear 之后写回。不要仅改文案。

另一个容量漏洞：pruneUserEntriesLocked 只限制 userEntries 为 200，不清理 selectionCounts；persistSelectionWeights 又把不在 userEntries 的权重作为空读音条目补回，因此总保存条数并未真正限为 200。衰减方法目前也没有生产调用路径。请统一容量与实际淘汰策略。

验收：学习→在 App 清除→不杀 IME 再打开→再选词/保存→杀进程重启，旧词始终不恢复；用可控 store 验证 clear/save 并发。插入超过 200 个不同词后验证所有内存/落盘集合的实际上限。

## S05 / P2：部分选词 API 有了，但真实候选集合不能保证提供可用前缀（代码确认）

位置：`PinyinEngine.searchContinuous` / `phraseCandidates` / `searchSegmented`。

证据：只有 exact+prefix 候选不足 64 才调用 phraseCandidates；phraseCandidates 从完整位置倒序添加，满 64 立即退出，而且只保留 words.size>=2 的路径。完整句已有较多组合时，前缀可完全被挤掉；单个词的前缀选词（如先选“我”）根本不由该路径提供。显式分隔路径的所有结果仍消费整段。

要求：把整句候选和部分选词候选作为明确来源，给部分选词预留配额/独立选择入口，支持单词前缀及带分隔符输入。不要只在 synthetic fixture 恰好不足 64 个时验证“分段可用”。

验收：真实词库里给出一个存在超过 64 个整句路径的输入，仍可先选一个词再选余下；支持先选“我”；显式分隔符也能部分选择，消费长度按原始输入坐标计算。

## S06 / P2：等待查询后自动提交的词不进入学习（代码确认）

位置：`KeyboardController.recordCandidateSelection`（约 212 行）、`schedulePinyinQuery`。

根因：pending 的最终 CommitText 在处理 ComposingUpdated 时产生；学习方法只识别 CandidateTapped/AiCandidateTapped/Space，不识别本次完成的 pending。用户同样按空格选首词，慢一点就学习，快一点变成等待确认就不学习。

要求：让 Reduction 或提交事件带上实际选中候选、读音、来源与消费范围，学习基于真实完成的候选提交。不要学习取消的确认或 raw fallback，也不要把整段剩余拼音当成前缀候选读音。

验收：同一候选直接确认与延迟确认计数各增加一次；pending 被取消不增加；partial candidate 保存正确读音，禁学习字段始终不写。

## S07 / P2：AI 原文上下文候选没有替换范围，新词扩展也被内置词表限制

位置：`KeyboardController.startAiCandidates`、`KeyboardReducer.aiCandidatesUpdated` / `aiCandidateTapped`；`ai/internal/AiPinyinDictionary.matches`。

代码确认：composing 为空时可把 rawContext 中已提交的纯拼音作为查询，但 aiCandidatesFor 只保存空 composing，不保留原拼音范围。点结果时只 CommitText(word)，不替换已提交拼音（未选中的情况下），会变成 nihao你好。学习读音也因 aiCandidatesFor/composing 都为空而丢失。这条路径应限制为选区，或保存并验证可替换范围；不能操作用户之后移动过的光标。

能力限制：当前质量门禁要求候选存在于内置 wordToPinyins 映射，因此真正不在内置词库的新词和组合整句会被拒绝。安全过滤已有改进，但这使 AI 很难提供相对本地词库的增益。请明示限制，或用可信的离线字符/多音字读音组合验证扩展；不要恢复为任意中文都放行。

验收：已提交拼音、选中拼音、有前文的拼音各自定义清晰，点候选不重复原文；原文变化时不得替换错误范围；真实模型单独统计可用率、拼音正确率和相对本地候选新增率。

## S08 / P2：数值标志被当成互斥模式，signed+decimal 丢正负号（代码确认）

位置：`core/KeyboardUiState.kt.EditorInputInfo.numericMode` / `numericSymbols`；`ime/KeyboardScreen.kt.NumericLayout` / `FunctionRow`。

根因：同时带 TYPE_NUMBER_FLAG_DECIMAL 和 TYPE_NUMBER_FLAG_SIGNED 时，when 先返回 Decimal，numericSymbols 只有点，没有 +/-。数值能力应可组合。此外 numeric 布局仍允许通过更多符号页进入字母；输入框可能拒绝这些键，造成无效交互。TYPE_CLASS_DATETIME 也统一当整数，无日期/时间分隔能力。

要求：使用独立能力标志合并允许符号，至少保证负小数可直接输入；数字、电话、日期时间布局分别定义。移除或解释会切到不可用字符的入口。

验收：同时开启两个 flag 输入 -12.5，无需绕多层键盘；单独 integer/signed/decimal/phone 均正常；日期时间若暂不支持，在文档明确，不宣称已完整适配。

## S09 / P2/P3：剩余产品体验和验收缺口

这些项需要真实使用测量，不能直接当成已证实的崩溃 bug：

- 输入连续长句目前仍为静态词频+分段惩罚，没有上下文转移评分、简拼/混拼与常见错键容错。应建立固定真实语料，报告首选/前五命中率和纠正次数，再决定如何改引擎。
- 高度控制仍只增加底部留白；本轮未以此认定 bug。若设置已改为“抬升”，保留；若仍宣称百分比整体缩放，应统一名称、说明和实际效果。
- composing 条出现、展开候选页和 AI 条会增加窗口高度，需实测小屏/横屏/大字体的可用编辑区。不能只看 1080×2400 竖屏截图。
- 多指 tracker 单测通过并不等于真实 Compose 触摸链正确。用重叠触点、反序抬起、Shift+字母、长按删除+字母验证；当前按释放顺序提交，需要确认符合预期并防止功能键触发布局重组吞其他触点。
- TextSession.deleteBackward 只按代理对处理 Unicode，家庭 emoji、肤色修饰和组合字符可能被拆开。为显示字符边界添加真实编辑框测试。
- QA_REPORT 已更新顶部 213 项，但下方仍有“无设备/无模型”、旧候选上限与旧验收状态。将历史记录明确标日期，并添加与最终源码、APK 对应的最新汇总，避免读者把旧状态当现状。

## 必须补的跨模块回归

1. 查询完成后切层再确认（S01，已实机复现）。
2. pending 期间继续字母、第二次标点、退格和回车（S02）。
3. partial candidate + 标点/数字，分别测试即时与延迟结果（S03）。
4. 设置 UI 清除到活跃 IME，再持久化和重启（S04）。
5. 真实词库下分段候选不被整句 64 个结果挤掉（S05）。
6. 立即确认和等待结果确认产生相同学习效果（S06）。
7. raw-context AI 结果替换和 signed+decimal 组合标志（S07/S08）。

优先使用虚拟时钟、可控查询 dispatcher、可维护编辑文本和选区的 FakeTextSession、可阻塞保存的 store。测试应断言编辑框最终文本和状态一致，不仅断言命令字符串。触摸与窗口场景补 instrumentation/ADB 结果。

## 交付门槛

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

最终修复需复测 release/minify 实际安装包，并记录 SHA-256。此次审查仅重新执行了单元测试；没有重新运行 lint 和 assemble，也没有重新安装 APK。报告区分本轮 ADB 实测、源码推断和旧记录，不得宣称 S02–S08 已经全部在手机上复现。

执行 AI 最终回复：逐项列出 S 编号、修复内容、测试结果、未完成/未验证项及验收文档路径。保持第一轮历史文件，后续不要继续把已修复项当成当前待办。
