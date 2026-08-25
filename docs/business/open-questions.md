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
| Q5 | P2 | 工具授权回收对「已提交未执行完」的工作流是否需要追溯撤销（当前时点语义=已过校验不中断）？ | flows/tool-authorization.md | 提交时点校验后无运行中撤销机制；是否需中止在途工作流属业务风险偏好 | 产品/安全负责人 | 高危工具合规要求；「授权撤销需立即止血」场景 | 待确认 |
| Q6 | P1 | KafkaTemplate.send 异步未确认——发送失败时提交已 202、工作流悬空 PENDING；生产是否需同步确认或 outbox？ | flows/kafka-async-dispatch.md | 代码未对 send future 做 get/回调；「接受静默丢」vs「强确认」是可靠性预算决策 | 架构师/运维 | broker 可用性 SLA；提交失败容忍度；outbox 规划 | 待确认 |
| Q7 | P2 | 定义库是全局命名空间——多调用方提交同名工作流互相覆盖（同版本 last-write-wins）；协作语义是共享库还是 per-caller 命名空间？ | flows/workflow-versioning.md | (name, version) 全局主键无 createdBy 维度；单人 demo 自洽，多人协作语义属产品决策 | 产品/架构师 | 目标用户单人/团队；同名工作流业务预期 | 待确认 |
| Q8 | P2 | 预算超限告警的运维响应预案——事件后人工介入 SLA 与动作（kill/联系提交方/仅记录）？ | flows/cost-budget-control.md | 非阻断语义下系统无动作；响应流程是运维制度问题 | 运维/产品 | Grafana 告警通道；运维手册 |
| Q9 | P2 | DryRunEngine 与 diagnosis 已建但生产入口缺失（dryRun 零生产调用）——是否补「服务端按 id 诊断」「干跑端点/CLI」？ | flows/observability-diagnosis.md | CodeGraph 证实 dryRun 4 处调用全为测试；补入口属产品范围决策 | 产品/架构师 | UI 诊断 Tab 数据来源；开发者调试工作流 | 待确认 |
| Q10 | P1 | 列加密 key 轮换策略——当前单钥，换 key 即旧行全部不可解（聚合端点跳过、恢复损坏）；需双钥并行解密或信封加密？ | flows/column-encryption.md | 代码只有单钥；轮换周期/应急流程是安全运维制度且实现成本需权衡 | 安全负责人/架构师 | 合规轮换 mandate；数据保留期与重写迁移成本 | 待确认 |
| Q11 | P2 | RAG 是否从 demo 升级核心能力（pgvector/嵌入模型/动态知识库/多租户隔离）？ | flows/rag-retrieval.md | 当前内存库/静态文档是有意最小验证实现；升级取决于产品叙事中 RAG 权重 | 产品/架构师 | 简历/产品叙事；真实知识库接入需求 | 待确认 |

## 已解决问题归档

| 编号 | 问题 | 结论 | 确认人 | 确认日期 |
|---|---|---|---|---|
