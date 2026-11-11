# 开发指南

## 1. 环境与目录

Windows PowerShell；JDK17+（已有验证环境为JDK25）、Android SDK compile37/target36、Gradle Wrapper9.7.1。版本以 `gradle/libs.versions.toml`、`app/build.gradle.kts` 和 wrapper 配置为准。当前仅构建 Android13+ arm64。

`local.properties` 的 `sdk.dir` 是本机路径，不提交。Python工具使用 Python3，数据处理主要依赖 pypinyin0.55.0、zhconv1.4.3；建议虚拟环境固定版本。部分历史脚本支持 `.tmp/pypinyin_pkg`，不要无说明切换数据生成依赖。

Python依赖清单为根目录 `requirements-dev.txt`，可用 `python -m pip install -r requirements-dev.txt` 安装；它们不是Android运行时依赖。

| 目录 | 用途 |
|---|---|
| `app/src/main` | 生产代码、Android资源、生产拼音资产 |
| `app/src/test` | 状态机、引擎、AI、隐私、评测与事件回放 |
| `eval/` | 冻结语料、生成器、校验器、聚合快照、署名 |
| `scripts/` | 构建、统一检查、数据与设备工具 |
| `legal/` | 法律署名和数据来源，与开发文档分开维护 |
| `.tmp/` | 忽略的本地数据原件、依赖缓存和临时设备输出 |
| `build/`、`app/build/`、`dist/` | 可再生报告和安装包，禁止作为源文件引用 |
| `.local/` | 忽略的本地可恢复整理备份，不是发布输入 |
| `models/` | 用户本地模型权重，忽略且整理时保留 |

## 2. 构建与统一检查

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
```

debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；release 默认未签名，位于 `app/build/outputs/apk/release/`。开发包体包含调试/推理依赖，不应作为最终发布体积承诺。

`scripts/verify.ps1` 是统一工程入口：实际重跑单测、lint、debug构建、v1/v2/v3/D05语料校验、Python契约、文档引用和APK网络权限检查。旧 `verify-phase1.ps1` 保留兼容转发，避免维护两套相似流程。工程门禁不是产品发布验收。

`-StrictSources` 当前明确加严的是v3的CC0原件/zip校验，不代表所有生产资源和D05来源已获得完整严格校验；生产detailed export的缺失和许可再核验仍须单独处理。

```powershell
# 严格源文件校验要求冻结CC0原件与zip都存在
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1 -StrictSources

# 已知v3重合：退出1是发现，不能为了全绿改掉冻结语料
python eval/v3/audit_training_overlap.py
```

统一门禁暂时清除并最终恢复 sealed/v2validation/v3验收及D04导出的 opt-in 环境开关，不意外消费冻结验收集。专门验收由候选冻结后的显式命令单独执行，见 [评测规范](EVALUATION.md)。

## 3. 数据与模型准备

默认轻量校验在部分源文件缺失时可能跳过溯源；严格发布前须确认所有声明来源与hash。不要临时下载“最新版”覆盖冻结原件后重新生成历史语料。来源路径和hash见各 `manifest.json`、[数据来源](../legal/DATA_PROVENANCE.md)。

生产词典生成需指定jieba原件与Tatoeba detailed export；`.tmp/tatoeba/cmn_sentences_detailed.tsv.bz2` 在本次盘点中不存在，不能把普通导出冒充它。原生产资产和976名作者清单保留，完整资源再校验需要准备匹配provenance的原件。

模型通过系统文件选择器导入 `.litertlm`。外部下载辅助脚本为 `scripts/download-model.ps1`，应用自身没有网络权限。模型不是运行单测或基础输入的前提。

## 4. 常用诊断

```powershell
# D05 dev是可查看、可调参的开发集
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05DevEvalTest --rerun-tasks --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05RankingAblationTest --rerun-tasks --console=plain
# 长输入边界诊断：输出 build/d05/long-input-boundary.txt 与 -sweep.txt
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinLongInputBoundaryEvalTest --rerun-tasks --console=plain
# Partial排序诊断：输出 build/d05/partial-ranking.txt
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinPartialRankingEvalTest --rerun-tasks --console=plain
# D05 natural_long 路径丢失诊断：输出 build/d05/long-sentence-path-loss.txt 与 -detail.tsv
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05LongSentencePathLossTest --rerun-tasks --console=plain
# 长输入 lattice 预算消融：输出 build/d05/long-budget-ablation.txt（默认关闭实验）
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05LongBudgetAblationTest --rerun-tasks --console=plain
# D05 各类别质量门禁：写 dev-baseline.txt，低于 3894643 基线允许窗口即测试失败
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05DevEvalTest --rerun-tasks --console=plain
# natural_long 句级 rerank 单机制消融：输出 build/d05/long-sentence-rerank-ablation.txt（只读诊断）
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05LongSentenceRerankAblationTest --rerun-tasks --console=plain
# 长输入局部纠错窗口诊断：输出 build/d05/local-correction.txt（默认关闭实验）
.\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinD05LocalCorrectionEvalTest --rerun-tasks --console=plain
python eval/d05/dev/check_d05_dev.py

# 宿主Rime实验，非Android集成
powershell -NoProfile -ExecutionPolicy Bypass -File eval/d04/bootstrap.ps1
```

D04使用说明位于 `eval/d04/README.md`，同词表工具位于 `eval/d04/same_dictionary/`。原始报告输出到 `build/`，提交的聚合快照应有数据hash、参数和环境。

## 5. 设备测试

本轮将原先只存在临时目录的 `p0.html`、`unicode.html`、`password.html` 提升为 `scripts/device-qa/fixtures/` 的测试源文件。

```powershell
# 在独立终端以前台方式启动，仅绑定本机
python -m http.server 8769 --bind 127.0.0.1 --directory scripts/device-qa/fixtures
# 密码smoke历史脚本使用8770；运行时另开终端
python -m http.server 8770 --bind 127.0.0.1 --directory scripts/device-qa/fixtures
adb -s <serial> reverse tcp:8769 tcp:8769
adb -s <serial> reverse tcp:8770 tcp:8770
```

`scripts/device-qa/` 为设备基线/AI资源/smoke/D05操作工具；`scripts/p0-device/` 为历史正确性回归工具。多数脚本带具体机型坐标/默认serial，换设备必须传参并重新测量，不能当通用自动验收。部分脚本会改变默认IME、旋转、开关或安装包，执行前读脚本并记录/恢复设备状态。

编辑工具与空格手势的设备脚本：`scripts/device-qa/editing_tools.ps1`；D07 连续混排设备脚本：`scripts/device-qa/mixed_input.ps1`。两者都需要本机 HTTP 服务、`adb reverse` 与目标机安装本轮 APK。它用 `uiautomator dump --compressed` 读取页面状态，键盘按键坐标按 22021211RC/1080×2400@440 测量；换机型/字体/横屏需重新测量，不能当通用矩阵。

编辑正确性回归脚本：`scripts/device-qa/regression_fixes.ps1`，使用 debug-only `QaEditorActivity` 的“结束组合”按钮与 `selectall` 宿主字段；需要安装本轮 debug APK，不需要 HTTP 服务。该宿主不进入 release。

```powershell
# 在 8769 起服务并 adb reverse 后；如端口被占用可用 -BaseUrl 指向实际端口
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-qa/editing_tools.ps1 -Serial <serial> -Tag editing
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-qa/english_prediction.ps1 -Serial <serial> -Tag english
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-qa/smoke.ps1 -Serial <serial> -BaseUrl http://127.0.0.1:8769 -Tag smoke
```

2026-11-09 本轮已连接 22021211RC 执行 `editing_tools.ps1`（14 PASS/0 FAIL）和 `smoke.ps1`（10 PASS/0 FAIL）；历史其他设备结论仍为历史记录，不并成本轮新测。

## 6. 工作区整理

```powershell
# 只预览
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tidy-workspace.ps1 -WhatIf
# 可恢复整理：将生成物移至.local，不删除源代码、原始语料、模型或本机配置
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tidy-workspace.ps1
```

先完成运行和验证，再停止Gradle daemon后移动缓存。备份占用磁盘，整理不等于释放空间。不要对工作区运行无差别 `git clean -fdx`，它会删除模型、数据原件和本机配置。

## 7. 提交要求

一个批次只承担一个主要机制或一项仓库维护目的。记录before/after、数据hash、分母、负向类别、测试与未测项。禁止按验收句写生产规则；禁止把文档目标改写成已达结果。旧文档重置后的历史可通过 `git show 69ad648:docs/<旧文件>` 查询。
