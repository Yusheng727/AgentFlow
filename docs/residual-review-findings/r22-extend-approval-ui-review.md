# R22 扩列 + 审批中心 ce-code-review 残留项（需要修复/决策）

> 来源：`ce-code-review`（2026-08-24，11 评审：correctness/testing/maintainability/project-standards/agent-native/learnings-researcher + security/api-contract/adversarial/data-migration/deployment-verification-agent），`mode:agent` 报告制，`base:8540a1c`（main fast-forward 至 `d450f35` 后 review）。
> 对应功能：`feat/r22-extend-approval-ui` 系列 commit（U1–U5 已合 main；U6 ce-code-review 本为计划门禁，此前漏跑——本次补跑）。
> 本文件为「评审 foundings + 待修复/待决策」的残留台账；修复后逐项标 ✅ 并回填 `developer-notes/03-review-findings.md`。
> 更新日期：2026-08-24。

---

## 结论摘要

- **无 P0**。核心加密往返（routing_decisions / workflow_definitions 的 save↔find 加解密、双 strict 生产装配、聚合端点鉴权）correctness 与 security 双确认一致、认证/鉴权边界 sound。
- **1 个 P1**（聚合端点无 per-workflow 错误隔离 → 单行 corrupt 500 级联 + mock 掩盖）。
- **7 个 P2**（多为「系统方案」隐藏落子：恒空契约字段 / 迁移照抄漏 USING / 测试空洞）。
- **若干 P3 / advisory**（语义漂移 / N+1 已 Deferred / key 轮换爆炸面扩大）。

---

## P1 — 高影响（建议修复后再 merge 或明确 residual）

### 1. [P1/manual] ✅ **已修复（2026-08-24）** 聚合端点无 per-workflow 错误隔离：单行 corrupt → 整个 /api/approvals/pending 500（+ mock 掩盖）
- **现状（已修复）**：① `ApprovalCenterController.pending()` 加 per-workflow try/catch——单 wf 待批查询（`findPendingApprovals`）抛异常时跳过该 wf、记 warn，其余 wf 仍返回（200 部分结果，非整体 500）；② UI `withMockFallback` 新增 `preventServerErrorMock` 选项，`listPendingApprovals` 对 HTTP 5xx 不降级 mock（防 corrupt 500 被演示 mock 列表掩盖），抛 `ApiError` 由 ApprovalCenter 显 error toast；③ 新增 `ApprovalCenterControllerTest.corruptWorkflowRowSkippedNotWhole500`（mock 单 wf 抛异常 → 断言 200 + 剩其余 wf）+ `api.test.ts` 5xx 不 mock 用例。

---

## P2 — 中影响（多为「系统方案」的隐性落点）

### 2. [P2/gated_auto] ✅ **已修复（2026-08-24）** `ApprovalCenterView.workflowName` 恒为 null——投影字段在真实路径从不填充，仅 mock 填
- **现状（已修复）**：`ApprovalCenterView.of(r)` 改为 `of(r, wf.workflowName())` 传真实工作流名——外层循环已有 `WorkflowExecutionRecord wf`（含 workflowName）；UI ApprovalCard 展示 `workflowName ?? workflowId`。新增 `ApprovalCenterControllerTest.workflowNamePopulatedFromRecord` 断言真实路径 workflowName 非空（7 个 reviewer 命中项）。

### 3. [P2/manual] ✅ **已修复（2026-08-24）** V8 迁移漏 `USING x::text`——与声明的 V7 先例不一致
- **现状（已修复）**：`V8__r22_encrypt_routing_and_definitions.sql` 两处 `ALTER COLUMN TYPE TEXT` 补 `USING decisions::text` / `USING definition::text`，与 V7（`USING output::text` / `USING channel_values::text`）逐字对齐——显式 cast 而非依赖隐式 assignment cast。

### 4. [P2/manual] ✅ **已修复（2026-08-24）** starter 生产双 strict 装配测试空洞——`assertThatCode` 包恒绿，测不出 fail-closed
- **现状（已修复）**：`StarterIntegrationTest.productionModeWithKeyRegistersBothEncryptedStores` 移除「catch-all 吞异常 + doesNotThrowAnyException() 恒绿」——改为真实断言：① 定义存储 `postgresWorkflowDefinitionStore(ds)` 在 mock DataSource 下完整实例化成功（类型断言）；② checkpoint `postgresCheckpointManager(ds)` 在 mock DataSource 下确实抛 RuntimeException（证明走了 Flyway 连真库路径，而非吞错恒绿）。strict「缺 key 抛错」语义仍由 core `ColumnEncryptorsTest` build seam 覆盖（构造不因缺 key 糊弄）。

### 5. [P2/downstream] ✅ **已修复（2026-08-24）** 两处新加密写路径（save→toEncryptedJson）无门禁测试，默认 mvn verify 零覆盖
- **现状（已修复）**：`VersionTest.postgresStoreEncryptsWriteAlwaysOn` 用 `FakeJdbcTemplate` + 真实 `AesGcmColumnEncryptor`（不依赖 PG/IT/ON CONFLICT）——断言 save 落库的原始值是 `AESGCM:` 密文（不含明文子串）且 find 解密还原（always-on 写路径密文形态）；`postgresStoreNoopPlaintext` 锁 Noop 明文回归。routing_decisions 写路径密文形态仍归真 PG IT（H2 无法跑 ON CONFLICT），CI 需保证 PG service 实跑（deployment 块）——此项覆盖定义存储写路径，routing 侧留待 PG。

### 6. [P2/manual] ✅ **已修复（2026-08-24）** `hashKeys` / admin-key 门控逻辑复制——三处逐字重复
- **现状（已修复）**：新增 `com.agentflow.api.security.AdminApiKeys`（单一真相源：`from(csv)` 解析 → SHA-256 哈希集合 + `isAdmin/isEmpty`），`ApprovalCenterController` / `ApprovalController` / `ToolGrantController` 三处原逐字拷贝全部切到共享组件，删除各 `hashKeys` 私有方法。

### 7. [P2/downstream] ✅ **已修复（2026-08-24）** 直接构造 Postgres 存储默认 Noop 无 warn——静默绕加密
- **现状（已修复）**：`PostgresWorkflowDefinitionStore(DataSource, ObjectMapper)` 与 `PostgresCheckpointManager(DataSource)` / `(DataSource, ObjectMapper)`（未显式传 encryptor 的 public 构造）均补 `log.warn` 提示「未注入 ColumnEncryptor → R22 列加密不生效（明文落库）」，直接实例化该 store 的 embedder 不再无声降级。

---

## P3 / advisory（记录备查，多为语义/维护性）

### 8. [P3/advisory] `fromEnv()` 宽松 fallback 明文——一个 miswire 全降级（pre_existing）
- `ColumnEncryptors.fromEnv()` 缺 key 返回 Noop（仅 warn）；若生产 wiring 复用宽松工厂即静默明文。建议 strict 为唯一生产路径。跨 reviewer：security (pre_existing)。

### 9. [P3/advisory] 单 key 解密使 key 轮换 fail-closed——本 diff 扩列扩大爆炸面
- `AesGcmColumnEncryptor` 单 key 解密；routing/definition 加密后，轮换（Deferred）时旧 key 密文不可读。plan 已 Deferred，记录即可。跨 reviewer：adversarial + security + correctness（residual）。

### 10. [P3/advisory] 聚合端点 N+1 + 无分页放大（admin 遍历全部）
- plan U3 与 Javadoc 已记 Deferred「SQL JOIN 单查询优化」。adversarial 另提醒无 LIMIT/分页的滥用放大面。建议合并到 #1 的「单 SQL JOIN」一并解。

### 11. [P3/advisory] 聚合端点无访问权返回 200 空数组 vs per-wf 403——同一 API 区语义分歧
- plan 明示「空数组非 403 防泄漏存在性」，但与 `ApprovalController#pending` 的 403 相反。有意设计需在接口文档显式标注（两端口令不一致）。api-contract + agent-native。

### 12. [P3/advisory] `ApprovalCenterView.status` 用 String、姊妹 `ApprovalView` 用 ApprovalStatus 枚举——类型漂移
- 线格式暂一致（都大写序列化），但枚举改名/加值时两 DTO 一处跟着变一处不变。建议对齐枚举类型。api-contract。

### 13. [P3/advisory] `toWorkflowSummary` 保留 `raw as WorkflowStatusUi` 无校验 cast
- 后端新增状态会静默落进四列 lookup 的 undefined 桶——上现 bug（AWAITING_APPROVAL 丢失）正是此模式造成。maintainability residual。

### 14. [P3/advisory] 既有明文行不 backfill 加密——「加密覆盖新写、不覆盖存量」
- V8 只转型，明文行永久明文。密文形态对 read 兼容，但「系统性方案」声称的「5 处敏感列全加密」实为「对新写全加密」。deployment-verification 提示。记录即可（plan 未承诺 backfill）。

### 15. [P3/advisory] `agentflow-starter/pom.xml` 用 maven-surefire-plugin + environmentVariables 塞 `AGENTFLOW_ENCRYPTION_KEY` 进测试 JVM
- 为让 starter 测试可用 strict 构造，pom 给 surefire 配了 env 注入。learnings 提醒：确认该插件配置与仓库既有 build/jest 无冲突（verify 断链风险）。记录即可。

### 16. [P3/advisory] H2 手建表 PK/索引与生产漂移
- `PostgresCheckpointManagerEncryptionTest` 手建 `workflow_id` 单列 PK，而 V4/V5 生产是 `(workflow_id, round)` 复合 PK；`workflow_definitions` 测试表缺 `(workflow_name, created_at DESC)` 索引。只读测试低危，但若未来加 upsert 方向测试会静默失败。learnings 建议注释标注漂移。

---

## testing_gaps（评审指出的未被测试锁住的缺口）

- 聚合端点 500 掩盖（#1）无测试：单 wf corrupt 行应返回 partial 而非整个 500（adversarial）；`listPendingApprovals` 应区分 5xx vs 网络不可达（adversarial）。
- `workflowName` 无端到端断言（#2 恒 null 未被测试网住）；修复后需在 creator 用例断言 `node.get("workflowName")` 非空（correctness、api-contract、maintainability）。
- 写路径密文形态无非 env 门控测试（#5）——H2 不跑 ON CONFLICT；需 CI PG service 或 FakeJdbcTemplate seam（testing、data-migration、project-standards）。
- 缺 key fail-closed 抛错无独立单测（#4）——`ColumnEncryptorsTest` build seam 或独立构造断言（project-standards、testing）。
- `decryptRaw` 空/损坏密文未测；`AesGcmColumnEncryptor` 截断/畸形 `AESGCM:` 前缀用例缺失（testing、security、adversarial）。
- UI `decideApproval` 乐观移除后失败回滚的竞态（deciding 中再点）未测（maintainability、testing）。
- 跨端点可见域一致性（per-wf vs 聚合）无对拍断言（api-contract）。

---

## deployment 注意（deployment-verification-agent）

- **blocking_pre_deploy**：CI 必须让两个真 PG IT 实跑（非 skip）；`AGENTFLOW_ENCRYPTION_KEY` 必须在首次 boot 前存在且各节点一致；V8 USING 对齐 V7。
- **verification_queries**：`SELECT ... FROM workflow_routing_decisions WHERE decisions NOT LIKE 'AESGCM:%'`（迁移后应为空）；`information_schema.columns` 确认列型 TEXT。
- **rollback_caveats**：一旦写入密文，旧代码 `?::jsonb` INSERT 失败 + 读返回乱码 → 回滚须在零密文窗口内 restore-from-backup。
- **monitoring**：首次加密部署 watch ① startup miss key ② AES-GCM 解密失败 spikes ③ 明文残留经未解密 handler 回写。
- **risk_rating**：**MEDIUM**——迁移 + 密文写路径在本地未动手验证，CI 需补。

---

## 建议修复顺序

> **本轮已修复（2026-08-24）**：#1 P1 + #2/#3/#4/#5/#6/#7 P2 全部落地（见各条 ✅）。剩余 P3/advisory（#8–#16）为记录项，不阻断；deployment 注意项留待真 PG 环境验证。