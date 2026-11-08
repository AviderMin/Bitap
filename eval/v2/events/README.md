# Bitap 事件序列集（eval/v2/events）

本目录是 D01 的事件序列数据集，用于在 **JVM** 上回放键盘控制器行为。它是「最终编辑器文本 + 状态」级别的回归集，不是引擎准确率数据集，也不代表真机触控/延迟。

## 1. 文件

| 文件 | 内容 |
|---|---|
| `event_cases.jsonl` | 每行一个 JSON 对象；当前 32 条，覆盖 11 类事件 |
| `README.md` | 本说明 |

当前类别与条数：

| category | 条数 | 覆盖行为 |
|---|---:|---|
| `continuous_input_selection` | 4 | 连续输入 + 候选选择、两次选词拼接 |
| `pending_confirmation` | 3 | Space / Candidate 在查询未就绪时的 pending 确认 |
| `backspace_unicode` | 3 | Unicode grapheme 退格、选区退格 |
| `selection_change` | 3 | composing 中选区变化、选区替换、全选删除 |
| `cursor_move` | 3 | 光标移到中间后插入英文/中文/标点 |
| `editor_switch` | 3 | 切换字段（EditorInfoChanged）：文本→密码/数字/邮箱 |
| `recreate_controller` | 3 | 横屏/配置变化：重建 KeyboardController（JVM 模拟） |
| `language_switch` | 3 | 中英切换、composing 中切换、Shift 大写锁定 |
| `punctuation` | 3 | 中文标点提交、pending 标点 |
| `space` | 2 | 空格提交候选、英文空格 |
| `rapid_keys` | 2 | 不等查询的快速键序 |

## 2. JSONL schema

每行：

```json
{
  "id": "evt-cont-001",
  "category": "continuous_input_selection",
  "description": "连续输入 nihao 后点首选候选",
  "steps": [
    {"action": "startInput", "args": {"kind": "text"}},
    {"action": "appendInput", "args": {"text": "n"}, "settle": false},
    {"action": "appendInput", "args": {"text": "i"}, "settle": false},
    {"action": "appendInput", "args": {"text": "h"}, "settle": false},
    {"action": "appendInput", "args": {"text": "a"}, "settle": false},
    {"action": "appendInput", "args": {"text": "o"}, "settle": false},
    {"action": "settle"},
    {"action": "tapCandidate", "args": {"index": 0}}
  ],
  "expectedFinalText": "你好",
  "expectedState": {
    "composing": "",
    "candidates": [],
    "language": "chinese",
    "layer": "letters",
    "pendingConfirmation": "none",
    "selectionStart": 2,
    "selectionEnd": 2
  }
}
```

### 2.1 顶层字段

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `id` | 是 | string | 全局唯一；建议 `evt-<category>-<序号>` |
| `category` | 是 | string | 见上表；测试会校验必需类别齐全 |
| `description` | 否 | string | 人类可读说明 |
| `steps` | 是 | array | 顺序执行的动作列表 |
| `expectedFinalText` | 是 | string | 回放结束后 `TextSession` 的完整编辑器文本 |
| `expectedState` | 是 | object | 结束状态断言；字段均可选，但至少包含 `composing` |

### 2.2 step

```json
{"action": "appendInput", "args": {"text": "n"}, "settle": false}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `action` | 是 | 见 2.3 |
| `args` | 否 | 动作参数；无参数时省略或为 `{}` |
| `settle` | 否 | 默认 `true`：动作执行后 `advanceUntilIdle()`，等待防抖（30ms）与查询协程完成。设为 `false` 用于构造「查询未返回」「快速键序」等时序 |

### 2.3 支持的 action

| action | args | 语义 |
|---|---|---|
| `startInput` | `{"kind": "text"\|"password"\|"number"\|"email"\|"url"\|"phone"\|"datetime"}` | 调用 `KeyboardController.onStartInput`，重置编辑器状态 |
| `replaceEditorText` | `{"text": "..."}` | 替换 `RecordingTextSession` 的编辑器文本并把光标放到末尾（模拟切换字段/预置文本） |
| `appendInput` | `{"text": "..."}` | 发送 `KeyboardAction.AppendInput`（按键/字符） |
| `tapCandidate` | `{"index": 0}` | 发送 `KeyboardAction.CandidateTapped` |
| `space` / `backspace` / `enter` | 无 | 发送对应 `KeyboardAction` |
| `toggleLanguage` | 无 | 发送 `KeyboardAction.ToggleLanguage` |
| `showLayer` | `{"layer": "letters"\|"numbers"\|"symbols"}` | 发送 `KeyboardAction.ShowLayer` |
| `shiftTap` | `{"now": 1000}` | 发送 `KeyboardAction.ShiftTapped`；两次 350ms 内可锁定大写 |
| `selectText` | `{"start": 1, "end": 1, "candidatesStart": -1, "candidatesEnd": -1}` | 设置 `RecordingTextSession` 选区并调用 `KeyboardController.onSelectionChanged` |
| `settle` | 无 | 仅 `advanceUntilIdle()`，用于显式等待异步查询 |
| `expectState` | 同 `expectedState` 字段的子集 | 中间断言；用于证明 pending 等瞬态确实发生 |
| `recreateController` | 无 | 销毁并重建 `KeyboardController` + 协程 scope（模拟横屏/配置变化）；同一个 `RecordingTextSession` 文本保留 |

### 2.4 expectedState 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `composing` | string | 键盘状态中的拼音 composing |
| `candidates` | string[] | 当前候选的完整词表（精确比较；通常用 `firstCandidate` 更稳） |
| `firstCandidate` | string | 当前候选第一项 |
| `candidatesEmpty` | bool | 是否无候选 |
| `candidatesFor` | string | 候选对应的 composing |
| `language` | `"chinese"` / `"english"` | 当前输入语言 |
| `layer` | `"letters"` / `"numbers"` / `"symbols"` | 当前键盘层 |
| `shift` | `"off"` / `"shifted"` / `"locked"` | Shift 状态 |
| `pendingConfirmation` | `"none"` / `"space"` / `"candidate"` / `"punctuation"` | 待确认动作 |
| `selectionStart` / `selectionEnd` | int | `RecordingTextSession` 的光标/选区 |

## 3. 执行口径（JVM 回放，不代表真机）

- 测试类：`app/src/test/java/com/bitap/keyboard/qa/EventSequenceReplayTest.kt`。
- 运行：
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.qa.EventSequenceReplayTest" --rerun-tasks --console=plain
  ```
- 回放使用 `RecordingTextSession`：它在 JVM 中维护编辑器文本、光标/选区、composing 区间，并记录 `commitText` / `setComposingText` / `finishComposingText` / `deleteBackward` / `sendEnter` 日志；结构类似 `AndroidTextSession`，退格遵循 `UnicodeGrapheme.lastGraphemeLength`。
- 测试使用**测试内固定的合成词库**（`EventSequenceReplayTest.DEFAULT_TEST_DICTIONARY`，仅 20 余条常见词），不读取也不修改 `app/src/main/assets/pinyin_dict.tsv`，从而保证期望句不会进入生产词库、回放结果不受引擎版本波动影响。
- 查询使用 `PinyinEngine` 的真实搜索路径；异步用 `kotlinx-coroutines-test` 的 `StandardTestDispatcher` + `TestCoroutineScheduler` 虚拟时间推进，不 sleep。
- 每个 case 独立创建 `RecordingTextSession` 和 `KeyboardController`；`recreateController` 会销毁旧 scope 后新建 controller，验证状态不泄漏。

## 4. 边界与不覆盖

- 只验证 `KeyboardController` + `TextSession` 的 JVM 行为，不验证真机 IME 生命周期、系统输入法框架、Compose 渲染、触控、横屏真实配置回调、ADB 点击。
- 不覆盖多指触控、滑行、长按、语音、手写；这些在 D08/D09 另测。
- 不覆盖 AI 生成（AI 词库需模型与 native 运行时，JVM 测试使用 stub 服务；AI 资源在 D02/设备任务）。
- 事件数据全部为合成场景，不含真实聊天、账号、密码或个人信息。
- 期望文本是「该场景下控制器产出的正确文本」，不是语言模型质量评价；引擎准确率由 `eval/pinyin_cases_*_v2_*.tsv` 与 D01 协议评测。

## 5. 维护规则

- 新增事件必须同时更新本文件类别表与 `EventSequenceReplayTest` 的必需类别断言（如新增 category）。
- 不得为了通过测试修改 `app/src/main/assets/pinyin_dict.tsv`；如需新词，只允许加进测试内合成词库并在本文件说明。
- 不把 `event_cases.jsonl` 的期望字符串用于生产词库/算法奖励。
