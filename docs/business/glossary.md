# 术语表

按业务语言解释领域术语。状态值与枚举名是代码事实（A 级证据）；业务解释标注可信度。

## 工作流核心

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| 工作流（Workflow） | 一次多 Agent 协作任务的执行实例（YAML 声明拓扑 + inputs 入参驱动） | agentflow-core/.../dsl/WorkflowDefinition.java | 已确认 |
| 节点（Node） | 工作流中的一个执行步骤（绑定一个 agent），可配 prompt/tools/schema/超时/on_error | dsl/NodeDefinition.java | 已确认 |
| Agent | 节点背后的执行器（LLM 适配器/mock/审批门/RAG 等），按名字从 NodeRegistry 解析 | agent/AgentFunction.java + NodeRegistry.java | 已确认 |
| Channel（通道） | 节点间传值的命名管道；多节点写同一 channel 由 Reducer 决定合并语义 | dsl/ChannelDefinition.java + Reducer.java | 已确认 |
| Reducer | channel 合并策略：OVERWRITE（覆盖）/CONCAT（拼接）/MAX（取最大，数值精度保守）/CUSTOM | dsl/Reducer.java | 已确认 |
| inputs | 工作流启动入参（提交时传入，Map）；可在 prompt/when 谓词中引用。注意：原入参不持久化，恢复期不可得 | api/WorkflowController.java#SubmitRequest + BspEngine#recoverAndExecute 注释 | 已确认 |

## 执行模型（BSP）

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| BSP（Bulk Synchronous Parallel） | 整体同步并行执行模型：按层并行跑节点、层间 barrier 同步——最快节点也要等最慢的同层节点 | engine/BspEngine.java 类注释:52-65 | 已确认 |
| super-step | BSP 的一个执行层（0-based）；静态分层算法（最长路径）决定节点属于哪层 | dsl/DAGLayerer.java + BspEngine | 已确认 |
| barrier（屏障） | 层间同步点：本层全部节点完成后按声明序 Reducer 合并 channel、写 barrier checkpoint，再进下一层 | engine/BspEngine#applyBarrier | 已确认 |
| Virtual Thread（VT） | Java 21 虚拟线程，承载同层节点的并行执行 | Executors.newVirtualThreadPerTaskExecutor（BspEngine:215） | 已确认 |
| round（迭代轮次） | v2 循环维度：回边命中开启新轮，环上节点每轮重执行；checkpoint 带 round 维度 | BspEngine#runRounds + V5 迁移 | 已确认 |

## 状态机

| 术语 | 取值 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|---|
| WorkflowStatus | PENDING / RUNNING / AWAITING_APPROVAL / SUCCESS / FAILED | 工作流实例状态（V7 扩 AWAITING_APPROVAL） | engine/checkpoint/WorkflowStatus.java + V7 迁移 | 已确认 |
| NodeStatus | IN_PROGRESS / COMPLETED / FAILED | 节点执行状态；COMPLETED 不可覆盖，FAILED 可经重跑升级 COMPLETED | engine/checkpoint/NodeStatus.java | 已确认 |
| ApprovalStatus | PENDING / APPROVED / REJECTED | 审批单状态（原子转移，终态不可再变） | engine/checkpoint/ApprovalStatus.java | 已确认 |
| SKIPPED | （trace 态，非枚举） | 被动态路由切断的节点标记：不执行、不消耗成本 | BspEngine#markSkippedNodes | 已确认 |
| FALLBACK | （指标态） | 经 on_error 兜底收敛的工作流终态指标值（status 字段仍 SUCCESS，指标/trace 维度区分） | BspEngine#execute:245-246 + AgentFlowMetrics.STATUS_FALLBACK | 已确认 |

## 审批（HITL）

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| HITL（Human-in-the-Loop） | 人工介入环节：高风险节点暂停等人决策 | engine/ApprovalGateAgent.java | 已确认 |
| 审批门 agent | 首跑抛 ApprovalRequiredException（请求审批）、决策注入后放行的节点执行器；demo 参考实现 ApprovalGateAgent（agent 名 `approval`） | engine/ApprovalGateAgent.java + ApprovalRequiredException | 已确认 |
| 审批单（ApprovalRequest） | 暂停时落库的待办：含审什么（requestPayload）、断点信息（round/superStep/nodeId）、上下文快照（contextSnapshot） | engine/checkpoint/ApprovalRequest.java | 已确认 |
| contextSnapshot | 暂停点的只读 channel 快照（含同层兄弟输出、不含审批节点自身）——恢复时重建执行上下文的唯一依据 | ApprovalRequest.java 注释 + BspEngine#applyBarrier | 已确认 |
| decidedBy | 审批人标识——服务端从 API Key hash 推导，客户端不可伪造 | ApprovalController.java:44 注释 | 已确认 |
| approvalDecision | 注入 AgentInput 的审批决策（APPROVE/REJECT）；空=首跑，非空=审批恢复重跑 | agent/AgentInput.java:30-44 | 已确认 |

## 路由与循环（v2）

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| when 谓词 | 条件边上的 SpEL 布尔表达式（`output.<field>` / `context.<channel>` / `inputs.<key>`），按声明序首 true 命中 | dsl/EdgeDefinition.java + prompt/PredicateEvaluator.java | 已确认 |
| on_error | 节点失败时的兜底跳转目标（声明式）；逆转「任一失败即终止」，目标自身失败不二次跳转 | dsl/NodeDefinition + BspEngine#applyBarrier | 已确认 |
| 回边（loop 边） | 指向更早节点的条件边（形成环），必须带 when 退出条件 + max_iterations 上限 | EdgeDefinition.java:8-16 注释 | 已确认 |
| max_iterations | 回边的迭代上限（DSL 声明）；引擎另有全局 1000 轮硬上界双保险 | SemanticValidator + BspEngine#checkIterationCap | 已确认 |
| takenEdges（已走边） | 运行时实际走过的边列表（from->to）——checkpoint 唯一持久化的路由事实，恢复期重算可达集的单一真相源 | V4 迁移注释 + CheckpointManager 注释:171-174 | 已确认 |
| 可达集（active） | 当前允许执行的节点集合；每层只跑可达节点 | BspEngine#computeReachable + runStep | 已确认 |

## 恢复与容错

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| checkpoint（两级） | 节点级（完成当下即写，防重复计费）+ barrier 级（层完成写 channel 快照） | CheckpointManager.java 注释:19-29 | 已确认 |
| RecoveryProtocol | 崩溃恢复协议：定位崩溃层（nextSuperStep）、收集已完成节点与重放输出 | engine/checkpoint/RecoveryProtocol.java | 已确认 |
| replayOutputs（重放输出） | 崩溃层已完成节点的输出——未进上一 barrier，恢复时须按 Reducer 合并回 context | ExecutionState.java 注释:16-18 | 已确认 |
| stray 记录 | 超时 abort 后在飞线程写出的孤立 COMPLETED（未经 barrier 合并）——恢复时按 FAILED 状态鉴别并整体重跑崩溃层 | RecoveryProtocol.java:91-101 | 已确认 |
| tryClaim（原子认领） | 消费侧幂等：条件 UPDATE 仅 PENDING→RUNNING 恰一胜出，防并发重复投递双跑双计费 | PostgresCheckpointManager#TRY_CLAIM_SQL | 已确认 |
| 重试策略（RetryPolicy） | 节点级瞬时错误重试（指数退避，仅 transient） | engine/fault/RetryPolicy.java | 已确认 |

## 安全与权限

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| callerId | 调用方身份 = X-API-Key 的 SHA-256 hash（不落明文） | api/security/ApiKeyAuthFilter.java#CALLER_ID_ATTR | 已确认 |
| admin | 持有 AGENTFLOW_ADMIN_API_KEYS 中 Key 的调用方（可跨创建者审批/管理工具授权） | api/security/AdminApiKeys.java | 已确认 |
| IDOR 防护 | 状态/轨迹/重试仅创建者可访问（ownership 校验 403） | api/security/WorkflowOwnershipChecker.java | 已确认 |
| 工具授权（Tool Allowlist） | 调用方可用的 LLM 工具白名单（config ∪ DB grants，总空才 allow-all）；提交时强制 | api/security/CallerToolAllowlist.java | 已确认 |
| 提交守卫（SubmissionGuard） | 提交时预防性拦截：节点数上界（默认 500）+ 预估成本上界 → 422 | api/security/WorkflowSubmissionGuard.java | 已确认 |

## 派发

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| Dispatcher（派发器） | 提交后异步执行的投递抽象：默认本地虚拟线程；可换 Kafka（`agentflow.kafka.enabled` 门控） | api/WorkflowDispatcher.java | 已确认 |
| Topic `agentflow.workflow.executions` | Kafka 模式的执行消息通道（JSON 字符串，key=workflowId，at-least-once） | kafka/KafkaWorkflowDispatcher.java#TOPIC | 已确认 |

## 歧义与易混项

- **「重试」三义**：① 节点级 RetryPolicy（引擎内瞬时错误重试）② retry 端点（调用方对 FAILED 工作流整体重跑，非断点续跑）③ 恢复期崩溃层未完成节点重跑（断点续跑的一部分）。上下文中必须区分。
- **「恢复」两路径**：崩溃恢复（recoverAndExecute，处理 RUNNING 崩溃/FAILED abort）≠ 审批恢复（approveAndResume，处理 AWAITING_APPROVAL）；互斥拒绝混用。
- **SUCCESS vs FALLBACK**：on_error 兜底收敛的执行 status 字段是 SUCCESS，仅指标/trace 记 fallback——下游按 status 判定时注意（见 dynamic-routing-and-loops.md 待确认问题）。
- **callerId vs createdBy**：同一概念（API Key SHA-256 hash）在请求上下文与持久化列两个名字。
- **两套 admin env 名**：`AGENTFLOW_ADMIN_API_KEYS`（admin 判定）≠ `AGENTFLOW_API_API_KEYS`（普通鉴权，宽松绑定带 API_ 前缀）——配错名静默 401。
- **「预算」两道防线**：提交守卫（422 硬拦截，WorkflowSubmissionGuard）≠ 运行预算（记账告警非阻断，WorkflowBudget）——硬软分工，勿混。
- **「加密」两模式**：fromEnv()（dev 宽松 Noop）≠ fromEnvStrict()（生产 fail-closed 启动失败）。
- **Agent「扩展点」vs「引擎改动」**：RAG/审批门/任意 LLM 适配器都是 AgentFunction 实现零引擎改动；路由/循环/审批暂停是引擎改动——判断新能力归属哪侧先查此界。

## 第二批补充术语（2026-08-26）

| 术语 | 业务含义 | 证据 | 可信度 |
|---|---|---|---|
| 工具授权（tool grant） | 调用方对某 LLM 工具的使用许可：config 静态 ∪ DB 动态（caller_tool_grants），双空才全局允许 | api/security/CallerToolAllowlist.java#isAllowed | 已确认 |
| 通配授权 `*` | 授予该调用方全部工具（config 集合与 DB 行语义一致） | CallerToolAllowlist#hasTool + IS_GRANTED_SQL | 已确认 |
| grantedBy | 授权操作的审计字段（admin 的 caller hash） | V6 迁移 + ToolGrantController#grant | 已确认 |
| AGENTFLOW_ENCRYPTION_KEY | 列加密密钥（base64 32B AES-256），只从 env 读；生产缺失启动失败 | security/ColumnEncryptors.java | 已确认 |
| `AESGCM:iv:ct` | 自描述密文格式；decrypt 按前缀识别，无前缀=legacy 明文原样返回 | security/AesGcmColumnEncryptor.java | 已确认 |
| 双 strict 装配 | 生产同时强制 checkpoint 与定义存储加密（杜绝半吊子状态） | starter/AgentFlowAutoConfiguration:100-114 | 已确认 |
| Topic key=workflowId | Kafka 消息以 workflowId 为 key——同工作流同分区保序 | kafka/KafkaWorkflowDispatcher#dispatch | 已确认 |
| agentflow-workers | 默认消费组（同组多实例分摊分区） | KafkaAgentFlowAutoConfiguration:73 | 已确认 |
| auto.offset.reset=earliest | 新消费组从分区头读——订阅前消息不丢（幂等消费使重扫安全） | KafkaAgentFlowAutoConfiguration:74 | 已确认 |
| （name, version）定义键 | 工作流定义的全局定位键；同键重提交覆盖（last-write-wins） | version/WorkflowVersionManager | 已确认 |
| 版本冲突（Conflict） | 执行版本落后最新定义版本——WARN 提示不阻断，在途实例按旧 DAG 跑完 | version/VersionConflictDetector | 已确认 |
| edge-triggered 超限 | 预算超限事件恰记一次（首次跨过上界），非每节点重复 | observability/WorkflowBudget#record | 已确认 |
| budget_exceeded | 预算超限指标名（agentflow.workflow.cost.budget_exceeded） | observability/AgentFlowMetrics 常量 | 已确认 |
| ExecutionTrace / NodeTrace | 执行轨迹（进程内存 Registry，重启丢——与 checkpoint 独立） | observability/ExecutionTraceRegistry | 已确认 |
| 诊断 6 类问题 | 迭代超限/连续超时/Token 异常(>3×均值且>100)/SpEL 失败/Channel 缺失/节点重复（合法循环豁免） | api/DiagnosisService#diagnose | 已确认 |
| 干跑（DryRun） | 不调 LLM 的拓扑验证（内置 mock 复用分层）；生产入口未接线 | debug/DryRunEngine | 已确认 |
| 词袋 embedder | 确定性嵌入（小写+分词+集合），无外部向量服务——demo RAG 离线可测 | demo-rag/InMemoryVectorStore | 已确认 |
| 检索→增强→委托 | RagAgentFunction 三步：topK 检索 → 拼上下文换 prompt → 全字段透传 delegate | demo-rag/RagAgentFunction#execute | 已确认 |
| mock fallback（NodeRegistry） | 未注册 agent 名回落 mock——demo 拓扑混合 agent 可用 | demo-api ApiConfig + RagDemoConfig | 已确认 |
