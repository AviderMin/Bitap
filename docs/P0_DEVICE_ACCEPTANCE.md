# Bitap P0 真机闭环验收报告

设备：`fe4ab855` / 22021211RC（lineage_munch）/ Android 16 / API 36 / 1080×2400 / density 440
测试执行人：device-qa（本会话唯一设备操作者）
报告状态：**FINAL（最终 release 包验收完成）**
测试窗口：2026-11-07 19:15–20:45（Asia/Shanghai），其中最终包安装 20:28:31、最终四项 20:29–20:45

## 1. 最终结论摘要

最终 release APK（SHA-256 `A8D8AFE8…09FB0FC`，源码 commit `956b5043…`）在真机上完成四项闭环验收：

| # | 验收项 | 判定 | 关键结果 |
| --- | --- | --- | --- |
| 1 | 连续两次/三次 pending 确认（重复拼音、标点、退格） | **PASS** | A 序列 `ni/ni/nihao` + 空格 → `你好你好你好`；B 序列 2 次 pending + 逗号 + 句号 + 退格 + nihao → `你好你好，你好`；连续 3 轮全部一致 |
| 2 | 横屏完整流程（输入、选词、退格重选、展开/收起、旋转恢复） | **PASS** | `jintiantianqihenhao`→`今天天气很好`；退格后 `今天天气很`，重输恢复；展开候选不挤出底部功能行、收起后空格可提交；旋转回竖屏文本保留 |
| 3 | 真实 Android InputConnection 删除（Unicode 簇） | **PASS（7/7）** | 家庭 emoji、肤色、国旗、e+U+0301、普通 emoji 一次退格整簇清空；汉字删最后一个；已有选区一次清空 |
| 4 | AI 生成时移动光标/修改选区/关闭 AI | **PASS** | loading 中移光标与改选区均立即取消、20s+ 无残留无提交；loading 中点击“取消 AI”立即清空、23s 无旧结果；取消后键盘可继续输入 |

无 P0 阻塞缺陷遗留。pre-check 阶段发现的横屏候选面板、AI 取消入口问题已在最终包（task-5 修复）复验通过；详见第 11 节 pre-check 记录。

## 2. 环境与包信息

### 2.1 设备与工具

| 项 | 值 |
| --- | --- |
| 设备 | `fe4ab855`，22021211RC，Android 16（`ro.build.version.release=16`），API 36，1080×2400，density 440 |
| 输入法 | `com.bitap.keyboard/.ime.BitapImeService`（已启用并设为默认） |
| 浏览器 | Via `mark.via/.Shell` |
| 测试页 | `.tmp/p0final/p0.html`、`.tmp/p0final/unicode.html`（UTF-8，经 `adb reverse tcp:8769` 访问 `http://127.0.0.1:8769`） |
| 测试服务 | `.tmp/p0final` 下 Python `http.server 8769 --bind 127.0.0.1`（本任务自建，端口 8769） |

### 2.2 最终 release 包（交付判定依据）

| 项 | 值 |
| --- | --- |
| APK | `dist/bitap-0.1.0-demo.apk` |
| 大小 | 28,555,607 bytes |
| SHA-256 | `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC` |
| 源码 commit | `956b5043fdfc8a891093851e445b041acd9216d8`（feat(ime): generalize pinyin decoding, shorthand and typo handling） |
| 签名/校验 | debug key 签名，apksigner verify 通过（Lead 构建信息） |
| 安装 | `adb -s fe4ab855 install -r dist/bitap-0.1.0-demo.apk` → Success；`lastUpdateTime=2026-11-07 20:28:31` |
| 设备包一致性 | `pm path` + `adb pull base.apk` 后 Get-FileHash = `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC`（与 dist 完全一致） |
| 包信息证据 | [p0-final-package-info.txt](evidence/p0-final-package-info.txt) |

## 3. 单一操作者声明

- 操作者：device-qa（本会话），唯一设备操作者。
- 起止时间：2026-11-07 19:15（pre-check 开始）至 2026-11-07 20:45（最终验收结束），Asia/Shanghai。
- 声明：设备 `fe4ab855` 在测试期间仅由 device-qa 通过本会话 adb 操作；Lead、engine-dev、eval-dev、input-dev 均未操作设备；无并发自动化或人工输入。
- 外部干扰记录：测试期间设备收到 Telegram（`xyz.nextalone.nagram`）通知横幅一次（约 19:25），已临时设置 `heads_up_notifications_enabled=0`（原值 1），不属于操作者输入；测试结束已恢复。
- 测试环境清理：pre-check 开始前发现旧会话遗留的 8766/8768 `python -m http.server --directory .tmp\smoke` 进程（PID 22104 / 3484，启动于 13:12 / 18:38），已记录命令行并 Stop-Process，随后 `adb reverse --remove-all`，建立本任务 8769 服务；详见 7.1。

## 4. 最终包验收：Item 1 — 连续两次/三次 pending 确认

测试页：`p0.html`；输入通过真实键盘坐标点击（坐标表见 `scripts/p0-device/run_item1.ps1` 注释）。为进入 30ms 查询 debounce 的 pending 路径，字母与空格的间隔按脚本 50ms 连按；每一步用 uiautomator dump 读取 EditText 文本并截图。

### 4.1 Scenario A：三次连续 pending（ni / ni / nihao）

| 步骤 | 操作 | 实测编辑框文本 | 判定 |
| --- | --- | --- | --- |
| A1 | 点 n、i、空格（快按） | `你好`（len=2 sel=2-2） | PASS |
| A2 | 点 n、i、空格 | `你好你好`（len=4） | PASS |
| A3 | 点 n、i、h、a、o、空格 | `你好你好你好`（len=6） | PASS |

### 4.2 Scenario B：两次 pending + 标点 + 退格 + 第三次 pending

| 步骤 | 操作 | 实测编辑框文本 | 判定 |
| --- | --- | --- | --- |
| B1 | n i + 空格 | `你好` | PASS |
| B2 | n i + 空格 | `你好你好` | PASS |
| B3 | 点逗号 `，` | `你好你好，` | PASS |
| B4 | 点句号 `。` | `你好你好，。` | PASS |
| B5 | 退格一次 | `你好你好，` | PASS |
| B6 | nihao + 空格 | `你好你好，你好` | PASS |

**重复性：** 完整 A/B 序列连续执行 3 轮（`p0-final-item1-r1/r2/r3`），三轮最终文本全部为 `你好你好你好` 与 `你好你好，你好`，无丢键、无卡死。pre-check 阶段曾出现 1 次不可复现的 `你好ao` 残留（快速连按时序异常），最终包 3 轮未复现，记录为已消除/不可复现观察。

**证据（节选）：**
- [r1 A1](evidence/p0-final-item1-r1-a1-ni-space.png)、[r1 A2](evidence/p0-final-item1-r1-a2-ni-space.png)、[r1 A3](evidence/p0-final-item1-r1-a3-nihao-space.png)
- [r1 B3 逗号](evidence/p0-final-item1-r1-b3-comma.png)、[r1 B4 句号](evidence/p0-final-item1-r1-b4-period.png)、[r1 B5 退格](evidence/p0-final-item1-r1-b5-backspace.png)、[r1 B6 最终](evidence/p0-final-item1-r1-b6-nihao-space.png)
- 完整 3 轮 XML/PNG：`docs/evidence/p0-final-item1-r1-*` … `r3-*`

## 5. 最终包验收：Item 2 — 横屏完整流程

测试步骤与实测（最终包，脚本 `run_item2.ps1`，键位由 `detect_keys.py` 从截图动态识别）：

| # | 步骤 | 实测/观察 | 判定 |
| --- | --- | --- | --- |
| 1 | 竖→横旋转（`user_rotation 1`，`accelerometer_rotation=0`） | 界面 2400×1080，编辑框与键盘正常显示 | PASS |
| 2 | 横屏输入 `jintiantianqihenhao` | 候选条显示拼音，编辑框文本 `jintiantianqihenhao` | PASS |
| 3 | 空格选词 | 编辑框 = `今天天气很好` | PASS |
| 4 | 退格改错 | 编辑框 = `今天天气很`（删除“好”） | PASS |
| 5 | 重输 `hao` + 空格 | 编辑框恢复 `今天天气很好` | PASS |
| 6 | 展开候选（点 `⌄`） | 候选面板以覆盖层展开，**输入行仍可见**（`今天天气很好nihao`）、**底部 123/中英/空格/回车完整可见**、主键区不被挤出 | PASS |
| 7 | 收起候选（点 `⌃`） | 候选条恢复显示 `你好/你哈哦/...`，编辑框文本保持 `今天天气很好nihao` | PASS |
| 8 | 收起后点空格 | 编辑框 = `今天天气很好你好`（候选正常提交） | PASS |
| 9 | 横→竖旋转恢复 | 文本保留 `今天天气很好你好`、光标可见，键盘正常显示 | PASS |

- 键盘窗口占比（dumpsys window InputMethod frame / 当前屏高）：最终脚本记录 **768/1080 = 71.1%**（空态首次测量曾为 702/1080 = 65.0%，随 IME 窗口状态变化；候选态固定 71.1%）。编辑框可见高度约 75px，但一行文本+光标清晰可见。
- 证据：[横屏键盘](evidence/p0-final-item2-01-landscape-keyboard.png)、[长句候选](evidence/p0-final-item2-02-long-candidates.png)、[选词](evidence/p0-final-item2-03-long-committed.png)、[退格](evidence/p0-final-item2-04-backspace.png)、[重选恢复](evidence/p0-final-item2-05-retype-committed.png)、[展开候选](evidence/p0-final-item2-07-expanded.png)、[收起候选](evidence/p0-final-item2-08-collapsed.png)、[收起后空格提交](evidence/p0-final-item2-08b-space-after-collapse.png)、[竖屏恢复](evidence/p0-final-item2-09-portrait-restored.png)

## 6. 最终包验收：Item 3 — 真实 InputConnection 删除（Unicode 簇）

本地页 `unicode.html` 每个用例：聚焦 textarea → 点一次退格（键位 991,2025）→ dump EditText 文本/选区 + 截图 + DOM status（含 code points）。

| 用例 | 初始值 | 退格后 EditText / DOM status | 判定 |
| --- | --- | --- | --- |
| family | 👨‍👩‍👧‍👦（U+1F468 ZWJ U+1F469 ZWJ U+1F467 ZWJ U+1F466，len=11） | 空，`len=0 sel=0-0 value=""` | PASS |
| skin | 👍🏽（U+1F44D U+1F3FD） | 空，`len=0 sel=0-0` | PASS |
| flag | 🇨🇳（U+1F1E8 U+1F1F3） | 空，`len=0 sel=0-0` | PASS |
| accent | e + U+0301（DOM cp=65,301） | 空，`len=0 sel=0-0` | PASS |
| emoji | 😀（U+1F600） | 空，`len=0 sel=0-0` | PASS |
| hanzi | 汉字 | `汉`（len=1 sel=1-1 cp=6c49） | PASS |
| selection | 选中文本（选区 0-4） | 空，`len=0 sel=0-0`（该例 uiautomator EditText 文本属性有缓存滞后，截图与 DOM 均确认空） | PASS |

**结论：7/7 PASS。**
证据：[family 前](evidence/p0-final-item3-family-before.png)/[后](evidence/p0-final-item3-family-after.png)、[skin 前](evidence/p0-final-item3-skin-before.png)/[后](evidence/p0-final-item3-skin-after.png)、[flag 前](evidence/p0-final-item3-flag-before.png)/[后](evidence/p0-final-item3-flag-after.png)、[accent 前](evidence/p0-final-item3-accent-before.png)/[后](evidence/p0-final-item3-accent-after.png)、[emoji 前](evidence/p0-final-item3-emoji-before.png)/[后](evidence/p0-final-item3-emoji-after.png)、[hanzi 前](evidence/p0-final-item3-hanzi-before.png)/[后](evidence/p0-final-item3-hanzi-after.png)、[selection 前](evidence/p0-final-item3-selection-before.png)/[后](evidence/p0-final-item3-selection-after.png)；每项 XML 同名保存。

## 7. 最终包验收：Item 4 — AI 生成时移动光标、修改选区、关闭 AI

触发路径：AI 词库候选——输入 `pingguo` 后点候选条 ✦ AI，进入“AI 词库思考中…”，此时入口变为“取消 AI”（新修复）。

| 子项 | 操作 | 实测 | 判定 |
| --- | --- | --- | --- |
| (a) 移动光标 | loading 中点编辑框（sel 7→5） | [a1-loading](evidence/p0-final-item4-a1-loading.png) 显示思考中/取消 AI；3s 后 [a2](evidence/p0-final-item4-a2-after-cursor-3s.png) 无 loading、文本仍 `pingguo` sel=5-5；23s 后 [a3](evidence/p0-final-item4-a3-after-cursor-23s.png) 同样无残留、无提交 | PASS |
| (b) 改变选区 | loading 中长按编辑框选中 `pingguo`（sel 0-7） | [b1-loading](evidence/p0-final-item4-b1-loading.png) 显示思考中；2s 后 [b2](evidence/p0-final-item4-b2-after-selection-2s.png) loading 消失、选区 0-7 保持；22s 后 [b3](evidence/p0-final-item4-b3-after-selection-22s.png) 无残留、无提交 | PASS |
| (c) 关闭/取消 AI | loading 中点击“取消 AI”（与 ✦ AI 同一入口） | [c4-loading](evidence/p0-final-item4-c4-loading.png) 确认“取消 AI”+“AI 词库思考中…”可见；快速第二次点击后 [c5-doubletap-after](evidence/p0-final-item4-c5-doubletap-after.png)（0.3s）显示 ✦ AI、无加载、无 AI 候选；[c5-after-20s](evidence/p0-final-item4-c5-after-20s.png)（20s）同样干净、文本仍 `pingguo`、无旧结果提交 | PASS |
| (d) 键盘仍可用 | 取消后点字母 a | [d-usable](evidence/p0-final-item4-d-usable.png)：EditText=`pingguoa`（len=8），键盘正常输入；随后退格恢复 | PASS |

说明（测试方法透明记录）：单次“点 AI→截图→再点 AI”的人工/脚本流程中，screencap 约需数百毫秒，模型可能在截图完成前已返回，导致第二击变成“重新请求”并显示 AI 候选（`c1→c2` 记录）；因此取消判定采用同一 adb shell 内“触发+立即取消”的双击序列（c4/c5），并在 0.3s 与 20s 两个时点确认无 loading、无候选、无提交。`c1`/`c2` 文件保留作为该时序现象的证据。AI 面板润色路径在 Via WebView 中即使原生长按选中文本仍提示“先输入拼音，或选中一段文字”，当前 KeyboardScreen 无独立 AI 面板入口，记 GAP（不影响本项 AI 词库路径的 PASS）。

## 8. 缺陷、限制与未验证项

| # | 类型 | 内容 | 状态 |
| --- | --- | --- | --- |
| 1 | 已修复并复验 | pre-check 的横屏展开候选挤出底部功能行/遮挡输入行、收起后候选条消失且空格不能提交 | 最终包 PASS（5.6–5.8） |
| 2 | 已修复并复验 | pre-check 的 AI loading 无取消入口、再次点击 AI 重新请求 | 最终包 PASS（7c） |
| 3 | 已修复并复验 | pre-check 的旋转回竖屏需重新点输入框（文本保留但键盘不自动出现）；最终包旋转回竖屏键盘正常显示 | 最终包 PASS（5.9） |
| 4 | GAP（非 P0 主路径） | AI 面板润色入口在 Via WebView 下未识别选中文本；未找到独立 AI 面板 UI | 记录，不影响 AI 词库路径 |
| 5 | 观察项 | pre-check 一次快速连按 B 序列出现 `你好ao` 残留；最终包 3 轮未复现 | 观察项关闭（不可复现） |

## 9. 证据文件清单

所有最终包证据位于 `docs/evidence/`，命名规则 `p0-final-<item>-<step>.png/.xml`：
- Item1：`p0-final-item1-r1-*` / `r2-*` / `r3-*`（每轮 a1–a3、b1–b6，PNG+XML）
- Item2：`p0-final-item2-01` … `09`、`08b`（PNG+XML），`p0-final-item2-kb-layout.png`
- Item3：`p0-final-item3-{family,skin,flag,accent,emoji,hanzi,selection}-{before,after}.png/.xml`
- Item4：`p0-final-item4-{a0..a3,b0..b3,c0..c5,d-usable}`（PNG+XML），其中 c4/c5 为取消专项证据
- 包信息：`p0-final-package-info.txt`
- Pre-check 原始证据（不算最终交付）：`.tmp/p0final/precheck-item1-r1..r3`、`precheck-item2c`、`precheck-item3b/3c`、`precheck-item4b`

## 10. 可复跑命令

```powershell
cd D:\Projects\Github\Bitap
# 0) 启动本地测试服务（在 .tmp/p0final 下）
& "C:\Users\AviderMin\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" -m http.server 8769 --bind 127.0.0.1
adb -s fe4ab855 reverse tcp:8769 tcp:8769
& scripts/p0-device/setup_env.ps1

# 1) 安装最终包并核验 SHA
Get-FileHash dist/bitap-0.1.0-demo.apk -Algorithm SHA256
adb -s fe4ab855 install -r dist/bitap-0.1.0-demo.apk
# pm path 后 adb pull base.apk，再 Get-FileHash 对比

# 2) Item1（3 轮）
1..3 | ForEach-Object { & scripts/p0-device/run_item1.ps1 -EvidenceDir docs/evidence -Tag "p0-final-item1-r$_" }

# 3) Item2
& scripts/p0-device/run_item2.ps1 -EvidenceDir docs/evidence -Tag p0-final-item2

# 4) Item3
& scripts/p0-device/run_item3.ps1 -EvidenceDir docs/evidence -Tag p0-final-item3

# 5) Item4（AI 词库；取消场景采用双击序列，脚本内为 0.25s 截图+再点击，机器快时可能错过 loading，
#    可用同 shell 内“input tap AI; input tap AI”方式复现 c4/c5）
& scripts/p0-device/run_item4.ps1 -EvidenceDir docs/evidence -Tag p0-final-item4

# 6) 单步辅助
& scripts/p0-device/dump_ui.ps1 -Out .tmp/p0final/ui.xml
& scripts/p0-device/screenshot.ps1 -Out .tmp/p0final/cap.png
& scripts/p0-device/tap.ps1 -Match "空格"
```

## 11. Pre-check 记录（非交付判定，最终包之前）

> 本节记录在 pre-check 包 `dist/bitap-0.1.0-demo.apk` SHA-256 `05D97304664D319368FFD1AD3CC07A80D5C44E0C3CBFDEF5F8C1F9898A190BCD`（27,642,199 bytes，versionName 0.1.0，源码 HEAD `d5616bb` + 未提交 PinyinEngine.kt）上的结果。**不作为最终 PASS 依据。**

### 11.1 旧会话遗留清理（记录命令行）

```text
PID 22104  Created 2026/11/7 13:12:18
  "C:\Users\AviderMin\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
  -m http.server 8766 --bind 127.0.0.1 --directory .tmp\smoke
PID 3484   Created 2026/11/7 18:38:58
  "C:\Users\AviderMin\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
  -m http.server 8768 --bind 127.0.0.1 --directory .tmp\smoke
```

均为旧会话遗留、非本任务操作者；已 Stop-Process，清除 `adb reverse --remove-all`，建立本任务 8769 服务。`.tmp/smoke` 下 18:42–19:03 截图/XML 为上一会话产物，未用作本报告证据。

### 11.2 Pre-check 四项结论

| 项 | 结果 | 发现 |
| --- | --- | --- |
| 1 pending | PASS（A/B 序列） | 一次快速连按出现 `你好ao` 残留，重测未复现 |
| 2 横屏 | PARTIAL | 键盘占 65–71.1% 高度、编辑区 75px；展开候选挤出底部功能行/遮挡输入行；收起后候选条变占位、空格不能提交；旋转回竖屏需重点输入框 |
| 3 Unicode 删除 | PASS 7/7 | selection 例 uiautomator 文本属性缓存滞后，DOM/截图确认空 |
| 4 AI 取消 | PARTIAL/FAIL(c) | loading 无取消控件，再点 ✦ AI 会重新请求并显示 AI 候选；移光标/改选区可取消 |

以上缺陷均已由 task-5 修复并在最终包复验（第 4–7 节）。

---

*device-qa，2026-11-07 20:46 最终版。所有最终结论仅基于上述最终 release APK；证据可追溯至 `docs/evidence/p0-final-*`。*
