# 业务能力目录

| 优先级 | 业务能力 | 简介 | 入口 | 核心模块 | 文档状态 | 风险/复杂度 |
|---|---|---|---|---|---|---|
| P0 | 工作流生命周期 | 提交→守卫→派发→BSP 执行→终态（一切链路的地基） | HTTP POST /api/workflows 等 | agentflow-api + core | 已文档化 | 高：核心实体全量流转、无界 VT/成本防护 |
| P0 | HITL 人工审批 | 暂停→人工决策→恢复续跑（含审批中心聚合） | HTTP approvals 端点 | core + api + ui | 已文档化 | 高：三状态机交汇、权限规则密集、审计身份防伪造 |
| P0 | 崩溃恢复与重试 | 断点续跑、输出重放、stray 防护、手动 retry | recoverAndExecute + retry 端点 | core | 已文档化 | 高：防 LLM 重复计费、恢复正确性硬约束 |
| P1 | 动态路由与循环 | when 条件分支 / on_error 兜底 / 回边迭代收敛 | DSL edges/nodes 声明 | core | 已文档化 | 中高：BSP 语义保留 + 恢复重放确定性 |
| P1 | 权限与工具授权 | API Key 鉴权、per-caller 工具授权、admin 管理 | HTTP /api/tools/grants 等 | api/security | 候选 | 高：权限域（生命周期文档仅覆盖其提交侧拦截面） |
| P2 | Kafka 异步分发 | 提交/执行解耦、at-least-once + 幂等消费 | Topic agentflow.workflow.executions | kafka-starter | 候选 | 中：生命周期文档已覆盖幂等/认领面，生产者→消费者全链路细节待深挖 |
| P2 | 工作流版本管理 | 定义按 (name, version) 存储、旧实例按旧 DAG 执行、冲突检测 | 提交时自动记录 + version-check 端点 | core/version | 候选 | 中：影响恢复/retry 语义（生命周期文档 R11 已覆盖要点） |
| P2 | 成本与预算控制 | 提交守卫 422 + per-workflow 预算记账（edge-triggered） | 提交时 + 执行期 | api/security + observability | 候选 | 高：金额类规则（生命周期文档 R2/R3 已覆盖守卫面） |
| P2 | 可观测与诊断 | 5 类指标、trace、5 类异常诊断 | /trace、/diagnosis、/actuator/prometheus | api + observability | 候选 | 低：支撑性能力 |
| P2 | 敏感数据列加密 | 5 处 JSONB 敏感列 AES-256-GCM + 双 strict 装配 | 存储层横切 | core/security + starter | 候选 | 中：安全横切（审批文档已涉及其损坏行容错面） |
| P2 | RAG 检索增强 | 向量检索→增强→委托（引擎零改动扩展点） | demo-rag（agent: rag） | demo-rag | 候选 | 低：演示性扩展点 |

## 优先级评估依据

入口数与调用频率（生命周期/审批为全部调用必经）、核心实体影响范围（状态机交汇）、跨模块依赖（Kafka/DB/LLM）、状态复杂度（五状态 + 三套子状态机）、高风险规则（审批权限/成本/防重复计费）、代码耦合度（引擎核心）。

## 文档状态说明

- 已文档化：flows/ 下有对应文档且通过校验
- 进行中：已选定，分析中
- 候选：已识别，待排期
