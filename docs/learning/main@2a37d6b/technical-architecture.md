# 技术架构学习笔记

> 分析时间：2026-08-25 23:55 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：snapshot
> 取证方式：CodeGraph（调用链逐跳验证：callers/callees/impact/query route）+ 源码精读 + git 历史
> 分析范围：BSP 执行模型、DSL/引擎/checkpoint 分层、SPI 扩展点、容错与幂等、安全设计
> 未覆盖/不可访问区域：Spring AI 2.0 / LangChain4j 框架内部实现（只分析适配器侧用法）；SonarCI 后台

## 分层与依赖方向（带证据）

**单向依赖、core 零框架**是全仓最重要的结构不变量：

- `agentflow-core` 的 pom **不依赖 Spring AI / LangChain4j / spring-kafka**，只依赖 slf4j/Jackson/networknt（schema 校验）+ 测试域 Spring [代码已确认]（core 主代码 `com.agentflow.*` 无任何 `org.springframework.ai`/`dev.langchain4j` import；两适配器反向依赖 core；CodeGraph: import 节点按 namespace 验证无跨层泄漏）。
- 依赖方向：`adapters/* → core ← api ← kafka-starter ← starter ← demo-api`，UI 经 HTTP 调 api [代码已确认]（各模块 pom 依赖声明 + CodeGraph callers 跨模块分布验证：如 WorkflowExecutionService 的 26 callers 恰好分布在 kafka-starter/api/demo-api 三层）。
- **框架知识外移纪律**：`ErrorClassifier.composed()` 组合分类器在 core，但「LC4j 的哪些异常是 transient」这类框架知识由 LC4j 适配器注入、Spring 前缀由 Spring 适配器注入 [历史已确认]（commit `18978e8` B2+M2：*框架异常知识全部移出 core，core 保持框架无关*）。
- **共享件下沉**：SpelPromptResolver / OutputSchemaValidator 原在 Spring 适配器私有，后下沉 core `com.agentflow.prompt` 成为两框架单一真相源 [代码已确认]（CodeGraph callers "SpelPromptResolver"：恰好 com.agentflow.adapters.langchain4j 与 com.agentflow.adapters.springai 两个 namespace 引用）+ [历史已确认]（commit `9ba7271` C2）。

## 识别到的设计模式（每个模式指出具体类/方法 + CodeGraph 调用证据）

1. **SPI + 策略**：`AgentFunction`（CodeGraph 验证 **6 实现**：SpringAiAgentAdapter/LangChain4jAgentAdapter/MockAgentFunction/demo-rag RagAgentFunction/core ApprovalGateAgent/core DryRunMockAgentFunction——零改动互换）、`CheckpointManager`（InMemory/Postgres/Noop 三实现）、`ColumnEncryptor`（AesGcm/Noop）、`WorkflowDispatcher`（CodeGraph: 接口 10 callers——LocalVirtualThreadDispatcher 默认 + KafkaWorkflowDispatcher 生产可换）、`WorkflowDefinitionStore`（InMemory 19 callers + Postgres 10 callers）[代码已确认]。
2. **可空注入的渐进增强（null-object 变体）**：`BspEngine` 构造器链 3→4→5→6 参逐层加 retryPolicy/errorHandler/timeoutPolicy/traceRegistry/metrics（CodeGraph node: 5 个构造器方法），任一 null = 该横切能力关闭、行为与旧版一致——95 个 callers 中旧调用方零破坏 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L96-L127` + 字段注释「null = 不重试，backward compat with U2」）。
3. **条件转移幂等（compare-and-swap on DB）**：`tryClaim` 单条 `UPDATE workflow_executions SET status='RUNNING' WHERE id=? AND status='PENDING'`，用影响行数判定执行权 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java` TRY_CLAIM_SQL package-private 单一真相源 + `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java#onMessage` 消费入口；CodeGraph callers "tryClaim"：接口 default + InMemory compute + 测试锁定并发恰一胜出）。
4. **自描述密文前缀 + legacy 兼容**：`AesGcmColumnEncryptor` 密文带 `AESGCM:` 前缀；`decrypt` 对非前缀值原样返回（升级前明文行不炸），加密开关平滑上线 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/security/AesGcmColumnEncryptor.java`；CodeGraph callers "encrypt"：10 个调用点全部在 store 读写边界与自身测试）。
5. **宽严双工厂（fail-closed 装配）**：`ColumnEncryptors.fromEnv()`（dev 宽松→Noop+warn）与 `fromEnvStrict()`（生产缺 key→抛异常拒绝明文落库），starter 生产模式用 strict [代码已确认]（`agentflow-core/src/main/java/com/agentflow/security/ColumnEncryptors.java` + `agentflow-starter/src/main/java/com/agentflow/starter/AgentFlowAutoConfiguration.java` 生产装配）。
6. **barrier 语义的确定性合并**：同 super-step 并发写 channel 由 `ChannelReducer` 按节点声明序合并（OVERWRITE/CONCAT/MAX/CUSTOM），并发执行但合并确定 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/ChannelReducer.java` javadoc「按节点声明序逐个 merge，保证确定性」）。
7. **复制消除（shared skeleton）**：`runRounds` 被 execute / recoverAndExecute / approveAndResume 三入口复用 [代码已确认]（CodeGraph callers "runRounds"：**恰 3 个、全部在 BspEngine.java 内**——execute:192/recoverAndExecute:411/approveAndResume:581）；注释明言「消除『复制必然漂移』历史 P0 模式」[历史已确认]（commit `e29c4cc`）。同构地，`resolveTakenEdges` 3 callers（recoverAndExecute/approveAndResume/updateReachability）——路由求值单点复用。
8. **命令-查询分离的守卫（pre-flight validation）**：`WorkflowSubmissionGuard` 在提交时预估成本/节点数上界（422 拒绝），与运行后的 `budget_exceeded` 记账形成「事前拦截+事后告警」两层 [代码已确认]（`agentflow-api/src/main/java/com/agentflow/api/security/WorkflowSubmissionGuard.java` + `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java#L190` 调用点）。
9. **执行语义收敛（dispatch/execute 分离）**：`WorkflowExecutionService#run` 11 callers——本地 VT dispatcher、Kafka consumer、retry 路径复用同一「RUNNING→execute→SUCCESS/FAILED」语义，防两处分发路径漂移 [代码已确认]（CodeGraph callers "run" + 类 javadoc KTD-B「把执行收敛到一处」）。

## 扩展点与隔离点

**扩展点（加新不改老）**：
- 新 LLM 框架：实现 `AgentFunction` 即可，引擎/DSL/持久化零改动——由 demo-rag 用 `RagAgentFunction`（BspEngine 直跑 `agent: rag`）实证 [历史已确认]（commit `9d09e6e`：*验证 KTD-6「引擎层零改动、Agent 扩展点成立」*；CodeGraph: RagAgentFunction implements AgentFunction，demo-rag 模块仅依赖 core）。
- 新 Reducer：`ChannelReducer` 构造传 `Map<channel, Function>` 注册 CUSTOM [代码已确认]。
- 新分发通道：实现 `WorkflowDispatcher`（CodeGraph: 接口被 WorkflowController 注入消费、两个实现分别被各自 autoconfig 装配——加第三通道不动 controller）[代码已确认]。

**隔离点（变化不扩散）**：
- **适配器窄表面**（KTD-7）：所有 Spring AI 调用收敛在 `SpringAiAgentAdapter` 一个类（CodeGraph: callLlm 唯一 caller 在同类内）；框架 2.0→2.1 迁移只碰适配器 [需求已确认]（`docs/plans/agentflow/03-key-technical-decisions.md` KTD-7）+ [代码已确认]（LC4j 适配器 pom 只依赖 core+langchain4j，构建级证明可替换）。
- **加密边界在 store 层**：加解密只发生在 `PostgresCheckpointManager`/`PostgresWorkflowDefinitionStore` 读写边界（CodeGraph: toEncryptedJson/decryptRaw 调用点全部在两 store 内），业务层见到的永远是明文 [代码已确认]。
- **谓词求值错误≠false**：`PredicateEvaluator` 求值异常或非 boolean 结果按 Fatal 处理，只有成功求值为 false 才「不命中」——路由不静默吞错 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/prompt/PredicateEvaluator.java` 77 行；CodeGraph: 2 个 evaluate 重载，caller 恰为 BspEngine.predicateEvaluator 字段与测试）。
- **审批与崩溃恢复互斥**：`recoverAndExecute` 对 AWAITING_APPROVAL 显式抛 `IllegalStateException` 拒绝恢复，`approveAndResume` 才处理审批态——两条恢复路径不串台 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L452`）。

## 性能与稳定性设计（缓存/异步/重试/幂等/锁/事务边界）

- **Virtual Threads 并行 + allOf barrier**：同层节点 `Executors.newVirtualThreadPerTaskExecutor()` 并行跑，`CompletableFuture.allOf` 等全部完成才进 barrier——BSP「最快等最慢」语义 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java` javadoc + runStep 实现）。
- **checkpoint 写限流**：Postgres 实现用 `Semaphore(20)` 限并发写，COMPLETED 同步写（防 LLM 重复计费优先于吞吐）[代码已确认]（KTD-3 v4.3 决议落点，`agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java`）。
- **重试预算组合式**：3 attempt × 内含 schema-retry ≤2 = 9 上限，指数退避 1s→2s→4s 仅 transient [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/fault/RetryPolicy.java`；CodeGraph: NodeExecutor 21 callers 中 RetryPolicy 是包裹方）。
- **双保险迭代上限**：per-loop `max_iterations` + 引擎 `MAX_TOTAL_ROUNDS=1000` 硬上界，防未声明上界/逻辑 bug 死循环 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L78` + `checkIterationCap`）。
- **Kafka at-least-once 幂等**：消费先 tryClaim（原子条件转移）再执行；终态跳过防重放重复计费；unstaged 任意 id 丢弃防 ledger 污染 [代码已确认]（`agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java#onMessage`）。
- **定义重取的瞬时容忍**：`WorkflowExecutionService` 对定义缺失做 3 次重试 ×1s backoff（DEF_LOOKUP_RETRY/DEF_LOOKUP_BACKOFF_MS 常量），为跨节点 read-after-write 留容忍窗口 [代码已确认]（`agentflow-api/src/main/java/com/agentflow/api/WorkflowExecutionService.java#L25-L26` + javadoc「为未来跨节点留容忍窗口」）。
- **预算 edge-triggered**：`WorkflowBudget` 超限首次触发一次 `budget_exceeded`，不按节点数重复自增 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/observability/WorkflowBudget.java` record/isExceeded/isActive 6 members）。

## 框架能力的关键用法（不是罗列框架清单）

- **Spring 7 SpEL 加固用法**：`forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess(), new MapAccessor())` 同时支持 record 组件+嵌套 Map 键访问；Builder 不设 TypeLocator/MethodResolver → T() 与方法调用全禁（防 SpEL 注入）[代码已确认]（`agentflow-core/src/main/java/com/agentflow/prompt/SpelPromptResolver.java` + U3 关键发现记录）。
- **Spring AI 2.0 mutable advisor deque 坑**：`.chatResponse()` 与 `.content()` 各触发一次 advisor 链，第二次撞空 deque → 适配器只调一次 `chatResponse()` 从同一响应取 content+usage [历史已确认]（U3 关键发现，commit 记录于 developer-notes）。
- **spring-kafka 4.1 废弃 JsonSerializer → String 承载 JSON**：废弃类不用，用 StringSerializer + 项目自持 ObjectMapper（wire 格式可控 + 补 JavaTimeModule 禁时间戳数组）[代码已确认]（`agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaAgentFlowAutoConfiguration.java#agentflowKafkaObjectMapper` 自持 mapper @Bean）+ [历史已确认]（commit `5c6b75c`）。
- **Micrometer PercentileHistogram**：`recordNodeDuration` 开 `publishPercentileHistogram` 暴露 `_bucket` 序列，Grafana 用 `histogram_quantile` 算 P50/P95/P99 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java` + Grafana 交付记录）。

## v1→v2 演进中最值得学的机制（个人标注）

1. **动态路由不放弃 BSP**：静态图仍预分层，运行时「可达性剪枝」——每层只跑可达节点，被切断的标 SKIPPED；checkpoint 只持久化路由决策（已走边），恢复期用「源节点+已走边」BFS 重算可达集，不复活 SKIPPED、不重复计费 [历史已确认]（commit `93fab2a`+`4866249`+`db1eb99` 三段交付；CodeGraph: updateReachability 唯一 caller 是 runStep——路由决策在 barrier 内聚点收口）。
2. **回边=条件边+loop 标记+指向更早节点**：`EdgeDefinition(from,to,when,loop,maxIterations)` 单 record 承载 v1/v2 两代语义（+两个便捷构造器向后兼容），`resolveTakenEdges` 求值逻辑零改动复用；分层豁免回边保 BSP 不变量 [历史已确认]（commit `432a5f2`–`9162074` 五段交付）。
3. **HITL 三态机**：execute 遇 `ApprovalRequired` → 存上下文快照+审批单 PENDING+AWAITING_APPROVAL 后 paused 退出（finally 不兜底 FAILED——`BspEngine.java#L219` paused 标志）；审批经 `approveAndResume` 注入 `approvalDecision` 重跑待批节点，从审批层续跑 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L555-L634` javadoc+实现；CodeGraph: approveAndResume 的 9 个 caller 全部为测试方法——resume/reject/再暂停/兄弟输出保留/takenEdges 预置每语义一测，行为锁定密度高）。
