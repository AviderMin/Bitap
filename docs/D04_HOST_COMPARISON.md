# D04 宿主机质量对照与阶段一审查补充

日期：2026-11-08（Asia/Shanghai）。基线提交：`9423fde`。

## 结论与完成边界

完成了可复现的 Windows 宿主机 librime 质量 PoC、经 InputEngine 的自研候选导出、统一评分器和聚合快照；没有将 librime 接入生产 IME。**D04 整项尚未完成**：Android/JNI 适配、同机时延/PSS/APK 增量、取消和部分候选消费契约、独立泛化验收仍待验证。

现有证据不支持直接换引擎，也不支持开始按 validation 调参。继续保留自研生产默认；首先解决评测训练源重合，再评估更多方案和 Android 成本。

## 本次新测

数据：v2 dev 1,430 条，引擎可评分 1,384 条，交互排除 46 条；两边同一个文件，SHA-256 `3421ca0837d3b1726532c186810deab759a42fcdf622f4157c6f76216bbe53d6`。未运行 v2 validation，未修改语料、生产算法、词库或学习数据。

宿主：Windows x64；自研运行于项目 Gradle JVM，librime 通过 Python ctypes 调用 MSVC x64 DLL。无学习、无编辑器上下文。两边完整原始输入查询，前 8 项评分，64 项 oracle；完整消费约束一致，partial 候选不先过滤再排名。

| 指标 | 自研 PinyinInputEngine | librime 最小 pinyin_simp 组合 |
|---|---:|---:|
| Top1 | 714/1384，51.59% | 573/1384，41.40% |
| Top1 Wilson 95% CI | [48.96%,54.21%] | [38.83%,44.02%] |
| Top5 | 1001/1384，72.33% | 601/1384，43.42% |
| Top5 Wilson 95% CI | [69.91%,74.62%] | [40.84%,46.05%] |
| 正确候选 oracle64 | 1169/1384 | 611/1384 |
| 完整覆盖64（不要求正确） | 1383/1384 | 1375/1384 |
| rankCost（修正规则） | 3.5809 | 5.5535 |
| 宿主前8查询 p50/p95/p99 | 3.2668/7.5423/9.1524 ms | 0.2368/0.6432/0.7975 ms |

配对首选：两边均正确 477、自研独有 237、librime 独有 96、均错误 574。librime 存在互补结果，但不能据此通过“知道答案后选正确引擎”的 oracle 合并宣称质量提升。

### 分类结果（Top1 / Top5 命中数）

| 类别 | n | 自研 | librime 最小组合 |
|---|---:|---:|---:|
| natural_short | 400 | 228 / 328 | 217 / 217 |
| natural_medium | 450 | 222 / 318 | 167 / 167 |
| natural_long | 300 | 111 / 155 | 67 / 67 |
| homophone | 40 | 40 / 40 | 34 / 39 |
| shorthand | 40 | 31 / 38 | 25 / 31 |
| typo | 40 | 15 / 21 | 0 / 1 |
| mixed_pinyin | 40 | 20 / 38 | 17 / 29 |
| number_punct（可评分） | 34 | 19 / 30 | 13 / 13 |
| proper_noun | 40 | 28 / 33 | 33 / 37 |

这里 Rime 的自然句 Top1 与 Top5 相同，和该 script translator/方案的候选组织有关：前排除了整句也会出现前缀词。此项准确率不能覆盖逐段选词完成整句的实际体验，不能推导“Rime 产品一定更难用”。当前最小方案的有限 derive 也不等于完整邻键错拼模型。

### 时延边界

计时包含清除/设置 Rime 原始输入并取前 8 项；额外逐候选模拟选择、oracle 和部署不计入查询耗时。自研计时来自 InputEngine diagnostics。缓存、JIT、FFI、运行时不同，数据只作宿主粗略预算，**不能据此宣称手机快多少倍、达到 50ms 可见反馈或内存更少**。本次没有新建 APK、安装手机包、测 PSS 或端到端输入延迟。

Rime 首次 setup/initialize/部署/创建会话合计本次 266.35ms，不是 Android 冷启动数据，也不能与已有输入法唤起时延比较。

## 阶段一审查发现

### 1. rankCost 实现与协议不一致，已修正

`PinyinV2EvalTest.CaseResult` 原先对所有 rank≥0 计 rank+1，包括第 6–8 位；协议定义为前五未命中记 9。已改为 `rank in 0..4` 才计分。历史 dev 约 3.50 与本次 3.5809 的差异属于成本口径修正，Top1/Top5 没有因此变化。历史报告不回写伪装成当时结果。

统一评分器新增 5 个契约检查：部分消费不算整句命中、第六名成本为 9、交互分母分离、导出 ID 不匹配拒绝、前8排名不被64项 oracle 池代替。

### 2. v2 与生产 n-gram 的训练来源重合

证据链：

1. `docs/LICENSE_CLEANUP.md` 记录生产字符 n-gram 对全部 89,289 句生成统计，没有句子排除。
2. 同文件明确普通导出与 detailed 导出的 ID 和逐句文本完全一致。
3. `eval/v2/corpus/manifest.json` 记录 v2 自然句来源是这批普通导出，行数 89,289。

因此，当前“整句不在词典 + dev/validation 近重复隔离”的检查，**没有隔离生产统计训练源**。这不意味着有人按答案硬编码，但不能把这套 validation 当作训练源独立的未见数据，也不能据此证明真实泛化。

本次不读取 validation 逐条内容、不删除重合难例、不改词典以改善分数。协议增加该限制。D04 的结果暂为同源探索性系统对照；自研使用 Tatoeba 统计，Rime 使用另一词库，更不能将差值解释成纯引擎优劣。

修复选择：优先采用未进入生产统计的、许可明确的新来源，冻结前按来源/近重复簇切分；另一可行方案是重建训练统计前排除验收簇、冻结新的资产与基线。无论选哪条，都要新建评测版本，保留旧结果。

### 3. D02 仍有设备退出条件未完成

阶段一文档已经诚实记录关闭 AI 后设置页 PSS 超预算、取消生成设备端未验证。本次未复测，继续作为剩余项；不能因为 D00–D03 已提交而将它们标为全部达标。

## 固定依赖与许可

| 项目 | 固定值 |
|---|---|
| librime release | 1.17.0 / 33e7814，官方 Windows MSVC x64 |
| 7z SHA-256 | `7478c7caa4ff6b37de86daba1f7ce4a994a4f5ba24872a820fb2b3a9b01fed15` |
| rime.dll SHA-256 | `86b4c7357d4c6d293ce5589b234d8859ca2ac30923a03bedfa3926eeaf97fb0b` |
| pinyin-simp commit | `0c6861ef7420ee780270ca6d993d18d4101049d0` |
| dictionary SHA-256 | `e140ee947cdc73b4c3e40cbf704145f1af19b2293432273fae269a10e3c5d1e8` |
| experiment schema SHA-256 | `a14049a3c5f89167fc057eaa854eebd9422b429d589831c81d0c8ccff80fd973` |

上游 [librime LICENSE](https://github.com/rime/librime/blob/33e7814/LICENSE) 为 BSD-3-Clause；[pinyin-simp LICENSE](https://github.com/rime/rime-pinyin-simp/blob/0c6861ef7420ee780270ca6d993d18d4101049d0/LICENSE) 为 Apache-2.0，词典源自 Android Pinyin IME，方案作者 Gong Chen。改动与署名见 `eval/d04/README.md`、`bitap_d04.schema.yaml`、`LICENSE.pinyin-simp`。本次未向 APK 引入依赖；预编译包的传递依赖和插件许可证仍需在生产分发前完整审查，不宣称已完成全部商用许可验收。

## 后续完成 D04 所需工作

1. 修复训练/验收来源隔离并冻结新的对照协议；只用 dev 做实现诊断。
2. 进一步对比一个明确版本的更完整 Rime 方案，或构建严格记录读音转换差异的同词典实验；不按当前评测答案修词库。
3. 增加独立 Android 实验变体、NDK/JNI 和串行线程所有权，接到 InputEngine；不默认替换生产引擎。
4. 验证 candidate 的真实消费范围、分段选择、取消、关闭、会话/版本、学习关闭和清除；当前宿主仅输出 full/partial，不能复用为生产 consumption 实现。
5. 同设备固定 release 条件测冷/热唤起、p95/p99、PSS/native、APK 增量与状态恢复。
6. 冻结组合后做一次独立验收，形成最终选型 ADR。当前 ADR 只决定“不立即切换”。

当前 SDK 目录没有 NDK/CMake，这说明 Android 实验环境尚未准备，不代表技术不可行。它们及 native 依赖的准备属于下一批 D04 集成工作。本次不将宿主 DLL 放入 Android 工程充数。

## 复现与验证

复现命令见 `eval/d04/README.md`。聚合快照见 `eval/d04/results/dev-host-2026-11-08.json`；原始候选与 metadata 位于 `build/d04/`。

- baseline 导出专项使用 `--rerun-tasks`，BUILD SUCCESSFUL；随后最终代码运行 `:app:testDebugUnitTest :app:lintDebug`，单测任务实际执行，BUILD SUCCESSFUL（48s）。
- XML 汇总：355 tests / 0 failures / 0 errors / 3 skipped。跳过项为原有 sealed、validation，以及默认不启用的 D04 导出；导出专项已单独启用并通过。
- lint：0 errors / 26 warnings / 1 hint，与阶段一一致；新导出代码初次编译的 nullable 警告已修正。
- Python 评分契约 5/5 通过，固定版本 bootstrap 实际执行成功；全量 Rime dev 1,430 条运行成功。
- v1 语料校验、显式泄漏检查、v2 语料校验和 Python 编译均通过；`git diff --check` 通过。这些检查的通过不推翻上文已确认的生产统计训练源重合。
- 默认 v2 dev 重新运行后 Top1=714/1384、Top5=1001/1384、rankCost=3.58，与统一评分器一致。
- 本次未执行 validation、未新增设备实测、未构建新 APK、未提交 git commit。

## 后续补充（2026-11-08 晚）

### A. 同词典 Rime 宿主对照

为避免把词典差异误读为引擎差异，新增 `docs/D04_SAME_DICTIONARY.md`：用项目 `app/src/main/assets/pinyin_dict.tsv`（65,999 条，SHA-256 `4c979d12…f20d`）生成 Rime `pinyin_simp.dict.yaml`，复用同一受控 `bitap_d04` 方案，在 v2 dev 同语料上重跑。

| 指标 | Bitap PinyinInputEngine | Rime + 项目词表 | 差异解读 |
|---|---:|---:|---|
| Top1 | 714/1384，51.59% | 445/1384，32.15% | 同词表下 Rime 仍低 19.44pp |
| Top5 | 1001/1384，72.33% | 466/1384，33.67% | Rime 自然句每例仅约 1 个完整句候选，Top1==Top5 |
| oracle64 | 1169 | 468 | Rime 候选结构/句子模型不等价 |
| rankCost | 3.5809 | 6.3338 | 前五外记 9 |

同时对比“不同词典”的 Rime（pinyin-simp 词表）Top1 41.40%/Top5 43.42%：换成本项目词表这一整体替换使 Rime Top1 下降 9.25pp；该差值包含词表集合、词频权重与搜索/切分交互的净效应，不能拆成可加的“词典贡献”，也不能解释为纯引擎能力。同词表也不等于同语言模型：Bitap 的字符 n-gram 统计参与解码/排序（默认 `SENTENCE_CORPUS_WEIGHT=0`、`SENTENCE_DICTIONARY_WEIGHT=0`，不是整句后置重排），Rime 词典格式无法承接这些统计；Rime 最小方案对自然输入只产出一个完整句候选，因此本实验只能作为宿主诊断，不能作为 Android 选型或产品结论。

### B. 训练源独立的 v3 验收集

为解决 §阶段一审查发现 2 的训练源重合，新增 `docs/EVALUATION_V3_PROTOCOL.md` 与 `docs/EVALUATION_V3_BASELINE.md`：来源为 irvin/cc0-sentences 的 zh-TW 日常聊天文件（CC0-1.0，`zhconv` 转简体），共 601 条（short 320 / medium 240 / long 41），与 v1/v2 无 exact/近重复，且与生产词库整句无重叠；但完整训练源审计发现 **1/601 条规范化精确训练句重合**，其余在字符 bigram Jaccard≥0.80 规则下未检出（见 `docs/D04_REVIEW_08b5543.md`）。

| 数据集 | 口径 | n | Top1 | Top5 | rankCost | oracle |
|---|---|---:|---:|---:|---:|---:|
| v2 dev（同源探索） | 引擎可评分 | 1,384 | 51.59% | 72.33% | 3.5809 | 84.5% |
| v2 validation（同源探索） | 引擎可评分 | 685 | 52.4% | 72.7% | 3.49（旧口径） | 84.8% |
| **v3 validation（训练源独立）** | 引擎可评分 | 601 | **38.8%** [35.0,42.7] | **59.4%** [55.4,63.3] | **4.61** | **77.0%** |

v3 在新来源上明显低于 v2，提示跨来源泛化不足；但 v3 同时更换来源、地区用词与句长分布，当前实验未隔离训练重合与分布变化，不能把整个差值归因于同源训练。也不能解释为引擎在 v3 上突然变差（PinyinEngine 未改）。v3 只作独立验收，不进入调参；其首轮结果应作为后续 D05–D07 的新来源自然句基线。

### C. D04 仍未完成

以上均为 Windows x64 宿主实验，不是 Android/JNI：未测 PSS、APK 增量、冷/热唤起、真机 p95/p99，也未验证生产消费范围、取消、学习和多 ROM。完整 D04 仍需按 §后续完成 D04 所需工作 继续；ADR 0002 的“暂不切换”不变。
