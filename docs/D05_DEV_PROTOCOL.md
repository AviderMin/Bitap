# D05 跨来源诊断 dev 协议

- 文档版本：d05-v1
- 日期：2026-11-08
- 任务：task-9 / D05 跨来源诊断 dev 集
- 数据：eval/d05/dev/dev.tsv（950 条，允许逐条诊断）
- 生成：eval/d05/dev/build_d05_dev.py
- 校验：eval/d05/dev/check_d05_dev.py
- 清单：eval/d05/dev/manifest.json
- 署名：eval/d05/dev/ATTRIBUTION.md

## 1. 目的与角色

D05 需要一套**允许逐条查看、允许按失败案例做诊断和实现调参**的开发集。它不是验收集，不能用于发布验收或对外宣称泛化成绩。

| 数据集 | 角色 | 允许逐条诊断 | 验收 |
|---|---|---:|---:|
| v1 dev/holdout | 历史小样本 | 是 | 否 |
| v1 sealed | 旧回归 | 否（只回归） | 否 |
| v2 dev/validation | Tatoeba 同源探索 | v2 dev 是；validation 仅聚合 | v2 validation 不作独立验收 |
| **D05 dev** | **跨来源诊断开发集** | **是（本协议允许）** | **否** |
| **v3 validation** | **训练源独立验收集（CC0）** | **否（只聚合）** | **是** |

规则：

1. D05 dev 的句子可以逐条查看、按类别/失败原因分析，并用于实现诊断与调参；鼓励用它定位错误。
2. D05 dev 的任何结果不得写成“验收通过”或“泛化达标”；发布验收只允许 v3 validation（必要时另建 v4）。
3. D05 不修改生产词库；不把 D05 句子写入 pinyin_dict.tsv 或作为算法特例。
4. D05 与 v3 必须保持来源隔离：v3 已选择的 601 条全部从 D05 候选中排除，并做 exact+near-dup 排除；D05 不得读取 v3 逐条结果做决策之外的使用（D05 是 dev，可以看 v3 的聚合，但不能把 v3 句子搬入 D05）。
5. D05 的 special 类别只能从词典/ jieba 通用词构造，禁止从 v3/v2 期望句改写或按句子特判。

## 2. 数据来源与许可

| 项 | 值 |
|---|---|
| 来源仓库 | irvin/cc0-sentences（https://github.com/irvin/cc0-sentences） |
| 使用文件 | **主来源**：setences.txt、g0v_slack_rand0m.txt、tg_common_voice.txt 的剩余行（排除 v3 已选 601 条及近重复）；**补充**：sayit.txt、lms.txt、cvsprint_201902.txt、exam_text.txt、gov_press_release.txt、cofacts.txt、web_slang.txt、chatlogs.txt |
| 许可 | CC0 1.0 Universal（https://creativecommons.org/publicdomain/zero/1.0/） |
| 压缩包 | .tmp/cc0/cc0-sentences.zip，SHA-256 e229d8e3bef48473d55b3ab1a123555080f855120206ee5a82b34bb9413d9fc5 |
| 转换 | zhconv 1.4.3，zh-TW → zh-CN（简体），UTF-8 |
| 特殊词种子 | .tmp/jieba.txt（MIT）；pypinyin 0.55.0（MIT） |

未使用 Tatoeba 句子；D05 不依赖生产 n-gram 训练源。

## 3. 数据构成

| 类别 | 条数 | 说明 |
|---|---:|---|
| natural_short | 300 | CC0 跨来源 2–8 汉字 |
| natural_medium | 289 | CC0 跨来源 9–16 汉字 |
| natural_long | 11 | CC0 跨来源 17–24 汉字（源数据长句不足，如实记录） |
| typo | 100 | 从 jieba 高频通用词构造的漏字母/多字母/相邻交换/邻键 |
| shorthand | 100 | 从 jieba 高频词构造的全声母简拼（含歧义组合） |
| mixed_pinyin | 100 | 完整音节 + 末音节声母 |
| mixed_en | 50 | 交互项：中文前缀 + 英文原文提交 |
| **合计** | **950** | 自然句 600 + 专项 350 |

自然句短/中优先从主来源（setences/g0v/tg）选取；其他文件只补充缺口。实际自然句来源：short 300（g0v 221 / setences 79）、medium 289（g0v 220 / setences 69）、long 11（web_slang 6 / exam_text 4 / cvsprint 1）。长句目标 ≥30，但主来源和补充文件过滤后仅 11 条 17–24 字句可用：v3 已选 601 条中包含大量长句，按本任务要求排除 v3 的 exact/近重复后长句不足以达到 30，**如实记录为 11 条，不通过改写/补造句子凑数**；自然句总量仍为 600（short 300 / medium 289 / long 11）。

## 4. 筛选与隔离

1. 逐行读取指定 CC0 文件，zhconv 转简体。
2. 只保留 CJK 统一表意文字（GB2312 可编码）与中文标点；排除拉丁字母、数字、Emoji、引号/括号和内部终止符。
3. 按汉字数分 short 2–8、medium 9–16、long 17–24；拼音长度 ≤60。
4. 精确去重；排除与 pinyin_dict.tsv 整词相同的自然句；排除与 v1/v2/v3 任一 expected 完全相同的自然句；排除与 v1/v2/v3 expected 字符 2-gram Jaccard ≥0.75 的近重复自然句。
5. 自然句在候选内部做字符 2-gram Jaccard ≥0.75 的 MinHash/LSH 近重复聚类，每簇最多取 2 条，减少重复句。先选主来源，只有主来源填不满时再用补充文件。
6. 专项类别只从 jieba 通用词构造；不引用 v3/v2 期望句；mixed_en 使用独立的合成前缀/英文尾清单并做输入唯一性检查。

## 5. 文件格式

TSV，6 列，UTF-8，LF：

    id <TAB> category <TAB> input <TAB> expected <TAB> note <TAB> source

- id：d05-类别-序号，唯一。
- category：natural_short / natural_medium / natural_long / typo / shorthand / mixed_pinyin / mixed_en。
- input：小写拼音/输入串；mixed_en 含小写英文尾；mixed_pinyin 为混拼。
- expected：期望中文；homophone 不适用本集；shorthand/mixed_en 可用 | 列多个可接受答案。
- note：设计说明；typo 含“正确拼音 X”。
- source：自然句为 cc0:文件名:行号；专项为 template:类别:键。

## 6. 使用方式与诊断口径

- 逐条诊断：可以按类别、失败原因（缺词/切分/排序/容错/交互）统计并定位具体句子；这是 D05 的核心用途。
- 指标可使用与 v2/v3 相同的 Top1/Top5/rankCost/oracle/fullCoverage，但 D05 结果只作为开发信号，不作为验收结论。
- mixed_en 为交互项，单独统计 prefix Top1/Top5，不计入引擎可评分分母；D05 的自然句与其余专项均为引擎可评分。
- 建议每次实现变更先用 D05 定位失败案例，再在 v2 dev 做回归；v3 只在冻结候选后由 Lead 运行验收。

## 7. 复现

    python eval/d05/dev/build_d05_dev.py
    python eval/d05/dev/check_d05_dev.py

生成器固定输入哈希（manifest 记录源文件 SHA-256），同输入同 hash；校验器会检查格式、输入唯一、类别计数、词典整句泄漏、v3/v1/v2 精确/近重复隔离、来源追溯及 typo/shorthand/mixed_pinyin/mixed_en 可推导性。

## 8. 局限

1. 来源是台湾网络社群/日常聊天为主（setences、g0v、tg、chatlogs），加政府新闻稿/教材/歌词等补充文本；繁体转简体后仍有地区用词与语体差异；这不是验收分布。
2. natural_long 仅 11 条，长句统计不充分；长句问题需结合 v3 长句和后续构造专用长句。
3. 专项类别为模板构造，不代表真实输入分布；其用途是类别诊断，不是总体准确率估计。
4. D05 可逐条看，因此一旦用于调参就不能再作为未见数据；它本来就不是验收集。
5. JVM 延迟不是 Android 端到端延迟。
