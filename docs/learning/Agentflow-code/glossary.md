# 术语表（业务术语优先，技术名词只收项目特有概念）

> target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92（snapshot 2026-08-25，CodeGraph 增强版）

## super-step
- 代码名：`SuperStep` / `DAGLayerer.computeSuperSteps`
- 含义：BSP 模型的同步单位——静态 DAG 按最长路径分层（`level[v]=max(level[u])+1`）后的一层；同层节点并行执行，层间 barrier 同步 [代码已确认]
- 出现在：core/engine、UI PipelineView（按 step 分组渲染）、ExecutionTrace.NodeTrace.step

## channel
- 代码名：`ChannelDefinition` / `WorkflowContext` / `ChannelValue`
- 含义：节点间数据传递的命名管道——上游节点输出写入 channel，下游 prompt 用 `${channel名}` 占位符引用；同名并发写由 Reducer 策略合并 [代码已确认]
- 出现在：core/dsl、core/engine、demo YAML

## Reducer
- 代码名：`Reducer` 枚举 + `ChannelReducer`
- 含义：channel 并发写的合并策略：OVERWRITE（默认）/CONCAT/MAX/CUSTOM（注册函数）[代码已确认]
- 出现在：core/dsl、core/engine

## 两级 Checkpoint（KTD-3）
- 代码名：`saveNodeOutput` / `saveBarrier`
- 含义：节点级（Agent 完成当下立即持久化，防 super-step 中途崩溃致 LLM 重复计费）+ barrier 级（super-step 合并后持久 channel 快照）；失败层不写 barrier [代码已确认]
- 出现在：core/engine/checkpoint（CodeGraph impact: CheckpointManager 528 affected symbols——全仓影响面最大接口）

## 路由决策（routing decision）
- 代码名：`workflow_routing_decisions.decisions` / `findRoutingDecisions` / `takenEdges`
- 含义：v2 条件路由的已走边列表（`from->to` 编码，`EdgeDefinition.edgeKey` 单一编码源），checkpoint 的单一真相源——恢复期据此 BFS 重算可达集，不复活 SKIPPED 节点 [代码已确认]
- 出现在：checkpoint、BspEngine.recoverAndExecute、V4/V8 迁移

## 回边（back edge / loop edge）
- 代码名：`EdgeDefinition.loop` / `maxIterations` / `runRounds`
- 含义：v2 循环——指向更早节点的条件边，必须带 when（退出条件）+ max_iterations（有界性）；执行时引擎外层迭代轮次循环，双 active 集合（active 前向 / nextActive 回边目标累积）[代码已确认]
- 出现在：core/dsl 校验、BspEngine、demo-loop（draft→critique→draft 反思循环）

## round（迭代轮次）
- 代码名：`ExecutionState.round` / `NodeTrace.round`
- 含义：回边引入的迭代维度——checkpoint 唯一约束加 round，恢复期做轮次转换检测（该轮回边已命中→round++ 从层 0 续跑）[代码已确认]
- 出现在：checkpoint、BspEngine、V5 迁移

## on_error 兜底
- 代码名：`NodeDefinition.onError(goto)`
- 含义：v2 节点级失败跳转——逆转「任一失败即 abort」不变量：失败跳 cleanup、正常下游 SKIPPED、cleanup 自身失败不二次跳转；作为隐式边纳入环校验与分层 [代码已确认]
- 出现在：core/dsl、BspEngine、demo-conditional

## HITL（Human-in-the-Loop）审批
- 代码名：`ApprovalRequired` / `AWAITING_APPROVAL` / `approveAndResume`
- 含义：工作流执行中断→人工决策→恢复的全链路：节点抛 ApprovalRequired → 引擎暂停（存上下文快照）→ 审批人 APPROVE/REJECT → 从审批单续跑 [代码已确认]
- 出现在：core/engine、core/engine/checkpoint、api（ApprovalController + ApprovalCenterController）、UI 审批中心 Tab

## tryClaim（原子幂等）
- 代码名：`CheckpointManager.tryClaim` / `TRY_CLAIM_SQL`
- 含义：单条条件 UPDATE（仅 PENDING→RUNNING 命中）判定执行权——并发消费恰一胜出，防重复投递双跑双计费 [代码已确认]
- 出现在：checkpoint（default 方法 + InMemory compute + Postgres SQL 三层）、kafka-starter 消费入口

## R22 列加密
- 代码名：`ColumnEncryptor` / `AesGcmColumnEncryptor`（`AESGCM:` 前缀）/ `fromEnvStrict`
- 含义：静态列级 AES-256-GCM 加密，覆盖 5 处敏感 JSONB→TEXT 列；生产装配 fail-closed（缺 AGENTFLOW_ENCRYPTION_KEY 拒绝启动）[代码已确认]
- 出现在：core/security、两个 Postgres store、starter

## 提交守卫（Submission Guard）
- 代码名：`WorkflowSubmissionGuard`
- 含义：提交时预防性拦截（节点数>500 或预估成本超限 → 422 SUBMISSION_LIMIT），与运行后 budget_exceeded 告警构成事前/事后两层 [代码已确认]
- 出现在：api/security、WorkflowController.submit

## AgentFunction（KTD-6）
- 代码名：`AgentFunction` / `AgentInput` / `AgentOutput`
- 含义：Agent 调用的框架无关窄接口——所有 LLM 框架调用收敛到适配器实现，引擎只认此接口 [需求已确认]（`docs/plans/agentflow/03-key-technical-decisions.md` KTD-6）；CodeGraph 验证 **6 个实现类**：SpringAi/LangChain4j 两适配器 + MockAgentFunction + demo-rag RagAgentFunction + core ApprovalGateAgent + core DryRunMockAgentFunction [代码已确认]
- 出现在：core/agent、两个适配器、mock、RAG

## 适配器窄表面（KTD-7）
- 代码名：`SpringAiAgentAdapter` / `LangChain4jAgentAdapter`
- 含义：可移植性约束——所有框架 API 调用收敛在适配器一个类内，框架升级只碰适配器；LC4j 适配器依赖面仅 core+langchain4j 构建级证明 [需求已确认]（KTD-7）
- 出现在：agentflow-adapters/*

## mock fallback（KTD-1）
- 代码名：`withMockFallback`（UI api.ts）/ `MockAgentFunction`
- 含义：UI 真实 API 优先、失败降级 mock 数据防白屏；**写操作无 mock fallback**（防假成功）、5xx 不降级（防 corrupt 500 被掩盖）[代码已确认]
- 出现在：agentflow-ui/src/lib/api.ts

## 执行语义收敛（KTD-B）
- 代码名：`WorkflowExecutionService` / `WorkflowDispatcher`
- 含义：dispatch（怎么派发：本地 VT/Kafka）与 execute（怎么执行：RUNNING→engine→终态）两层分离——两 dispatcher + retry + 审批恢复共享同一执行语义，防路径漂移 [代码已确认]（CodeGraph: run 11 callers）
- 出现在：agentflow-api、agentflow-kafka-starter

## unstaged 消息守卫
- 代码名：`KafkaWorkflowConsumer#onMessage` 的 findStatus 判空分支
- 含义：从未 initWorkflow 的任意 workflowId 消息直接丢弃——防绕过提交接口往 topic 注入伪造执行记录/放大成本 [代码已确认]
- 出现在：agentflow-kafka-starter
