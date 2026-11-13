# Bitap 当前工作树复核：不足与改进建议

审查日期：2026-11-13（北京时间）。审查范围：HEAD `80de879` 加当前未提交工作树（批次0/1、候选面板分页修复）。方法：独立代码/文档/设备证据审查、现有 verify 日志与测试 XML 核对；本轮审查本身不修改源码、词库或评测资产。

## 1. 总体结论

当前工作树的生产默认路径未发现 P0 行为回归或安全阻断：无网络权限、备份关闭、cleartext 关闭、模型导入使用固定私有路径；最终 `scripts/verify.ps1` 为 PASS（573 项单测 / 0 failures / 0 errors / 4 skipped，lint 0 errors / 29 warnings / 1 hint）。但在可交付性、测试证据、文档口径和候选面板修复完整性上存在明确不足，其中 1 项 UI P0 需要先修。

## 2. P0

### P0-1 横屏/紧凑候选面板未接入自适应分页

- 证据：[KeyboardScreen.kt](app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt) 的 `CandidatePanelOverlay` 仍调用固定 `candidatePanelPageSize(compact = true)`（第 1044 行）；`CandidateBar` 里的自适应 `expandedPageSize` 只在 `!compact` 的 inline 面板（第 837 行）使用。横屏/紧凑 overlay 仍为固定 8 项/页，而 2 行可见区最多约 4 个长候选，页脚“页数”与可见项再次不一致，原缺陷在横屏路径完整保留。
- 影响：§37 的“页数与可见项一致”只对竖屏默认字体 inline 路径成立，不能作为 FR-02 完整修复；`CandidatePanelGeometryTest` 的 compact=4 断言在生产 compact 路径不生效，属测试假阳性。
- 最小修复：给 `CandidatePanelOverlay` 传入 `candidatePanelPageSize(displayCandidates, compact = true)`，并加横屏设备/Compose 断言。

## 3. P1

### P1-1 宽度估算未覆盖字体缩放、屏宽、混合候选和长文本截断

- 证据：`candidatePanelPageSize(candidates, compact)` 用固定容量 40/76、CJK=2/其他=1、2 单位 padding（KeyboardScreen.kt:141-152）。按 393dp 屏宽、18sp 估算：8 个 CJK 字候选在 fontScale 1.3 时实际只能 1 个/行（4 个可见），估算仍给 8；9 个 CJK 字在默认字体也可能 1 个/行；360dp 屏更差。混合列表用 `maxOf` 取全列表最宽候选，64 个短候选 + 1 个长候选会从 6 页膨胀到 17 页。
- 截断：展开面板 `maxLines=2`（KeyboardScreen.kt:958）；150% 字体下每行约 12 个 CJK 字，2 行 24 字，D05 natural_long 有 25/123（20.3%）超过 24 字会被 ellipsis 截断；两行文本加“分段/纠错”badge 约 54dp，超过 50dp 行预算，partial 候选可能再次内部滚动。
- 影响：130%/150% 字体、360dp/横屏、混合候选下仍会出现“页脚 ≠ 可见项”或候选尾部不可区分；§37 的“长句 4 项/页”不是所有配置的保证。
- 最小修复：按实际可用宽度、fontScale、2dp 间距和真实行高计算分页；优先使用 Compose `TextMeasurer`/自定义 layout/滚动列表；至少补 1.3/1.5 字体、332/365/411dp、混合长短和 emoji/组合字符测试。

### P1-2 TargetTrace 测试可被 no-op 通过，且归因仍是 bucket 级存在性

- 证据：`PinyinD05LongSentenceTargetTraceTest.kt:185-212` 只断言每例 `buckets.size`、reason 合法、target 归一化和交叉检查，未断言 `NONE=24 / BEAM_PRUNED=43 / ENTRY_TRUNCATED=22 / NO_DICTIONARY_ENTRY=1` 分布；一个对所有 90 例返回 NONE、fullTargetPaths=1 的伪 trace 可通过现有断言。
- 归因：`debugTraceTarget` 的 `lastAlive` 取任意 target 前缀路径存活的最大 position（PinyinEngine.kt:1149-1185），较短前缀晚存活会掩盖最长前缀被 beam 剪掉的断点；`truncationAt` 按该 key 下所有 beam 内 target 路径聚合（PinyinEngine.kt:1078-1101），可能把 BEAM_PRUNED 误归为 ENTRY_TRUNCATED/NO_DICTIONARY_ENTRY。KDoc/§35.1 已写“bucket 级归因”，但 §35.1 的 43/22 分层仍被下一批当作因果优先级。
- 补充：`PinyinD05BackwardCompletionAblationTest` / `PinyinD05ScoreWindowBeamAblationTest` 的 `loadTargetReasons` 读取 `build/d05/long-sentence-target-trace-detail.tsv`；文件缺失返回 null、测试仍 PASS。最后一次全量 verify 中 Backward 测试先于 TargetTrace 生成/更新 detail，可能使用上一轮残留 TSV。
- 最小修复：断言固定 reason 分布和 `fullTargetPaths` 关系；把 `maxTargetPrefixLenBefore/InBeam` 与具体截断条目写入 DebugTargetBucket/KeyProbe；Backward/ScoreWindow 在测试内直接重算 layer map 或显式声明/校验 layerSource，不要静默读取旧 build 产物。

### P1-3 默认工程门禁未分层，且缺少 CI/androidTest

- 证据：最终完整 verify 为 **9m47s / 573 tests**；测试 XML 合计 565.81s，10 个 `PinyinD05*` 诊断类约 521.81s（92.2%）；三个新诊断测试合计 94.72s（16.1%，Backward 29.58s、ScoreWindow 52.52s、TargetTrace 12.61s）。`scripts/verify.ps1:139-142` 无条件跑 `:app:testDebugUnitTest --rerun-tasks`，没有 opt-in 分层；仓库无 `.github` CI，无 `app/src/androidTest`。
- 影响：日常门禁被开发消融拖到近 10 分钟；诊断测试重跑成本随机制增加线性增长；无硬件/Compose UI 合同测试。
- 最小修复：默认快速套件只保留必要回归；`PinyinD05*AblationTest` / TargetTrace 移到 `-Pdiagnostics` 或独立 Gradle task；加最小 CI（单测、lint、debug/release、权限、文档链接）；补 Compose UI/androidTest。

### P1-4 工作树未提交、基线不可复现，release 不拒绝 dirty

- 证据：当前 10 个 modified + 4 个 untracked（3 个 631–703 行的诊断测试 + 25.9KB 审查文档）；干净 HEAD 为 568 项，当前工作树 573 项，`6B706B2C...` APK hash 无法从提交复现。`scripts/build-release.ps1:129-136` 仅打印 dirty 仍继续发布并写 `dist/SHA256SUMS.txt`。
- 最小修复：按 docs / engine / UI 三组拆分 commit；提交后 clean-tree 重跑 verify 并记录 hash；`build-release` dirty 默认 fail，显式 `-AllowDirty` 才放行。

### P1-5 隐私文案与实际剪贴板读取矛盾

- 证据：`BitapApp.kt:911` 写“不读取剪贴板”，但 `TextSession.kt:625-634/812-826` 的复制/剪切/粘贴路径会访问 `ClipboardManager`；`PROGRESS.md:303` 旧状态仍把剪贴板读取列为范围外，`:339` 已记录粘贴实现。
- 最小修复：改为“不后台监听；仅在用户点击复制/剪切/粘贴时访问系统剪贴板”，同步 PROGRESS 历史章节。

### P1-6 Release APK 未携带项目/部分第三方许可与 NOTICE

- 证据：`app/build.gradle.kts:49-56` blanket 排除 `/META-INF/LICENSE*`、`/META-INF/NOTICE*`；当前 release APK 113 个 entry 中没有项目 `THIRD_PARTY_NOTICES.md` 和 LiteRT-LM 的 `LICENSE`/`THIRD_PARTY_NOTICE.txt`。`THIRD_PARTY_NOTICES.md` 仅声明 LiteRT-LM Apache-2.0，不随 APK 分发。
- 最小修复：把项目/第三方 notice 打进 `assets/notices` 或 `res/raw`，不要 blanket 排除；verify 增加 APK entry 检查。

### P1-7 PinyinEngine 继续膨胀，诊断/实验代码堆在生产源集

- 证据：PinyinEngine.kt 从 HEAD 2,407 行增至 2,996 行（+589）；`EngineTuning` 约 40 个字段，类内有 `debugSearch/debugTraceTarget/debugNgramStats/debugWordFrequency/debugBestSegmentation/backwardCompletionCappedQueryCount/scoreWindowBeamStats` 等诊断 API；`DebugTargetTraceBuilder`、`computeBackwardCompletion`、`scoreWindowBeam` 全在生产类。违反 REVIEW §H 的按索引/搜索/收集/学习拆分建议。
- 最小修复：至少把 DebugTargetTrace* 和 builder 移到同包 `PinyinDiagnostics.kt`，trace 用轻量快照对象；`computeBackwardCompletion` 移到独立 internal 文件；实验 tuning 独立文件/接口。拆分后用 PinyinEngineTest、D05DevEval、三个新测试做行为等价回归。

## 4. P2（摘要）

- `scoreWindowBeam + backwardCompletion` 同时开启时，scoreWindow 先按纯 score 重排，backward 排序被丢弃；建议显式互斥或定义组合语义。
- `scoreWindowBeamStats.queries` 实际是每个 bucket position 的调用次数，不是 query 数；报告 legend 易误读。
- `PinyinD05BackwardCompletionAblationTest` / `ScoreWindow` 读取旧 build TSV（见 P1-2）且字典 hash 未断言；golden exact 数字（如 oracle==23）会在词典更新时产生脆弱失败。
- `PathNode.targetPrefixLen` 在 trace 关闭时仍多一个字段与每 edge 分支；静态字段布局推断对象大小不增，但无 JVM/ART 微基准，不应断言零成本。
- `computeBackwardCompletion` 的 `shouldContinue` 未覆盖 DP options/inner 循环；`operations` 只计 entry 数，不是总操作上限；capped/cancelled/unreachable 混在一起。
- 模型导入：`displayName == null` 时跳过 `.litertlm` 扩展检查；无 magic/hash/签名校验，任意 ≥50MiB 文件可进入 native 解析（固定私有路径，无目录穿越）。
- debug-only `QaEditorActivity` `android:exported="true"` 无权限；release manifest 已确认不含它，但 debug APK 侧载时可被拉起。
- 文档状态混杂：README 一段同时含 568/571/573 与旧/新 APK hash；ROADMAP “2026-11-13 当前状态”前半仍是审查快照 568；PROGRESS §35.3 的 p95 数字与当前 `build/d05/score-window-beam-ablation.txt` 不一致（旧运行样本）。
- 权限门禁只 denylist INTERNET/ACCESS_NETWORK_STATE，未来新增危险权限不会被拦截；建议 allowlist。
- `docs/PROGRESS.md` 已 740 行/37 个 `##` 章节，建议拆分“当前状态 + 历史归档”并加 TOC。

## 5. 已验证项

1. 当前测试产物：94 个 XML，573 tests / 0 failures / 0 errors / 4 skipped；与 `.tmp/verify-panel-20261113.log:95` 一致。
2. 当前 debug APK：109,613,058B，SHA-256 `6B706B2C1E77B9D289A8191205CB4782A838757F398F9C630C389FAA1C035D6D`，与 PROGRESS §37 一致。
3. `python scripts/check_repo_links.py`：checkedFiles=202，brokenReferences=0。
4. D05 报告：dev-baseline 470/1000、635/1000、234/700、366/700、142/300、200/300、89/277、152/277、3/123、14/123 与 §34.2 一致；target trace 24/43/22/1/0；backward/score-window 报告与 §35.2/§35.3 主体数字一致。
5. 设备截图：r35 系列 008/027/003/004/025 与 r35b 系列 008/027 均存在；r35b 008/027 显示第 1/16 页、4 个长候选；r35c smoke 10 PASS/0 FAIL。
6. Manifest：release merged manifest 仅 MainActivity + 受 BIND_INPUT_METHOD 保护的 BitapImeService；无 INTERNET/ACCESS_NETWORK_STATE，无其他危险权限；allowBackup=false、fullBackupContent=false、dataExtractionRules 排除备份、usesCleartextTraffic=false。

## 6. 未验证项

- 横屏/紧凑 overlay 当前 APK 的实际分页；130%/150%/200% 字体、360dp/411dp/平板、分屏、折叠屏。
- 第 2–16 页、最后一页、看分段跳转、旋转后 candidatePage 状态。
- TalkBack、无障碍焦点顺序、候选 chip 完整文本朗读；真实双拇指/误选恢复/翻页操作成本。
- 非 22021211RC / 非 LineageOS ROM；release 签名/验签；严格来源 `-StrictSources`（生产 detailed 压缩原件仍缺失）。
- JVM/ART hot-path 微基准与设备帧时延。

## 7. 建议修复顺序

1. **P0**：横屏/紧凑 overlay 接入 `candidatePanelPageSize(displayCandidates, compact=true)`，补横屏设备断言。
2. **P1**：按真实字体/屏宽/测量重写分页策略（或改可滚动候选列表），补 130%/150% 字体与混合候选设备证据；修 maxLines/badge 行高。
3. **P1 证据链**：TargetTrace 断言固定分布；Backward/ScoreWindow 不依赖旧 build TSV；诊断测试移出默认 verify。
4. **P1 交付/隐私/许可**：按批提交并让 build-release dirty fail；修剪贴板文案；打包项目与 litertlm 许可/NOTICE。
5. **P1 架构**：拆分 PinyinEngine 诊断/实验代码，控制 2,996 行继续增长。
6. P2 项：scoreWindow+backward 互斥、统计命名、字典 hash、模型导入 magic/hash、debug QA exported、权限 allowlist、文档状态统一。

> 本报告为审查结论，不替代 [2026-11-13 基线审查](REVIEW_2026-11-13.md) 或任何验收规范；所有路径数字均对应当前工作树/现有产物，未提交的工作树不等于可复现发布。

## 8. 修复追踪（2026-11-13 后续继续开发）

- **P0-1 已修**：新增 `candidatePanelUsesScrollMode(candidates, fontScale, screenWidthDp)` 与 `CandidateScrollPanel`；长候选展开面板改为单列 `LazyColumn`，页脚只显示“共 N 项 · 上下滑动查看更多”，不再显示虚假页码；`CandidatePanelOverlay`（compact/横屏）也走同一路径，不再固定 8 项/页。设备截图 `.tmp/device-qa/d05/r35d-d05-008-02-expanded.png` 与 `r35d-d05-008-scrolled.png` 验证首屏 4 项与上滑后续项。
- **P1-1 部分修复**：scroll mode 阈值按 fontScale/screenWidth 缩放，长/放大字体会切到滚动列表，避免页脚页码与可见项不一致；但短/中候选的宽度估算仍为近似值，130%/150%/200% 字体、360dp、混合长短、emoji/组合字符、TalkBack 仍需设备矩阵验证。
- **其余 P1/P2 保持未修复**：TargetTrace 断言与 bucket 级归因、Backward/ScoreWindow 旧 TSV 依赖、verify 分层/CI/androidTest、未提交工作树构建可复现性、剪贴板文案、release 许可/NOTICE、PinyinEngine 2,996 行拆分等均需后续批次处理（详见前文章节）。

