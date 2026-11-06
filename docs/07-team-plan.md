# Bitap 团队协作与任务计划

版本：0.1｜负责人：Lead｜任务板：team_task_list

## 1. 角色与写入范围

| 成员 | 角色 | 共享任务 | 写入范围 | 交付 |
| --- | --- | --- | --- | --- |
| lead | Team Lead / 产品与架构 | task-1（已完成） | 构建文件、Manifest、res、docs、scripts、assets、app 入口、最终集成 | 可编译 APK、文档定调、合并与验收 |
| ime-dev | IME 工程师 | task-2 | `ime/**`、`core/**`、`test/**/core/**` | InputMethodService、Compose 键盘、拼音引擎、Reducer |
| ai-dev | 端侧 AI 工程师 | task-3 | `ai/internal/**`、`test/**/ai/**` | LiteRT-LM 运行、模型导入、结果清理 |
| theme-dev | 设计系统工程师 | task-4 | `ui/**`（不含 ThemeContract.kt） | Miuix/Glasmio 双主题、首页、设置、模型管理 UI |
| qa-dev | QA / 验收 | task-5 | `test/**/qa/**`、`docs/QA_REPORT.md`、`scripts/verify.ps1` | 独立测试、构建验收、报告 |

写入范围是 advisory 而非锁；同一文件绝不并发写。跨范围改动先给 Lead 发消息。

## 2. 流程

1. **文档先行**：Lead 先冻结 `docs/00–06` 与 ADR-0001；任何范围变更先改文档再改代码。
2. **任务板**：每个任务有 owner、write scopes、验收标准；teammate 必须 claim 当前 revision，完成后 complete。
3. **并行**：T1/T2/T3 三线并行；T4 以 blocked_by 等待 T1–T3。Lead 同时做词库、脚本、集成。
4. **集成**：Lead 负责合并跨模块 API、修复编译、跑全量构建；不把集成工作丢回给单个 teammate。
5. **验收**：qa-dev 独立运行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`，写 QA_REPORT；Lead 复核最终 diff 与权限清单，提交 git。
6. **报告**：teammate 完成时用 send_message 报告文件清单/命令结果/风险；Lead 在最终答复汇总所有成员结果。

## 3. 任务依赖

```
task-1 (Lead 基座/文档) ─┬─> task-2 (IME) ─┐
                         ├─> task-3 (AI)  ─┼─> task-5 (QA)
                         └─> task-4 (UI)  ─┘
```

## 4. 决策与冲突

- 契约文件 `ai/AiContract.kt`、`ui/theme/ThemeContract.kt`、`settings/**` 由 Lead 维护；teammate 只读。
- 若实现需要改契约：先发消息说明原因，Lead 决定是否改；禁止直接改对方的文件。
- 构建失败先定位归属模块，由 owner 修；QA 只报告不改生产代码。
- 所有视觉/交互争议以 `docs/01-experience-spec.md` 与 `docs/04-design-system.md` 为准。

## 5. 时间盒（本次会话）

- 0–15 min：文档、基座、词库、任务板。
- 15–70 min：T1/T2/T3 并行实现，Lead 集成入口与资源。
- 70–95 min：合并、编译、单测、QA 报告。
- 95–120 min：最终构建、隐私检查、git 提交、交付说明。
