# 阶段一 D02 AI 资源实测报告（2026-11-08）

> 设备侧 QA 报告。设备 `fe4ab855`（22021211RC / LineageOS 23.2 / Android 16 / API 36 / 1080×2400 / 440dpi）。报告由 device-qa 编写，所有数值来自本轮 `dumpsys meminfo`，未引用历史未复测数据。
> 原始输出目录：`.tmp/device-qa/ai/`；脚本：`scripts/device-qa/ai_resource_test.ps1`、`scripts/device-qa/set_ai.ps1`、`scripts/device-qa/pss.ps1`。

## 1. 被测包与模型

| 项目 | 值 |
|---|---|
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK 大小 / SHA-256 | 108,552,903 B / **E06838C99F6A1E8D8C00B56CFDA00D58530DDCD14843A507A78AC44B0A46AEC5** |
| 安装时间 / 方式 | 2026-11-08 11:57:12（`adb install -r` 覆盖旧 demo 包，Success，应用数据保留） |
| versionCode / versionName | 1 / 0.1.0 |
| APK flags | `[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]` → `run-as` 可用 |
| 设备上 APK SHA-256 | `e06838c99f6a1e8d8c00b56cfda00d58530ddcd14843a507a78ac44b0a46aec5`（与本地一致） |
| 模型文件 | `files/models/bitap-qwen3-0.6b.litertlm`，`343,490,560 B` |
| 模型 SHA-256 | `BF1ABA9CE8D963124407353C6AA4CBF72EBA78DB21AE133DE983EAC672CE83E0`（与仓库 `models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` 逐字节一致） |
| 模型来源 | `adb install -r` 保留上一轮已导入的私有目录文件；本轮用 `run-as ls/sha256sum/stat` 验证大小与哈希，未重复推送 343MB（见 §5 命令） |
| 应用内模型状态 | 首页「导入本地模型（可选）」显示 Qwen3-0.6B · 327.6 MB + 已完成；设置页显示“327.6 MB · 就绪” |

> 说明：本轮最终交付 debug 包为 Lead 随后重建的 `841126E4…`（108,812,306 B，与 E068 仅差设置页开关直接调用 `setEnabled` 的 3 行改动，D02 主逻辑相同）。**§2–§4 的详细 PSS 序列在 E068 上采集；§3.3 给出最终包 8411 的快速复核数据（10 s 峰值窗口）**，§6.1 为最终包 smoke。资源数据与 APK hash 的对应关系以此为准，禁止把某包数值挪用到未测包。

## 2. 测量方法（可复现）

### 2.1 环境固定

- 手动亮度 20（`settings put system screen_brightness_mode 1; screen_brightness 20`），竖屏（user_rotation=0），屏幕常亮。
- 电量 100%，电池温度：run1 27.7 °C，run2 28.4 °C，thermal status 0；无录屏/无编译并发。
- 每轮开始先 `am force-stop com.bitap.keyboard` 得到新进程，等待 6 s 后重新 `ime set com.bitap.keyboard/.ime.BitapImeService`（注意：本 ROM 上 force-stop 会把 default_input_method 回退到 LatinIME，必须重设；这一点已写入脚本）。
- 打开本地测试页 `http://127.0.0.1:8769/p0.html`（Via `mark.via/.Shell`）→ 点击文本框 → 键盘显示 3 s 后开始采样。

### 2.2 采样与口径

- 每次采样：`adb shell dumpsys meminfo com.bitap.keyboard`，取 **TOTAL PSS（KB）**，同时记录 `Native Heap` 行（第一列 Pss Total）与 `Java Heap` App Summary 值。
- 生成中每秒采样一次；关闭 AI 后按 0/5/10/15/20 s 采样。
- **基线定义**：同一 APK、同一场景、AI 已启用但尚未加载引擎、键盘可见并静置 3 s 的 PSS（`01-before-generation`）。这是“生成前基线”，与“关闭 AI 后”比较是同一构建/同一用户状态，避免 release 与 debug 包差异污染结论。
- 门槛（研发文档 §9.3）：**关闭 AI 后 10 s 内 PSS 回到基线 + 100 MiB（102,400 KB）以内**。

### 2.3 命令

```powershell
# 主场景（本轮报告采用 run2）
pwsh -NoProfile -File scripts/device-qa/ai_resource_test.ps1 -Tag d02-run2 -Query pingguo -PeakSeconds 20
# 辅助场景（run1，nihao）
pwsh -NoProfile -File scripts/device-qa/ai_resource_test.ps1 -Tag d02-run1 -Query nihao -PeakSeconds 25
# 切换 AI 开关（真实 UI）
pwsh -NoProfile -File scripts/device-qa/set_ai.ps1 -Enabled $false
```

## 3. 主场景结果：pingguo → ✦ AI → 生成「苹果」

功能结果：输入 `pingguo` 后点击 ✦ AI，键盘出现「AI 词库 苹果」，AI 候选为「苹果」，本地候选为「苹果 / 平过 / 平国 / 瓶过」。截图：`.tmp/device-qa/ai/d02-run2-04-ai-gen-end.png`。

### 3.1 PSS / Native / Java 时间序列（run2）

| 阶段 | 时间点 | TOTAL PSS (KB) | Native Heap PSS (KB) | Java Heap (KB) | 原始文件 |
|---|---|---:|---:|---:|---|
| 生成前基线（键盘可见、引擎未加载） | 12:07:50 | **211,763** | 10,631 | 97,456 | `d02-run2-01-before-generation.txt` |
| 输入 pingguo 后、点 AI 前 | 12:07:54 | 183,390 | 15,835 | 53,036 | `d02-run2-02-before-ai-tap.txt` |
| 生成中 1 s | 12:07:55 | 456,376 | 216,275 | 66,364 | `d02-run2-03-ai-gen-01s.txt` |
| 生成中 2 s | 12:07:57 | 1,617,348 | 1,341,795 | 50,892 | `d02-run2-03-ai-gen-02s.txt` |
| **生成中 3 s（峰值）** | 12:07:59 | **1,904,872** | **1,344,003** | 50,972 | `d02-run2-03-ai-gen-03s.txt` |
| 生成中 4 s | 12:08:00 | 1,807,768 | 1,249,087 | 51,300 | `d02-run2-03-ai-gen-04s.txt` |
| 生成中 5–20 s（平台期） | — | 1,806,068–1,806,212 | 1,247,767 | 51,008 | `d02-run2-03-ai-gen-05s..20s.txt` |
| 关闭 AI 前（设置页，引擎仍加载） | 12:08:30 | 1,854,098 | 1,253,746 | 57,248 | `d02-run2-04-before-ai-off.txt` |
| 关闭 AI 后 **0 s** | 12:08:30 | 369,206 | 107,942 | 52,904 | `d02-run2-05-ai-off-0s.txt` |
| 关闭 AI 后 **5 s** | 12:08:35 | 302,762 | 85,866 | 52,752 | `d02-run2-06-ai-off-05s.txt` |
| 关闭 AI 后 **10 s** | 12:08:40 | **280,602** | **63,778** | 52,680 | `d02-run2-06-ai-off-10s.txt` |
| 关闭 AI 后 15 s | 12:08:45 | 272,853 | 56,242 | 52,680 | `d02-run2-06-ai-off-15s.txt` |
| 关闭 AI 后 20 s | 12:08:50 | 268,185 | 51,574 | 52,680 | `d02-run2-06-ai-off-20s.txt` |

### 3.2 判定

- 门槛：基线 211,763 + 100 MiB = **313,911 KB**。
- 关闭 AI 后 10 s：**280,602 KB ≤ 313,911 KB（余量 33,309 KB ≈ 32.5 MiB）→ PASS（本构建同场景口径）**。
- 5 s 也已低于门槛（302,762 KB，余量 11,149 KB）。
- Native Heap：峰值 1,344,003 KB → 10 s 63,778 KB（下降 95.3%），20 s 51,574 KB；证明 native 推理引擎内存确实被释放，而不是仅 cancel 请求。
- 绝对 PSS 说明：10 s 时 280,602 KB 仍远高于旧 release 包空闲 56,882 KB。原因可从 `d02-run2-06-ai-off-10s.txt` 直接读出：`.apk mmap` PSS 94,005 KB（108 MB debug APK）、`EGL mtrack` 41,224 KB（设置页 Compose 渲染）、Java/DEBUG 开销等；**debug 包与 release 空闲不可直接比较**。同一构建的生成前基线 211,763 KB 才是本门槛的正确对照。

### 3.3 最终包 8411 快速复核（同一脚本，PeakSeconds=10）

最终 APK：108,812,306 B / **841126E455B675A67FD0BD2D5E03BF6F6C4D82CADBD8D50ADD9A30A063EB4510**，安装于 12:18:02；模型 SHA-256 仍为 BF1ABA9C…（343,490,560 B）。环境温度 29.6 °C。

| 阶段 | TOTAL PSS (KB) | Native Heap PSS (KB) | Java Heap (KB) |
|---|---:|---:|---:|
| 生成前基线（键盘可见、引擎未加载） | **157,955** | 11,711 | 42,948 |
| 点 AI 前（pingguo 候选） | 183,569 | 15,919 | 53,028 |
| 生成中 1 s | 457,707 | 213,523 | 66,320 |
| 生成中 2 s | 1,629,147 | 1,340,863 | 50,896 |
| **峰值 3 s** | **1,899,831** | 1,335,011 | 50,964 |
| 生成中 4–10 s | 1,856,267–1,857,987 | 1,298,971–1,300,299 | 51,004–51,328 |
| 关闭 AI 前（设置页） | 1,905,633 | 1,304,974 | 57,260 |
| 关闭 AI 后 0 s | 368,425 | 106,778 | 52,812 |
| 关闭 AI 后 5 s | 303,985 | 86,794 | 52,776 |
| 关闭 AI 后 10 s | **284,661** | **67,566** | 52,680 |
| 关闭 AI 后 15 s | 285,209 | 68,118 | 52,676 |
| 关闭 AI 后 20 s | 285,233 | 68,142 | 52,676 |
| 关闭后返回键盘可见态（再开键盘，补充采样） | **215,247** | 35,148 | 52,796 |

判定（必须分开写清）：

- 若以“设置页内关闭后 10 s”对照“同轮生成前键盘态基线 157,955”：门槛 157,955+102,400 = **260,355 KB**，实测 284,661 KB，**超出 24,306 KB（23.7 MiB）→ 严格口径不达标**。
- 若以“关闭后回到键盘可见态”的同一用户状态对照基线：215,247 − 157,955 = **+57,292 KB（56.0 MiB）→ 达标**。
- Native Heap 仍从峰值 1,340,863 KB 降到 10 s 67,566 KB、回到键盘 35,148 KB，**引擎释放本身稳定成立**；差异主要来自 debug 包 `.apk mmap`（10 s 时 94,039 KB）与设置页 EGL/Compose（41,224 KB）等非引擎开销，以及“基线”受进程预热/Java Heap 的影响（E068 run2 基线 211,763 vs final 157,955）。
- 因此：**“10 秒内 PSS 回到基线+100MiB”不能宣称在所有采样状态稳定达标**；E068 run2 设置页口径达标（280,602 ≤ 313,911），final 8411 设置页口径不达标（284,661 > 260,355），返回键盘态达标。结论应为“部分口径达标、引擎卸载成立，需 release 包复测后才能作为产品门槛”。

## 4. 辅助场景：nihao → ✦ AI（run1）

功能结果：输入 `nihao` 点击 ✦ AI 后，生成结束时键盘显示红色提示 **“AI 词库暂无合适候选，请直接使用本地候选”**（截图 `.tmp/device-qa/ai/d02-run1-04-ai-gen-end.png`），即本次模型输出被质量门禁拒绝，没有产生 AI 候选；资源上引擎确实完成了加载与推理。

| 阶段 | TOTAL PSS (KB) | Native Heap (KB) | Java Heap (KB) |
|---|---:|---:|---:|
| 生成前基线 | 211,511 | 10,451 | 97,332 |
| 点 AI 前（nihao 候选） | 177,081 | 14,395 | 49,004 |
| 生成中 1 s | 429,053 | 185,891 | 66,320 |
| 生成中 2 s | 1,586,913 | 1,307,995 | 51,248 |
| 生成中 3 s | 1,779,061 | 1,308,239 | 50,968 |
| **峰值 4 s** | **1,804,268** | **1,247,875** | 51,296 |
| 生成中 5–25 s | 1,802,136–1,802,764 | 1,246,203–1,247,875 | 50,984 |
| 关闭 AI 前（设置页） | 1,852,681 | 1,252,947 | 57,248 |
| 关闭 AI 后 0 s | 367,309 | 106,727 | 52,900 |
| 关闭 AI 后 5 s | 274,972 | 58,931 | 52,752 |
| 关闭 AI 后 **10 s** | **260,917** | **44,951** | 52,676 |
| 关闭 AI 后 15 s | 260,928 | 44,963 | 52,676 |
| 关闭 AI 后 20 s | 260,929 | 44,963 | 52,676 |

判定：同样 PASS（260,917 ≤ 313,911 KB）。原始文件前缀 `d02-run1-*`，CSV `d02-run1-pss.csv`。该轮也说明：AI 状态机/资源路径正常，但 `nihao` 的模型输出质量门禁会 fallback，属于功能质量观察项，不是资源失败。

## 5. 模型导入与 run-as 证据

本轮安装为 debug 包（DEBUGGABLE），模型已随 `adb install -r` 的数据保留存在于私有目录，实际执行并验证：

```text
$ adb -s fe4ab855 shell dumpsys package com.bitap.keyboard | grep flags
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]

$ adb -s fe4ab855 shell "run-as com.bitap.keyboard ls -l files/models/"
total 335772
-rw------- 1 u0_a182 u0_a182 343490560 2026-11-07 12:18 bitap-qwen3-0.6b.litertlm

$ adb -s fe4ab855 shell "run-as com.bitap.keyboard sha256sum files/models/bitap-qwen3-0.6b.litertlm"
bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0  files/models/bitap-qwen3-0.6b.litertlm

$ (Get-FileHash -Algorithm SHA256 models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm).Hash
BF1ABA9CE8D963124407353C6AA4CBF72EBA78DB21AE133DE983EAC672CE83E0
```

结论：模型文件完整且与仓库文件一致，无需重新推送 343MB。若需从零导入（本机未重复执行 343MB 传输），标准流程为：
```powershell
adb push models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /sdcard/Download/
adb shell "cat /sdcard/Download/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm | run-as com.bitap.keyboard sh -c 'mkdir -p files/models; cat > files/models/bitap-qwen3-0.6b.litertlm'"
```
`run-as` 管道机制已用小文件验证可用（shell 侧 cat 有 /sdcard 读权限，run-as 只负责写入私有目录）；343MB 整文件传输未重复执行，已如实标注。

## 6. isEngineLoaded 与取消

- **isEngineLoaded**：D02 在 `AiContract.kt` 新增 `val isEngineLoaded: Boolean`，`DefaultAiRewriteService` 委托 `engine.isLoaded`；但 **没有** logcat 输出、设置页显示或 dumpsys/binder 暴露，设备侧无法直接读取真/假。本轮以两项间接证据判断卸载：① Native Heap 从 1.25–1.34 GB 降到 35–68 MB；② 关闭 AI 后 10 s PSS 相对生成前基线的回落幅度（E068 run2 设置页口径达标，final 8411 设置页口径不达标、返回键盘态达标，详见 §3.3——不能笼统写成全部达标）。JVM 侧由 `app/src/test/java/com/bitap/keyboard/ai/AiServiceLifecycleTest.kt` 覆盖（Lead 报告全量 354 tests / 0 failures）。
- **取消生成（设备侧）**：尝试用 `input tap` 点击“取消 AI”条目 4 轮（`cancel-01..04`、`cancel4-*`，截图与 meminfo 均在 `.tmp/device-qa/ai/`）。由于 AI 加载时键盘候选行会因“AI 词库思考中…”状态行整体上移约 70 px，而 uiautomator 不包含 IME 窗口节点，无法稳定定位“取消 AI”按钮；已尝试 y=1494/1450/1548 与不同等待时序，其中一次在 y=1450 点击后 PSS 仍升至 1.85 GB（未能确认取消生效）。因此 **“取消生成”在设备端判定为未验证**，不计入通过；原因已如实记录，建议用 TalkBack/无障碍节点或人工点击复核（JVM 单测已覆盖取消语义）。此项不影响 §3 关闭 AI 的资源结论。

### 6.1 最终包 8411 smoke

命令：`pwsh -NoProfile -File scripts/device-qa/smoke.ps1 -Tag final-8411-smoke`，结果 **10/10 PASS**（2026-11-08 12:19，APK 841126E4…）：
键盘显示、nihao→你好、退格、标点、连续输入、设置页打开、AI 开关可见且关闭/重开无崩溃、密码框（英文态、pwdLen=5 不进入中文候选）。证据：`.tmp/device-qa/smoke/final-8411-smoke-*`。

## 7. 截图与文件索引

| 文件 | 内容 |
|---|---|
| `.tmp/device-qa/ai/d02-run2-00-keyboard.png` | 新 debug 包键盘显示 |
| `.tmp/device-qa/ai/d02-run2-01-query-candidates.png` | pingguo 本地候选（点 AI 前） |
| `.tmp/device-qa/ai/d02-run2-03-ai-gen-02s.png / 04s / 06s` | 生成中内存阶段截图 |
| `.tmp/device-qa/ai/d02-run2-04-ai-gen-end.png` | AI 候选「苹果」结果 |
| `.tmp/device-qa/ai/d02-run2-05-settings.png` | AI 开关开启状态（关闭前） |
| `.tmp/device-qa/ai/d02-run2-07-after-ai-off.png` | AI 开关关闭后（保存） |
| `.tmp/device-qa/ai/d02-run1-04-ai-gen-end.png` | nihao 的 AI fallback 提示 |
| `.tmp/device-qa/ai/d02-run2-pss.csv` / `d02-run1-pss.csv` / `final-8411-pss.csv` | 三次测量完整 CSV |
| `.tmp/device-qa/ai/final-8411-04-ai-gen-end.png` | 最终包 8411 AI 候选结果 |
| `.tmp/device-qa/ai/final-8411-06-ai-off-10s.txt` | 最终包关闭 AI 10 s meminfo |
| `.tmp/device-qa/ai/final-8411-09-ai-off-keyboard-visible.txt` | 最终包关闭 AI 后返回键盘态 meminfo |
| `.tmp/device-qa/ai/d02-run2-06-ai-off-*.txt` | 关闭后各时间点完整 meminfo |

## 8. 结论摘要

| 项目 | 结果 | 说明 |
|---|---|---|
| AI 生成可触发 | PASS（pingguo，E068 与 8411 均验证） | AI 候选「苹果」；nihao 触发但质量门禁 fallback |
| 生成峰值 PSS | E068 1,904,872 KB / 8411 1,899,831 KB | 与历史 1.79 GB 同量级；native 峰值约 1.34 GB |
| 关闭 AI 后 10 s PSS（设置页口径） | E068 280,602 KB / 8411 284,661 KB | E068 相对基线 211,763 → 达标；8411 相对基线 157,955 → 超 23.7 MiB 不达标；口径敏感 |
| 关闭 AI 后返回键盘态 PSS（8411） | 215,247 KB | 相对基线 157,955 → +56.0 MiB，达标 |
| 卸载证据 | Native Heap 1.34 GB → 67,566 KB（10 s，8411）/ 35,148 KB（返回键盘） | 证明 release 路径实际执行 |
| isEngineLoaded 设备可见性 | 未暴露 | 仅 JVM 属性；以 PSS/Native 间接判定 |
| 取消生成（设备） | 未验证 | UI 自动化无法稳定点击“取消 AI”，如实记录 |
| 最终包 smoke | 10/10 PASS | `final-8411-smoke` |

**最终判定：D02 的“native 引擎卸载”在设备上成立（Native Heap 1.34 GB → 约 35–68 MB，AI 生成可正常触发）。但“关闭 AI 后 10 秒 PSS 回到基线+100 MiB”只在部分口径达标：E068 run2 设置页口径达标（280,602 ≤ 313,911），final 8411 设置页口径不达标（284,661 > 260,355），8411 返回键盘态达标（215,247 ≤ 260,355）。因此不能宣称该门槛在所有测量状态稳定达标；需在 release/正式包固定场景复测后再作为产品验收结论。峰值 ~1.9 GB 的内存成本仍是低内存设备风险。**
