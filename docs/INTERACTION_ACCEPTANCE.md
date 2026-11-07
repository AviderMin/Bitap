# 交互验收（P2）：AI 入口语义 + 显式中英切换

> 状态：**设备侧报告**（device-qa），已并入 [FINAL_DELIVERY.md](FINAL_DELIVERY.md)。设备：fe4ab855（22021211RC / Android 16 / API 36 / 1080x2400 / user build）。
> 本轮只覆盖：① Via WebView 中 ✦ AI 入口的语义与提示；② 显式中英切换（点按「中/英」）。**「无切换连续混排」不在本轮完成声明内。**

## 1. 被测包与环境

| 角色 | 文件 | SHA-256 | 大小 | 版本信息 |
|---|---|---|---:|---|
| 基线 | dist/bitap-0.1.0-demo.apk（commit 956b504） | A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC | 28,555,607 B | versionName=0.1.0 / versionCode=1 |
| P2 预览（仅交互验证） | dist/bitap-0.1.1-batchC-preview.apk | 76646F1DF68E52B3F75F08088F64108E25FC2E8ADF3CB814EDC0BE42DB65AE5A | 28,555,607 B | **versionName 仍为 0.1.0 / versionCode=1**（未改版本号，按 SHA 识别） |
| **最终交付** | dist/bitap-0.1.0-final.apk | CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0 | 28,301,655 B | versionName=0.1.0 / versionCode=1（source b5caf39） |

- 安装方式：`adb -s fe4ab855 install -r <apk>`；基线回滚：`adb -s fe4ab855 install -r -d dist/bitap-0.1.0-demo.apk`（如需降级加 `-d`）。
- 测试页：`.tmp/p2device/p2.html`（本机 HTTP 8772 + `adb reverse`，Via 打开 `http://127.0.0.1:8772/p2.html`）；textarea 预置中文句「今天天气很好，我们一起去公园散步吧。」
- 截图与 UI dump 均存于 `.tmp/p2device/`；uiautomator 不暴露 IME 窗口节点，AI 相关文字证据以截图 + APK classes.dex 字符串为准。

## 2. AI 入口（✦ AI）基线 vs 预览

**基线 0.1.0（错误提示原文）**

| 场景 | 操作 | 基线结果 | 判定 |
|---|---|---|---|
| 中文选区 | textarea 选中「今天天气很好」(6 字) → 点 ✦ AI | 候选条下方红色提示 **「先输入拼音，或选中一段文字」**；文字已选中却仍提示去选中文字，且该入口实际是「AI 词库候选（仅拼音）」，不提供文本改写 | **FAIL**（提示用户执行无法完成的操作） |
| 无选区 | 光标在中文句中(2-2) → 点 ✦ AI | 同上，红色「先输入拼音，或选中一段文字」 | **FAIL**（文案含无法兑现的“选中一段文字”） |
| 拼音选区（程序化选中 nihao） | 页面按钮选中 sel=0-5 → 点 ✦ AI | ✦ AI 按钮变灰禁用，无任何提示（静默） | 记录（无错误提示，但无反馈） |
| 拼音 composing | 键盘输入 nihao（候选「你好」等）→ 点 ✦ AI | 显示「AI 词库思考中…」→ 约 3–5 s 后红色「AI 词库暂无合适候选，请直接使用本地候选」 | 可用 |

证据：`.tmp/p2device/baseline-cn-selected.png`、`baseline-cn-ai-0.4s.png`、`baseline-nosel-ai.png`、`baseline-pyselected-ai.png`、`baseline-ai-gen-1s.png`、`baseline-ai-gen-4s.png`；基线 APK 字符串见 `baseline-ai-strings.txt`（「AI 词库候选」无“只根据拼音”限定）。

**P2 预览包（修复后）**

| 场景 | 操作 | 预览结果 | 判定 |
|---|---|---|---|
| 中文选区 | textarea 选中「今天天气很好」(6 字) → 点 ✦ AI | 红色提示 **「AI 词库只根据拼音给出候选；当前选区不是拼音，请先输入拼音或选中一段拼音」**（完整文案取自 APK classes.dex，38 字符）；明确说明本入口只做拼音候选，不再声称可处理任意选中文字 | **PASS** |
| 无选区 | 光标 2-2 → 点 ✦ AI | 红色提示 **「AI 词库只根据拼音给出候选，请先输入拼音」**（22 字符） | **PASS** |
| 拼音选区（长按选中已上屏 nihao） | 长按选中「nihao」→ 点 ✦ AI | 显示「AI 词库思考中…」→ 2 s 后红色「AI 词库暂无合适候选，请直接使用本地候选」；即“选中一段拼音”路径可触发 AI 词库请求 | **PASS**（Via 长按选区有效；页面按钮的程序化选区不等价，不作为失败依据） |
| 拼音 composing | 输入 nihao → 点 ✦ AI | 「AI 词库思考中…」→ 5 s 内「AI 词库暂无合适候选，请直接使用本地候选」 | **PASS** |
| 按钮语义 | 从 APK classes.dex 提取 | 预览新增 **「AI 词库候选（只根据拼音）」** contentDescription；无选区/中文选区提示均只指向拼音；不再出现「AI 词库未就绪，点击查看提示」这类模糊入口 | **PASS** |

证据：`.tmp/p2device/preview-cn-selected.png`、`preview-cn-ai-0.5s.png`、`preview-nosel-ai.png`、`preview-longpress-selected.png`、`preview-longpress-ai.png`、`preview-longpress-ai2s.png`、`preview-ai-composing-1s.png`、`preview-ai-composing-5s.png`；预览 APK 字符串见 `preview-ai-strings.txt`。

**AI 入口结论：** 基线 0.1.0 的「先输入拼音，或选中一段文字」对中文选区会误导用户去执行无法完成的操作；预览包已改为单列“拼音候选”语义，中文选区给出明确“当前选区不是拼音”，无选区给出“请先输入拼音”，按钮语义为「AI 词库候选（只根据拼音）」。**P2 AI 入口项：基线 FAIL → 预览 PASS；最终包复核见 §6。**

## 3. 显式中英切换（点按「中/英」）

前置：Via → p2.html textarea 聚焦；键盘为 Bitap。所有断言以 textarea 的 `len=` / `sel=` 状态与可见文本为准（截图内状态行可读）。

| 用例 | 步骤 | 基线 0.1.0 | 预览包 | 判定 |
|---|---|---|---|---|
| 有 composing 切换 | 输入 nihao（composing，候选「你好」）→ 点「中/英」 | 切到「英文输入」；textarea 保留 **nihao**（len=5，原 composing 只提交一次、无重复/丢字） | 同左（len=5） | **PASS** |
| 切换后空格 | 接上，点空格 | 追加半角空格（len=6） | 同左（len=6） | **PASS** |
| 切换后字母 | 接上，输入 abc | **nihao abc**（len=9） | 同左（len=9） | **PASS** |
| 切换后回车 | 接上，点回车 | 插入换行（len=10） | 同左（len=10） | **PASS** |
| 无 composing 切换 | 空 textarea → 点「中/英」→ 输入 hello | 英文输入正常，hello（len=5） | 同左（len=5） | **PASS** |
| 再切回中文 + composing 再切英文 | hello → 点「中/英」回中文 → 输入 nihao（composing）→ 再点「中/英」 | **hellonihao**（len=10，第二次 raw composing 只提交一次，无重复） | 同左（len=10） | **PASS** |
| 英文标点 | 英文层点逗号 | 追加半角 `,`（len=11） | 同左（len=11） | **PASS** |
| 中文标点 | 切回中文层点逗号 | 追加全角 `，`（len=12） | 同左（len=12） | **PASS** |
| 光标位置 | 页面按钮置 sel=2-2 → 切英文 → 输入 x | 在索引 2 插入：**hexllonihao,，**（len=13, sel=3-3），切语言不改光标 | 同左（len=13, sel=3-3） | **PASS** |

证据（基线）：`switch1-before-composing.png`、`switch1-after-cn2en.png`、`switch1-after-space.png`、`switch1-after-abc.png`、`switch1-after-enter.png`、`switch2-cn-composing.png`、`switch2-after-second-switch.png`、`switch2-en-comma.png`、`switch2-cn-comma.png`、`switch2-caret2-before.png`、`switch2-caret2-after-X.png`。
证据（预览）：同名 `preview-switch1-*.png`、`preview-switch2-*.png`（见目录）。

**显式切换结论：基线 0.1.0 与预览包在上述路径均为 PASS**：有 composing 时切换只提交一次 raw composing，不丢字不重复；切换后标点/空格/回车/光标位置均正确。

## 4. 未覆盖边界 / 说明

- **「无切换连续混排」不在本轮完成声明内**（本轮只验证显式点按「中/英」切换）。
- AI 入口仅验证「AI 词库候选（仅拼音）」语义与提示；**AI 原文改写/润色能力不在该入口提供**，预览包提示已明确这一点，未再把“选中文字 → 改写”作为可用承诺。
- 预览包 versionName 未变（0.1.0），仅能按 SHA-256 与 lastUpdateTime 区分；最终 C 批 APK 需以新 SHA 复验。
- uiautomator 无法读取 InputMethod 窗口内部节点，AI 提示原文以截图 + APK dex 字符串双重确认；预览 APK 的 UI 字符串来自 `dist/bitap-0.1.1-batchC-preview.apk` classes.dex 提取（`preview-ai-strings.txt`）。
- 本轮未验证：AI 入口在无模型/模型导入中的组合态、横屏、其他 App（仅 Via WebView）。

## 5. 结论

| 项目 | 基线 0.1.0 | 预览包 | 备注 |
|---|---|---|---|
| 中文选区后 ✦ AI 提示 | FAIL（「先输入拼音，或选中一段文字」） | **PASS**（「AI 词库只根据拼音给出候选；当前选区不是拼音，请先输入拼音或选中一段拼音」） | 最终 C 批复验 |
| 无选区 ✦ AI 提示 | FAIL（含“选中一段文字”） | **PASS**（「AI 词库只根据拼音给出候选，请先输入拼音」） |  |
| ✦ AI 按钮语义 | 「AI 词库候选」（未限定拼音） | **PASS**（「AI 词库候选（只根据拼音）」） | 静态 dex 证据 |
| 有 composing 显式中英切换 | PASS（只提交一次、无丢字） | PASS |  |
| 无 composing / 标点 / 空格 / 回车 / 光标 | PASS | PASS |  |

## 6. 最终交付包终验（dist/bitap-0.1.0-final.apk，SHA CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0，28,301,655 B，source b5caf39）

> 最终包与 P2 预览包同一代码路径（A+B+P2 合并后重建），但 SHA 不同；以下为最终包实测复核（versionName=0.1.0 / versionCode=1，按 SHA 识别）。

| 用例 | 操作 | 最终包结果 | 判定 |
|---|---|---|---|
| 中文选区 | textarea 选中「今天天气很好」(sel=0-6) → 点 ✦ AI | 红色提示「AI 词库只根据拼音给出候选；当前选区不是拼音，请先输入拼音或选中一段拼音」（38 字，同预览） | **PASS** |
| 无选区 | 光标 2-2 → 点 ✦ AI | 红色提示「AI 词库只根据拼音给出候选，请先输入拼音」（22 字） | **PASS** |
| 有 composing 显式中英切换 | 输入 nihao（composing）→ 点「中/英」 | 切到英文；textarea 保留 nihao，len=5，只提交一次、无重复丢字；空格 len=6、abc len=9、回车 len=10（final-switch1-*.png） | **PASS** |

证据：`.tmp/p2device/final-cn-ai.png`、`final-nosel-ai.png`、`final-switch1-before-composing.png`、`final-switch1-after-cn2en.png`、`final-switch1-after-space.png`、`final-switch1-after-abc.png`、`final-switch1-after-enter.png`。
最终包 P2 结论：**AI 入口提示 PASS；显式切换 PASS**。版本号未变，交付时以 SHA-256 为准。

## 7. 交付状态

- 基线 0.1.0（A8D8AFE8…）：AI 入口 2 个错误提示 FAIL、显式切换 PASS（见表）。
- P2 预览（76646F1D…）：AI 入口 PASS、显式切换 PASS（过程证据保留）。
- 最终包（CFA88667…）：AI 入口 PASS、显式切换 PASS（本轮复核）。
- 未覆盖：无切换连续混排、AI 模型未导入组合态、其他 App/横屏。
