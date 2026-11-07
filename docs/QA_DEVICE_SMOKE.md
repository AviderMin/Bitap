# Bitap 0.1 A6 最终真机冒烟与截图验收

日期：2026-11-07 12:45–13:00（Asia/Shanghai）｜负责人：qa-dev｜任务：task-11

> 本报告只记录实际执行过的操作与观察结果。自动化点击和截图已保留在 `docs/evidence/`。未完成验证的项目明确标记 PARTIAL / GAP，不写成 PASS。

## 1. 设备与环境

| 项 | 值 |
| --- | --- |
| 设备 | `fe4ab855`，型号 22021211RC（Redmi K40S / lineage_munch） |
| 系统 | Android 16 / API 36（`ro.build.version.release=16`, `sdk=36`） |
| 屏幕 | 1080×2400，density 440 |
| 输入法 | `com.bitap.keyboard/.ime.BitapImeService`（系统设置中已启用/已选择） |
| 测试应用 | Via 浏览器 `mark.via`、Gmail `com.google.android.gm`、系统设置、短信 `com.android.messaging`、拨号器 |
| APK | `dist/bitap-0.1.0-demo.apk`（release/minify，debug key 签名，27,560,279 bytes） |
| APK SHA-256（本轮初测包） | `955BF2538B23E7F195CFAB5D43421ACF5EF24DB402EBD66B6486DE1EAB60DB03` |
| APK SHA-256（最终重签包） | `508F81FF6FDF530162DB6152CFBB1507BF73D5D2D629112E18B2AFA27E91113A`（12:56 重装后复测） |

复现命令：

```powershell
adb devices -l
adb install -r dist/bitap-0.1.0-demo.apk
adb shell ime enable com.bitap.keyboard/.ime.BitapImeService
adb shell ime set com.bitap.keyboard/.ime.BitapImeService
adb shell am start -n mark.via/.Shell
```

## 2. 冒烟结果

| # | 场景 | 操作 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| D1 | 安装/选择 Bitap | `adb install -r` + `ime enable/set` | **PASS**：安装成功，默认输入法为 Bitap | — |
| D2 | Via 地址栏弹出键盘 | 点按 Via 地址栏 | **PASS**：Bitap 键盘正常显示，无崩溃 | — |
| D3 | 中文拼音 nihao | 依次点按 n/i/h/a/o | **PASS**：候选条显示「你好 你号 你灏 泥好」，地址栏 composing=nihao；最终 508f 包复测结果相同 | [955b](evidence/bitap-a6-955b-01-nihao-candidates.png)、[508f](evidence/bitap-a6-final-01-via-nihao.png) |
| D4 | 空格提交首选 | 点按「空格」 | **PASS**：地址栏变为「你好」，候选清空 | [截图](evidence/bitap-a6-955b-02-after-space.png) |
| D5 | 单次退格 | 点按 ⌫ 一次 | **PASS**：地址栏由「你好」变为「你」 | [截图](evidence/bitap-a6-955b-03-after-backspace.png) |
| D6 | 长按退格松手 | 在 ⌫ 上按住 2.6 s 后松手 | **PASS**：地址栏清空（连续删除生效，松手后停止） | [截图](evidence/bitap-a6-955b-04-after-longpress.png) |
| D7 | 中文标点 | 点按「，」 | **PASS**：地址栏插入「，」 | [截图](evidence/bitap-a6-955b-05-after-punctuation.png) |
| D8 | ✦ AI 入口 | 点按候选条右侧 ✦ AI | **PASS（有反应，无崩溃）**：955b 包提示「先输入拼音，或选中一段文字」；最终 508f 包在 nihao composing 下显示「AI 词库思考中…」；未等待 AI 最终结果 | [955b 反馈](evidence/bitap-a6-955b-06-ai-feedback.png)、[508f 思考中](evidence/bitap-a6-final-02-ai-thinking.png) |
| D9 | ⚙ 设置入口 | 点按候选条右侧齿轮（约 1006,1470） | **PASS**：前台变为 `com.bitap.keyboard/.MainActivity`，显示 Bitap 设置页 | [截图](evidence/bitap-a6-final-03-settings.png) |
| D10 | 设置页内容 | 查看首页 | **PASS**：三步清单均为已完成；模型已安装 Qwen3-0.6B · 327.6 MB；主题/深色模式可见 | [955b 截图](evidence/bitap-a6-955b-07-settings-opened.png) |
| D11 | 系统搜索框 | 系统设置 → 搜索（`com.android.settings.intelligence/.search.SearchActivity`） | **PASS**：Bitap 键盘弹出，回车键标签为「搜索」 | [截图](evidence/bitap-a6-settings-search-keyboard.png) |
| D12 | 邮箱输入框 | Gmail 写邮件页 | **PARTIAL**：Gmail 写邮件页可弹出 Bitap 键盘（见截图），但自动化聚焦到发件人/主题区域时观察到的是中文布局与「回车」标签，**未确证 email 输入类型触发了英文布局/专属回车标签**；需人工在 To 字段确认 | [截图](evidence/bitap-a6-gmail-compose-keyboard.png) |
| D13 | 数字输入框 | 拨号器 `tel:10086`、短信 `sms:10086` | **GAP**：AOSP 拨号器使用自带数字键盘，不弹系统 IME；短信会话页正文为普通文本输入。**未获得数字输入框触发 Bitap Numbers 层的真机证据** | — |
| D14 | 稳定性 | 全流程操作 + `logcat -b crash` | **PASS（当前最终包）**：12:56 重装 508f 后 crash buffer 无新增记录，Bitap 进程存活（pid 26263）；无应用崩溃弹窗 | — |

## 3. 崩溃记录（重要）

在 **12:13–12:19（早于 12:45 首次安装的旧构建）** 的 crash buffer 中发现 Bitap IME 反复崩溃：

```text
FATAL EXCEPTION: main
Process: com.bitap.keyboard, PID: 18795
java.lang.NoClassDefFoundError: Failed resolution of:
    Lcom/bitap/keyboard/ai/internal/DefaultAiRewriteService;
    at com.bitap.keyboard.ai.LocalAiRewriteService.<clinit>(Unknown Source:0)
    at com.bitap.keyboard.BitapApplication.aiService_delegate$lambda$0(BitapApplication.kt:10)
    at com.bitap.keyboard.BitapApplication.getAiService(BitapApplication.kt:10)
    at com.bitap.keyboard.ime.BitapImeService.onCreate(BitapImeService.kt:78)
Caused by: java.lang.ClassNotFoundException: Didn't find class
    "com.bitap.keyboard.ai.internal.DefaultAiRewriteService"
```

- 该记录发生在旧安装包上；当前 955b 包（12:45 安装）与最终 508f 包（12:56 重装）在本次冒烟中均未再出现该崩溃。
- 但这是 **release/minify 包缺失类的崩溃形态**，若最终发布包也使用 R8/minify，必须由 Lead/构建负责人确认 `DefaultAiRewriteService` 已被 keep 规则保留，并在干净安装上复验；QA 已如实记录，不作为当前包 PASS 的依据。

## 4. 需求覆盖结论

| 重点验证项 | 结论 |
| --- | --- |
| 键盘可输入 nihao / 空格 / 退格 / 长按退格 / 标点 | **PASS**（955b 详细步骤 + 508f nihao 复测） |
| ✦ AI 有反应 | **PASS（有反应、无崩溃）**；最终 AI 文本结果未等待 |
| ⚙ 齿轮进入设置 | **PASS**（508f：前台 `com.bitap.keyboard/.MainActivity`） |
| 数字/邮箱输入框布局与回车标签 | **PARTIAL / GAP**：系统搜索框已验证「搜索」标签；Gmail 写邮件页仅确认键盘弹出；数字输入框未取得系统 IME 场景，**不判 PASS** |
| 崩溃 | 旧构建存在 `NoClassDefFoundError` 崩溃历史；当前 508f 包冒烟无新增崩溃，需构建方确认 R8 keep 规则 |

## 5. 证据文件（本次新增，均位于 docs/evidence/）

- [bitap-a6-955b-01-nihao-candidates.png](evidence/bitap-a6-955b-01-nihao-candidates.png)
- [bitap-a6-955b-02-after-space.png](evidence/bitap-a6-955b-02-after-space.png)
- [bitap-a6-955b-03-after-backspace.png](evidence/bitap-a6-955b-03-after-backspace.png)
- [bitap-a6-955b-04-after-longpress.png](evidence/bitap-a6-955b-04-after-longpress.png)
- [bitap-a6-955b-05-after-punctuation.png](evidence/bitap-a6-955b-05-after-punctuation.png)
- [bitap-a6-955b-06-ai-feedback.png](evidence/bitap-a6-955b-06-ai-feedback.png)
- [bitap-a6-955b-07-settings-opened.png](evidence/bitap-a6-955b-07-settings-opened.png)
- [bitap-a6-final-01-via-nihao.png](evidence/bitap-a6-final-01-via-nihao.png)
- [bitap-a6-final-02-ai-thinking.png](evidence/bitap-a6-final-02-ai-thinking.png)
- [bitap-a6-final-03-settings.png](evidence/bitap-a6-final-03-settings.png)
- [bitap-a6-gmail-compose-keyboard.png](evidence/bitap-a6-gmail-compose-keyboard.png)
- [bitap-a6-settings-search-keyboard.png](evidence/bitap-a6-settings-search-keyboard.png)

---

*qa-dev，2026-11-07。所有结论来自 adb 自动化点击/截图与 logcat；未验证项已按要求标记 PARTIAL/GAP。*
