# 评测协议 v2（D01）

- 文档版本：v2.0
- 日期：2026-11-08
- 适用阶段：阶段一 D01；后续 D04–D07 的引擎对比、召回/排序改造和回归均按本协议执行
- 上位依据：docs/PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md §9（评测体系重建）与 §10（D01）
- 实现文件：
  - 生成器：eval/v2/corpus/build_v2_corpus.py
  - 校验器：eval/v2/check_v2_corpus.py
  - 数据：eval/v2/corpus/pinyin_cases_v2_dev.tsv、eval/v2/corpus/pinyin_cases_v2_validation.tsv
  - 清单与许可：eval/v2/corpus/manifest.json、eval/v2/corpus/ATTRIBUTION.md
  - JUnit 评测器：app/src/test/java/com/bitap/keyboard/core/PinyinV2EvalTest.kt
  - 首轮基线：docs/EVALUATION_V2_BASELINE.md

## 1. 目的与数据集角色

本协议用来回答三个问题：**新数据上引擎首选/前五是否正确、正确路径是否存在、每类输入的错误结构是什么**。它不把测试通过、功能存在或旧小样本成绩等同于产品可用。

数据集角色与治理：

| 数据集 | 位置 | 允许用途 | 禁止用途 |
|---|---|---|---|
| v1 dev | eval/pinyin_cases_dev.tsv | 历史可比、旧引擎调试 | 作为新验收证据 |
| v1 holdout | eval/pinyin_cases_holdout.tsv | 历史可比 | 作为新验收证据 |
| v1 sealed | eval/pinyin_cases_sealed.tsv | **降级为回归集**：仅检查“旧结果不明显回退” | 继续宣称“未见数据/封存验收” |
| **v2 dev** | eval/v2/corpus/pinyin_cases_v2_dev.tsv | 引擎开发、调参、逐条诊断 | 作为发布验收结论 |
| **v2 validation** | eval/v2/corpus/pinyin_cases_v2_validation.tsv | **仅验收**；只允许看聚合指标 | 调参、选词、按错误改规则；逐条阅读 |

规则：

1. v2 validation 在发布验收前**不得**进入日常调参回路。开发人员只能看 PinyinV2EvalTest 的 validation 聚合输出；不得打开 TSV 逐条对照。
2. 一旦有人按 validation 的逐条结果修改词库、规则或特征，该 validation 即视为已消费，只能降级为回归集；必须用同一生成器重建一个新版本（新 hash）作为下一次验收集。
3. 旧 sealed 已公开入库且报告可见，不再具备盲测意义。后续只能作为**回归集**报告，不能写成“新的封存验收”。
4. 评测语料只作评测。**不得**把 v2 语料、验收句、固定答案或句子专用奖励写进 app/src/main/assets/pinyin_dict.tsv 或任何生产算法；生成器与校验器均不调用 PinyinEngine。

## 2. 数据集构成

### 2.1 规模与类别

v2 语料共 **2,140 条**：dev 1,430 条、validation 710 条。每条只有一个 split，按近重复簇整簇归属。

| 类别 | 含义 | dev | validation |
|---|---|---:|---:|
| natural_short | 自然短句（2–8 字） | 400 | 200 |
| natural_medium | 自然中句（9–16 字） | 450 | 220 |
| natural_long | 自然长句（17–24 字，拼音长度 ≤60） | 300 | 150 |
| homophone | 同音词歧义（同一拼音对应 2–3 个可接受词） | 40 | 20 |
| shorthand | 简拼（全声母，含歧义组） | 40 | 20 |
| typo | 错拼（漏字母/多字母/相邻交换/邻键） | 40 | 20 |
| mixed_pinyin | 混拼（完整音节 + 末音节声母） | 40 | 20 |
| number_punct | 数字与标点（中文数词、日期、金额、撇号/空格/尾标点） | 40 | 20 |
| proper_noun | 专有名词（地名、人名、公司名） | 40 | 20 |
| mixed_en | 中英混输（中文前缀 + 英文原文提交；交互项） | 40 | 20 |
| **合计** |  | **1,430** | **710** |

自然句按 Tatoeba 原句长度分桶；专项类别按模板/少量人工种子构造。mixed_en 以及 number_punct 中含阿拉伯数字的用例属于**交互项**：它们计入“全量”视图，但**不进入引擎准确率分母**（见 §5 与 §5.2），必须单独作为端到端失败项报告。其余类别为引擎可评分用例。

### 2.2 文件格式

TSV，6 列，UTF-8，LF：

    id <TAB> category <TAB> input <TAB> expected <TAB> note <TAB> source

- id：稳定、唯一，v2d-… / v2v-… 前缀区分 split。
- input：实际输入到引擎的拼音串；小写英文字母，允许空格、撇号、数字（数字仅用于交互项）。
- expected：可接受答案，多个答案用竖线分隔；匹配时任一命中即算命中。
- note：设计意图或构造说明；typo 行固定包含“正确拼音 <pinyin>”，供校验器复核。
- source：tatoeba:<sentence_id> 或 template:<category>:<key> / manual:<category>:<key>。

### 2.3 数据来源与许可

| 资源 | 用途 | 许可 |
|---|---|---|
| Tatoeba Chinese (cmn) sentences export，.tmp/tatoeba/cmn_sentences.tsv（89,289 句，SHA-256 a11463da289d918050e441a003848a795a97dcc47ffd1b9ed5345d1d70e0c84f） | natural_* 句子来源 | CC BY 2.0 FR；署名见 eval/v2/corpus/ATTRIBUTION.md 与 docs/TATOEBA_CONTRIBUTORS.txt |
| jieba 词典（MIT） | homophone/shorthand/typo/mixed_pinyin 的候选词种子 | MIT |
| pypinyin 0.55.0（MIT） | 所有行拼音生成 | MIT |

Tatoeba 署名文本（与 eval/v2/corpus/ATTRIBUTION.md 一致）：

> Contains Chinese sentence data from Tatoeba (https://tatoeba.org), licensed under Creative Commons Attribution 2.0 France (CC BY 2.0 FR, https://creativecommons.org/licenses/by/2.0/fr/). Authors: see docs/TATOEBA_CONTRIBUTORS.txt (976 contributors, generated mechanically from the detailed sentences export). Changes: a subset of sentences was filtered and punctuation-normalized for near-duplicate comparison; original sentences are redistributed verbatim as evaluation expectations only and are never added to the production dictionary.

边界：CC BY 2.0 FR 不自动覆盖其他资源；Tatoeba 可能含外部导入句，若发现额外许可要求应替换该句。评测语料不随 APK 分发，只保留在仓库评测目录。

## 3. 构造与切分流程

生成命令（可重复；同输入同 hash）：

    python eval/v2/corpus/build_v2_corpus.py
    python eval/v2/check_v2_corpus.py

### 3.1 自然句筛选

1. 只取 Tatoeba cmn 行；剔除含拉丁字母、数字、Emoji、引号/括号等非中文标点的句子。
2. **简体代理判定**：全部汉字为 CJK 统一表意文字且可由 GB2312 编码（排除常见繁体专用字），标点仅允许中文标点集合。
3. 长度：按汉字数分 short 2–8、medium 9–16、long 17–24；必须只有一个句末终止符（。！？…）且位于句尾。
4. 精确去重（按汉字序列）；同一文本保留最小 sentence id。
5. **词典整句泄漏排除**：汉字序列等于 pinyin_dict.tsv 任一完整词条的句子剔除（本轮 288 条）。
6. **v1 评测集重叠排除**：与 eval/pinyin_cases_{dev,holdout,sealed}.tsv 的任一 expected 完全相同（汉字序列）的句子剔除（本轮 8 条）。
7. **拼音长度 ≤60**：PinyinEngine 的 MAX_SEARCH_INPUT_LENGTH=64 按输入字符数硬截断；把 17–24 字自然长句限制在拼音 ≤60，使长句指标衡量解码/排序而不是硬上限。超长输入的 UX 由事件序列集与设备任务另行覆盖，不在本指标分母内。

本轮筛选统计：89,289 句 → 通过筛选 43,359 句 → 按桶稳定哈希抽样形成候选池 9,873 句（short 4,000、medium 4,000、long 1,873）。见 manifest.json 的 split.natural_pool 与 filters.rejected_counts。

### 3.2 近重复聚类与切分

- 特征：汉字 2-gram 集合。
- 相似度：Jaccard 相似度阈值 **0.75**；同时要求两句子长度比 ≥0.6。
- 近邻召回：MinHash 64 个置换、16 bands × 4 rows 的 LSH（等价于以字符 n-gram Jaccard 为核心的 SimHash/LSH 思路），候选对再精确计算 Jaccard。
- 合并：Union-Find 得到近重复簇；**整簇只归入一个 split**，每簇最多取 3 条进入语料（控制同簇重复过多）。
- dev/validation 按桶分别从簇列表中按稳定哈希顺序取满目标数；同一簇不会跨 split。
- 独立校验：check_v2_corpus.py 用同一 Jaccard 阈值检查 dev × validation 的所有自然句候选对；本轮 cross_pairs_above_threshold = 0。

本轮候选池共 9,851 个簇，其中 20 个多成员簇（20 个簇含 2–3 条近重复）。因此 v2 自然句的 split 由“来源（全部 Tatoeba）+ 近重复簇”共同决定，而不是随机切行。

### 3.3 专项类别

- homophone / shorthand / typo / mixed_pinyin：从 jieba 高频词（freq ≥500、2–4 字、排除人名/地名/专名标签）派生，经 pypinyin 生成拼音；同拼音词组、同声母词组自动构造；每个 input 全局唯一。
- number_punct：中文数词/日期/金额/时间模板 + 撇号/空格/尾标点用例 + 阿拉伯数字交互项。
- proper_noun：人工地名/人名/公司名清单（87 条，取 60 条）。
- mixed_en：人工“中文前缀 + 英文尾”清单（65 条，取 60 条），输入为前缀拼音 + 英文小写，expected 保留英文大小写。
- 专项类别之间做输入唯一性检查；同类别内 expected 的字符 2-gram Jaccard ≥0.85 视为近重复并跳过。专项表只用于评测，不进入生产词库。

### 3.4 可重复性

生成器不使用当前时间、随机数或 Python 内置随机哈希；所有抽样/排序使用 SHA-256 稳定键。输出固定 LF/UTF-8。manifest.json 记录两个数据文件与源文件的 SHA-256；任何语料变更必须提升 generator_version 并更新 baseline 文档中的 hash。

## 4. 评测执行

### 4.1 JUnit 评测器

PinyinV2EvalTest.kt 从仓库根目录读取 v2 数据与 pinyin_dict.tsv，对每条用例调用 PinyinEngine.search(input, limit=8)；先对前 24 条预热，再按用例顺序测量。

默认只评测 **dev**，输出 build/pinyin-v2-eval-dev.txt。validation 仅在环境变量 PINYIN_V2_VALIDATION=1 时运行，输出 build/pinyin-v2-eval-validation.txt，且报告只含聚合与分类汇总，不含 case id、输入、期望或候选明细。

    # dev（日常回归，默认）
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest" --rerun-tasks

    # validation（仅发布验收；由 Lead 在冻结候选上执行）
    $env:PINYIN_V2_VALIDATION='1'
    .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinV2EvalTest" --rerun-tasks

### 4.2 与旧评测器的关系

- PinyinQualityEvalTest（v1 dev/holdout）和 PinyinSealedEvalTest（旧 sealed）保留用于历史比较与回归；不得用它们替代 v2 validation 宣称泛化能力。
- 旧 avgTaps 改名为 rankCost（定义见 §5）；历史文档中的 avgTaps 数字不重算，但新报告只使用 rankCost。
- 任何“质量提升”结论必须同时给出 v2 dev 与 v2 validation 的同类对比，并说明旧 sealed 回归是否回退。

## 5. 指标定义

所有指标默认在**引擎可评分用例**上计算；交互项单列。匹配前对候选与 expected 做相同归一化：去空白/标点，只保留汉字、字母与数字。

| 指标 | 定义 |
|---|---|
| **Top1** | 期望任一答案位于返回候选第 1 位 |
| **Top5** | 期望任一答案位于返回候选前 5 位 |
| **完整覆盖率（fullCoverage）** | 返回候选池（search(input, limit=64)）中至少存在一个候选的 consumedInputLength ≥ requiredCoverage(input)，即解码器能给出覆盖全部输入字符的候选（不论文字是否正确） |
| **正确路径 oracle（oracleHit）** | 期望任一答案出现在 search(input, limit=64) 的完整候选池中（按同一匹配规则）。oracle 与 Top5 的差是召回损失；Top5 与 Top1 的差是排序损失 |
| **分类错误率** | 每类 1 − Top1（同时报告 1 − Top5 与失败原因分布） |
| **rankCost** | 命中时 rank + 1，未进前五记 9；只计算候选选择成本，不含字母按键。报告平均值与总和 |
| **交互前缀命中** | mixed_en / 数字交互项单独统计：候选是否等于 expected 的中文前缀；不计入引擎 Top1/Top5 分母 |
| **延迟** | 单次 search(input, limit=8) 的 JVM 纳秒计时，p50/p95/p99；仅作同机参考，不代表 Android 端到端延迟 |

失败原因分类沿用 v1 口径并明确优先级：交互（交互项）→ 容错（typo/shorthand/mixed_pinyin）→ 排序（目标在 6–8 位或同骨架换字）→ 切分（字符重叠 ≥50% 或空候选）→ 缺词（期望无法由词典组合）。分类是启发式，不替代人工裁决。

### 5.1 置信区间

对 Top1/Top5 报告 **Wilson score 95% 置信区间**：

    center = (p + z²/(2n)) / (1 + z²/n)
    half   = z * sqrt(p(1-p)/n + z²/(4n²)) / (1 + z²/n)

其中 p = 命中数/n、z = 1.96。报告格式：Top1 = x/n = p% [低%, 高%]。
- 每个类别每个 split 的样本量仅 20–450 条，区间较宽；**不以单条用例的变化调整总方向**。
- 任何类别 Top1/Top5 相对上一版下降 ≥3 个百分点，或正确路径 oracle 下降、正确性回归，必须解释并人工审阅；不能只凭总分提升合并。

### 5.2 报告口径

每次评测报告至少包含：

1. 环境（日期、commit、JVM、词典条目数、语料 manifest hash）。
2. 全量视图与引擎可评分视图的 n / Top1 / Top5 / rankCost / fullCoverage / oracle。
3. 按类别的 n / Top1 / Top5 / rankCost / 错误率 / 95% CI；交互项前缀命中单列。
4. 失败原因计数。
5. 延迟 p50/p95。
6. 与上一版/旧数据集的同口径对比，以及未完成项与风险。

## 6. 事件序列集口径（D01 配套，单独交付）

事件序列集是引擎准确率之外的端到端行为验收，**不得**把其邮箱/混排/交互用例混进拼音引擎准确率分母后只报一个总数。

- 文件：eval/v2/events/event_cases.jsonl（≥24 条）与 eval/v2/events/README.md。
- 每条字段：id、category、steps（[{action,args}]）、expectedFinalText、expectedState。
- 覆盖：连续输入+选词、pending 确认、退格与 Unicode 删除、选区变化、光标移动、切换字段（EditorInfoChanged）、横屏/重建（重建 controller）、中英切换、标点、空格、快速键序。
- 执行：JVM 上用 RecordingTextSession + KeyboardController 回放，断言最终编辑器文本、composing、候选与状态；只代表 JVM 行为，不代表真机。
- 数据：事件为合成数据，不含真实隐私；期望句不得写入生产词库。

## 7. 治理、版本与复现

1. 语料变更流程：改生成器 → 运行 python eval/v2/corpus/build_v2_corpus.py → 运行 python eval/v2/check_v2_corpus.py → 将新 manifest.json 的 dev/validation SHA-256 写入 docs/EVALUATION_V2_BASELINE.md → 评审 diff。
2. 校验器必须通过：6 列格式、id/input 唯一、dev/validation 输入无交集、自然句近重复隔离（Jaccard ≥0.75 为 0）、类别计数 ≥ 下限、词典整句泄漏 0、专项类别可推导、manifest hash 一致、Tatoeba 源句逐条可追溯。
3. 评测只通过 JUnit 输出；不接受手工挑选用例或修改分母来“提高”成绩。
4. 本协议不修改生产代码，不新增网络/云服务；所有数据与脚本在仓库内可追溯。

## 8. 局限

1. Tatoeba 句子存在翻译腔、方言/书面语混杂；自然句质量高于随机但不等同真实聊天分布。
2. “简体”以 GB2312 可编码为代理，少量共用字/异体字可能仍带繁体用法（如“著”），不影响拼音评测但需在质量评审中知悉。
3. pypinyin 对多音字按默认读音生成拼音，可能和引擎词典读音不同；这是“独立来源”的代价，不能按引擎输出改语料。
4. 自然长句上限 24 字/拼音 60 字符，是为避开引擎 64 字符硬上限；更长输入的 UX 由事件序列/设备检查单独覆盖，不能把本指标当作长文完整能力。
5. 专项类别为模板/人工构造，分布与真实输入不同；只能用于分类回归，不能外推整体准确率。
6. 多答案只枚举有限写法，未列出的合理表达会被记为失败；同音/简拼类应结合 oracle 与人工抽查解读。
7. JVM 延迟不是 Android 端到端延迟；设备指标以 D00/设备任务记录为准。

## 9. D04 审查补充（2026-11-08）

1. 阶段一评测器 `rankCost` 实现曾对命中第 6–8 名计 `rank+1`，与本协议“前五未命中记 9”不同。D04 已修正为仅 rank 0–4 计分，其余记 9；历史 Top1/Top5 不变，历史 rankCost 保留原记录并标注不可直接与修正值比较。
2. `docs/LICENSE_CLEANUP.md` §2/§3 记录：生产字符 n-gram 使用全部 89,289 条 Tatoeba 句子，detailed 与普通导出 ID/文本一致。v2 自然句来自相同普通导出，因此当前 dev/validation 虽然互相近重复隔离、整句不在词典中，**仍与生产统计训练源重合**。校验器的“整句泄漏 0”不覆盖统计训练重合。
3. v2 可用于同一来源分布的回归和探索性系统对比，不再将其表述为训练源独立的泛化验收。新泛化验收须采用未参与生产 n-gram 的来源，或在生成训练统计前按来源/句子簇隔离并冻结；不得仅在评测后删除难例。D04 不重写历史语料、资产和基线，也不据 validation 细项调参。
4. 训练源独立的 v3 验收集已建立：见 `docs/EVALUATION_V3_PROTOCOL.md` 与 `docs/EVALUATION_V3_BASELINE.md`（CC0 来源，601 条，仅验收不调参）。v2 与 v3 不得混在同一分母只报一个总分。
