# 开发笔记目录

> 记录 AgentFlow 开发过程中的工程沉淀：实现思路、踩坑、Review 发现、技术决策。
> **用途**：简历素材 + 秋招面试「拷打」弹药库。与 `docs/plans/`（决策件）、`docs/handoff/`（Claude 接手清单）互补——本目录面向**人**（面试官/自己复习），后者面向**Agent**。
> **持续更新（2026-08-11）**：补入档 B 收尾（WorkflowSubmissionGuard、R10 per-workflow budget）+ v1.1（LangChain4jAgentAdapter / KTD-7 + 其 review 发现），分布在 01/02/03/04。评审残留项另见 `../residual-review-findings/langchain4j-adapter-review.md`。

## 文档清单

| 文档 | 内容 | 面试场景 |
|:---|:---|:---|
| [00-interview-arsenal.md](./00-interview-arsenal.md) | 面试弹药总览：每个单元的「能讲的故事 + 深挖点 + 反问准备」 | 面试前一晚扫一遍 |
| [01-implementation-rationale.md](./01-implementation-rationale.md) | 关键实现的技术选型与设计权衡（为什么 BSP、为什么两级 checkpoint、为什么 VT+Semaphore） | 「为什么这么设计」类问题 |
| [02-bugs-and-fixes.md](./02-bugs-and-fixes.md) | 实现过程踩的坑 + 根因分析 + 修复（按单元） | 「遇到最难的问题」「线上 bug 怎么排查」 |
| [03-review-findings.md](./03-review-findings.md) | ce-code-review 多 agent 审查发现的高价值问题 + 修复思路 | 「代码质量」「工程 rigor」展示 |
| [04-glossary.md](./04-glossary.md) | 项目术语表（BSP/super-step/channel/Reducer/checkpoint 等）+ 一句话解释 | 自我介绍/讲项目时不卡壳 |
| [05-observability-ui-followup.md](./05-observability-ui-followup.md) | 后续 #9–#12：可运行 API Server wiring + 3 个 Boot 4.1 坑、trace 超级步分层、NodeTrace 反序列化、看板列表端点/状态归一/指标防漂移 | 「真实路径为什么跑不通」「遇到最难的问题」 |

## 维护约定

- **何时写**：每完成一个单元（verify 绿后）/ 每修一个非平凡 bug / 每次 review 出有价值发现 → 当场补 1-2 段
- **怎么写**：每条遵循 STAR 变体——**Situation**（什么场景）→ **Task**（要解决什么）→ **Action**（怎么做的）→ **Result**（结果 + 量化）。面试时只讲前三个 + Result 的一句话，深挖细节在文档里备查
- **不写什么**：不抄 plan 正文（plan 是决策件，这里写的是「我遇到的实际问题」）；不写无量化/无证据的空话（"优化了性能" → "VT 并发 50 节点 + Semaphore(20) 限流，避免 HikariCP 连接池耗尽"）
- **简历联动**：每条 bug/finding 修完，在 `00-interview-arsenal.md` 对应单元下加一行「可讲的故事」标签，方便面试前提取

## 已有沉淀（迁移 / 索引）

- `docs/handoff/u3-spring-ai-adapter.md` — U3 Spring AI 适配器实现决策（历史记录）
- `docs/handoff/u5-checkpoint-recovery.md` — U5 checkpoint/recovery 实现决策
- `docs/handoff/u14-api-security.md` — U14 API 鉴权实现决策
- `CLAUDE.md` 「当前进度」段 + 「关键发现」段 — 跨单元技术坑速查

> handoff 文档偏「决策记录」（为什么这么做），本目录偏「过程记录」（踩了什么坑、怎么发现的、怎么修的）——面试官更想听后者。
