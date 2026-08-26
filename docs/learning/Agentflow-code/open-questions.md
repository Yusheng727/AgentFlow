# 待确认问题

> 所有 [待确认] 结论的汇总。问题被确认后：更新对应文档 → 记录答案与确认人 → 标记"已解决"。
> target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92（snapshot 2026-08-25）

## 优先级定义

- **P0**：影响对资金/库存/权限/合规相关逻辑的理解，或影响实习核心任务的判断；
- **P1**：影响对主链路理解的准确性；
- **P2**：补充性问题。

## 问题列表

| 编号 | 优先级 | 问题 | 来源文档 | 缺什么证据 | 建议确认对象（导师/产品/架构师/运维） | 建议核查资料 | 状态 |
|---|---|---|---|---|---|---|---|
| Q1 | P1 | Redis 在 docker-compose 中声明但业务代码零依赖——它是预留缓存层还是 compose 模板惯性？若 v1.1/v2 有规划，规划是什么？ | module-map.md | core/api 源码无 spring-data-redis import；计划文档未检索到 Redis 消费方条目 | 项目作者本人（简历项目语境） | `docs/plans/agentflow/04-high-level-design.md` 全文、ROADMAP v1.1 节 | 待确认 |
| Q2 | P2 | `agent.ApprovalRequiredException`（异常形态）与 `engine.NodeResult.ApprovalRequired`（结果形态）分居两包的设计考量——为何 NodeResult 内嵌 record 而不是独立类？ | module-map.md | 无 ADR 记录该切分理由 | 项目作者 / ce-code-review 记录 | `docs/developer-notes/03-review-findings.md` HITL 相关节 | 待确认 |
| Q3 | P1 | 提交守卫 DEFAULT_MAX_NODES=500 与 CHARS_PER_TOKEN=4 的估算参数依据（压测/行业经验/拍脑袋）？面试被追问「为什么是 500」时的答案 | product-intent-hypotheses.md | javadoc 只给了值未给依据 | 项目作者本人 | `docs/plans/agentflow/06-open-questions-risks-metrics.md` OQ 清单 | 待确认 |
| Q4 | P2 | 引擎 MAX_TOTAL_ROUNDS=1000 硬上界的量级选择依据（单个回边循环 3-5 次为常态时 1000 是否过松/过紧） | technical-architecture.md | 字段注释只说「纵深防御」未说量级来源 | 项目作者本人 | 同上 | 待确认 |
| Q5 | P2 | `demo-api` 的 `agentflow.real.enabled=true` 真实 LLM 路径在生产部署叙事中的定位——demo-api 本身是否会被当作生产参考部署物，还是纯粹演示（影响加密/鉴权配置的严肃程度评估） | system-overview.md | demo-api 模块名与内容均为演示形态，但 README 有 Quick Start | 项目作者本人 | README.md「3 接入模式」节 | 待确认 |
| Q6 | P2 | BspEngine execute 的 4 个公开重载是否构成调用方迁移负担（95 callers 中还有多少用旧 Map 形式；是否该标 @Deprecated） | implementation-walkthroughs/bsp-engine-execution-loop.md | 重载共存无废弃标注；未统计调用方分布 | 项目作者本人 | CodeGraph callers 按重载签名分组 | 待确认 |
| Q7 | P1 | recoverAndExecute 生产触发路径——当前 callers 全在测试/演示，是否有计划接定时扫描 RUNNING 超时工作流或 admin 端点 | implementation-walkthroughs/checkpoint-recovery-protocol.md | CodeGraph：生产代码无调用点 | 项目作者本人 | ROADMAP v1.1 节 | 待确认 |
| Q8 | P1 | 恢复入口无 tryClaim——并发 recoverAndExecute 同一 workflow 会双跑；生产预期靠调用纪律还是待补原子抢占 | implementation-walkthroughs/checkpoint-recovery-protocol.md | recoverAndExecute 无任何 claim 前置 | 项目作者本人 / 架构师 | ce-code-review 记录 | 待确认 |
| Q9 | P2 | replayOutputs 与 completedNodeIds「同序同源」约定靠注释维持（无类型级绑定），演化中如何防漂移 | implementation-walkthroughs/checkpoint-recovery-protocol.md | RecoveryProtocol javadoc 注释约定 | 项目作者本人 | — | 待确认 |
| Q10 | P2 | Spring 适配器无 LC4j 对称的「逐轮」中断检查（ChatClient 内置循环无法插检查点）——是否有框架 issue 跟踪或 workaround 计划 | implementation-walkthroughs/dual-adapter-comparison.md | callLlm 单次调用无 isInterrupted 检查 | 项目作者本人 | Spring AI issue tracker | 待确认 |
| Q11 | P2 | 审批单无超时机制（PENDING 永久等待）——是否有业务场景需要审批超时自动 REJECT 或升级提醒 | implementation-walkthroughs/hitl-approval-lifecycle.md | workflow_approvals 无 TTL/超时列 | 项目作者本人（业务向） | 02-requirements R20 | 待确认 |
| Q12 | P2 | Kafka listener 并发度与 VT 引擎的配合——容器线程数小于同层节点数时是否成吞吐瓶颈 | implementation-walkthroughs/kafka-dispatch-decoupling.md | listener 容器并发配置未与引擎层宽耦合论证 | 项目作者本人 / 架构师 | KafkaAgentFlowAutoConfiguration factory 配置 | 待确认 |
| Q13 | P1 | API Key 无轮换/吊销机制（静态 hash 集合，改 key 需重启）——v1.1/v2 是否计划（短期 token/JWT/管理端点） | implementation-walkthroughs/api-security-layers.md | ApiKeyAuthFilter 构造时固化集合 | 项目作者本人 | ROADMAP 安全条目 | 待确认 |
| Q14 | P2 | real.enabled 模式下其余 agent 名回落 mock——混合模式（部分真实部分 mock）是演示便利还是有生产用途（如灰度） | implementation-walkthroughs/rag-extension-point.md | ApiConfig 注释「其他 agent 名仍回落 mock fallback」 | 项目作者本人 | — | 待确认 |

## 已解决问题归档

| 编号 | 问题 | 结论 | 确认人 | 确认日期 |
|---|---|---|---|---|
