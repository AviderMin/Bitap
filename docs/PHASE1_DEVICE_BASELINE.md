# 阶段一设备基线（2026-11-08）

> 设备侧 QA 报告。设备 serial `fe4ab855`，本轮由 device-qa 独占操作。采集时间：2026-11-08 11:33–11:43（Asia/Shanghai）。
> 原始输出目录：`.tmp/device-qa/`；脚本：`scripts/device-qa/collect_baseline.ps1`、`scripts/device-qa/pss.ps1`。

## 1. 设备与 ROM

| 项目 | 值 | 证据 |
|---|---|---|
| 设备 serial | `fe4ab855` | `adb devices -l` |
| 型号 / 品牌 / codename | 22021211RC / Redmi / `munch` | `getprop ro.product.model` 等 |
| 系统 | **LineageOS 23.2-20261102-NIGHTLY-munch**（Android 16） | `getprop ro.lineage.version` |
| Android / API | 16 / 36 | `ro.build.version.release` / `ro.build.version.sdk` |
| 构建 ID | BP4A.251205.006 | `ro.build.display.id` |
| 构建指纹 | `Redmi/munch/munch:13/RKQ1.211001.001/V816.0.9.0.ULMMIXM:user/release-keys`（vendor 指纹仍为 MIUI 基线） | `ro.build.fingerprint` |
| 构建类型 | user / `ro.debuggable=0` / `ro.adb.secure=1` | `getprop` |
| ABI | arm64-v8a | `ro.product.cpu.abi` |
| 分辨率 / 密度 | 1080×2400 / 440 dpi（物理） | `wm size`、`wm density` |
| 刷新率 | 120 Hz（supportedModes 60/120） | `dumpsys display` |
| 采集时电量 / 温度 | 100% / 25.8 °C / thermal status 0 | `dumpsys battery`、`dumpsys thermalservice` |

原始文件：`.tmp/device-qa/baseline-props.txt`、`baseline-getprop.txt`、`baseline-battery.txt`。

## 2. 当前安装包（基线）

| 属性 | 值 |
|---|---|
| package | `com.bitap.keyboard` |
| versionName / versionCode | **0.1.0 / 1** |
| minSdk / targetSdk | 33 / 36 |
| APK 路径 | `/data/app/~~hFfX4KFprklJvvwT3Ohw0w==/com.bitap.keyboard-FLd-W6NWUwNvrj-TpZAXag==/base.apk` |
| APK 大小 | **28,301,655 B** |
| APK SHA-256 | **CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0** |
| 签名 | APK Signature Scheme v3（demo key，`apkSigningVersion=3`） |
| debuggable | 否（flags=`[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`，无 DEBUGGABLE；因此 `run-as` 不可用） |
| firstInstallTime / lastUpdateTime | 2026-11-07 12:18:19 / 2026-11-07 23:16:44 |
| 安装方式 | `adb install`（initiatingPackageName=`com.android.shell`） |
| 应用内模型状态 | 已导入（首页「导入本地模型（可选）」显示 Qwen3-0.6B · 327.6 MB + 已完成）；模型文件位于应用私有目录，非 debuggable 包无法直接核验路径/字节数 |

该 hash 与历史 `docs/DEVICE_PERFORMANCE.md` 中 “After final” 包一致（CFA88667…），即当前设备安装的仍是上一轮 final demo 包，不是本轮新构建。

原始文件：`.tmp/device-qa/baseline-dumpsys-package.txt`、`baseline-pss-*.txt`。

## 3. 输入法启用状态

```text
$ adb -s fe4ab855 shell settings get secure default_input_method
com.bitap.keyboard/.ime.BitapImeService

$ adb -s fe4ab855 shell ime list -s
com.android.inputmethod.latin/.LatinIME
com.bitap.keyboard/.ime.BitapImeService
```

- Bitap 已启用且为默认输入法；系统内另有 AOSP LatinIME 可切换。
- 首页截图显示第 2 步「切换到 Bitap」未打勾（`baseline-04-app-home.png`），与 `settings get secure default_input_method` 的实际状态不一致；可能是首页状态刷新时序/展示问题，列入待复核观察项，不影响实际输入。

## 4. 空闲 PSS（dumpsys meminfo com.bitap.keyboard）

统一口径：`adb -s fe4ab855 shell dumpsys meminfo com.bitap.keyboard`，取 **TOTAL PSS（KB）**；不强制停止其他应用，不录屏，屏幕常亮，温度 25.8–26.0 °C。不同进程生命周期/键盘可见性对 PSS 影响很大，故分行列出，禁止跨口径比较。

| # | 采集条件 | TOTAL PSS | TOTAL RSS | Java Heap | Native Heap | 原始文件 |
|---|---|---:|---:|---:|---:|---|
| 1 | 本轮首次采集：进程已长期存活（pid 17879）、未主动改变键盘/输入状态 | **56,882 KB** | 203,020 KB | 34,056 KB | 9,560 KB | 见下方说明（终端输出，未单独落盘） |
| 2 | 清空输入后键盘可见，静置 5 s（同一进程，11:41） | 74,860 KB | 224,968 KB | 39,700 KB | 12,888 KB | `.tmp/device-qa/baseline-pss-kb-visible.txt` |
| 3 | 键盘收起（BACK）后静置 10 s（同一进程，11:41） | 63,208 KB | 213,312 KB | 34,188 KB | 10,984 KB | `.tmp/device-qa/baseline-pss-kb-hidden.txt` |
| 4 | `am force-stop` 后 8 s（系统重新绑定新进程 pid 32702） | 9,101 KB | 118,480 KB | 1,748 KB | 1,474 KB | `.tmp/device-qa/baseline-pss-fresh-idle.txt` |
| 5 | `am force-stop` 后 30 s（同上） | 6,172 KB | 111,284 KB | 48 KB | 408 KB | `.tmp/device-qa/baseline-pss-fresh-idle-30s.txt` |
| 6 | 重新预热键盘→收起→静置 60 s（新进程，11:44） | 25,803 KB | 171,152 KB | 5,480 KB | 9,304 KB | `.tmp/device-qa/baseline-pss-warm-idle-60s.txt` |

说明：

- 第 1 行是本轮首次 `dumpsys meminfo` 的原始终端输出，**56,882 KB**，与 D00 任务记录的历史口径 `56,730 KB` 相差 +152 KB（+0.27%，同量级）。该次输出未单独落盘为文件，数值直接来自执行记录；后续可复现条件请以第 2/3 行为准。
- 第 4/5 行是 force-stop 后的“冷绑定”进程，PSS 大部分为共享代码页（RSS 111 MB 但私有 PSS 很小），不代表用户态空闲成本，不应与热进程比较。
- 本设备 120 Hz、Android 16、LineageOS user build；历史 `DEVICE_PERFORMANCE.md` 报告的空闲 65,159 KB（AI off fresh）与 98,510 KB（AI on 键盘刚显示）均为不同口径，本表不混用。

## 5. 键盘唤起与 nihao 出候选 smoke（基线包）

操作脚本（手工等价）：`adb reverse tcp:8769 tcp:8769` → 打开本地测试页 `http://127.0.0.1:8769/p0.html`（Via 浏览器 `mark.via/.Shell`）→ 点击文本框 `(540,600)` → 逐键点击 `n i h a o`（按键坐标见 `.tmp/p0final/run_item1.ps1`）→ 截图 + uiautomator dump。

| 步骤 | 结果 | 证据 |
|---|---|---|
| 键盘唤起 | PASS：点击文本框后键盘窗口显示，`dumpsys input_method` `mInputShown=true` | `baseline-01-keyboard.png` |
| nihao 出候选 | PASS：候选区显示「拼音 nihao」，候选为 **你好 / 你哈哦 / 你号 / 尼好**（首候选「你好」） | `baseline-02-nihao.png`、`baseline-02-nihao.xml` |
| 选择首候选 | PASS：点击「你好」后输入框文本 `text='你好'`，页面状态 `len=2 sel=2-2 value="你好"` | `baseline-03-commit-nihao.png`、`baseline-03-commit-nihao.xml` |
| 应用启动 | PASS：`am start -n com.bitap.keyboard/.MainActivity` 正常显示首页，无崩溃 | `baseline-04-app-home.png`、`baseline-04-app-home.xml` |

```text
# uiautomator dump（节选，baseline-02-nihao.xml）
EditText  text='nihao'  bounds=[33,363][1047,833] FOCUSED id=main
TextView  text='len=5 sel=5-5 value="nihao"'  bounds=[33,864][1047,908] id=status
# 截图候选区（baseline-02-nihao.png）：你好  你哈哦  你号  尼好  ⌄  ✦ AI  ⚙
```

### 5.1 基线包完整行为 smoke（脚本 `scripts/device-qa/smoke.ps1`）

命令：`pwsh -NoProfile -File scripts/device-qa/smoke.ps1 -Tag baseline-smoke`（证据目录 `.tmp/device-qa/smoke/baseline-smoke-*`）。
结果：**10/10 PASS**（2026-11-08 11:55）。

| # | 步骤 | 结果 | 实测值 |
|---|---|---|---|
| 1 | 键盘唤起 | PASS | 页面状态 `len=0 sel=0-0 value=""`，键盘显示 |
| 2 | nihao 出候选 | PASS | `len=5 sel=5-5 value="nihao"`，候选「你好/你哈哦/你号/尼好」 |
| 3 | 选首候选 | PASS | 文本 `你好`，`len=2 sel=2-2` |
| 4 | 退格 | PASS | `你好` → `你`（`len=1`） |
| 5 | 标点 | PASS | `你，。`（`len=3`） |
| 6 | 连续输入 | PASS（不崩溃、文本增长） | 输入 `jintian`+`tianqi` 并分别选首候选后实际文本 `今天天气很好天气`（首候选为整句短语，属当前排序行为，非本项失败） |
| 7 | 设置页可打开 | PASS | 键盘 ⚙ 打开 Bitap App，滚动后找到「本地 AI 词库」 |
| 8 | AI 开关可见/可切换 | PASS | `checked=true`，关闭 2s 后重新开启，无崩溃 |
| 9 | 密码框行为 | PASS（按预期进入英文态） | 密码框输入 `nihao` 后保持 `pwdLen=5`，键盘显示「英文输入」、无中文候选 |

密码框“不学习”补充复核（新 debug 包可用 run-as）：final smoke 前后 `bitap_user_lexicon.xml` 均为 19 条、key 集合无新增（仅正常输入框的 你好/今天天气很好/天气 计数 +1），见 `.tmp/device-qa/lexicon-before-smoke.xml` 与 `lexicon-after-final-smoke.xml`；说明密码框输入未写入用户词库。

## 6. 已知限制与注意事项

1. **ADB 单指点击不等价于真实双拇指输入**：所有按键均为 `adb shell input tap` 注入事件，无法验证交叠按压、抬起顺序、跨行滑动等真实触控体验（对应研发文档 F04 的要求，需后续双拇指试打）。
2. **uiautomator dump 不包含 IME 窗口节点**：本机 dump 只返回前台应用（Via/WebView）节点；键盘候选必须靠截图识别，或依赖测试页回显的 `len=... value=...` 文本。
3. **基线包（安装前）不可调试**：`ro.debuggable=0` 且 APK flags 无 DEBUGGABLE，`run-as` 不可用；本轮后续已安装 debuggable debug APK（见 §7），D02 资源实测在 debug 包上完成。
4. 设备为 LineageOS user build，`adb root` 不可用；不要使用需要 root 的采集手段。
5. 单设备单 ROM（Android 16），不能代表 Android 13–15 或其他 ROM；本报告只对 `fe4ab855` 负责。
6. 基线采集时屏幕亮度 18（自动模式）；D02 内存测量期间按历史口径固定为 20 手动并在报告中记录温度。
7. 首页「切换到 Bitap」状态与系统实际默认输入法不一致（见 §3），需复核是否 UI 状态刷新问题。


## 7. 新 debug 包安装记录（本轮，附）

| 阶段 | APK | 大小 | SHA-256 | 安装/验证 |
|---|---|---:|---|---|
| 安装前旧包 | 历史 0.1.0 demo | 28,301,655 B | CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0 | versionCode=1 / versionName=0.1.0 / 非 debuggable |
| 本轮第一次 debug | app-debug.apk（11:57 构建） | 108,552,903 B | E06838C99F6A1E8D8C00B56CFDA00D58530DDCD14843A507A78AC44B0A46AEC5 | 2026-11-08 11:57:12 `adb install -r` Success；DEBUGGABLE；数据与模型保留 |
| 最终 debug | app-debug.apk（12:10 构建） | 108,812,306 B | 841126E455B675A67FD0BD2D5E03BF6F6C4D82CADBD8D50ADD9A30A063EB4510 | 2026-11-08 12:18:02 `adb install -r` Success；DEBUGGABLE；模型 SHA-256 仍 BF1ABA9C…（343,490,560 B） |

- 两次 `adb install -r` 均**未**发生签名冲突，无需 uninstall，应用数据（含 343 MB 模型）完整保留。
- 安装后默认输入法仍为 Bitap；注意：在本 ROM 上执行 `am force-stop com.bitap.keyboard` 会把 default_input_method 回退到 `com.android.inputmethod.latin/.LatinIME`，需重新 `ime set com.bitap.keyboard/.ime.BitapImeService`（D02 脚本已处理，记录为设备行为特性）。
- 新包 smoke 与 D02 资源数据见 [PHASE1_AI_RESOURCE.md](PHASE1_AI_RESOURCE.md)。
## 8. 证据文件索引

| 文件 | 内容 |
|---|---|
| `.tmp/device-qa/baseline-01-keyboard.png` | 键盘唤起后截图 |
| `.tmp/device-qa/baseline-02-nihao.png/.xml` | nihao 候选截图与 UI dump |
| `.tmp/device-qa/baseline-03-commit-nihao.png/.xml` | 选「你好」后文本与状态 |
| `.tmp/device-qa/baseline-04-app-home.png/.xml` | Bitap 首页（含模型已导入状态） |
| `.tmp/device-qa/baseline-dumpsys-package.txt` | 完整包信息 |
| `.tmp/device-qa/baseline-getprop.txt` | 完整 getprop |
| `.tmp/device-qa/baseline-pss-*.txt` | 各条件 meminfo 原始输出 |
