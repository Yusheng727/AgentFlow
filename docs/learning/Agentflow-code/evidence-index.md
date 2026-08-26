# 结论 → 证据索引

> target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92（snapshot 2026-08-25，CodeGraph 增强版）。每条关键结论的证据锚点速查。

| 结论 | 所在文档 | 可信度 | 证据（文件#符号 / CodeGraph / commit） |
|---|---|---|---|
| AgentFlow 定位=从0复现展示后端工程能力（七三开） | system-overview.md | [需求已确认] | `docs/plans/agentflow/01-problem-frame.md` |
| BSP 循环=Plan→Execute(VT 并行)→Barrier→Checkpoint | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java` 类 javadoc |
| 提交→执行主链 8 跳（Controller→Guard→Dispatcher→Service→Engine→runRounds→runStep→AgentFunction） | system-overview.md | [代码已确认] | CodeGraph callers 逐跳：WorkflowDispatcher 10 callers / run 11 callers / BspEngine 95 callers |
| HTTP 入口 13 条路由全清单 | system-overview.md | [代码已确认] | CodeGraph query "route"（13 route 节点）+ 各 Controller @RequestMapping |
| AgentFunction 6 实现（SpringAi/LC4j/Mock/Rag/ApprovalGate/DryRunMock） | technical-architecture.md | [代码已确认] | CodeGraph query "AgentFunction"（6 个 implements import + RagAgentFunction class 声明） |
| runRounds 恰 3 callers=三入口共享骨架 | technical-architecture.md | [代码已确认] | CodeGraph callers "runRounds"（execute:192 / recoverAndExecute:411 / approveAndResume:581） |
| resolveTakenEdges 3 callers（recover/approve/updateReachability）路由求值单点复用 | technical-architecture.md | [代码已确认] | CodeGraph callers "resolveTakenEdges" |
| applyBarrier/updateReachability 唯一 caller=runStep（barrier 内聚） | technical-architecture.md | [代码已确认] | CodeGraph callers "applyBarrier" / "updateReachability" |
| BspEngine 全仓扇入最高（95 callers，31 测试类） | module-map.md | [代码已确认] | CodeGraph explore "high fan-in"（BspEngine 条目） |
| CheckpointManager 改动影响面最大（528 affected symbols） | module-map.md | [代码已确认] | CodeGraph impact "CheckpointManager" |
| SpelPromptResolver 两适配器 namespace 共用（单一真相源） | technical-architecture.md | [代码已确认] | CodeGraph callers "SpelPromptResolver"（adapters.langchain4j + adapters.springai） |
| WorkflowExecutionService 26 callers 分布 api/kafka-starter/demo-api 三层 | system-overview.md | [代码已确认] | CodeGraph explore "WorkflowExecutionService"（blast radius 条目） |
| Kafka 消费幂等三防（重放/并发/unstaged） | system-overview.md | [代码已确认] | `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java#onMessage` |
| tryClaim 原子条件转移防并发双跑 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java` TRY_CLAIM_SQL + CodeGraph callers "tryClaim" |
| retry 在 Kafka 模式先复位 PENDING 防幂等吞消息 | product-intent-hypotheses.md | [代码已确认]+[历史已确认] | `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java#L322` 注释 + ce-code-review 记录 |
| 定义重取 3×1s 容忍窗口 | product-intent-hypotheses.md | [代码已确认] | `agentflow-api/src/main/java/com/agentflow/api/WorkflowExecutionService.java#L25-L26` |
| 动态路由=可达性剪枝不放弃 BSP、checkpoint 存路由决策 | technical-architecture.md | [历史已确认] | commit `93fab2a`+`4866249`+`db1eb99` |
| 回边=条件边+loop 标记+有界校验+分层豁免 | glossary.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/dsl/EdgeDefinition.java`（2 便捷构造器向后兼容）+ `SemanticValidator.java` |
| 轮次转换检测（回边命中→round++ 从层 0 续跑） | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#recoverAndExecute` 内 thisRoundHasBackedge 逻辑 |
| HITL 暂停存上下文快照+AWAITING_APPROVAL 不兜底 FAILED | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L219`（paused 标志+finally 判断） |
| approveAndResume 行为由 9 测试锁定（resume/reject/再暂停/兄弟输出/边预置） | technical-architecture.md | [代码已确认] | CodeGraph callers "approveAndResume"（9 测试方法于 BspEngineApprovalResumeTest） |
| recoverAndExecute 对 AWAITING_APPROVAL 拒绝误恢复 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L452` |
| R22 列加密 AESGCM: 前缀自描述+legacy 明文兼容 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/security/AesGcmColumnEncryptor.java` + CodeGraph callers "encrypt"（10 调用点全在 store 边界/测试） |
| 生产 fail-closed（缺 key 拒启动）双 strict 装配 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/security/ColumnEncryptors.java` + `agentflow-starter/src/main/java/com/agentflow/starter/AgentFlowAutoConfiguration.java` |
| V8 迁移 JSONB→TEXT 承载密文+显式 USING | glossary.md | [代码已确认] | `agentflow-core/src/main/resources/db/migration/V8__r22_encrypt_routing_and_definitions.sql` |
| 提交守卫 422 事前拦截+预算事后告警两层 | technical-architecture.md | [代码已确认] | `agentflow-api/src/main/java/com/agentflow/api/security/WorkflowSubmissionGuard.java` + `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java#L190` |
| 审批聚合空数组非 403（不泄漏存在性） | product-intent-hypotheses.md | [代码已确认] | `agentflow-api/src/main/java/com/agentflow/api/ApprovalCenterController.java` |
| decidedBy 服务端推导防伪造 | product-intent-hypotheses.md | [历史已确认] | CLAUDE.md U6 交付记录 + `agentflow-api/src/main/java/com/agentflow/api/ApprovalController.java` |
| UI 写操作无 mock fallback 防假成功 | product-intent-hypotheses.md | [代码已确认] | `agentflow-ui/src/lib/api.ts`（decideApproval 无包裹 vs 读端点全包裹） |
| UI 读操作 5s 超时降级 mock 防白屏（KTD-1） | system-overview.md | [代码已确认] | `agentflow-ui/src/lib/api.ts#withMockFallback` |
| SpEL 加固（forPropertyAccessors+禁 T()） | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/prompt/SpelPromptResolver.java` |
| 谓词求值错误按 Fatal 非 false | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/prompt/PredicateEvaluator.java` |
| spring-kafka 4.1 废弃 JsonSerializer→String 承载+自持 ObjectMapper @Bean | technical-architecture.md | [代码已确认]+[历史已确认] | `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaAgentFlowAutoConfiguration.java#agentflowKafkaObjectMapper` + commit `5c6b75c` |
| 节点级 checkpoint Semaphore(20) 限流同步写 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java` |
| 预算 edge-triggered 不重复自增 | technical-architecture.md | [代码已确认] | `agentflow-core/src/main/java/com/agentflow/observability/WorkflowBudget.java` |
| 框架知识外移（composed classifier 注入） | technical-architecture.md | [历史已确认] | commit `18978e8` |
| 共享件下沉 core（SpelPromptResolver/OutputSchemaValidator） | technical-architecture.md | [历史已确认] | commit `9ba7271` |
| 6 demo=每引擎能力配最小验收示例 | product-intent-hypotheses.md | [需求已确认] | `docs/plans/agentflow/05-implementation-units.md` |
| JaCoCo INSTRUCTION BUNDLE ≥80% 全模块门禁 | README.md（本档案） | [代码已确认] | `pom.xml` jacoco check 规则 |
| Redis compose 声明但业务代码零依赖 | module-map.md | [合理推断]+[待确认] | `docker-compose.yml` 有服务 + CodeGraph query "redis" 无业务符号命中（Q1） |
