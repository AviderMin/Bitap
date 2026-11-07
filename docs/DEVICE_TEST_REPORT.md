# Bitap 0.1 真机 Via 浏览器测试报告

日期：2026-11-06 23:26–23:40（Asia/Shanghai）
设备：`22021211RC`（Redmi K40S / munch），Android 16 / API 36
系统输入法：Bitap `com.bitap.keyboard/.ime.BitapImeService`
浏览器：Via `mark.via`
APK：`dist/bitap-0.1.0-demo.apk`（release/minify，debug key 签名）

## 1. 首轮 Bug 与修复

首轮在 Via 地址栏弹出键盘时，Bitap IME 进程崩溃：

```text
java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from
android.widget.LinearLayout{... #android:id/parentPanel}
    at androidx.compose.ui.platform.AbstractComposeView.onAttachedToWindow
    at android.inputmethodservice.InputMethodService.setInputView
```

根因：`InputMethodService` 的 `SoftInputWindow` 是 Dialog，Compose 在窗口 DecorView 上查找 lifecycle owners，只在 ComposeView 上挂载不够。

修复（提交 `b4a14c9`）：`BitapImeService` 在 `onCreate`、`onCreateInputView`、`onWindowShown` 将 `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner` 挂到 `window.window.decorView`。修复后重新构建、安装并复测通过。

## 2. 实测结果

| # | 场景 | 操作 | 结果 |
| --- | --- | --- | --- |
| D1 | 启用/选择 Bitap | `adb shell ime enable/set com.bitap.keyboard/.ime.BitapImeService` | PASS，Via 键盘实际为 Bitap |
| D2 | 中文拼音候选 | 在 Via 地址栏点按 `n` `i` | PASS，候选条显示「你 泥 尼 拟 逆 暱 腻 妮」，点选「你」后 EditText = 你 |
| D3 | 多字拼音 | 点按 `n` `i` `h` `a` `o` | PASS，候选条显示「你好」，点选后 EditText = 你好 |
| D4 | 英文输入 | 切换「英」，点按 `h` `e` `l` `l` `o` | PASS，EditText = hello |
| D5 | 数字层 | 点 `123` | PASS，显示 1–0 与 @#$%&*-+() |
| D6 | 符号层 | 点 `#+=` | PASS，显示 []{}#%^*+= / _\|~<>€£¥· 等 |
| D7 | Miuix 主题 | 首页选择 Miuix；浅/深切换 | PASS，首页与键盘渲染正常 |
| D8 | Glasmio 主题 | 首页选择 Glasmio；浅/深切换 | PASS，发光渐变背景、半透明键帽正常 |
| D9 | AI 未安装模型 | 未放模型时点 ✦ | PASS，面板显示「本地模型未就绪，请先在 Bitap 中导入模型」，插入禁用 |
| D10 | AI 已安装模型 | 将 Qwen3 0.6B dynamic INT4（343,490,560 bytes）放入私有模型目录，点 ✦ → 润色 | PASS，面板显示「本地思考中…」，约 10–15 s 返回「hello」，点「插入」后 EditText 增加 hello（hellohello → hellohellohello） |
| D11 | 隐私 | aapt dump permissions | PASS，无 INTERNET / ACCESS_NETWORK_STATE |
| D12 | 主题持久化 | 切换主题/深色后重启 Activity | PASS，SharedPreferences 保存 `theme_family` / `dark_mode` |
| D13 | 键盘整体高度 | 首页「键盘整体高度」从 100% 调到 120%，再打开 Via 键盘 | PASS，Settings 保存 `keyboard_height=1.2`，按键保持固定 54dp，行间距/键盘上沿增高且无裁切 |
| D14 | 系统快捷键栏 | 125% 高度下检查底部「收起 / 选择键盘」系统栏 | PASS，键盘通过 `WindowInsets.navigationBars` 为系统栏预留底部安全区，底部按键不再与系统栏重叠 |
| D15 | 独立 App | 打开系统应用抽屉 | PASS，可见独立 **Bitap** 图标；点击可进入首页/设置（不是只有 IME 服务） |
| D16 | App/键盘主题分离 | App 选 Glasmio，键盘切到纸白/墨玉 | PASS，App 保持 Glasmio 光效，键盘按自己的纸白/墨玉主题渲染，互不串联 |
| D17 | AI 词库入口 | 输入 `nihao`，点候选条 `✦ AI` | PARTIAL：按钮进入加载省略态，但本次未返回候选；请求链路已接通，需继续调试 prompt/解析 |
| D18 | 深色模式圆角透明 | 深色模式下检查键盘左上/右上圆角外侧 | PASS，`InputMethodService` 窗口背景与 DecorView 设为透明后，圆角外侧显示后方页面而非黑块 |
| D19 | 双拇指键位 | 检查底部功能行权重与左右手分区 | PASS，左侧 `123`/`中/英`/`，`，中央加宽 `空格`，右侧 `。`/`↵`；Shift 左下、退格右上 |

## 3. 截图证据

- [Miuix 浅色：nihao → 你好候选](evidence/via-bitap-miuix-candidates.png)
- [Glasmio 深色：Via 中的 Bitap 键盘](evidence/via-bitap-glasmio-dark-keyboard.png)
- [Miuix 深色：首页与主题选择](evidence/bitap-home-miuix-dark.png)
- [AI 面板：模型就绪、四动作](evidence/bitap-ai-panel.png)
- [AI 润色结果：hello + 插入按钮](evidence/bitap-ai-result.png)
- [键盘高度设置：100% → 125%](evidence/bitap-height-setting.png)
- [125% 高度下的 Via Bitap 键盘](evidence/bitap-keyboard-height-125.png)
- [为底部系统快捷键栏预留安全区后的键盘](evidence/bitap-keyboard-navsafe-125.png)
- [固定键帽 54dp、整体高度 120% 的键盘](evidence/bitap-keyboard-fixed-keys-120.png)
- [深色模式圆角透明修复](evidence/bitap-dark-transparent-corners.png)
- [双拇指功能行布局](evidence/bitap-thumb-layout.png)
- [独立 Bitap App：系统应用抽屉](evidence/bitap-app-drawer.png)
- [App/键盘主题分离设置](evidence/bitap-keyboard-theme-settings.png)
- [独立的 Ink 键盘主题](evidence/bitap-ink-keyboard.png)

## 4. 说明

- 模型通过 ADB 放入应用私有目录以节省时间，**未完成用户从文件选择器选择文件的端到端导入测试**；模型文件选择器已成功打开，导入代码路径由 QA 静态覆盖。
- AI 首轮加载 native 库时出现过一条 `NativeLibraryLoader.nativeCheckLoaded` 日志，随后 `liblitertlm_jni.so` 正常加载并完成推理；该日志未导致失败。
- 设备上的 Bitap 设置：Miuix/Glasmio 与浅/深均实测过；截图使用 Miuix 深色与 Glasmio 深色作为代表。
