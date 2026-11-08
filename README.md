# Bitap

离线、隐私优先的 Android 中文输入法。当前版本0.1.0，支持Android13+、arm64；仍处于输入质量与交互打磨阶段，尚无达到成熟商业输入法体验的证据。

已实现65,999词条的本地拼音候选、简拼/轻量纠错、英文/数字/符号、候选学习、设置与独立键盘主题。可选 `.litertlm` 模型只在点击 `✦ AI` 时补充拼音候选，不参与每次按键；应用无网络权限。改写/润色没有生产入口。

最新进度：D00–D03基础工程完成；D04完成宿主与同词表实验，Android PoC未完成；D05完成跨来源dev、候选展示与词内统计权重实验及小样本设备验证。当前重点是候选选择成本、长输入预算、召回/排序、纠错和连续混排。最近一批工作树改动已完成P0候选面板按可见行分页、页脚固定、分段候选标识/入口，以及>64字符长输入的前缀分段查询（JVM测试 + 22021211RC/Android16单机竖屏验证；字体放大、横屏、多ROM未测）。长输入边界诊断进一步表明：64附近音节/词回退未优于固定64，短词边界分段可提高候选召回但操作成本上升，暂不切换生产默认；partial排序实验固定候选池/切点并冻结入选候选后，Top64命中不变；LONGEST_FIRST变差，SHORTEST_FIRST仅小幅提高前缀Top8、整句完成仍为1/33，未带来可用净收益，默认仍为SCORE；详见[进度](docs/PROGRESS.md)第8–9节。

## 文档

- [文档索引](docs/README.md)
- [项目需求](docs/PRODUCT_REQUIREMENTS.md)
- [当前进度与已知问题](docs/PROGRESS.md)
- [架构](docs/ARCHITECTURE.md)、[开发指南](docs/DEVELOPMENT.md)、[评测规范](docs/EVALUATION.md)、[路线图](docs/ROADMAP.md)
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
