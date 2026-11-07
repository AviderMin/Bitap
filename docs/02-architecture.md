# Bitap 架构设计

版本：0.1｜状态：冻结｜负责人：Lead

## 1. 总览

```
┌────────────────────────────── Android App ──────────────────────────────┐
│ MainActivity (Compose)                                                  │
│   └─ ui/ 首页、设置、模型管理、主题预览                                  │
│                                                                          │
│ BitapImeService : InputMethodService                                    │
│   ├─ ImeComposeHost (ComposeView + lifecycle/savedstate owners)          │
│   ├─ KeyboardScreen (ime/)  ← 观察 KeyboardState                         │
│   ├─ KeyboardController (ime/)                                          │
│   │    ├─ KeyboardReducer (纯函数，可单测)                               │
│   │    ├─ TextSession → InputConnection                                 │
│   │    └─ PinyinEngine → assets/pinyin_dict.tsv                         │
│   └─ AiLexiconCoordinator → ai/ LocalAiService                          │
│                                                                          │
│ ai/   ModelStore (私有目录) → LiteRtQwenEngine (LiteRT-LM)               │
│ settings/ SettingsRepository (SharedPreferences + StateFlow)            │
│ ui/theme/ BitapTheme → App: MiuixTheme/GlasmioTheme                     │
│          BitapKeyboardTheme → 键盘: Paper/Ink/Aurora（独立 palette）     │
└──────────────────────────────────────────────────────────────────────────┘
```

## 2. 模块边界与写入范围

| 目录/文件 | 所有者 | 职责 |
| --- | --- | --- |
| `app/src/main/java/com/bitap/keyboard/ime/**`, `core/**` | ime-dev | 输入法服务、Compose 键盘、输入状态机、TextSession |
| `app/src/main/java/com/bitap/keyboard/ai/**` | ai-dev | 模型存储、LiteRT-LM 引擎、AI 词库候选生成与结果解析 |
| `app/src/main/java/com/bitap/keyboard/ui/**`（theme 契约文件除外） | theme-dev | 双主题实现、首页、设置页、模型管理 UI |
| `app/src/main/java/com/bitap/keyboard/ui/theme/ThemeContract.kt` | Lead | 调色板契约（只读引用；实现由 theme-dev 写 `BitapTheme.kt`） |
| `app/src/test/java/com/bitap/keyboard/qa/**` | qa-dev | 验收测试与报告 |
| 构建文件、Manifest、资源、`docs/`、`scripts/`、`assets/` | Lead | 基座、文档、词库、集成 |

规则：同一文件同一时间只有一个 owner；跨边界修改先发消息，不直接改对方文件。

## 3. IME 生命周期

```
onCreate → 创建 SettingsRepository 观察者
onStartInput → TextSession 绑定当前 InputConnection；读取 editorInfo.inputType
onCreateInputView → ComposeView(BitapKeyboardTheme(settings.keyboardTheme)) → KeyboardScreen
按键 → KeyboardController.dispatch(action)
     → reducer 更新 state（StateFlow）
     → TextSession.commitText / setComposingText
onFinishInput → 清空 composing / 取消 AI 任务（不保存文本）
onDestroy → 释放 ComposeView、AI 协程、关闭引擎
```

关键决策：**IME 不持有 Activity**；ComposeView 由 Service 自己实现 `LifecycleOwner`、`ViewModelStoreOwner`、`SavedStateRegistryOwner`，保证 Compose 生命周期正确。

## 4. 键盘状态机

单向下行数据流：

```
UI event → KeyboardAction → KeyboardReducer.reduce(state, action) → new state
                                    │
                                    └→ TextSession side effects（提交/删除/composing）
```

- `KeyboardUiState`：语言、Shift、层、composing、本地候选、AI 词库候选、模型状态、键盘高度。
- Reducer 保持纯函数；`TextSession` 由 Controller 在 reducer 之后调用。
- PinyinEngine 查询在 `Dispatchers.Default`；50 ms 内返回；输入序列变化时取消旧查询；连续拼音通过 DP 词段组合补全整句候选。
- 候选点击提交；空格提交首选；无候选时按原始 composing 上屏。

## 5. AI 词库管线

```
用户点击候选条 ✦ AI → 读取当前拼音/光标前最近一段（≤128 字符）
                    → AiLexiconCoordinator.suggest(pinyin, context)
           → ModelStore.requireModelPath()
                    → LiteRtQwenEngine.rewrite(词库 prompt)（懒加载，单实例，CPU）
                    → Flow<AiEvent> → AiLexiconParser
                    → 追加到候选条（AI 标签）
                    → 用户点选候选 → TextSession.commitText
```

- 本地 PinyinEngine 始终先出候选，AI 只做补充；
- 引擎懒加载：首次 AI 请求才 `Engine.initialize()`，避免影响键盘启动；
- 并发：同一时刻只有一个 AI 请求；新的请求取消前一个；
- 超时：20 s；失败/无结果不阻断键盘；
- 释放：`onDestroy` 或设置关闭 AI 时关闭 `Engine`。

## 6. 数据存储

| 数据 | 位置 | 生命周期 |
| --- | --- | --- |
| 主题/开关偏好 | SharedPreferences `bitap_settings` | 持久 |
| 本地模型 | `filesDir/models/*.litertlm` | 持久，用户可删除 |
| 当前 composing/候选 | 内存 | 输入结束清除 |
| AI prompt/结果 | 内存 | 面板关闭即丢，不写日志 |
| 输入历史/联系人 | 不采集 | — |

## 7. 构建基线

- Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.21（AGP 新 DSL 关闭，兼容 KGP）。
- compileSdk 37（API 37.0），targetSdk 36，minSdk 33（Glasmio 要求）。
- Compose BOM 2026.09.00；Miuix 0.9.4；Glasmio 0.1.2；LiteRT-LM 0.19.1。
- JDK 17+；本机在 JDK 25 上验证。

## 8. 测试策略

- 纯 JVM：Reducer、PinyinEngine、ResultSanitizer、SettingsRepository 映射逻辑。
- Android instrumentation（有设备时）：IME 绑定、候选点击、主题切换、导入流程。
- 构建门禁：`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- 性能门禁：冷启动键盘 ≤ 300 ms；按键反馈 ≤ 50 ms；AI 超时 20 s。

## 9. 架构决策记录

见 [ADR-0001](adr/0001-foundation.md)。后续变更需写 ADR，不能只改代码。
