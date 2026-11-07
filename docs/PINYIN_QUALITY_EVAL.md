# 拼音输入质量评测（P1）

> 状态：**基线版**（engine-dev 改进前，commit d5616bb）。after 对比待 engine-dev 改进落地后用同一评测器重跑并追加。
>
> 评测器：[PinyinQualityEvalTest.kt](../app/src/test/java/com/bitap/keyboard/core/PinyinQualityEvalTest.kt)；语料：[dev](../eval/pinyin_cases_dev.tsv) / [holdout](../eval/pinyin_cases_holdout.tsv)；生成与校验脚本：[build_corpus.py](../eval/build_corpus.py) / [check_corpus.py](../eval/check_corpus.py) / [check_no_leak.py](../eval/check_no_leak.py)；复跑入口：[run_pinyin_eval.ps1](../eval/run_pinyin_eval.ps1)。

## 1. 目标与独立性

本评测用于在 engine-dev 改动前后，用同一套语料和同一评测器给出可复跑、可比较的输入质量基线，回答三个问题：整句/短语能否一次选中、目标候选是否在前五、完成一次输入需要多少次选词或改错。评测器只调用 `PinyinEngine.search(input, limit=8)` 公共 API，不读取引擎内部结构，也不依赖实现里的词典排序或验收测试。

语料独立性约束：

- 全部句子由人工按真实手机输入场景编写，**不是**从 `pinyin_dict.tsv` 或实现里的验收句生成；`pypinyin` 只用于生成/校验拼音拼写，不参与选词。
- 开发集（dev）可用于 engine-dev 的通用改进与调试；保留集（holdout）只向 Lead 汇报聚合指标和失败类别数量，**不提供具体句子**，也不得作为词条、提示示例或特殊判断进入实现。
- 已核对：日常聊天、时间地点、工作沟通、未收录整句、长句五类中没有任何一条期望整句已经是词典整条词条（即必须通过组合/解码得到）；同音词、简拼、混拼、错拼类的目标词本身就是词典词，这是为了能评测“候选是否存在”，与“整句未收录”不矛盾。

## 2. 语料设计

### 2.1 文件与格式

每条一行，制表符分隔：

```
id<TAB>category<TAB>input<TAB>expected<TAB>note
```

- `expected` 可用 `|` 分隔多个都算正确的答案（如 `干嘛|干吗` 的同音变体）；匹配时任一答案命中即算命中。
- `note` 记录设计意图；错拼类会注明错拼类型与正确拼音。
- 开发集 **132 条**，保留集 **132 条**，合计 **264 条**；两边各 11 类、每类 12 条。两边 `input` 无重复，保留集不使用开发集出现过的输入。

### 2.2 类别分布

| 类别 | 覆盖点 | 开发集 | 保留集 |
|---|---|---|---:|---:|
| daily_chat 日常聊天 | 寒暄、约饭、报平安、日常应答 | 12 | 12 |
| time_place 时间地点 | 具体时间、相对时间、地点与问路 | 12 | 12 |
| work_comm 工作沟通 | 会议、邮件、文档、进度、发布 | 12 | 12 |
| unseen_sentence 未收录整句/多种表达 | 未收录整句、同音异形（的/得等）多答案 | 12 | 12 |
| homophone 同音词歧义 | 单/双字同音词，expected 用 `\|` 列多个合理答案 | 12 | 12 |
| long_segment 长句和分段选词 | 15–30 字长句整句；其中 2 条以首段为期望（分段选词） | 12 | 12 |
| mixed_en 中英文混输 | 中文前缀 + 英文原文尾（交互项，见 §3.4） | 12 | 12 |
| number_punct 数字与标点 | 中文数词/金额/日期/时间、阿拉伯数字原文、撇号/空格/尾标点 | 12 | 12 |
| shorthand 简拼 | 全声母简拼（nh、wm、dbq 等） | 12 | 12 |
| mixed_pinyin 混拼 | 完整音节 + 后续声母（zhongg、beij 等） | 12 | 12 |
| typo 常见错拼 | 漏字母/多字母/相邻交换/邻键各 3 条 | 12 | 12 |

### 2.3 可判定性与多答案

- 拼音按普通话标准拼写（如“不可以迟到”写作 `bukeyichidao`），不把明显错误拼音写成期望；`pypinyin` 校验除 typo/简拼/混拼外的每条 expected 与 input 一致。
- 同音词（如 `shijian → 时间|事件|实践|世间`）和语法同音变体（`他说得很有道理|他说的很有道理`）用 `|` 列出多个合理答案，避免把“另一个同样正确的中文写法”误判为失败。
- 错拼类 note 标注“漏字母/多字母/相邻交换/邻键 + 正确拼音”，期望仍是自然中文。

### 2.4 语料自查（可复跑）

- `python eval/check_corpus.py`：核对 132+132 条、11 类各 12 条、id/input 无重复；所有全拼类 expected 与 input 拼音一致（errors=0）；typo 与正确拼音的 Damerau 距离均为 1，且“漏字母/多字母/相邻交换/邻键”子类型与 note 一致；shorthand 均为对应词的全声母；mixed_pinyin 均可由“完整音节+声母”组合生成；mixed_en 中文前缀均 ≥2 字且英文尾均为 ASCII；词典读音告警 0。
- `python eval/check_no_leak.py`：基线自查扫描 103 个文本文件、14,935 次检查；after 冻结版本复跑扫描 112 个文本文件、16,240 次检查；两次均为 holdout 句子/长输入源码命中 0、holdout 整句作为词典整条词条命中 0。
- 两个脚本在每次 engine-dev 改动后、交付前都应重跑；自查通过只代表“语料可判定且未泄漏”，不代表实现质量。

## 3. 评测器与指标

### 3.1 运行方式

评测器是 JVM 单元测试，一条命令复跑（在 baseline worktree 或主工作区均可；脚本会强制重跑，避免 Gradle 把“只改了 eval 语料”当成 up-to-date）：

```powershell
# 等价命令：$env:PINYIN_EVAL_STAGE='baseline'; .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks
.\eval\run_pinyin_eval.ps1 -Stage baseline   # 主工作区 after 评测时改为 after
```

- 输入：仓库根目录 `eval/pinyin_cases_dev.tsv`、`eval/pinyin_cases_holdout.tsv`；词典：`app/src/main/assets/pinyin_dict.tsv`。
- 输出：`build/pinyin-quality-eval-baseline.txt`（或 `-after.txt`），同时打印到 Gradle 控制台。
- 每次搜索 `limit=8`；返回候选按顺序即键盘候选页顺序。

### 3.2 指标定义

| 指标 | 定义 |
|---|---|
| top1 | 期望任一答案在返回候选第 1 位 |
| top5 | 期望任一答案在返回候选前 5 位 |
| 选词/改错次数 taps | 命中时 rank+1（第 1 位=1 次，第 5 位=5 次）；未进前五记 9（翻一页再改错的经验上界）。报告平均与总计 |
| p50/p95/p99 延迟 | 单次 `search` 的 JVM 纳秒计时换算毫秒，排序取分位；先做 48 条预热以规避 JIT 冷启动 |
| 按类别拆分 | 每类 n、top1、top5、平均 taps |
| 失败原因 | 仅对未进前五的失败分类，见 §3.3 |

匹配规则：候选词与期望答案做去空白/标点后的比较；整句/短语类要求候选完整覆盖输入（`consumedInputLength` ≥ 输入有效字符数），避免把“只选中前半句”算作整句命中。分段选词（long_segment 中 note 含“分段选词”）和 mixed_en 交互项允许候选匹配期望中文前缀，匹配后由用户继续输入/原文提交后续内容。

### 3.3 失败原因分类

| 原因 | 含义 |
|---|---|
| 缺词 | 期望句无法由词典条目组合出（词典缺少必要词条） |
| 切分 | 候选到达了大致区域但音节切分/词边界错误（与期望字符重叠 ≥50%，或整句解码无输出） |
| 排序 | 候选存在但在第 6–8 位；或同长度候选前两字正确、同音字/用词选择错误（骨架对、排序/选字错） |
| 交互 | 评测器/输入流程问题：mixed_en 中文前缀+英文原文尾、阿拉伯数字原文提交等，**标注不计入引擎指标** |
| 容错 | typo、简拼、混拼类未命中（引擎尚不支持轻量纠错/简拼索引） |

分类是词典与字符重叠启发式，不是人工逐条裁决；边界处（切分与同音选字）存在人工判定余地，见 §8 局限。

### 3.4 全量（含交互项）与引擎可评分

- **引擎可评分**：排除 mixed_en 12 条/集，以及 number_punct 中含阿拉伯数字的 1 条/集（这类输入的流程是“数字原文提交、中文段选词”，不是单次连续拼音整句）。
- **全量**：包含上述交互项。报告同时给出 `all.*` 与 `engine.*` 两组指标；正文默认引用 **engine** 指标，并在总览表列出 all 值，便于看清交互项影响。
- mixed_en 用例是“中文前缀 + 原文英文尾”的交互路径，不是单次连续拼音整句；其中像“这个|App”这种只评测前缀的用例，按同一规则只要求候选完整等于中文前缀（≥2 字），不能用一个字（“我”）冒充命中。

## 4. 测试环境

| 项 | 值 |
|---|---|
| 基线代码 | worktree `D:\Projects\Github\Bitap-baseline-head`，detached HEAD = d5616bb（干净，无主工作区未提交改动） |
| OS | Windows 11 10.0（JVM 单元测试，非真机） |
| JVM | OpenJDK 25.0.4.1（Microsoft build） |
| Gradle | 9.7.1 |
| 词典规模 | 30003 条（d5616bb 内置 pinyin_dict.tsv） |
| 参考设备 | 22021211RC / Android 16（P0 设备记录；JVM 延迟仅作参考，真机延迟要求由 P0 文档负责） |

基线专门在独立 worktree 运行，避免主工作区里 engine-dev 正在修改的 `PinyinEngine.kt` 污染“改进前”数据。主工作区仅用于 engine-dev 落地后的 after 评测。

## 5. 基线结果（d5616bb）

### 5.1 总览

| 数据集 | 口径 | 用例数 | top1 | top5 | 平均 taps | 总 taps |
|---|---|---:|---:|---:|---:|---:|
| 开发集 | 全量（含交互项） | 132 | 53/132 = 40.2% | 73/132 = 55.3% | 4.80 | 634 |
| 开发集 | **引擎可评分** | 119 | 51/119 = 42.9% | 71/119 = 59.7% | 4.48 | 533 |
| 保留集 | 全量（含交互项） | 132 | 41/132 = 31.1% | 62/132 = 47.0% | 5.45 | 719 |
| 保留集 | **引擎可评分** | 119 | 40/119 = 33.6% | 61/119 = 51.3% | 5.14 | 612 |

### 5.2 按类别拆分（dev / holdout）

| 类别 | dev top1 | dev top5 | dev 平均taps | holdout top1 | holdout top5 | holdout 平均taps |
|---|---:|---:|---:|---:|---:|---:|
| daily_chat 日常聊天 | 5/12 | 9/12 | 3.92 | 4/12 | 7/12 | 4.58 |
| time_place 时间地点 | 7/12 | 11/12 | 2.00 | 5/12 | 10/12 | 3.00 |
| work_comm 工作沟通 | 7/12 | 9/12 | 3.25 | 0/12 | 2/12 | 7.92 |
| unseen_sentence 未收录整句/多种表达 | 7/12 | 9/12 | 3.25 | 6/12 | 9/12 | 3.25 |
| homophone 同音词歧义 | 12/12 | 12/12 | 1.00 | 11/12 | 11/12 | 1.50 |
| long_segment 长句和分段选词 | 1/12 | 2/12 | 7.75 | 2/12 | 2/12 | 7.67 |
| mixed_en 中英文混输（交互项） | 2/12 | 2/12 | 7.67 | 1/12 | 1/12 | 8.17 |
| number_punct 数字与标点 | 4/12 | 7/12 | 4.67 | 6/12 | 8/12 | 4.00 |
| shorthand 简拼 | 0/12 | 0/12 | 9.00 | 0/12 | 0/12 | 9.00 |
| mixed_pinyin 混拼 | 8/12 | 12/12 | 1.33 | 6/12 | 12/12 | 1.83 |
| typo 常见错拼 | 0/12 | 0/12 | 9.00 | 0/12 | 0/12 | 9.00 |

说明：mixed_en 全部为交互项，number_punct 中各有 1 条阿拉伯数字交互项，故这两行的 top1/top5 属于 all 口径；其余类别均为引擎可评分。

### 5.3 延迟（JVM 参考值）

| 数据集 | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| 开发集 132 次查询 | 4.372 ms | 17.054 ms | 19.296 ms | 26.587 ms |
| 保留集 132 次查询 | 2.607 ms | 7.657 ms | 12.968 ms | 14.571 ms |

JVM 值受机器负载、JIT 和测试进程影响，只用于横向回归（同一机器 before/after）；不能替代 22021211RC / Android 16 真机按键延迟，真机数据以 P0 文档为准。

### 5.4 失败原因数量（未进前五）

| 原因 | 开发集 | 保留集 |
|---|---:|---:|
| 缺词 | 0 | 0 |
| 切分 | 13 | 18 |
| 排序 | 11 | 16 |
| 交互（不计入引擎） | 11 | 12 |
| 容错 | 24 | 24 |
| 合计失败 | 59 | 70 |

### 5.5 基线观察

- **短句/常用词**：daily_chat、time_place、unseen_sentence 的 top1 约 33%–58%，日常高频词基本可用；homophone 12/12、11/12 说明单字/双字同音候选覆盖良好。
- **长句与未收录整句**：long_segment top1 仅 1/12、2/12，且两条长输入直接返回空候选（解码步数预算/路径爆炸），是 P1 的主要短板。
- **工作沟通**：保留集 work_comm top1 0/12、top5 2/12，明显弱于开发集，说明书面语/多名词组合泛化不足（非语料泄漏）。
- **简拼/错拼**：baseline 无通用简拼索引、无轻量纠错，shorthand 与 typo 均 0/12（对应 P2 项）；mixed_pinyin top1 8/12、6/12，top5 12/12、12/12，说明部分混拼被前缀/短语命中，但没有通用索引保证。
- **失败结构**：缺词 0，失败集中在切分（13/18）与排序（11/16）；即词典并不缺词，瓶颈是通用音节切分、短语组合和排序。交互项 11/12 属于 mixed_en/数字原文路径，不计入引擎结论。
- **性能**：JVM p50 约 3–4 ms；长句最慢单次 20+ ms，P1 改进时需关注长输入步数预算。

## 6. 开发集逐条结果

> 以下为开发集全部 132 条（可用于 engine-dev 调参/回归，不含保留集内容）。`result` 中 TOPn 表示期望答案排名第 n，MISS 表示未进前五；rank 列 -1 为 MISS。

| id | 类别 | 输入 | 期望 | 结果 | 排名 | 选词次数 | 失败原因 |
|---|---|---|---|---|---:|---:|---|
| dev-daily_chat-01 | daily_chat | nichifanlema | 你吃饭了吗 | TOP1 | 1 | 1 | — |
| dev-daily_chat-02 | daily_chat | wogangxiaban | 我刚下班 | TOP1 | 1 | 1 | — |
| dev-daily_chat-03 | daily_chat | wanshangyiqichifanba | 晚上一起吃饭吧 | TOP5 | 5 | 5 | — |
| dev-daily_chat-04 | daily_chat | mingtianyoukongma | 明天有空吗 | TOP1 | 1 | 1 | — |
| dev-daily_chat-05 | daily_chat | haodemeiwenti | 好的没问题 | TOP1 | 1 | 1 | — |
| dev-daily_chat-06 | daily_chat | womashangdaojia | 我马上到家 | TOP2 | 2 | 2 | — |
| dev-daily_chat-07 | daily_chat | jintianhaoleia | 今天好累啊 | TOP4 | 4 | 4 | — |
| dev-daily_chat-08 | daily_chat | nixianshuiba | 你先睡吧 | MISS | — | 9 | 切分 |
| dev-daily_chat-09 | daily_chat | zhoumoquguangjiema | 周末去逛街吗 | MISS | — | 9 | 排序 |
| dev-daily_chat-10 | daily_chat | zhezhoumoniyouanpaima | 这周末你有安排吗 | MISS | — | 9 | 切分 |
| dev-daily_chat-11 | daily_chat | wozailushangle | 我在路上了 | TOP1 | 1 | 1 | — |
| dev-daily_chat-12 | daily_chat | biezhaojimanmanlai | 别着急慢慢来 | TOP4 | 4 | 4 | — |
| dev-time_place-13 | time_place | mingtianzaoshangbadian | 明天早上八点 | TOP1 | 1 | 1 | — |
| dev-time_place-14 | time_place | xiawusandiankaihui | 下午三点开会 | TOP1 | 1 | 1 | — |
| dev-time_place-15 | time_place | wanshangqidianban | 晚上七点半 | TOP2 | 2 | 2 | — |
| dev-time_place-16 | time_place | houtianzhongwu | 后天中午 | TOP2 | 2 | 2 | — |
| dev-time_place-17 | time_place | xiazhousanxiawu | 下周三下午 | TOP1 | 1 | 1 | — |
| dev-time_place-18 | time_place | zaibeijingxizhan | 在北京西站 | TOP1 | 1 | 1 | — |
| dev-time_place-19 | time_place | shanghaipudongjichang | 上海浦东机场 | TOP1 | 1 | 1 | — |
| dev-time_place-20 | time_place | tushuguanzainaer | 图书馆在哪儿 | TOP1 | 1 | 1 | — |
| dev-time_place-21 | time_place | huiyishizaiwulou | 会议室在五楼 | MISS | — | 9 | 排序 |
| dev-time_place-22 | time_place | zaoshangjiudianchufa | 早上九点出发 | TOP1 | 1 | 1 | — |
| dev-time_place-23 | time_place | mingtianshangwushidianban | 明天上午十点半 | TOP2 | 2 | 2 | — |
| dev-time_place-24 | time_place | ditiezhanzenmezou | 地铁站怎么走 | TOP2 | 2 | 2 | — |
| dev-work_comm-25 | work_comm | zhegefanganxuyaoxiugai | 这个方案需要修改 | TOP1 | 1 | 1 | — |
| dev-work_comm-26 | work_comm | qingbawenjianfageiwo | 请把文件发给我 | TOP1 | 1 | 1 | — |
| dev-work_comm-27 | work_comm | xiangmujinduzenmeyang | 项目进度怎么样 | TOP2 | 2 | 2 | — |
| dev-work_comm-28 | work_comm | mingtianshangwukaipingshenhui | 明天上午开评审会 | TOP1 | 1 | 1 | — |
| dev-work_comm-29 | work_comm | zhefenbaogaoxiewanmeiyou | 这份报告写完没有 | MISS | — | 9 | 切分 |
| dev-work_comm-30 | work_comm | youjianwoyijinghuifule | 邮件我已经回复了 | MISS | — | 9 | 排序 |
| dev-work_comm-31 | work_comm | xuqiuwendanggengxinyixia | 需求文档更新一下 | TOP1 | 1 | 1 | — |
| dev-work_comm-32 | work_comm | kehuxiawuguolai | 客户下午过来 | TOP1 | 1 | 1 | — |
| dev-work_comm-33 | work_comm | huiyigaidaozhousi | 会议改到周四 | MISS | — | 9 | 排序 |
| dev-work_comm-34 | work_comm | ceshihuanjingbushuhaole | 测试环境部署好了 | TOP1 | 1 | 1 | — |
| dev-work_comm-35 | work_comm | zhegegongnengshenmeshihoushangxian | 这个功能什么时候上线 | TOP3 | 3 | 3 | — |
| dev-work_comm-36 | work_comm | yusuanhaixuyaoqueren | 预算还需要确认 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-37 | unseen_sentence | jintianzhongwuchishenme | 今天中午吃什么 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-38 | unseen_sentence | wojuedezheyangbutaihao | 我觉得这样不太好 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-39 | unseen_sentence | ninengbangwogemangma | 你能帮我个忙吗 | TOP3 | 3 | 3 | — |
| dev-unseen_sentence-40 | unseen_sentence | zhegewentiyoudianfuza | 这个问题有点复杂 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-41 | unseen_sentence | tamenyijingzailushangle | 他们已经在路上了 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-42 | unseen_sentence | womenyiqixiangbanfa | 我们一起想办法 | TOP2 | 2 | 2 | — |
| dev-unseen_sentence-43 | unseen_sentence | zhejianshiwolaichuli | 这件事我来处理 | MISS | — | 9 | 排序 |
| dev-unseen_sentence-44 | unseen_sentence | nixiankankanzaishuo | 你先看看再说 | MISS | — | 9 | 切分 |
| dev-unseen_sentence-45 | unseen_sentence | wokenenglaibujile | 我可能来不及了 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-46 | unseen_sentence | waimianhaoxiangyaoxiayule | 外面好像要下雨了 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-47 | unseen_sentence | tashuodehenyoudaoli | 他说得很有道理\|他说的很有道理 | TOP1 | 1 | 1 | — |
| dev-unseen_sentence-48 | unseen_sentence | womenhuangedifangliaoba | 我们换个地方聊吧 | MISS | — | 9 | 排序 |
| dev-homophone-49 | homophone | shijian | 时间\|事件\|实践\|世间 | TOP1 | 1 | 1 | — |
| dev-homophone-50 | homophone | yishi | 意识\|仪式\|一时\|议事 | TOP1 | 1 | 1 | — |
| dev-homophone-51 | homophone | gongshi | 公式\|公示\|攻势\|工事 | TOP1 | 1 | 1 | — |
| dev-homophone-52 | homophone | qishi | 其实\|骑士\|启示\|歧视 | TOP1 | 1 | 1 | — |
| dev-homophone-53 | homophone | jishi | 及时\|即使\|技师\|记事 | TOP1 | 1 | 1 | — |
| dev-homophone-54 | homophone | zhongshi | 重视\|忠实\|中式 | TOP1 | 1 | 1 | — |
| dev-homophone-55 | homophone | shishi | 事实\|实施\|试试\|事事 | TOP1 | 1 | 1 | — |
| dev-homophone-56 | homophone | xiangmu | 项目\|橡木 | TOP1 | 1 | 1 | — |
| dev-homophone-57 | homophone | gongyi | 工艺\|公益\|公意\|工蚁 | TOP1 | 1 | 1 | — |
| dev-homophone-58 | homophone | baoming | 报名\|保命 | TOP1 | 1 | 1 | — |
| dev-homophone-59 | homophone | shangwu | 上午\|商务 | TOP1 | 1 | 1 | — |
| dev-homophone-60 | homophone | difang | 地方\|提防 | TOP1 | 1 | 1 | — |
| dev-long_segment-61 | long_segment | wojintianxiawuyaoqugongsicanjiayigezhongyaodehuiyi | 我今天下午要去公司参加一个重要的会议 | TOP1 | 1 | 1 | — |
| dev-long_segment-62 | long_segment | mingtianshangwushidianwomenzaihuiyishitaolunxiangmujindu | 明天上午十点我们在会议室讨论项目进度 | MISS | — | 9 | 切分 |
| dev-long_segment-63 | long_segment | zhegewentiwomenyijingtaolunguohenduocile | 这个问题我们已经讨论过很多次了 | TOP2 | 2 | 2 | — |
| dev-long_segment-64 | long_segment | ninengbunengbangwokankanzhefenbaogaonaliyouwenti | 你能不能帮我看看这份报告哪里有问题 | MISS | — | 9 | 排序 |
| dev-long_segment-65 | long_segment | ruguomingtiantianqihaodehuawomenjiuqugongyuansanbu | 如果明天天气好的话我们就去公园散步 | MISS | — | 9 | 切分 |
| dev-long_segment-66 | long_segment | wozuotianzaitushuguanjieleliangbenshu | 我昨天在图书馆借了两本书 | MISS | — | 9 | 切分 |
| dev-long_segment-67 | long_segment | zhegezhoumowoxiangdaijiarenqujiaoquwanyitian | 这个周末我想带家人去郊区玩一天 | MISS | — | 9 | 切分 |
| dev-long_segment-68 | long_segment | qingnibahuiyijiyaohexiazhoujihuayiqifageiwo | 请你把会议纪要和下周计划一起发给我 | MISS | — | 9 | 排序 |
| dev-long_segment-69 | long_segment | womenxianquerenxuqiufanweizaianpaikaifapaiqi | 我们先确认需求范围再安排开发排期 | MISS | — | 9 | 切分 |
| dev-long_segment-70 | long_segment | tashuoxiageyueyaoqushanghaichuchaiyigexingqi | 他说下个月要去上海出差一个星期 | MISS | — | 9 | 切分 |
| dev-long_segment-71 | long_segment | jintianxiabanyihouwomenyiqiquchaoshimaidiandongxi | 今天下班以后 | MISS | — | 9 | 切分 |
| dev-long_segment-72 | long_segment | mingtianshangwuwuihuibaxiugaihoudefanganfadaonideyouxiang | 明天上午 | MISS | — | 9 | 切分 |
| dev-mixed_en-73 | mixed_en | woyaomaiiphone | 我要买iPhone | MISS | — | 9 | 交互 |
| dev-mixed_en-74 | mixed_en | mingtianfanipdf | 明天发你PDF | MISS | — | 9 | 交互 |
| dev-mixed_en-75 | mixed_en | woxuyaoxiazaiyigeapp | 我需要下载一个APP | MISS | — | 9 | 交互 |
| dev-mixed_en-76 | mixed_en | zhegebanbenyijingrelease | 这个版本已经release | MISS | — | 9 | 交互 |
| dev-mixed_en-77 | mixed_en | womenyongpython | 我们用Python | MISS | — | 9 | 交互 |
| dev-mixed_en-78 | mixed_en | zhegeapphenhao | 这个 | TOP1 | 1 | 1 | — |
| dev-mixed_en-79 | mixed_en | nizaislackshanghuifuyixia | 你在 | MISS | — | 9 | 交互 |
| dev-mixed_en-80 | mixed_en | jidecheckyoujian | 记得 | TOP1 | 1 | 1 | — |
| dev-mixed_en-81 | mixed_en | huiyigaidaoteamsjinxing | 会议改到 | MISS | — | 9 | 交互 |
| dev-mixed_en-82 | mixed_en | zhegegongnengzaiiosshangyoudianwenti | 这个功能在 | MISS | — | 9 | 交互 |
| dev-mixed_en-83 | mixed_en | womingtianyaokaihuireview | 我明天要开会review | MISS | — | 9 | 交互 |
| dev-mixed_en-84 | mixed_en | womenxiankankanfeedback | 我们先看看feedback | MISS | — | 9 | 交互 |
| dev-number_punct-85 | number_punct | sanshifenzhonghou | 三十分钟后 | TOP2 | 2 | 2 | — |
| dev-number_punct-86 | number_punct | erbaiwushikuaiqian | 二百五十块钱 | MISS | — | 9 | 排序 |
| dev-number_punct-87 | number_punct | baifenzhijiushi | 百分之九十 | MISS | — | 9 | 排序 |
| dev-number_punct-88 | number_punct | shiyueyihao | 十月一号 | TOP2 | 2 | 2 | — |
| dev-number_punct-89 | number_punct | diyici | 第一次 | TOP1 | 1 | 1 | — |
| dev-number_punct-90 | number_punct | sidianlingwufen | 四点零五分 | MISS | — | 9 | 排序 |
| dev-number_punct-91 | number_punct | mingtian10dianjian | 明天 | MISS | — | 9 | 交互 |
| dev-number_punct-92 | number_punct | zhegeshoujihualeliangqianduokuai | 这个手机花了两千多块 | MISS | — | 9 | 切分 |
| dev-number_punct-93 | number_punct | wozaichezhandengleershifenzhong | 我在车站等了二十分钟 | TOP3 | 3 | 3 | — |
| dev-number_punct-94 | number_punct | xi'an | 西安 | TOP1 | 1 | 1 | — |
| dev-number_punct-95 | number_punct | tian'anmen | 天安门 | TOP1 | 1 | 1 | — |
| dev-number_punct-96 | number_punct | nihao, | 你好 | TOP1 | 1 | 1 | — |
| dev-shorthand-97 | shorthand | nh | 你好 | MISS | — | 9 | 容错 |
| dev-shorthand-98 | shorthand | wm | 我们 | MISS | — | 9 | 容错 |
| dev-shorthand-99 | shorthand | nm | 你们 | MISS | — | 9 | 容错 |
| dev-shorthand-100 | shorthand | tm | 他们 | MISS | — | 9 | 容错 |
| dev-shorthand-101 | shorthand | zj | 再见 | MISS | — | 9 | 容错 |
| dev-shorthand-102 | shorthand | xx | 谢谢 | MISS | — | 9 | 容错 |
| dev-shorthand-103 | shorthand | sm | 什么 | MISS | — | 9 | 容错 |
| dev-shorthand-104 | shorthand | zm | 怎么 | MISS | — | 9 | 容错 |
| dev-shorthand-105 | shorthand | wsm | 为什么 | MISS | — | 9 | 容错 |
| dev-shorthand-106 | shorthand | hh | 哈哈 | MISS | — | 9 | 容错 |
| dev-shorthand-107 | shorthand | bb | 拜拜 | MISS | — | 9 | 容错 |
| dev-shorthand-108 | shorthand | kx | 开心 | MISS | — | 9 | 容错 |
| dev-mixed_pinyin-109 | mixed_pinyin | zhongg | 中国 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-110 | mixed_pinyin | beij | 北京 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-111 | mixed_pinyin | shangh | 上海 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-112 | mixed_pinyin | mingt | 明天 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-113 | mixed_pinyin | jint | 今天 | TOP2 | 2 | 2 | — |
| dev-mixed_pinyin-114 | mixed_pinyin | pengy | 朋友 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-115 | mixed_pinyin | dianh | 电话 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-116 | mixed_pinyin | gongz | 工作 | TOP1 | 1 | 1 | — |
| dev-mixed_pinyin-117 | mixed_pinyin | xuex | 学习 | TOP2 | 2 | 2 | — |
| dev-mixed_pinyin-118 | mixed_pinyin | wans | 晚上 | TOP2 | 2 | 2 | — |
| dev-mixed_pinyin-119 | mixed_pinyin | kaix | 开心 | TOP2 | 2 | 2 | — |
| dev-mixed_pinyin-120 | mixed_pinyin | chif | 吃饭 | TOP1 | 1 | 1 | — |
| dev-typo-121 | typo | nhao | 你好 | MISS | — | 9 | 容错 |
| dev-typo-122 | typo | mintian | 明天 | MISS | — | 9 | 容错 |
| dev-typo-123 | typo | daja | 大家 | MISS | — | 9 | 容错 |
| dev-typo-124 | typo | woyaoo | 我要 | MISS | — | 9 | 容错 |
| dev-typo-125 | typo | jintiann | 今天 | MISS | — | 9 | 容错 |
| dev-typo-126 | typo | zaijiaan | 再见 | MISS | — | 9 | 容错 |
| dev-typo-127 | typo | nihoa | 你好 | MISS | — | 9 | 容错 |
| dev-typo-128 | typo | pengyuo | 朋友 | MISS | — | 9 | 容错 |
| dev-typo-129 | typo | shijain | 时间 | MISS | — | 9 | 容错 |
| dev-typo-130 | typo | nihap | 你好 | MISS | — | 9 | 容错 |
| dev-typo-131 | typo | mingtiab | 明天 | MISS | — | 9 | 容错 |
| dev-typo-132 | typo | gongzui | 工作 | MISS | — | 9 | 容错 |

### 6.1 开发集失败详情（59 条，前五候选）

| id | 类别 | 输入 | 期望 | 结果 | taps | 原因 | 实际前五 |
|---|---|---|---|---:|---|---|---|
| dev-daily_chat-08 | daily_chat | nixianshuiba | 你先睡吧 | MISS | 9 | 切分 | 你西安谁把\|你西安水把\|你西安睡把\|你西安谁吧\|你西安水吧 |
| dev-daily_chat-09 | daily_chat | zhoumoquguangjiema | 周末去逛街吗 | MISS | 9 | 排序 | 周末去光接吗\|周末去光接马\|周末去光街吗\|周末去光及鄂吗\|周末去光街马 |
| dev-daily_chat-10 | daily_chat | zhezhoumoniyouanpaima | 这周末你有安排吗 | MISS | 9 | 切分 | 褶皱模拟有安排吗\|褶皱模拟有安排马\|褶皱模拟又安排吗\|褶皱模拟又安排马\|褶皱模拟有安排骂 |
| dev-time_place-21 | time_place | huiyishizaiwulou | 会议室在五楼 | MISS | 9 | 排序 | 会议实在无楼\|会议实在五楼\|会议实在无剅\|会议实在无搂\|会议实在物楼 |
| dev-work_comm-29 | work_comm | zhefenbaogaoxiewanmeiyou | 这份报告写完没有 | MISS | 9 | 切分 | 这份报告西鄂皖没有\|这份报告系鄂皖没有\|这份报告喜鄂皖没有\|这份报告浠鄂皖没有\|这份报告细鄂皖没有 |
| dev-work_comm-30 | work_comm | youjianwoyijinghuifule | 邮件我已经回复了 | MISS | 9 | 排序 | 有机按我已经恢复了\|邮件我已经恢复了\|有机安我已经恢复了\|游击按我已经恢复了\|有机暗我已经恢复了 |
| dev-work_comm-33 | work_comm | huiyigaidaozhousi | 会议改到周四 | MISS | 9 | 排序 | 会议该到宙斯\|会议该道宙斯\|会议该到周四\|会议该道周四\|会议改到宙斯 |
| dev-unseen_sentence-43 | unseen_sentence | zhejianshiwolaichuli | 这件事我来处理 | MISS | 9 | 排序 | 这件是我来处理\|这件时我来处理\|这件使我来处理\|这监视我来处理\|这见识我来处理 |
| dev-unseen_sentence-44 | unseen_sentence | nixiankankanzaishuo | 你先看看再说 | MISS | 9 | 切分 | 你西安看看再说\|你西安看看在说\|泥西安看看再说\|尼西安看看再说\|拟西安看看再说 |
| dev-unseen_sentence-48 | unseen_sentence | womenhuangedifangliaoba | 我们换个地方聊吧 | MISS | 9 | 排序 | 我们换各地放疗把\|我们湖按各地放疗把\|我们环各地放疗把\|我们忽按各地放疗把\|我门户按各地放疗把 |
| dev-long_segment-62 | long_segment | mingtianshangwushidianwomenzaihuiyishitaolunxiangmujindu | 明天上午十点我们在会议室讨论项目进度 | MISS | 9 | 切分 | （无候选） |
| dev-long_segment-64 | long_segment | ninengbunengbangwokankanzhefenbaogaonaliyouwenti | 你能不能帮我看看这份报告哪里有问题 | MISS | 9 | 排序 | 你能不能浜我看看着分报告那里有问题\|你能不能帮我看看着分报告那里有问题\|你能不能邦我看看着分报告那里有问题\|你能不能浜我看看这份报告那里有问题\|你能不能帮我看看这份报告那里有问题 |
| dev-long_segment-65 | long_segment | ruguomingtiantianqihaodehuawomenjiuqugongyuansanbu | 如果明天天气好的话我们就去公园散步 | MISS | 9 | 切分 | （无候选） |
| dev-long_segment-66 | long_segment | wozuotianzaitushuguanjieleliangbenshu | 我昨天在图书馆借了两本书 | MISS | 9 | 切分 | 我做提案在图书关节了里昂本书\|我做提案再图书关节了里昂本书\|我昨天在图书关节了里昂本书\|我做提案在图书关节了两本书\|我做提案在图书故氨基鄂了里昂本书 |
| dev-long_segment-67 | long_segment | zhegezhoumowoxiangdaijiarenqujiaoquwanyitian | 这个周末我想带家人去郊区玩一天 | MISS | 9 | 切分 | 这个周末我想代价人去郊区万一提案\|这个周末我想代价人区郊区万一提案\|这个周末我想代价人去郊区玩意提案\|这个周末我想代价人取郊区万一提案\|这个周末我想带家人去郊区万一提案 |
| dev-long_segment-68 | long_segment | qingnibahuiyijiyaohexiazhoujihuayiqifageiwo | 请你把会议纪要和下周计划一起发给我 | MISS | 9 | 排序 | 请你把会议纪要河西啊州计划一起发给我\|清你把会议纪要河西啊州计划一起发给我\|请你把会议纪要和下州计划一起发给我\|请你把会议纪要河西啊周计划一起发给我\|清你把会议纪要和下州计划一起发给我 |
| dev-long_segment-69 | long_segment | womenxianquerenxuqiufanweizaianpaikaifapaiqi | 我们先确认需求范围再安排开发排期 | MISS | 9 | 切分 | 我们西安确认需求范围在安排开发派其\|我们西安去恶人需求范围在安排开发派其\|我们西安确认需求范围在安排开发派起\|我们西安去恶人需求范围在安排开发派起\|我们西安确认需求范围在安排开发拍其 |
| dev-long_segment-70 | long_segment | tashuoxiageyueyaoqushanghaichuchaiyigexingqi | 他说下个月要去上海出差一个星期 | MISS | 9 | 切分 | 他说西阿哥余额要去上海出差一个兴起\|他说西阿哥余额要去上海出差一个星期\|他说系阿哥余额要去上海出差一个兴起\|他说喜阿哥余额要去上海出差一个兴起\|他说系阿哥余额要去上海出差一个星期 |
| dev-long_segment-71 | long_segment | jintianxiabanyihouwomenyiqiquchaoshimaidiandongxi | 今天下班以后 | MISS | 9 | 切分 | 今天下班以后我们一起去潮湿买地按东西\|今天下班以后我们一起去超市买地按东西\|今天下班以后我们一起去潮湿卖地按东西\|今天下班以后我们一起去超市卖地按东西\|今天下班以后我们一起去潮湿买地安东西 |
| dev-long_segment-72 | long_segment | mingtianshangwuwuihuibaxiugaihoudefanganfadaonideyouxiang | 明天上午 | MISS | 9 | 切分 | 明天\|命题\|名\|明\|命 |
| dev-mixed_en-73 | mixed_en | woyaomaiiphone | 我要买iPhone | MISS | 9 | 交互 | 我\|握\|窝\|卧\|斡 |
| dev-mixed_en-74 | mixed_en | mingtianfanipdf | 明天发你PDF | MISS | 9 | 交互 | 明天\|命题\|名\|明\|命 |
| dev-mixed_en-75 | mixed_en | woxuyaoxiazaiyigeapp | 我需要下载一个APP | MISS | 9 | 交互 | 我\|握\|窝\|卧\|斡 |
| dev-mixed_en-76 | mixed_en | zhegebanbenyijingrelease | 这个版本已经release | MISS | 9 | 交互 | 这个版本已经热了啊色\|这个版本已经热了阿色\|这个版本已经惹了啊色\|这个版本已经热了啊繬\|这个版本已经热了啊濇 |
| dev-mixed_en-77 | mixed_en | womenyongpython | 我们用Python | MISS | 9 | 交互 | 我们\|我\|握\|窝\|卧 |
| dev-mixed_en-79 | mixed_en | nizaislackshanghuifuyixia | 你在 | MISS | 9 | 交互 | 你\|泥\|尼\|拟\|逆 |
| dev-mixed_en-81 | mixed_en | huiyigaidaoteamsjinxing | 会议改到 | MISS | 9 | 交互 | 会议\|回忆\|会意\|会\|回 |
| dev-mixed_en-82 | mixed_en | zhegegongnengzaiiosshangyoudianwenti | 这个功能在 | MISS | 9 | 交互 | 这个\|这\|着\|者\|折 |
| dev-mixed_en-83 | mixed_en | womingtianyaokaihuireview | 我明天要开会review | MISS | 9 | 交互 | 我\|握\|窝\|卧\|斡 |
| dev-mixed_en-84 | mixed_en | womenxiankankanfeedback | 我们先看看feedback | MISS | 9 | 交互 | 我们\|我\|握\|窝\|卧 |
| dev-number_punct-86 | number_punct | erbaiwushikuaiqian | 二百五十块钱 | MISS | 9 | 排序 | 二百武士快前\|二百无石块前\|二百武士快其按\|二百五十快前\|二百务实快前 |
| dev-number_punct-87 | number_punct | baifenzhijiushi | 百分之九十 | MISS | 9 | 排序 | 百分之就是\|百分只就是\|百分至就是\|白分支就是\|百分直就是 |
| dev-number_punct-90 | number_punct | sidianlingwufen | 四点零五分 | MISS | 9 | 排序 | 四点另五分\|四点令五分\|四点领悟分\|四点领五分\|四点灵五分 |
| dev-number_punct-91 | number_punct | mingtian10dianjian | 明天 | MISS | 9 | 交互 | 明天地案件\|命题按地案件\|明天第案件\|明天点见\|名天地案件 |
| dev-number_punct-92 | number_punct | zhegeshoujihualeliangqianduokuai | 这个手机花了两千多块 | MISS | 9 | 切分 | 这个手计划了里昂迁都哦快\|这个受计划了里昂迁都哦快\|这个首计划了里昂迁都哦快\|这个手计划了里昂迁都哦块\|这个手机话了里昂迁都哦快 |
| dev-shorthand-97 | shorthand | nh | 你好 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-98 | shorthand | wm | 我们 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-99 | shorthand | nm | 你们 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-100 | shorthand | tm | 他们 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-101 | shorthand | zj | 再见 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-102 | shorthand | xx | 谢谢 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-103 | shorthand | sm | 什么 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-104 | shorthand | zm | 怎么 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-105 | shorthand | wsm | 为什么 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-106 | shorthand | hh | 哈哈 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-107 | shorthand | bb | 拜拜 | MISS | 9 | 容错 | （无候选） |
| dev-shorthand-108 | shorthand | kx | 开心 | MISS | 9 | 容错 | （无候选） |
| dev-typo-121 | typo | nhao | 你好 | MISS | 9 | 容错 | 嗯好\|嗯号\|嗯灏\|嗯豪\|嗯浩 |
| dev-typo-122 | typo | mintian | 明天 | MISS | 9 | 容错 | 民提案\|米嗯提案\|敏提案\|闽提案\|抿提案 |
| dev-typo-123 | typo | daja | 大家 | MISS | 9 | 容错 | 大\|打\|达\|答\|搭 |
| dev-typo-124 | typo | woyaoo | 我要 | MISS | 9 | 容错 | 我要哦\|我要噢\|我要喔\|我药哦\|我咬哦 |
| dev-typo-125 | typo | jintiann | 今天 | MISS | 9 | 容错 | 今天嗯\|进提案嗯\|今提案嗯\|近提案嗯\|金提案嗯 |
| dev-typo-126 | typo | zaijiaan | 再见 | MISS | 9 | 容错 | 在家按\|在家安\|在家暗\|在家案\|在家氨 |
| dev-typo-127 | typo | nihoa | 你好 | MISS | 9 | 容错 | 你\|泥\|尼\|拟\|逆 |
| dev-typo-128 | typo | pengyuo | 朋友 | MISS | 9 | 容错 | 捧与哦\|捧于哦\|碰与哦\|砰与哦\|碰于哦 |
| dev-typo-129 | typo | shijain | 时间 | MISS | 9 | 容错 | 是\|时\|使\|事\|市 |
| dev-typo-130 | typo | nihap | 你好 | MISS | 9 | 容错 | 你\|泥\|尼\|拟\|逆 |
| dev-typo-131 | typo | mingtiab | 明天 | MISS | 9 | 容错 | 命题\|名\|明\|命\|鸣 |
| dev-typo-132 | typo | gongzui | 工作 | MISS | 9 | 容错 | 共最\|供最\|宫最\|功最\|公最 |

### 6.2 最严重 15 条（供 Lead 快速判断）

| id | 期望 | 输入 | 实际前五 | 原因 |
|---|---|---|---|---|
| dev-long_segment-62 | 明天上午十点我们在会议室讨论项目进度 | mingtianshangwushidianwomenzaihuiyishitaolunxiangmujindu | （无候选） | 切分 |
| dev-long_segment-65 | 如果明天天气好的话我们就去公园散步 | ruguomingtiantianqihaodehuawomenjiuqugongyuansanbu | （无候选） | 切分 |
| dev-long_segment-64 | 你能不能帮我看看这份报告哪里有问题 | ninengbunengbangwokankanzhefenbaogaonaliyouwenti | 你能不能浜我看看着分报告那里有问题\|你能不能帮我看看着分报告那里有问题\|你能不能邦我看看着分报告那里有问题\|你能不能浜我看看这份报告那里有问题\|你能不能帮我看看这份报告那里有问题 | 排序 |
| dev-long_segment-68 | 请你把会议纪要和下周计划一起发给我 | qingnibahuiyijiyaohexiazhoujihuayiqifageiwo | 请你把会议纪要河西啊州计划一起发给我\|清你把会议纪要河西啊州计划一起发给我\|请你把会议纪要和下州计划一起发给我\|请你把会议纪要河西啊周计划一起发给我\|清你把会议纪要和下州计划一起发给我 | 排序 |
| dev-long_segment-69 | 我们先确认需求范围再安排开发排期 | womenxianquerenxuqiufanweizaianpaikaifapaiqi | 我们西安确认需求范围在安排开发派其\|我们西安去恶人需求范围在安排开发派其\|我们西安确认需求范围在安排开发派起\|我们西安去恶人需求范围在安排开发派起\|我们西安确认需求范围在安排开发拍其 | 切分 |
| dev-long_segment-67 | 这个周末我想带家人去郊区玩一天 | zhegezhoumowoxiangdaijiarenqujiaoquwanyitian | 这个周末我想代价人去郊区万一提案\|这个周末我想代价人区郊区万一提案\|这个周末我想代价人去郊区玩意提案\|这个周末我想代价人取郊区万一提案\|这个周末我想带家人去郊区万一提案 | 切分 |
| dev-long_segment-70 | 他说下个月要去上海出差一个星期 | tashuoxiageyueyaoqushanghaichuchaiyigexingqi | 他说西阿哥余额要去上海出差一个兴起\|他说西阿哥余额要去上海出差一个星期\|他说系阿哥余额要去上海出差一个兴起\|他说喜阿哥余额要去上海出差一个兴起\|他说系阿哥余额要去上海出差一个星期 | 切分 |
| dev-long_segment-66 | 我昨天在图书馆借了两本书 | wozuotianzaitushuguanjieleliangbenshu | 我做提案在图书关节了里昂本书\|我做提案再图书关节了里昂本书\|我昨天在图书关节了里昂本书\|我做提案在图书关节了两本书\|我做提案在图书故氨基鄂了里昂本书 | 切分 |
| dev-daily_chat-08 | 你先睡吧 | nixianshuiba | 你西安谁把\|你西安水把\|你西安睡把\|你西安谁吧\|你西安水吧 | 切分 |
| dev-daily_chat-10 | 这周末你有安排吗 | zhezhoumoniyouanpaima | 褶皱模拟有安排吗\|褶皱模拟有安排马\|褶皱模拟又安排吗\|褶皱模拟又安排马\|褶皱模拟有安排骂 | 切分 |
| dev-work_comm-29 | 这份报告写完没有 | zhefenbaogaoxiewanmeiyou | 这份报告西鄂皖没有\|这份报告系鄂皖没有\|这份报告喜鄂皖没有\|这份报告浠鄂皖没有\|这份报告细鄂皖没有 | 切分 |
| dev-work_comm-30 | 邮件我已经回复了 | youjianwoyijinghuifule | 有机按我已经恢复了\|邮件我已经恢复了\|有机安我已经恢复了\|游击按我已经恢复了\|有机暗我已经恢复了 | 排序 |
| dev-unseen_sentence-43 | 这件事我来处理 | zhejianshiwolaichuli | 这件是我来处理\|这件时我来处理\|这件使我来处理\|这监视我来处理\|这见识我来处理 | 排序 |
| dev-number_punct-92 | 这个手机花了两千多块 | zhegeshoujihualeliangqianduokuai | 这个手计划了里昂迁都哦快\|这个受计划了里昂迁都哦快\|这个首计划了里昂迁都哦快\|这个手计划了里昂迁都哦块\|这个手机话了里昂迁都哦快 | 切分 |
| dev-mixed_en-73 | 我要买iPhone | woyaomaiiphone | 我\|握\|窝\|卧\|斡 | 交互 |

## 7. 保留集结果（仅聚合，不泄漏句子）

保留集与开发集同分布、同数量（132 条），但具体句子不在本报告、也不向 engine-dev 提供。聚合如下：

| 指标 | 全量（含交互项） | 引擎可评分 |
|---|---:|---:|
| top1 | 41/132 = 31.1% | 40/119 = 33.6% |
| top5 | 62/132 = 47.0% | 61/119 = 51.3% |
| 平均 taps | 5.45 | 5.14 |
| p50 / p95 | 2.607 / 7.657 ms | — |
| 失败原因数量 | 排序 16、切分 18、交互 12、容错 24；缺词 0 | 同左（交互不计入引擎） |

## 8. 局限

1. **JVM 延迟 ≠ 真机延迟**：本报告延迟为 Windows 11 + OpenJDK 25 的 JVM 单元测试值，只能做同机 before/after 参考；真机 22021211RC / Android 16 的按键延迟以 P0 设备验收为准。
2. **多答案人工判定边界**：expected 只能枚举有限的合理写法；同音/同义但未列出的自然表达会被记为失败。我们为同音词和“的/得”等常见变体提供了多答案，但仍可能存在漏枚举。
3. **失败分类是启发式**：缺词/切分/排序由词典组合、字符重叠、同骨架前缀等规则自动分类，边界处存在人工判断余地；报告同时保留每条开发集失败的实际候选，便于复核。
4. **语料规模**：每类 12 条、每集 132 条，适合作为回归红线，但单类比例的置信区间较宽；不应把 1–2 条差异当作显著改善。
5. **交互项口径**：mixed_en 和阿拉伯数字用例测的是“中文前缀选词 + 原文提交”的交互路径，不是单次连续拼音整句；它们计入 all 指标、不计入 engine 指标。
6. **简拼/混拼/错拼基线为 0 是预期缺口**：baseline d5616bb 没有通用简拼索引和纠错，相关能力在 P2 任务范围；不能把 0/12 解读为这些输入方式本身不可用。
7. 拼音使用 pypinyin 0.55 校验，个别多音字读音与词典读音可能不同；此类差异会体现为失败，需按实际词典读音复核。

## 9. 复跑与对比

- 基线（本报告）：baseline worktree d5616bb，命令见 §3.1，设置 `PINYIN_EVAL_STAGE=baseline`。
- 改进后：engine-dev 落地后用主工作区同一评测器、同一语料，设置 `PINYIN_EVAL_STAGE=after`，输出 `build/pinyin-quality-eval-after.txt`，并在本文件追加 after 对比与失败变化。
- 对比时建议看：`SUMMARY_ENGINE` 行的 dev/holdout top1/top5/avgTaps，以及 §5.4 的失败原因数量变化；同一类别 top1/top5 用于定位回归/提升。

## 10. After 对比（engine-dev 冻结后，956b504）

### 10.1 环境与口径

- after 代码：主工作区 commit `956b504`（engine-dev 冻结版本），词典 **65,999 条 / 3,090,305 B**；baseline 为 d5616bb / 30,003 条。
- 最终 release APK（Lead 提供）：SHA-256 `A8D8AFE874A2304DDC92439A72EEEB449180F1C2C00E60EB2E300D2FE09FB0FC`，28,555,607 bytes；本报告评测在 JVM 单元测试运行，APK 仅用于标识代码冻结产物，真机验收见 P0。
- 同一评测器、同一语料、`limit=8`；after 运行：`$env:PINYIN_EVAL_STAGE='after'; .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --console=plain --rerun-tasks`。
- **注意**：after 同时包含词典扩容（30,003→65,999 条）与引擎解码/排序/简拼/纠错/混输改动，下表是整体效果，不拆分单项贡献。
- `engine` 口径仍排除 mixed_en 12 条/集 + 阿拉伯数字原文 1 条/集；保留集仅聚合。

### 10.2 总览（baseline → after）

| 数据集 | 口径 | baseline top1 | after top1 | Δ | baseline top5 | after top5 | Δ | baseline avgTaps | after avgTaps | Δ |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 开发集 | 全量（含交互项） | 40.2% | 65.2% | +25.0pp | 55.3% | 81.1% | +25.8pp | 4.80 | 2.69 | -2.11 |
| 开发集 | **引擎可评分** | 42.9% | 70.6% | +27.7pp | 59.7% | 83.2% | +23.5pp | 4.48 | 2.45 | -2.03 |
| 保留集 | 全量（含交互项） | 31.1% | 58.3% | +27.2pp | 47.0% | 76.5% | +29.5pp | 5.45 | 3.11 | -2.34 |
| 保留集 | **引擎可评分** | 33.6% | 63.9% | +30.3pp | 51.3% | 81.5% | +30.2pp | 5.14 | 2.76 | -2.38 |

### 10.3 按类别变化（开发集，top1 / top5 / avgTaps）

| 类别 | baseline top1 | after top1 | baseline top5 | after top5 | baseline avgTaps | after avgTaps |
|---|---:|---:|---:|---:|---:|---:|
| daily_chat 日常聊天 | 5/12 | 11/12 (+6) | 9/12 | 12/12 (+3) | 3.92 | 1.08 |
| time_place 时间地点 | 7/12 | 11/12 (+4) | 11/12 | 11/12 (+0) | 2.00 | 1.67 |
| work_comm 工作沟通 | 7/12 | 10/12 (+3) | 9/12 | 10/12 (+1) | 3.25 | 2.08 |
| unseen_sentence 未收录整句/多种表达 | 7/12 | 9/12 (+2) | 9/12 | 12/12 (+3) | 3.25 | 1.25 |
| homophone 同音词歧义 | 12/12 | 12/12 (+0) | 12/12 | 12/12 (+0) | 1.00 | 1.00 |
| long_segment 长句和分段选词 | 1/12 | 2/12 (+1) | 2/12 | 7/12 (+5) | 7.75 | 5.00 |
| mixed_en 中英文混输（交互项） | 2/12 | 2/12 (+0) | 2/12 | 8/12 (+6) | 7.67 | 4.50 |
| number_punct 数字与标点 | 4/12 | 8/12 (+4) | 7/12 | 9/12 (+2) | 4.67 | 3.08 |
| shorthand 简拼 | 0/12 | 6/12 (+6) | 0/12 | 8/12 (+8) | 9.00 | 3.58 |
| mixed_pinyin 混拼 | 8/12 | 9/12 (+1) | 12/12 | 12/12 (+0) | 1.33 | 1.33 |
| typo 常见错拼 | 0/12 | 6/12 (+6) | 0/12 | 6/12 (+6) | 9.00 | 5.00 |

### 10.4 按类别变化（保留集，仅聚合）

| 类别 | baseline top1 | after top1 | baseline top5 | after top5 | baseline avgTaps | after avgTaps |
|---|---:|---:|---:|---:|---:|---:|
| daily_chat 日常聊天 | 4/12 | 10/12 (+6) | 7/12 | 12/12 (+5) | 4.58 | 1.17 |
| time_place 时间地点 | 5/12 | 8/12 (+3) | 10/12 | 11/12 (+1) | 3.00 | 2.00 |
| work_comm 工作沟通 | 0/12 | 4/12 (+4) | 2/12 | 7/12 (+5) | 7.92 | 5.00 |
| unseen_sentence 未收录整句/多种表达 | 6/12 | 11/12 (+5) | 9/12 | 12/12 (+3) | 3.25 | 1.25 |
| homophone 同音词歧义 | 11/12 | 12/12 (+1) | 11/12 | 12/12 (+1) | 1.50 | 1.00 |
| long_segment 长句和分段选词 | 2/12 | 3/12 (+1) | 2/12 | 5/12 (+3) | 7.67 | 5.83 |
| mixed_en 中英文混输（交互项） | 1/12 | 1/12 (+0) | 1/12 | 4/12 (+3) | 8.17 | 6.08 |
| number_punct 数字与标点 | 6/12 | 9/12 (+3) | 8/12 | 10/12 (+2) | 4.00 | 2.42 |
| shorthand 简拼 | 0/12 | 5/12 (+5) | 0/12 | 8/12 (+8) | 9.00 | 4.08 |
| mixed_pinyin 混拼 | 6/12 | 6/12 (+0) | 12/12 | 12/12 (+0) | 1.83 | 1.67 |
| typo 常见错拼 | 0/12 | 8/12 (+8) | 0/12 | 8/12 (+8) | 9.00 | 3.67 |

### 10.5 延迟变化（JVM 参考值）

| 数据集 | baseline p50 / p95 | after p50 / p95 | 变化 |
|---|---:|---:|---|
| 开发集 132 次查询 | 4.372 / 17.054 ms | 0.899 / 3.160 ms | p50 -79.4%，p95 -81.5% |
| 保留集 132 次查询 | 2.607 / 7.657 ms | 0.754 / 2.948 ms | p50 -71.1%，p95 -61.5% |

JVM 值仅同机参考，不能替代 22021211RC / Android 16 真机按键延迟；after 词典更大（65,999 条）但 JVM 查询更快，说明索引/查询路径也有优化，仍需 P0 真机确认。

### 10.6 失败原因变化（未进前五）

| 原因 | 开发集 baseline → after | 保留集 baseline → after |
|---|---:|---:|
| 缺词 | 0 → 0 | 0 → 0 |
| 切分 | 13 → 1 | 18 → 4 |
| 排序 | 11 → 9 | 16 → 10 |
| 交互（不计入引擎） | 11 → 5 | 12 → 9 |
| 容错 | 24 → 10 | 24 → 8 |
| 合计失败 | 59 → 25 | 70 → 31 |

### 10.7 改进证据与回归

- 开发集 per-case 迁移：132 条中 **72 条前后都进前五、35 条由失败转为前五命中、24 条仍失败、1 条回归**（基线 59 条失败 → after 25 条失败）。
- 提升最大的类别：daily_chat（dev top1 +6、holdout +6）、unseen_sentence（holdout top1 +5、top5 +3）、shorthand（top5 +8/+8）、typo（top5 +6/+8）、work_comm（holdout top5 +5）、long_segment（top5 +5/+3）；切分失败从 13/18 降到 1/4，说明通用音节切分/短语解码是主要收益来源。
- **唯一回归**：`dev-work_comm-28`（明天上午开评审会）baseline TOP1 → after RANK6，实际候选变为“明天上午开平身会/开平身回…”。建议 engine-dev 检查词典扩容后“评审会”条目的拼音/词频是否被覆盖或降权。
- 保留集提升幅度（engine top1 +30.3pp、top5 +30.2pp）高于开发集，说明收益不是只对开发集过拟合；但保留集只作为一次冻结后验证，不参与调参。

### 10.8 仍未达标项

- **long_segment**：dev top1 2/12、top5 7/12；holdout top1 3/12、top5 5/12——仍是最大短板，长整句候选能进入前五但难以首选，长输入步数/路径预算仍需优化。
- **shorthand**：dev top1 6/12、holdout 5/12，top5 均 8/12；通用简拼索引已生效但排序/歧义（同声母多词）不足。
- **typo**：dev top1/top5 6/12（纠错候选要么第一、要么缺失），holdout top1/top5 8/12；轻量纠错覆盖仍不完整。
- **mixed_en 交互项**：dev top5 8/12、holdout 4/12，top1 2/12、1/12；中文前缀+原文英文尾路径不稳定，且本来不计入引擎指标。
- **work_comm**：holdout top1 4/12、top5 7/12，较 baseline 明显改善但仍是书面语最弱类别。
- **失败结构**：缺词仍为 0，剩余失败以“排序”为主（dev 9/25、holdout 10/31），即候选已存在但未进前五；下一步重点应是排序而非继续扩词。

### 10.9 保留集泄漏复查（最终冻结版本）

- `python eval/check_no_leak.py`：扫描 112 个文本文件、16,240 次检查；**source-leaks=0、dictionary-whole-sentence-hits=0**。
- `python eval/check_corpus.py`：264 条、11 类各 12、拼音/子类型/混拼规则检查 **errors=0**，词典读音告警 0。
- 结论：最终词典/源码/测试/文档中没有出现 holdout 句子或整句词条；本报告对保留集只列聚合与类别数字，不含具体句子。

### 10.10 产物

- after 报告：[build/pinyin-quality-eval-after.txt](../build/pinyin-quality-eval-after.txt)（开发集逐条结果 + 保留集聚合；FAIL/原因可复查）
- baseline 报告：[build/pinyin-quality-eval-baseline.txt](../build/pinyin-quality-eval-baseline.txt)
- 评测器：[PinyinQualityEvalTest.kt](../app/src/test/java/com/bitap/keyboard/core/PinyinQualityEvalTest.kt)；语料与自查脚本见文首。
