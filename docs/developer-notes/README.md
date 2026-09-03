# 开发笔记目录

> 记录 AgentFlow 开发过程中的工程沉淀：实现思路、踩坑、Review 发现、技术决策。
> 与 `docs/plans/`（决策件）、`docs/handoff/`（交接清单）互补——本目录记录「实现过程中遇到的实际问题与解法」，供贡献者理解设计取舍。

## 文档清单

| 文档 | 内容 | 适用场景 |
|:---|:---|:---|
| [01-implementation-rationale.md](./01-implementation-rationale.md) | 关键实现的技术选型与设计权衡（为什么 BSP、为什么两级 checkpoint、为什么 VT+Semaphore） | 理解「为什么这么设计」 |
| [02-bugs-and-fixes.md](./02-bugs-and-fixes.md) | 实现过程踩的坑 + 根因分析 + 修复（按单元） | 排查同类问题、理解已知边界 |
| [03-review-findings.md](./03-review-findings.md) | 多 agent 审查发现的高价值问题 + 修复思路 | 代码质量与工程 rigor 参考 |
| [04-glossary.md](./04-glossary.md) | 项目术语表（BSP/super-step/channel/Reducer/checkpoint 等）+ 一句话解释 | 阅读源码前建立词汇 |
| [05-observability-ui-followup.md](./05-observability-ui-followup.md) | 后续 #9–#12：可运行 API Server wiring + 3 个 Boot 4.1 坑、trace 超级步分层、NodeTrace 反序列化、看板列表端点/状态归一/指标防漂移 | 「真实路径为什么跑不通」案例 |
| [06-grafana-closure-pg-finish.md](./06-grafana-closure-pg-finish.md) | 2026-08-07：Grafana 可观测全闭环（指标挂钩 engine/mock 记账/Prometheus exporter）+ PG 收尾（Postgres IT + listByCreatedBy 兑现）——「面板为什么全空」到「6 面板全有数据」 | 可观测性接线路径 |

## 维护约定

- **何时写**：每完成一个单元（verify 绿后）/ 每修一个非平凡 bug / 每次 review 有价值发现 → 当场补 1-2 段
- **怎么写**：每条遵循 STAR 变体——**Situation**（什么场景）→ **Task**（要解决什么）→ **Action**（怎么做的）→ **Result**（结果 + 量化）；细节备查，叙事讲前三段
- **不写什么**：不抄 plan 正文（plan 是决策件，这里写的是「实现时遇到的实际问题」）；不写无量化/无证据的空话（"优化了性能" → "VT 并发 50 节点 + Semaphore(20) 限流，避免 HikariCP 连接池耗尽"）

## 已有沉淀（迁移 / 索引）

- `docs/handoff/u3-spring-ai-adapter.md` — U3 Spring AI 适配器实现决策（历史记录）
- `docs/handoff/u5-checkpoint-recovery.md` — U5 checkpoint/recovery 实现决策
- `docs/handoff/u14-api-security.md` — U14 API 鉴权实现决策

> handoff 文档偏「决策记录」（为什么这么做），本目录偏「过程记录」（踩了什么坑、怎么发现的、怎么修的）。
