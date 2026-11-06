# Bitap 0.1 交付说明

日期：2026-11-06｜负责人：Lead｜状态：工程交付（无真机）

## 1. 交付物

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| Demo APK（可安装） | `dist/bitap-0.1.0-demo.apk` | minify release，debug key 签名，约 27.5 MB，arm64-v8a，Android 13+ |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | 约 107 MB，便于调试 |
| APK SHA-256 | `dist/SHA256SUMS.txt` | 校验 demo APK |
| 开发文档 | `docs/00–07` | 产品、体验、架构、AI、设计系统、隐私、质量、团队 |
| 词库 | `app/src/main/assets/pinyin_dict.tsv` | 29,999 条中文词/字，离线拼音候选 |
| 本地模型脚本 | `scripts/download-model.ps1` | 外部下载 Qwen3 0.6B dynamic INT4（328 MiB），不随 APK |

## 2. 已实现

- **输入法**：`BitapImeService` + Compose 键盘；英文 QWERTY、中文全拼候选、数字/符号层、Shift、中英切换、退格/回车/空格；候选选择与拼音 composing。
- **本地 AI**：Qwen3 0.6B 的 `.litertlm` 导入（SAF）、私有目录存储、LiteRT-LM CPU 推理、流式结果、20 s 超时、结果清理；AI 必须手动触发并手动插入。
- **双主题**：Miuix `MiuixTheme` 与 Glasmio `GlasmioTheme` + `GlasmioBackdropHost`；四套浅/深 palette；键盘与首页/设置一致。
- **首页/设置**：三步启用清单、主题即时预览、深色模式、AI/触觉/声音开关、模型管理、隐私承诺。
- **隐私**：Manifest 无 `INTERNET`；无分析 SDK；无输入日志；`allowBackup=false`；模型可删除。

## 3. 验证结果

- `:app:testDebugUnitTest`：77 tests 全部通过（51 基线 + 26 QA，见 [QA 报告](QA_REPORT.md)）。
- `:app:lintDebug`：0 errors（14 warnings，均为版本提示/资源建议类）。
- `:app:assembleDebug`：通过。
- `:app:assembleRelease`：通过；demo APK 使用 debug key 签名。
- `aapt dump permissions`：无 `android.permission.INTERNET`。

## 4. 未验证（无设备）

当前机器没有连接的 Android 设备/模拟器，以下项目已静态检查但未真机执行：

- IME 在系统设置中启用、切换、弹出与 ComposeView 生命周期；
- 键盘实际高度、横竖屏/字体缩放、触觉反馈；
- Glasmio 玻璃折射/极光在 IME 窗口中的显示与性能；
- 真实 `.litertlm` 模型加载、首 token 延迟与改写质量（需导入 328 MiB 模型）；
- 系统输入法设置/选择器跳转流程。

建议在 Android 13+ 真机上按 [质量门槛](06-quality-bar.md) 的 A1–A12 验收矩阵执行；模型推理用 [scripts/download-model.ps1](../scripts/download-model.ps1) 获取后通过系统文件选择器导入。

## 5. 已知边界

- 0.1 不包含云 AI、语音、滑行输入、联系人词库、输入学习。
- 模型导入为不确定进度（contract 无字节进度）。
- release demo 使用 Android debug keystore，不可直接上架；正式发布需配置 release keystore 并补充设备测试。
- 仅打包 arm64-v8a，未包含 x86_64/ChromeOS。
