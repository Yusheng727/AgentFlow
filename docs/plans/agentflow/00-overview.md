---
title: feat: AgentFlow Multi-Agent 编排引擎
type: feat
date: 2026-06-25
deepened: 2026-06-25
---

## Summary

AgentFlow 是一个 Java 原生的轻量级 Multi-Agent 编排引擎。YAML DSL 声明工作流，BSP（Bulk Synchronous Parallel）执行模型驱动，多个专业 Agent 能像微服务一样协作。v1 聚焦**静态 DAG（串行+并行 + 混合拓扑）+ PostgreSQL 持久化 Checkpoint + 调试体验**，10 周内交付。三个验证场景采用**差异化拓扑**证明引擎通用性：① 供应商风险评估（并行→汇总）、② 合同审核流水线（4 步链式串行）、③ 投资分析决策（双层 fork-join）。新增 Mock 模式（本地零成本调试）+ Workflow 版本管理（生产安全更新）。

> **v4 修订要点**：基于 ce-doc-review 6 persona 审查（coherence/feasibility/scope/security/adversarial/product），方向重确认 + 真 bug 修复：
> - 🟢 **方向**：AgentFlow 不转向——七三开（后端 70% + Agent 30%）+ 主流技术栈 + 从0复现展示后端能力，与 ToyRush/InterviewCoach 互补
> - 🔴 **P0-1 Recovery off-by-one bug**：伪代码查错层（`targetSuperStep-1` → `nextSuperStep`），修复崩溃层 LLM 重复计费
> - 🔴 **P0-2 super-step 编号统一 0-based**：分层算法/时序图/checkpoint/Recovery 全统一
> - 🔴 **P0-3/4 安全**：新增 R21（API 鉴权防 IDOR）+ R22（LLM 凭证 + 敏感数据脱敏）
> - 🔴 **P0-5 前提重写**：Problem Frame 从"生态空白"改为"从0复现展示后端能力"，补 LangGraph4j 等竞品对比 + 诚实声明
> - 🟠 **P1**：U12 super-step 数 5→4；Interview Value 重写七三开叙事
> - ⚪ **safe_auto**：6 处一致性修复（重复 Test scenarios/覆盖率/Phase 周数/表名/单元数/关键路径）

> **v3 修订要点**：基于 Senior Architect Review 反馈，重点修复以下问题：
> - 🔴 Critical：Checkpoint 恢复状态检查缺陷（C1）、KTD 数量统一为 9 个（C2）、Recovery 算法伪代码补齐（C3）、R7 Supervisor 重新定义为"轻量 Supervisor"（C4）
> - 🟠 Important：LLM 输出 schema 校验（I1）、Virtual Threads + PG Checkpoint 并发策略（I2）、CI/CD 纳入范围（I4）
> - 新增 "Recovery Protocol" 独立章节和 "Interview Value" 章节

> **v2 修订要点**：基于 4 位审查者（架构师、工程师、产品经理、风险专家）反馈，解决 3 个 Critical + 5 个 High 级问题，涵盖 BSP 算法明确化、Checkpoint 语义重构、接口合约补全、场景差异化、范围削减。

---

## 文档导航（拆分版）

本计划原为单文件（约 1090 行），已按章节边界拆分为 7 个分片，便于独立阅读与维护：

| 分片 | 文件 | 内容 |
|:---|:---|:---|
| 1 | [`01-problem-frame.md`](./01-problem-frame.md) | Problem Frame、简历定位、现有方案对比、目标用户 |
| 2 | [`02-requirements.md`](./02-requirements.md) | R1–R22 全部需求 + Deferred + Outside Identity |
| 3 | [`03-key-technical-decisions.md`](./03-key-technical-decisions.md) | KTD-1 ~ KTD-9 |
| 4 | [`04-high-level-design.md`](./04-high-level-design.md) | 架构图、BSP 模型、分层算法、时序、ER 图、状态机 |
| 5 | [`05-implementation-units.md`](./05-implementation-units.md) | Unit Priority 矩阵 + U0–U14 全部实现单元（Phase 1/2/3） |
| 6 | [`06-open-questions-risks-metrics.md`](./06-open-questions-risks-metrics.md) | Open Questions（含 2026-06-28 review）、Risks、Success Metrics |
| 7 | [`07-sources-revision-interview.md`](./07-sources-revision-interview.md) | Sources、Revision History、Interview Value |

> **拆分说明**：本主文件保留 frontmatter、Summary、修订要点摘要与导航。完整内容在各分片中。两轮 ce-doc-review（v4.2）的全部修订已落在分片内相应位置。
