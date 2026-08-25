# AgentFlow 学习档案（main@2a37d6b）

- **target**：`main` @ `2a37d6b27e5d50687c656adbeedd2b92c0fdee92`（2026-08-25，snapshot 模式，**CodeGraph 增强取证版**）
- **这是什么**：AgentFlow 的个人学习档案——Java 原生轻量级 Multi-Agent 编排引擎。以个人成长视角组织：实现层（How）→ 技术层（How it is designed）→ 产品层（What problem it solves）→ 个人学习层（What I learned）。
- **取证方式**：CodeGraph 索引（266 文件 / 5,068 节点 / 11,949 边，已 sync 至 target commit）——callers/callees/impact/query route 逐跳验证调用链；辅以源码精读与 git 历史。
- **生成时间**：2026-08-25 23:55

## 目录导览

| 文档 | 一句话说明 |
|---|---|
| [system-overview.md](system-overview.md) | 系统全景：三层身份、13 条 HTTP 路由清单、CodeGraph 验证的主调用链、架构图 |
| [module-map.md](module-map.md) | 模块地图：13 模块关系 + 两大结构热点（扇入/影响面量化） |
| [technical-architecture.md](technical-architecture.md) | 技术架构学习笔记：9 个设计模式（带 CodeGraph 调用计数）、扩展点/隔离点、稳定性设计 |
| [glossary.md](glossary.md) | 术语表：17 个项目特有概念（super-step/channel/回边/HITL/tryClaim 等） |
| [product-intent-hypotheses.md](product-intent-hypotheses.md) | 产品意图假设：12 条可观察行为与动机推测（带可信度） |
| [open-questions.md](open-questions.md) | 待确认问题清单（Q1–Q5） |
| [evidence-index.md](evidence-index.md) | 40 条结论 → 证据锚点速查表 |
| [implementation-walkthroughs/](implementation-walkthroughs/) | 端到端实现讲解（深挖，待选） |
| [technical-decisions/](technical-decisions/) | ADR 风格技术决策记录（深挖，待选） |
| [learning-summaries/](learning-summaries/) | 个人学习总结（深挖，待选） |

## 可信度统计（本骨架集）

- [代码已确认]：58 处（其中 14 处含 CodeGraph 调用计数/路由清单等仅图索引可得的结构性证据）
- [历史已确认]：14 处（git commit 佐证的设计演进）
- [需求已确认]：4 处（`docs/plans/agentflow/` 计划文档佐证）
- [合理推断]：7 处
- [待确认]：5 处（全部同步至 open-questions.md）

## CodeGraph 相对初版（纯静态 grep）新增的精确性

1. **调用链从「读代码推测」升级为「逐跳验证」**：提交→执行主链 8 跳全部经 callers 命令确认（如 runRounds 恰 3 callers 且全在 BspEngine 内）；
2. **量化结构热点**：BspEngine 95 callers（扇入之首）、CheckpointManager 528 impact symbols（影响面之首）——「改哪最痛」有了数字；
3. **完整路由清单**：13 条 HTTP route 节点全集（初版遗漏 version-check 与 DELETE tools/grants 两条）；
4. **SPI 实现全集**：AgentFunction 确认 6 实现（初版只列 4）；
5. **跨模块复用证据**：WorkflowExecutionService 26 callers 恰分布三层、SpelPromptResolver 恰被两适配器 namespace 引用——「单一真相源」从口号变调用图事实。

## 如何增量更新

- **代码演进后**：`codegraph sync` 后换新 commit 重跑 snapshot（同 ref 覆盖可再生骨架，open-questions/evidence-index 合并追加）；
- **只关心某分支的变更**：对该分支走 diff 模式，输出到 `docs/learning/branches/` 下以该分支名命名的子目录；
- **深挖某模块**：在本文档目录的 `implementation-walkthroughs/`、`technical-decisions/`、`learning-summaries/` 子目录追加。
