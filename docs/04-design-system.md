# Bitap 设计系统：App 双主题 × 键盘独立主题

版本：0.2｜状态：冻结｜依赖：[体验规范](01-experience-spec.md)

## 1. 设计立场

Bitap 的视觉不是「皮肤」，而是两层可独立选择的气质：

**App 外观（Miuix / Glasmio）**：
- **Miuix「清透」**：像一张干净的纸。暖白、细描边、克制的蓝，强调可读性与秩序。
- **Glasmio「流光」**：像一块被光照亮的玻璃。半透明卡片、边缘高光、低饱和极光背景。

**键盘主题（纸白 / 墨玉 / 极光）**：
- **纸白 Paper**：白键、深色文字、清晰边界，适合长时间输入。
- **墨玉 Ink**：温润纸感与青绿色强调，安静但有个性。
- **极光 Aurora**：冷色渐变、半透明键帽，强调触感与层次。

App 主题只影响首页/设置；键盘主题只影响 IME，两者互不串联。

## 2. 参考与依赖

- Miuix：`top.yukonga.miuix.kmp:miuix-ui:0.9.4`，使用 `MiuixTheme`、`MiuixButton`、`MiuixSwitch`、`MiuixCard` 等。
- Glasmio：`io.github.neoruaa.glasmio:glasmio-scaffold:0.1.2`，使用 `GlasmioTheme`、`GlasmioBackdropHost`、`GlasmioButton`、`GlasmioSwitch`、`GlassSurface`。
- 两库均为 Apache-2.0；不使用其示例中的 Apple 字体/图标资源。Glasmio 图标使用内置回退。

## 3. Token

| Token | Miuix Light | Miuix Dark | Glasmio Light | Glasmio Dark |
| --- | --- | --- | --- | --- |
| background | `#F5F5F7` | `#111114` | 极光渐变 | 深空渐变 |
| key | `#FFFFFF` | `#2A2A2E` | 白 38% | 白 12% |
| keyPressed | `#E8E8ED` | `#3A3A3F` | 白 55% | 白 20% |
| functionKey | `#E9E9EE` | `#1F1F23` | 白 22% | 白 8% |
| accent | `#3482FF` | `#5B9BFF` | `#0A84FF` | `#64D2FF` |
| textPrimary | `#111114` | `#F5F5F7` | `#0B0B0F` | `#F5F5F7` |
| danger | `#FF3B30` | `#FF453A` | `#FF453A` | `#FF6961` |

圆角：键帽 10 dp（Miuix）/ 12 dp + 连续曲率（Glasmio）；键盘顶部 dock 24 dp；卡片 20–28 dp；胶囊 999 dp。间距基数 4 dp；键盘边距 6–8 dp。

键帽固定 54 dp，每行上下间隙固定 4 dp；用户滑杆只调整键盘底部留白并抬高整块键盘，所有触控目标最小 48 dp。

键盘主题 token 见 [KeyboardTheme.kt](../app/src/main/java/com/bitap/keyboard/ui/theme/KeyboardTheme.kt)：纸白/墨玉提供实色明暗 palette，极光提供半透明键帽 + 冷色渐变背景。键盘整体高度包含 `WindowInsets.navigationBars` 底部安全区，避免系统「收起 / 选择键盘」快捷栏与按键重叠。IME 的 `SoftInputWindow` 与 DecorView 必须设透明背景，否则深色模式下顶部圆角外侧会露出黑块。

## 4. 组件

### 键盘键

- 普通键：44–52 dp 高，水平间距 4 dp；按下缩放 0.96、亮度 -6%。
- 功能键：弱化底色；图标键使用 20–22 dp 居中图标。
- 候选条：高 42 dp，左对齐候选，右侧 ✦ AI 入口；最多显示 5 个候选，可横向滚动。

### AI 词库候选

- 候选条右侧显示 `✦ AI` 按钮；未就绪时降透明度。
- AI 候选与本地候选同条展示，前面加一个小号 `AI` 标签。
- AI 候选 chip 高度 48 dp，点选后才上屏；不使用独立改写面板。

### 首页

- 品牌区：64 dp 图标 + 产品名 + 一句话。
- 三步清单卡片；当前主题下使用对应库的卡片组件。
- 主题选择：两张并排预览卡，选中描边 accent。
- 隐私声明：一行锁图标 +「无网络权限 · 数据不出设备」。

## 5. 动效

- 键盘出现：120 ms 淡入 + 8 dp 上移；不弹跳。
- 按键：按下 90 ms、回弹 120 ms，spring(damping 0.85, stiffness 700)。
- AI 候选：请求时按钮变省略态；候选追加不改变键位布局。
- 主题切换：180 ms 颜色交叉淡化；布局不移动。

## 6. 可访问性

- 所有交互元素 ≥ 48 dp；键帽对比度 ≥ 4.5:1。
- 图标键必须有 `contentDescription`；装饰性背景 `clearAndSetSemantics {}`。
- 支持字体缩放 1.3×；不使用纯颜色传达状态（AI 禁用同时降透明度 + 可点击提示）。
- Glasmio 提供「降低透明度」回退：不支持的设备使用不透明石墨底。

## 7. 验收清单

- [ ] 双主题在浅色/深色下键盘布局一致、无裁切。
- [ ] Miuix 主题实际使用 `MiuixTheme`；Glasmio 主题实际使用 `GlasmioTheme` + `GlasmioBackdropHost`。
- [ ] App 双主题与键盘三主题均可独立选择，且键盘可完成完整输入与 AI 词库候选流程。
- [ ] 主题切换在设置页与键盘内即时生效（下一次弹出键盘读取最新设置）。
- [ ] 未使用 Apple 专有字体/图标；回退图标可用。
