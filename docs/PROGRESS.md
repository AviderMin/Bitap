# 当前进度与已知问题

基线：`69ad648`，2026-11-08（北京时间）。当前 HEAD 为 `71625de`（2026-11-09 工作快照，包含第7–9节的 P0 面板/长输入/诊断批次）；以下数字取自相应提交的历史记录；本轮 P0-A/P0-B/长句诊断在 `71625de` 工作树上开发，未提交 Git，验证见第10节。

## 1. 阶段状态

| 阶段 | 已完成 | 未完成/边界 |
|---|---|---|
| D00 工程基线 | 能力清单、65,999词条统计、rankCost口径梳理 | 产品体验未达标 |
| D01 评测体系 | v2、32条/11类事件回放，后续补v3审计和D05dev | v2同训练源；JVM事件不等于真实触控 |
| D02 AI生命周期 | cancel/unload/close区分、锁/epoch、懒加载、设备native释放证据 | 约1.9GB峰值、部分PSS场景超预算、设备取消未稳定验收 |
| D03 引擎接口 | InputEngine适配、候选session/version、stale防护 | native候选消费/取消/学习契约未验证 |
| D04 引擎比较 | Rime宿主、同词表、v3新来源基线和完整训练源重合审计 | Android/JNI、PSS/包体/延迟/多ROM未完成；不切换默认 |
| D05 质量诊断 | 跨来源dev扩展、失败分类、partial位置、词内统计权重消融 | 增益有限，有类别回退；没有新独立验收 |
| D05设备补充 | 2个partial与2个排序案例的操作成本 | 单设备、单指ADB、小样本，不代表总体收益 |
| D06–D15 | 尚待继续交付 | 纠错/混排/触控/编辑/学习/设备矩阵/发布等 |

## 2. 关键提交

| 提交 | 内容 |
|---|---|
| `9423fde` | D00–D03：基线、v2、AI生命周期、InputEngine |
| `f073836` | D04宿主比较、评分口径与训练源审查 |
| `08b5543` | 同词表Rime、v3新来源验收 |
| `b3f5c3e` | v3完整训练重合审计、因果措辞与严格校验 |
| `4588c12` | D05初始950条dev与失败分类 |
| `27932a2` | early partial在5个full后 |
| `9fdec89` | D05增补Wikipedia长句，dev升至1,050条 |
| `eb9669c` | 词内corpus n-gram权重0.35→0.50 |
| `69ad648` | partial位置与排序操作成本设备验证 |

## 3. 已有质量证据

### 3.1 D04宿主对照

固定v2 dev，1,384条可评分：

| 组合 | Top1 | Top5 | oracle64 |
|---|---:|---:|---:|
| 当时自研 | 714/1384，51.59% | 1001/1384，72.33% | 1169 |
| Rime1.17+pinyin-simp最小方案 | 573/1384，41.40% | 601/1384，43.42% | 611 |
| Rime最小方案+项目词表 | 445/1384，32.15% | 466/1384，33.67% | 468 |

聚合快照保留在 `eval/d04/results/`，读音转换报告在 `eval/d04/same_dictionary/`。同词表转换65,999项、flat key差异0、68项ü拼写规范化；不代表语言模型/权重使用/候选枚举完全相同。换词表的9.25pp是固定组合净效应，不能拆成可加贡献。

Rime最小方案自然句通常只有一个完整句候选，Top5口径无法代表分段选词体验；宿主更快不能换算Android更快。生产继续自研。

### 3.2 v3首次冻结基线

历史代码基线`f073836`，601条自然句；不是当前0.50权重的新验收。

| 指标 | 历史结果 |
|---|---:|
| Top1 | 233/601，38.8%，Wilson95% [35.0,42.7] |
| Top5 | 357/601，59.4%，Wilson95% [55.4,63.3] |
| rankCost | 4.61 |
| oracle64 | 463/601，77.0% |
| 完整覆盖 | 601/601，100%（不要求文字正确） |

601条中发现1条与完整Tatoeba训练源规范化精确重合，其余在规定bigram阈值下未检出；冻结内容与首次分数未改。新来源分数更低提示跨来源泛化不足，但地区用词/长度/难度也改变，不能单独证明同源训练高估的幅度。

### 3.3 最新D05排序实验

当前dev=1,050条，自然句700（short300/medium277/long123），专项350（typo100/shorthand100/mixed_pinyin100/mixed_en50）。其中33条长句拼音65–70字符超过当前64上限；排序诊断单列这些预算失败，不能在产品总表中隐去。

自然句可排序子集667条（short300/medium277/long90），固定词库/搜索预算/partial策略：

| 词内corpus权重 | Top1 | Top5 | rankCost | oracle64 |
|---|---:|---:|---:|---:|
| off=0.0 | 223/667，33.4% | 351/667，52.6% | 5.166 | 468 |
| baseline=0.35 | 235/667，35.2% | 363/667，54.4% | 5.025 | 477 |
| 当前=0.50 | 234/667，35.1% | 366/667，54.9% | 4.994 | 479 |

改进仅Top5+3条，Top1-1条；当前long子集Top1=3/90、Top5=14/90。shorthand Top1 76→79/100，Top5 98→97/100有一条回退；typo73/79保持；mixed_pinyin Top1 83→84、Top5 93保持。当前权重影响路径保留，oracle变化不能声称纯候选重排。

旧950条dev的自然句59.0%→60.7%属于之前展示机制实验，不能与现1,050条集的54.4%→54.9%直接比较，更不能据分数降低断言产品回退。

## 4. 实际候选操作成本

历史设备：22021211RC、LineageOS23.2、Android16、1080×2400@440dpi、竖屏、120Hz。测试使用ADB逐字单指注入，AI/学习关闭并清除历史学习；不是双拇指人类试用。

最新历史debug包：108,812,306B，SHA-256 `E888234FEF76E3EDE63EACBACE9ADEC959E701DC4E9F2409657BE3180F39F7EF`。它不是本轮整理重新生成包的hash。

- 2个partial案例：rank4→rank6，展开后选择从2次操作增至3次；额外1次面板滚动，前缀/剩余拼音正确，无额外退格。一个案例折叠横滑同样多1次。
- 2个按“旧排名较差、新排名前五”挑选的排序dev案例：候选浏览减少3次/2次。属于正向案例证据，有选择偏差，不能代表总体收益。
- 长候选折叠行只能完整显示1项和下一项截断；展开面板配置6项但首屏仅4完整行，第5/6位需滚动。切页保留滚动位置也可能增加操作。

这些证据要求下一批先处理候选面板几何、首屏可发现性及页间滚动，再判断partial排名折中。不能把“前8返回项”写成“8项屏幕可见”。旧截图/UI dump已经移到本地备份；已跟踪历史材料仍可在Git历史查看。

## 5. 资源和设备边界

历史AI峰值PSS=1,904,872KB；关闭后native约1.34GB降至35–68MB，但设置页和返回键盘场景的PSS门槛结果不同，不能宣称全场景达标。设备端取消生成尚未稳定验证。

更早设备记录按键到DisplayPresent p95约64.6ms、连续输入jank68%为历史工具口径；不同commit/构建/场景的数据不可合并为当前性能，jank不等于相同比例按键失效。

所有设备记录仅单型号/单ROM；Android13–15、多ROM、TalkBack、真实双拇指、长期功耗均未完成矩阵验收。

## 6. 当前明确未完成

1. 中文首选/长句质量远未达到产品目标，最新dev增益很小。
2. 长输入前缀分段已实现并有JVM/单机竖屏初步验证；64附近音节/词边界回退未优于固定64，短词边界分段能提高候选召回但操作成本与整句质量未达切换门槛，长串局部错拼和正常词误触纠错仍不足。
3. 连续中英数字混排尚未完整交付。
4. 候选面板已改为按可见行分页并固定页脚，但真机视口、字体放大、横屏几何和partial操作成本尚未复测；旧的配置6项/首屏4行问题仍作为设备待验证项。
5. AI峰值、统一PSS释放预算、取消设备验收未完成。
6. Rime Android/JNI、native消费/学习/取消、设备选型预算未完成。
7. 单词删除、光标拖动、删除加速、英文预测/联想等高频效率工具未完整交付。
8. 构建存在Kotlin/AGP旧DSL弃用警告；lint仍有26 warnings/1 hint，不能称零告警。

## 7. P0 工作树改动（已包含于 `71625de`）

这批改动在 `7e8a3aa` 工作树上完成，随后随仓库整理进入 `71625de`；代码证据为JVM单元测试与工程门禁，未连接设备、未生成release包。

### 7.1 候选面板几何与分段可发现性

- 展开面板不再固定“竖屏6项/紧凑3项一页”，改为按“48dp候选chip最小高度 + 2dp间距 = 50dp行高”计算可见行：竖屏每页4行、紧凑每页2行。面板高度预算为可见行×50dp + 固定页脚48dp + 内边距16dp，即264dp/164dp；默认字体下所有页内候选和页脚完整可见。
- 页脚（页码、看分段、上一页、下一页；紧凑模式的悬浮面板另有收起候选）移出垂直滚动区，始终可见；切换页或使用“看分段”时列表滚动位置重置到页首。大字体或超长多行候选仍可能使候选列表内部滚动，但页脚不会滚动。
- `partial`候选（`consumedInputLength`在1..composing.length-1）在折叠候选条和展开面板显示“分段”，同时是纠错候选时显示“纠错·分段”；无障碍描述为“分段候选 <word>，选择后其余拼音保留”。当首个分段候选不在当前页时，页脚提供“看分段”入口跳转。候选列表顺序和PinyinEngine排序未改动。
- 验证：`CandidatePanelGeometryTest`9项、`KeyboardP0InputFixTest`5项全部通过（JVM策略/UI编译）。设备22021211RC（LineageOS23.2/Android16/1080×2400@440dpi/竖屏）上，`nihao`展开页实测4行候选完整可见且页脚可见（第1/16页）；`woxihuan`点击“看分段”从页1跳到页13/16并显示4个带“分段”标记的候选。未做字体放大、横屏、多ROM或真实双拇指操作成本测试。

### 7.2 长输入64字符预算

- 新增`InputEngine.maxQueryInputLength`（默认`Int.MAX_VALUE`，仅`PinyinInputEngine`声明64）和`queryInputFor`：`KeyboardUiState.composing`保留完整输入，查询只发送不超过引擎预算的前缀；候选`consumedInputLength`是该前缀内的偏移，选中后未消费的尾部继续留在composing并按同一规则重新查询。删除与“上屏原文”始终按全文工作。
- 旧`PinyinEngine.search`的64字符硬上限保留：直接调用仍对>64输入返回空；生产路径仅在控制器侧分段。默认不声明预算的引擎仍收到完整composing。
- 验证：`KeyboardControllerLongInputTest`8项全部通过（JVM fake engine/TextSession）。同一设备上D05 natural_long-001（66字符拼音）输入后状态保持`len=66`且展开候选出现“分段”候选；点选首个候选后文本为“街道办事处位于心于是城北地区的先来达到南湖的被bu”，即消费64字符前缀、剩余“bu”2字符继续composing，证明长输入前缀分段链路生效。另一条70字符D05样例同样保持`len=70`并显示4个单行“分段”候选。未做连续多段选择成本、字体/横屏/多ROM验证；D05的33条65–70字符样本仍按超预算单列，不改排序分母。

### 7.3 本轮全量验证

- `scripts/verify.ps1`（2026-11-08）：RESULT PASS。单测383项 / 0 failures / 0 errors / 4 skipped（64个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,764,098B，SHA-256 `38684491D74141830F294B8BF8FFDA206A6E9A27265E3FE42E0222771F5396F8`。
- 设备验证：将同一debug APK安装到22021211RC（LineageOS23.2/Android16/1080×2400@440dpi，竖屏），`scripts/device-qa/smoke.ps1 -Tag p0-verify -SkipPassword`得到9 PASS / 0 FAIL（键盘显示、nihao候选与提交、退格、标点、连续输入、设置页、AI开关）；候选面板、66与70字符长输入证据见7.1/7.2。
- 设备限制：单机型、单ROM、竖屏、默认字体、ADB单指注入；字体放大、横屏/紧凑悬浮面板、TalkBack、多ROM、真实双拇指与完整操作成本矩阵仍未验证。设备证据目录为`.tmp/device-qa/`（忽略目录，不作为发布输入）。
- v1/v2/v3/D05语料检查、D04与v3 Python契约、文档引用158文件0断链、APK权限（无INTERNET/ACCESS_NETWORK_STATE）均通过。
## 8. 长输入分段边界诊断（D05 dev，已包含于 `71625de`）

本轮保留第7节的UI改进为固定基线，只诊断长输入分段策略，不改变生产默认：`InputEngine.QueryInputMode`提供`FIXED`/`SYLLABLE`/`WORD`三种实验策略，`PinyinEngine.queryPrefixForBoundary`负责回退到音节或多字词边界；控制器默认仍是`FIXED`。

### 8.1 指标定义

- 前缀准确：候选池前1/前8/前64中，候选汉字文本是任一期望答案的CJK前缀。
- 剩余输入一致性：按候选`consumedInputLength`截断后，剩余composing必须等于原输入`substring(consumed)`。
- 完成整句操作：贪心选第一候选与oracle选最长正确前缀两种诊断模拟；都是候选点击代理，不是设备触摸，也不是验收。步数必须拆分：成功样本的步数、失败样本退出前步数、零步失败（没有任何候选可选即退出）分开报告，不能把零步失败的低步数当成效率。
- 分母/退出：所有比例分母均为33条超预算自然句；某一步无候选、无消费或达到30步上限即提前退出；成功步数只统计最终文本精确匹配的行，失败前步数统计失败行已执行的候选点击，零步失败单列。oracle是每次选最长正确前缀的局部贪心，不是全路径全局最优。

### 8.2 D05 dev 33条超预算自然句结果

| 策略 | 平均前缀长度 | 前缀Top1 | 前缀Top8 | 前缀Top64 | 剩余一致 | 贪心成功 | 贪心成功步数 | 贪心失败前步数 | oracle成功 | oracle成功步数 | oracle失败前步数 | oracle零步失败 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| FIXED 64 | 64.00 | 5/33 | 17/33 | 23/33 | 33/33 | 1/33 | 2.00 | 2.00 | 9/33 | 2.00 | 0.71 | 10 |
| SYLLABLE 64 | 62.97 | 0/33 | 14/33 | 19/33 | 33/33 | 0/33 | 0.00 | 2.03 | 8/33 | 2.13 | 0.60 | 14 |
| WORD 64 | 62.91 | 0/33 | 14/33 | 19/33 | 33/33 | 0/33 | 0.00 | 2.03 | 8/33 | 2.13 | 0.60 | 14 |
| WORD 24 | 23.12 | 7/33 | 25/33 | 30/33 | 33/33 | 2/33 | 3.00 | 2.87 | 11/33 | 3.18 | 1.64 | 3 |
| WORD 40 | 39.61 | 4/33 | 17/33 | 28/33 | 33/33 | 2/33 | 2.00 | 1.87 | 12/33 | 2.33 | 1.10 | 5 |

### 8.3 结论与边界

- 只在64附近回退1–2个字符没有收益，反而变差：33条中有18条固定64截断不在音节边界，但SYLLABLE/WORD的前缀Top8从17降到14，说明问题不是“切在半个音节”，而是62–63字符仍会让完整候选压住更短的正确分段候选。
- 把词边界目标降到24或40能提高正确前缀出现在前64池的比例（23→30/28），但Top1仍只有7/4条，贪心整句正确仍只有2条，成功路径步数2.00–3.18、失败前步数1.55–2.87；不足以切换生产默认。
- oracle 整句成功只有9–12/33，且零步失败有3–14条：失败样本在第一步前就没有候选，不能把其平均步数0.60–1.64解释为效率。该上限也不是全局最优路径搜索，只是“每次选最长正确前缀”的局部贪心。
- 这是D05 dev可查看集诊断，不是验收；未做边界模式的设备触摸测试，未改生产默认。报告文件为`build/d05/long-input-boundary.txt`、`build/d05/long-input-boundary-sweep.txt`，测试为`PinyinLongInputBoundaryEvalTest`。

## 9. Partial 排序实验（D05 dev，已包含于 `71625de`）

按要求先固定候选池与切点：FIXED 64、默认 partial quota、默认 early partial 位置不变；先冻结入选 partial 的成员与消费范围，再仅在既定 partial 槽位重排显示顺序。

| 排序 | natural Top1 | natural Top5 | natural rankCost | 前缀Top1 | 前缀Top8 | 前缀Top64 | 命中样本平均名次 | 命中样本平均消费长度 | 贪心完成 | 成功步数 | 失败前步数 | 零步失败 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SCORE（生产默认） | 234/667 | 366/667 | 4.99 | 5/33 | 14/33 | 23/33 | 17.61 | 17.70 | 1/33 | 2.00 | 2.00 | 0 |
| LONGEST_FIRST | 234/667 | 366/667 | 4.99 | 4/33 | 11/33 | 23/33 | 23.87 | 21.26 | 1/33 | 2.00 | 2.00 | 0 |
| SHORTEST_FIRST | 234/667 | 366/667 | 4.99 | 5/33 | 16/33 | 23/33 | 13.96 | 16.26 | 1/33 | 2.00 | 2.00 | 0 |

结论：

- 先冻结入选候选：所有模式的候选文本与`consumedInputLength`集合完全一致，Top64命中均为23/33；此前把Top64 23→9/31当作排序效果是选择效应，实验已修正。
- 三种排序对D05 dev完整句Top1/Top5/rankCost完全一致（234/667、366/667、4.99），且完整句回归已增加`consumedInputLength >= requiredCoverage`的完整消费条件，无回退。
- `LONGEST_FIRST`明显变差（Top1 4/33、Top8 11/33）。`SHORTEST_FIRST`的Top8为16/33（SCORE 14/33），无Top8损失；但命中样本平均名次17.61→13.96、平均消费长度17.70→16.26，贪心整句完成仍为1/33且步骤相同，只是小幅排序改善，不足以切换默认。
- 消费长度只在命中样本上统计；未命中不再以0混入均值。生产默认仍保留`SCORE`，下一步应独立实验partial配额与选择策略/候选评分。
- 验证：`PinyinPartialRankingEvalTest`；报告`build/d05/partial-ranking.txt`。D05 dev可查看集诊断，不是验收；未做设备触摸测试。
- 控制器消费链路新增`KeyboardControllerLongInputEventTest`：用真实`KeyboardController`+`TestEditorTextSession`+生产词库输入66字符，先断言全量composing，再点候选后断言实际editor text为“候选词+精确剩余后缀”、controller composing为后缀、发出`setComposing`事件；另测`CommitRaw`写入完整原文并清空composing。该测试补足单纯偏移截取一致性的缺口。

## 10. P0 故障恢复与 D05 长句路径诊断（2026-11-09，工作树未提交）

本轮以 `71625de` 为起点，只做可靠性闭环和诊断；不改拼音排序默认、词库、评测资产或设备配置。代码证据为 JVM 测试与工程门禁，未连接设备、未生成 release 包、未提交 Git。

### 10.1 候选查询失败恢复（P0-A）

- 新增 `KeyboardAction.PinyinQueryFailed(composing, version, sessionId, message)` 与 `KeyboardUiState.candidateQueryError`。
- `KeyboardController.schedulePinyinQuery` 用 `runPinyinQueryCatchingFailure` 捕获非 `CancellationException` 异常并派发失败事件；`CancellationException` 原样抛出。派发前仍校验 `isActive`/token，reducer 再校验 version/session/composing，过期失败丢弃。
- `KeyboardReducer.pinyinQueryFailed`：存在匹配 pending 时结束等待——Space/Candidate 按 `commitRaw` 原文回退，Punctuation 提交原文+标点——随后 `replayQueued` 按既有顺序回放排队按键；无 pending 时把 `candidatesFor` 标为当前 composing（视为已完成空结果），防止后续 Space 再次永久等待。提示在下一次 composing 更新/输入/提交时清除。
- 测试：`KeyboardReducerQueryFailureTest` 8项、`KeyboardControllerQueryFailureTest` 8项（真实 `KeyboardController`+`TestEditorTextSession`+抛异常引擎）。覆盖输入→空格→查询异常→原文/状态、排队退格、排队继续输入、标点保留、无 pending 失败后 Space 仍可原文提交、stale 失败不影响新输入、`CancellationException` 不被吞。
- 未改 `PinyinEngine` 排序；未做设备/真实 IME 异常注入。

### 10.2 词库加载状态与重试（P0-B）

- 新增 `DictionaryLoadState { Loading, Ready, Failed }`、`KeyboardUiState.dictionaryState/dictionaryError`、`KeyboardAction.DictionaryStateChanged/RetryDictionaryLoad`。
- `BitapImeService.loadPinyinDictionary()` 显式发布 Loading→Ready/Failed；失败保留“词库加载失败”简短提示，重试先回 Loading，`onDestroy` 取消加载 Job，`CancellationException` rethrow。
- `KeyboardScreen` 中文候选条显示“词库加载中…”或“词库加载失败，点此重试（仍可提交原文）”；失败整行可点击派发重试；`candidateQueryError` 与词库状态共用一行，词库失败优先，Ready/英文不显示。失败时 `CommitRaw` 原文提交仍可用（未就绪查询返回空候选，raw chip 保留）。
- 测试：`KeyboardDictionaryStateTest` 7项、`KeyboardControllerDictionaryTest` 4项、`KeyboardScreenDictionaryStatusTest` 6项；加跑 `KeyboardReducerTest` 与 `KeyboardControllerQueryFailureTest` 共60项通过。
- 未做设备 UI 截图、字体放大、横屏或 TalkBack 验证。

### 10.3 D05 natural_long 路径丢失诊断（P1-C）

- 新增只读诊断 `PinyinD05LongSentencePathLossTest`：D05 dev `natural_long` 共123条，拼音≤64的90条进入分母，33条65–70超预算句单列；不改生产默认。
- 指标：Top1 3/90（3.3%，Wilson95 1.1–9.3%）；Top5 14/90（15.6%，9.5–24.4%）；rankCost 7.96（miss=9）；oracle64 22/90；fullCoverage 90/90；search8 JVM p50≈5.6ms、p95≈7.9ms（单进程诊断，不是设备时延）。
- 分层：ok-top1 3、ok-top5 11；not-generated 59（dictionary-word-missing 1、lattice-wide-not-found 58）；only-wide-budget 7（beam 6、entries 1）；generated-not-collected 2；collected-ranked-low 8；truncation-other 0。独立只读词库切分可拼出89/90，说明主要瓶颈是 production lattice 的 beam/entry 早期剪断而非词库整体缺失；58条宽预算不可见中42条 debug full-path sink 饱和，不能完全证明路径不存在。
- 结论：生产默认排序不变。下一步按单机制消融验证有界前向/后向 beam 或分层预算（预期影响最大但时延/内存风险高），再验证评分重排（8/90）与候选收集/最终池 cap（2/90）；`d05-natural_long-075` 属通用词库缺条目，不能按句写规则。报告：`build/d05/long-sentence-path-loss.txt`、`build/d05/long-sentence-path-loss-detail.tsv`。
- D05 dev 可查看集诊断，不是验收；未读 v3 冻结验收句，未做设备触摸测试。

### 10.4 本轮全量验证

- `scripts/verify.ps1`（2026-11-09）：RESULT PASS。单测417项 / 0 failures / 0 errors / 4 skipped（70个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,774,666B，SHA-256 `5BC263C9D1408B2EEC2C9E2C5BD6D916C7D173BAD23331F805186B4A46106A1F`。
- 语料检查 v1/v2/v3/D05 全部通过；文档引用164文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 设备验证：本轮未连接设备。字体放大、横屏/紧凑悬浮面板、TalkBack、多ROM、真实双拇指、查询异常真实注入与词库失败真实弹窗仍需设备矩阵验证；JVM/工程门禁不能替代产品验收。

## 11. 长句预算与局部纠错实验（2026-11-09，工作树未提交）

两项均为默认关闭的实验，生产默认完全不变；只读取 D05 dev，不读冻结验收。

### 11.1 P1-D 长输入 lattice 预算消融

- 新增 `EngineTuning.longInputBeamSize`/`longInputEntriesPerKey`（默认0=沿用 beam64/entries8，仅 queryLength>=24 时可选生效）和只读 `debugBestSegmentation`；生产默认未变（测试断言 default view == production view）。
- 报告 `build/d05/long-budget-ablation.txt`；测试 `PinyinD05LongBudgetAblationTest`。
- natural667 baseline Top1 234/667、Top5 366/667、rankCost 4.994、oracle479；natural_long baseline Top1 3/90、Top5 14/90、rankCost 7.956、oracle22/90。
- beam96/128/192、entries16/32及组合：natural_long Top1/Top5/rankCost全部零变化，oracle最多+1（beam192）；natural_long p95从6.759ms升到9.685/13.122/19.814ms（1.4–2.9x），组合最高超过56ms；natural667部分组合oracle +5~+7但伴随4~7条回退。所有变体Top5Gain=0、Top5Loss=0。
- `debugBestSegmentation`探针 exactMatch 2/90、exactNotInSearch64=0、exactTop5Miss=0；即使强行置顶，上限也只有Top1+2、Top5/oracle+0，不能作为排序策略。
- 结论：不把任何beam/entry预算作为长输入生产默认；预算层无实质收益，下一步应转向候选收集/评分层。

### 11.2 P1-E 长输入局部纠错窗口

- 新增默认关闭 `localCorrectionWindowEnabled=false`、`localCorrectionTailLimit=12`、`localCorrectionMaxWindow=12`、`localCorrectionBudget=8`；仅在显式启用时扫描尾部窗口、生成编辑距离1候选（保留正确前缀与未消费后缀、isCorrection=true），生产默认不进入。
- 合成正确性 `PinyinLocalCorrectionSyntheticTest` 5/5：末尾漏字/多字/相邻交换/替换/带未消费后缀均通过。
- D05 typo100：baseline与enabled完全相同（Top1 73/100、Top5 79/100、rankCost 2.750、oracle 81/100）；原因是D05 typo全部≤12字符，新窗口不触发。
- D05 natural667：baseline Top1 234、Top5 366、rankCost 4.994、oracle479；enabled回退Top1 230、Top5 356、rankCost 5.105、oracle469；top8额外纠错2/667，top64额外46/667（累计143个额外纠错词），Top5回退10条，rankCost +0.111。专名/英文/数字样本top8/top64无错误纠错，Top1不变。
- 结论：不开启；需D06真实长串局部错拼数据，并先解决单字词条排除与候选word去重冲突后再做单机制复测。报告 `build/d05/local-correction.txt`，测试 `PinyinD05LocalCorrectionEvalTest`。

### 11.3 实验批次全量验证（不含删除加速）

- `scripts/verify.ps1`（2026-11-09，包含第10节P0与第11.1/11.2实验，删除加速前）：RESULT PASS。单测420项 / 0 failures / 0 errors / 4 skipped（73个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,774,666B，SHA-256 `315DA15FFFA63E3D1DF5B7112FEF9B144B8963BB04836927FC4029BCDC35C0DF`。
- 语料检查 v1/v2/v3/D05 全部通过；文档引用167文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 未连接设备；第10.4节为P0批次的中间验证数字，本节为包含两项实验但未含删除加速的中间数字；含删除加速与设备验证的最终数字见12.2节。

## 12. 编辑效率：长按删除加速（2026-11-09，工作树未提交）

- `KeyboardScreen.deleteRepeatIntervalMs` 现在按长按重复次数加速删除：前8次保持55ms，第9–20次40ms，第21次起25ms；长按阈值仍为400ms，短按和原有删除语义不变。
- 纯函数与区间单调性由 `KeyboardScreenDeleteRepeatTest` 覆盖；未改变 `Backspace` 的 reducer/IME 命令，也未改变双指/多指指针归属逻辑。

### 12.1 设备验证（22021211RC）

- 设备：22021211RC / LineageOS23.2 / Android16 / 1080×2400@440dpi；ADB单指注入；安装本轮debug APK（108,774,830B，SHA-256 `B7724BD7A7E3AAEF283B4AE03759F380D0BB8F961999C9926A8D6818B22FF6CA`）。
- `scripts/device-qa/smoke.ps1 -Tag device-2026-11-09 -SkipSettings`：7 PASS / 0 FAIL（键盘显示、nihao候选与提交、退格、标点、连续输入、密码字段）。
- `scripts/device-qa/smoke.ps1 -Tag device-settings-2026-11-09 -SkipPassword`：9 PASS / 0 FAIL（增加设置页、AI开关可见与切换）。
- D05 `natural_long-001`（66字符拼音）：输入后状态`len=66`，展开面板显示“分段”候选和“第1/16页·共64项”页脚，证明长输入前缀分段和候选面板在实机可用。
- 字体缩放1.3：`nihao`展开面板4个候选与固定页脚完整可见；横屏：紧凑键盘可显示（未做完整候选输入）。截图证据在`.tmp/device-qa/d05/font-scale-1.3-02-expanded.png`与`.tmp/device-qa/landscape/landscape-keyboard2.png`。
- 删除加速：ADB同点长按2秒从`len=40`删到`len=0`；1.5秒从`len=66`删到`len=40`，重复删除生效。未做真实双拇指、长按误触与完整操作成本测量。
- 设备证据目录为`.tmp/device-qa/`（忽略目录，不作为发布输入）。

### 12.2 删除加速批次验证（中间）

- `scripts/verify.ps1`：RESULT PASS。单测421项 / 0 failures / 0 errors / 4 skipped（74个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,774,830B，SHA-256 `B7724BD7A7E3AAEF283B4AE03759F380D0BB8F961999C9926A8D6818B22FF6CA`。
- 语料检查v1/v2/v3/D05全部通过；文档引用168文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 设备结论只覆盖22021211RC单机、单ROM、ADB单指和上述场景；字体放大/横屏只抽测了界面可见性，多ROM、TalkBack、真实双拇指和完整操作成本仍未验证。

## 13. 预选词闪动修复（2026-11-09，工作树未提交）

- 原因：`KeyboardReducer.appendInput`按R03必须立即清空`candidates`，防止旧候选被误提交；但候选查询有30ms debounce加JVM/Android查询时间，UI在这段窗口内显示空列表，随后重新渲染候选行，造成每输入一个字符候选条闪一下。
- 修复在`KeyboardScreen`显示层，不改变reducer/引擎语义：用`retainedCandidates/retainedComposingLength`保留上一组候选；`shouldRetainCandidateRow`只在“新输入已有、最新查询尚未返回、无查询错误”时保留旧行；旧候选chip设为不可点击，新结果到达后原子替换；空composing、已完成空结果或查询错误时清除。展开面板与紧凑悬浮面板共用同一显示源，避免候选行/面板不同步。
- 测试：`KeyboardScreenCandidateRetentionTest`覆盖保留/替换/空结果/空输入/查询错误的判定；`CandidatePanelGeometryTest`原有候选标识与页脚逻辑不变。
- 设备抽测：22021211RC上输入`n`后候选为“嗯/那/能…”；立即按`i`瞬间仍显示上一组候选（不空白），约700ms后替换为`ni`的新候选“你好/你/泥…”。截图证据`.tmp/device-qa/flicker/01-after-n.png`、`02-after-i-immediate.png`、`03-after-i-settled.png`。
- 未覆盖：真实双拇指快速连打、超长候选/字体放大下的行高变化和TalkBack朗读；这些需要在后续设备矩阵复测。

### 13.1 候选闪动修复批次验证（中间）

- `scripts/verify.ps1`：RESULT PASS。单测422项 / 0 failures / 0 errors / 4 skipped（75个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,781,062B，SHA-256 `59CD775E8C8F1D7C35F798192F78A85A6FE55F1D31B7BC7D2AE4BD6582A39C33`。
- 语料检查 v1/v2/v3/D05 全部通过；文档引用169文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 最终合并 `smoke.ps1 -Tag device-final-2026-11-09`：**10 PASS / 0 FAIL**（键盘、候选、退格、标点、连续输入、密码、设置页、AI开关）；长输入/字体/横屏证据见12.1，候选闪动显示层抽测见13节上文。

## 14. 候选面板长短混排（2026-11-09，工作树未提交）

- 原展开面板用纵向`Column`，每个`CandidateChip`强制`fillMaxWidth`，所有候选各占一整行，短词也浪费整行宽度。
- 改为`FlowRow`按chip自身宽度自动换行：短词多项同行，长词按自身宽度换行；不再强制每个候选独占一行。面板可见行高预算保持竖屏4行/紧凑2行（264/164dp），页脚固定；每页容量改为竖屏12项/紧凑8项，长词或大字体超出时仅候选区内部滚动。
- `CandidatePanelGeometryTest`同步改为验证`candidatePanelVisibleRows`与页容量12/8，不再把页容量当作可见行数；`KeyboardScreenCandidateRetentionTest`等显示层测试保持。
- 设备抽测：22021211RC上输入`nihao`展开，12项候选以2行长短混排显示（“你好 你号 尼好 泥好 你豪 逆好 / 妮好 你耗 拟好 尼号 你浩 腻好”），页脚“第1/6页·共64项”；66字符长输入展开时短候选仍可同行、长候选按自身宽度换行（截图`.tmp/device-qa/d05/flow-panel-2026-11-09-02-expanded.png`、`flow-long-2026-11-09-02-expanded.png`）。
- 未覆盖：极端多行长词、字体放大/横屏的组合矩阵、TalkBack朗读顺序与真实双拇指操作成本。

### 14.1 长短混排后的最终验证

- `scripts/verify.ps1`：RESULT PASS。单测422项 / 0 failures / 0 errors / 4 skipped（75个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 108,778,542B，SHA-256 `831C2E0DD106C203456CC8BE5514D3E7CF6D65895C6948D2BF8E526E524AC43E`。
- 语料检查v1/v2/v3/D05全部通过；文档引用169文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 最终合并 `smoke.ps1 -Tag device-flow-final-2026-11-09`：**10 PASS / 0 FAIL**（键盘、候选、退格、标点、连续输入、密码、设置页、AI开关）；`nihao`展开12项2行长短混排、字体1.3展开12项3行、66字符长输入展开均可正常显示。

## 15. 代码审查与市场对照（2026-11-09）

本轮只读审查了`PinyinEngine.kt`（2,084行）、`KeyboardScreen.kt`（1,670行）、`BitapApp.kt`（1,059行）和`KeyboardReducer.kt`（1,029行），并与主流Android输入法的常见能力做了对照；未改变拼音排序、词库和实验默认。

### 15.1 本轮小增强：本地学习词条单项删除

- [BitapApp.kt](app/src/main/java/com/bitap/keyboard/ui/BitapApp.kt)新增“本地学习词条”设置区：从`UserLexiconStore`读取本机保存的候选词/拼音/选择次数，逐条提供删除按钮；清空全部时同步清空列表。删除通过`saveEntries`持久化，重新打开键盘后生效；不读取或展示输入历史。
- 设置页设备冒烟：`smoke.ps1 -Tag device-lexicon-2026-11-09 -SkipPassword` **9 PASS / 0 FAIL**（设置页、AI开关等不受影响）。

### 15.2 代码与能力对照结论

- 明显强项：离线/无网络权限、密码与禁学习字段、本地学习可控、AI主动入口与生命周期、查询失败恢复、候选显示层稳定性。
- 高优先级不足：编辑工具缺失（撤销、光标移动、选区/复制粘贴、词删除）、英文预测/下一词联想缺失、连续中英数字混排未完整交付、长句质量仍低（D05 natural_long Top1 3/90、Top5 14/90）、长串局部纠错仍无可接受默认。
- 中优先级不足：无`androidTest`/真机自动矩阵；`TextSession`只有提交/删除/读取接口，缺少`setSelection`、`deleteSurroundingText`等编辑原语；`PinyinEngine`/`KeyboardScreen`过大，后续应按搜索、评分、候选面板、按键/手势拆分。
- 明确范围外：9键/双拼/滑行/语音/手写、云同步/剪贴板读取（隐私取舍）；表情、贴纸、剪贴板历史、单手/悬浮键盘、用户词库导入导出可作为后续产品评估。
- 下一步建议：先做编辑工具与`androidTest`最小矩阵；长句继续做候选收集/评分层单机制消融；英文预测/联想和D07混排按独立批次验证。

### 15.3 本轮全量验证

- `scripts/verify.ps1`：RESULT PASS。单测426项 / 0 failures / 0 errors / 4 skipped（77个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 109,385,318B，SHA-256 `3638736308BB09E3EA91D241391F1FCA21736E4BD94D62CA3788871AB2D4EBED`。
- 语料检查v1/v2/v3/D05全部通过；文档引用171文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 设置页设备冒烟见15.1；此前流式候选/删除加速/长输入设备结果仍见12–14节；一步撤销JVM测试见16节。

## 16. 编辑工具：有界多步撤销与重做（2026-11-09，工作树未提交）

- [TextSession.kt](app/src/main/java/com/bitap/keyboard/core/TextSession.kt)新增`deleteBackward(count)`，Android实现使用`deleteSurroundingText`，JVM fake使用逐字符删除；不改变普通退格的grapheme语义。
- [KeyboardController.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardController.kt)维护最多10条最近`CommitText`历史的`undoStack`和`redoStack`；每次撤销仅当编辑器光标前仍完整匹配最近一条提交时删除，匹配失败则清空历史；重做只在撤销后的同一光标位置可执行。普通输入/候选提交/标点等继续累积历史；切字段清空历史，真实光标/选区变化由精确suffix与redo锚点校验保护，不直接清空；查询/AI内部事件不使撤销/重做按钮闪烁。
- [KeyboardScreen.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt)原在功能行“中/英”键旁显示“↶/↷”；2026-11-10 按用户反馈移除这对按键，撤销/重做改放到编辑工具页的独立“撤销/重做”行，主键盘保持不拥挤。
- 测试：`KeyboardControllerUndoTest` 3项（真实Controller+`TestEditorTextSession`）覆盖候选提交后撤销、连续提交两次后逐步撤销、撤销后重做。
- 未覆盖：选区撤销、光标移动后的撤销/重做、真实IME/cross-field撤销和多年历史压缩。

## 17. 编辑工具：光标左右移动（2026-11-09，工作树未提交）

- [TextSession.kt](app/src/main/java/com/bitap/keyboard/core/TextSession.kt)新增`moveCursorBy(delta)`：Android用`InputConnection.setSelection`移动光标，JVM fake用测试会话光标实现；光标移动不修改文本。
- [KeyboardController.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardController.kt)处理`KeyboardAction.MoveCursor`，成功后清理撤销/重做历史，避免移动光标后误撤旧提交。
- [KeyboardScreen.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt)在功能行加入“←/→”光标键，左右各移动一个字符。**2026-11-09 后续按用户反馈改为空格下方留白处长按滑动（见第18.2节），可见箭头键已移除。**
- 测试：`KeyboardControllerCursorTest` 1项覆盖左右移动和文本不变。
- 未覆盖：选区扩展、词级光标移动、长按连续移动、真实IME在WebView/多行字段的边界行为。

## 18. 市场对照与编辑工具补齐（2026-11-09，工作树未提交）

### 18.1 市场对照

- 新增 [市场对照](MARKET_COMPARISON.md)：用公开资料对照搜狗、百度、讯飞、微信输入法、Gboard、SwiftKey 六款 Android 输入法，覆盖中文输入方式、纠错/混排、英文预测、编辑工具、候选面板、隐私/离线、AI 与生态，文末列26个编号来源。
- 方法是桌面研究：未安装、试用或拆包竞品，竞品“支持”均为官网/官方帮助/应用商店的公开宣称；不引用市占率、下载量、准确率等无可复核口径的数字。
- 结论：主流产品已是“全功能套件”；Bitap 当前只覆盖基础拼音、英文 QWERTY、候选面板、本地学习和主动式本地 AI，日常高频差距是中文长句质量、编辑工具完整度、英文预测/下一词、连续中英数字混排与候选面板操作成本。Bitap 最可验证的差异化仍是无 INTERNET/ACCESS_NETWORK_STATE、密码与禁学习字段不写词库、本地 AI 只由用户主动触发。
- 市场对照给出的第一批建议与本次实现一致：先做编辑工具（选区/复制/剪切/粘贴/按词删除）与文档/测试闭环；英文预测、D07连续混排、九键/双拼评估放到后续批次；云同步、语音/手写/滑行、表情/皮肤生态不跟做。

### 18.2 编辑工具：选区、剪贴板与词级操作

- [TextSession.kt](app/src/main/java/com/bitap/keyboard/core/TextSession.kt) 新增 `selectAll()`、`copySelection()`、`cutSelection()`、`paste()`、`moveCursorByWord(delta)`、`deleteBackwardWord()`，默认实现返回 false。`AndroidTextSession` 优先 `InputConnection.performContextMenuAction(android.R.id.selectAll/copy/cut/paste)`，不支持时退化到 `getSelectedText` + `ClipboardManager` + `setSelection`/`commitText`；新增纯函数 `WordBoundary`（CJK 单字为词、连续字母数字为词、跳过空白）。
- [KeyboardUiState.kt](app/src/main/java/com/bitap/keyboard/core/KeyboardUiState.kt) 新增 `hasSelection`、`editingToolsVisible` 和 `ToggleEditingTools/SelectAll/CopySelection/CutSelection/Paste/MoveCursorByWord/DeleteWord` 动作；[KeyboardReducer.kt](app/src/main/java/com/bitap/keyboard/core/KeyboardReducer.kt) 保持这些动作的 reducer 状态中性，并在 `SelectionChanged`（含 composing 为空与正常 IME composing 回调）同步 `hasSelection`，`EditorInfoChanged` 重置两个字段。
- [KeyboardController.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardController.kt) 在 dispatch 早期执行编辑动作：SelectAll 成功置 `hasSelection=true`；Cut/Paste 成功清 `hasSelection` 并清理撤销态；按词移动/按词删除成功清理撤销态；`onStartInput/onFinishInput` 重置编辑工具状态。
- [KeyboardScreen.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt) 将“编辑”入口放到候选条 **AI 与设置之间**（composing 为空时显示），点击后打开**独立编辑键盘页** `EditingToolsPage`：标题“编辑工具”，八项主键（全选/复制/剪切/粘贴；词左/词右/删词/收起）加下方独立撤销/重做行，共十键，并保留“空格下方长按滑动移动光标”提示；主键盘功能行不再显示撤销/重做，避免与“中/英”键拥挤。**按用户反馈，原来的可见“←/→”光标键已移除，改为空格下方留白处的长按滑动手势 `SpaceCursorPad`：长按后水平滑动，每16dp移动一个字符，使用纯函数 `cursorStepsForDrag` 计算步数；仅竖屏非紧凑模式显示，不占按键行宽度。**
- 测试：新增 `KeyboardControllerEditingTest` 9项（全选/复制/无选区不变/剪切/粘贴/英文与中文词移动/删词/工具栏显隐与新输入重置/选区同步）、`KeyboardScreenEditingTest` 6项（工具栏可见性、八项主键与撤销/重做行文案）、`KeyboardScreenSpaceCursorTest` 3项（16dp步进、方向与提示文案）；`KeyboardControllerUndoTest` 3项、`KeyboardControllerCursorTest` 1项继续通过。

### 18.3 设备验证（22021211RC）

- 设备：22021211RC / LineageOS23.2 / Android16 / 1080×2400@440dpi / 竖屏 / ADB单指注入；安装本轮debug APK（109,413,834B，SHA-256 `AD16749E1A7E0A3AEBE23E25F03D9056016A7D18A647382BEACB657EE936C7E8`）。
- 新增 `scripts/device-qa/editing_tools.ps1`（支持 `-BaseUrl`，默认 `http://127.0.0.1:8769`），用 `uiautomator dump --compressed` 读取页面状态、按键坐标按本机测量：**14 PASS / 0 FAIL**。覆盖：候选条 AI 与设置之间的“编辑”入口打开独立编辑页、全选（sel=0-11）、复制保持选区、清空后粘贴恢复“hello world”、剪切清空、再次粘贴、按词删除“world”得到“hello ”、词左到0/词右到5、空格下方长按滑动左移2字符（sel 11→9）与右移2字符（sel 9→11）、返回键盘。证据在 `.tmp/device-qa/editing-tools/`。
- `scripts/device-qa/smoke.ps1` 同步改为 `-BaseUrl` 可配置并重测：**10 PASS / 0 FAIL**（键盘显示、nihao候选与提交、退格、标点、连续输入、设置页、AI开关、密码字段）。兼容旧默认 `http://127.0.0.1:8769`，本轮用本机临时 `http://127.0.0.1:8771` 服务。
- 未覆盖：Android IME窗口不出现在 `uiautomator` active-window dump 中，设备脚本使用本机测量坐标，不是通用自动矩阵；只测了 WebView textarea 与单机竖屏，未测原生 EditText、Compose BasicTextField、多行、TalkBack、横屏/紧凑模式、字体放大和多ROM；`performContextMenuAction`/系统剪贴板路径只在设备上抽测，JVM fake 只证明逻辑；选区拖拽扩展和剪贴板历史仍未实现。

### 18.4 本轮全量验证

- `scripts/verify.ps1`（2026-11-09）：**RESULT PASS**。单测443项 / 0 failures / 0 errors / 4 skipped（80个测试XML）；lint 0 errors / 26 warnings / 1 hint；debug APK 109,413,834B，SHA-256 `AD16749E1A7E0A3AEBE23E25F03D9056016A7D18A647382BEACB657EE936C7E8`。
- 语料检查v1/v2/v3/D05全部通过；D04/v3 Python契约5+4项通过；文档引用176文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 设备结论只覆盖22021211RC单机、LineageOS23.2/Android16、竖屏、默认字体、ADB单指；换机型/字体/横屏/紧凑模式需重新测量，不把JVM/单机抽测当作产品验收。

## 19. 英文补全与下一词联想（2026-11-09，工作树未提交）

### 19.1 实现

- 新增 [EnglishPrediction.kt](app/src/main/java/com/bitap/keyboard/core/EnglishPrediction.kt)：EnglishPredictor 提供本地英文补全（内置常用词 + 用户已上屏词）和用户 bigram 下一词；EnglishPredictionData/EnglishPredictionStore 只保存单词与相邻 bigram 计数，最多保留 800 个词/300 个前词/每前词8个后继；SharedPreferencesEnglishPredictionStore 使用应用私有存储。
- [KeyboardUiState.kt](app/src/main/java/com/bitap/keyboard/core/KeyboardUiState.kt) 新增 englishSuggestions 字段与 EnglishSuggestionTapped 动作；[KeyboardReducer.kt](app/src/main/java/com/bitap/keyboard/core/KeyboardReducer.kt) 保持该动作 reducer 状态中性。
- [KeyboardController.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardController.kt) 跟踪英文当前词前缀；空格/标点/回车提交词并记录 bigram；只有 learningEnabled 且非密码/禁学习字段才写入学习；点击“联想”chip 时删除已输入前缀、提交单词加空格，并记入撤销栈；clearUserLexicon 同时清除英文学习。
- [KeyboardScreen.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt) 在英文模式候选条显示最多3个“联想”chip；密码/数字字段不显示，中文模式不显示；[BitapApp.kt](app/src/main/java/com/bitap/keyboard/ui/BitapApp.kt) 的“清除本地学习词库”同时清除英文联想学习。
- 冷启动词表为手工常用词（DEFAULT_ENGLISH_WORDS，约200词），不使用第三方频率数据；SEED_BIGRAMS 仅提供少量冷启动常用搭配，后续用户 bigram 可扩展。

### 19.2 测试与设备

- EnglishPredictionTest 6项：常用词补全、学习词排序/快照恢复、bigram 学习与 seed 覆盖、序列化往返、清除后 seed 保留、非法词忽略。
- KeyboardControllerEnglishPredictionTest 5项：输入 hel 显示 hello、点击替换前缀、thank+空格出现 you、学习关闭仍可补全但不持久化、密码字段不显示、清除学习。
- 新增 scripts/device-qa/english_prediction.ps1：22021211RC/1080×2400@440/竖屏，**4 PASS / 0 FAIL**：hel 状态、点击 hello 后得到 hello 加空格、thank+空格出现 you、点击 you 后得到 hello thank you 加空格。脚本启动时 force-stop IME 后重新设置默认 IME，重启后仍出现 thank -> you，证明 SharedPreferences 持久化生效。
- 证据目录 .tmp/device-qa/english-prediction/。
- 未覆盖：只测 WebView textarea 与单机竖屏；未测原生 EditText/Compose BasicTextField/多行/TalkBack/横屏/多ROM；未做真实任务击键/接受率、跨用户词表质量或中文下一词。

### 19.3 本轮全量验证

- scripts/verify.ps1（2026-11-09）：RESULT PASS。单测454项 / 0 failures / 0 errors / 4 skipped（82个测试XML）；lint 0 errors / 28 warnings / 1 hint（新增2条为 EnglishPredictionStore 的 UseKtx 风格提示，不影响构建）；debug APK 109,420,822B，SHA-256 88A09CC0FCE6CAF4AED99D6092D15A1D76591E5AC0A1AEB45485D7AE55690B7A。
- 语料检查v1/v2/v3/D05全部通过；D04/v3 Python契约5+4项通过；文档引用180文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。

## 20. 仓库审查 P1 修复（2026-11-09/10，工作树未提交）

### 20.1 修复内容

- **P1-1 学习数据删除/清除不复活**：`UserLexiconStore` 增加 `currentRevision()`（File store 按 canonical path 共享 revision，SharedPreferences store 使用进程/名称级 revision），`create()` 返回缓存共享实例；`PinyinEngine` 在 search/persist 前检测外部 revision 变化，从 store 重载并**替换**内存快照，避免把设置页已删除的词条写回，同时保留其他词条。`EnglishPredictionStore.create()` 同样缓存共享实例；`KeyboardController` 在记录/持久化前比较 `store.load()` 与上一次快照，发现清除或外部变化时调用 `EnglishPredictor.resetFrom()`，不再写回旧词。
- **P1-2 英文补全误删**：`onSelectionChanged` 顶部先同步 `TextSession` 绝对选区；真实外部光标/选区变化清空英文前缀与联想。点击补全前重新读取绝对 caret，确认选区折叠且光标前文本与 `englishBuffer`（忽略大小写）一致后才删除/提交。
- **P1-3 撤销误删**：undo/redo 栈改为 `UndoRecord(text,start,end,sessionId)`；`execute()` 根据 CommitText/SetComposingText/FinishComposing/DeleteBackward 维护虚拟绝对 caret 和提交区间；撤销要求当前 selection 折叠在记录 end 且光标前文本完全一致，外部光标移动/编辑会清空历史。多步撤销/重做保留。
- **P1-4 光标绝对定位**：`TextSession` 新增 `EditorSelection`、`updateSelection()`、`getSelection()`、`getTextAfterCursor()`；`AndroidTextSession` 缓存 `onUpdateSelection` 绝对选区（无回调时用 `SurroundingText.offset` 回退）。`moveCursorBy`、按词移动改为绝对光标 + ≤4096 字符窗口；`selectAll` fallback 使用 `ExtractedText.startOffset + length`；删除 `MAX_MOVE_TEXT = 1_000_000` 无界读取。
- **P1-5 许可证统一**：`LICENSE` 原为 GPL-2.0 文本，与 README、THIRD_PARTY_NOTICES、legal/DATA_PROVENANCE 中一致的 Apache-2.0 声明冲突；按现有三处声明将 LICENSE 统一为 Apache-2.0。发布前如实际采用 GPL-2.0，应反向统一 README/声明文件。

### 20.2 测试与验证

- `KeyboardR08Test` 新增 2 项：外部清空后运行中引擎 persist 不复活；删除一个词条后不复活且不丢失其他词条。
- `KeyboardControllerUndoTest` 新增 2 项：外部 caret 移动使 undo 失效（`xx` 不被误删）；自身 commit callback 仍保留 undo。
- `KeyboardControllerEnglishPredictionTest` 新增 4 项：外部光标移动清空补全；无选区回调时补全前重查前缀；自身 commit callback 保留前缀；设置清除后不复活英文学习。
- 新增 `TextSessionSelectionTest` 4 项，覆盖绝对选区契约与 bounded after-cursor 读取。
- 审查临时探针 `ReviewRegressionProbeTest`（`.tmp/review-probes`，未提交）9 项全部通过，包含原 4 个复现场景：英文补全误删、撤销误删、英文学习清除复活、中文词条删除复活。

### 20.3 全量门禁（2026-11-10）

- `scripts/verify.ps1`：**RESULT PASS**。单测466项 / 0 failures / 0 errors / 4 skipped（83个测试XML）；lint 0 errors / 28 warnings / 1 hint；debug APK 109,287,138B，SHA-256 `98D6EAFC1AAD4318BB1F8E8C1DA7CCF2CBB446A5EF47B0FF93E56D8A4B143659`。
- 语料检查v1/v2/v3/D05全部通过；D04/v3 Python契约5+4项通过；文档引用181文件、0断链；APK无INTERNET/ACCESS_NETWORK_STATE权限。
- 本轮未做真机验证。`AndroidTextSession` 绝对光标实现仅通过编译和 `TestEditorTextSession` fake 间接验证；真实原生 EditText/Compose/WebView 光标与选区行为仍需设备矩阵复测。
- 设备结论只覆盖22021211RC单机、LineageOS23.2/Android16、竖屏、默认字体、ADB单指；换机型/字体/横屏/紧凑模式需重新测量，不把JVM/单机抽测当作产品验收。

## 21. D07 连续中英数字混排基础版（2026-11-10，工作树未提交）

### 21.1 实现的输入规则

- **显式字面量运行**：中文模式下，composing 为空且 Shift 开启时，首个拉丁字母启动 literal English run；保留输入大小写，后续字母/数字/`_`/`-` 继续按字面追加。空格提交 `literal + " "`，回车只提交 literal，其他标点先提交 literal 再提交标点；退格逐字缩减，语言切换/切换字段不重复提交。literal run 不发起拼音查询，也不接受迟到的拼音候选结果。
- **连续混排候选**：新增 [EnglishLexicon.kt](app/src/main/java/com/bitap/keyboard/core/EnglishLexicon.kt) 和 [MixedInputResolver.kt](app/src/main/java/com/bitap/keyboard/core/MixedInputResolver.kt)。对单个 Latin token，若拼音检索已有完整 CJK 覆盖则完全保持拼音路径；否则按最长英文/数字后缀切分，生成“中文前缀 + 规范英文/数字后缀”的候选（如 `daimajianchayongsonar -> 代码检查用Sonar`、`jiage123 -> 价格123`、`yongpython -> 用Python`），整词英文在拼音无完整候选时作为 fallback（如 `android -> Android`）。最多 3 个，按词去重。
- **引擎接线**：[PinyinInputEngine.kt](app/src/main/java/com/bitap/keyboard/core/engine/PinyinInputEngine.kt) 在 baseline 结果前合并 mixed 候选，标记 `CandidateSource.Other`，不改变纯拼音候选的顺序/字段；resolver 只调用 delegate `PinyinEngine.search`，不递归 adapter。

### 21.2 测试与边界

- `KeyboardMixedInputTest` 18 项：`Android16`/`user_id` 保留大小写，跨 Numbers/Symbols 层继续字面量，空格/回车/标点/退格/语言切换/字段切换，拼音->literal->空格->拼音矩阵，literal run 不触发拼音查询；全部通过。
- `MixedInputResolverTest` 16 项：词表规范化、最长后缀、数字后缀、整词 fallback、短功能词不劫持拼音、大小写规则、去重/limit、`PinyinInputEngine` 合并与纯拼音不变；另用生产 `pinyin_dict.tsv` 实测 `daimajianchayongsonar -> 代码检查用Sonar`、`zhegebanbenyongkotlin -> 这个版本用Kotlin`、`jiage123 -> 价格123`、`yongpython -> 用Python`，全部通过。
- `scripts/verify.ps1`（2026-11-10）：**RESULT PASS**。单测500项 / 0 failures / 0 errors / 4 skipped（85个测试XML）；lint 0 errors / 28 warnings / 1 hint；debug APK 109,304,054B，SHA-256 `330C1E0616D8B6A15DF3D079DD907E493253F1A8B13266000B8AE96630074FBD`。v1/v2/v3/D05语料检查、D04/v3 Python契约、文档引用186文件0断链（新增 mixed_input.ps1 后单独复跑 link checker）、APK无INTERNET/ACCESS_NETWORK_STATE权限均通过。
- 真机验证（2026-11-10，22021211RC/LineageOS23.2/Android16/1080×2400@440，安装同 hash debug APK）：新增 `scripts/device-qa/mixed_input.ps1` **8 PASS / 0 FAIL**（`Android16` literal、literal+空格、`fangan -> 方案` 纯拼音不回归、`daimajianchayongsonar -> 代码检查用Sonar`、`android -> Android`、`nihaom -> 你好吗`、`zenml -> 怎么了`、`nihaob -> 你好吧`）；`smoke.ps1` **10 PASS / 0 FAIL**；`editing_tools.ps1` **14 PASS / 0 FAIL**（冷启动页面未就绪时首次执行未通过，重新执行通过，脚本启动等待仍是设备工具弱点）；`english_prediction.ps1` **4 PASS / 0 FAIL**。证据目录 `.tmp/device-qa/mixed-input/`、`.tmp/device-qa/smoke/`、`.tmp/device-qa/editing-tools/`、`.tmp/device-qa/english-prediction/`。
- 未覆盖：D07 仍无原生 EditText/Compose/多行/TalkBack/横屏/字体放大设备矩阵；英文后缀识别是内置词表启发式，不能覆盖任意专名；小写开头的 identifier（如 `user_id`）需手动切换或 Shift 初始事件，产品交互仍需确认；长句排序和词库未改动。
## 22. 主键盘功能行拥挤修复（2026-11-10）

- 按用户反馈移除功能行“中/英”键旁的 `↶/↷` 撤销/重做键；主键盘底行恢复为 `123 / 中英 / 逗号 / 空格 / 句号 / 回车` 的简洁布局。
- 撤销/重做移到独立编辑工具页的第三行（`撤销 / 重做` 两键，等宽），编辑页仍保留原八项主键和空格下方滑动提示；`KeyboardScreenEditingTest` 增到 6 项并验证历史行文案。
- 因底行删键后按钮位置变化，重新标定 `smoke.ps1`、`mixed_input.ps1`、`editing_tools.ps1` 的坐标；改后真机回归：smoke **10 PASS / 0 FAIL**、mixed_input **8 PASS / 0 FAIL**（后续补入语气词用例）、editing_tools **14 PASS / 0 FAIL**、english_prediction **4 PASS / 0 FAIL**。证据目录 `.tmp/device-qa/smoke/`、`.tmp/device-qa/mixed-input/`、`.tmp/device-qa/editing-tools/`、`.tmp/device-qa/english-prediction/`。
- `scripts/verify.ps1`（2026-11-10）：**RESULT PASS**。单测501项 / 0 failures / 0 errors / 4 skipped（85个测试XML）；lint 0 errors / 28 warnings / 1 hint；debug APK 109,304,166B，SHA-256 `E651DC4A3AC6501089590A7C7FE7E06D908FD80E43F9330B54B8CA2E7AC7B9E0`；语料、Python契约、文档186文件0断链、无网络权限均通过。
## 23. 语气词首字母简拼补全（2026-11-10）

- 问题反馈：`nihaom`、`zenml` 这类把句末语气词打成首字母的输入，当前只显示 `你好`/`怎么` 等部分候选，不能直接得到 `你好吗`/`怎么了`；`nihaoma`、`zenmele` 等完整拼音本来可命中，说明缺口只在首字母简拼。
- 修复：[PinyinEngine.kt](app/src/main/java/com/bitap/keyboard/core/PinyinEngine.kt) 增加句末语气词简拼补全：在常规候选和轻量纠错之后，检查最后一个字符是否为 `m/l/b/n/a/y/w/o`，若前缀已有完整覆盖 CJK 候选，则生成对应语气词候选；支持 `吗/嘛`、`了/啦`、`吧`、`呢`、`啊`、`呀`、`哇`、`哦`。完整拼音候选优先；已有整句候选时不追加，避免 `zenm -> 怎么吗` 之类的噪声；语气词按固定顺序稳定排序。
- 测试：`PinyinEngineTest` 新增首字母语气词用例；新增 `PinyinParticleSuffixTest` 用生产 `pinyin_dict.tsv` 验证 `nihaom -> 你好吗`、`zenml -> 怎么了`、`nihaoma -> 你好吗`、`zenmele -> 怎么了`、`zenm -> 怎么`。
- 真机：`mixed_input.ps1` 扩展到 8 项并 **8 PASS / 0 FAIL**，新增 `nihaom -> 你好吗`、`zenml -> 怎么了`、`nihaob -> 你好吧`。
- 边界：当前只覆盖常见单字母语气词简拼，不做方言/多音语气词穷举；不按句子写规则，不改变完整拼音路径。
- `scripts/verify.ps1`（2026-11-10）：**RESULT PASS**。单测503项 / 0 failures / 0 errors / 4 skipped（86个测试XML）；lint 0 errors / 28 warnings / 1 hint；debug APK 109,782,770B，SHA-256 `00BD72296D48E5173A7EFB2ADCF8A4C06619485F74D993ACFC322C78B82A8E47`；文档187文件0断链、语料/Python契约/无网络权限均通过。
## 24. 三项编辑与学习数据正确性修复（2026-11-10）

- **P1 学习词条删除与延迟保存的原子性**：[UserLexiconStore.kt](app/src/main/java/com/bitap/keyboard/core/UserLexiconStore.kt) 增加 `saveEntriesIfCurrent(entries, generation, expectedRevision)` 与 `updateEntries { ... }`；File store 使用按 canonical path 共享的锁，SharedPreferences store 使用实例锁，版本/清除代数校验和写入在同一锁内完成。[PinyinEngine.kt](app/src/main/java/com/bitap/keyboard/core/PinyinEngine.kt) 保存前捕获 expectedRevision，CAS 失败时从 store 重载并替换内存，不写回旧快照；[BitapApp.kt](app/src/main/java/com/bitap/keyboard/ui/BitapApp.kt) 单条删除改为 store 原子更新。回归：`KeyboardR08Test` 10/10、`KeyboardS04Test` 3/3。
- **P1 光标按字素簇移动**：[TextSession.kt](app/src/main/java/com/bitap/keyboard/core/TextSession.kt) 的 `UnicodeGrapheme` 增加前后边界遍历 `moveBy`，`AndroidTextSession.moveCursorBy()` 按可见字素逐簇移动，A😀B、旗帜、ZWJ family、组合字符不会被代理对/组合序列截断；WordBoundary 已有 codePoint 边界保护。回归：`UnicodeGraphemeTest` 13 项、`TextSessionSelectionTest` 6 项。
- **P2 英文补全撤销恢复原前缀**：[KeyboardController.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardController.kt) 的 `UndoRecord` 增加 `replacedText`；`hel -> hello -> Undo` 恢复 `hel`，Redo 恢复 `hello `，选区替换同样记录替换前后文本。回归：`KeyboardControllerUndoTest` 6 项、`KeyboardControllerEnglishPredictionTest` 12 项。
- `scripts/verify.ps1`（2026-11-10）：**RESULT PASS**。单测518项 / 0 failures / 0 errors / 4 skipped（86个测试XML）；lint 0 errors / 29 warnings / 1 hint；debug APK 108,851,118B，SHA-256 `41B792A4A9C0616AA0ADE786BED17AC495A34F96A849DF35AB4E40EC068936F2`；文档188文件0断链、语料/Python契约/无网络权限均通过。
- 未做真机验证；本轮修复是 JVM 回归和代码审查闭环，emoji/选区/真实编辑器行为仍需设备矩阵验证。
## 25. 三项修复真机验证记录（2026-11-10）

- 环境：22021211RC / LineageOS23.2 / Android16 / 1080×2400@440；debug APK SHA-256 `41B792A4A9C0616AA0ADE786BED17AC495A34F96A849DF35AB4E40EC068936F2`。新增 **debug-only** `app/src/debug/.../qa/QaEditorActivity` 作为原生 EditText、Compose BasicTextField、WebView textarea 的验证宿主，不进入 release。
- 原生 EditText：`A😀B` 用空格下方手势左移一个可见字符后 selection `4-4 -> 3-3`；两次退格依次得到 `A😀`、`A`，未出现代理对中间位置。
- Compose BasicTextField：`A😀B` 两次退格得到 `A`，emoji 删除完整；空格手势移动未观察到 selection 变化（当前宿主/Compose input connection 选择回调未闭环），需继续定位。
- WebView textarea：`A😀B -> A😀` 正常删除 B，但再退格留下 **lone high surrogate** `A\ud83d`；现有 `deleteSurroundingText`/code-point 路径仍会破坏 emoji，属于本轮发现的真实 WebView 缺陷，未声称修复。
- 英文补全撤销：JVM `KeyboardControllerUndoTest` / `KeyboardControllerEnglishPredictionTest` 通过；真机 QA 宿主中完成英文补全后进入编辑工具页点击“撤销”未改变文本，当前不能作为真机验收通过。
- 学习词条删除后的持续输入/切设置/重启链路尚未完成设备脚本化，仍需补验证。
- 结论：三项修复的 JVM 回归已闭环；设备验证暴露 WebView emoji 删除、Compose 光标移动、真机撤销与学习删除链路问题。长句解码质量优化暂不启动，先处理这些设备层问题。
