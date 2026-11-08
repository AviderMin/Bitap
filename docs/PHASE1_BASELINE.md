# Bitap 阶段一基线（D00）

- 文档编号：D00 / PHASE1_BASELINE
- 日期：2026-11-08（Asia/Shanghai）
- 代码基线：`54bc9f33e99fe670b4bab2e39400b7b816fc4676`（短哈希 `54bc9f3`）
- 版本：0.1.0（versionCode 1）
- 状态：D00 交付物。本文件的数字分为「本轮实测」与「历史数据」两类，两者不混用、不互相冒充。

## 1. 基线定义与工作区状态

- 代码基线为 commit `54bc9f3`（`docs: record next-round delivery, sealed evaluation and device performance`），该提交仅文档变更；其父提交 `a51eac7` 为功能修复提交（AI 入口语义与显式中英切换）。历史 release APK 构建于更早的 `b5caf39`，早于 `a51eac7` 的上述修复，故历史设备数据与本轮代码存在小范围差异。
- 本轮测量时 `git status --porcelain=v1` 仅有一项：`?? docs/PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md`（本轮研发文档，未跟踪）。测量时没有源码、词库或评测语料改动。
- 说明：团队并行开发开始后工作区会包含其他任务的未提交改动；本文件所有「本轮实测」数字均以 `54bc9f3` + 上述测量时状态为准，后续代码变化不改变这些基线值。

## 2. 环境

| 项 | 值 | 证据 |
|---|---|---|
| 操作系统 | Microsoft Windows NT 10.0.28000.0；PowerShell 7.6.9 | `[System.Environment]::OSVersion.VersionString`、`$PSVersionTable.PSVersion` |
| JDK | openjdk 25.0.4.1 2026-08-18 LTS（Microsoft-14951867，build 25.0.4.1+1-LTS）；`JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\` | `java -version` |
| Gradle | 9.7.1（Kotlin 2.4.0 / Groovy 4.0.32 / Launcher JVM 25.0.4.1） | `gradle/wrapper/gradle-wrapper.properties`、`.\gradlew.bat --version` |
| Android 构建 | compileSdk 37（compileSdkMinor 0）、targetSdk 36、minSdk 33、versionName 0.1.0、versionCode 1、ABI arm64-v8a | `app/build.gradle.kts:11-22` |
| 测试设备 | serial `fe4ab855`；型号 22021211RC（product `lineage_munch`，device `munch`）；Android 16 / API 36；1080×2400 @ 440dpi；默认输入法 Bitap | `adb devices -l`、`adb -s fe4ab855 shell getprop ro.product.model` / `ro.build.version.release` / `ro.build.version.sdk`、`wm size` / `wm density`、`settings get secure default_input_method` |

## 3. 本轮实测（2026-11-08）

本轮实测均在 `54bc9f3` 的干净代码快照上执行；单测/lint 使用 `--rerun-tasks` 强制重跑（不是缓存 UP-TO-DATE 结果）。

### 3.1 单测与 lint

命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --rerun-tasks --console=plain
```

结果：

| 项 | 结果 | 证据 |
|---|---|---|
| 构建 | BUILD SUCCESSFUL in 59s；35 actionable tasks: 35 executed | 命令输出 |
| 单测 | **311 tests / 0 failures / 0 errors / 1 skipped** | `app/build/test-results/testDebugUnitTest/TEST-*.xml`，48 个测试类 XML 汇总（tests/failures/errors/skipped 属性求和） |
| 跳过项 | `PinyinSealedEvalTest` 默认跳过（仅在 `PINYIN_SEALED_RUN=1` 时执行，见该测试 `assumeTrue`） | `app/src/test/java/com/bitap/keyboard/core/PinyinSealedEvalTest.kt:68-71` |
| Lint | **0 errors / 26 warnings / 1 hint**（27 issues） | `app/build/reports/lint-results-debug.xml` 按 `issue severity` 统计 |

单测汇总的 PowerShell 复核命令：

```powershell
$files = Get-ChildItem app/build/test-results/testDebugUnitTest/TEST-*.xml
$t = 0; $f = 0; $e = 0; $s = 0
foreach ($file in $files) {
  [xml]$x = Get-Content -LiteralPath $file.FullName
  $t += [int]$x.testsuite.tests; $f += [int]$x.testsuite.failures
  $e += [int]$x.testsuite.errors;  $s += [int]$x.testsuite.skipped
}
"tests=$t failures=$f errors=$e skipped=$s suites=$($files.Count)"
```

### 3.2 Debug APK

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 字节数 | 107,796,359 B（约 102.8 MiB） |
| SHA-256 | `DBAE3A9F230A24A901E867623D922DC9FBE1E819463D35B29FAFDD887CDE52D4` |
| 版本 | versionName 0.1.0 / versionCode 1（`app/build/outputs/apk/debug/output-metadata.json`） |
| 产物时间 | 2026-11-07 18:58:21（本任务未重新执行 `assembleDebug`；哈希取自现存 debug 产物） |

复核命令：

```powershell
Get-FileHash -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

> 说明：本任务是基线记录，不重新构建 APK，避免把并行开发中的源码带进产物。哈希与 Lead 在 `54bc9f3` 基线上的测量一致。

### 3.3 离线词库

| 项 | 值 |
|---|---|
| 文件 | `app/src/main/assets/pinyin_dict.tsv` |
| 文件字节数 | 2,304,382 B |
| 总行数 | 129,168（含注释/空行） |
| 有效词条 | **65,999 条** |
| 1 字词条 | 5,999 |
| 2 字词条 | 40,538 |
| 3 字词条 | 13,272 |
| 4 字词条 | 6,190 |
| 文件 SHA-256 | `4C979D12D957F2E567F06844CFAA705E07DBF544B808FF055C86669D5A53F20D` |

统计口径：UTF-8（BOM 容忍）；跳过空行与 `#` 开头行；每行按 TAB 拆分，第二列为词条；按词条字符数分组（故 1+2+3+4 字 = 65,999，无 5 字及以上词条）。

复核命令（Python）：

```python
from pathlib import Path
raw = Path("app/src/main/assets/pinyin_dict.tsv").read_bytes()
lines = raw.decode("utf-8-sig").splitlines()
entries = [l for l in lines if l.strip() and not l.strip().startswith("#")]
from collections import Counter
c = Counter()
for l in entries:
    parts = l.split("\t")
    w = parts[1].strip()
    c[len(w) if len(w) <= 4 else "5+"] += 1
print(len(entries), dict(sorted(c.items())))
```

> README 的「约 3 万条」与历史 `docs/08-delivery.md` 的「29,999 条」是早期数字；本任务已把 README 修正为 65,999，历史交付文档按「历史数据不改写」原则保留原样。历史文档与当前资产的差异以本文件为准。

### 3.4 评测语料

| 数据集 | 文件 | 用例数 | 类别构成 | SHA-256 |
|---|---|---|---|---|
| dev | `eval/pinyin_cases_dev.tsv` | 132 | 11 类 × 12（daily_chat/time_place/work_comm/unseen_sentence/homophone/long_segment/mixed_en/number_punct/shorthand/mixed_pinyin/typo） | `9a5193da6f2869d513ce0fb7d5bc3fbadc5da9398ff658e9ee12c587d651c5a3` |
| holdout | `eval/pinyin_cases_holdout.tsv` | 132 | 同上，11 类 × 12 | `4caf2b4f75aa8d425534b0eb726c167d044994822a40d66e8429c89e479304ce` |
| sealed | `eval/pinyin_cases_sealed.tsv`（与 `.tmp/sealed/pinyin_cases_sealed.tsv` 内容相同） | 132 | 6 类 × 22（long_natural/shorthand_ambiguity/mixed_pinyin/typo_correction/english_abbrev/number_punct） | `d9e2d946ca131ac452654bfac66d44a21c9cf1757f59e5ed2e5a66aff3a5b987` |

复核命令：

```powershell
python eval/check_corpus.py
python eval/check_no_leak.py
```

本轮结果：

- `check_corpus.py`：`dev=132 holdout=132 total=264`、`categories ok=True`、`errors=0`、`polyphone-warnings=0`。
- `check_no_leak.py`：`holdout cases=132 filesScanned=203 checks=29435`、`source-leaks=0`、`dictionary-whole-sentence-hits=0`。

> 说明：`check_corpus.py` 依赖 `pypinyin`，需使用装有该依赖的系统 Python；bundled runtime 的 Python 不含 pypinyin，直接运行会报 `ModuleNotFoundError`。`check_no_leak.py` 不依赖 pypinyin。

### 3.5 拼音质量基线（dev / holdout）

为给 D01 新协议提供同口径对照，本轮用现有 `PinyinQualityEvalTest` 在 `54bc9f3` 上重跑 dev/holdout（JVM，词典 65,999 条，搜索上限 8）。本任务已把该测试的输出标签由 `avgTaps` 改为 `rankCost`，数值定义不变。

命令：

```powershell
$env:PINYIN_EVAL_STAGE='phase1-baseline'
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks --console=plain
```

报告：`build/pinyin-quality-eval-phase1-baseline.txt`

| 口径 | 用例数 n | Top1 | Top5 | rankCost |
|---|---:|---:|---:|---:|
| dev 全量 | 132 | 97/132（73.5%） | 108/132（81.8%） | 2.52 |
| dev 引擎可评分 | 119 | 94/119（79.0%） | 102/119（85.7%） | 2.20 |
| holdout 全量 | 132 | 81/132（61.4%） | 108/132（81.8%） | 2.78 |
| holdout 引擎可评分 | 119 | 79/119（66.4%） | 102/119（85.7%） | 2.46 |

说明：

- 「引擎可评分」排除 mixed_en 交互项与含数字的输入（这些是交互/端到端项，不应混入纯引擎准确率分母）。
- 本结果与 `FINAL_DELIVERY.md` 记录的 after 值一致（dev 引擎 79.0%/85.7%，holdout 引擎 66.4%/85.7%），可作为回归对照。
- 该结果不是设备实测延迟或真实操作数；JVM 延迟只能作参考。

### 3.6 设备空闲 PSS

命令：

```powershell
adb -s fe4ab855 shell dumpsys meminfo com.bitap.keyboard
```

Lead 在本轮基线条件（设备静置、键盘未显示）下实测：**TOTAL PSS 56,730 KB**（读取 `dumpsys meminfo` 的 `TOTAL PSS` 行，单位 KB；同一输出还含 Native Heap / Dalvik Heap 等分项）。

本任务复核说明：设备在测量期间被并行设备 QA 使用，PSS 随键盘显示/使用状态明显波动（11:37 抽查 62,206 KB，11:42 抽查 123,160 KB）。因此 `56,730 KB` 是「静置空闲」基线值，不能当作固定常量；任何前后对比必须在同一场景、同一操作序列下采集，并同时记录 Native/Dalvik 分项。

## 4. 历史数据（引用，不冒充本轮测量）

下表均来自历史文档，未经本轮重新执行；仅用于区分「过去基线」与「本轮基线」。

| 来源 | 日期/版本 | 指标 | 历史值 |
|---|---|---|---|
| `docs/FINAL_DELIVERY.md` | 2026-11-07；release 0.1.0；code `b5caf39`；APK `CFA88667…`（28,301,655 B） | 单测 | 311 tests / 0 failures / 0 errors / 1 skipped（sealed 默认 skip） |
| `docs/SEALED_EVAL.md` / `docs/FINAL_DELIVERY.md` | 一次性执行于 `6aa15a5`；sealed 132 条 | 引擎可评分 Top1 / Top5 / avgTaps | 51/105（48.6%）/ 78/105（74.3%）/ 3.53 |
| 同上 | sealed 132 条 | 自然长句 Top1 / Top5 | 4/22 / 12/22 |
| `docs/FINAL_DELIVERY.md` §3.2 | 2026-11-07；22021211RC / Android 16 | 空闲 PSS（AI on，刚显示） | 98,510 KB |
| 同上 | 同上 | AI 关闭 fresh 进程 PSS | 65,159 KB |
| 同上 | 同上 | AI 生成峰值 PSS / Native 峰值 | 1,793,781 KB / 1,321,276 KB |
| 同上 | 同上 | 生成后 18s PSS | 约 1.72 GB（未退出、未释放 native） |
| 同上 | 同上 | 冷启动 p50 / p90 / p95 | 134 / 142 / 144.2 ms |
| 同上 | 同上 | 按键→DisplayPresent p50 / p95 | 31.4 / 64.6 ms |
| 同上 | 同上 | 连续输入 jank（173/175 帧） | 68.00% janky |

> 历史数据保留 `avgTaps` 原名，便于与旧文档逐字对照；其数值与 `rankCost` 定义相同，因此可直接比较（例如 sealed 3.53）。历史设备 PSS 的条件与产品版本见对应文档，不能与本节 3.6 的 56,730 KB 直接相加或混用。

## 5. 指标口径：avgTaps 与 rankCost

### 5.1 定义

- **Top1**：期望候选在最终候选列表中第 1 位（rank=0）。
- **Top5**：期望候选在最终候选列表中前 5 位（rank ∈ [0,4]）。
- **rankCost（原名 avgTaps，2026-11-08 起统一）**：对每条用例，若期望候选命中且 rank=r（r≥0），记 r+1；若未进前 5（含未召回），记 9；对所有用例取平均。只计「候选选择」的代理成本，**不含拼音字母按键、候选面板展开、翻页、退格与修正**。
- rankCost 不是「真实按键总数」或「屏幕真实点选次数」；真实操作数应另设指标（如每百字修正操作、退格次数、展开次数、选词次数、语言切换次数，见研发文档 §9.2）。

### 5.2 旧名与新名的关系

- 历史 md 文档中的 `avgTaps` 与当前代码输出的 `rankCost` 是同一个公式，数值可比。改名原因：旧名容易被误读为「按键总数」，实际只是候选名次的代理值。
- 历史文档（`FINAL_DELIVERY.md`、`SEALED_EVAL.md`、`DEVICE_PERFORMANCE.md`、`PINYIN_QUALITY_EVAL.md`、`LONG_SEGMENT_ANALYSIS.md`、`NEXT_PHASE_DELIVERY.md` 等）按「历史数据不改写」原则保持 `avgTaps` 原文；新文档、新测试输出统一使用 `rankCost`。
- 评测代码中的输出字段：`rankCost`、`totalRankCost`、`engineRankCost`、`baseRankCost` / `afterRankCost`（长句诊断）。
- 本任务涉及代码：`PinyinQualityEvalTest.kt`、`PinyinSealedEvalTest.kt`、`PinyinQualityBaselineTest.kt`、`PinyinEngineLongSegmentDiagnosticTest.kt`；只改输出标签与内部同义变量名，不改任何评分、排序或搜索逻辑。

## 6. 复现命令汇总

在仓库根目录（`D:\Projects\Github\Bitap`）执行：

```powershell
# 0) 基线身份
git rev-parse HEAD
git status --porcelain=v1

# 1) 环境
java -version
.\gradlew.bat --version

# 2) 单测 + lint（强制重跑）
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --rerun-tasks --console=plain

# 3) 词库统计
python - <<'PY'
from pathlib import Path
from collections import Counter
raw = Path("app/src/main/assets/pinyin_dict.tsv").read_bytes()
lines = raw.decode("utf-8-sig").splitlines()
entries = [l for l in lines if l.strip() and not l.strip().startswith("#")]
c = Counter()
for l in entries:
    w = l.split("\t")[1].strip()
    c[len(w) if len(w) <= 4 else "5+"] += 1
print("entries=", len(entries), "byLen=", dict(sorted(c.items())))
PY

# 4) 语料检查
python eval/check_corpus.py
python eval/check_no_leak.py

# 5) 质量基线（输出 build/pinyin-quality-eval-phase1-baseline.txt）
$env:PINYIN_EVAL_STAGE='phase1-baseline'
.\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks --console=plain

# 6) APK 哈希
Get-FileHash -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256

# 7) 设备
adb devices -l
adb -s fe4ab855 shell getprop ro.product.model
adb -s fe4ab855 shell getprop ro.build.version.release
adb -s fe4ab855 shell dumpsys meminfo com.bitap.keyboard
```

> 注：第 3 步的 here-doc 在 Windows PowerShell 中可按脚本文件执行；若使用 `python -c` 请把换行改为分号并注意引号。

## 7. 已知限制

1. 单测与质量评测是 JVM 结果，不等于 Android 端到端延迟或真实触控体验；设备性能需按 `DEVICE_PERFORMANCE.md` 的口径单独测量。
2. 单设备（22021211RC / Android 16）结果不能代表 Android 13–15、其他 ROM、折叠屏或低内存机型。
3. PSS 依赖进程状态与最近使用；本任务记录的两次数值均受并行设备 QA 影响，56,730 KB 仅为 Lead 在静置条件下的基线值，后续对比必须复现同一场景。
4. 本任务未重新执行 sealed 评测（一次性历史数据），也未重新构建 release APK；sealed 只作为回归集，不应据其反复调参。
5. 文档中「本轮实测」仅指 2026-11-08 在 `54bc9f3` 干净快照上的命令结果；并行任务合并后的代码需要重新测量，不能直接沿用。
6. 词库统计以当前 `pinyin_dict.tsv` 为准；若后续替换词库，必须重新生成统计并记录 SHA-256。

## 8. 关联文档

- 能力清单：[docs/CAPABILITY_INVENTORY.md](CAPABILITY_INVENTORY.md)
- 研发总报告：[docs/PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md](PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md)
- 历史交付：[docs/FINAL_DELIVERY.md](FINAL_DELIVERY.md)、[docs/SEALED_EVAL.md](SEALED_EVAL.md)、[docs/DEVICE_PERFORMANCE.md](DEVICE_PERFORMANCE.md)
