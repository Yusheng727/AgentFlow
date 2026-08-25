# 业务能力目录

> 更新时间：2026-08-26（第二批 7 能力文档化，11 项全部覆盖）｜ 代码版本：main@2a37d6b

| 优先级 | 业务能力 | 简介 | 入口 | 核心模块 | 文档状态 | 风险/复杂度 |
|---|---|---|---|---|---|---|
| P0 | 工作流生命周期 | 提交→守卫→派发→BSP 执行→终态（一切链路的地基） | HTTP POST /api/workflows 等 | agentflow-api + core | 已文档化 | 高：核心实体全量流转、无界 VT/成本防护 |
| P0 | HITL 人工审批 | 暂停→人工决策→恢复续跑（含审批中心聚合） | HTTP approvals 端点 | core + api + ui | 已文档化 | 高：三状态机交汇、权限规则密集、审计身份防伪造 |
| P0 | 崩溃恢复与重试 | 断点续跑、输出重放、stray 防护、手动 retry | recoverAndExecute + retry 端点 | core | 已文档化 | 高：防 LLM 重复计费、恢复正确性硬约束 |
| P1 | 动态路由与循环 | when 条件分支 / on_error 兜底 / 回边迭代收敛 | DSL edges/nodes 声明 | core | 已文档化 | 中高：BSP 语义保留 + 恢复重放确定性 |
| P1 | 权限与工具授权 | API Key 鉴权、per-caller 工具授权（config ∪ DB）、admin 管理 | HTTP /api/tools/grants 等 | api/security | 已文档化 | 高：权限域——自授防护/通配/审计留痕 |
| P2 | Kafka 异步分发 | 提交/执行解耦、at-least-once + 原子认领幂等消费 | Topic agentflow.workflow.executions | kafka-starter | 已文档化 | 中：offset 策略/异步发送确认边界 |
| P2 | 工作流版本管理 | 定义 (name, version) 快照存取、旧实例按旧 DAG、冲突检测 | 提交自动记录 + version-check 端点 | core/version | 已文档化 | 中：恢复语义根基 + 全局命名空间边界 |
| P2 | 成本与预算控制 | 提交守卫 422 硬拦截 + per-workflow 预算记账告警（非阻断） | 提交时 + 运行时记账 | api/security + observability | 已文档化 | 高：金额域——两道防线分工决议 |
| P2 | 可观测与诊断 | 6 指标族 + trace 穿线 + 6 类问题诊断 + 干跑 | /trace、/diagnosis、/actuator/prometheus | api + observability + debug | 已文档化 | 中：IDOR 防护/指标恰一次/干跑未接线 |
| P2 | 敏感数据列加密 | 5 处敏感列 AES-256-GCM + 生产双 strict fail-closed | 存储层横切（5 写点） | core/security + starter | 已文档化 | 中高：安全横切——legacy 兼容/key 轮换缺口 |
| P2 | RAG 检索增强 | 检索→增强→委托，引擎零改动扩展点实证 | demo（agent: rag） | demo-rag | 已文档化 | 低：demo 定位（内存库/确定性离线） |

## 优先级评估依据

入口数与调用频率（生命周期/审批为全部调用必经）、核心实体影响范围（状态机交汇）、跨模块依赖（Kafka/DB/LLM）、状态复杂度（五状态 + 三套子状态机）、高风险规则（审批权限/成本/防重复计费/权限域）、代码耦合度（引擎核心）。

## 文档状态说明

- 已文档化：flows/ 下有对应文档且通过校验
- 进行中：已选定，分析中
- 候选：已识别，待排期

（11 项能力全部「已文档化」——无候选遗留。新增能力时按 skill 增量更新模式补录。）
