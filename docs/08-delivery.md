# Bitap 0.1 交付说明

日期：2026-11-06｜负责人：Lead｜状态：工程交付 + 真机 Via 冒烟测试通过

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
- **键盘高度**：85%–135% 滑杆（默认 100%，5% 步进），键帽 48–78 dp 自适应；键盘顶部改为圆角 dock 与轻量提手线。
- **首页/设置**：三步启用清单、主题即时预览、深色模式、AI/触觉/声音开关、模型管理、隐私承诺。
- **隐私**：Manifest 无 `INTERNET`；无分析 SDK；无输入日志；`allowBackup=false`；模型可删除。

## 3. 验证结果

- `:app:testDebugUnitTest`：77 tests 全部通过（51 基线 + 26 QA，见 [QA 报告](QA_REPORT.md)）。
- `:app:lintDebug`：0 errors（14 warnings，均为版本提示/资源建议类）。
- `:app:assembleDebug`：通过。
- `:app:assembleRelease`：通过；demo APK 使用 debug key 签名。
- `aapt dump permissions`：无 `android.permission.INTERNET`。

## 4. 真机 Via 冒烟测试

已于 `22021211RC`（Android 16 / API 36）完成 Via 浏览器实测，详见 [DEVICE_TEST_REPORT.md](DEVICE_TEST_REPORT.md)：

- 启用/选择 Bitap IME，Via 地址栏实际弹出 Bitap 键盘；
- 中文拼音：`ni` → 候选「你 泥 尼 拟 逆 暱 腻 妮」，点选后上屏「你」；`nihao` → 「你好」并上屏；
- 英文点按输入 `hello`；数字/符号层切换正常；
- Miuix / Glasmio 浅色与深色主题均实际渲染；
- AI 未装模型时正确显示「本地模型未就绪」；放入 Qwen3 0.6B dynamic INT4 后，「润色」在约 10–15 s 返回「hello」，手动「插入」后成功写回 Via；
- 隐私：aapt 无 `INTERNET` / `ACCESS_NETWORK_STATE`。

首次弹键盘曾触发 `ViewTreeLifecycleOwner not found` 崩溃，已通过 IME `window.decorView` owner 挂载修复（提交 `b4a14c9`）。

仍未覆盖：竖屏/横屏旋转、字体缩放、TalkBack、真实系统输入法设置页跳转；模型导入通过 ADB 预置到私有目录，未完成文件选择器的端到端导入。

建议继续在 Android 13+ 真机上按 [质量门槛](06-quality-bar.md) 的 A1–A12 验收矩阵补测。

## 5. 已知边界

- 0.1 不包含云 AI、语音、滑行输入、联系人词库、输入学习。
- 模型导入为不确定进度（contract 无字节进度）。
- release demo 使用 Android debug keystore，不可直接上架；正式发布需配置 release keystore 并补充设备测试。
- 仅打包 arm64-v8a，未包含 x86_64/ChromeOS。
