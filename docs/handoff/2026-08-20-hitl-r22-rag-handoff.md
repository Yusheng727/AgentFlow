# HITL + R22 + RAG — 实施交接（2026-08-20，中途快照）

> 本交接文档记录 `feat/hitl-r22-rag` 分支的**当前中途状态**，供新会话接力。
> 权威计划：`docs/plans/2026-08-20-001-feat-hitl-r22-rag-plan.md`（U1–U9，已过 ce-doc-review 自审吸收 review findings）。
> 完成标准：全仓 `mvn -s settings.xml verify` 绿 + JaCoCo 达标 + commit + push 远程 main。

## 当前状态（已推送本分支）

**分支**：`feat/hitl-r22-rag`（已从 main 切出，本地领先远程 1 commit）

| 单元 | 状态 | 验证 |
|:---|:---|:---|
| U1 HITL 核心类型 | ✅ 编码+测试 | `ApprovalHITLTypesTest` 7/7 绿；core 全量 330 tests 绿 |
| U2 审批持久化 SPI + InMemory | ✅ 编码+测试 | `InMemoryCheckpointManagerApprovalTest` 7/7 绿 |
| U3 V7 迁移 + Postgres 审批 + TEXT | 🟡 编码完成、**H2/真 PG 测试未写** | V7 SQL + Postgres 审批 4 方法 + 去 `::jsonb` 已改；**PostgresCheckpointManagerTest（H2）与 PostgresCheckpointManagerIT（真 PG）待补** |
| U4 引擎暂停 | ✅ 编码+测试 | `BspEngineApprovalPauseTest` 6/6 绿；core 全量 340 tests 绿 |
| U5 引擎恢复 | ✅ 编码+测试 | `BspEngineApprovalResumeTest` 8/8 绿；core 全量 348 tests 绿 |
| U6 API + demo | ⛔ 未开始 | — |
| U7 R22 加密 | ⛔ 未开始（V7 列型已就位） | — |
| U8 demo-rag | ⛔ 未开始 | — |
| U9 文档 | ⛔ 未开始 | — |

**关键基线**：U1–U3 改动后 core 全量测试 **330 全绿**（无回归）。分支已 commit + push（commit `feat(approval): U1 审批类型 + U2 InMemory SPI + U3 V7 迁移/Postgres 审批`）。

## 已落地的设计决策（实现期，review 吸收）

- **`ApprovalDecision` 唯一真相源 = `com.agentflow.engine.checkpoint.ApprovalDecision`**；`com.agentflow.engine.ApprovalDecision` 是早期占位重复枚举，已标 `@Deprecated`，**不应使用**（rm 权限未放开，无法删除文件，故保留占位而非物理移除）。
- **`NodeResult` 三态**：`Success | Failure | ApprovalRequired(nodeId, description, requestPayload)`；引擎在暂停时才用异常数据 + 上下文快照构造完整 `ApprovalRequest`（workflowId/round/superStep 由引擎填）。`NodeExecutor` 在 ExecutionException 分支先判 `cause instanceof ApprovalRequiredException`。
- **`RetryPolicy` 第三 permit 透传**（修 unchecked cast CCE）：`ApprovalRequired` 不重试、原样返回。
- **`AgentInput` 提前加 `approvalDecision`（ApprovalDecision，可空）**——本计划 U4 才需要，但为让 core 占位 `ApprovalGateAgent` 编译提前落地（纯增量，11-arg 便捷构造委托 null，零回归）。
- **`WorkflowStatus` 加 `AWAITING_APPROVAL`**；**V7 迁移**把 `workflow_executions.status_check` 扩枚举 + 建 `workflow_approvals` 表 + checkpoint 敏感列 `JSONB→TEXT`（R22 静态加密先决）。
- **U4 引擎暂停（2026-08-20 落地）**：`BspEngine.applyBarrier` 返回私有 record `BarrierResult(onErrorTargets, paused)`——识别 `NodeResult.ApprovalRequired` 即**审批优先暂停**（同层兄弟 Fail 也不 abort，兄弟 Success 输出已并入 context 作快照）→ `ApprovalRequest.pending(workflowId, nodeId, round, step.index, description, requestPayload, flattenContext(context))` 一次落库 + `updateStatus(AWAITING_APPROVAL)` + `metrics.recordApprovalEvent(pending)`，**不写路由决策/barrier**（该层未完成）。`runStep`/`runRounds` 透出 paused（runRounds 返回 boolean），`execute`/`recoverAndExecute` 暂停时 trace 记 `AWAITING_APPROVAL`、finally **不兜底 FAILED**（`!outcomeRecorded && !paused`）。`ExecutionTrace.Status` 加 `AWAITING_APPROVAL`；`AgentFlowMetrics` 加 `WORKFLOW_APPROVAL_EVENT` 指标族 + `recordApprovalEvent`（pending/approved/rejected，Grafana 面板留待 U9）。**防御**：`recoverAndExecute` 对 `AWAITING_APPROVAL` 抛 `IllegalStateException` 拒绝误恢复（U5 `approveAndResume` 才处理）。
- **两层占位注意事项（记此）**：
  1. `F:\Java\JAVAcode\agent-flow\...\ApprovalRequest.java` 是**路径笔误**在仓库外（`F:\Java\JAVAcode\agent-flow` 是 main 的兄弟目录，非本仓库），不编译、不进 git；**手工删除**。
  2. `engine/ApprovalRequired.java` 是从 NodeResult 占位改造来的 `@Deprecated` 空类（原引用了非 permitted 实现会编译失败）。

## 划的 Decided Deferred（尚未实现、需新会话完成）

- **U5 引擎恢复（2026-08-20 落地）**：`BspEngine.approveAndResume(def, agentResolver, reducer, cp, workflowId, approvalId, decision, decidedBy)`——**从审批单恢复，不依赖 RecoveryProtocol**（崩溃恢复是另一条路径）。流程：① `findApprovalById` 无记录 → `IllegalStateException`（明确报错）；`confirmApproval` 原子 PENDING→终态，已决策 → 幂等 no-op 返回快照 context ② REJECT → `updateStatus(FAILED)` + trace/metrics rejected，下游不跑 ③ APPROVE → 重建 context（快照含兄弟输出）→ `updateStatus(RUNNING)` → **重跑待批节点**（12-arg AgentInput 注入 `approvalDecision=APPROVE` + round，走 retryPolicy；ApprovalRequired 原样透传 → 再次 saveApprovalRequest + AWAITING_APPROVAL 多级审批链；Failure → FAILED）→ `applyOutput` merge 真实输出 → **补记其路由决策**（U4 暂停走 paused 分支未调 updateReachability，`resolveTakenEdges` 重算入 takenEdges）→ `runRounds` 续跑：startRound=req.round()、startLayer=req.superStep()、**firstExcluded=审批层节点全集**（兄弟输出已在快照不重跑、审批节点已 merge，整层剔除防双跑）、active=`computeReachable`（源 → takenEdges）、**takenEdges 预置自 `findRoutingDecisions(wf, round)`**（防 saveRoutingDecisions UPSERT 覆盖丢边，review P2）、超时基线重定 `Instant.now()`（review 决议）。收尾 SUCCESS/FAILED 复用 `outcomeRecorded`/finally 不兜底 paused。
- **U7 R22**：`ColumnEncryptor`（AES-GCM）+ `fromEnv()/fromEnvStrict()` fail-closed + AESGCM 前缀 legacy 兼容 + AutoConfiguration strict 接线。
- **U8 demo-rag**、**U6 API**、**U9 doc**。
- **最后的 ce-code-review** + `mvn verify` 全仓绿 + push 远程 main。

## 风险与注意（接手前必读）

- **`::jsonb` 已从 `saveNodeOutput`/`saveBarrier` 移除**（output/channel 现为 TEXT）；`saveRoutingDecisions` 仍在 `workflow_routing_decisions`（V4 未改 = JSONB 合法）——**不要**去掉那个 `::jsonb`。
- **H2 vs 真 PG 分离**：H2 测试手建兼容表跑静态 SQL；真 PG IT 跑 Flyway V1–V7。V7 的 `workflow_executions_status_check` DROP 依赖 PG 自动命名，H2 测试建表时直接写全枚举。
- **JaCoCo**：U3 新代码（Postgres 审批方法）需 ≥85% 才过 verify；PostgresCheckpointManager 新方法必须有足够行覆盖（H2 或真实 IT）。
- **epic**.审批快照不得公开，`GET /pending` 返回投影；`requestPayload` 不写 trace/日志（U6 脱敏）。