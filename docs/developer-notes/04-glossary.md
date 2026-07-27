# 术语表

> 讲项目时不卡壳的速查。每个术语一句话解释 + 关键属性。
> 面试时如果口误或卡壳，回来扫一眼纠偏。

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
