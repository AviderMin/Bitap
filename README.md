# Bitap

离线、隐私优先的 Android 中文输入法。当前版本0.1.0，支持Android13+、arm64；仍处于输入质量与交互打磨阶段，尚无达到成熟商业输入法体验的证据。

已实现65,999词条的本地拼音候选、简拼/轻量纠错、英文/数字/符号、候选学习、设置与独立键盘主题。可选 `.litertlm` 模型只在点击 `✦ AI` 时补充拼音候选，不参与每次按键；应用无网络权限。改写/润色没有生产入口。

最新进度：D00–D03基础工程完成；D04完成宿主与同词表实验，Android PoC未完成；D05完成跨来源dev、候选展示与词内统计权重实验及小样本设备验证。当前重点是候选选择成本、长输入预算、召回/排序、纠错和连续混排。已实现：P0候选面板按可见行分页与页脚固定、分段候选标识/“看分段”入口、>64字符长输入前缀分段查询；编辑工具（全选/复制/剪切/粘贴/按词删除、撤销/重做、空格下方长按滑动光标）；本地英文补全与用户bigram下一词联想；D07连续中英数字混排基础版与句末语气词首字母简拼（nihaom→你好吗、zenml→怎么了）。长输入边界诊断与partial排序实验均未切换生产默认（64附近音节/词回退无收益；`LONGEST_FIRST`变差、`SHORTEST_FIRST`整句完成仍为1/33）。历史设备验证仅覆盖22021211RC/LineageOS23.2/Android16单机竖屏，字体放大、横屏、多ROM未测；市场对照见[市场对照](docs/MARKET_COMPARISON.md)。当前基线（审查快照`80de879`）工程门禁：568项单测（0 failures/0 errors/4 skipped）、lint 0 errors/29 warnings/1 hint、debug APK 109,573,786B、SHA-256 `228B7DB1E44952286B46914A31406535D469F24E82C7BC97AC3FFBFC5161EAD6`；这是审查 §8 / [进度](docs/PROGRESS.md)第31节的验证记录，不是本轮重跑，详见[进度](docs/PROGRESS.md)第34节。2026-11-13 批次1 完成长句目标追踪与两项默认关闭机制消融（详见[进度](docs/PROGRESS.md)第35节）：natural_long≤64 的 90 条分层为 NONE=24、BEAM_PRUNED=43、ENTRY_TRUNCATED=22、NO_DICTIONARY_ENTRY=1；两项机制均只带来 long oracle +1、Top5 仍 14/90，未达研发目标，生产默认未变；批次1新增 3 个诊断测试类、验证 571 项；随后按真机发现修复候选面板长候选展示（§37：长列表改为滚动模式、页脚显示总项数并支持上下滑动），最终 `scripts/verify.ps1` 为 574 项 / 0 failures / 0 errors / 4 skipped。22021211RC 真机抽样 5 条 natural_long（修复前）：首屏精确候选 1/5、第2页可得 2/5，其余 3/5 在最终64项内不可得；展开面板页脚“1/6页·64项”但长候选仅4项可见、余项藏在内部滚动（详见[进度](docs/PROGRESS.md)第36节）；该分页问题已在第37节改为长列表滚动模式（页脚显示总项数、上下滑动查看），不再显示虚假页码。

## 文档

- [文档索引](docs/README.md)
- [项目需求](docs/PRODUCT_REQUIREMENTS.md)
- [当前进度与已知问题](docs/PROGRESS.md)
- [架构](docs/ARCHITECTURE.md)、[开发指南](docs/DEVELOPMENT.md)、[评测规范](docs/EVALUATION.md)、[路线图](docs/ROADMAP.md)
- [市场对照：主流 Android 输入法能力矩阵](docs/MARKET_COMPARISON.md)
- [2026-11-13 项目审查、市场对照与详细改进建议](docs/REVIEW_2026-11-13.md)
- [2026-11-13 当前工作树复核：不足与改进建议](docs/REVIEW_2026-11-13-FOLLOWUP.md)
- [本轮仓库审查与清理](docs/REPOSITORY_AUDIT.md)

## 构建与检查

JDK17+（已有JDK25验证环境）、Android SDK37、项目Gradle Wrapper。SDK路径保存在忽略的 `local.properties`。

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
# 原件完整时使用严格溯源模式；不自动运行冻结验收
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1 -StrictSources
```

debug产物：`app/build/outputs/apk/debug/app-debug.apk`。完整命令、Python依赖、设备页面和可恢复整理工具见 [开发指南](docs/DEVELOPMENT.md)。

## 隐私与模型

无INTERNET/ACCESS_NETWORK_STATE权限、无账号遥测、关闭系统备份。密码不提供AI上下文且不学习；普通字段允许的本地学习会保存词条/拼音/次数，可关闭与清除。

模型不随APK或仓库分发，经系统文件选择器导入应用私有目录；基础输入无需模型。历史设备AI峰值约1.9GB，关闭后PSS并非所有场景达预算，详见 [进度](docs/PROGRESS.md)。本机 `models/` 不提交，仓库整理时保留。

## 许可

代码采用 [Apache-2.0](LICENSE)。第三方依赖与模型见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)；数据来源和限制见 [legal/DATA_PROVENANCE](legal/DATA_PROVENANCE.md)，Tatoeba作者见 [署名列表](legal/TATOEBA_CONTRIBUTORS.txt)。评测目录内的数据许可证与代码许可分别适用。
