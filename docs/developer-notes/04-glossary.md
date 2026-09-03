# 术语表

> 讲项目时不卡壳的速查。每个术语一句话解释 + 关键属性。
> 术语口径速查——口误或卡壳时回来扫一眼纠偏。

---

## 执行模型

### BSP（Bulk Synchronous Parallel）
批量同步并行。工作流按 DAG 分层成 super-step，每层内节点并行执行（互不可见），barrier 同步后合并输出，再进下一层。AgentFlow 的核心执行模型（KTD-1）。

### super-step
BSP 的执行单元。DAG 按最长路径分层，每层是一个 super-step，0-based 编号（level 0 = 入度为 0 的节点）。同一 super-step 内节点并行，跨 super-step 串行。

### barrier（屏障）
super-step 内所有节点完成后的同步点。barrier 处按节点声明序应用 Reducer 合并 channel 到全局 context，然后写 barrier checkpoint，再进下一 super-step。

### channel
Agent 间传递数据的命名管道。Agent 输出写 channel，下游 Agent 用 SpEL `${channel名}` 读。同一 super-step 内多节点写同一 channel 用 Reducer 合并。

### Reducer
channel 合并策略。OVERWRITE（后写覆盖）/ CONCAT（连接）/ MAX（取最大）/ CUSTOM（自定义函数）。按节点声明序应用，保证确定性。

### ChannelValue
channel 值的包装（value + version）。version 用于审计/幂等，v1 静态 DAG 不使用，v2 条件分支可能用。

---

## 持久化

### Checkpoint
工作流执行状态的持久化快照，用于崩溃恢复。AgentFlow 两级：节点级 + barrier 级（KTD-3）。

### 节点级 checkpoint
单个 Agent 执行完成当下立即持久化其 AgentOutput（防 LLM 重复计费）。同步写 + Semaphore(20) 限流。

### barrier 级 checkpoint
super-step barrier 合并后持久化 channel 快照。`super_step` 字段记录已完成的 super-step 编号。失败层不写 barrier。

### RecoveryProtocol
崩溃恢复协议。查 `latestBarrier.step = k` → `nextSuperStep = k+1`，查崩溃层（nextSuperStep）COMPLETED 节点跳过 + 重放输出。off-by-one 修复：查 nextSuperStep 不是 nextSuperStep-1。

### ExecutionState
Recovery 的输出：`nextSuperStep` + `channelSnapshot`（来自上一 barrier）+ `completedNodeIds`（崩溃层已完成，跳过）+ `replayOutputs`（崩溃层已完成节点的输出，重放进 channel——P0 修复）。

### stray COMPLETED 记录
timeout abort 后，在飞 VT 仍完成并写出 COMPLETED 到已 abort 的 super-step。这些记录未经 barrier 合并，Recovery 查到 FAILED 状态时整体重跑崩溃层忽略它们（P0 修复 ADV-2）。

### Semaphore(20)
PostgresCheckpointManager 的并发限流器。VT 并发 50+ 时限制同时在飞的 DB 写入 ≤ 20（对齐 HikariCP maxPoolSize），防连接池耗尽。

### ON CONFLICT 幂等
PostgreSQL 的 upsert 语法。`ON CONFLICT (...) DO UPDATE ... WHERE status<>'COMPLETED'`——COMPLETED 终态不可覆盖，FAILED/IN_PROGRESS 可升级为 COMPLETED。

---

## Agent 层

### AgentFunction
Agent 的函数式接口：`execute(AgentInput) -> AgentOutput` + `cancel()` 默认 noop + 异常合约（throws AgentExecutionException，区分 Transient/Fatal）。

### AgentInput / AgentOutput
Agent 的输入输出契约。Input 含 context（只读快照）+ inputs（启动入参）+ tools + outputSchema。Output 含 content + channelWrites + structuredOutput + metadata。

### TransientException / FatalException
Agent 异常分类。Transient（网络超时/429 限流）触发重试；Fatal（400 参数错误/SpEL 错误）直接进 ErrorHandler 不重试。

### NodeRegistry
`Map<String, AgentFunction>` 的来源，按 agent name 查找。U3 引入。

### SpEL prompt 模板
YAML 节点的 `prompt_template` 用 `${...}` 引用 context 变量，SpEL `SimpleEvaluationContext` 解析，禁 `T()` 防注入（KTD-2）。

### SpringAiAgentAdapter
AgentFunction 的 Spring AI 实现。SpEL 解析 + ChatClient.call + 异常映射 + cancel best-effort。所有 Spring AI 调用收敛在此（KTD-7 可移植性）。

### Advisor Chain
Spring AI 的拦截器链。TokenCountingAdvisor（接 Micrometer）+ LoggingAdvisor（写 ExecutionTrace）+ OutputSchemaValidator（schema 校验 + 反馈重试）。

---

## 容错

### TimeoutPolicy
节点级 + 工作流级超时。节点超时触发 `future.cancel(true)` 中断 VT + `AgentFunction.cancel()`；工作流总超时 abort 整个工作流。

### RetryPolicy
指数退避重试。1s→2s→4s，max 3 次，仅 transient。retry 预算与 schema-retry 共享计数器（3×(1+2)=9 上限）。

### ErrorClassifier
异常分类器。IOException/Timeout/网络 → Transient；SpEL + 其余 → Fatal。adapter.mapException 委托它（单一真相源）。

### ErrorHandler
abort 前的 context 补偿逻辑（如写 `errorHandled=true`）。v1 只能改 context，不能跳转路径（v2 动态路由）。

### WorkflowExecutionException
BSP 引擎的聚合失败异常。含 superStep 索引 + failures 列表。abort 时抛出，U5 P0 修复后 abort 前调 updateStatus(FAILED)。

---

## 工程化

### JaCoCo 80% 门禁
代码覆盖率强制门禁，INSTRUCTION 维度 BUNDLE 级。从 U1 起每个有代码的模块 < 80% → `mvn verify` 失败。

### ce-code-review
compound-engineering 插件的多 agent code review skill。10 个 persona reviewer（correctness/testing/maintainability/security/performance/api-contract/data-migration/reliability/adversarial/agent-native）并行审 diff，cross-reviewer agreement 提权，confidence anchor 门控。

### KTD（Key Technical Decision）
计划文档里的关键技术决策。KTD-1 BSP、KTD-3 两级 checkpoint、KTD-6 AgentFunction cancel、KTD-7 Spring AI 适配器可移植性 等 9 个。

### DAG layerer
`DAGLayerer.computeSuperSteps`，最长路径分层算法。`level[v]=max(level[u])+1`，把 DAG 转成 0-based super-step 序列。

### Virtual Threads
Java 21 轻量级线程。~KB 级，可创建数千个，JVM 调度到 carrier thread。AgentFlow 用 `Executors.newVirtualThreadPerTaskExecutor()` 并行执行 super-step 内节点。

---

## 状态

### WorkflowStatus
工作流执行状态枚举：PENDING → RUNNING → SUCCESS | FAILED。FAILED 可由节点级失败 abort 触发（U4）+ abort 路径显式标记（U5 P0 修复）。

### NodeStatus
节点级 checkpoint 状态：IN_PROGRESS | COMPLETED | FAILED。COMPLETED 终态不可覆盖，FAILED/IN_PROGRESS 可升级为 COMPLETED。

### workflow_executions / workflow_node_outputs / workflow_checkpoints
U5 的三张表。 executions = 工作流实例元数据（状态/版本）；node_outputs = 节点级 checkpoint；checkpoints = barrier 级 checkpoint。

---

## 可观测性（U7）

### ExecutionTrace
执行追踪树（U3 引入，U7 穿线）。根（workflow 级）+ 子（每节点 NodeTrace）。`Snapshot` 是不可变冻结视图，供 TraceController/DiagnosisService 读取。线程安全：CopyOnWriteArrayList 承载子 trace，volatile 承载根级 end/status。

### NodeTrace
节点执行 trace。构造时记 start，`succeed(outputSummary, promptTokens, completionTokens)` / `fail(error)` 记终态。Status: RUNNING → SUCCESS | FAILED。

### ExecutionTraceRegistry
按 workflowId 集中存放 ExecutionTrace（U7 引入，ConcurrentHashMap）。BspEngine.execute 开头 `register(workflowId)`，通过 AgentInput.trace() 透传给 AgentFunction，TraceController 从此取 snapshot。v1 不清理（v1.1 加 TTL）。

### trace 穿线（KTD-2）
BspEngine 5-arg 构造器注入 registry → execute() 注册 trace → AgentInput 第 9 字段透传 → MockAgentFunction/SpringAiAgentAdapter 从 input.trace() 取 trace 写 NodeTrace。不用 ThreadLocal（VT 脆弱），用 record 字段显式传。

### AgentFlowMetrics
5 Micrometer 指标封装：workflow.executed（Counter）/ node.duration（Timer）/ tokens.consumed（Counter，tag agent+model）/ workflow.cost.estimated（Counter，tag model）/ workflow.cost.budget_exceeded（Counter）。

### CostCalculator
token×模型单价表。三层定价：代码默认价 → classpath agentflow-cost-pricings.json → 程序化 override()。warn-once 去重未知 model。cost(model, promptTokens, completionTokens) 返回 USD。

### TraceController
`GET /api/workflows/{id}/trace` REST 端点（U7）。注入 WorkflowOwnershipChecker 防 IDOR（非创建者 403），从 ExecutionTraceRegistry 取 snapshot 返回。mock 模式也返回完整树（KTD-2 补齐）。

---

## 前端 UI

### React 5 Tab UI
agentflow-ui 模块，React 18 + TypeScript + Vite + Tailwind CSS。5 Tab：Dashboard / Submit / WorkflowDefinitions / PipelineView / DiagnosisPanel。真实 API 优先 + mock fallback（KTD-1）。

### mock fallback（KTD-1）
api.ts 封装 fetch，先调真实 REST API，后端不可达时降级 mockData（setTimeout 模拟）。保证 UI 独立可用不白屏。

### PipelineView
BSP Pipeline 可视化组件。按 super-step 分组渲染节点卡片，barrier 用分隔线——体现"同层并行 + barrier 同步"的 BSP 语义。

### YamlEditor
contenteditable + 语法高亮 + 行号 + 实时校验（缺 nodes/agentflow 段警告）。提交工作流前校验 YAML 结构。

---

## 档 B 收尾 + v1.1（2026-08）

### WorkflowSubmissionGuard（提交守卫）
`POST /api/workflows` 提交时的预防性校验（档 B 安全缺口 #2）：DAG 节点数 > maxNodes 或预估成本 > maxCostUsd → **422 SUBMISSION_LIMIT**。在 initWorkflow 前拒绝（不产生执行记录）。`model/maxNodes/maxCostUsd` 任一 null 即禁用对应检查。

### WorkflowBudget（per-workflow 预算累加器，R10）
`budget_tokens`/`budget_cost` 声明在 YAML `agentflow:` 段，BspEngine 构造 `WorkflowBudget` 经 `AgentInput.budget()` 穿线。`record()` **edge-triggered**：只在首次跨过上界返回 true（不按节点数重复记 `budget_exceeded`）。synchronized 线程安全（超步内多节点并行记账）。

### budget_exceeded（per-workflow 语义）
`agentflow.workflow.cost.budget_exceeded` Counter。R10 后含义是"该工作流跨过自己声明的预算"，而非旧的"全局累计成本超全局阈值"。

### LangChain4jAgentAdapter（v1.1 第二适配器）
核心 AgentFunction 的第二个 LLM 框架实现，用 LangChain4j 1.0.0、**零 Spring AI**。窄表面对齐 `SpringAiAgentAdapter`：SpEL → `ChatModel.chat(ChatRequest)` → TokenUsage → AgentOutput。裸 ChatModel 无内置工具闭环 → 适配器手写工具执行循环（≤5 轮防死循环，usage 跨轮累加）。

### SpelPromptResolver（core 复用件）
prompt 模板 `${...}` 占位符的 SpEL 解析器，KTD-2 安全约束（SimpleEvaluationContext 禁 T()/反射）。v1.1 从 spring-ai 适配器**下沉 core**（`com.agentflow.prompt`）供两适配器共用——框架无关件单一真相源。

### Prometheus exporter / actuator
demo-api 接 `micrometer-registry-prometheus` + actuator，暴露 `/actuator/prometheus` scrape 端点（U7），Prometheus 从这里抓 `agentflow_*` 指标。移除手写 SimpleMeterRegistry，统一交给 Boot 自动装配的 PrometheusMeterRegistry。

### publishPercentileHistogram
`recordNodeDuration` 的 Timer 开此开关，暴露 `_bucket` 序列 → Grafana `histogram_quantile` 算节点耗时 P50/P95/P99（count/sum/max 语义不变）。

## HITL + R22 + RAG（2026-08，feat/hitl-r22-rag）

### AWAITING_APPROVAL（审批暂停态）
`WorkflowStatus` 枚举成员。引擎在 super-step barrier 识别 `NodeResult.ApprovalRequired` 后置此态——**合法中间态**（非 SUCCESS/FAILED），`run()` 不覆盖它、`recoverAndExecute` 拒绝误恢复、失去引用计时的"工作流总超时"也不计（恢复时重定基线）。批准续跑至 SUCCESS/FAILED，拒绝→FAILED。

### ApprovalRequiredException / ApprovalRequired（NodeResult 三态）
Agent 在需要人类决策时抛 `ApprovalRequiredException`（`ApprovalGateAgent` 首跑 `approvalDecision()==null` 即抛）；`NodeExecutor` 转成 `NodeResult.ApprovalRequired` 第三态（区别于 Success/Failure）。RetryPolicy 对它**不重试、原样透传**（防 unchecked cast 把审批吞成 Failure）。

### 上下文快照（contextSnapshot）
暂停时把当前 WorkflowContext 的 channel 扁平视图（**含兄弟 Success 输出、不含审批节点**）随审批单落库。恢复时用它重建 context → 兄弟不重跑（防 LLM 重复计费，KTD-3 在 HITL 的镜像），只重跑待批节点。

### AesGcmColumnEncryptor（R22 列级加密）
AES-256-GCM，12B 随机 IV，格式 `AESGCM:<ivB64>:<ctB64>` **自描述前缀**。GCM 自带完整性（篡改→decrypt 抛错）。key 从 base64(32B) `SecretKeySpec` 显式派生（防工厂漂移）。`decrypt` 对非 `AESGCM:` 前缀值**原样返回**——legacy 明文行兼容（升级前数据读得动）。key 只从 env `AGENTFLOW_ENCRYPTION_KEY` 读。

### fromEnv() vs fromEnvStrict()（加密工厂）
`ColumnEncryptors.fromEnv()` 宽松（dev/demo）：缺/非法 key → `NoopColumnEncryptor` + warn（明文落库）。`fromEnvStrict()` 生产 fail-closed：缺/非法 key → 抛异常**拒绝明文落地**。`AgentFlowAutoConfiguration` 生产装配确定接 strict。

### RagAgentFunction（demo-rag）
Agent 侧实现「检索 → 增强 → 委托」：query → `InMemoryVectorStore.topK` → 拼增强 prompt（`[上下文]` 段 + 原文 query）→ 委托 wrapped agent。**KTD-6 证明**：BspEngine 毫不知情能编排所有 AgentFunction，`RagEngineZeroChangeTest` 直跑 `agent: rag` 节点零改动成立。

### InMemoryVectorStore（确定性 embedder）
doc 列表 + **按空白切 token 的集合 + 余弦相似度**作检索，离线可测零外部向量服务。限定：中文无空格不分词（demo 文档用词间空格）。真实 embedding/向量库属于 InterviewCoach（分工不重复）。

