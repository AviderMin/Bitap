# 仓库审查与清理记录

日期：2026-11-08。开始时HEAD=`69ad648`、工作树干净；未发现适用的AGENTS.md。用户授权清理仓库、保留源码、重置docs并按进度重建需求与开发文档。

## 1. 保留和重建范围

- 保留生产实现、Android资源和词库、全部单测、评测集/manifest/工具/聚合快照、Gradle wrapper/构建配置、LICENSE和第三方声明。
- 原docs共234文件（47 Markdown、117 PNG、68 XML、2 TXT），混有早期MVP规范、多轮“最终报告”和设备dump。本轮重建为8份现行文档，不继续堆叠阶段报告；旧记录可经Git历史查询。
- Tatoeba作者署名迁至 `legal/TATOEBA_CONTRIBUTORS.txt`，数据来源重建为 `legal/DATA_PROVENANCE.md`，并更新声明、生成器及评测署名引用。
- 三个仅存在临时目录的设备HTML测试源文件提升至 `scripts/device-qa/fixtures/`，避免清理后设备脚本失去宿主页面。
- 统一 `scripts/verify.ps1`，旧阶段脚本保留转发；补D05/Python/链接检查并区分严格原件模式。
- 将设备脚本写入docs/evidence的默认位置改为忽略输出目录；清理只改来源引用/工具，不改变键盘算法、词库、冻结语料或权重。
- 设备工具的输出默认值改为从仓库位置解析，去掉当前机器的硬编码仓库路径；仍保留其已知机型/坐标限制。新增 `requirements-dev.txt` 记录两项Python数据工具依赖。

## 2. 本地生成物处理

自动审批拒绝了递归删除和逐项删除，工具仅返回`blocked by policy`，没有提供详细原因。之后采用可恢复移动，将旧docs以及日志、历史APK、构建/实验缓存移到Git忽略的 `.local/cleanup-backup-20261108/`，不再尝试永久删除。

因此本轮完成的是活跃工作区与版本库整理，**不是磁盘空间回收**。备份保留旧文件并占用空间，不作为正式文档/构建输入。`.git`不改写历史；模型、本机SDK配置和评测所需原始来源保留。具体移动清单和字节数由整理脚本在备份区记录。

实际收尾：Gradle daemon停止后，247个生成物目标全部移动成功，共2,044,134,719字节（约1.90GiB），另保留原docs234文件的备份。根目录旧日志、`build`、`app/build`、`dist`、`.gradle`、`.kotlin`及旧实验目录均已移出活跃位置。`.tmp`只保留 `cc0/`、`tatoeba/`、`jieba.txt`、`pypinyin_pkg/`；模型与 `local.properties` 原地保留。恢复清单为备份区 `moves.json`、`docs-reset.json`。

## 3. 本次审查的实质发现

1. 最新进度是D05扩充dev、词内权重0.50和小样本设备验证，不能停留在旧950条dev或阶段一状态。
2. D05当前dev1050条，33条超过64字符预算；排序用667自然句子集时必须同时报告33条预算失败。不同版本分数不可直接比较。
3. partial放第6位在真机增加1次滚动；面板6项/页但首屏只有4完整行。下一批优先候选面板与分段可发现性。
4. v2同训练源；v3新来源有1条重合，首次分数不属于当前参数；统计标签、冻结和因果解释继续保留限制。
5. AI native释放不等于所有PSS场景过关，设备取消、峰值、跨ROM仍未完成。rewrite代码没有生产入口。
6. 部分设备工具依赖固定机型/坐标，部分原始数据位于忽略缓存；fresh clone的轻量校验不等于严格溯源或实机验收。
7. 生产detailed export原件当前缺失，保留署名与provenance但不宣称本次重跑完整许可校验。
8. Kotlin/AGP旧DSL弃用和26条lint warnings/1 hint仍需后续独立处理；本批不混入依赖迁移。

## 4. 验证记录

| 本轮检查 | 结果 |
|---|---|
| `scripts/verify.ps1 -StrictSources` | RESULT: PASS；真实重跑单测，26个单测构建任务全部执行 |
| JVM测试XML汇总 | 362 tests / 0 failures / 0 errors / 4 skipped（冻结/专项opt-in默认跳过） |
| lint | 0 errors / 26 warnings / 1 hint；保持既有水平 |
| debug构建 | 成功，108,812,306B；SHA-256 `09CA066EA4C13B1962246BA9CAB9C61E0657F1759965780C7CE45AB3365565A2` |
| APK权限 | 无INTERNET / ACCESS_NETWORK_STATE |
| v1/v2/v3/D05校验 | 全部通过；v3使用strict原件/zip校验，不执行v3引擎验收 |
| Python契约 | D04评分5/5，v3审计4/4 |
| 文档引用 | 153文件，0断链 |
| Git格式检查 | `git diff --check`通过；新维护文本使用LF |
| PowerShell工具 | 全部脚本静态解析通过；整理工具预览及实际移动成功 |
| 保护资产 | 19份词库/语料/manifest/快照内容与HEAD一致；976位作者列表一致 |
| 应用源码 | 8份有diff的生产/测试文件仅注释/文档引用变化，无非注释内容变化 |

运行日志和摘要保留于 `.local/repository-cleanup-verification.log`、`.local/repository-cleanup-summary.json`；本轮构建与测试原始输出位于备份中的 `app/build` 和 `build`。构建输出在检查后归档，重新构建会生成新的活跃输出目录。

本轮不运行v3引擎验收、不修改冻结hash、不连接手机，不把历史设备结果标成当前新测。生产detailed原件缺失，未宣称重跑完整生产许可校验；原有AGP/Kotlin弃用与测试编译警告仍在。本轮变更未自动提交Git。
