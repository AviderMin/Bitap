# D05 真机验证：partial rank4→rank6 与排序操作成本（2026-11-08）

> device-qa 设备侧报告。设备 `fe4ab855`（22021211RC / LineageOS 23.2-20261102-NIGHTLY-munch / Android 16 / API 36 / 1080×2400 / 440dpi / 竖屏）。设备仅由 device-qa 操作；未修改产品代码、词库或评测语料。
> 原始证据：`.tmp/device-qa/d05/`；设备脚本：`scripts/device-qa/d05_case.ps1`；JVM 对照工具：`scripts/device-qa/d05_candidates.java`、`scripts/device-qa/d05_ranking.java`。

## 1. 被测 APK 与测试条件

| APK | 大小 | SHA-256 | 说明 |
|---|---:|---|---|
| display-old | 108,441,048 B | `52B0A08094D329E61536B13547577089E622DD890C9733BCED11B6339787689D` | early partial 在 3 个 full 后（rank4），weight 0.35 |
| ranking-old | 108,441,048 B | `A477927B2DE6A8C9A73F9F7E9D02C5957A6D2F12E01DCF5F98157BD4F858F9C3` | early partial afterFull=5，weight 0.35 |
| final | 108,812,306 B | `E888234FEF76E3EDE63EACBACE9ADEC959E701DC4E9F2409657BE3180F39F7EF` | early partial afterFull=5（rank6），weight 0.50；17:17:55 安装，验证结束保留此包 |

- 安装前设备包：`com.bitap.keyboard` 0.1.0 / versionCode 1，SHA-256 `841126E4…`（见 `.tmp/device-qa/d05/package-before.txt`）。
- 每次 `adb install -r` Success，数据保留；设备端 APK hash 与本地一致。
- 固定条件：竖屏 1080×2400；界面语言中文；Miuix 浅色主题；**本地 AI 关闭**；**用户学习关闭**；用户词库清空（`shared_prefs/bitap_user_lexicon.xml` 删除并重启 IME）。这样候选列表只由词典/引擎决定，避免历史学习污染对比。
- 输入方式：`adb shell input tap` 按固定坐标逐字母输入（110 ms/键）；测试页为 Via 打开的 `http://127.0.0.1:8769/p0.html`，逐次核对页面 `len=... value="..."` 状态。

## 2. 方法说明：什么叫“候选行实际可见”

- **折叠候选行**：键盘上方横向可滚动的候选 chip 行。1080 px 宽度、这些长候选（10–11 个汉字）时，屏幕**只能完整显示第 1 个 chip，第 2 个 chip 在右边缘被裁切**，即实际可读 chip = **2 个（1 完整 + 1 截断）**。因此“引擎返回前 8 项”不等于屏幕可见 8 项。
- **展开候选面板**：点候选行右端的展开 `⌄` 后，页面 pageSize=6，但面板高度只完整容纳 **4 行**（第 5 行只露出顶部，第 6 行不可见）；需要面板内上滑才能看到第 5/6 行。这个事实直接决定 rank4 与 rank6 的操作差异。
- 候选文字识别：**未使用 OCR**；人工查看截图并与同 APK 源码的 JVM 候选表（`d05_candidates.java` 输出的前 8 项）交叉核对；未能可靠识别的项不写入结果。

## 3. partial 展示位置：display-old（rank4）vs final（rank6）

### 3.1 测试案例（均来自 D05 dev）

| 用例 | input（有效长度） | expected | partial 候选（consumed） | display-old 候选 index | final 候选 index |
|---|---|---|---:|---:|
| `d05-natural_medium-004` | `niyaogeishuiwomeiyouyijian`（26） | 你要给谁我没有意见 | 你要给（8） | **3（第4位）** | **5（第6位）** |
| `d05-natural_medium-010` | `faxianyijingpaileyixieren`（25） | 发现已经排了一些人 | 发现一（8） | **3（第4位）** | **5（第6位）** |

两例均由 JVM harness 在对应 APK 的 PinyinEngine 默认 tuning 下确认（原始 `.tmp/device-qa/d05/candidates-display-old.txt`、`candidates-final.txt`）。

### 3.2 设备操作与结果

| 用例 / APK | 折叠行可见 chip | 路径 A：展开 + 面板操作 | 路径 B：折叠行横滑 | 选中后文本（页面状态） | 结果 |
|---|---|---:|---:|---|---|
| 004 / display-old | 2（1 完整+1 截断） | 展开 1 + 面板滚动 0 + 翻页 0 + 候选点击 1 = **2 次** | 横滑 2 + 点击 1 = 3 次 | `你要给shuiwomeiyouyijian`，`len=21 sel=21-21` | PASS：partial 前缀正确、剩余拼音保留 |
| 004 / final | 2（1 完整+1 截断） | 展开 1 + 面板滚动 1 + 翻页 0 + 候选点击 1 = **3 次** | 横滑 3 + 点击 1 = 4 次 | `你要给shuiwomeiyouyijian`，`len=21 sel=21-21` | PASS：同上；比 rank4 多 1 次面板滚动/横滑 |
| 010 / display-old | 2（1 完整+1 截断） | 展开 1 + 面板滚动 0 + 翻页 0 + 候选点击 1 = **2 次** | 未重复测（同 004 几何） | `发现一jingpaileyixieren`，`len=20 sel=20-20` | PASS |
| 010 / final | 2（1 完整+1 截断） | 展开 1 + 面板滚动 1 + 翻页 0 + 候选点击 1 = **3 次** | 未重复测 | `发现一jingpaileyixieren`，`len=20 sel=20-20` | PASS |

关键截图：

- display-old 004：`display-old-c004b-01-collapsed.png`（折叠行 2 chip）→ `display-old-c004b-02-expanded.png`（partial 第 4 行直接可见）→ `display-old-c004b-03-after-partial.png`
- final 004：`final-c004-01-collapsed.png` → `final-c004-02-expanded-p1.png`（首屏仅 4 行）→ `final-c004-03-panel-scrolled.png`（上滑后第 6 行 `你要给` 可见）→ `final-c004-04-after-partial.png`
- display-old 010：`display-old-c010-01-collapsed.png` → `display-old-c010-02-expanded.png` → `display-old-c010-03-after-partial.png`
- final 010：`final-c010-01-collapsed.png` → `final-c010-03-panel-scrolled.png` → `final-c010-04-after-partial.png`
- 折叠行横滑证据：`display-old-swipe-05-after2slow.png`（2 次慢横滑后 partial 可见）；`final-swipe-04-after3.png`（3 次慢横滑后 partial 可见）。

### 3.3 partial 结论

- **rank6 相对 rank4 确实增加了操作**：在相同长输入下，展开面板路径由 **2 次操作（展开+点选）** 增加到 **3 次（展开+1 次面板上滑+点选）**；折叠行横滑路径由 **2 次横滑+点选** 增加到 **3 次横滑+点选**。
- **增加的是“滚动/横滑”一次，不是翻页、不是修正、不是退格**：两例最终都正确提交 partial 前缀并保留剩余拼音，无回退/修正。rank6 仍位于候选列表第 6 位（前 8 项返回内），但“返回前 8 项 ≠ 首屏可见”，本设备上第 6 位需一次滚动才可见。
- 因此 D05 报告 §7.3 的“partial 仍在 8 项可见区”在**列表层面成立**，但**首屏可发现性出现 1 次滚动成本**；若产品目标要求打开候选面板即见 partial，rank6 有真实 UX 代价。

## 4. 排序改进操作成本：ranking-old（0.35）vs final（0.50）

选择 JVM 中 old rank>5 且 new rank<=5 的两个自然句案例（`scripts/device-qa/d05_ranking.java` 全量 natural 扫描）：`d05-natural_medium-180`、`d05-natural_medium-211`。两例在设备上分别用 ranking-old / final 选择正确完整句。

| 用例 | 期望句（input 有效长度） | ranking-old 候选 rank | final 候选 rank | ranking-old 设备操作 | final 设备操作 | 选中后文本 |
|---|---|---|---|---|---:|---|
| 180 | 它有十足的能力控制数字（30） | **6（第7位，第2页）** | **4（第5位，第1页）** | 展开1 + 面板滚动2 + 下一页1 + 回到页首滚动1 + 候选点击1 = **6 次** | 展开1 + 面板滚动1 + 候选点击1 = **3 次** | `它有十足的能力控制数字`（len=11） |
| 211 | 就只是会不会看到而已吧（29） | **10（第11位，第2页）** | **4（第5位，第1页）** | 展开1 + 面板滚动2 + 下一页1 + 候选点击1 = **5 次** | 展开1 + 面板滚动1 + 候选点击1 = **3 次** | `就只是会不会看到而已吧`（len=11） |

- 两次都正确选中期望完整句，无错字、无退格；输入字母数相同（30/29 键），差异全部来自候选浏览。
- 候选面板页脚显示 `第 1 / 11 页`（pageSize=6，64 候选），ranking-old 的正确句需要翻到第 2 页；final 的第 5 位仍在第 1 页，但首屏只能完整显示 4 行，仍需 1 次面板上滑。
- **操作成本结论（仅这两个 dev 案例）**：final 相对 ranking-old 分别减少 **3 次** 和 **2 次**候选浏览操作（约 50%/40%）；这是“候选排序改进在真机上可能减少操作”的正向证据，但样本仅 2 条 dev case，不能外推为总体用户收益或验收结论。

截图：

- ranking-old 180：`ranking-old-c180-01-collapsed.png` → `ranking-old-c180-02-expanded-p1.png` → `ranking-old-c180c-03-footer.png`（第 1/11 页、下一页）→ `ranking-old-c180e-04-page2.png`（翻页后保留底部位置）→ `ranking-old-c180f-05-page2-top.png`（滚回页首见 `它有十足的能力控制数字`）→ `ranking-old-c180g-06-selected.png`
- final 180：`final-c180-01-collapsed.png` → `final-c180-03-panel-scrolled.png`（第 5 行 `它有十足的能力控制数字`）→ `final-c180-04-selected.png`
- ranking-old 211：`ranking-old-c211-01-collapsed.png` → `ranking-old-c211-04-page2.png` → `ranking-old-c211-06-selected.png`
- final 211：`final-c211-01-collapsed.png` → `final-c211-03-panel-scrolled.png` → `final-c211-04-selected.png`

## 5. 设备与脚本

- 设备：`fe4ab855`，22021211RC / `munch`，LineageOS 23.2 / Android 16 / API 36 / 1080×2400@440dpi / 120Hz，user build。
- 输入页：`.tmp/p0final/p0.html`，Via `mark.via/.Shell`，本地 HTTP 8769；`adb reverse tcp:8769 tcp:8769`。
- 主要脚本：`scripts/device-qa/d05_case.ps1`（打开页面、清空、逐字母输入、截图/UI dump、可选展开）。
- JVM 对照：`scripts/device-qa/d05_candidates.java`（按 APK 的 PinyinEngine 默认 tuning 输出每个 dev 案例前 8 候选与 firstPartial index）和 `scripts/device-qa/d05_ranking.java`（全 natural 案例 old/new rank）。
- 运行示例：
```powershell
pwsh -NoProfile -File scripts/device-qa/d05_case.ps1 -InputStr niyaogeishuiwomeiyouyijian -Tag final-c004
# JVM 对照（classpath 指向对应 APK 的 app/build/tmp/kotlin-classes/debug）
java -cp "<kotlin-stdlib>;<coroutines-core-jvm>;<apk-classes>;.tmp/device-qa/d05/jvm" D05Candidates app/src/main/assets/pinyin_dict.tsv eval/d05/dev/dev.tsv final .tmp/device-qa/d05/candidates-final.txt
```

## 6. 未验证项与限制

1. **无 OCR**：候选文字由截图目视识别并与 JVM 候选表核对；没有拍摄多帧自动 OCR。重复执行或换分辨率时，需人工复核或接入 OCR/无障碍节点。
2. 只测 1 台设备 / 1 个 ROM / 1 套 Miuix 浅色主题；折叠行“可见 2 个 chip”、面板“首屏 4 行”是本设备/本字号的实测，不同 DPI/字号/主题会变化，不能直接外推。
3. 输入使用 `adb shell input tap` 单指注入，所有横滑/滚动为合成手势；不代表真实双拇指触控或真实用户速度。
4. 只取 2 个 partial 案例和 2 个排序案例（均来自 D05 dev 诊断集，非验收）；不能据此宣称整体产品或验收通过。
5. ranking 的“操作数”以明确路径计（展开/面板滚动/翻页/点击）；若用户改用横滑路径或不同手势，绝对次数会变化，但方向“final 正确候选更靠前、浏览步骤更少”在两案例均成立。
6. D05 dev 可逐条诊断，本报告只作为真实设备操作成本的补充证据，不替代 v3 validation/JVM 指标。

## 7. 结论摘要

| 问题 | 结论 |
|---|---|
| rank6 是否比 rank4 增加滚动/展开/修正？ | **是，增加 1 次面板上滑或 1 次折叠行横滑；不增加展开次数/翻页/退格/修正**。partial 选中后前缀与剩余 composing 均正确。 |
| 排序 0.35→0.50 是否减少完整句操作？ | **在这 2 个 old rank>5→new rank<=5 的 dev 案例上减少 3/2 次候选浏览操作**；样本小，不能外推为总体收益。 |
| “前8项返回”是否等于屏幕可见？ | **否**：折叠行仅 2 chip（1 完整+1 截断）；展开面板首屏仅 4 个完整行，第 5/6 位需要面板滚动。 |
| 未验证 | OCR 自动识别、多设备/多字号、真实双拇指；均如实标记。 |

**最终判断：D05 的 partial rank6 在真机上确实带来 1 次滚动成本，不是“免费仍在 8 项可见区”；排序改进在这两个 dev 案例上能减少 2–3 次浏览操作。二者都是小样本设备证据，不能替代验收。**

收尾状态：最终包 `E888234F…` 已保留安装（108,812,306 B，设备端 hash 一致，见 `.tmp/device-qa/d05/final-package-info.txt`）；测试期间临时关闭的 AI/学习已恢复为开启（`bitap_settings.xml` 已还原），默认输入法仍为 Bitap。
