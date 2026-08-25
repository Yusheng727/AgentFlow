# 结论 → 证据索引

> 每份流程文档关键结论的证据速查（详细规则级证据见各文档第 13 节）。

| 结论 | 所在文档 | 可信度 | 证据（文件#符号） |
|---|---|---|---|
| 提交链路：解析→授权→守卫→落库→派发→202 | flows/workflow-lifecycle.md | 已确认 | api/WorkflowController.java#submit + CodeGraph callers dispatch |
| 提交前预防拦截：节点数/成本上界 → 422 | flows/workflow-lifecycle.md | 已确认 | api/security/WorkflowSubmissionGuard.java#check |
| 工具未授权提交 → 403（不落执行记录） | flows/workflow-lifecycle.md | 已确认 | WorkflowController.java:176-188 + CallerToolAllowlist |
| 仅创建者可查/重试（防 IDOR，403） | flows/workflow-lifecycle.md | 已确认 | api/security/WorkflowOwnershipChecker.java#requireOwnership |
| 仅 FAILED 可 retry；retry 先复位 PENDING 再派发 | flows/workflow-lifecycle.md | 已确认 | WorkflowController.java#retry + WorkflowControllerTest#retryResetsToPendingBeforeDispatch:259 |
| retry 走整体重跑（run→execute，非断点续跑） | flows/workflow-lifecycle.md / crash-recovery-retry.md | 已确认 | WorkflowExecutionService.java#run |
| Kafka 消费幂等：tryClaim 原子 PENDING→RUNNING + 终态跳过 + 未 staged 丢弃 | flows/workflow-lifecycle.md | 已确认 | KafkaWorkflowConsumer#onMessage + TRY_CLAIM_SQL + CheckpointManagerTest#tryClaimConcurrentExactlyOneWins + KafkaDispatchE2eIT#replayDoesNotReRun |
| 定义按 (name, version) 提交存/执行取（旧实例按旧 DAG） | flows/workflow-lifecycle.md | 已确认 | WorkflowController.java:202-206 + WorkflowVersionManager |
| 审批暂停：兄弟输出入快照、不写 barrier、置 AWAITING_APPROVAL | flows/hitl-approval.md | 已确认 | BspEngine#applyBarrier + BspEngineApprovalPauseTest 4 断言 |
| 审批人 = 创建者 ∪ admin；decidedBy 服务端推导防伪造 | flows/hitl-approval.md | 已确认 | ApprovalController#canAccess/#decide |
| 决策原子幂等：confirmApproval 条件转移，已决策 no-op | flows/hitl-approval.md | 已确认 | CheckpointManager#confirmApproval + InMemoryCheckpointManagerApprovalTest#concurrentConfirm:82 + BspEngineApprovalResumeTest#secondApproveIsNoop |
| REJECT → FAILED 下游不跑；APPROVE 注入决策重跑 + 续跑 | flows/hitl-approval.md | 已确认 | BspEngine#approveAndResume + BspEngineApprovalResumeTest 6 断言 |
| 待办 API 精简投影（payload/snapshot 不下发） | flows/hitl-approval.md | 已确认 | ApprovalController#ApprovalView + ApprovalCenterView |
| 聚合端点：非 admin 空数组非 403 + 单 wf 损坏跳过隔离 | flows/hitl-approval.md | 已确认 | ApprovalCenterController#pending |
| 恢复定位查崩溃层本身（nextSuperStep，off-by-one 修复） | flows/crash-recovery-retry.md | 已确认 | RecoveryProtocol#recover + BspEngineRecoveryTest 3 断言 |
| FAILED 状态 → 崩溃层整体重跑（stray 防护） | flows/crash-recovery-retry.md | 已确认 | RecoveryProtocol:91-101 + RecoveryProtocolTest 正反两面断言:104/122 |
| 崩溃层已完成节点跳过 + 输出按 Reducer 重放进 context | flows/crash-recovery-retry.md | 已确认 | ExecutionState + BspEngine#applyReplayOutput + recoverReplaysCrashLayerOutput:52 |
| AWAITING_APPROVAL 拒绝崩溃恢复（两路径隔离） | flows/crash-recovery-retry.md | 已确认 | BspEngine#recoverAndExecute:452-455 + BspEngineApprovalPauseTest#recoveryRefusesAwaitingApproval:221 |
| 恢复不复活 SKIPPED；轮次转换（回边命中入下一轮） | crash-recovery / dynamic-routing | 已确认 | BspEngine#computeReachable + RecoveryConditionalTest#recoveryDoesNotReviveSkippedNode:67 + RecoveryLoopTest 4 断言 |
| 路由：声明序首 true、无命中无默认 Fatal | flows/dynamic-routing-and-loops.md | 已确认 | BspEngine#resolveTakenEdges + BspEngineConditionalTest#twoWayBranch/#noMatchFails |
| 谓词错误 ≠ 不命中（Fatal 可诊断）；沙箱禁 T()/反射 | flows/dynamic-routing-and-loops.md | 已确认 | PredicateEvaluator#evaluate |
| 回边三件套 + 退出边 + 方向 + 喂回 OVERWRITE（解析期强制） | flows/dynamic-routing-and-loops.md | 已确认 | SemanticValidator.java:72-123 |
| 迭代上限双保险（max_iterations + 全局 1000） | flows/dynamic-routing-and-loops.md | 已确认 | BspEngine#checkIterationCap + BspEngineLoopTest#iterationCapFails:89 |
| 路由决策只存已走边（单一真相源），先于 barrier 落盘 | flows/dynamic-routing-and-loops.md | 已确认 | CheckpointManager 注释:171-174 + BspEngine#runStep:881 |
| on_error 逆转 abort；目标自身失败不二次跳转；FALLBACK 指标终态 | flows/dynamic-routing-and-loops.md | 已确认 | BspEngine#applyBarrier:910-918 + BspEngineOnErrorTest 4 断言（onErrorTargetFailureIsFatal:96） |
| WorkflowStatus 五态（含 AWAITING_APPROVAL，V7 DDL） | 全部流程文档 | 已确认 | checkpoint/WorkflowStatus.java + V7 迁移 |
| NodeStatus 三态；COMPLETED 不可覆盖、FAILED 可升级 | flows/crash-recovery-retry.md | 已确认 | checkpoint/NodeStatus.java + InMemoryCheckpointManager:69 |
| 状态机全部生产写入点 = 8 处（CodeGraph 枚举闭环） | flows/crash-recovery-retry.md | 已确认 | CodeGraph callers updateStatus（27 处中生产 8：retry/run/execute/recoverAndExecute/approveAndResume/applyBarrier/onMessage/接口 default） |
| recoverAndExecute 生产零调用（恢复无编排，Q2 图上可证） | flows/crash-recovery-retry.md | 已确认 | CodeGraph callers recoverAndExecute（9 处全为测试） |
| Kafka retry 复位与消费认领并发安全 | flows/workflow-lifecycle.md | 已确认 | TRY_CLAIM_SQL + retryResetsToPendingBeforeDispatch + tryClaimConcurrentExactlyOneWins + replayDoesNotReRun（三重证据，v2 由推断升级） |
| 同层双路由节点激活同一后继仅执行一次 | flows/dynamic-routing-and-loops.md | 已确认 | active Set 语义 + joinRunsWhenBranchSkipped:67（v2 由推断升级） |
| 决策 API→服务→引擎四级链路单一入口 | flows/hitl-approval.md | 已确认 | CodeGraph：resumeAfterApproval 唯一 caller=decide:90；approveAndResume 唯一生产 caller=resumeAfterApproval:95 |
| 崩溃恢复与 retry 并发（当前不可达——恢复无生产调用方） | flows/crash-recovery-retry.md | 合理推断 | CodeGraph 恢复零生产调用 + retry 代码（未来接入编排时需评估） |
| 生产 Kafka offset 策略需运维确认 | open-questions.md Q1 | 待确认 | — |
| 恢复触发编排（自动 vs 人工）待确认 | open-questions.md Q2 | 待确认 | — |

### 第二批（2026-08-26，7 能力）

| 结论 | 所在文档 | 可信度 | 证据（文件#符号） |
|---|---|---|---|
| 授权判定 = config ∪ DB，双空才 allow-all；DB 有记录即强制 | flows/tool-authorization.md | 已确认 | CallerToolAllowlist#isAllowed + CallerToolAllowlistTest 10 断言 |
| 工具授权强制点唯一 = 提交时逐节点校验；拒绝 403 不落记录 | flows/tool-authorization.md | 已确认 | CodeGraph: isAllowed 生产唯一调用点 submit:152 + WorkflowController:176-188 |
| grant/revoke admin-only；未配 admin key 全 403（安全默认）；幂等 INSERT | flows/tool-authorization.md | 已确认 | ToolGrantController + GRANT_SQL(WHERE NOT EXISTS) |
| 通配 `*` 授全部工具（config 与 DB 双侧语义一致） | flows/tool-authorization.md | 已确认 | hasTool + IS_GRANTED_SQL |
| Kafka 装配属性门控：不 enabled 零 bean（回落本地 VT，v1 行为） | flows/kafka-async-dispatch.md | 已确认 | @ConditionalOnProperty + defaultDispatcher |
| wire = String 承载 JSON + starter 自持 Jackson2 mapper（JavaTime/ISO） | flows/kafka-async-dispatch.md | 已确认 | KafkaAgentFlowAutoConfiguration#agentflowKafkaObjectMapper |
| 消息 key=workflowId 同分区保序；submit/retry 双路径统一 dispatch | flows/kafka-async-dispatch.md | 已确认 | KafkaWorkflowDispatcher#dispatch + CodeGraph: dispatch 生产恰两处 |
| tryClaim 原子认领恰一执行 + 未 staged 丢弃 + 非引擎异常兜底 FAILED | flows/kafka-async-dispatch.md | 已确认 | TRY_CLAIM_SQL + onMessage + KafkaDispatchE2eIT |
| offset 默认 earliest（订阅前消息不丢；幂等使重扫安全） | flows/kafka-async-dispatch.md | 已确认 | KafkaAgentFlowAutoConfiguration:74/113-116 |
| 定义按 (name, version) 快照；恢复/retry 按旧版本执行；缺失 3 次退避不进 RUNNING | flows/workflow-versioning.md | 已确认 | WorkflowVersionManager + loadDefinitionWithRetry + VersionTest |
| 版本冲突 WARN 不阻断（在途实例按旧 DAG 跑完） | flows/workflow-versioning.md | 已确认 | VersionConflictDetector + versionCheck 端点 |
| 生产定义存储与 checkpoint 双 strict 加密（缺 key 启动失败） | flows/workflow-versioning.md / column-encryption.md | 已确认 | AgentFlowAutoConfiguration:100-114 |
| 提交守卫双上界（节点数 500 默认/预估成本）→ 422 不落记录 | flows/cost-budget-control.md | 已确认 | WorkflowSubmissionGuard#check + GuardTest 12 断言 |
| per-workflow 预算 = 记账告警非阻断（硬防护在提交前——两道防线分工决议） | flows/cost-budget-control.md | 已确认 | WorkflowBudget 无中止 + recordBudget + CLAUDE.md 拍板 |
| edge-triggered 超限恰一次 + synchronized 线程安全 + 双维度独立 | flows/cost-budget-control.md | 已确认 | WorkflowBudget#record + WorkflowBudgetTest 12 断言 |
| trace 仅创建者 + 未注册 404；三执行入口都注册（不与 checkpoint 状态分裂） | flows/observability-diagnosis.md | 已确认 | TraceController + BspEngine:209/424/606 |
| 终态指标恰一次（outcomeRecorded + finally 兜底；paused 不记终态） | flows/observability-diagnosis.md | 已确认 | BspEngine#execute:218-277 |
| 诊断 6 类问题（循环重复豁免；Token 阈值 3×均值且>100） | flows/observability-diagnosis.md | 已确认 | DiagnosisService#diagnose + DiagnosisServiceTest 6 断言 |
| 干跑零 LLM 零生产调用（CodeGraph 证实 4 处全测试） | flows/observability-diagnosis.md | 已确认 | DryRunEngine + CodeGraph: callers dryRun |
| 加密生产 fail-closed（缺 key 启动失败）+ dev Noop 宽松 + key 只从 env | flows/column-encryption.md | 已确认 | ColumnEncryptors#build + ColumnEncryptorsTest |
| 密文 AESGCM:iv:ct 自描述 + legacy 明文前缀兼容 + GCM 篡改检测 + 每次新 IV | flows/column-encryption.md | 已确认 | AesGcmColumnEncryptor + IT 真密文证据 |
| 扩列范围 = 5 处敏感列（CodeGraph: toEncryptedJson 恰 5 生产写点闭环） | flows/column-encryption.md | 已确认 | PostgresCheckpointManager×4 + PostgresWorkflowDefinitionStore×1 + V7/V8 DDL |
| RAG 引擎零改动（纯 AgentFunction 扩展点，KTD-6 实证） | flows/rag-retrieval.md | 已确认 | RagEngineZeroChangeTest + demo 无 core 改动 |
| 检索确定性（词袋+余弦）+ 0 分过滤 + 无命中不硬造上下文 + 全字段透传委托 | flows/rag-retrieval.md | 已确认 | RagAgentFunction + InMemoryVectorStore + 4 断言测试 |
| 真实 LLM fail-fast（缺 key 启动失败不静默回落）+ 成本入账 | flows/rag-retrieval.md | 已确认 | RagDemoConfig#realDelegate + RagRealLlmIT |
| 授权 DB 不可用 → 提交 fail-fast（无静默放行降级） | flows/tool-authorization.md | 合理推断 | JdbcToolGrantRepository 无 catch（按异常传播推断） |
| 授权回收时点语义（不追溯在途工作流） | flows/tool-authorization.md | 合理推断 | 提交校验时点 + 无运行中撤销机制 |
| 提交侧「落库+发消息」无 outbox（发送失败悬空 PENDING） | flows/kafka-async-dispatch.md | 合理推断 | submit 顺序 + 未发现 outbox（Q6 待确认） |
| 守卫估算不含循环轮次（循环工作流实际成本可数倍于估算） | flows/cost-budget-control.md | 合理推断 | estimateCost 无 round 维度 |
| Kafka send 异步确认策略待定 | open-questions.md Q6 | 待确认 | — |
| key 轮换机制缺失 | open-questions.md Q10 | 待确认 | — |

## 维护说明

- 新增/修改流程文档时同步更新本索引；
- 证据失效（文件删除/重构改名）时优先更新索引与文档第 13 节。
