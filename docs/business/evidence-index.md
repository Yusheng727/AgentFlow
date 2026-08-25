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

## 维护说明

- 新增/修改流程文档时同步更新本索引；
- 证据失效（文件删除/重构改名）时优先更新索引与文档第 13 节。
