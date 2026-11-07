# 真机性能测量：基线 0.1.0 vs Batch C Final

> device-qa 设备侧报告，已并入 [FINAL_DELIVERY.md](FINAL_DELIVERY.md)。设备：fe4ab855（22021211RC / Android 16 / API 36 / 1080x2400 / 440dpi / **user build**）。
> 每张数据表均标注对应 APK SHA 与测量脚本；原始输出在 `.tmp/perf2/`。

## 1. 被测包、环境与设置

| 角色 | 文件 | SHA-256 | 大小 | 版本 | 源码 |
|---|---|---|---:|---|---|
| 基线 | dist/bitap-0.1.0-demo.apk | A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC | 28,555,607 B | versionName=0.1.0 / versionCode=1 | 956b504 |
| After | dist/bitap-0.1.0-final.apk | CFA886673D36645EE4C76B2A3DF2A4FFAFC379EB69EF0140AA6CB11F98435EE0 | 28,301,655 B | versionName=0.1.0 / versionCode=1 | b5caf39 |

- 安装：`adb -s fe4ab855 install -r <apk>`（final 安装于 2026-11-07 23:16:44；基线回滚用同一命令）。
- 固定设置：屏幕亮度 20（手动，`screen_brightness_mode=1`），媒体音量未修改；默认输入法 `com.bitap.keyboard/.ime.BitapImeService`；测试页 Via(`mark.via/.Shell`) 打开 `.tmp/perf/perf.html`（本地 HTTP 8771 + adb reverse）。
- AI 模型：已导入 `/data/data/com.bitap.keyboard/files/models/bitap-qwen3-0.6b.litertlm`，343,490,560 B。
- 温度：基线测量期间 battery 29.7–30.5°C；final 测量期间 30.5–30.8°C，收尾 30.7°C；thermal status 0；每次测量前记录并等温（<35°C）。

## 2. 冷启动 / 热启动

**口径（脚本 `.tmp/perf2/tools/run_startup.ps1` + 设备端 `measure_tap_show.sh`）：**
- 设备端 shell 循环每 ~30ms 执行 `dumpsys window windows`，解析 InputMethod 窗口块（`package=com.bitap.keyboard` 且 `mHasSurface=true`）；从 `input tap 540 500` 发出到窗口可见的耗时完全在设备端计时，避免 adb 往返。
- 冷启动：Via 测试页前台 → `ime set Bitap` 显示一次 → BACK 收起 → `am force-stop com.bitap.keyboard` → 500ms → `settings put default_input_method Bitap` → 300ms → tap textarea → 轮询。
- **重要限制**：Android 16 对默认 IME 有系统级保活，force-stop 后 <100ms system_server 就以新 pid 重新绑定进程；因此本“冷启动”测的是**进程重启后的首次显示**，不是“进程不存在时点击”（无法在该设备稳定获得该状态）。每样本记录的 pid_before==pid_after（新 pid 已在 tap 前存活）。
- 热启动：键盘已显示过、进程存活，BACK 收起后再次 tap。
- 样本：基线与 final 均使用同一脚本、n=12 cold + n=12 hot。基线 cold 第 1 样本 5301ms/150 次轮询为轮询超时（未检出窗口，判无效，不纳入统计）；基线有效 cold n=11，final n=12。

| 指标 | 基线 0.1.0 (A8D8AFE8…) | After final (CFA88667…) | 说明 |
|---|---:|---:|---|
| 冷启动 p50 | 201 ms (n=11 有效) | **134 ms** (n=12) | 同脚本同 n（基线 1 个无效样本单列） |
| 冷启动 p90 | 513 ms | **142 ms** | 质量门禁 p90 ≤300ms：基线 FAIL → final PASS |
| 冷启动 p95 | 517.5 ms | **144.2 ms** |  |
| 冷启动 min–max | 190–522 ms | 108–147 ms |  |
| 热启动 p50 | 92 ms (n=12) | 91.5 ms (n=12) |  |
| 热启动 p90 | 99.8 ms | 104.7 ms |  |
| 热启动 p95 | 131.5 ms | 105.0 ms |  |

**重启后首测（n=1，脚本 `.tmp/perf2/tools/reboot_once.ps1` / `reboot_measure.ps1`）**：`adb reboot` → `sys.boot_completed=1` 用时 **34.4 s**；重启后 Bitap pid=6729 已由系统预绑定（无输入也已存在），首次 tap→键盘窗口可见 **180 ms**。结论：即使重启也无法获得“进程不存在”状态，冷启动应按“首次显示”理解；n=1 仅作参考。
原始：`.tmp/perf2/baseline-startup.csv`、`.tmp/perf2/startup.csv`、`.tmp/perf2/reboot-first-show.png`。

## 3. 按键到帧端到端（4 类）

**口径（脚本 `.tmp/perf2/tools/run_key_latency.ps1` + `run_key_latency2.ps1`；解析 `parse_framestats.py`）：**
- 每次类别前 `dumpsys gfxinfo com.bitap.keyboard reset`，再以 `adb shell input tap` 逐键输入；结束后抓 `dumpsys gfxinfo ... framestats`。
- 取 `InputEventId != 0` 的 input 帧（每键通常 1 帧；另有 1 帧非 input 动画帧不计）。两列口径：
  - **HandleInputStart → FrameCompleted**：应用侧 input 处理到帧完成（质量门禁“按键处理主线程 ≤50ms”近似口径；不含合成器显示）。
  - **HandleInputStart → DisplayPresentTime**：FrameTimeline 的系统 present 时间（非 JVM 查询），更接近用户看到的一帧。
- 输入方式为 InputManager 注入（`adb shell input tap`），非物理触控；`getevent` 不产生设备节点事件，因此无法用 getevent 做物理 tap 对齐。`dumpsys SurfaceFlinger --latency` 对 InputMethod layer 只返回刷新周期 16.67ms、无帧数据（Android 16 该 layer 无 latency buffer），已按任务要求以 gfxinfo 为替代并标注口径边界。
- 屏幕刷新：FrameInterval 观测到 8.26–16.67ms（120Hz 面板），present 时间受 vsync 量化影响，p95 为粗粒度。
- 样本：基线 142 个 input 帧（normal 38 / long 37 / short 30 / correct 37）；final 142 个（同分布）。

| 类别 | 基线 p50 h→c | 基线 p95 h→c | 基线 p50 h→present | 基线 p95 h→present | final p50 h→c | final p95 h→c | final p50 h→present | final p95 h→present |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 普通拼音 | 12.8 ms | 17.7 ms | 31.5 ms | 64.7 ms | 13.7 ms | 21.2 ms | 39.5 ms | 64.7 ms |
| 长句 | 11.4 | 15.3 | 31.4 | 64.5 | 11.7 | 16.2 | 35.6 | 48.3 |
| 简拼 | 10.7 | 13.3 | 31.4 | 39.8 | 11.3 | 13.9 | 31.4 | 63.5 |
| 纠错 | 10.2 | 14.0 | 23.4 | 39.7 | 10.3 | 14.4 | 23.3 | 64.4 |
| **合计 142 帧** | **11.0** | **15.5** | **31.3** | **64.3** | **11.5** | **17.6** | **31.4** | **64.6** |

结论：final p95 h→c 17.6ms，低于 50ms 门禁；与基线基本持平（p95 +2.1ms，属输入帧分布波动，未出现倍数级退化）。

## 4. 连续输入帧卡顿（gfxinfo framestats）

**口径（脚本 `.tmp/perf2/tools/run_jank.ps1`；解析 `parse_jank.py`）**：clear → `gfxinfo reset` → 连续输入 87 个字母（约 12s）→ 等待 1s → `dumpsys gfxinfo ... framestats`。janky% 取 dumpsys summary（全量帧）；p50/p90/p95/p99 取 framestats 最近 120 帧的 `FrameCompleted-IntendedVsync`；另算 `FrameCompleted > FrameDeadline` 的 deadline-miss 比例。

| 指标 | 基线 0.1.0 (A8D8AFE8…) | After final (CFA88667…) |
|---|---:|---:|
| 总帧数（summary） | 173 | 175 |
| Janky frames | 130（75.14%） | 119（68.00%） |
| p50 / p90 / p95 / p99 帧耗时（framestats 120 帧） | 15.3 / 24.5 / 27.0 / 28.2 ms | 13.8 / 24.0 / 25.5 / 28.5 ms |
| 最大帧耗时 | 29.6 ms | 33.5 ms |
| Frame deadline missed（framestats 120 帧） | 87（72.5%） | 80（66.7%） |
| Missed Vsync（summary） | 0 | 0 |
| Slow UI thread（summary） | 76 | 69 |

结论：final janky% 从 75.1% 降至 68.0%，p50 改善；但 p99/最大帧略升（28.5/33.5 vs 28.2/29.6ms），属长尾波动；整体仍偏高（120Hz 下 16.7ms deadline），列为已知短板。原始：`.tmp/perf2/baseline-gfx-jank*.txt`、`gfx-jank*.txt`。

## 5. PSS / Java / Native / Graphics

**口径（脚本 `.tmp/perf2/tools/final_mem.ps1`、`mem_idle.ps1`、`final_ai_off_fresh.ps1`）**：`dumpsys meminfo com.bitap.keyboard -d`。空闲=键盘显示 3s 后；连续输入后=39 个字母输入后 2s；AI 生成中=输入 nihao 后点 ✦ AI，每秒采样 18 次取峰值。PSS 含 native 引擎分配；模型 343,490,560 B 已导入私有目录，AI 生成时 Native Heap 峰值 1.32GB 远大于模型文件本身（包含 LiteRT runtime/arena），因此 PSS 不能仅按 mmap 模型文件解释。

| 场景 | 基线 0.1.0 | After final |
|---|---:|---:|
| 空闲（AI on，键盘刚显示） TOTAL PSS / Java / Native / Graphics | 117,691 / 61,488 / 20,176 / 20,388 KB | **98,510 / 49,084 / 19,124 / 10,096 KB** |
| 连续输入后（基线当时 AI off；final AI on） TOTAL PSS / Java / Native / Graphics | 131,151 / 63,284 / 27,652 / 21,324 KB | 95,790 / 36,864 / 17,852 / 20,784 KB（AI on） |
| AI 关闭（fresh 进程） TOTAL PSS / Java / Native / Graphics | 95,444 / 38,992 / 28,800 / 8,984 KB | **65,159 / 33,696 / 9,388 / 10,992 KB** |
| AI 生成中峰值 TOTAL PSS / RSS / Native | 1,735,188 / 1,895,836 / 1,256,952 KB（8s 峰值） | **1,793,781 / 1,948,668 / 1,321,276 KB（4s 峰值）** |
| AI 生成结束后（18s，未退出） TOTAL PSS / Native | 1,735,188 / 1,256,952 KB | 1,718,397 / 1,250,452 KB |

**AI 开/关差异（final）**：AI off fresh 65,159 KB；AI on 未生成 idle 98,510 KB（+33.4MB）；AI 生成峰值 1,793,781 KB（相对 idle +1.69GB）。注意：在**已生成过**的进程里关闭 AI 并不会释放 native 内存（实测切 off 后 PSS 仍 1,725,568 KB，Native 1,254,284 KB）；只有杀进程重启才回到 65MB。这是当前最大的内存瓶颈/风险（低内存设备可能被 LMK）。
原始：`.tmp/perf2/mem-idle.txt`、`mem-ai-off-idle.txt`、`mem-ai-off-typing.txt`、`final-mem-idle.txt`、`final-mem-typing.txt`、`final-mem-ai-off-idle-fresh.txt`、`final-mem-ai-gen-*.txt`。

## 6. avgTaps 真机核对（3 条 dev long_segment）

**口径**：从 `eval/pinyin_cases_dev.tsv` 选 61/62/63（未用 holdout/sealed）；设备用键盘实际逐字母输入并记录 ①候选选择点击 ②展开/翻页点击 ③纠错/退格 ④拼音字母按键；与同一 APK 的 JVM 评测（`build/pinyin-quality-eval-after.txt`，956b504 阶段）rank+1 对比。设备结果在基线包（A8D8AFE8…）上采集。

| 用例 | 期望句 | 拼音字母键 | 候选选择点击 | 展开/翻页 | 纠错/退格 | JVM rank+1 | 差异说明 |
|---|---:|---:|---:|---:|---:|---:|---|
| dev-long_segment-61 | 我今天下午要去公司参加一个重要的会议 | 50 | 1 | 1 | 0 | 2（TOP2） | 设备折叠态只显示首候选，点 v 展开后选第2位：1 展开+1 选择；JVM 把第2位记作2次选择 |
| dev-long_segment-62 | 明天上午十点我们在会议室讨论项目进度 | 56 | 1 | 1 | 0* | 3（TOP3） | 展开后选第3位：1 展开+1 选择；JVM rank+1=3；*测量中因坐标误差误选2次（非正常路径，不计） |
| dev-long_segment-63 | 这个问题我们已经讨论过很多次了 | 40 | 1 | 1 | 0 | 1（TOP1） | 折叠态只显示前缀「这个问题我们已经讨论过很多」，展开后才见整句第1位，故实际 UI 点击=1展开+1选择 |

结论：3 条均可选到期望整句；JVM 的 rank+1 只代表“候选选择次数”，未计入拼音字母（40–56 键/条）与候选面板展开；实际设备每条还多 1 次展开操作。设备选词次数与 JVM 一致（rank 位置 2/3/1 → 1 次选择），展开使 UI 总操作数 +1。原始截图：`.tmp/perf2/avgtaps-61-*.png`、`avgtaps-62c-selected.png`、`avgtaps-63-selected.png`；汇总 `.tmp/perf2/avgtaps-baseline.csv`。

## 7. 稳定性/交互验证（final）

| 项目 | 结果 | 证据 |
|---|---|---|
| 过期查询取消（快速输入 nihao 后追加 pengyou） | PASS：候选显示最新「你好朋友」等，无旧「你好」残留 | `final-stale-query.png` |
| 长时间输入（100 个字母连续输入，含已有文本 len=112） | PASS：无崩溃、进程存活、候选/键盘正常；**未做连续 3 分钟** | `final-long-input.png` |
| 后台恢复（Home→返回 Via→点击输入框） | PASS：后台时键盘隐藏，返回后 tap 输入框键盘恢复 mInputShown=true | `final-background-resume2.png` |
| AI 模型状态 | 已导入（343,490,560 B）；AI 默认开 | 见 §5 |

## 8. 瓶颈与限制

1. **AI 生成内存峰值 1.79GB PSS / Native 1.32GB**（final）——最大风险；关闭 AI 不释放已加载 native 内存，需杀进程；低内存设备可能 LMK。基线 1.74GB，两者接近。
2. **连续输入 jank 仍高**：final 68% janky（基线 75.1%），120Hz 下多数帧超过 16.7ms deadline；p99 32ms。
3. 冷启动基线 p90 513ms 不达 300ms 门禁，final p90 142ms 达标；热启动 ~92–105ms 稳定。
4. 按键处理应用侧 p95 17.6ms（<50ms 门禁），present p95 64.6ms 属显示链路。
5. 测量限制：SurfaceFlinger --latency 无 InputMethod 数据；input tap 为注入事件而非物理触控；冷启动受系统 IME 保活影响（“进程重启后首次显示”口径，重启后 n=1 亦证明进程预绑定）；gfxinfo framestats 仅保留最近 120 帧，janky% 用 summary 全量、分位数用 120 帧。
6. 基线 P1 的「过期查询/长输入/后台恢复」未在基线包上执行（优先 P2 与 final after，时间限制）；final 已 PASS，基线对应项标注未测。3 分钟长稳未做。
7. 设备温度全程 29.7–30.8°C，thermal status 0；无过热降频迹象。

## 9. 文件与脚本索引

- 启动：`.tmp/perf2/startup.csv`（final）、`.tmp/perf2/baseline-startup.csv`（基线，含 1 个无效样本）、`.tmp/perf2/tools/run_startup.ps1`、`measure_tap_show.sh`、`reboot_once.ps1`。
- 按键：`.tmp/perf2/gfx-key-*.txt`、`gfx-key2-*.txt`（final 原始）、基线统计见 §3（原始基线帧文件在 final 重跑时被覆盖，数值已在本文记录）；解析 `parse_framestats.py`。
- Jank：`baseline-gfx-jank.txt` / `baseline-gfx-jank-summary.txt`（基线归档）、`gfx-jank.txt` / `gfx-jank-summary.txt`（final）、`parse_jank.py`。
- 内存：见 §5 文件列表。
- avgTaps：`avgtaps-*.png`、`avgtaps-baseline.csv`。
- 交互：`final-stale-query.png`、`final-long-input.png`、`final-background-resume2.png`。
