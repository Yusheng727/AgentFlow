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
| U6 API + demo | ✅ 编码+测试 | `ApprovalControllerTest` 8 + `WorkflowExecutionServiceHitlTest` 4 绿；api 94 + demo-api 6 绿 |
| U7 R22 加密 | ✅ 编码+测试 | `AesGcmColumnEncryptorTest` 6 + `ColumnEncryptorsTest` 6 + `PostgresCheckpointManagerEncryptionTest` 3 绿；core 363 绿 + JaCoCo met |
| U8 demo-rag | ✅ 编码+测试 | `InMemoryVectorStoreTest` 4 + `RagAgentFunctionTest` 3 + `RagEngineZeroChangeTest` 2 绿（KTD-6 引擎零改动证明） |
| U9 文档 | ✅ 已同步 | CLAUDE.md / ROADMAP / developer-notes / README / handoff |

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
- **U6 API + demo（2026-08-20 落地）**：① `WorkflowExecutionService.run()` U4 paused 后**不误标 SUCCESS**（查 status 若 AWAITING_APPROVAL 则 return 留待批态）+ 新增 `resumeAfterApproval(wfId, name, version, approvalId, decision, decidedBy)` → `approveAndResume` → 返回恢复后状态 ② 新建 `ApprovalController`（`/api/workflows/{wfId}/approvals`）：`GET .../pending` 待批列表（**精简投影** `ApprovalView`：approvalId/nodeId/description/status/createdAt——requestPayload/contextSnapshot 存库不**下发 API**，review P2 防敏感载荷旁路泄漏）+ `POST .../{approvalId}` 决策 ③ **decidedBy 服务端推导**：请求体仅 `{decision}`，客户端 decidedBy 一律忽略，`decidedBy` = caller X-API-Key hash（review P1 防审批审计身份伪造）④ 访问控制：创建者（ownership）或 admin（`AGENTFLOW_ADMIN_API_KEYS` 门控）可查/批；approval 归属 workflow 校验（不匹配 400）⑤ demo-api `ApiConfig.nodeRegistry` 注册 `approval` → `ApprovalGateAgent`（core 已有，demo 复用不重复建）⑥ `ApiKeyAuthFilter.sha256` 改 public（ApprovalController admin hash 用）。
- **U7 R22 列级加密（2026-08-20 落地）**：`com.agentflow.security` 新建 4 类——`ColumnEncryptor`（接口 encrypt/decrypt）+ `NoopColumnEncryptor`（恒等，单例）+ `AesGcmColumnEncryptor`（AES-256-GCM，12B 随机 IV，`AESGCM:<ivB64>:<ctB64>` 自描述，GCM 完整校验篡改抛错，key 从 base64(32B) `SecretKeySpec` 显式派生防工厂漂移）+ `ColumnEncryptors` 工厂（`fromEnv()` 宽松：缺/非法 key → Noop+warn 明文落库 dev/demo；`fromEnvStrict()` 生产 fail-closed：缺/非法 → 抛异常拒绝明文落地；抽 package-private `build(key, strict)` 供测试直测，避免改写进程 env）。`PostgresCheckpointManager` 加可空 `ColumnEncryptor` 字段 + 新构造（默认 Noop），`saveNodeOutput/saveBarrier/saveApprovalRequest` 写用 `toEncryptedJson`、读（`findLatestBarrier/findCompletedNodes/approvalRowMapper`）用 `decryptRaw`（**decrypt 对非 `AESGCM:` 前缀值原样返回**——legacy 明文行兼容，KTD-E1）；`saveRoutingDecisions`（`::jsonb`）**不**加密。`AgentFlowAutoConfiguration.postgresCheckpointManager` 注入 `ColumnEncryptors.fromEnvStrict()` **生产装配确定接入 strict**。引擎/DSL/InMemory 零感知（加密是存储层关注点，KTD-E1/E2）。
- **U8 demo-rag（2026-08-21 落地）**：新模块 `demo-rag/` —— `InMemoryVectorStore`（doc 列表 + **确定性 token 集合 embedder**：按空白切 token + 余弦相似度 top-k，离线可测零外部向量服务；空库/空 query/k 超界防御）+ `RagAgentFunction`（AgentFunction：query → top-k 检索 → 拼增强 prompt（`[上下文]` 段 + 原文 query）→ 委托 wrapped agent（mock/真实）；委托输出透传、抛异常原样透传）+ `RagDemoConfig`/`RagDemoApplication`（NodeRegistry 注册 `rag` + 内置知识库，默认 mock wrapped 零密钥）+ 根 pom 登记模块（demo 惯例 `<jacoco.skip>true</jacoco.skip>`）。**KTD-6 核心证明**：`RagEngineZeroChangeTest` 用 `BspEngine` 直跑 `agent: rag` 节点工作流，引擎/DSL 零改动即成功（捕获增强 prompt 断言拖取真实发生）。**实现注**：中文无空格分词限制——tokenSet 按空白切，demo 文档/query 用词间空格；真实 LLM 接入（`agentflow.rag.real.*`）与把 rag 接进 demo-api REST 的跨模块依赖均为 **Deferred**（demo-rag 自包含可跑，避免 demo-api→demo-rag 模块耦合）。
- **剩余 Deferred**：审批「恢复」在 Kafka（跨节点）模式接线（本地 dispatcher 语义已可用，跨节点记 Deferred）；R22 扩展加密到 `workflow_definitions`/`routing_decisions`（聚焦 checkpoint 敏感列，其余 Deferred）；审批 Web UI / 前端审批面板；RagAgentFunction 真实模型接入。
- **最后的 ce-code-review** + `mvn verify` 全仓绿 + push 远程 main。

## 风险与注意（接手前必读）

- **`::jsonb` 已从 `saveNodeOutput`/`saveBarrier` 移除**（output/channel 现为 TEXT）；`saveRoutingDecisions` 仍在 `workflow_routing_decisions`（V4 未改 = JSONB 合法）——**不要**去掉那个 `::jsonb`。
- **H2 vs 真 PG 分离**：H2 测试手建兼容表跑静态 SQL；真 PG IT 跑 Flyway V1–V7。V7 的 `workflow_executions_status_check` DROP 依赖 PG 自动命名，H2 测试建表时直接写全枚举。
- **JaCoCo**：U3 新代码（Postgres 审批方法）需 ≥85% 才过 verify；PostgresCheckpointManager 新方法必须有足够行覆盖（H2 或真实 IT）。
- **epic**.审批快照不得公开，`GET /pending` 返回投影；`requestPayload` 不写 trace/日志（U6 脱敏）。