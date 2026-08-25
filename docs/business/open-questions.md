# 待业务确认问题

> 汇总所有流程文档第 14 节的问题，按优先级排序。问题被确认后：更新对应流程文档 → 将答案与确认人记录在此 → 标记"已解决"。

## 优先级定义

- **P0**：可能导致业务理解错误、资金/库存/合规风险；
- **P1**：影响流程准确性；
- **P2**：补充性问题。

## 问题列表

| 编号 | 优先级 | 问题 | 来源文档 | 为什么代码不足以确认 | 建议确认对象 | 建议核查资料 | 状态 |
|---|---|---|---|---|---|---|---|
| Q1 | P1 | Kafka 默认 `auto.offset.reset=earliest` 对新消费组的历史消息回放——生产是否需限定消费组或改 latest？ | flows/workflow-lifecycle.md | 装配面默认 earliest（任务队列语义），但生产 offset 策略属运维决策，代码中未见环境差异化配置 | 运维/架构师 | KafkaAgentFlowAutoConfiguration 装配 + 生产消费组 offset 管理策略 | 待确认 |
| Q2 | P1 | RUNNING 孤儿（崩溃遗留）是否需要自动检测/自动恢复（定时扫描 + 自动 recoverAndExecute），还是仅人工触发？ | flows/crash-recovery-retry.md | CodeGraph 图上可证：`recoverAndExecute` 全部 9 个调用方均为测试（BspEngineRecoveryTest / RecoveryConditionalTest / RecoveryLoopTest + 2 个 demo 演示测试），生产 main 代码零调用——引擎恢复能力已建但无生产编排接线 | 架构师/运维 | 部署脚本、运维手册、是否存在外部恢复编排器 | 待确认 |
| Q3 | P1 | 审批人模型是否需要独立于「创建者 ∪ admin」的审批角色（部门审批人、多级会签）？ | flows/hitl-approval.md | 当前审批人集合由 API Key 所属决定；ApprovalRequest 无 assignee 字段，未见业务角色审批人数据模型 | 产品/业务负责人 | 是否存在「指定审批人」业务诉求；workflow_approvals 是否需扩列 | 待确认 |
| Q4 | P2 | on_error 兜底完成的执行业务上是「成功」还是「降级成功」——status=SUCCESS 但指标记 fallback，下游按 status 判定是否会误读？ | flows/dynamic-routing-and-loops.md | WorkflowStatus 枚举无 FALLBACK 值（trace/指标维度区分）；是否需业务级区分取决于下游消费方式 | 产品/架构师 | 下游对 GET /status 的消费逻辑；UI 看板对 fallback 的展示需求 | 待确认 |

## 已解决问题归档

| 编号 | 问题 | 结论 | 确认人 | 确认日期 |
|---|---|---|---|---|
