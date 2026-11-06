# ADR-0001：Bitap 0.1 技术基座

- 状态：Accepted
- 日期：2026-11-06
- 决策者：Lead（待 team review）

## 背景

仓库为空，需要从零建立 Android 输入法。约束：双主题依赖 Miuix/Glasmio、本地 Qwen3-0.6B、隐私优先、构建环境只有 Android SDK 36/37 与 JDK 25，无 NDK/CMake、无真机。

## 决策

1. **单模块 Android + Kotlin + Jetpack Compose**：降低构建复杂度；IME 与首页共享主题与设置。
2. **Gradle 9.7.1 + AGP 9.4.1 + Kotlin 2.4.21**：匹配当前 SDK 与主题库的 Kotlin 2.4 元数据；暂时关闭 AGP 新 DSL（`android.newDsl=false`）以兼容 KGP。后续迁移内置 Kotlin 时另立 ADR。
3. **minSdk 33**：Glasmio 0.1.2 的最低要求；0.1 放弃 Android 12 及以下。
4. **LiteRT-LM 0.19.1 + Qwen3-0.6B INT4**：预编译 AAR 无需 NDK；比自建 llama.cpp 更适合当前环境；模型外置导入。
5. **拼音用本地词库，不用 AI**：保证输入延迟与离线可用；AI 只做显式改写。
6. **主题库直接依赖**：Miuix 0.9.4、Glasmio 0.1.2，不复制其源码或 Apple 资产。
7. **无网络权限**：把隐私从「承诺」变成构建产物属性。

## 后果

- 优点：构建快、依赖可控、主题原汁原味、隐私边界硬。
- 代价：APK 需处理两套主题库体积；AGP 新 DSL 暂时关闭；Glasmio 玻璃效果在低端设备需回退；无真机时只能做编译与单测验收。
- 后续：GPU 后端、模型下载器、拼音词库扩展、内置 Kotlin 迁移分别写 ADR。
