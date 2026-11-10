# 评测与质量规范

## 1. 数据集角色

| 数据 | 规模/角色 | 开发使用规则 |
|---|---|---|
| `eval/pinyin_cases_dev.tsv`、holdout、sealed | 各132，历史回归 | 旧sealed已曝光，不再叫新盲测；不用于硬编码句子 |
| `eval/v2/corpus/` | dev1430/validation710，同源探索 | 自然句与生产Tatoeba统计同源，不能叫训练源独立验收 |
| `eval/v2/events/` | 32条/11类事件回放 | 最终文本/状态回归；不是触控或真实IME设备验收 |
| `eval/v3/corpus/` | 601自然句，冻结新来源验收基线 | 首轮已运行，存在1条训练句重合；不逐轮按聚合分数选优 |
| `eval/d05/dev/` | 1050开发诊断 | 可逐条诊断和调参；不是发布验收；包含超预算输入 |
| `eval/d04/results/` | 宿主比较聚合快照 | 必须结合词库/方案/候选组织限制解释 |

v3与D05部分来自相同CC0仓库不同文本，按句子精确/近重复排除，不等于所有领域/来源都相互独立。训练集合、评测集合和其他验收集合需分别检查，不能只查现有v1/v2样例就声明完整训练隔离。

### 冻结hash

| 文件 | SHA-256 |
|---|---|
| v2 dev | `3421ca0837d3b1726532c186810deab759a42fcdf622f4157c6f76216bbe53d6` |
| v2 validation | `9097f226f1709d8b5e20d0faf4a661419d46d051e04271e5d302b0ce7f072994` |
| v3 validation | `355d1e7ee611eed492c6d937a7d6261b015c3b7ef8df22a083a5c97b8ab6c8f2` |
| 当前D05 dev | `63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad` |

文件原始字节/依赖版本/换行会影响hash；从manifest核对，不手改冻结文件再悄悄重算。语料改版要新版本、新基线、保留旧记录，不能通过删掉已失败的句子提升成绩。

## 2. 指标与分母

- Top1/Top5：完整消费当前有效输入、文本命中允许答案，位于原返回列表前1/5项；不能先过滤partial再重新排名。
- rankCost：仅前五命中记rank+1，其余记9。它是候选名次代理，**不是实际点按次数或总输入成本**。旧v2曾将第6–8位也计rank+1，历史成本不可直接与修正值比较。
- oracle64：期望完整候选出现在最多64项返回池。它不是穷尽搜索oracle，也不证明64项外或放大搜索后一定不存在。
- fullCoverage：存在消费全部输入的候选，不要求文字正确；100%不等于准确率100%。
- 交互项：mixed_en/阿拉伯数字等按协议单列，不混入纯引擎分母；同时不能将它们从产品端到端失败表中删除。
- 失败分类：noRecall、pruningLoss、rankedLow、displayLoss依赖当前诊断预算/启发式与返回配额，不等于根因定论。查询limit8与64可能改变候选组织，必须保留两套返回值。
- 延迟：固定设备、构建、温度、刷新率、AI/学习开关、预热和样本量，报告p50/p95/p99。JVM、native宿主、应用帧完成、系统呈现分别标注。
- 真实操作：字母、候选点击、展开、滚动、翻页、切换语言、退格和修正分开计数。显示前N项不能代替实际视口几何。

Top1/Top5报告命中数/分母、Wilson95%置信区间和分类；配对实验同时给出成功/失败转换，不凭单条涨跌断言总体改善。

## 3. D05输入预算视图

当前1050条中mixed_en50为交互，纯引擎全量1000。自然句700中33条拼音65–70超出引擎64上限；可排序自然句667，引擎可排序总计967。

排序实验可在667子集定位机制，但每份综合报告同时写全量数量、超预算33和失败状态；不能将子集成绩叫完整长句能力。预算问题的改善需新增独立机制与UX测试，不和权重实验一起调整。

控制器已对>64字符输入采用“保留全文composing、仅查询64字符前缀”的分段规则（JVM测试覆盖）；这不改变本节的统计口径：33条65–70字符仍必须在全量报告中列为超预算，排序指标仍只对≤64可排序子集计算，长输入分段的真实文本正确性已在22021211RC单机竖屏做初步验证（66字符样例消费64字符前缀并保留尾部）；连续选择、字体/横屏/多ROM和操作成本仍需设备验证。

长输入边界诊断单独报告（`build/d05/long-input-boundary.txt`、`-sweep.txt`）：对D05 dev 33条65–70字符自然句比较FIXED/SYLLABLE/WORD前缀策略，记录前缀Top1/Top8/Top64、剩余输入一致性、贪心与oracle整句操作代理。该诊断不进入Top1/Top5排行分母，也不作为生产策略切换的验收；固定64仍是当前生产默认。

Partial排序诊断（`build/d05/partial-ranking.txt`）：固定候选池、64切点、quota和early partial位置，先冻结入选候选及消费范围，仅重排既定partial槽位；必须断言所有模式的候选文本/消费集合与Top64命中一致，再报告完整句自然集Top1/Top5/rankCost、前缀Top1/Top8/Top64、命中样本平均名次/消费长度、贪心整句成功/失败步数。`SHORTEST_FIRST`仅把Top8从14提高到16/33，命中样本平均消费长度16.26（SCORE17.70），不能按“前N出现率”单独称收益；生产默认保持SCORE。

长句路径丢失诊断（`build/d05/long-sentence-path-loss.txt`、`-detail.tsv`）：只看D05 dev `natural_long` 且拼音≤64的90条，按“未生成（独立词库能否拼出）/仅宽beam或entry预算可见/已生成未收集/已收集但排名低/截断”分层，并单列33条65–70超预算句；报告分母、置信区间、JVM查询时延和API限制（debug full-path sink 4000条上限、无per-position beam bucket）。它是开发诊断，不是验收，不改变生产排序默认；分类依赖当前beam/entry/候选配额，不能当作根因定论。

长输入lattice预算消融（`build/d05/long-budget-ablation.txt`）：对D05 natural 667（short300/medium277/long90）比较baseline beam64/entries8与beam96/128/192、entries16/32及组合；必须同时报告natural_long 90的Top1/Top5/rankCost/oracle64、natural667是否回退、p50/p95 JVM时延。该报告证明预算提升没有Top1/Top5收益且时延上升，不能把oracle微增当作产品改善，也不能据此启用生产默认。

长输入局部纠错窗口（`build/d05/local-correction.txt`）：默认关闭的尾部窗口编辑距离1实验，合成用例验证word/consumed/isCorrection/后缀保留；D05 typo100与natural667必须报告baseline/enabled对照、top8/top64额外纠错候选数、Top1/Top5回退、专名/英文/数字回退样本和JVM时延。当前结论为不开启；没有D06真实长串局部错拼数据前不能把合成用例当作能力验收。

D07 连续中英数字混排（工作树基础版）：`KeyboardMixedInputTest` 18项覆盖 Shift 启动 literal run、大小写/数字/`_`/`-`、空格/回车/标点/退格/语言与字段切换、literal run 不触发拼音查询；`MixedInputResolverTest` 16项覆盖词表规范化、最长英文/数字后缀、整词 English fallback、短功能词不劫持拼音、去重/limit、`PinyinInputEngine` 合并与纯拼音不变；另用生产 `pinyin_dict.tsv` 实测 `daimajianchayongsonar -> 代码检查用Sonar` 等4例。该矩阵是 JVM 合成事件与引擎单测，不是设备验收；D05 `mixed_en` 50条仍作为交互项，不进入纯引擎 Top1/Top5 分母。

## 4. 训练重合与验收治理

生产字符n-gram使用全部89,289条Tatoeba。v2自然句同源已确认。v3换成CC0后仍有1/601规范化精确重合；审计规则为CJK字符规范化、bigram Jaccard≥0.80、长度比≥0.5，不做语义级保证。

```powershell
python eval/v3/check_v3_corpus.py
python eval/v3/check_v3_corpus.py --strict
python eval/v3/audit_training_overlap.py
```

轻量模式可能跳过缺失原件的溯源，严格模式要求四个CC0文件和固定zip存在并匹配。重合审计退出1表示发现已知重合，退出2表示无法验证输入，不能当作随机环境失败忽略。冻结聚合快照为 `eval/v3/training_overlap_audit_2026-11-08.json`。

仅冻结候选后运行专门验收：

```powershell
# 仅当正式决定执行该轮验收；不是普通回归命令
$env:PINYIN_V3_ACCEPTANCE='1'
try {
  .\gradlew.bat :app:testDebugUnitTest --tests com.bitap.keyboard.core.PinyinV3EvalTest --rerun-tasks --console=plain
} finally { Remove-Item Env:PINYIN_V3_ACCEPTANCE -ErrorAction SilentlyContinue }
```

验收只输出聚合不等于可以无限重试选优。若根据聚合反馈改变策略或反复挑模型，该集合逐渐成为开发信号；记录每次运行、候选commit和决策，必要时另建隔离新验收。v3只含自然句，D06错拼、D07混排仍需专项与事件/设备矩阵。

## 5. 各层验证

| 层 | 必需证据 |
|---|---|
| 状态机/接口 | stale/session、partial消费、pending、密码/禁学习、取消与清除 |
| 算法 | dev分类、before/after、单机制消融、oracle和延迟、负向类别 |
| 数据 | manifest/hash、格式、重复、训练与验收隔离、来源追溯/署名 |
| 设备 | 原生/Compose/WebView、字段矩阵、光标/选区/横屏/切应用、触控 |
| 性能 | 同设备构建/场景的p95/p99、PSS/native、包体、温升与释放 |
| 隐私 | APK权限、备份、上下文最小化、禁学习、无正文日志 |
| 发布 | 独立质量、设备矩阵、资源许可、签名/hash、回退说明 |

当前没有完整原生/Compose/WebView自动设备矩阵；ADB坐标脚本和JVM FakeTextSession覆盖的层次不同。工程门禁通过后仍需产品验收。

## 6. 实验交付格式

每批记录候选commit、词库/语料hash、参数、学习和上下文策略、设备与环境、正确性、分类命中数/分母、延迟、消融、真实操作、负向项、未完成和下一步。一次只改变一个主要机制；新数据和新实现同时变化时分开做对照。

重现历史D04/v3分数须使用对应代码commit或明确冻结当时tuning；当前默认权重与候选组织已改变，不能用当前导出覆盖历史快照后仍沿用旧基线名称。当前运行产生的新报告注明新commit和参数。
