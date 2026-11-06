# Bitap 质量门槛与验收

版本：0.1｜状态：冻结｜负责人：qa-dev + Lead

## 1. Definition of Done

一个任务完成必须同时满足：

- 代码在 `:app:assembleDebug` 中编译通过；
- 新增纯逻辑有 JVM 单测，`:app:testDebugUnitTest` 通过；
- 不引入网络权限、分析 SDK、输入日志；
- 变更文件在任务声明的写入范围内；
- 公开 API/行为变化同步更新 docs；
- Lead 复核 diff 与构建结果。

## 2. 必跑命令

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

## 3. 验收矩阵

| # | 场景 | 预期 |
| --- | --- | --- |
| A1 | 冷启动首页 | 无崩溃；主题样例可见；三步清单可点击。 |
| A2 | 启用键盘 | 系统输入法设置可打开；Bitap 出现在列表中。 |
| A3 | 输入英文 | 字符即时上屏；Shift 生效；退格/回车正确。 |
| A4 | 输入拼音 `nihao` | 候选出现「你好」；点选后上屏；空格提交首选。 |
| A5 | 符号层 | `123`/`#+=`/`ABC` 切换正确；符号上屏。 |
| A6 | 主题 Miuix 浅/深 | 键帽、文字、候选对比清晰；无裁切。 |
| A7 | 主题 Glasmio 浅/深 | 玻璃材质可读；回退不透明模式正常。 |
| A8 | AI 未装模型 | ✦ 禁用；点击有导入提示；键盘不受影响。 |
| A9 | AI 已装模型 | 选文字 → ✦ → 润色 → 流式结果 → 插入；原文在插入前不变。 |
| A10 | AI 取消/超时 | 取消后无结果上屏；超时提示重试；键盘仍可用。 |
| A11 | 隐私 | `aapt dump permissions` 无 INTERNET；飞行模式全功能。 |
| A12 | 无障碍 | TalkBack 可读出主要按键；触控目标达标。 |

## 4. 单测覆盖

- `KeyboardReducerTest`：字母、Shift、中文 composing、退格、符号层。
- `PinyinEngineTest`：`nihao`→你好、前缀候选、无匹配、大小写。
- `AiResultSanitizerTest`：引号/前缀/think 块/超长/空结果。
- `SettingsRepositoryTest`（纯映射可抽离）：枚举回退、默认值。
- `TextSessionTest`：使用 FakeTextSession 验证提交/删除顺序。

## 5. 性能与稳定性

- 键盘冷启动 p90 ≤ 300 ms；按键处理主线程 ≤ 50 ms。
- 拼音查询在后台线程，输入 20 个字母不卡顿。
- AI 超时 20 s；连续点击 5 次不会并发多个引擎请求。
- 无网络依赖；无未捕获异常导致 IME 崩溃（捕获并回退系统输入视图）。

## 6. 交付物

- 可安装 debug APK（约 107 MB）与 minify release demo APK（约 27.5 MB，debug key 签名）。
- 测试报告 `docs/QA_REPORT.md`（qa-dev 写）。
- 隐私检查输出（权限列表）。
- 构建日志摘要。
- Git 提交记录（Lead 最终提交）。
