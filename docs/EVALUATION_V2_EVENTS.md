# D01 事件序列集与回放结果（EVALUATION_V2_EVENTS）

- 日期：2026-11-08
- 任务：D01（阶段一）事件序列集
- 状态：交付物；与本目录数据集、`EventSequenceReplayTest` 一起构成可回放的最小事件回归集
- 数据：`eval/v2/events/event_cases.jsonl`（32 条）
- 规范：`eval/v2/events/README.md`
- 测试：`app/src/test/java/com/bitap/keyboard/qa/EventSequenceReplayTest.kt`

## 1. 目标与边界

本交付回答「一串按键、选词、删除、光标和字段切换之后，编辑器最终应该是什么文本、键盘应处于什么状态」。它补足纯拼音引擎准确率无法覆盖的交互链路，但不是真机测试：

- 在 **JVM** 上用 `RecordingTextSession` + `KeyboardController` 回放；
- 不验证 Compose 渲染、系统 IME 生命周期、触控时序、真机横屏/多窗口；
- 事件全部为合成数据，不含真实聊天、账号、密码或个人信息；
- 不读取、不修改生产词库 `app/src/main/assets/pinyin_dict.tsv`，也不读取 `eval/v2/corpus`。

## 2. 数据集

`eval/v2/events/event_cases.jsonl`：32 条，11 类：

| 类别 | 条数 | 覆盖内容 | 代表 case |
|---|---:|---|---|
| `continuous_input_selection` | 4 | 连续输入 + 选词、两次选词拼接 | evt-cont-001 ~ 004 |
| `pending_confirmation` | 3 | 查询未就绪时的 Space / Candidate pending | evt-pending-001 ~ 003 |
| `backspace_unicode` | 3 | ZWJ emoji、肤色 modifier、选区删除 | evt-backspace-001 ~ 003 |
| `selection_change` | 3 | composing 内光标变化、选区替换、全选删除 | evt-selection-001 ~ 003 |
| `cursor_move` | 3 | 光标中间插入英文/中文/标点 | evt-cursor-001 ~ 003 |
| `editor_switch` | 3 | 文本→密码/数字/邮箱字段（EditorInfoChanged） | evt-editor-001 ~ 003 |
| `recreate_controller` | 3 | 横屏/配置变化：重建 KeyboardController | evt-recreate-001 ~ 003 |
| `language_switch` | 3 | 中英切换、composing 中切换、Shift 大写锁定 | evt-lang-001 ~ 003 |
| `punctuation` | 3 | 中文标点、pending 标点 | evt-punct-001 ~ 003 |
| `space` | 2 | 空格提交候选、英文空格 | evt-space-001 ~ 002 |
| `rapid_keys` | 2 | 不等查询的快速键序 | evt-rapid-001 ~ 002 |

每条 case 包含 `id / category / steps[{action,args}] / expectedFinalText / expectedState`；schema、action 列表和 expectedState 字段见 `eval/v2/events/README.md`。

## 3. 回放实现

- 测试：`app/src/test/java/com/bitap/keyboard/qa/EventSequenceReplayTest.kt`。
- `RecordingTextSession`：JVM 内维护文本、光标/选区、composing 区间和操作日志；`deleteBackward` 使用与生产 `AndroidTextSession` 相同的 `UnicodeGrapheme.lastGraphemeLength` 规则，避免把 emoji 的代理对/ZWJ 序列拆坏。
- `KeyboardController`：真实控制器；异步拼音查询使用 `StandardTestDispatcher` + `TestCoroutineScheduler` 虚拟时间推进（30ms 防抖），不 sleep；`settle:false` 用于构造 pending / 快速键序。
- 测试内使用固定合成词库（`DEFAULT_TEST_DICTIONARY`，20 余条常见词），只服务本测试，生产词库不变，保证期望文本可重复且不受引擎迭代影响。
- `recreateController`：销毁旧 controller/scope 后新建，同一个 `RecordingTextSession` 保留文本，验证状态不泄漏。

## 4. 执行结果

- 执行日期：2026-11-08（Asia/Shanghai）
- 环境：Windows / JDK 25.0.4.1 / Gradle 9.7.1；`:app:testDebugUnitTest`（JVM，非真机）
- 命令：
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.qa.EventSequenceReplayTest" --rerun-tasks --console=plain
  ```
- 结果：**BUILD SUCCESSFUL in 21s**。
- JUnit XML：`app/build/test-results/testDebugUnitTest/TEST-com.bitap.keyboard.qa.EventSequenceReplayTest.xml`，`tests=1 failures=0 errors=0 skipped=0`（一个测试方法回放全部 case）。
- 测试 stdout：`EventSequenceReplay: 32 cases passed; categories=backspace_unicode,continuous_input_selection,cursor_move,editor_switch,language_switch,pending_confirmation,punctuation,rapid_keys,recreate_controller,selection_change,space`。
- **32/32 event cases 通过**，11 个必需类别全部覆盖；`event cases need >=24` 与类别断言均通过。
- 补充全量回归（同日、含本测试）：`:app:testDebugUnitTest --rerun-tasks` 成功，54 个测试类 XML 汇总为 **354 tests / 0 failures / 0 errors / 2 skipped**；本测试类在 XML 中为 1 个 test、0 failure。

按类别结果：

| 类别 | 通过/总数 |
|---|---:|
| `continuous_input_selection` | 4/4 |
| `pending_confirmation` | 3/3 |
| `backspace_unicode` | 3/3 |
| `selection_change` | 3/3 |
| `cursor_move` | 3/3 |
| `editor_switch` | 3/3 |
| `recreate_controller` | 3/3 |
| `language_switch` | 3/3 |
| `punctuation` | 3/3 |
| `space` | 2/2 |
| `rapid_keys` | 2/2 |
| **合计** | **32/32** |

> 执行说明：团队并行运行 Gradle 时，前两次尝试分别遇到 `compileDebugUnitTestKotlin` 缓存文件占用和 `processDebugResources` 的 `R.jar` 文件锁（错误信息为 Windows 文件占用，不是测试断言失败）；第三次重试成功，以上结果取自成功运行的 XML。全量测试中该测试类计入 1 个 JUnit 测试方法（内部循环 32 条 case），因此「32 条通过」指 case 数，不是 32 个独立 @Test 方法。


## 5. 覆盖与不能覆盖的边界

覆盖：

- 连续输入、候选选择、pending 确认、空格与标点；
- Unicode 退格、选区删除、选区变化、光标移动；
- 字段切换（text/password/number/email）、controller 重建（横屏模拟）；
- 中英切换、Shift 大小写、快速键序。

不覆盖（按研发报告要求另行处理）：

- 真机 IME 生命周期、系统输入法切换、旋转真实配置回调、分屏/折叠屏；
- Compose 渲染、候选面板展开/翻页、触控多指/滑行/长按；
- AI 词库生成（需模型与 native runtime，JVM 用 stub）；
- 引擎准确率（由 D01 的 pinyin 语料评测负责；本集只看交互最终状态）。

## 6. 运行命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.qa.EventSequenceReplayTest" --rerun-tasks --console=plain
```

报告：`build/test-results/testDebugUnitTest/TEST-com.bitap.keyboard.qa.EventSequenceReplayTest.xml`（或 Gradle 测试报告 `app/build/reports/tests/testDebugUnitTest/`）。

## 7. 维护规则

1. 新增 case 必须写入 `eval/v2/events/event_cases.jsonl`，并在 `eval/v2/events/README.md` 更新类别/action 文档。
2. 不得把期望文本写入生产词库；如新场景需要新词，只能加进测试内 `DEFAULT_TEST_DICTIONARY`。
3. 每个 case 独立；不得依赖执行顺序或共享可变状态。
4. 事件集只作回归/验收，不用于引擎调参；case 的期望文本应人工审核。
