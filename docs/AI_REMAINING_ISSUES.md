# Bitap 下一轮修复任务书（交给 AI 执行）

审查日期：2026-11-07（Asia/Shanghai）  
审查基线：`47c65d1`，审查开始时工作区干净。  
目标：先确保快速连续输入不漏字、不误提交、不多删，再提高中文候选质量。本文是代码审查与实施任务书，不代表本轮完成了真机复测。

## 给执行 AI 的指令

请先核对当前代码，按本文优先级修复仍然存在的问题；若后续提交已经解决某项，提供证据后跳过。不要重新实现已修复功能，也不要把“有候选”“没有崩溃”直接等同于日常输入体验合格。

每项交付包括：修复实现、能复现原问题的回归测试、实际验证结果、未验证项。保持无网络权限，不记录输入文本、AI 上下文或模型原始输出。优先保持现有主题和设置，避免无关重构。涉及真实输入内容保存的功能要提供明确设置与清除入口。

本轮只生成本文，不修改产品代码。建议分批提交：输入正确性 → 中文解码 → 输入框及隐私 → 候选 UI 与验收记录。

## 已经有改动的部分，不应按旧问题原样重做

| 上轮问题 | 当前代码状态 | 尚需关注 |
| --- | --- | --- |
| 删除最后一个拼音字母残留 | 已改为 FinishComposing + DeleteBackward | 真编辑框与选区变化仍需回归 |
| 标点覆盖组合文本、内部状态未清空 | 已先提交候选/原拼音，再提交标点并清理状态 | 快速按标点仍会提交原拼音，见 R03 |
| 旧候选误提交 | 已增加 candidatesFor 校验 | 失配时直接提交原拼音，见 R03 |
| 长按退格松手继续循环 | 已取消 repeatJob | 松手仍额外触发一次删除，见 R02 |
| AI 旧拼音结果串到新拼音 | 拼音变化时已 cancelAi 并递增 token | 关闭 AI、光标变化仍有漏洞，见 R07 |
| 无组合候选 | 已有 phraseCandidates 动态规划/截断搜索 | 未支持部分选词，评分和边界仍不足 |
| 候选只有八个 | 已扩至 64，并增加展开分页 | 仍是固定上限，UI 状态与空间分配待改 |
| 没有选词记忆 | 已有持久化 UserLexiconStore | 缺开关/清除入口，且无法新增可检索词条 |
| 输入框类型、回车标签未适配 | 已有 EditorInputInfo、密码禁 AI、回车标签与 onUpdateSelection | 光标内部移动及邮箱策略待改 |
| 按键声音开关不生效 | 已接入 playSoundEffect | 无需重新实现 |
| 普通按钮命中死区、无预览 | 已有行级命中、横向滑选、按压预览 | 同行双指会丢输入，见 R01 |
| AI 不可靠且无反馈 | 已有 parser、quality gate 和明确降级 | 质量门禁仍不能验证任意中文候选是否匹配拼音 |

## P1：优先修复输入正确性

### R01 同一行重叠触点会漏字

位置：`app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt`，`KeyRow`（约 549 行）。

证据：`awaitEachGesture` 只追踪 `down.id`，其他 pointer 没有独立提交路径；该轮手势结束后还要等待触点释放。仓库 `docs/QA_DEVICE_SMOKE.md` §6 已记录双指 q+w 只输入 q，结论为 PARTIAL。这是现有历史真机证据，本轮未重新操作设备。

影响：双拇指快速输入时两次触碰很容易时间重叠，即使用户没有刻意“双指同时按”，仍可能漏字。

要求：为触点分别维护命中键、按压/释放、取消和重复状态；明确定义多指输入提交顺序。避免一个触点抬起导致另一个触点丢失或被误认为长按。

验收：

- 同行 q 按下、w 按下、q 抬起、w 抬起，按既定顺序输出两个字母。
- 同行交错抬起、跨行双指、Shift 与字母重叠、删除与字母重叠均有明确且稳定结果。
- 一轮连续双拇指 100 次输入无丢字、重复提交；记录事件序列和预期，不只检查进程存活。

### R02 长按退格松手时必然再触发一次单击

位置：`KeyboardScreen.kt`，`KeyRow.stopRepeat` / `finally` / 释放后的 `onClick`（约 560–619 行）。

根因：重复触发后 `repeated=true`，但 finally 调用 stopRepeat 又设置 `repeated=false`；之后判断 `!repeated` 永远成立。因此长按已经删过字，松手还会调用一次 onClick。

要求：分开“取消计时任务”和“本轮是否已经重复”的状态；取消任务不能擦掉用于决定是否单击的事实。跨键滑动也要避免把上一键重复状态错误沿用或清除。

验收：在足够长的文本上，精确控制重复次数，触发 N 次 repeat 后松手，总删除数必须恰好 N；短按只删一次；不能用“文本删空”验证多删问题，因为空文本掩盖额外删除。

### R03 新候选尚未返回时，空格、候选点击和标点仍会把原拼音上屏

位置：`core/KeyboardReducer.kt`，`candidateTapped`（约 178 行）、`space`、`appendInput`；`ime/KeyboardController.kt`，`schedulePinyinQuery`。

根因：保留旧候选 + 30ms debounce；失配时，点击旧候选调用 commitRaw，空格和标点也回退原拼音。解决了“提交旧词”，但将错误变成“提交英文字母”。

复现：输入 ni 并得到候选，再输入 hao，未推进查询时钟就按空格；当前提交 nihao。若此时点屏幕上仍显示的旧候选，同样提交原拼音。当前 `QaInputScenarioIntegrationTest.staleCandidatesAreNotSubmittedBySpace` 将 commit:nih 当成成功，需修改验收目标。

要求：确认动作必须针对当前 composing 的最新查询结果处理，可将确认排队到查询完成；正常异步查询不要靠主线程执行昂贵解码绕过。等待时不得把陈旧候选呈现成可提交的新候选。显式“提交原拼音”作为独立用户操作保留。只有最新查询确实无匹配时，才按明确策略处理原文。

验收：用虚拟时间覆盖最后一个字母后 0/10/29ms 内按空格、点候选和点逗号；nihao 应得到你好或“你好，”，不能得到 nihao、你、nihao，。连续快速输入下一段时也不能吞掉已排队的确认。

### R04 光标或选区移动到 composing 内部时仍保留错误状态

位置：`core/KeyboardReducer.kt.selectionChanged`（约 294 行）、`ime/KeyboardController.kt.onSelectionChanged`。

根因：只要 selectionStart/End 位于组合区域内部就直接返回，未维护组合内编辑光标。输入逻辑却始终向 composing 尾部追加。选中组合区的一部分后按字母、移动到中间后退格，逻辑状态可能与编辑意图不同。

要求：选择一个完整可实现的策略：维护组合内光标与编辑位置，或在非预期选区/光标变化时安全结束组合并清理候选。识别 IME 自己发起编辑后的正常回调，不能导致每次按键都结束组合。不要仅判断“在范围内”。

验收：nihao 中间点光标、选中 ih、全选组合、拖出组合区，再分别输入和退格；编辑内容、composing、候选必须一致，且不删除其他已提交文字。用真实语义的 FakeInputConnection 或 Android 编辑框验证，而不只是记录命令字符串。

## P1：中文输入能力仍未闭环

### R05 引擎支持分隔符，但实际键盘无法构成 xi'an

位置：`core/PinyinEngine.kt.parseQuery` / `searchSegmented`；`core/KeyboardReducer.kt.appendInput` / `showLayer`；`ime/KeyboardScreen.kt` 字母/符号布局。

证据：引擎单测能直接 search("xi'an")，但字母层只接受 a-z 进入 composing；撇号被当作标点提交。键盘撇号在数字层，切层本身又先提交原拼音。因此用户无法通过当前键盘实际输入该查询。

要求：提供可达的拼音分隔输入方式，分隔符进入 composing 而非直接上屏；同时保留中英文标点的合理行为。支持分隔后的最后音节未输入完时继续候选，避免要求每一段都已精确命中才有结果。

验收：用实际按键 action 链输入 xi'an 并选择西安；退格经过分隔符正确；连续/重复分隔符有定义；xi'a 等未完成输入仍可继续，不提前提交 xi。

### R06 组合候选仍不能分段选词，且不等于成熟整句解码

位置：`core/KeyboardUiState.kt.Candidate`；`core/KeyboardReducer.kt.commitCandidate`；`core/PinyinEngine.kt.phraseCandidates`。

证据：Candidate 没有消费拼音范围；任意候选提交后 composing 全清空。phraseCandidates 虽可组合全句，但用户无法确认前半段后保留后半段修正。

要求：让候选携带 consumedInputLength 或等价边界，区分完整组合、前缀补全与部分选词。提交前缀候选后保留剩余拼音并重新查询；已选部分与剩余部分的退格行为必须定义。

同时改进解码质量：

- 当前评分是各词 ln(freq+1) 累加 + 字长奖励 − 固定分段罚分，没有转移/上下文评分；不能因为 woaini 有结果就声称整句准确。
- phraseCandidates 的最大键长固定为 12，而词库构建允许更长键；会漏掉需要长词参与的组合路径。应从词典或合理索引推导边界。
- 简拼、混拼、邻键容错目前没有完整实现；作为后续体验能力明确列出，不和上述正确性修复混为一谈。

验收：支持把连续句子分两次选完，第一次确认不得丢剩余拼音；真实词库覆盖“我爱你 / 我想吃饭 / 今天天气很好 / 明天见”和包含超过 12 个拼音字母词条的组合。评估首选命中率、前五命中率及需要点击次数，不仅断言候选非空。

## P2：AI、学习和输入框策略

### R07 关闭 AI、没有 composing 时移动光标，不会使候选请求完整失效

位置：`ime/KeyboardController.kt.onSettingsChanged` / `publishAiAvailability` / `onSelectionChanged` / `startAiCandidates`；`core/KeyboardReducer.kt.aiAvailabilityChanged` / `aiCandidateTapped`。

证据：拼音变化时会 cancelAi，已修复。但关闭 AI 时 aiAvailabilityChanged 只关闭 panel，不清空 aiCandidates、loading 或取消词库任务；没有 panel 时 controller 也不会因此 cancelAi。候选 UI 展示 aiCandidates 不检查 aiEnabled，AiCandidateTapped 也不检查可用性或候选归属。onSelectionChanged 在 composing 为空时直接返回，不能使从已提交拼音发起的请求失效。

要求：关闭 AI、切字段、上下文光标/选区变化时，使相应请求 token 和展示候选失效；提交 AI 候选校验其所属会话/拼音与当前策略。不要读取被禁止的上下文。

验收：用可控延迟 FakeAiService，在请求中关闭设置，再返回 Completed，UI 不显示也不能提交；从 rawContext 发起请求后移动光标，同样不回填旧位置候选。

### R08 已持久化学习，但无控制入口；AI 新词记忆不能真正检索

位置：`core/UserLexiconStore.kt`；`core/PinyinEngine.kt.recordSelection` / `loadFromAssets` / `searchContinuous`；`ime/KeyboardController.kt.recordCandidateSelection`；设置 UI。

证据：默认保存词语和次数，clearSelectionMemory 仅有引擎 API，没有用户入口。存的是 word→count，不是 pinyin→word；AI 返回一个原词库不存在的词，记录次数后也不会加入下次查询的候选集合。持久化的组合句若没有重新被有限搜索生成，也无法恢复检索。

要求：提供学习开关与清除入口，清除应作用于正在运行的 IME 引擎而非另建对象；保存可检索的词与读音映射及必要来源。响应 IME_FLAG_NO_PERSONALIZED_LEARNING，禁止在该字段学习；密码策略继续保留。明确用户词的容量、淘汰和计数衰减。

性能：apply() 不代表调用前的编码免费。当前每次选词在 dispatch 主线程复制、排序并序列化全部词权重，loadFromAssets 中读取记忆也发生在切到 IO 之前。将加载与批量持久化移出按键路径，设置合理边界。

验收：未在内置词库的合法 AI 新词，下次输入同拼音可检索且重启仍在；关闭学习/隐私字段不写；清除后当前会话和重启均不恢复；大词表下测主线程耗时。更新 docs/00、05、08 中“不做输入学习/输入不落盘”的旧描述。

### R09 邮箱/网址禁止切中文，数字/电话只复用通用符号层

位置：`core/KeyboardUiState.kt.EditorInputInfo.allowsChineseInput` / `preferredLayer`；`core/KeyboardReducer.kt.toggleLanguage`；`ime/KeyboardScreen.kt.NumberRows`。

问题：Email/Url 不只是默认英文，而是强制不允许切中文。浏览器地址栏经常同时是中文搜索入口，会因此无法输入中文；是否出现取决于宿主实际 inputType，需按宿主确认。电话/数字框自动进入 Numbers 已实现，但还是 1–0 加大量符号的通用页，没处理 signed/decimal 等数值能力。

要求：区分“初始布局建议”和“是否硬性禁止语言切换”；URL 搜索和合理邮箱场景允许用户主动选中文。电话、整数、小数、负数使用相应布局与符号；UI 不要展示无作用的中英切换键。

验收：真实 URL 类型框可主动切中文搜索；Email 默认英文可直接输入 @ 和 .；Number/Decimal/Signed/Phone 分别验证，禁止只用拨号器自带键盘当系统 IME 证据。

### R10 AI 质量门禁只是格式过滤，未核验任意中文与拼音是否匹配

位置：`ai/internal/AiCandidateParser.kt.parseForPinyin`；`AiLexiconQualityGate.kt`；控制器另有一份较宽松 parser。

证据：parseForPinyin 拒绝回显和少数固定示例泄漏，没有通用拼音匹配验证。任意非示例中文（例如 query=nihao，raw=天气）仍可能通过；parse 也只是“包含汉字”而非全为合法候选字符。历史 `DEVICE_TEST_REPORT.md` §6 仍标记候选质量 PARTIAL、降级 PASS，不能改写成质量通过。

要求：统一解析入口；利用词典读音或可信离线读音映射检查候选与当前输入兼容，保留多音字与明确补全策略；无法验证则降级。不得依靠不断增加示例黑名单作为通用质量保证。

验收：任意错拼音中文、解释句、标签、特殊符号等不能变成可提交词；合法多音字有用例；真实模型测试分别报告可用候选率、正确率、耗时与降级率。不要先更换模型来掩盖输入链路问题。

## P2/P3：可用性与性能

### R11 候选 UI 状态、长句显示和高度控制仍需改

位置：`ime/KeyboardScreen.kt.CandidateBar` / `CandidateExpandedPanel` / `CandidateChip` / `KeyboardScreen`。

- AI 候选放在最多 64 个本地候选之后，横向滚动找 AI 结果成本很高；应保证生成后有可发现的展示位置。
- 候选横向 scrollState 未按新 composition 重置；浏览旧结果末尾后，新结果首选可能仍在屏幕外。展开分页也应按 composing/版本管理，不能只监听列表是否相等。
- 展开面板每行三个单行候选，组合长句可能溢出或截断且无法辨认；必须让用户看清将提交的内容。
- 展开候选是向上增加键盘总高度，仍保留全部字母区；需在小屏、横屏和大字体下检查编辑区域占用，必要时采用替换式候选页。
- 高度设置目前仍然只改变底部留白，键高固定 54dp。若设计确实是“抬升键盘”，应改名并解释；若叫“整体高度比例”，则实现与预期一致的尺寸调整。不要宣称已按百分比缩放键盘。
- 拼音有候选时缺少稳定可见的独立 composing 显示与原文提交入口；应能辨认当前输入并主动提交英文缩写/拼音。
- smartPunctuation 仍只存储，未被输入流程消费；选择接入并定义行为，或移除无效契约。

验收：先滚候选到末尾，再换拼音，首选回到可见区；长句能辨认并正确提交；AI 结果无需滚过几十个词；小屏横屏不裁切且仍可编辑；高度设置名称与实际效果一致。

### R12 解码缺少取消检查和规模边界，学习后每次输入更容易触发组合搜索

位置：`core/PinyinEngine.kt.searchContinuous` / `phraseCandidates` / `searchSegmented`；`ime/KeyboardController.kt.schedulePinyinQuery`。

证据：selectionWeights 非空后，即使精确/前缀候选足够，也执行 phraseCandidates；循环没有协程取消检查。控制器 cancel Job 不会自动中断普通 CPU 循环。composing 长度未设边界，搜索维护各位置 path 列表与词列表复制。

要求：设置可解释的输入/搜索预算与中间候选上限，加入有效的取消协作，避免快速输入把 Default 线程池耗在过期查询。不要仅靠 finally 不发布结果来声称计算已取消。学习权重不应无条件放大每次查询成本。

验收：真实词库 + 非空学习表，连续快速输入 20/50/100 个字母及退格；记录查询 p50/p95、取消后残留任务与主线程耗时；超长无效输入可继续删除/清空，不能卡住键盘。阈值与设备/构建条件写入报告。

## 验证与交付要求

### 测试优先级

1. R01/R02 使用触摸事件级测试；R03 使用虚拟时钟 + 延迟查询；R04 用能维护文本和选区的编辑器替身或 instrumentation。
2. R05/R06 同时覆盖引擎和实际键盘 action 链，禁止只直接调 search() 绕过 UI 不可达问题。
3. R07/R08 注入延迟 AI 和可观测持久化 store，验证取消、设置与学习限制。
4. 真实词库回归包含多句输入、同音词修正、原文提交、分段选词与连续标点。
5. 所有报告标明源码 commit、APK SHA-256、debug/release、设备和时间；静态推断、历史证据、本次实测分列。

### 构建命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

修复触摸/IME 行为后，验证最终 release/minify 包，避免只测试 debug。历史存在过缺类崩溃，需干净安装最终包确认，不可仅依赖 keep 规则静态检查。

### 本次审查的验证边界

- 已重新阅读当前生产核心、键盘手势、输入框适配、学习存储、AI 质量过滤与相关测试，以及仓库最新真机报告。
- 已确认工作区开始时干净、基线为 47c65d1。
- 本轮尝试执行 testDebugUnitTest，Gradle 启动即报 `java.io.IOException: Unable to establish loopback connection`；未执行到测试，不能把历史 150 项通过当成本次通过。
- 未在本轮安装 APK、修改手机输入内容或执行真机交互。R01 的双指结果引用仓库历史实测；R02/R03 等为当前源码可追踪的逻辑缺陷，仍应在修复前补可失败的测试。

### AI 最终回复格式

列出已完成的问题编号、修改摘要、对应测试与实际结果、仍未验证/未解决项目，以及更新后的验收报告路径。不要仅回复“已修复全部体验问题”，也不要在没有运行证据时将条目标为 PASS。
