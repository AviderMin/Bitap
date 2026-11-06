# Bitap

> 轻触，恰到好处。

Bitap 是一个 **小而美、隐私优先、AI 克制而精准** 的 Android 输入法。它把 AI 留在设备里，把选择权留给你。

- **零网络权限**：应用清单中没有 `INTERNET`，输入、候选与 AI 提示都不会离开设备。
- **本地 AI**：支持导入 Qwen3-0.6B（LiteRT-LM，INT4 `.litertlm`，约 328 MiB）在手机本地运行，只在你主动点击 ✦ 时工作。
- **双主题**：Miuix「清透」与 Glasmio「流光」两套完整设计语言，覆盖键盘、引导页与设置页。
- **中文拼音 + 英文 QWERTY**：内置约 3 万条离线词库，按键即时出候选；AI 不参与每一次按键。

## 状态

Bitap 0.1 已完成工程实现：输入法服务、离线拼音候选、AI 改写面板、模型导入、双主题、首页/设置、隐私边界与单测。产品与架构基调见 [产品简报](docs/00-product-brief.md)、[体验规范](docs/01-experience-spec.md)、[架构设计](docs/02-architecture.md)、[AI 设计](docs/03-ai-design.md)、[设计系统](docs/04-design-system.md)、[隐私安全](docs/05-privacy-security.md)、[质量门槛](docs/06-quality-bar.md)、[团队计划](docs/07-team-plan.md) 与 [交付说明](docs/08-delivery.md)。

## 构建

环境：JDK 17+（本项目在 JDK 25 上验证）、Android SDK API 37（37.0）、Gradle Wrapper 9.7.1。

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug      # 可安装 debug APK，约 107 MB（含调试工具与全量 LiteRT 依赖）
.\gradlew.bat :app:assembleRelease    # minify release，约 27.5 MB（未签名）
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

Release APK：`app/build/outputs/apk/release/app-release-unsigned.apk`（demo 签名版可用 `apksigner` 生成）

## 本地模型

商店分发的 APK **不包含模型权重**。请从 [litert-community/Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) 获取 `.litertlm` 文件，然后在 Bitap 首页「本地 AI」中导入。也可以使用：

```powershell
.\scripts\download-model.ps1
adb push .\models\Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /sdcard/Download/
```

在手机上用系统文件选择器把模型导入 Bitap 的私有目录。应用不申请网络权限。

## 隐私边界

- 无 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
- AI 输入/结果仅存在内存，不写日志、不落盘、不接入分析 SDK；
- 备份关闭（`allowBackup=false`），模型位于应用私有目录，可随时删除；
- 没有真机时只能完成编译、lint、单测与静态隐私验收，设备验收矩阵见 [QA 报告](docs/QA_REPORT.md)。

## 许可

代码以 Apache-2.0 发布。Miuix、Glasmio、LiteRT-LM、Qwen3 的使用与署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；模型权重受其各自许可约束，不随仓库分发。
