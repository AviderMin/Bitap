# Third-Party Notices

Bitap 代码以 Apache-2.0 发布。以下依赖/数据分别遵循其许可：

| 组件 | 版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| Miuix | 0.9.4 | Apache-2.0 | Miuix 主题与组件 |
| Glasmio | 0.1.2 | Apache-2.0 | Liquid Glass 主题与组件 |
| LiteRT-LM | 0.19.1 | Apache-2.0 | 本地模型运行时 |
| Qwen3-0.6B | - | Apache-2.0 | 可选本地模型（不随 APK 分发） |
| AndroidX / Jetpack Compose | BOM 2026.09.00 | Apache-2.0 | UI 基础 |
| Kotlin / kotlinx.coroutines | 2.4.21 / 1.11.0 | Apache-2.0 | 语言与协程 |

拼音词库由公开中文词频数据与拼音转换工具生成，仅包含词语、拼音与频次，不含用户数据。具体生成脚本与数据来源见 `scripts/build_pinyin_dict.py` 与 `assets/pinyin_dict.tsv` 头部注释。
