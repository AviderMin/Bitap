# D04 同词典 Rime 宿主对照实验

日期：2026-11-08（Asia/Shanghai）。基线：D04 宿主 PoC `f073836`；生产代码、词库、AI 与评测语料未修改。

## 结论与边界

本实验回答一个更窄的问题：如果 Rime 与 Bitap 使用**同一份词表与词频权重**，词典覆盖差异不再成立，剩余差距还有多大？

- 同词典下，Bitap `PinyinInputEngine` Top1 714/1384（51.59%）、Top5 1001/1384（72.33%）；Rime `bitap_d04` + 项目词表 Top1 445/1384（32.15%）、Top5 466/1384（33.67%）。**即使词表相同，两者仍存在明显差距**。
- 同词典 Rime 比“不同词典”Rime（Top1 573/1384，41.40%）更低：把 pinyin-simp 自带词典换成本项目 asset 后，Rime 自然句 Top1 从 217/167/67（short/medium/long）降到 144/120/43。这衡量固定组合换词表/权重这一整体替换的净影响，包含词表集合、权重与搜索/切分交互，不能拆成“词典贡献”再与解码器收益相加，也不能归因于解码器本身。
- 但这不是“纯引擎消融”：同词表+同词频仍不等于同语言模型。Bitap 的字符 n-gram 统计参与解码/排序（默认 `SENTENCE_CORPUS_WEIGHT=0`、`SENTENCE_DICTIONARY_WEIGHT=0`，不是整句后置重排），Rime `pinyin_simp.dict.yaml` 格式无法承接这套统计；Rime 方案在该最小配置下对自然句只产生 **1 个完整句候选**（见 `candidateStructure`），Top1==Top5，64 项 oracle 与 Bitap 的多整句候选枚举也不可直接并列。因此本结果只能说明“同词表条件下宿主组合仍不同”，不能宣称某一引擎在 Android 上必然更好。
- 不修改生产代码、词库或评测语料；不把 Rime 或词典接入 APK。

## 实验设计

- 词表来源：`app/src/main/assets/pinyin_dict.tsv`，SHA-256 `4c979d12d957f2e567f06844cfaa705e07dbf544b808ff055c86669d5a53f20d`，65,999 条词条、词频合计 58,378,390。`build/d04/bitap-dev.meta.json` 的 `dictionarySha256` 与此相同；`rime-samedict-dev.meta.json` 的 `dictionarySha256` 指向生成词典，`make_snapshot.py` 会断言两条链路，防止把不同词表的结果混用。
- 转换：`eval/d04/same_dictionary/build_same_dictionary.py` 用 pypinyin 0.55.0 的 `lazy_pinyin(word, style=Style.NORMAL)` 恢复音节边界，生成 Rime `pinyin_simp.dict.yaml`（`word<TAB>syllables<TAB>weight`），共 65,999 条；生成文件 SHA-256 `18282fcaa33c6e21eb893aa2a248391229c070d405901b108c4c89018c3ee7ec`。
- 方案：复用 `eval/d04/bitap_d04.schema.yaml`（SHA-256 `a14049a3c5f89167fc057eaa854eebd9422b429d589831c81d0c8ccff80fd973`）与 `eval/d04/default.yaml`（SHA-256 `b449df35896ccaa68380519c37c9158a7af7a77cd5ddeaa4a4e3ea38259c3b7f`），只替换 `translator.dictionary` 指向的 `pinyin_simp` 词典内容。
- 宿主：librime 1.17.0 / 33e7814 Windows MSVC x64，`rime.dll` SHA-256 `86b4c7357d4c6d293ce5589b234d8859ca2ac30923a03bedfa3926eeaf97fb0b`；本次首次部署+创建会话 664.79 ms（宿主初始化，不是 Android 冷启动）。
- 语料：`eval/v2/corpus/pinyin_cases_v2_dev.tsv`，SHA-256 `3421ca0837d3b1726532c186810deab759a42fcdf622f4157c6f76216bbe53d6`，1,430 条；交互项排除 46 条，可评分 1,384 条。未运行 validation。
- 评分：`eval/d04/compare.py` 统一规则；Top8 参与排名、64 项 oracle/完整覆盖池、rankCost 前五未命中记 9；Rime 的 full 通过隔离 composition 逐项模拟选择验证，不导出 partial 偏移。

## pypinyin 读音转换差异

`build_same_dictionary.py` 对全部 65,999 条词条逐条比较：

| 检查 | 数量 |
|---|---:|
| 检查词条 | 65,999 |
| pypinyin flat 读音与 asset pinyin key 不一致 | 0 |
| 音节数 ≠ 汉字数 | 0 |
| 非法/空音节词条 | 0 |
| 需要 `lve→lue` / `nve→nue` 机械规范化的词条 | 68 |
| 使用的不同音节数 | 403 |
| 不在原 pinyin-simp 415 音节清单内的音节 | 0 |

- 本环境 pypinyin 版本与 asset 生成链路记录一致（0.55.0），且 asset 本身就是由同版本 pypinyin 生成，因此默认读音与 flat key **0 差异**；没有发现“多音字读错导致平铺拼音不同”的条目。仍统计到 20,772 条词含至少一个多音字，但其词级默认读音全部与 asset key 一致。
- 68 条仅涉及 üe 拼写约定（pypinyin `lve`/`nve` → Rime `lue`/`nue`），例如：战略 `zhanlve → zhan lue`、略 `lve → lue`、侵略 `qinlve → qin lue`、忽略 `hulve → hu lue`。完整报告与样例见 `eval/d04/same_dictionary/pinyin_conversion_report.json`。
- 不按评测答案修改词表；若未来 pypinyin 版本变化，应重新生成并核对报告中的差异计数。

## 总体结果

| 指标 | Bitap `PinyinInputEngine` | Rime `bitap_d04` + 项目词表 |
|---|---:|---:|
| 可评分 n | 1,384 | 1,384 |
| Top1 | 714 / 51.59% | 445 / 32.15% |
| Top1 Wilson 95% CI | [48.96%, 54.21%] | [29.74%, 34.66%] |
| Top5 | 1,001 / 72.33% | 466 / 33.67% |
| Top5 Wilson 95% CI | [69.91%, 74.62%] | [31.23%, 36.20%] |
| 正确候选 oracle64 | 1,169 | 468 |
| 完整覆盖64（不要求正确） | 1,383 | 1,374 |
| rankCost（前五未命中记 9） | 3.5809 | 6.3338 |
| 宿主前8查询 p50/p95/p99 | 3.2668 / 7.5423 / 9.1524 ms | 0.3788 / 0.9157 / 1.4064 ms |

配对首选：两边均正确 412、仅 Bitap 正确 302、仅 Rime 正确 33、均错误 637。

### 分类结果（Top1 / Top5 命中数）

| 类别 | n | Bitap Top1/Top5 | Bitap oracle64 | Rime Top1/Top5 | Rime oracle64 |
|---|---:|---:|---:|---:|---:|
| natural_short | 400 | 228 / 328 | 368 | 144 / 144 | 144 |
| natural_medium | 450 | 222 / 318 | 380 | 120 / 120 | 120 |
| natural_long | 300 | 111 / 155 | 209 | 43 / 43 | 43 |
| homophone | 40 | 40 / 40 | 40 | 40 / 40 | 40 |
| shorthand | 40 | 31 / 38 | 40 | 38 / 39 | 40 |
| typo | 40 | 15 / 21 | 23 | 0 / 1 | 1 |
| mixed_pinyin | 40 | 20 / 38 | 39 | 21 / 38 | 39 |
| number_punct（可评分） | 34 | 19 / 30 | 33 | 10 / 10 | 10 |
| proper_noun | 40 | 28 / 33 | 37 | 29 / 31 | 31 |

结构诊断（来自同一批原始导出，已写入快照 `candidateStructure`）：

- 自然句 64 项池中，Rime 每例完整句候选数：`natural_short` 400 例均 1 个；`natural_medium` 449 例 1 个、1 例 0 个；`natural_long` 300 例均 1 个。因此 natural 类 Top1==Top5，oracle 与多候选引擎不可直接比较。
- 完整覆盖缺口 9 例（Bitap 有完整候选而 Rime 没有）：7 例 typo（Rime 最小方案无错拼纠正）+ 2 例 number_punct（`wanshang.`、`zaijian!` 在 RimeSetInput 下返回 0 候选，属于宿主/方案限制，不是词表缺词）。

## 与“不同词典”实验的差异

D04 宿主 PoC 的“不同词典”结果（`eval/d04/results/dev-host-2026-11-08.json`）与本实验对照：

| 指标 | 不同词典：pinyin-simp（Rime） | 同词典：项目 asset（Rime） | 同词典 Bitap |
|---|---:|---:|---:|
| Top1 | 573 / 41.40% | 445 / 32.15% | 714 / 51.59% |
| Top5 | 601 / 43.42% | 466 / 33.67% | 1,001 / 72.33% |
| oracle64 | 611 | 468 | 1,169 |
| 完整覆盖64 | 1,375 | 1,374 | 1,383 |
| rankCost | 5.5535 | 6.3338 | 3.5809 |

- 两套词典本身差异很大：原 pinyin-simp 词表 65,125 条（64,425 个不同词），与项目词表仅 35,411 个词重合（项目独有 30,588、pinyin-simp 独有 29,014），词频范围 0–4,828,294 对比项目 28–883,634。因此“不同词典”不是同一个词表的排序差异，而是词表集合与权重分布都不同；详见 `pinyin_conversion_report.json` 的 `rimeReference`。
- 换成本项目词表后，Rime Top1 下降 9.25 个百分点、Top5 下降 9.75 个百分点（oracle 611→468）；自然句三类分别 217→144、167→120、67→43。同词典下 shorthand 反而从 25/31 提升到 38/39，homophone 34→40；proper_noun 33→29、mixed_pinyin 17→21。这说明换用不同词表/权重这一整体替换的净效应明显，**不能拆成可加贡献，也不能把“不同词典”对照的 Rime 分数解释为纯引擎能力**。
- 同时也不能把同词典下的 Rime 低分解释为词表更差：两边词表完全一致。剩余差异包括 Rime scheme 的候选生成、词权重使用方式、缺少 Bitap 的字符 n-gram 解码/排序统计、以及 Rime 每个自然输入只生成一个完整句候选等。
- 因此本实验支持两点：1) 此前 D04“不同词典”差值中词典因素不可忽略；2) 即使去掉词表差异，自研方案在本套 v2 dev 上的 Top1 仍高于该最小 Rime 方案，但该结论不是 Android 产品结论，也不足以单独决定引擎选型。

## 限制

1. 宿主 Windows x64 实验，librime 通过 ctypes 调用 MSVC DLL；不是 Android/JNI，没有 PSS、APK 增量、冷/热唤起或真机输入延迟数据。延迟只作宿主粗略预算，不能换算手机倍数。
2. Rime 使用受控最小 `bitap_d04` 方案（无学习、无补全、无标点/识别器、无第三方插件）；不是完整 Rime 默认配置，也不是产品可用方案。
3. 同词表≠同语言模型：Bitap asset 的字符 n-gram 聚合统计（来自 Tatoeba 导出的字符/二元/三元计数，见 asset provenance）无法写入 `pinyin_simp.dict.yaml`，因此 Rime 侧没有等价统计信号；这是剩余差距中无法归为“词典”的部分。
4. Rime C ABI 不暴露候选消费范围；full 通过隔离 composition 逐项选择验证，partial 偏移未导出；两边的 partial 处理不能直接当作生产消费契约。
5. v2 dev 自然句来源与生产 n-gram 训练源存在重合（见 `docs/D04_HOST_COMPARISON.md` §阶段一审查发现）；本实验仅作 dev 探索性诊断，不是独立泛化验收，未运行 validation，也未据结果调参或改词库。
6. 本次 pypinyin 0.55.0 与 asset 生成版本一致，词典转换差异为 0；更换 pypinyin/词库版本后必须重新生成 `pinyin_conversion_report.json` 并复核。

## 复现

依赖准备（联网、只写入 `.tmp/`）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File eval/d04/bootstrap.ps1
```

生成同词典 Rime 数据与转换报告（默认写入 `build/d04/same-dictionary/shared`，build 产物不提交）：

```powershell
python eval/d04/same_dictionary/build_same_dictionary.py
```

在全新 work 目录跑同词典 Rime 候选（`--work` 必须不存在 user 子目录）：

```powershell
python eval/d04/rime_probe.py `
  --dll .tmp/d04/bootstrap/native/dist/lib/rime.dll `
  --shared build/d04/same-dictionary/shared `
  --work build/d04/same-dictionary/work-01 `
  --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv `
  --out build/d04/rime-samedict-dev.jsonl
```

对比并生成聚合快照：

```powershell
python eval/d04/compare.py `
  --corpus eval/v2/corpus/pinyin_cases_v2_dev.tsv `
  --baseline build/d04/bitap-dev.jsonl `
  --rime build/d04/rime-samedict-dev.jsonl `
  --out build/d04/comparison-samedict-dev.json

python eval/d04/same_dictionary/make_snapshot.py
```

`build/d04/bitap-dev.jsonl` 由 `D04BaselineExportTest` 导出（`BITAP_D04_EXPORT=1`，`--rerun-tasks`）；本实验直接复用该文件，未修改生产导出代码。

契约测试：

```powershell
python -m unittest discover -s eval/d04 -p "test_*.py" -v
```

## 产物

| 文件 | 说明 |
|---|---|
| `eval/d04/same_dictionary/README.md` | 工具与命令说明 |
| `eval/d04/same_dictionary/build_same_dictionary.py` | 可重复转换脚本 |
| `eval/d04/same_dictionary/make_snapshot.py` | 从 build 原始导出生成聚合快照 |
| `eval/d04/same_dictionary/pinyin_conversion_report.json` | pypinyin 差异/规范化/音节覆盖明细 |
| `eval/d04/results/dev-host-same-dict-2026-11-08.json` | 聚合快照（本文数字来源） |
| `build/d04/rime-samedict-dev.jsonl`、`.meta.json` | Rime 原始候选（忽略，不提交） |
| `build/d04/comparison-samedict-dev.json` | compare.py 原始输出（忽略，不提交） |

本实验未向 APK 引入 librime、Rime 词库或任何新依赖；未修改 `app/src/main/assets/pinyin_dict.tsv`、`eval/v2/corpus`、`eval/d04` 既有脚本与生产代码。
