# feat: R22 加密扩列（routing_decisions + workflow_definitions）+ 审批 Web UI

> 类型：feat ｜ 日期：2026-08-23 ｜ 深度：Deep（跨 security / checkpoint / version / api / ui 五区）
> 定位：把 R22 从「只加密 checkpoint 一处」升级为「敏感列系统性加密方案」（v1.1 R22 收尾），把 HITL 审批从「curl-only」升级为「Web 可操作」（v2 HITL 交付闭环）。交付后全量 `mvn verify` 绿 + UI `npm test`/`build` 绿 → ce-code-review → push。
> 前置：U7 R22 已有的 `ColumnEncryptor` SPI（`com.agentflow.security`：Noop/AesGcm AES-256-GCM 自描述前缀 + legacy 明文兼容 + `ColumnEncryptors` 工厂 fromEnv 宽松/fromEnvStrict fail-closed）与 `PostgresCheckpointManager` 条件加解密模式是本计划的复用地基；U6 `ApprovalController`（GET pending 精简投影 + POST 决策、decidedBy 服务端推导、创建者/admin 门控）是审批 UI 的既有 API 契约。

---

## Summary

两件共享「在同一套既定模式上扩面」哲学的功能：

1. **R22 加密扩列** —— 把列级静态加密从 checkpoint 敏感列（node output / channel / 审批载荷）扩展到剩余两处 JSONB 敏感数据：`workflow_routing_decisions.decisions`（路由决策，含上下文派生边信息）与 `workflow_definitions.definition`（完整工作流定义，含 prompt_template 业务话术）。两处都复制 `PostgresCheckpointManager` 已验证的「可空 ColumnEncryptor + toEncryptedJson/decryptRaw 边界」模式。**同时补真 PG IT 覆盖**（密文形态证据），使「系统性方案」叙事有测试证据链。
2. **审批 Web UI** —— React UI 加第 6 个 Tab「审批中心」：列待批审批（真实 API 优先 + mock fallback，与现有 5 Tab 同模式）、APPROVE/REJECT 决策、决策后跳转看板看终态。看板补「AWAITING_APPROVAL」状态分桶（新第 4 列或并入进行中并加徽标——设计决策见 KTD-U2），让「提交→暂停→审批→续跑」全链路在 UI 可见。

---

## Problem Frame

- **R22 扩列**：`docs/ROADMAP.md` §3 把「checkpoint 列级加密」标 ✅ 但 residual 记录「R22 加密扩到 routing_decisions」为 Deferred。现状：`workflow_routing_decisions.decisions`（JSONB，存已走边列表，条目含节点输出派生的路由信息）与 `workflow_definitions.definition`（JSONB，存完整 DSL——`prompt_template` 含业务敏感话术、`mock_response` 含演示数据）**明文落库**。面试叙事缺口：「列级加密」若只覆盖一处表，被追问「哪些列？为什么只有这些？」时答案是「做了个 demo」而非「系统性方案」。
- **审批 UI**：HITL U4–U6 已交付引擎暂停/恢复 + REST 端点，但操作路径是 curl/Postman——「人机协同」的「人」没有界面。`docs/plans/2026-08-20-001-feat-hitl-r22-rag-plan.md` 明确把「审批 Web UI」记 Deferred；ROADMAP §4 HITL 行同样标注。UI 现有 5 Tab 无审批入口，且 `api.ts` 状态归一把 `AWAITING_APPROVAL` 静默丢进 `failed`（`raw as WorkflowStatusUi` 不含该值 → 三列分桶 lookup undefined）——**这是实际 bug**，不只是缺功能。

### 需求反面（本计划不做什么）

- **不**做 R22 key 轮换（多 key 版本前缀 `AESGCM:v2:` 体系）——记 ROADMAP 后续。
- **不**做跨节点（Kafka 模式）审批恢复——既有 Deferred 不变。
- **不**做审批 UI 的轮询自动刷新（手动刷新按钮 + 决策后重拉；轮询留后续）。
- **不**给 `workflow_definitions` 做按需列拆分（不把 prompt 拆单独列）；整列加密。
- **不**做 UI 审批的批量操作/评论/转派——单审批单决策按钮。
- **不**做 E2E 浏览器测试（webapp-testing）——Vitest 单测覆盖到 api 层契约与组件渲染。

---

## Key Technical Decisions

### KTD-S1 — 扩列复用「可空 ColumnEncryptor + 边界加解密」模式，零引擎/DSL 感知
`PostgresWorkflowDefinitionStore` 构造器加可空 `ColumnEncryptor`（默认 Noop，向后兼容——与 `PostgresCheckpointManager` 同一模式）；`save` 走 `encrypt(toJson(def))`、`find`/`findLatest` 走 `decrypt(raw)`。`PostgresCheckpointManager.saveRoutingDecisions/findRoutingDecisions` 从 `toJson` 切 `toEncryptedJson`/`decryptRaw`（**类内已有 helper，纯切线**）。`InMemoryWorkflowDefinitionStore`/`InMemoryCheckpointManager.routingDecisions` 不加密（内存明文，与 dev 模式一致）。**Why**：密文自描述（`AESGCM:` 前缀）+ legacy 明文兼容已内建于 SPI，扩列零新概念；引擎/DSL/API 完全零改动（KTD-E2 延续）。

### KTD-S2 — 列型迁移：两张表 JSONB→TEXT（V8，对齐 V7 先例）
密文非合法 JSON，PG 侧需列型 `JSONB→TEXT`。新迁移 `V8__r22_encrypt_routing_and_definitions.sql`：
```sql
ALTER TABLE workflow_routing_decisions ALTER COLUMN decisions TYPE TEXT;
ALTER TABLE workflow_definitions ALTER COLUMN definition TYPE TEXT;
```
同步改两条 INSERT 的 `?::jsonb` cast（routing 的 INSERT 已有 `?::jsonb`，definitions 的 INSERT 也有）→ 直接去 cast（TEXT 列接 String 参数无需 cast）。**Why not 新增列**：V7 已确立「原列转型 TEXT」先例，不加密文影子列避免读路径分叉。**注意**：`?::jsonb` 在 TEXT 列上仍合法（TEXT→JSONB cast 后存回 TEXT 会丢密文），必须去掉；H2 兼容表测试同步。

### KTD-S3 — 定义存储的加密门控：生产 wiring 同样 fail-closed
`AgentFlowAutoConfiguration` 目前**不注册** `PostgresWorkflowDefinitionStore`（demo-api 用 InMemory，生产用 starter 的场景由用户自配）——扩列后 starter 补条件注册：`agentflow.mock.enabled=false` + DataSource 存在时注册 `PostgresWorkflowDefinitionStore(dataSource, ColumnEncryptors.fromEnvStrict())`，与 checkpoint manager 同一 strict 纪律（**杜绝「checkpoint 加密了、定义还明文」的半吊子状态**）。demo-api 维持 InMemory（demo 不加密，明示）。
**Why**：如果 starter 只加密 checkpoint 而定义存储默认无加密接线，「系统性方案」叙事在 wiring 层就漏了。

### KTD-S4 — 审批 UI = 新 Tab「审批中心」，全列表端点 + 既有 per-wf 端点
UI 需要跨工作流的待批聚合视图，但既有 `GET /api/workflows/{id}/approvals/pending` 是 per-workflow 的。方案：**后端加 `GET /api/approvals/pending`（跨工作流聚合）**，复用 `findPendingApprovals` 按 `listByCreatedBy(caller)`（admin 传 null=全部）遍历聚合，投影复用 `ApprovalView`（精简投影纪律延续——不下发 payload/snapshot）。门控：每条审批仍按创建者/admin 过滤（非本人非 admin 不可见）。UI 新组件 `ApprovalCenter.tsx`：列表（workflowId 简写/nodeId/description/createdAt）+ 每行 APPROVE/REJECT 按钮 + 决策后乐观移除 + toast + 失败回滚重拉。
**Why not per-wf 轮询拼合**：前端拼 N 个工作流的 pending 请求需要先知道哪些 wf 有审批——没有索引端点，拼合不可行。聚合端点是审批中心的最小充分后端。

### KTD-U1 — `AWAITING_APPROVAL` 状态修复 + 看板呈现
`types/index.ts` 的 `WorkflowStatusUi` 加 `'awaiting_approval'`；`api.ts` `toWorkflowSummary` 归一映射 `awaiting_approval` 单列直通（不并入 running——它是稳定可操作态，值得独立列）。看板三列 → 四列（进行中/待审批/已完成/失败），`Dashboard.tsx` `boardColumns` 扩一项 + `statusBadge` 扩样式（琥珀色系对齐现有 tailwind 配置：`bg-warning-light text-warning` 若无则用 amber）。表格状态列同样归一。
**Why**：待审批是 HITL 的核心可观测状态，与 running 混淆会让用户错过审批；独立列 = 「审批需要人」在界面上大声说出来。

### KTD-U2 — mock fallback 数据补审批场景
`mockData.ts` 加 `mockPendingApprovals: ApprovalSummary[]`（2-3 条演示数据：供应商风险评估的金额审批 + 合同审核的条款审批）；`api.ts` 加 `listPendingApprovals()`/`decideApproval()` 端点封装，`withMockFallback` 模式与既有端点一致（后端不可达 → mock 数据 + 徽标；**决策 POST 无 mock fallback**——写操作不能静默假成功，失败 toast 报错，与 retry 的处理对齐）。`mockWorkflows` 补一条 `awaiting_approval` 状态的看板数据。

### KTD-U3 — 测试策略：H2 单测证加密路径 + 真 PG IT 证密文形态
- `PostgresWorkflowDefinitionStoreEncryptionTest`（H2 手建 TEXT 兼容表 + skipMigrations 类似 seam——该类无 Flyway，直接 H2）：Noop 明文回归 / AesGcm 密文不含明文子串 + 读还原 / legacy 明文兼容。对齐 `PostgresCheckpointManagerEncryptionTest` 三段式。
- routing 决策加密：`PostgresCheckpointManagerEncryptionTest` 扩 2 例（H2 手建 routing 兼容表，INSERT 无 `ON CONFLICT...WHERE` 阻碍——routing 的 upsert 是 `ON CONFLICT DO UPDATE` 无 WHERE，H2 可跑）。
- 真 PG IT：`PostgresCheckpointManagerIT` / 新增 `PostgresWorkflowDefinitionStoreIT`（Failsafe，env 门控跳过）验证 V8 迁移后真 PG 列型 + 密文形态（原始列 startsWith `AESGCM:` 不含明文）。
- API：`ApprovalControllerTest` 补聚合端点 3 例（创建者只见自己 / admin 见全部 / 非创建者非 admin 空）。
- UI：`api.test.ts` 补 `listPendingApprovals`/`decideApproval` 契约 + mock fallback；`ApprovalCenter.test.tsx` 渲染列表/决策按钮点击回调/空态；`Dashboard.test.tsx` 补 `awaiting_approval` 分桶。

---

## Implementation Units

### U1: routing_decisions 加密（core）
- **Goal**：`PostgresCheckpointManager` 的 routing 决策存取走加密路径 + V8 迁移 + H2 测试
- **Files**：
  - Modify: `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java`（saveRoutingDecisions 切 toEncryptedJson；findRoutingDecisions 切 decryptRaw；INSERT 去 `?::jsonb`）
  - Create: `agentflow-core/src/main/resources/db/migration/V8__r22_encrypt_routing_and_definitions.sql`
  - Modify: `agentflow-core/src/test/java/com/agentflow/engine/checkpoint/PostgresCheckpointManagerEncryptionTest.java`（+2 例）
- **Approach**：纯切线——helper 已在类内；INSERT SQL 的 `?::jsonb` cast 去掉（TEXT 列）
- **Test scenarios**：H2 手建 `workflow_routing_decisions` 兼容表（TEXT decisions 列）：① Noop 明文 JSON 落列（回归）② AesGcm 密文 startsWith 前缀不含明文子串 + findRoutingDecisions 解密还原
- **Verification**：`mvn -pl agentflow-core test` 绿；新 2 例过
- **Execution note**：先写测试（H2 表建好后断言密文形态），再切实现——测试先行锁行为

### U2: workflow_definitions 加密（core + starter）
- **Goal**：`PostgresWorkflowDefinitionStore` 条件加密 + starter 生产 fail-closed 注册
- **Files**：
  - Modify: `agentflow-core/src/main/java/com/agentflow/version/PostgresWorkflowDefinitionStore.java`（加可空 encryptor 构造 + save/find/findLatest 切线 + 去 `?::jsonb`）
  - Create: `agentflow-core/src/test/java/com/agentflow/version/PostgresWorkflowDefinitionStoreEncryptionTest.java`
  - Create: `agentflow-core/src/test/java/com/agentflow/version/PostgresWorkflowDefinitionStoreIT.java`（Failsafe 真 PG，env 门控）
  - Modify: `agentflow-starter/src/main/java/com/agentflow/starter/AgentFlowAutoConfiguration.java`（生产条件注册 Postgres store + strict encryptor）
  - Modify: `agentflow-starter/src/test/...`（装配测试若有——检查 StarterIntegrationTest 是否需扩）
- **Approach**：对齐 `PostgresCheckpointManager` 的构造器模式（public 2-arg 不变 + 新 public (DataSource, ColumnEncryptor)）；starter `@ConditionalOnProperty(mock.enabled=false) + @ConditionalOnBean(DataSource)` 注册
- **Test scenarios**：H2 三段式（Noop 回归/AesGcm 密文+还原/legacy 明文兼容）+ starter 装配断言（生产模式注入的是 strict 构造的 store）
- **Verification**：`mvn -pl agentflow-core,agentflow-starter test` 绿
- **Execution note**：test-first（加密三段式先写）

### U3: 跨工作流待批聚合端点（api）
- **Goal**：`GET /api/approvals/pending` 聚合 + 门控
- **Files**：
  - Modify: `agentflow-api/src/main/java/com/agentflow/api/ApprovalController.java`（新根路由方法——注意现有类是 `@RequestMapping("/api/workflows/{workflowId}/approvals")`，聚合端点路径不同，加 `@GetMapping` 绝对路径或拆新 controller；**推荐拆 `ApprovalCenterController`** 避免路径冲突）
  - Create: `agentflow-api/src/test/java/com/agentflow/api/ApprovalCenterControllerTest.java`（或并入既有测试类）
- **Approach**：新建 `ApprovalCenterController`（`/api/approvals`）：`GET /pending` → `listByCreatedBy(callerId)`（caller 是 admin → null=全部）遍历 `findPendingApprovals` 聚合；复用 `ApprovalView` 投影 + `ApprovalView` 加 `workflowId` 字段（聚合视图需要知道属于哪个 wf——现投影无 wfId）；每条按创建者/admin 过滤
- **Test scenarios**：① 创建者只见自己 wf 的待批 ② admin 见全部 ③ 无审批返回空数组 ④ 非创建者非 admin → 空（不是 403——聚合端点不知道你要看谁，非 admin 只看自己的，天然无泄漏）⑤ 401 无 key
- **Verification**：`mvn -pl agentflow-api test` 绿
- **Execution note**：ApprovalView 加字段是 breaking change 吗——现 UI 未消费该类型，后端消费方是 pending 端点自身；加字段向后兼容（Jackson 序列化多一个字段）

### U4: 审批中心 Tab + 状态修复（ui）
- **Goal**：第 6 Tab 审批中心 + `AWAITING_APPROVAL` 四列看板 + api 封装 + mock 数据
- **Files**：
  - Modify: `agentflow-ui/src/types/index.ts`（WorkflowStatusUi 加 awaiting_approval；新 ApprovalSummary/ApprovalDecisionRequest 类型）
  - Modify: `agentflow-ui/src/lib/api.ts`（listPendingApprovals/decideApproval 封装 + toWorkflowSummary awaiting_approval 直通）
  - Modify: `agentflow-ui/src/lib/mockData.ts`（mockPendingApprovals + awaiting_approval 看板数据）
  - Create: `agentflow-ui/src/components/ApprovalCenter.tsx`
  - Modify: `agentflow-ui/src/components/Layout.tsx`（navSections 加「审批中心」——放「操作」组，icon `ShieldCheck`）
  - Modify: `agentflow-ui/src/App.tsx`（tab 路由）
  - Modify: `agentflow-ui/src/components/Dashboard.tsx`（四列 + statusBadge + 表格状态列）
  - Modify: `agentflow-ui/src/lib/api.test.ts` / `agentflow-ui/src/components/Dashboard.test.tsx`（补测试）
  - Create: `agentflow-ui/src/components/ApprovalCenter.test.tsx`
- **Approach**：对齐 Dashboard 的 data-fetch 模式（useEffect + withMockFallback + MockSourceBadge）；决策 POST 失败走 ApiError 分支 toast（不 mock）；Layout navSections「操作」组加第 4 项；Dashboard 三列变四列（`grid-cols-4`，断点 `max-[960px]:grid-cols-2`）
- **Test scenarios**：① api 层：listPendingApprovals 契约（X-API-Key header/路径/超时/mock fallback）+ decideApproval 无 mock fallback（网络错直接抛）② Dashboard：awaiting_approval 独立分桶计数 ③ ApprovalCenter：渲染 mock 数据行、APPROVE 点击触发 decideApproval 调用、空态提示
- **Verification**：`npm test` 绿 + `npm run build` 绿（tsc strict）
- **Execution note**：Dashboard 状态修复先行（它是现存 bug），再 Tab

### U5: 文档同步 + 收尾
- **Goal**：CLAUDE.md / ROADMAP / developer-notes 同步；全仓 verify
- **Files**：
  - Modify: `CLAUDE.md`（当前状态段新增本轮）
  - Modify: `docs/ROADMAP.md`（§3 R22 行补扩列 + §4 HITL 行补审批 UI + residual 更新）
  - Modify: `docs/developer-notes/00-interview-arsenal.md`（R22 系统性叙事：哪些列、为什么、fail-closed 纪律；HITL 全链路含 UI）
  - Modify: `docs/developer-notes/01-implementation-rationale.md`（若选型记录需补扩列决策）
  - Modify: `docs/developer-notes/04-glossary.md`（若新术语）
- **Approach**：按「文档随开发同步（强制）」约定，每个 feature 落地即写
- **Verification**：文档存在且交叉引用一致

### U6: ce-code-review + push
- **Goal**：10 reviewer 评审 → 应用高确信修复 → 全绿 → push
- **Approach**：`/ce-code-review mode:agent`；residual 落 `docs/residual-review-findings/`；push origin main（或 feature 分支合 main 再 push——用户指示「推送上去」，合 main 推 main）

---

## Success Criteria

1. **加密系统性**：PG 模式下所有 5 处 JSONB 敏感列（node output / channel / 审批载荷×2 / routing decisions / definition）都走 `ColumnEncryptor` 边界；生产 wiring（starter）checkpoint + definition store 双 strict；密文形态有真 PG IT 证据
2. **审批全链路 UI 可见**：提交含审批节点的工作流 → 看板出现「待审批」列 → 审批中心 Tab 看到待批单 → APPROVE → 看板流转到已完成
3. **向后兼容**：无 key（Noop）行为与现状 byte-level 等价（明文 JSON）；legacy 明文行升级后读得动；InMemory/demo 路径零变化
4. **质量门禁**：全仓 `mvn verify` 绿 + JaCoCo 达标 + `npm test`/`npm run build` 绿
5. **文档**：CLAUDE.md/ROADMAP/developer-notes 同步，面试叙事升级（R22「系统性列加密」+ HITL「全链路含 UI」）

---

## Risks

| 风险 | 概率 | 缓解 |
|:---|:---:|:---|
| `?::jsonb` 残留（routing/definitions SQL 有两处 cast，漏改一处 → TEXT 列存 cast 后的 JSON 字符串） | 中 | H2 测试断言原始列 startsWith `AESGCM:` + 不含明文；grep 兜底检查 |
| V8 迁移在真 PG 上 JSONB→TEXT 转换大表锁表 | 低 | demo 级数据量；生产运维提示记 developer-notes |
| 聚合端点 listByCreatedBy(caller) 遍历 N 工作流 N+1 查询 | 中 | demo 规模可接受；Javadoc 记性能边界；SQL 侧 JOIN 优化记 Deferred |
| ApprovalView 加 workflowId 破坏现有消费方 | 低 | 消费方只有 pending 端点自身 + 测试；Jackson 加字段向后兼容 |
| UI 四列在窄屏挤压 | 低 | `max-[960px]:grid-cols-2` 断点 + 组件 max-w 约束 |
| starter 注册 PostgresWorkflowDefinitionStore 影响既有 mock 模式测试 | 低 | `@ConditionalOnProperty(mock=false)` + matchIfMissing=true 只在无 DataSource 时不注册；StarterIntegrationTest 全绿即证 |

---

## Deferred

- R22 key 轮换（`AESGCM:v2:` 多版本 key 体系）——记 ROADMAP
- 聚合端点 SQL JOIN 优化（单查询替代 N+1）
- 审批 UI 轮询自动刷新 / Web 编辑器（v2 既有 Deferred 不变）
- `workflow_executions.workflow_name` 等非 JSONB 列的加密评估（明文可接受——非敏感，只读列表用）
