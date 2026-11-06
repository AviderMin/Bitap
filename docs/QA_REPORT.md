# Bitap 0.1 QA 验收报告

版本：0.1 ｜ 负责人：qa-dev ｜ 日期：2026-11-06 ｜ 任务：task-5（T4 构建验收、测试与 QA 报告）

> 2026-11-06 23:26 起用户在真机 `22021211RC` 上关闭安全锁屏，Lead 已补做 Via 浏览器真机冒烟测试，结果见 [DEVICE_TEST_REPORT.md](DEVICE_TEST_REPORT.md)。本文 §2.7 保留首轮“锁屏导致 BLOCKED”快照，最终真机结论以 DEVICE_TEST_REPORT 为准。

> 本报告主体只记录 **23:26 前**实际执行过的命令与结果。该时点前没有可用真机，因此设备相关项标记 **BLOCKED**；23:26 用户关闭锁屏后完成的 Via 真机测试见 [DEVICE_TEST_REPORT.md](DEVICE_TEST_REPORT.md)，不伪造任何未执行项。

---

## 1. 验收环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Microsoft Windows 11 专业版（Build 28000，64-bit） |
| 工作目录 | `D:\Projects\Github\Bitap` |
| Git 状态 | `master` @ `d35d2a4 Initial commit`；`app/`、`docs/`、`scripts/`、构建文件均为未跟踪的新文件（本次验收针对当前工作树） |
| JDK | Microsoft OpenJDK 25.0.4.1 LTS（`JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\`） |
| Gradle | 9.7.1（wrapper）；内置 Kotlin 2.4.0，项目 Kotlin 2.4.21 |
| AGP | 9.4.1 |
| Android SDK | `D:\Applications\Android-SDK`，build-tools 36.0.0（aapt v0.2-13193326），compileSdk 37 |
| 应用配置 | applicationId `com.bitap.keyboard`，versionName 0.1.0，minSdk 33，targetSdk 36 |
| 设备 | **无**（`adb devices` 为空；`~/.android/avd` 不存在） |

验收时的工作树快照：**最终源码最后修改时间 2026-11-06 22:50:24**（`ime/KeyboardScreen.kt`，A12 触控目标修复）；**最终 APK 构建于 22:51:06**。首次验收快照为 22:43:26；A12 修复后已全量重跑 test / lint / assemble，本报告数字均为修复后结果。

---

## 2. 必跑命令与结果

### 2.1 单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

- 结果：**BUILD SUCCESSFUL**（exit code 0）
- 测试数量：**77 tests / 0 failures / 0 errors / 0 skipped**
  - 原有测试基线：5 个测试类、**51 tests**（与任务描述一致，全部通过）
  - 本次 QA 新增：5 个测试类、**26 tests**（全部通过）
- 数据来源：`app/build/test-results/testDebugUnitTest/TEST-*.xml`（JUnit XML 汇总）

| 测试类 | 测试数 | 失败 | 错误 | 跳过 | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| `com.bitap.keyboard.ai.AiPromptBuilderTest` | 6 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.ai.AiResultSanitizerTest` | 12 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.ai.ModelFileRulesTest` | 4 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.core.KeyboardReducerTest` | 19 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.core.PinyinEngineTest` | 10 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.qa.QaAiSafetyTest` | 6 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.qa.QaKeyboardControllerIntegrationTest` | 7 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.qa.QaPinyinAcceptanceTest` | 5 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.qa.QaPrivacyGuardTest` | 4 | 0 | 0 | 0 | PASS |
| `com.bitap.keyboard.qa.QaSettingsContractTest` | 4 | 0 | 0 | 0 | PASS |
| **合计** | **77** | **0** | **0** | **0** | **PASS** |

### 2.2 Lint

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

- **第一次执行：FAILED（exit code 1）**
  - 唯一 error：`D:\Projects\Github\Bitap\local.properties:1: Error: Windows file separators (\) and drive letter separators (':') must be escaped ... [PropertyEscape]`
  - 根因：本机 SDK 路径 `D:\Applications\Android-SDK` 在 property 文件中未正确转义，属于**环境配置问题**，不是产品代码问题。
  - 处置：Lead 修复为 `sdk.dir=D\:\\Applications\\Android-SDK` 后重跑。QA 未修改该文件。
- **修复后执行：BUILD SUCCESSFUL（exit code 0）**
- 结果：**0 errors / 14 warnings**（新增 QA 测试后、以及 A12 触控目标修复后，最终执行仍为 0 errors / 14 warnings）

14 个 warning 分类（全部非阻塞、可接受）：

| # | 类型 | 位置 | 分类说明 |
| --- | --- | --- | --- |
| 1 | `OldTargetApi` | `app/build.gradle.kts:17`（targetSdk 36 < compileSdk 37） | 可接受：docs/02 明确 targetSdk 36 |
| 2 | `AndroidGradlePluginVersion` | `gradle/wrapper/gradle-wrapper.properties:3`（Gradle 9.7.1 → 9.8.2 可升级） | 可接受：版本按 docs/02 冻结 |
| 3 | `AndroidGradlePluginVersion` | `gradle/libs.versions.toml:2`（AGP 9.4.1 → 9.5.1 可升级） | 同上 |
| 4 | `ChromeOsAbiSupport` | `app/build.gradle.kts:22`（仅 arm64-v8a） | 可接受：0.1 只发布手机 arm64 构建 |
| 5 | `ObsoleteSdkInt` | `app/src/main/res/mipmap-anydpi-v26`（minSdk 33） | 可接受：资源目录冗余，无功能影响 |
| 6 | `UnusedResources` | `app/src/main/res/values/colors.xml:4`（bitap_accent） | 轻微：可清理 |
| 7 | `UnusedResources` | `app/src/main/res/values/strings.xml:6`（tagline） | 轻微：可清理或后续使用 |
| 8 | `MonochromeLauncherIcon` | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:2` | 轻微：Android 16 图标着色建议 |
| 9–14 | `UseKtx` ×6 | `app/src/main/java/com/bitap/keyboard/settings/SettingsRepository.kt:22/26/30/34/38/42` | 风格建议：`prefs.edit()` → KTX 扩展 |

结论：**Lint 门禁通过（0 error）**；14 个 warning 均不阻塞 0.1，建议后续版本清理 UnusedResources/UseKtx。

### 2.3 Debug APK 构建

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

- 结果：**BUILD SUCCESSFUL**（exit code 0）
- APK 路径：`D:\Projects\Github\Bitap\app\build\outputs\apk\debug\app-debug.apk`
- APK 大小：**107,521,175 bytes = 107.52 MB（十进制）/ 102.54 MiB（Windows 显示）**
- SHA-256：`479DB0790C1B22B157EE8B94CCACA6B8FA3F299C349BC4C9B4905EA56B5A4EA4`（独立 App/键盘主题 + AI 词库 + 固定键高 + 候选稳定构建）
- APK 内条目：169 个；**不含任何 `.litertlm` 模型文件**（模型由用户导入）
- 主要体积构成：`classes.dex` 42.3 MB、`lib/arm64-v8a/liblitertlm_jni.so` 22.7 MB、其余 classesN.dex 若干；`assets/pinyin_dict.tsv` 586,532 bytes
- 说明：debug 包未混淆、未裁剪资源、包含 Compose 调试产物与 LiteRT JNI，107 MB 属预期；不能直接与 docs/05 的发布口径（release APK ≈ 27.5 MB）比较。

### 2.4 隐私检查

#### a) aapt dump permissions（构建产物，build-tools 36.0.0）

```powershell
& 'D:\Applications\Android-SDK\build-tools\36.0.0\aapt.exe' dump permissions app/build/outputs/apk/debug/app-debug.apk
```

实际输出：

```text
package: com.bitap.keyboard
permission: com.bitap.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.bitap.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

- **没有 `android.permission.INTERNET`**
- **没有 `android.permission.ACCESS_NETWORK_STATE`**
- 唯一的 permission 是 androidx 自动生成的 `com.bitap.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（应用私有、仅用于未导出动态接收器，不涉及网络）。

#### b) 构建产物 Manifest 属性（aapt dump xmltree）

```text
android:allowBackup(0x01010280)=(type 0x12)0x0          → false
android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0 → false
android:dataExtractionRules=@0x7f0c0000                  → 存在（data_extraction_rules.xml 排除 root/file/database/sharedpref）
service android:permission="android.permission.BIND_INPUT_METHOD"
```

#### c) 生产代码静态检查

| 检查项 | 命令/方式 | 结果 |
| --- | --- | --- |
| 日志打印 | `grep -R "Log\.[dviwe]\s*(" app/src/main` | **0 处** |
| `println` | `grep -R "println\s*(" app/src/main` | **0 处** |
| 网络相关代码 | `grep -R "http://|https://|java.net|okhttp|retrofit|URLConnection|WebView" app/src/main` | 仅 5 处 XML 命名空间声明（AndroidManifest.xml、ic_launcher.xml ×2、method.xml），**无运行时代码** |
| INTERNET 声明 | `grep -R "android.permission.INTERNET" app/src/main` | **0 处** |
| 网络/分析 SDK | `:app:dependencies --configuration debugRuntimeClasspath` 过滤 okhttp/retrofit/volley/firebase/analytics/crashlytics/play-services | **0 个** |

结论：**隐私硬边界全部满足**（无网络权限、无日志、无分析 SDK、备份关闭、明文流量关闭、模型不入包）。

### 2.5 一键复现脚本

新增 `scripts/verify.ps1`：依次执行 `testDebugUnitTest` → `assembleDebug` → aapt 权限检查（无 INTERNET / ACCESS_NETWORK_STATE），任一步失败 exit 1。

实测（2026-11-06）：

```powershell
.\scripts\verify.ps1
```

结果：**RESULT: PASS，SCRIPT_EXIT=0**（A12 修复后最终构建上复跑同样 PASS）；脚本正确打印 APK 路径、大小与权限列表。

### 2.6 最终复验（A12 修复后）

2026-11-06 22:50 Lead 修复 `KeyboardScreen.kt` 触控目标（AI ✦ 44→48dp，CandidateChip 38→48dp，ToneChip/PanelTextButton/PanelAccentButton 32→48dp，AI 面板最大高度 180→260dp）。QA 于 22:51 全量重跑：

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 单元测试 | `:app:testDebugUnitTest` | exit 0；**77 tests / 0 failures / 0 errors / 0 skipped** |
| Lint | `:app:lintDebug` | exit 0；**0 errors / 14 warnings** |
| Debug APK | `:app:assembleDebug` | exit 0；107,521,175 bytes，SHA-256 `479DB079...` |
| 一键门禁 | `scripts\verify.ps1` | exit 0；`RESULT: PASS` |


### 2.7 真机 Via 冒烟测试（2026-11-06 22:57–23:10）

连接设备：`22021211RC`（model `munch`），Android 16 / API 36，Via 浏览器 `mark.via`。安装 demo APK 后，ADB 成功：

- 注册并启用 IME：`ime enable/set com.bitap.keyboard/.ime.BitapImeService` 成功；
- 打开 Via 主页并点击搜索框，Via 进入地址栏输入态；
- 首次弹出 Bitap 键盘时，Bitap IME 进程崩溃：
  `java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.LinearLayout{... #android:id/parentPanel}`，堆栈落在 `AbstractComposeView.onAttachedToWindow` / `InputMethodService.setInputView`。
- 根因：`InputMethodService` 的 `SoftInputWindow` 是 Dialog，Compose 在 DecorView 上查找 owner，仅在 ComposeView 上设置不够。
- 修复：`BitapImeService` 在 `onCreate`、`onCreateInputView`、`onWindowShown` 将 `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` 同时挂到 `window.window.decorView`；debug APK 重新构建并安装。
- 修复后复测被阻塞：设备进入安全锁屏（`secure=true`、有锁屏凭据），ADB 无法解锁，`screencap` 为黑屏；需要用户手动解锁后继续在 Via 中复测。**因此本条不标 PASS。**

---

## 3. 本次新增 QA 测试清单

写入范围：`app/src/test/java/com/bitap/keyboard/qa/**`（未改动任何生产代码）。

| 文件 | 覆盖内容 | 测试数 |
| --- | --- | ---: |
| `QaSettingsContractTest.kt` | BitapSettings 默认值符合产品规范；ThemeFamily/DarkMode 持久化枚举名稳定（SharedPreferences 按 name 存储）；copy 不可变性 | 4 |
| `QaAiSafetyTest.kt` | Sanitizer 3× 边界（300 接受 / 301 拒绝）；think 块 + 前言 + 引号组合清理；空结果失败；AI 在 Completed 之前 Insert 必须是 no-op；结果长度上限；AI 关闭时面板不可打开 | 6 |
| `QaPinyinAcceptanceTest.kt` | 真实词库（asset）规模的端到端检查：>10k 词条、`nihao`→「你好」首选、候选 ≤8 且去重、前缀查询、无匹配不抛异常 | 5 |
| `QaKeyboardControllerIntegrationTest.kt` | FakeTextSession 跨模块验证：中文组词→候选→提交顺序、退格序列（setComposing→finish→delete）、回车、中英切换、AI 面板读取会话选中文本、AI 关闭时不弹面板、onFinishInput 清理 composing | 7 |
| `QaPrivacyGuardTest.kt` | 静态隐私门禁：Manifest 无 INTERNET/ACCESS_NETWORK_STATE、allowBackup=false、usesCleartextTraffic=false、BIND_INPUT_METHOD；生产源码无 Log/println | 4 |
| **合计** | | **26** |

---

## 4. 验收矩阵 A1–A12

> 图例：**PASS** = 已实际执行并通过；**BLOCKED** = 本机无设备/无模型，无法执行，不伪造通过；**静态审查** = 只完成代码/产物层检查，不等同于运行时通过。

| # | 场景 | 状态 | 证据 / 说明 |
| --- | --- | --- | --- |
| A1 | 冷启动首页 | **BLOCKED** | 无设备/AVD，无法启动 App。静态：MainActivity→BitapApp→BitapTheme 编译通过，assembleDebug 成功；未做运行时验证。 |
| A2 | 启用键盘 | **BLOCKED** | 无设备。静态：AndroidManifest 声明 IME service（BIND_INPUT_METHOD + android.view.InputMethod intent-filter），`res/xml/method.xml` 有 en_US / zh_CN 两个 subtype，设置页可跳 `Settings.ACTION_INPUT_METHOD_SETTINGS`；未验证系统列表中实际出现。 |
| A3 | 输入英文 | **BLOCKED** | 无设备。JVM 证据：`KeyboardReducerTest.englishTypingAppliesShiftOnce`、`QaKeyboardControllerIntegrationTest.enterCommitsComposingThenSendsEnterWhenIdle`（Shift/退格/回车逻辑）通过；未验证真实上屏。 |
| A4 | 输入拼音 `nihao` | **BLOCKED** | 无设备。JVM 证据：`QaPinyinAcceptanceTest` 对真实词库断言 `nihao`→「你好」首选、候选 ≤8；`QaKeyboardControllerIntegrationTest` 验证候选点击/空格提交首选顺序。未验证候选条渲染与真实 IME 上屏。 |
| A5 | 符号层 | **BLOCKED** | 无设备。JVM 证据：`KeyboardReducerTest.symbolLayerCommitsComposingAndTypesSymbols`；静态：KeyboardScreen 的 123 / #+= / ABC 层切换与符号键完整。未做触屏验证。 |
| A6 | 主题 Miuix 浅/深 | **BLOCKED** | 无设备，无法渲染对比度/裁切检查。静态：`BitapTheme.kt` 真实调用 `MiuixTheme(controller=ThemeController(...))`，MiuixLight/DarkPalette 与 docs/04 token 一致。 |
| A7 | 主题 Glasmio 浅/深 | **BLOCKED** | 无设备。静态：`BitapTheme.kt` 真实调用 `GlasmioTheme` + `GlasmioBackdropHost`（IME 内用等价 aurora 背景避免拉伸），GlasmioLight/DarkPalette 与 docs/04 一致；玻璃可读性未渲染验证。 |
| A8 | AI 未装模型 | **BLOCKED** | 无设备。JVM 证据：`KeyboardReducerTest.aiPanelShowsUnavailableWhenModelIsNotReady`、`QaAiSafetyTest.aiPanelCannotOpenWhenSettingIsOff`；静态：未就绪时 ✦ 45% 透明度，点击显示「本地模型未就绪，请先在 Bitap 中导入模型」。未验证真机提示与键盘可用性。 |
| A9 | AI 已装模型 | **BLOCKED** | 无设备，且本机无 `.litertlm` 模型（约 332 MB，未下载），引擎从未实际初始化。仅完成代码审查（懒加载、单请求、温度 0.2、maxTokens 128、20s 超时）。**未执行任何真实推理。** |
| A10 | AI 取消/超时 | **BLOCKED** | 无设备。静态：`LiteRtQwenEngine` 使用 `withTimeout(20_000)`、`conversation.cancelProcess()`；`KeyboardController` 以 `aiToken` 丢弃过期事件。未做运行时取消/超时验证。 |
| A11 | 隐私 | **PASS（产物层）/ BLOCKED（飞行模式）** | aapt dump permissions 无 INTERNET、无 ACCESS_NETWORK_STATE；APK manifest allowBackup=false、usesCleartextTraffic=false；生产代码无 Log/println、无网络库。**飞行模式全功能实测因无设备 BLOCKED。** |
| A12 | 无障碍 | **BLOCKED（真机）；静态修复已完成** | 无设备，无法运行 TalkBack。`KeyboardScreen.kt` 5 处触控目标已由 Lead 修复为 ≥48 dp（AI ✦ 44→48、CandidateChip 38→48、ToneChip/PanelTextButton/PanelAccentButton 32→48，AI 面板最大高 180→260dp），QA 已复核源码并重跑构建；键盘字母/功能键 `heightIn(min = 48.dp)` 达标；所有键均有 contentDescription。**真机 TalkBack/触控尺寸仍需在 A12 验收时复验。** |

汇总：A11 的构建产物隐私检查 **PASS**；A12 的 5 处触控目标已**静态修复**并复核；其余 A1–A10、A12 的运行时验收因无设备/无模型 **BLOCKED**。

---

## 5. 已知限制与问题清单

### 5.1 A12 触控目标（已在最终版修复）

QA 在首轮静态审查中发现 5 处交互控件低于 docs/01 §6 与 docs/04 §6 要求的 48 dp；已上报 Lead，并由 Lead 在最终源码中修复（2026-11-06 22:50）。QA 仅复核源码与重跑构建，未修改生产代码。

| 文件:行 | 修复前 | 修复后（当前源码） | 状态 |
| --- | --- | --- | --- |
| `app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt:173`（AI ✦ 入口） | `.size(44.dp)` | `.size(48.dp)` | ✅ 已修复 |
| `KeyboardScreen.kt:201`（候选词 chip） | `.heightIn(min = 38.dp)` | `.heightIn(min = 48.dp)` | ✅ 已修复 |
| `KeyboardScreen.kt:361`（改写动作 ToneChip） | `.heightIn(min = 32.dp)` | `.heightIn(min = 48.dp)` | ✅ 已修复 |
| `KeyboardScreen.kt:384`（AI 面板「放弃」） | `.heightIn(min = 32.dp)` | `.heightIn(min = 48.dp)` | ✅ 已修复 |
| `KeyboardScreen.kt:400`（AI 面板「插入」） | `.heightIn(min = 32.dp)` | `.heightIn(min = 48.dp)` | ✅ 已修复 |
| `KeyboardScreen.kt:238`（AI 面板最大高度） | `.heightIn(max = 180.dp)` | `.heightIn(max = 260.dp)` | ✅ 已修复 |

复核方式：`grep -n "size(48.dp)\|heightIn(min = 48.dp)\|heightIn(max = 260.dp)" app/src/main/java/com/bitap/keyboard/ime/KeyboardScreen.kt`。修复后已重跑 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`（均通过，见 §2.6）。**说明：这是静态修复与构建验证；TalkBack 与实际触控体验仍属 A12 真机验收，当前 BLOCKED。**

### 5.2 体积口径（docs/05 §5 已更新为 release APK ≈ 27.5 MB）

- docs/05 §5 原为「APK < 25 MB」，2026-11-06 已由 Lead 更新为「优化后 release APK ≈ 27.5 MB，模型单独导入」，该偏差已接受。
- 交付 demo APK：`dist/bitap-0.1.0-demo.apk` = **27,494,743 bytes = 27.49 MB（十进制）/ 26.22 MiB**，与 ≈27.5 MB 口径一致；SHA-256 `89AD96BDCA370D121FE85F6D8C652B1B48E576A9D6A51F0D21C4AF7F4FAF1331`（最终 release 签名版，见 `dist/SHA256SUMS.txt`）。
- 本次门禁产物 `app-debug.apk` = 107,521,175 bytes（107.52 MB 十进制 / 102.54 MiB；debug 未优化、含 LiteRT JNI，仅作测试安装包，不与 release 口径比较）。
- 模型权重不在 APK 内（已验证），体积主要来自 LiteRT-LM AAR 的原生库与 Compose/Miuix/Glasmio 依赖。

### 5.3 其他限制

1. **无设备/无模拟器**：`adb devices` 为空，无 AVD；所有 IME 运行时、触屏、TalkBack、飞行模式验收无法执行。
2. **AI 未实测**：没有 `.litertlm` 模型文件，`LiteRtQwenEngine` 的初始化/推理/取消/超时未在真机运行；`docs/03 §7` 的 20 条人工评测集未执行。
3. **Lint 14 warnings**：均非阻塞，详见 §2.2。
4. `PinyinEngineTest.realDictionaryContainsNihao` 使用 `assumeTrue`：若词库缺失会 skip 而不是失败；本次实际 skipped=0。
5. 首次 lint 失败的 `local.properties` 是环境文件（通常不入 Git），修复不影响产品代码；后续换机器需按同样规则转义 Windows 路径。
6. `dist/bitap-0.1.0-demo.apk` 使用 Android debug keystore 签名（docs/08 §5），仅用于内部安装验证，不可直接上架。

---

## 6. 给 Lead 的发布建议

**结论：构建/测试/隐私门禁 PASS，A12 触控目标已静态修复；建议作为 0.1 内部调试版（technical preview）交付；不建议直接对外发布。**

发布前必须完成（阻断项）：

1. **真机验收 A1–A12**：需在 API 33+ 真机（含中文输入法环境）逐项执行并记录；本报告所有 BLOCKED 项不得视为通过（含 A12 的 TalkBack 与实际触控体验）。
2. **AI 实测**：导入真实 `Qwen3-0.6B` 模型，跑 docs/03 §7 的 20 条评测（≥18/20 可读且不改原意、0 条自动上屏、0 条解释性前言）。
3. **正式发布签名**：`dist/bitap-0.1.0-demo.apk` 使用 Android debug keystore 签名（docs/08 §5），不可直接上架；正式发布需配置 release keystore。

已解决 / 已接受项（不阻断 technical preview）：

- **A12 触控目标**：5 处 <48 dp 已由 Lead 修复为 48 dp，AI 面板最大高度 180→260 dp；QA 已复核源码并重跑构建（§5.1）。
- **release 体积**：docs/05 §5 已更新为「优化后 release APK ≈ 27.5 MB」；`dist/bitap-0.1.0-demo.apk` = 27.49 MB（十进制），符合新口径，偏差已接受（§5.2）。
- Lint 14 个 warning（OldTargetApi / AndroidGradlePluginVersion / ChromeOsAbiSupport / ObsoleteSdkInt / UnusedResources ×2 / MonochromeLauncherIcon / UseKtx ×6）。
- debug APK 107 MB 仅用于测试安装；正式分发使用 release。
- 词库 29,999 条（30,000 行含表头）、586 KB，满足离线候选需求。

**隐私发布检查清单（docs/05 §5）逐项结果：**

| 检查项 | 结果 |
| --- | --- |
| aapt 不含 INTERNET | ✅ PASS |
| 全仓库无运行时 http 调用 | ✅ PASS（仅 XML 命名空间） |
| 无 Log.d/Log.i 打印输入或模型文本 | ✅ PASS（0 处 Log/println） |
| allowBackup=false、usesCleartextTraffic=false | ✅ PASS（构建产物验证） |
| 依赖无广告/分析 SDK | ✅ PASS（依赖树过滤为空） |
| 模型不在 APK 内 | ✅ PASS（APK 无 .litertlm，词库 586 KB） |

---

## 7. 复现步骤（任何人可重跑）

```powershell
cd D:\Projects\Github\Bitap

# 1) 单元测试（期望 77 tests / 0 failures）
.\gradlew.bat :app:testDebugUnitTest --console=plain

# 2) Lint（期望 0 errors / 14 warnings）
.\gradlew.bat :app:lintDebug --console=plain

# 3) Debug APK（期望 BUILD SUCCESSFUL）
.\gradlew.bat :app:assembleDebug --console=plain

# 4) 一键门禁（单测 + assemble + 权限检查，失败 exit 1）
.\scripts\verify.ps1
```

测试结果 XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml`
Lint 文本报告：`app/build/reports/lint-results-debug.txt`
APK：`app/build/outputs/apk/debug/app-debug.apk`

---

*报告生成：qa-dev，2026-11-06（最终版，含 A12 修复后的全量复验）。所有数字取自本机实际命令输出与 JUnit XML 统计；无设备项已明确标记 BLOCKED。*
