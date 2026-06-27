## Open Questions

| 编号 | 问题 | 状态 | 影响 | 临时方案 |
|:---|:---|:---:|:---|:---|
| OQ-1 | Java 21 Virtual Threads 在 StructuredTaskScope 中是否稳定？channel-merge/recovery 语义是否在 Phase 1 前验证？ | 待验证 | U2 | Week 1 Day 1-2 做 BSP Spike（**扩成端到端**：3 节点并行 + barrier 合并 + 崩溃/恢复往返），验证 VT + channel-merge + 两级 recovery 语义；VT 不稳定降级为 CompletableFutures |
| OQ-2 | Spring AI 2.0 → 2.1 Breaking Change 有多大？ | 持续监控 | U3 | 锁定 2.0.0 GA；适配器层最大程度隔离；CI 跑 2.0/2.1 snapshot 双 profile |
| OQ-3 | 国产 LLM 稳定性（通义千问、DeepSeek）在生产环境的表现？ | 待验证 | Demo | 用 Spring AI 抽象层；不稳定时 fallback 到更稳定模型；设置重试和降级 |
| OQ-4 | WorkflowContext JSONB 序列化复杂类型的精度问题？ | 待验证 | U5 | 仅允许 JSON 原生类型 + 显式注册的 @SerializableType（Instant、BigDecimal 内置支持）；用 snapshot test 验证往返无损 |

### From 2026-06-28 review

> 以下条目来自两轮 ce-doc-review 的 Defer 决策（18 条），按严重度排列。这些是判断类问题，需在动工前拍板；其中标 **⚠️ 动工前必答** 的 4 条不解决会直接撞墙。

#### P1 — 动工前重点

- ✅ **v4.3 已解决** Async node-level flush leaves a pre-barrier crash window defeating R3 — U5 / R3 / KTD-3 (adversarial, confidence 75)

  The flush-then-barrier invariant only drains the queue AT barrier time — a crash after a node completes but before the flush window fires (and before barrier) loses that COMPLETED output, so recovery re-executes and re-bills the LLM, violating R3's "立即保存" and the time-sequence diagram's crash-after-C1 claim. Open: make COMPLETED node-output writes synchronous (write-through), keep async batching for telemetry only.
  → **Resolved in v4.3（方案 B）**：COMPLETED 同步写 + Semaphore(max=20) 限流，异步批量仅留 telemetry。见 `05-implementation-units.md` U5。

- ✅ **v4.3 已解决** U13 (P0) depends on U12 (P1); Go/No-Go cut orphans P0 acceptance — Implementation Units / U13 + Risks (scope-guardian, confidence 75)

  U13 is P0 (uncuttable) yet depends on U12 (P1) and accepts "3 个 Demo 全部跑通". If the gate cuts P1 demos, the P0 delivery unit has no fallback acceptance. Open: restate U13 Dependencies as engine core (U2/U3/U5/U14); change acceptance to "≥1 Demo (U10) 跑通 + Starter/Docker 一键拉起", 3-Demo as stretch.
  → **Resolved in v4.3（方案 A）**：U13 Dependencies 改为 U2/U3/U5/U14（引擎核心 + 安全），验收改为"≥1 Demo（U10）跑通 + Starter/Docker 一键拉起"，3-Demo 降为 stretch。见 `05-implementation-units.md` U13。

- ✅ **v4.3 已解决** No tool-level authorization on YAML-submitted workflows — R21 / U14 / KTD-7 (security-lens, confidence 75)

  Any authenticated API key holder can submit YAML referencing any registered @Tool (财务数据库查询, 合规 API 查询), invoking privileged DB/external-API tools and exfiltrating data via workflow results. R21 only checks API key + ownership, never authorizing which tools a caller may reference. Open: per-caller tool allowlist enforced at YAML node-registration time.
  → **Resolved in v4.3（方案 A）**：新增 `CallerToolAllowlist`（per-caller，config/env 硬编码），R21 补工具级授权，YAML 解析期校验 `@Tool`。见 `02-requirements.md` R21 + `05-implementation-units.md` U14。

- **From-scratch rebuild premise doesn't answer "why not extend LangGraph4j"** — Problem Frame / Interview Value (product-lens + adversarial, confidence 100)

  The plan names its own top interviewer question but answers with "从0复现展示深度", never weighing fork/extend LangGraph4j (1.7k stars, active) as the buy-vs-build alternative. An interviewer can read rebuild-as-NIH, inverting the intended signal.

- **Per-workflow ownership has no data store in zero-infra (InMemory) mode** — U14 / R18① / R21 (security-lens, confidence 100)

  WorkflowOwnershipChecker queries workflow_executions.created_by, which only exists in the Postgres schema; InMemoryCheckpointManager (v1 default dev mode) has no equivalent store — IDOR protection becomes mode-dependent with no documented boundary.

- **No API Key registry, issuance, or validation source defined** — R21 / U14 (security-lens, confidence 100)

  ApiKeyAuthFilter must compare X-API-Key against a known-good set, but the plan never specifies where valid keys live, how they're issued/rotated/looked up. created_by is a hash with no registry to validate against — R21 is unimplementable as written.

- **R20 agentflow-archetypes has no implementation unit delivering it** — Requirements / U13 (scope-guardian, confidence 100)

  R20 is a stated requirement but no unit's Files/Approach produce agentflow-archetypes — U13 lists R20 yet ships only Starter/Docker/docs files. The "delivered scope" claim is false.

#### P2 — 演示前定即可

- ✅ **v4.3 已解决** R14 promises old-version execution but no store holds the old YAML definition — R14 / U8 (adversarial, confidence 75)

  R14/U8 promise "旧版本实例按旧版本定义执行到结束" after a version bump, but only the version string is persisted — the full DAG/YAML definition is not stored. The engine loads YAML from classpath, which after a bump holds the NEW definition, so an in-flight old-version instance that crashes/recovers loads the new DAG. R14's promise is unbacked. Open: workflow_definitions table keyed by (name, version), written at submission.
  → **Resolved in v4.3（方案 A）**：新增 `workflow_definitions` 表 + `WorkflowDefinitionStore` + `V3__workflow_definitions.sql`，Recovery/retry 按 version 取定义，不从 classpath 读。见 `05-implementation-units.md` U8 + `04-high-level-design.md` ER 图。

- ✅ **v4.3 已解决** cancel() defaults to noop, so timeout/cost-control rests on an unenforced agent contract — KTD-6 / U4 (adversarial, confidence 75)

  KTD-6 declares `default void cancel(...) { /* noop */ }` while the engine calls cancel() on timeout and the risk table flags LLM-cost 55%. With noop, a timed-out LLM HTTP call is not aborted — the VT stays blocked, the model keeps streaming, tokens keep billing, undermining R10 and the cost mitigation. Open: require LLM agents to wire cancel() to interrupt the VT / abort the ChatModel HTTP request; disallow noop-cancel LLM agents in production profile.
  → **Resolved in v4.3（方案 C）**：`SpringAiAgentAdapter` 覆盖 `cancel()` 接底层 HTTP 中断（`Future.cancel(true)`/RestClient abort），KTD-6 加覆盖约束，生产 profile 启动校验。见 `03-key-technical-decisions.md` KTD-6 + `05-implementation-units.md` U3/U4。

- **"七三开 (后端70%+Agent30%)" split not borne by unit inspection** — Interview Value / Problem Frame (product-lens, confidence 75)

  R13 (Mock) and R14 (SemVer versioning) are classed as "Agent 30%" but are backend/dev-tool concerns; of 15 units only U3 + KTD-7 are genuinely Agent work → true split ~85/15. Central claim fails a unit-count check.

- **U14 (Week 5-6) depends on U7 (Week 6), start-before-dependency-ready** — Implementation Units / U14 (scope-guardian, confidence 75)

  U14 declares dependency on U7 (Week 6) but is scheduled Week 5-6 — U14 must begin before its dependency's window closes. Ownership checks need U7's trace endpoint, so U14 either blocks on U7 or ships without the endpoint to protect.

- **Reducer abstraction built but no demo exercises channel conflict** — KTD-1 / U2 / R15-R17 (adversarial, confidence 75)

  ChannelReducer ships 4 strategies + custom, but all three demos write to disjoint channels — the conflict-resolution path is never exercised by any acceptance test. Abstraction earns complexity budget without validation.

- **KTD-1 BSP rationale defeats a strawman, not Actor/CSP** — KTD-1 (adversarial, confidence 75)

  BSP rationale only compares against "CompletableFuture + manual locking" (a weak alternative), ignoring Actor/CSP models that also eliminate races via message passing — the anticipated "why BSP not Actor?" interview question has no grounded answer.

- **Cost-risk "90% dev time mock" contradicts U9's Week 7 P1 schedule** — Risks / U9 (product-lens, confidence 75)

  The HIGH-severity LLM-cost risk is mitigated by "90% 开发时间用 mock", but U9 (the only unit delivering mock_response-driven agents) is P1-cuttable and scheduled Week 7, after Phase 1 (Weeks 1-5) where the cost risk bites. At most ~40% of dev time can use mock; if U9 is cut, the mitigation evaporates. Open: move a minimal mock capability into U3 (Week 3-4) or restate the mitigation as unit-test stubs.

- **Spring AI 2.1 risk mitigation cites v1.1 "distributed" as v1 differentiator** — Risks (product-lens, confidence 75)

  The competitive-risk mitigation names "YAML DSL + 分布式特性" as differentiation core against Spring AI 2.1, but distributed mode is v1.1 — v1 is single-node. v1's actual differentiator is only YAML DSL, thin given Spring AI 2.0 already ships 5 Agentic Patterns + Subagent. Open: cite v1-present differentiators (two-level Checkpoint + Recovery, BSP barrier, YAML + JSON Schema validation).

- **No per-request DAG size or token budget bound on POST /workflows** — U14 / R21 / R10 (security-lens, confidence 75)

  POST /workflows accepts arbitrary YAML; an authenticated caller can submit a DAG with thousands of nodes, spawning unbounded Virtual Threads, exhausting HikariCP (20 conn) and running unlimited LLM cost before any barrier. R10's budget_exceeded is post-hoc, not preventive. Open: WorkflowSubmissionGuard rejecting node count > max or estimated cost > budget before execution (422).

- **R10 "budget 告警" has no threshold source; budget_exceeded counter can't fire** — R10 / U7 (scope-guardian, confidence 75)

  R10 promises "Counter + budget 告警" and U7 ships `cost.budget_exceeded{workflow}`, but no budget limit is defined in YAML node config or properties — the alert can never trigger as specified. Open: add per-workflow `budget_tokens`/`budget_cost` YAML field, wire the counter to compare against it.

- **Agent 30% @Tool work overlaps InterviewCoach's Tool Calling claim** — Interview Value / Problem Frame (product-lens, confidence 75)

  The plan declares the three projects complementary and "不与 InterviewCoach 的 AI 能力维度重复", yet lists "@Tool 自动注册" under AgentFlow's Agent 30% — a Tool Calling capability InterviewCoach already claims. An interviewer comparing both resume lines can ask why two projects claim the same dimension. Open: articulate the layer boundary — framework-level tool wiring (AgentFlow) vs application-level agent tool use (InterviewCoach).

---

## Risks & Dependencies

| 严重度 | 风险 | 概率 | 影响 | 缓解 |
|:---:|:---|:---:|:---:|:---|
| 🔴高 | **BSP 引擎复杂度超预期**（super-step barrier、channel 合并、Virtual Threads 约束） | 70% | 整个 Phase 1 延期 | Week 1 Day 1-2 做 BSP Spike 最小原型，跑不通立即降级为简化版 BSP（仅独立 channel 写入，无 Reducer） |
| 🔴高 | **10 周时间线无缓冲**（关键路径 U1→U2→U3→U7→U14→U10→U11→U12→U13） | 60% | 无法按期交付 | Week 5 设 Go/No-Go Gate：引擎稳定则继续，不稳定则砍 U6/U8/U9 到 v1.1（**U14 承载 R21/R22 P0 安全，不可砍**；砍单只含 P1/P2，详见 Unit Priority 矩阵） |
| 🔴高 | **Spring AI 2.0 → 2.1 Breaking Change** | 50% | 适配器层返工 | 适配器层最大程度隔离；CI 跑双 profile；SPI 加载适配器 |
| 🔴高 | **LLM 调用成本失控**（重试机制 × 并发节点） | 55% | 开发阶段 token 费爆炸 | 90% 开发时间用 mock 模式；生产 Demo 限 token 预算；告警阈值 |
| 🔴高 | **Checkpoint 部分完成节点语义冲突** | 40% | 恢复时数据不一致 | 两级 checkpoint（节点级 + barrier 级）；snapshot test 验证往返无损 |
| 🟡中 | **LangChain4j 适配器工作量低估** | 45% | v1 范围膨胀 | v1 只做 Spring AI 适配器；LangChain4j 推迟到 v1.1 |
| 🟡中 | **国产 LLM 不稳定** | 40% | Demo 演示卡壳 | Spring AI 抽象层；fallback 机制；重试 + 降级 |
| 🟡中 | **Spring AI 2.1 自带 Multi-Agent 编排能力**（竞品风险） | 30% | 产品差异化消失 | 持续关注 Spring AI 路线图；AgentFlow 的 YAML DSL + 分布式特性是差异化核心 |
| 🟡中 | **Context 序列化爆炸**（RAG Agent 让 context 膨胀到 MB 级） | 30% | Checkpoint 性能下降 | v1 限制 context 大小（<1MB 默认）；v1.1 加增量 checkpoint |
| 🟢低 | **Virtual Threads pinned to carrier thread** | 20% | BSP 并行度下降 | 用 `-Djdk.tracePinnedThreads=full` JVM 参数检测；避免 synchronized 阻塞 |

---

## Success Metrics

| 指标 | 目标 | 衡量方式 |
|:---|:---|:---|
| 后端工程深度展示 | 15 个实现单元覆盖并发模型/持久化/容错/可观测/DSL/Starter/安全 七大维度 | 单元维度覆盖率（七大维度均有 U 落地） |
| 执行正确性 | 三个 Demo 工作流 100% 通过 | 单元测试 + 集成测试覆盖率 > 80% |
| 容错覆盖率 | 5 类故障场景全部有处理 | 故障注入测试 |
| 可观测性 | trace 完整展示 + 指标可监控 | Grafana 可视化验证 |
| 文档完整性 | README/Tutorial/Troubleshooting 齐全 | 文档评审 |
| 10 周交付 | 所有 P0 功能完成 | Week 5 Go/No-Go Gate |
| 设计权衡可复现性（v4.2 新增） | 七大维度每个都有可讲的设计权衡点（vs 显式备选，如 BSP vs Actor/CSP、两级 vs 单级 checkpoint） | 维度-权衡点覆盖（自评 + 模拟追问），避免"单元数=深度"的自指 |

> **v4.1 注**：原"开发效率提升 90%+（800 行 vs 50 行）"指标已移除——v4 Problem Frame 重写为"从0复现展示后端能力"后，"效率提升"不再是项目价值主张，且"800 行"基线无来源、不可验证。改为"后端工程深度展示"指标，与七三开定位一致。

← 返回 [`00-overview.md`](./00-overview.md)
