# Bitap 设计系统：Miuix × Glasmio 双主题

版本：0.1｜状态：冻结｜依赖：[体验规范](01-experience-spec.md)

## 1. 设计立场

Bitap 的视觉不是「皮肤」，而是两种气质：

- **Miuix「清透」**：像一张干净的纸。暖白、细描边、克制的蓝，强调可读性与秩序。
- **Glasmio「流光」**：像一块被光照亮的玻璃。半透明键帽、边缘高光、低饱和极光背景，强调触感与层次。

两者共享同一套布局、动效节奏与信息层级。切换主题只换 token 与材质，不改变操作位置。

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

圆角：键帽 10 dp（Miuix）/ 12 dp + 连续曲率（Glasmio）；卡片 20–28 dp；胶囊 999 dp。间距基数 4 dp；键盘边距 6–8 dp。

## 4. 组件

### 键盘键

- 普通键：44–52 dp 高，水平间距 4 dp；按下缩放 0.96、亮度 -6%。
- 功能键：弱化底色；图标键使用 20–22 dp 居中图标。
- 候选条：高 42 dp，左对齐候选，右侧 ✦ AI 入口；最多显示 5 个候选，可横向滚动。

### AI 面板

- 从候选条下方展开，高度自适应，最大 180 dp。
- 顶部细进度条（2 dp），不使用转圈大 Loading。
- 动作 chip：高度 32 dp，横向排列，可换行。
- 结果文本：15 sp / 22 sp 行高，最多 6 行，超出滚动。
- 插入按钮使用 accent 实色；放弃为文字按钮。

### 首页

- 品牌区：64 dp 图标 + 产品名 + 一句话。
- 三步清单卡片；当前主题下使用对应库的卡片组件。
- 主题选择：两张并排预览卡，选中描边 accent。
- 隐私声明：一行锁图标 +「无网络权限 · 数据不出设备」。

## 5. 动效

- 键盘出现：120 ms 淡入 + 8 dp 上移；不弹跳。
- 按键：按下 90 ms、回弹 120 ms，spring(damping 0.85, stiffness 700)。
- AI 面板：220 ms 高度动画 + 淡入；结果流式追加不闪烁。
- 主题切换：180 ms 颜色交叉淡化；布局不移动。

## 6. 可访问性

- 所有交互元素 ≥ 48 dp；键帽对比度 ≥ 4.5:1。
- 图标键必须有 `contentDescription`；装饰性背景 `clearAndSetSemantics {}`。
- 支持字体缩放 1.3×；不使用纯颜色传达状态（AI 禁用同时降透明度 + 可点击提示）。
- Glasmio 提供「降低透明度」回退：不支持的设备使用不透明石墨底。

## 7. 验收清单

- [ ] 双主题在浅色/深色下键盘布局一致、无裁切。
- [ ] Miuix 主题实际使用 `MiuixTheme`；Glasmio 主题实际使用 `GlasmioTheme` + `GlasmioBackdropHost`。
- [ ] 两套主题均可完成完整输入与 AI 改写流程。
- [ ] 主题切换在设置页与键盘内即时生效（下一次弹出键盘读取最新设置）。
- [ ] 未使用 Apple 专有字体/图标；回退图标可用。
